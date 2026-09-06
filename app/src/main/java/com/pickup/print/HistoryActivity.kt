package com.pickup.print

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.pickup.print.databinding.ActivityHistoryBinding
import com.pickup.print.databinding.DialogHistoryDetailBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var repo: RecognitionHistoryRepository
    private lateinit var adapter: HistoryAdapter
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root)

        repo = RecognitionHistoryRepository(this)
        adapter = HistoryAdapter(
            onClick = { showDetail(it) },
            onThumbClick = { ImagePreviewDialog.showFromPath(this, it.thumbPath) }
        )
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空历史")
                .setMessage("确定删除全部识别记录？")
                .setPositiveButton("清空") { _, _ ->
                    repo.clear()
                    reload()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        val focusId = intent.getStringExtra(EXTRA_FOCUS_ID)
        reload()
        if (focusId != null) {
            repo.get(focusId)?.let { showDetail(it) }
        }
    }

    private fun reload() {
        val items = repo.list()
        adapter.submitList(items)
        binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showDetail(item: RecognitionRecord) {
        val detail = DialogHistoryDetailBinding.inflate(LayoutInflater.from(this))
        detail.tvDetailCode.text = item.pickupCode ?: "(未识别到取件码)"
        detail.tvDetailMeta.text =
            "${timeFmt.format(Date(item.createdAt))} · ${HistoryAdapter.sourceLabel(item.source)}"
        detail.tvDetailCandidates.text =
            if (item.candidates.isEmpty()) "无" else item.candidates.joinToString("、")
        detail.tvDetailOcr.text = item.ocrText.ifBlank { "(无)" }
        val thumb = ImageUtils.loadBitmap(item.thumbPath)
        if (thumb != null) {
            detail.ivDetailThumb.setImageBitmap(thumb)
            detail.ivDetailThumb.setOnClickListener {
                ImagePreviewDialog.show(this, thumb)
            }
        }

        AlertDialog.Builder(this)
            .setTitle("识别详情")
            .setView(detail.root)
            .setPositiveButton("使用此取件码") { _, _ ->
                setResult(
                    RESULT_OK,
                    android.content.Intent().putExtra(EXTRA_USE_CODE, item.pickupCode.orEmpty())
                )
                finish()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    companion object {
        const val EXTRA_FOCUS_ID = "focus_id"
        const val EXTRA_USE_CODE = "use_code"
    }
}
