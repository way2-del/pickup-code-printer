package com.pickup.print

import android.view.LayoutInflater
import android.view.ViewGroup
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
            val thumb = ImageUtils.loadBitmap(item.thumbPath)
            if (thumb != null) {
                binding.ivThumb.setImageBitmap(thumb)
            } else {
                binding.ivThumb.setImageDrawable(null)
            }
            binding.root.setOnClickListener { onClick(item) }
            binding.ivThumb.setOnClickListener { onThumbClick(item) }
        }
    }

    companion object {
        fun sourceLabel(source: String): String = when (source) {
            "overlay" -> "悬浮截屏"
            "camera" -> "拍照"
            "gallery" -> "相册"
            else -> source
        }

        private val DIFF = object : DiffUtil.ItemCallback<RecognitionRecord>() {
            override fun areItemsTheSame(a: RecognitionRecord, b: RecognitionRecord) = a.id == b.id
            override fun areContentsTheSame(a: RecognitionRecord, b: RecognitionRecord) = a == b
        }
    }
}
