/** 超级岛 / 灵动岛通知助手 — 从 Nexio 移植，含 Shizuku XMSF bypass。 */
package com.pickup.print.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.pickup.print.AppPrefs
import com.pickup.print.MainActivity
import com.pickup.print.R
import com.pickup.print.shizuku.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

object IslandNotificationHelper {
    private const val TAG = "IslandNotificationHelper"
    private const val CHANNEL_ID = "pickup_code_island"
    private const val CHANNEL_NAME = "取件码超级岛"
    private const val BUSINESS_TAG = "pickup_code"
    const val NOTIFICATION_ID = 2001
    const val TEST_NOTIFICATION_ID = 5000
    /** 岛存活期间最长保持 XMSF bypass 的时间，超时强制恢复，避免影响推送 */
    private const val XMSF_HOLD_MAX_MS = 30 * 60 * 1000L

    private val sequenceCounter = AtomicLong(System.currentTimeMillis() / 1000)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)
    private val shizukuBypassMutex = Mutex()
    @Volatile private var xmsfHeld = false
    private var appContextRef: Context? = null

    private val releaseXmsfRunnable = Runnable {
        val ctx = appContextRef ?: return@Runnable
        scope.launch { releaseXmsfHold(ctx, reason = "timeout") }
    }

    /**
     * 发岛时关掉 XMSF 校验；岛还在时不要立刻恢复，否则系统会把岛降成普通通知。
     */
    private suspend fun withShizukuBypass(
        context: Context,
        notificationId: Int,
        notification: Notification,
        useShizukuBypass: Boolean,
    ) {
        if (!useShizukuBypass || !isShizukuAvailable()) {
            sendNotificationDirect(context, notificationId, notification)
            return
        }
        shizukuBypassMutex.withLock {
            appContextRef = context.applicationContext
            if (!xmsfHeld) {
                val disabled = try {
                    ShizukuManager.setXmsfNetworkingEnabled(context, false)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to disable XMSF networking", e)
                    sendNotificationDirect(context, notificationId, notification)
                    return@withLock
                }
                if (!disabled) {
                    Log.w(TAG, "Failed to disable XMSF networking, sending notification anyway")
                    sendNotificationDirect(context, notificationId, notification)
                    return@withLock
                }
                xmsfHeld = true
                Log.d(TAG, "XMSF networking disabled (held for island)")
                mainHandler.removeCallbacks(releaseXmsfRunnable)
                mainHandler.postDelayed(releaseXmsfRunnable, XMSF_HOLD_MAX_MS)
            }
            sendNotificationDirect(context, notificationId, notification)
            // 给 SystemUI 一点时间落岛，期间保持 bypass
            delay(300.milliseconds)
        }
    }

    private suspend fun releaseXmsfHold(context: Context, reason: String) {
        shizukuBypassMutex.withLock {
            if (!xmsfHeld) return@withLock
            try {
                ShizukuManager.setXmsfNetworkingEnabled(context, true)
                Log.d(TAG, "XMSF networking restored ($reason)")
            } catch (e: Exception) {
                Log.e(TAG, "CRITICAL: Failed to restore XMSF networking!", e)
            } finally {
                xmsfHeld = false
                mainHandler.removeCallbacks(releaseXmsfRunnable)
            }
        }
    }

    private fun sendNotificationDirect(
        context: Context,
        notificationId: Int,
        notification: Notification,
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    fun isIslandSupported(context: Context): Boolean {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod(
                "getBoolean",
                String::class.java,
                Boolean::class.javaPrimitiveType
            )
            method.invoke(null, "persist.sys.feature.island", false) as Boolean
        } catch (_: Exception) {
            false
        }
    }

    fun init(context: Context) {
        ShizukuManager.init(context)
    }

    fun isShizukuAvailable(): Boolean {
        return ShizukuManager.isShizukuRunning() && ShizukuManager.checkSelfPermission()
    }

    fun requestShizukuPermission(callback: (Boolean) -> Unit) {
        ShizukuManager.requestPermission(callback)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "取件码超级岛通知"
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun printIntentUri(context: Context, code: String): String {
        val safe = code.replace(";", "%3B").replace("#", "%23")
        return "intent:#Intent;component=${context.packageName}/.MainActivity;" +
            "S.${MainActivity.EXTRA_PICKUP_CODE}=$safe;" +
            "B.${MainActivity.EXTRA_OPEN_PRINT}=true;end"
    }

    private fun recaptureIntentUri(context: Context): String {
        return "intent:#Intent;action=${IslandActionReceiver.ACTION_RECAPTURE};" +
            "package=${context.packageName};end"
    }

    private fun buildActionInfo(
        title: String,
        intentType: Int,
        intentUri: String,
    ): JSONObject = JSONObject().apply {
        put("actionTitle", title)
        put("actionIntentType", intentType)
        put("actionIntent", intentUri)
    }

    private fun buildIslandParamsJson(
        context: Context,
        code: String,
        status: String,
        remark: String?,
        showActionButtons: Boolean,
        printEnabled: Boolean,
    ): String {
        val expandGlowEnabled = AppPrefs.isIslandExpandGlowEnabled(context)
        val paramV2 = JSONObject().apply {
            put("business", BUSINESS_TAG)
            put("protocol", 1)
            put("enableFloat", true)
            put("islandFirstFloat", true)
            put("updatable", true)
            // 持续性焦点通知，避免被当成一次性消息
            put("timeout", 60)
            put("outEffectSrc", if (expandGlowEnabled) "outer_glow" else "")
            put("reopen", "reopen")
            put("sequence", sequenceCounter.incrementAndGet())
            put("filterWhenNoPermission", false)

            put("baseInfo", JSONObject().apply {
                put("type", 2)
                put("title", code)
                put("content", buildString {
                    append(status)
                    if (!remark.isNullOrBlank()) {
                        append("｜")
                        append(remark)
                    }
                })
                put("subTitle", "")
                put("extraTitle", "")
                put("specialTitle", "")
                put("subContent", "")
                put("picFunction", "")
                put("showDivider", true)
                put("showContentDivider", false)
                put("colorTitle", "#111111")
                put("colorTitleDark", "#ffffff")
                put("colorContent", "#333333")
                put("colorContentDark", "#cccccc")
            })

            put("picInfo", JSONObject().apply {
                put("type", 1)
                put("pic", "")
            })

            if (showActionButtons) {
                // 按钮组件4：最多 2 个文字按钮
                val buttons = JSONArray()
                if (printEnabled) {
                    buttons.put(
                        buildActionInfo(
                            title = "去打印",
                            intentType = 1,
                            intentUri = printIntentUri(context, code),
                        )
                    )
                }
                buttons.put(
                    buildActionInfo(
                        title = "重新识别",
                        intentType = 2,
                        intentUri = recaptureIntentUri(context),
                    )
                )
                put("textButton", buttons)

                // 兼容 hintInfo 单按钮模板：主按钮=去打印（不可打印时=重新识别）
                val primary = if (printEnabled) {
                    buildActionInfo("去打印", 1, printIntentUri(context, code))
                } else {
                    buildActionInfo("重新识别", 2, recaptureIntentUri(context))
                }
                put("hintInfo", JSONObject().apply {
                    put("type", 2)
                    put("content", "操作")
                    put("title", status)
                    put("subContent", "来源")
                    put("subTitle", remark?.takeIf { it.isNotBlank() } ?: "取件打印")
                    put("actionInfo", primary)
                    put("colorContent", "#666666")
                    put("colorContentDark", "#aaaaaa")
                    put("colorTitle", "#222222")
                    put("colorTitleDark", "#eeeeee")
                    put("colorSubContent", "#666666")
                    put("colorSubContentDark", "#aaaaaa")
                    put("colorSubTitle", "#222222")
                    put("colorSubTitleDark", "#eeeeee")
                })
            }

            put("param_island", JSONObject().apply {
                put("islandProperty", 1)
                put("islandTimeout", 600)
                put("bigIslandArea", JSONObject().apply {
                    put("templateNo", 2)
                    put("imageTextInfoLeft", JSONObject().apply {
                        put("type", 1)
                        put("textInfo", JSONObject().apply {
                            put("title", code.take(8))
                            put("content", "")
                            put("showHighlightColor", false)
                            put("narrowFont", false)
                        })
                    })
                    put("textInfo", JSONObject().apply {
                        put("frontTitle", "")
                        put("title", status.take(6))
                        put("content", "")
                        put("showHighlightColor", false)
                        put("narrowFont", false)
                    })
                })
                put("smallIslandArea", JSONObject().apply {
                    put("picInfo", JSONObject().apply {
                        put("type", 1)
                        put("pic", "miui.focus.pic_small")
                        put("picDark", "miui.focus.pic_small_dark")
                    })
                })
            })
        }
        return JSONObject().put("param_v2", paramV2).toString()
    }

    private fun buildNotification(
        context: Context,
        code: String,
        status: String,
        remark: String?,
        notificationId: Int,
        showActionButtons: Boolean,
        printEnabled: Boolean,
        sourceIcon: Bitmap? = null,
    ): Notification {
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (code.isNotBlank() && code != "识别中" && code != "未识别" && code != "识别失败") {
                putExtra(MainActivity.EXTRA_PICKUP_CODE, code)
            }
        }
        val contentPi = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(code)
            .setContentText(status)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_STATUS)

        val fallbackIcon = Icon.createWithResource(context, R.mipmap.ic_launcher)
        val miniIcon = sourceIcon
            ?.takeIf { !it.isRecycled }
            ?.let { Icon.createWithBitmap(it) }
            ?: fallbackIcon

        val picsBundle = Bundle().apply {
            putParcelable("miui.focus.pic_app_icon", miniIcon)
            putParcelable("miui.focus.pic_app_icon_dark", miniIcon)
            putParcelable("miui.focus.pic_small", miniIcon)
            putParcelable("miui.focus.pic_small_dark", miniIcon)
        }
        builder.addExtras(Bundle().apply {
            putBundle("miui.focus.pics", picsBundle)
        })

        val notification = builder.build()
        notification.extras.putString(
            "miui.focus.param",
            buildIslandParamsJson(context, code, status, remark, showActionButtons, printEnabled)
        )
        return notification
    }

    private fun dispatch(
        context: Context,
        notification: Notification,
        notificationId: Int,
        autoCancelMs: Long?,
        useShizukuBypass: Boolean,
    ) {
        val appCtx = context.applicationContext
        if (useShizukuBypass && !isShizukuAvailable()) {
            sendNotificationDirect(appCtx, notificationId, notification)
        } else {
            scope.launch {
                withShizukuBypass(appCtx, notificationId, notification, useShizukuBypass)
            }
        }
        if (autoCancelMs != null && autoCancelMs > 0) {
            mainHandler.postDelayed({ cancel(appCtx, notificationId) }, autoCancelMs)
        }
    }

    /** 后台识别进行中。 */
    fun showRecognizingIsland(context: Context) {
        if (!isIslandSupported(context)) return
        if (!AppPrefs.isIslandEnabled(context)) return
        ensureChannel(context)
        val notification = buildNotification(
            context = context.applicationContext,
            code = "识别中",
            status = "识别中",
            remark = "正在识别取件码",
            notificationId = NOTIFICATION_ID,
            showActionButtons = false,
            printEnabled = false,
        )
        dispatch(context, notification, NOTIFICATION_ID, null, useShizukuBypass = true)
        Log.d(TAG, "Island recognizing")
    }

    /**
     * 展示取件码超级岛。
     * @param showActionButtons 显示「去打印 / 重新识别」
     */
    fun showPickupIsland(
        context: Context,
        code: String,
        status: String,
        remark: String? = null,
        notificationId: Int = NOTIFICATION_ID,
        autoCancelMs: Long? = null,
        useShizukuBypass: Boolean = true,
        showActionButtons: Boolean = false,
        printEnabled: Boolean = true,
        sourceIcon: Bitmap? = null,
    ) {
        if (!isIslandSupported(context)) return
        if (!AppPrefs.isIslandEnabled(context)) return
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return

        ensureChannel(context)
        val notification = buildNotification(
            context = context.applicationContext,
            code = trimmed,
            status = status,
            remark = remark,
            notificationId = notificationId,
            showActionButtons = showActionButtons,
            printEnabled = printEnabled && showActionButtons &&
                trimmed != "未识别" && trimmed != "识别失败" && trimmed != "识别中",
            sourceIcon = sourceIcon,
        )
        dispatch(context, notification, notificationId, autoCancelMs, useShizukuBypass)
        Log.d(TAG, "Island shown: code=$trimmed status=$status buttons=$showActionButtons")
    }

    fun cancel(context: Context, notificationId: Int = NOTIFICATION_ID) {
        val appCtx = context.applicationContext
        val manager = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId)
        scope.launch { releaseXmsfHold(appCtx, reason = "cancel") }
    }

    fun sendTestIslandNotification(context: Context) {
        if (!isIslandSupported(context)) {
            Log.w(TAG, "Island not supported on this device")
            return
        }
        if (!isShizukuAvailable()) {
            Toast.makeText(context, "Shizuku 未授权，超级岛通知可能无法正常显示", Toast.LENGTH_LONG).show()
        }
        showPickupIsland(
            context = context,
            code = "A12",
            status = "识别完成",
            remark = "测试来源",
            notificationId = TEST_NOTIFICATION_ID,
            autoCancelMs = 30_000L,
            useShizukuBypass = true,
            showActionButtons = true,
            printEnabled = true,
        )
    }
}
