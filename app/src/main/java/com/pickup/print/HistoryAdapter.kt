package com.pickup.print

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pickup.print.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val onClick: (RecognitionRecord) -> Unit,
    private val onThumbClick: (RecognitionRecord) -> Unit
) : ListAdapter<RecognitionRecord, HistoryAdapter.VH>(DIFF) {

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: RecognitionRecord) {
            binding.tvCode.text = item.pickupCode ?: "(未识别到取件码)"
            binding.tvMeta.text = "${timeFmt.format(Date(item.createdAt))} · ${sourceLabel(item.source)}"
            binding.tvSnippet.text = item.ocrText.replace('\n', ' ').take(80).ifBlank { "无 OCR 文本" }

            val isClipboard = item.source == "clipboard" || item.source.startsWith("clipboard:")
            binding.ivThumb.scaleType =
                if (isClipboard) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP

            val thumb = ImageUtils.loadBitmap(item.thumbPath)
            if (thumb != null) {
                binding.ivThumb.setImageBitmap(thumb)
            } else {
                binding.ivThumb.setImageDrawable(null)
            }

            val pkg = item.sourcePackage
            if (!pkg.isNullOrBlank() && !isClipboard) {
                val icon = AppInfoHelper.loadIconBitmap(binding.root.context, pkg, sizePx = 96)
                if (icon != null) {
                    binding.ivAppIcon.visibility = View.VISIBLE
                    binding.ivAppIcon.setImageBitmap(icon)
                } else {
                    binding.ivAppIcon.visibility = View.GONE
                    binding.ivAppIcon.setImageDrawable(null)
                }
            } else {
                binding.ivAppIcon.visibility = View.GONE
                binding.ivAppIcon.setImageDrawable(null)
            }

            binding.root.setOnClickListener { onClick(item) }
            binding.ivThumb.setOnClickListener { onThumbClick(item) }
        }
    }

    companion object {
        fun sourceLabel(source: String): String = when {
            source == "overlay" || source == "screenshot" -> "截屏"
            source.startsWith("screenshot:") -> {
                val app = source.removePrefix("screenshot:").trim()
                if (app.isBlank()) "截屏" else "截屏·$app"
            }
            source == "camera" || source == "camera_quick" -> "拍照"
            source == "gallery" -> "相册"
            source == "clipboard" -> "剪切板"
            source.startsWith("clipboard:") -> {
                val app = source.removePrefix("clipboard:").trim()
                if (app.isBlank()) "剪切板" else "剪切板·$app"
            }
            else -> source
        }

        private val DIFF = object : DiffUtil.ItemCallback<RecognitionRecord>() {
            override fun areItemsTheSame(a: RecognitionRecord, b: RecognitionRecord) = a.id == b.id
            override fun areContentsTheSame(a: RecognitionRecord, b: RecognitionRecord) = a == b
        }
    }
}
