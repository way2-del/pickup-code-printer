package com.pickup.print

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.pickup.print.shizuku.RecentTaskIdentityHelper
import java.util.concurrent.Executor

/**
 * 用无障碍 takeScreenshot 截取当前前台页面。
 * 截屏前先通过全局动作收起通知下拉面板，避免截到通知栏。
 * 同时记录窗口标题，尽量把「微信」细化到小程序名。
 */
class PickupCaptureAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }
    private var capturePending = false

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CAPTURE) {
                val skipShadeDismiss = intent.getBooleanExtra(EXTRA_SKIP_SHADE_DISMISS, true)
                beginCapture(skipShadeDismiss)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        val filter = IntentFilter(ACTION_CAPTURE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(captureReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(captureReceiver, filter)
        }
        Log.i(TAG, "a11y connected")
        KeepAliveService.start(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (pkg != null && pkg != packageName) {
                    val className = event.className?.toString()
                    // 微信/支付宝：只用窗口标题，不用 event.text（容易是「切换搜索引擎」等控件文案）
                    val title = if (SourceIdentityHelper.isHostApp(pkg)) {
                        resolveActiveWindowTitle(preferredPkg = pkg, windowTitleOnly = true)
                    } else {
                        extractEventTitle(event)?.takeIf { !SourceIdentityHelper.isNoiseTitle(it) }
                            ?: resolveActiveWindowTitle(preferredPkg = pkg)
                    }
                    noteForegroundApp(pkg, windowTitle = title, className = className)
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                if (looksLikeCopyAction(event)) {
                    val source = pkg ?: recentForegroundPackages.firstOrNull()
                    if (!source.isNullOrBlank() && source != packageName) {
                        val className = event.className?.toString()
                        val title = extractEventTitle(event)
                            ?: lastForegroundWindowTitle
                            ?: resolveActiveWindowTitle(preferredPkg = source)
                        noteForegroundApp(source, windowTitle = title, className = className)
                        val label = lastForegroundLabel
                        if (!isIgnoredSourceApp(source, label)) {
                            lastCopySourcePackage = source
                            lastCopySourceLabel = label
                            lastCopyAtMs = System.currentTimeMillis()
                            Log.i(TAG, "copy action hint from=$source ($label)")
                        }
                    }
                }
            }
        }
    }

    private fun extractEventTitle(event: AccessibilityEvent): String? {
        val fromText = event.text
            ?.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotBlank() } }
            ?.firstOrNull()
        if (!fromText.isNullOrBlank()) return fromText
        val desc = event.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank()) return desc
        return null
    }

    /**
     * 读指定应用自己的窗口标题。
     * [windowTitleOnly]=true：只采 AccessibilityWindowInfo.title（多任务卡片名），
     * 不扫控件树，避免「切换搜索引擎」之类文案。
     */
    private fun resolveActiveWindowTitle(
        preferredPkg: String? = null,
        windowTitleOnly: Boolean = SourceIdentityHelper.isHostApp(preferredPkg),
    ): String? {
        val candidates = mutableListOf<String?>()
        try {
            val wins = windows ?: emptyList()
            val appWins = wins.filter { w ->
                w.type == AccessibilityWindowInfo.TYPE_APPLICATION
            }

            fun packageOf(w: AccessibilityWindowInfo): String? =
                try {
                    w.root?.packageName?.toString()
                } catch (_: Exception) {
                    null
                }

            fun collectFrom(w: AccessibilityWindowInfo) {
                w.title?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { candidates += it }
            }

            if (!preferredPkg.isNullOrBlank()) {
                appWins.filter { packageOf(it) == preferredPkg }.forEach(::collectFrom)
            } else {
                appWins.filter { it.isActive }.forEach(::collectFrom)
                appWins.forEach(::collectFrom)
            }
        } catch (e: Exception) {
            Log.d(TAG, "resolveActiveWindowTitle windows failed: ${e.message}")
        }

        if (!windowTitleOnly) {
            try {
                val root = rootInActiveWindow
                if (root != null) {
                    try {
                        val rootPkg = root.packageName?.toString()
                        if (preferredPkg.isNullOrBlank() || rootPkg == preferredPkg) {
                            if (Build.VERSION.SDK_INT >= 28) {
                                root.paneTitle?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
                                    candidates += it
                                }
                            }
                        }
                    } finally {
                        root.recycle()
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "resolveActiveWindowTitle root failed: ${e.message}")
            }
        }

        if (!lastForegroundWindowTitle.isNullOrBlank() &&
            !SourceIdentityHelper.isNoiseTitle(lastForegroundWindowTitle)
        ) {
            candidates.add(0, lastForegroundWindowTitle)
        }
        val appLabel = preferredPkg?.let { AppInfoHelper.resolveLabel(this, it) }
        return SourceIdentityHelper.pickBestTitle(
            candidates = candidates,
            appLabel = appLabel,
            requireMiniLikeName = SourceIdentityHelper.isHostApp(preferredPkg),
        )
    }

    private fun looksLikeCopyAction(event: AccessibilityEvent): Boolean {
        val parts = buildList {
            event.text?.forEach { add(it?.toString().orEmpty()) }
            event.contentDescription?.let { add(it.toString()) }
            event.className?.let { add(it.toString()) }
        }.joinToString(" ")
        if (parts.isBlank()) return false
        val lower = parts.lowercase()
        return parts.contains("复制") ||
            parts.contains("拷贝") ||
            lower.contains("copy") ||
            lower.contains("clipboard")
    }

    private fun noteForegroundApp(
        pkg: String,
        windowTitle: String? = null,
        className: String? = null,
    ) {
        val appLabel = AppInfoHelper.resolveLabel(this, pkg)
        if (isIgnoredSourceApp(pkg, appLabel)) {
            Log.d(TAG, "skip ignored foreground=$pkg ($appLabel)")
            return
        }
        val title = windowTitle?.takeIf { it.isNotBlank() && !SourceIdentityHelper.isNoiseTitle(it) }
            ?: lastForegroundWindowTitle?.takeIf { !SourceIdentityHelper.isNoiseTitle(it) }
        val display = SourceIdentityHelper.refineDisplayLabel(
            packageName = pkg,
            appLabel = appLabel,
            windowTitle = title,
            className = className ?: lastForegroundClassName,
        )
        synchronized(recentForegroundLock) {
            recentForegroundPackages.removeAll { it == pkg }
            recentForegroundPackages.add(0, pkg)
            val stable = SourceIdentityHelper.preferStableLabel(recentForegroundLabels[pkg], display)
                ?: display
            recentForegroundLabels[pkg] = stable
            while (recentForegroundPackages.size > 12) {
                val removed = recentForegroundPackages.removeAt(recentForegroundPackages.lastIndex)
                recentForegroundLabels.remove(removed)
            }
        }
        lastForegroundPackage = pkg
        lastForegroundLabel = recentForegroundLabels[pkg] ?: display
        if (!title.isNullOrBlank() &&
            !SourceIdentityHelper.isNoiseTitle(title) &&
            SourceIdentityHelper.looksLikeAppOrMiniName(title)
        ) {
            lastForegroundWindowTitle = title
        }
        if (!className.isNullOrBlank()) lastForegroundClassName = className
        Log.d(TAG, "foreground=$pkg display=$lastForegroundLabel title=$title class=$className")
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        try {
            unregisterReceiver(captureReceiver)
        } catch (_: Exception) {
        }
        instance = null
        super.onDestroy()
    }

    /**
     * 快捷开关点击时系统会先收起下拉面板，此时不要再 dismiss/Back，否则会误退前台 App。
     * [skipShadeDismiss]=true：只稍等面板动画结束后截屏。
     */
    private fun beginCapture(skipShadeDismiss: Boolean) {
        if (capturePending) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "无障碍截屏需要 Android 11+", Toast.LENGTH_LONG).show()
            CaptureBus.emitFailure("需要 Android 11+")
            return
        }
        capturePending = true
        if (skipShadeDismiss) {
            Log.i(TAG, "capture skip shade dismiss (QS already collapsed)")
            // 等控制中心收起后再取标题，避免读成「微信·控制中心」
            mainHandler.postDelayed({
                snapshotCaptureTarget()
                takeScreenshotNow()
            }, WAIT_AFTER_QS_COLLAPSE_MS)
            return
        }
        snapshotCaptureTarget()
        dismissNotificationShade()
        mainHandler.postDelayed({
            dismissNotificationShade()
            mainHandler.postDelayed({
                snapshotCaptureTarget()
                takeScreenshotNow()
            }, WAIT_AFTER_DISMISS_MS)
        }, FIRST_DISMISS_GAP_MS)
    }

    private fun snapshotCaptureTarget() {
        val guessed = guessRecentForegroundApp()
        var pkg = guessed.first
        val remembered = guessed.second

        // 1) Shizuku：多任务 TaskDescription（HyperOS 上微信小程序经常 label=null）
        val taskIdentity = when {
            !pkg.isNullOrBlank() -> RecentTaskIdentityHelper.findMiniOrLabeledTask(pkg)
            else -> RecentTaskIdentityHelper.findTopLabeledTask(
                excludePackages = setOf(packageName, "com.android.systemui")
            )
        }
        if (pkg.isNullOrBlank() && taskIdentity != null) {
            pkg = taskIdentity.packageName
        }
        val taskLabel = taskIdentity?.label
        val taskIcon = taskIdentity?.icon

        // 2) 无障碍：只采窗口标题（不含控件文案）
        val liveTitle = resolveActiveWindowTitle(preferredPkg = pkg)

        // 3) 无障碍树品牌关键字（雪王币 → 蜜雪冰城），不依赖 TaskDescription
        val treeBrand = try {
            val root = rootInActiveWindow
            try {
                if (root != null &&
                    (pkg.isNullOrBlank() || root.packageName?.toString() == pkg ||
                        SourceIdentityHelper.isHostApp(root.packageName?.toString()))
                ) {
                    if (pkg.isNullOrBlank()) {
                        pkg = root.packageName?.toString()
                    }
                    BrandHintHelper.guessMiniNameFromNode(root)
                } else null
            } finally {
                root?.recycle()
            }
        } catch (e: Exception) {
            Log.d(TAG, "tree brand scan failed: ${e.message}")
            null
        }

        val appLabel = if (!pkg.isNullOrBlank()) {
            AppInfoHelper.resolveLabel(this, pkg)
        } else {
            remembered
        }
        val bestTitle = when {
            SourceIdentityHelper.looksLikeAppOrMiniName(treeBrand) -> treeBrand
            SourceIdentityHelper.looksLikeAppOrMiniName(taskLabel) -> taskLabel
            SourceIdentityHelper.looksLikeAppOrMiniName(liveTitle) -> liveTitle
            else -> treeBrand ?: taskLabel ?: liveTitle
        }
        var fresh = SourceIdentityHelper.refineDisplayLabel(
            packageName = pkg,
            appLabel = appLabel,
            windowTitle = bestTitle,
            className = taskIdentity?.topClassName ?: lastForegroundClassName,
        )
        if (treeBrand != null) {
            fresh = BrandHintHelper.refineLabelWithBrand(fresh, pkg, treeBrand) ?: fresh
        }
        val display = SourceIdentityHelper.preferStableLabel(remembered, fresh)
            ?: fresh
            ?: remembered
            ?: appLabel
        lastCaptureTargetPackage = pkg
        lastCaptureTargetLabel = display
        lastCaptureTargetIcon?.recycle()
        lastCaptureTargetIcon = taskIcon
        if (SourceIdentityHelper.looksLikeAppOrMiniName(bestTitle)) {
            lastForegroundWindowTitle = bestTitle
            if (!pkg.isNullOrBlank()) {
                synchronized(recentForegroundLock) {
                    recentForegroundLabels[pkg] = display ?: appLabel.orEmpty()
                }
                lastForegroundLabel = display
            }
        }
        Log.i(
            TAG,
            "capture target=$pkg ($display) treeBrand=$treeBrand taskLabel=$taskLabel " +
                "liveTitle=$liveTitle icon=${taskIcon != null} remembered=$remembered"
        )
    }

    private fun dismissNotificationShade() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                val ok = performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                Log.i(TAG, "DISMISS_NOTIFICATION_SHADE=$ok")
            } catch (e: Exception) {
                Log.w(TAG, "dismiss shade failed", e)
            }
        }
        try {
            val statusBarService = getSystemService("statusbar")
            if (statusBarService != null) {
                val clazz = Class.forName("android.app.StatusBarManager")
                for (name in listOf("collapsePanels", "collapse")) {
                    try {
                        clazz.getMethod(name).invoke(statusBarService)
                        Log.i(TAG, "StatusBarManager.$name ok")
                        break
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        try {
            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        } catch (_: Exception) {
        }
    }

    private fun takeScreenshotNow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            capturePending = false
            return
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    capturePending = false
                    val hardware = screenshot.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(hardware, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    hardware.close()
                    if (bitmap == null) {
                        CaptureBus.emitFailure("截屏位图为空")
                        return
                    }
                    lastBitmap = null
                    BackgroundCaptureOcr.processScreenshot(
                        this@PickupCaptureAccessibilityService,
                        bitmap
                    )
                }

                override fun onFailure(errorCode: Int) {
                    capturePending = false
                    val msg = when (errorCode) {
                        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "截屏内部错误"
                        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "无障碍权限不足"
                        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截屏太频繁，请稍后再试"
                        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "显示屏无效"
                        else -> "截屏失败 code=$errorCode"
                    }
                    CaptureBus.emitFailure(msg)
                    Toast.makeText(this@PickupCaptureAccessibilityService, msg, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    companion object {
        private const val TAG = "PickupA11y"
        const val ACTION_CAPTURE = "com.pickup.print.ACTION_A11Y_CAPTURE"
        const val EXTRA_SKIP_SHADE_DISMISS = "skip_shade_dismiss"
        private const val FIRST_DISMISS_GAP_MS = 180L
        private const val WAIT_AFTER_DISMISS_MS = 650L
        private const val WAIT_AFTER_QS_COLLAPSE_MS = 280L

        private val recentForegroundLock = Any()
        private val recentForegroundPackages = mutableListOf<String>()
        private val recentForegroundLabels = mutableMapOf<String, String>()

        @Volatile
        var instance: PickupCaptureAccessibilityService? = null
            private set

        @Volatile
        var lastBitmap: Bitmap? = null

        @Volatile
        var lastForegroundPackage: String? = null
            private set

        @Volatile
        var lastForegroundLabel: String? = null
            private set

        @Volatile
        var lastForegroundWindowTitle: String? = null
            private set

        @Volatile
        var lastForegroundClassName: String? = null
            private set

        @Volatile
        var lastCopySourcePackage: String? = null
            private set

        @Volatile
        var lastCopySourceLabel: String? = null
            private set

        @Volatile
        var lastCopyAtMs: Long = 0L
            private set

        @Volatile
        var lastCaptureTargetPackage: String? = null
            private set

        @Volatile
        var lastCaptureTargetLabel: String? = null
            private set

        @Volatile
        var lastCaptureTargetIcon: Bitmap? = null
            private set

        fun isIgnoredSourceApp(pkg: String, label: String? = null): Boolean {
            val p = pkg.lowercase()
            val l = (label ?: "").lowercase()
            if (l.contains("超级小爱") || l.contains("小爱同学") || l.contains("小爱")) return true
            if (p.contains("voiceassist") || p.contains("xiaoai") || p.contains("aiasst")) return true
            if (p == "com.miui.voiceassist" || p == "com.xiaomi.voiceassistant") return true
            if (p == "com.miui.accessibility" || p.startsWith("com.android.systemui")) return true
            if (p == "com.miui.home" || p == "com.android.launcher3") return true
            return false
        }

        fun guessRecentForegroundApp(): Pair<String?, String?> {
            synchronized(recentForegroundLock) {
                val pkg = recentForegroundPackages.firstOrNull()
                val label = pkg?.let { recentForegroundLabels[it] ?: lastForegroundLabel }
                return pkg to label
            }
        }

        fun guessClipboardSourceApp(): Pair<String?, String?> {
            val now = System.currentTimeMillis()
            val copyPkg = lastCopySourcePackage
            val copyLabel = lastCopySourceLabel
            if (!copyPkg.isNullOrBlank() &&
                now - lastCopyAtMs < 10 * 60_000L &&
                !isIgnoredSourceApp(copyPkg, copyLabel)
            ) {
                return copyPkg to copyLabel
            }
            return guessRecentForegroundApp()
        }

        fun consumeCaptureTarget(clear: Boolean = true): Triple<String?, String?, Bitmap?> {
            val result = Triple(lastCaptureTargetPackage, lastCaptureTargetLabel, lastCaptureTargetIcon)
            if (clear) {
                lastCaptureTargetPackage = null
                lastCaptureTargetLabel = null
                // icon 所有权交给调用方，这里只清空引用
                lastCaptureTargetIcon = null
            }
            return result
        }

        fun isRunning(): Boolean = instance != null

        fun isEnabledInSettings(context: Context): Boolean {
            val expected = ComponentName(context, PickupCaptureAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it)?.equals(expected) == true ||
                    it.contains("${context.packageName}/.PickupCaptureAccessibilityService") ||
                    it.contains("${context.packageName}/${PickupCaptureAccessibilityService::class.java.name}")
            }
        }

        fun isUsable(context: Context): Boolean =
            isRunning() || isEnabledInSettings(context)

        fun requestCapture(context: Context, skipShadeDismiss: Boolean = true): Boolean {
            if (instance == null) {
                if (isEnabledInSettings(context)) {
                    Toast.makeText(context, "无障碍正在重连，请再点一次截取", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
                }
                return false
            }
            context.sendBroadcast(
                Intent(ACTION_CAPTURE)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_SKIP_SHADE_DISMISS, skipShadeDismiss)
            )
            return true
        }
    }
}
