package com.pickup.print

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object ImageUtils {

    /** 生成预览缩略图，最长边不超过 [maxSide] 像素。 */
    fun createThumbnail(source: Bitmap, maxSide: Int = 360): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return source
        val longest = max(w, h)
        if (longest <= maxSide) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }
        val scale = maxSide.toFloat() / longest
        val tw = (w * scale).roundToInt().coerceAtLeast(1)
        val th = (h * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, tw, th, true)
    }

    fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 82): File {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        return file
    }

    fun loadBitmap(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(path)
    }
}
