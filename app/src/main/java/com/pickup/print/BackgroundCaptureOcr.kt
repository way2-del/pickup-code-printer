/** 快捷截屏后台 OCR：不拉起 App，识别完成后上超级岛。 */
package com.pickup.print

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import com.pickup.print.island.IslandNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object BackgroundCaptureOcr {
    private const val TAG = "BackgroundCaptureOcr"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val running = AtomicBoolean(false)

    fun processScreenshot(context: Context, bitmap: Bitmap) {
        val appCtx = context.applicationContext
        if (!running.compareAndSet(false, true)) {
            Toast.makeText(appCtx, "正在识别中，请稍候", Toast.LENGTH_SHORT).show()
            bitmap.recycle()
            return
        }

        // 不发「识别中」中间态岛，避免同 id 更新时被 SystemUI 降成普通通知
        Toast.makeText(appCtx, "正在后台识别取件码…", Toast.LENGTH_SHORT).show()

        scope.launch {
            mutex.withLock {
                try {
                    val captureTarget = PickupCaptureAccessibilityService.consumeCaptureTarget(clear = true)
                    val srcPkg = captureTarget.first
                    val srcLabel = captureTarget.second?.takeIf { it.isNotBlank() }
                        ?: srcPkg?.let { AppInfoHelper.resolveLabel(appCtx, it) }
                    val srcIcon = captureTarget.third
                    val sourceTag = when {
                        !srcLabel.isNullOrBlank() -> "screenshot:$srcLabel"
                        else -> "screenshot"
                    }

                    val text = OcrHelper.recognize(bitmap)
                    val parsed = PickupCodeParser.parse(text)
                    val refinedLabel = BrandHintHelper.refineLabelWithOcr(srcLabel, srcPkg, text)
                        ?: srcLabel
                    val tmp = File(appCtx.cacheDir, "tmp_bg_${System.currentTimeMillis()}.jpg")
                    withContext(Dispatchers.IO) {
                        ImageUtils.saveHistoryJpeg(bitmap, tmp)
                    }

                    val historyRepo = RecognitionHistoryRepository(appCtx)
                    historyRepo.add(
                        pickupCode = parsed.code,
                        candidates = parsed.candidates,
                        ocrText = text,
                        thumbJpeg = tmp,
                        source = when {
                            !refinedLabel.isNullOrBlank() -> "screenshot:$refinedLabel"
                            else -> "screenshot"
                        },
                        sourcePackage = srcPkg
                    )

                    val remark = refinedLabel
                    if (parsed.code != null) {
                        IslandNotificationHelper.showPickupIsland(
                            context = appCtx,
                            code = parsed.code,
                            status = "识别完成",
                            remark = remark,
                            showActionButtons = true,
                            sourceIcon = srcIcon,
                        )
                        withContext(Dispatchers.Main) {
                            Toast.makeText(appCtx, "已识别：${parsed.code}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        IslandNotificationHelper.showPickupIsland(
                            context = appCtx,
                            code = "未识别",
                            status = "未识别到取件码",
                            remark = remark,
                            showActionButtons = true,
                            printEnabled = false,
                            sourceIcon = srcIcon,
                        )
                        withContext(Dispatchers.Main) {
                            Toast.makeText(appCtx, "未识别到取件码，可点岛上「重新识别」", Toast.LENGTH_SHORT).show()
                        }
                    }
                    Log.d(TAG, "bg ocr done code=${parsed.code}")
                } catch (e: Exception) {
                    Log.e(TAG, "bg ocr failed", e)
                    IslandNotificationHelper.showPickupIsland(
                        context = appCtx,
                        code = "识别失败",
                        status = e.message?.take(40) ?: "OCR 失败",
                        remark = null,
                        showActionButtons = true,
                        printEnabled = false,
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appCtx, "识别失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    running.set(false)
                }
            }
        }
    }
}
