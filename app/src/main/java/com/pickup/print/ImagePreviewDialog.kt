package com.pickup.print

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

object ImagePreviewDialog {

    fun show(context: Context, bitmap: Bitmap?) {
        if (bitmap == null) return
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val image = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bitmap)
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(image)
        root.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(root)
        dialog.show()
    }

    fun showFromPath(activity: AppCompatActivity, path: String?) {
        val bmp = ImageUtils.loadBitmap(path) ?: return
        show(activity, bmp)
    }
}
