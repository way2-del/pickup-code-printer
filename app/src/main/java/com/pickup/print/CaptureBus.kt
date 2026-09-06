package com.pickup.print

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper

/** 应用内截屏结果总线，避免大图走 Intent。 */
object CaptureBus {
    interface Callback {
        fun onCaptured(bitmap: Bitmap)
        fun onFailed(message: String)
    }

    @Volatile
    private var callback: Callback? = null

    fun setCallback(cb: Callback?) {
        callback = cb
    }

    fun emitSuccess(bitmap: Bitmap) {
        Handler(Looper.getMainLooper()).post { callback?.onCaptured(bitmap) }
    }

    fun emitFailure(message: String) {
        Handler(Looper.getMainLooper()).post { callback?.onFailed(message) }
    }
}
