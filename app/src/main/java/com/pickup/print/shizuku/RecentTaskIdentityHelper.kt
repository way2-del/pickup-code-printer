/**
 * 读取多任务卡片的标题与图标（TaskDescription）。
 * 微信小程序在 HyperOS 上显示的「蜜雪冰城」+ 雪人图标就来自这里；
 * 普通无障碍往往只有「微信」。
 */
package com.pickup.print.shizuku

import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.util.Log
import com.pickup.print.SourceIdentityHelper
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.FileInputStream
import java.util.regex.Pattern

data class RecentTaskIdentity(
    val packageName: String,
    val label: String?,
    val icon: Bitmap?,
    val topClassName: String?,
    val taskId: Int,
)

object RecentTaskIdentityHelper {
    private const val TAG = "RecentTaskIdentity"
    private const val MAX_TASKS = 40

    fun findMiniOrLabeledTask(packageName: String?): RecentTaskIdentity? {
        val pkg = packageName?.takeIf { it.isNotBlank() } ?: return null
        if (!isReady()) return null
        val tasks = queryRecentTasks().filter { it.packageName == pkg }
        if (tasks.isEmpty()) return null

        val miniClass = tasks.firstOrNull { task ->
            SourceIdentityHelper.looksLikeMiniProgramClass(task.topClassName) &&
                SourceIdentityHelper.looksLikeAppOrMiniName(task.label)
        }
        if (miniClass != null) return miniClass

        return tasks.firstOrNull { task ->
            SourceIdentityHelper.looksLikeAppOrMiniName(task.label)
        } ?: tasks.firstOrNull()
    }

    fun findTopLabeledTask(excludePackages: Set<String> = emptySet()): RecentTaskIdentity? {
        if (!isReady()) return null
        return queryRecentTasks().firstOrNull { task ->
            task.packageName !in excludePackages &&
                !task.packageName.startsWith("com.android.systemui") &&
                !task.packageName.contains("launcher", ignoreCase = true) &&
                SourceIdentityHelper.looksLikeAppOrMiniName(task.label)
        }
    }

    fun queryRecentTasks(): List<RecentTaskIdentity> {
        if (!isReady()) return emptyList()
        val binderTasks = try {
            queryViaActivityTaskManager()
                .ifEmpty { queryViaActivityManager() }
        } catch (e: Exception) {
            Log.w(TAG, "binder query failed: ${e.message}")
            emptyList()
        }
        if (binderTasks.any { SourceIdentityHelper.looksLikeAppOrMiniName(it.label) }) {
            return binderTasks
        }
        // HyperOS 上 binder 有时拿不到 label，dumpsys 里却有 label + iconFilename
        val dumpsysTasks = try {
            queryViaDumpsys()
        } catch (e: Exception) {
            Log.w(TAG, "dumpsys query failed: ${e.message}")
            emptyList()
        }
        if (dumpsysTasks.isEmpty()) return binderTasks
        if (binderTasks.isEmpty()) return dumpsysTasks
        // 合并：同 taskId / 同包优先带 label 的
        val byKey = LinkedHashMap<String, RecentTaskIdentity>()
        (dumpsysTasks + binderTasks).forEach { task ->
            val key = if (task.taskId > 0) "id:${task.taskId}" else "pkg:${task.packageName}:${task.label}"
            val old = byKey[key]
            byKey[key] = when {
                old == null -> task
                SourceIdentityHelper.looksLikeAppOrMiniName(task.label) &&
                    !SourceIdentityHelper.looksLikeAppOrMiniName(old.label) -> task
                task.icon != null && old.icon == null -> task.copy(
                    label = task.label ?: old.label,
                    topClassName = task.topClassName ?: old.topClassName,
                )
                else -> old.copy(
                    label = old.label ?: task.label,
                    icon = old.icon ?: task.icon,
                    topClassName = old.topClassName ?: task.topClassName,
                )
            }
        }
        return byKey.values.toList()
    }

    private fun isReady(): Boolean =
        ShizukuManager.isShizukuRunning() && ShizukuManager.checkSelfPermission()

