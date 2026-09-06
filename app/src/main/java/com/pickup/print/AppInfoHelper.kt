package com.pickup.print

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import java.io.File

/** 解析其他 App 的显示名与图标（兼容 Android 11+ 包可见性）。 */
object AppInfoHelper {

    private val KNOWN_LABELS = mapOf(
        "com.tencent.mm" to "微信",
        "com.tencent.mobileqq" to "QQ",
        "com.tencent.tim" to "TIM",
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.taobao.taobao" to "淘宝",
        "com.tmall.wireless" to "天猫",
        "com.sankuai.meituan" to "美团",
        "com.sankuai.meituan.takeoutnew" to "美团外卖",
        "me.ele" to "饿了么",
        "com.xunmeng.pinduoduo" to "拼多多",
        "com.ss.android.ugc.aweme" to "抖音",
        "com.ss.android.article.news" to "今日头条",
        "com.smile.gifmaker" to "快手",
        "com.sina.weibo" to "微博",
        "com.alibaba.android.rimet" to "钉钉",
        "com.ss.android.lark" to "飞书",
        "com.tencent.wework" to "企业微信",
        "com.android.mms" to "短信",
        "com.android.contacts" to "联系人",
        "com.android.chrome" to "Chrome",
        "com.miui.gallery" to "相册",
        "com.android.browser" to "浏览器",
        "com.xiaomi.market" to "应用商店"
    )

    data class AppIdentity(
        val packageName: String,
        val label: String,
        val icon: Bitmap?
    )

    fun resolve(context: Context, packageName: String?): AppIdentity? {
        val pkg = packageName?.takeIf { it.isNotBlank() } ?: return null
        val label = resolveLabel(context, pkg)
        val icon = loadIconBitmap(context, pkg, sizePx = 256)
        return AppIdentity(pkg, label, icon)
    }

    fun resolveLabel(context: Context, packageName: String): String {
        KNOWN_LABELS[packageName]?.let { return it }

        val pm = context.packageManager
        // 1) ApplicationInfo 标签
        try {
            val ai = if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            val label = pm.getApplicationLabel(ai)?.toString()?.trim().orEmpty()
            if (label.isNotBlank() && !looksLikePackageTail(label, packageName)) {
                return label
            }
        } catch (_: Exception) {
        }

        // 2) Launcher Activity 标签（更接近桌面显示名）
        try {
            val launch = pm.getLaunchIntentForPackage(packageName)
            val cn = launch?.component
            if (cn != null) {
                val ai = if (Build.VERSION.SDK_INT >= 33) {
                    pm.getActivityInfo(cn, PackageManager.ComponentInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getActivityInfo(cn, 0)
                }
                val label = ai.loadLabel(pm)?.toString()?.trim().orEmpty()
                if (label.isNotBlank() && !looksLikePackageTail(label, packageName)) {
                    return label
                }
            }
        } catch (_: Exception) {
        }

        // 3) 扫一遍 MAIN/LAUNCHER，匹配包名
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val flags = if (Build.VERSION.SDK_INT >= 23) PackageManager.MATCH_ALL else 0
            val list = pm.queryIntentActivities(intent, flags)
            val hit = list.firstOrNull { it.activityInfo?.packageName == packageName }
            val label = hit?.loadLabel(pm)?.toString()?.trim().orEmpty()
            if (label.isNotBlank() && !looksLikePackageTail(label, packageName)) {
                return label
            }
        } catch (_: Exception) {
        }

        return KNOWN_LABELS[packageName]
            ?: packageName.substringAfterLast('.').ifBlank { packageName }
    }

    fun loadIconBitmap(context: Context, packageName: String, sizePx: Int = 256): Bitmap? {
        val pm = context.packageManager
        val drawable = try {
            pm.getApplicationIcon(packageName)
        } catch (_: Exception) {
            try {
                val launch = pm.getLaunchIntentForPackage(packageName) ?: return null
                val cn = launch.component ?: return null
                pm.getActivityIcon(cn)
            } catch (_: Exception) {
                return null
            }
        }
        return drawableToBitmap(drawable, sizePx)
    }

    /** 把图标画到浅色底上，保存为历史缩略图 JPEG。 */
    fun saveIconThumbJpeg(context: Context, packageName: String, dest: File, sizePx: Int = 256): File? {
        val icon = loadIconBitmap(context, packageName, sizePx) ?: return null
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(0xFFF2F5F9.toInt())
        val pad = (sizePx * 0.12f).toInt()
        val dst = android.graphics.Rect(pad, pad, sizePx - pad, sizePx - pad)
        canvas.drawBitmap(icon, null, dst, null)
        ImageUtils.saveJpeg(out, dest, quality = 90)
        if (out !== icon) out.recycle()
        if (!icon.isRecycled) icon.recycle()
        return dest
    }

    private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val src = drawable.bitmap
            if (src.width == sizePx && src.height == sizePx) {
                return src.copy(Bitmap.Config.ARGB_8888, false)
            }
            return Bitmap.createScaledBitmap(src, sizePx, sizePx, true)
        }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bmp
    }

    /** 避免把包名尾段（如 mm）当成正式名称。 */
    private fun looksLikePackageTail(label: String, packageName: String): Boolean {
        val tail = packageName.substringAfterLast('.')
        return label.equals(tail, ignoreCase = true) ||
            label.equals(packageName, ignoreCase = true)
    }
}
