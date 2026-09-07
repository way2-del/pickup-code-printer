package com.pickup.print

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pickup.print.ui.basic.SharedScrollBehavior
import com.pickup.print.ui.setPickupContent
import com.pickup.print.ui.utils.PageListDefaults
import com.pickup.print.ui.utils.overScrollVertical
import com.pickup.print.ui.utils.pageListContentPadding
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

class HistoryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = RecognitionHistoryRepository(this)
        setPickupContent(title = { "识别历史" }, showBack = true) { scrollBehavior, _ ->
            HistoryScreen(
                scrollBehavior = scrollBehavior,
                repo = repo,
                focusId = intent.getStringExtra(EXTRA_FOCUS_ID),
                onUseCode = { code ->
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_USE_CODE, code))
                    finish()
                }
            )
        }
    }

    companion object {
        const val EXTRA_FOCUS_ID = "focus_id"
        const val EXTRA_USE_CODE = "use_code"
    }
}

@Composable
fun HistoryScreen(
    scrollBehavior: SharedScrollBehavior?,
    repo: RecognitionHistoryRepository,
    focusId: String?,
    onUseCode: (String) -> Unit,
) {
    var items by remember { mutableStateOf(repo.list()) }
    var detail by remember {
        mutableStateOf(focusId?.let { id -> items.find { it.id == id } })
    }
    var showClear by remember { mutableStateOf(false) }
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    Scaffold(topBar = {}) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .scrollEndHaptic()
                .then(
                    if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                    else Modifier
                ),
            contentPadding = pageListContentPadding(scrollBehavior, paddingValues),
            verticalArrangement = Arrangement.spacedBy(PageListDefaults.SectionSpacing)
        ) {
            item {
                SmallTitle(
                    text = "记录",
                    modifier = Modifier.offset(x = PageListDefaults.SmallTitleOffsetX)
                )
                Card(cornerRadius = 20.dp, insideMargin = PaddingValues(0.dp)) {
                    TextButton(
                        text = "清空历史",
                        onClick = { showClear = true },
                        modifier = Modifier.fillMaxWidth(),
                        textColor = MiuixTheme.colorScheme.error
                    )
                }
            }
            if (items.isEmpty()) {
                item {
                    Text(
                        "暂无识别记录",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 24.dp)
                    )
                }
            }
            items(items, key = { it.id }) { item ->
                Card(
                    cornerRadius = 16.dp,
                    insideMargin = PaddingValues(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { detail = item }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val thumb = remember(item.thumbPath) { ImageUtils.loadBitmap(item.thumbPath) }
                        if (thumb != null) {
                            Image(
                                bitmap = thumb.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        Column {
                            Text(item.pickupCode ?: "(未识别到取件码)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(
                                "${timeFmt.format(Date(item.createdAt))} · ${HistoryLabels.sourceLabel(item.source)}",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            Text(
                                item.ocrText.replace('\n', ' ').take(80).ifBlank { "无 OCR 文本" },
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            }
        }
    }

    detail?.let { item ->
        OverlayDialog(
            show = true,
            title = "识别详情",
            onDismissRequest = { detail = null }
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(item.pickupCode ?: "(未识别到取件码)", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "${timeFmt.format(Date(item.createdAt))} · ${HistoryLabels.sourceLabel(item.source)}",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("候选：${if (item.candidates.isEmpty()) "无" else item.candidates.joinToString("、")}")
                Spacer(modifier = Modifier.height(8.dp))
                Text(item.ocrText.ifBlank { "(无)" })
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(
                    text = "使用此取件码",
                    onClick = { onUseCode(item.pickupCode.orEmpty()) },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (showClear) {
        OverlayDialog(
            show = true,
            title = "清空历史",
            summary = "确定删除全部识别记录？",
            onDismissRequest = { showClear = false }
        ) {
            Row(modifier = Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = "清空",
                    onClick = {
                        repo.clear()
                        items = emptyList()
                        showClear = false
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = "取消",
                    onClick = { showClear = false },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