    private fun queryViaActivityTaskManager(): List<RecentTaskIdentity> {
        val raw = SystemServiceHelper.getSystemService("activity_task") ?: return emptyList()
        val wrapped = ShizukuBinderWrapper(raw)
        val atm = Class.forName("android.app.IActivityTaskManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, wrapped) ?: return emptyList()

        val userIds = listOf(android.os.Process.myUid() / 100000, 0, 999).distinct()
        val out = ArrayList<RecentTaskIdentity>()
        for (userId in userIds) {
            val slice = invokeGetRecentTasks(atm, MAX_TASKS, 0, userId) ?: continue
            val list = slice.javaClass.getMethod("getList").invoke(slice) as? List<*> ?: continue
            list.mapNotNullTo(out) { parseRecentTaskInfo(it) }
        }
        // 补充正在跑的任务（有时比 recents 更能拿到 description）
        out += queryRunningTasks(atm)
        return out.distinctBy { "${it.taskId}:${it.packageName}:${it.label}" }
    }

    private fun queryRunningTasks(atm: Any): List<RecentTaskIdentity> {
        val out = ArrayList<RecentTaskIdentity>()
        atm.javaClass.methods.filter { it.name == "getTasks" }.forEach { m ->
            try {
                val args: Array<Any?> = when (m.parameterTypes.size) {
                    1 -> arrayOf(MAX_TASKS)
                    2 -> arrayOf(MAX_TASKS, false)
                    3 -> arrayOf(MAX_TASKS, false, false)
                    4 -> arrayOf(MAX_TASKS, false, false, 0)
                    else -> return@forEach
                }
                val list = m.invoke(atm, *args) as? List<*> ?: return@forEach
                list.mapNotNullTo(out) { parseRecentTaskInfo(it) }
                if (out.isNotEmpty()) return out
            } catch (e: Exception) {
                Log.d(TAG, "getTasks fail: ${e.message}")
            }
        }
        return out
    }

    private fun queryViaActivityManager(): List<RecentTaskIdentity> {
        val raw = SystemServiceHelper.getSystemService("activity") ?: return emptyList()
        val wrapped = ShizukuBinderWrapper(raw)
        val am = Class.forName("android.app.IActivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, wrapped) ?: return emptyList()

        val userIds = listOf(android.os.Process.myUid() / 100000, 0, 999).distinct()
        val out = ArrayList<RecentTaskIdentity>()
        for (userId in userIds) {
            val slice = invokeGetRecentTasks(am, MAX_TASKS, ActivityManager.RECENT_WITH_EXCLUDED, userId)
                ?: continue
            val list = slice.javaClass.getMethod("getList").invoke(slice) as? List<*> ?: continue
            list.mapNotNullTo(out) { parseRecentTaskInfo(it) }
        }
        return out
    }

    private fun invokeGetRecentTasks(service: Any, max: Int, flags: Int, userId: Int): Any? {
        val clazz = service.javaClass
        clazz.methods.filter { it.name == "getRecentTasks" }.forEach { m ->
            try {
                when (m.parameterTypes.size) {
                    3 -> return m.invoke(service, max, flags, userId)
                    2 -> return m.invoke(service, max, flags)
                }
            } catch (e: Exception) {
                Log.d(TAG, "getRecentTasks fail: ${e.message}")
            }
        }
        return null
    }

    /**
     * dumpsys 回退：能读到 label="古茗茶饮点单" 和 iconFilename=/data/system_ce/...png
     */
    private fun queryViaDumpsys(): List<RecentTaskIdentity> {
        val text = runShell(arrayOf("dumpsys", "activity", "activities")) ?: return emptyList()
        val results = ArrayList<RecentTaskIdentity>()
        // 按 ActivityRecord / Task 块粗切
        val blocks = text.split(Regex("(?=\\* (?:Hist|Task\\{)|taskDescription:)"))
        var lastPkg: String? = null
        var lastClass: String? = null
        var lastTaskId = -1
        val labelPat = Pattern.compile("""taskDescription:\s*label="([^"]*)".*?iconFilename=(\S+)""")
        val pkgPat = Pattern.compile("""packageName=([a-zA-Z0-9._]+)""")
        val cmpPat = Pattern.compile("""mActivityComponent=([a-zA-Z0-9._]+)/\.?([a-zA-Z0-9._]+)""")
        val taskIdPat = Pattern.compile("""\st(\d+)\b""")

        for (block in blocks) {
            pkgPat.matcher(block).let { m -> if (m.find()) lastPkg = m.group(1) }
            cmpPat.matcher(block).let { m ->
                if (m.find()) {
                    lastPkg = m.group(1)
                    lastClass = m.group(2)
                }
            }
            taskIdPat.matcher(block).let { m ->
                if (m.find()) lastTaskId = m.group(1)?.toIntOrNull() ?: lastTaskId
            }

            val lm = labelPat.matcher(block.replace('\n', ' '))
            if (!lm.find()) continue
            val label = lm.group(1)?.takeIf { it.isNotBlank() && it != "null" }
            val iconPath = lm.group(2)?.takeIf { it.isNotBlank() && it != "null" }
            val pkg = lastPkg ?: continue
            if (label == null && iconPath == null) continue
            val icon = iconPath?.let { loadIconFile(it) }
            results += RecentTaskIdentity(
                packageName = pkg,
                label = label,
                icon = icon,
                topClassName = lastClass,
                taskId = lastTaskId,
            )
        }
        Log.d(TAG, "dumpsys parsed ${results.size} labeled tasks")
        return results
    }

    private fun runShell(cmd: Array<String>): String? = ShizukuShell.exec(cmd)

    private fun loadIconFile(path: String): Bitmap? {
        return try {
            val bytes = ShizukuShell.execBytes(arrayOf("cat", path)) ?: return null
            if (bytes.isEmpty()) return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            try {
                FileInputStream(path).use { BitmapFactory.decodeStream(it) }
                    ?.copy(Bitmap.Config.ARGB_8888, false)
            } catch (e2: Exception) {
                Log.d(TAG, "loadIconFile $path fail: ${e.message}")
                null
            }
        }
    }

    private fun parseRecentTaskInfo(info: Any?): RecentTaskIdentity? {
        if (info == null) return null
        return try {
            val taskId = readInt(info, "id", "taskId") ?: -1
            val top = readComponent(info, "topActivity")
            val base = readComponent(info, "baseActivity")
            val orig = readComponent(info, "origActivity")
            val real = readComponent(info, "realActivity")
            val pkg = top?.packageName
                ?: base?.packageName
                ?: orig?.packageName
                ?: real?.packageName
                ?: readIntentPackage(info)
                ?: return null
            val className = top?.className ?: base?.className
            val td = readField(info, "taskDescription")
                ?: callNoArg(info, "getTaskDescription")
            val label = readTaskLabel(td)
            val icon = readTaskIcon(td)
            RecentTaskIdentity(
                packageName = pkg,
                label = label,
                icon = icon,
                topClassName = className,
                taskId = taskId,
            )
        } catch (e: Exception) {
            Log.d(TAG, "parseRecentTaskInfo: ${e.message}")
            null
        }
    }

    private fun readTaskLabel(td: Any?): String? {
        if (td == null) return null
        val label = callNoArg(td, "getLabel") as? String
            ?: readField(td, "label") as? String
            ?: readField(td, "mLabel") as? String
        return label?.trim()?.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun readTaskIcon(td: Any?): Bitmap? {
        if (td == null) return null
        for (name in listOf("getInMemoryIcon", "getIcon", "getIconBitmap")) {
            try {
                val bmp = callNoArg(td, name) as? Bitmap
                if (bmp != null && !bmp.isRecycled) {
                    return bmp.copy(Bitmap.Config.ARGB_8888, false) ?: bmp
                }
            } catch (_: Exception) {
            }
        }
        for (field in listOf("mIcon", "icon", "mBitmap", "mInMemoryIcon")) {
            try {
                val bmp = readField(td, field) as? Bitmap
                if (bmp != null && !bmp.isRecycled) {
                    return bmp.copy(Bitmap.Config.ARGB_8888, false) ?: bmp
                }
            } catch (_: Exception) {
            }
        }
        // HyperOS：图标常落在 /data/system_ce/.../recent_images/
        val filename = callNoArg(td, "getIconFilename") as? String
            ?: readField(td, "iconFilename") as? String
            ?: readField(td, "mIconFilename") as? String
        if (!filename.isNullOrBlank() && filename != "null") {
            loadIconFile(filename)?.let { return it }
            // 有的 ROM 只存文件名
            loadIconFile("/data/system_ce/0/recent_images/$filename")?.let { return it }
        }
        return null
    }

    private data class Comp(val packageName: String, val className: String?)

    private fun readComponent(info: Any, name: String): Comp? {
        val c = readField(info, name) ?: callNoArg(info, "get${name.replaceFirstChar { it.uppercase() }}")
        if (c == null) return null
        val pkg = callNoArg(c, "getPackageName") as? String
            ?: readField(c, "packageName") as? String
            ?: return null
        val cls = callNoArg(c, "getClassName") as? String
            ?: readField(c, "className") as? String
        return Comp(pkg, cls)
    }

    private fun readIntentPackage(info: Any): String? {
        val intent = readField(info, "baseIntent")
            ?: callNoArg(info, "getBaseIntent")
            ?: return null
        val pkg = callNoArg(intent, "getPackage") as? String
        if (!pkg.isNullOrBlank()) return pkg
        val comp = callNoArg(intent, "getComponent") ?: return null
        return callNoArg(comp, "getPackageName") as? String
    }

    private fun readField(obj: Any, name: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            } catch (_: Exception) {
                c = c.superclass
            }
        }
        return null
    }

    private fun readInt(obj: Any, vararg names: String): Int? {
        for (name in names) {
            val v = readField(obj, name)
            when (v) {
                is Int -> return v
                is Number -> return v.toInt()
            }
            try {
                val m = obj.javaClass.methods.firstOrNull {
                    it.name.equals("get${name.replaceFirstChar { ch -> ch.uppercase() }}", true) &&
                        it.parameterTypes.isEmpty()
                }
                val r = m?.invoke(obj)
                if (r is Int) return r
                if (r is Number) return r.toInt()
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun callNoArg(obj: Any, name: String): Any? {
        return try {
            obj.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            }?.invoke(obj)
        } catch (_: Exception) {
            null
        }
    }
}
