package com.pickup.print

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dothantech.printer.IDzPrinter.PrinterAddress
import com.kyant.backdrop.Backdrop
import com.kyant.capsule.ContinuousRoundedRectangle
import com.pickup.print.island.IslandNotificationHelper
import com.pickup.print.ui.CollectionScreen
import com.pickup.print.ui.MineScreen
import com.pickup.print.ui.basic.LiquidTopBarButton
import com.pickup.print.ui.basic.SharedScrollBehavior
import com.pickup.print.ui.components.LiquidAddButton
import com.pickup.print.ui.components.PickupBottomBar
import com.pickup.print.ui.setPickupContent
import com.pickup.print.ui.utils.PageListDefaults
import com.pickup.print.ui.utils.overScrollVertical
import com.pickup.print.ui.utils.pageListContentPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NativeMiuixTextField
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity(), PrinterManager.Listener, CaptureBus.Callback {

    private lateinit var printerManager: PrinterManager
    private lateinit var historyRepo: RecognitionHistoryRepository
    private lateinit var collectionRepo: CollectionRepository
    private lateinit var printPrefs: PrintPrefs

    private val devices = mutableStateListOf<PrinterAddress>()
    private var printerStatus by mutableStateOf("未连接打印机")
    private var printerConnected by mutableStateOf(false)
    private var connectedAddress by mutableStateOf<PrinterAddress?>(null)
    private var defaultKey by mutableStateOf<String?>(null)
    private var pickupCode by mutableStateOf("")
    private var remark by mutableStateOf("")
    private var latestRecord by mutableStateOf<RecognitionRecord?>(null)
    private var recentRecords by mutableStateOf<List<RecognitionRecord>>(emptyList())
    private var historyTotalCount by mutableIntStateOf(0)
    private var recognizing by mutableStateOf(false)
    private var clipboardDialog by mutableStateOf<ClipboardPrompt?>(null)
    private var clipboardPromptedThisResume = false
    private var autoConnectAttempted = false
    private var pendingAutoConnect = true
    private var mainSelectedTab by mutableIntStateOf(0)

    private var collectionCategories by mutableStateOf<List<CollectionCategory>>(emptyList())
    private var collectionItems by mutableStateOf<List<CollectionItem>>(emptyList())
    private var selectedCollectionCategoryId by mutableStateOf<String?>(null)
    private var showCollectionManage by mutableStateOf(false)
    private var showCaptureChooser by mutableStateOf(false)
    private var pendingSave by mutableStateOf<PendingRecognizeSave?>(null)
    /** 下一次拍照/相册 OCR 归入哪一类；截屏始终走取件码。 */
    private var pendingOcrCategory: PrintCategory = PrintCategory.PICKUP

    data class PendingRecognizeSave(
        val code: String,
        val candidates: List<String>,
        val thumbFile: File?,
        val source: String,
        val defaultKind: CollectionKind,
        val suggestedSubtitle: String?,
        val suggestedNote: String?,
    )

    private val accessibilitySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (PickupCaptureAccessibilityService.isUsable(this)) {
            KeepAliveService.start(this)
            toast("无障碍已就绪，可用快捷开关「取件截屏」")
        }
    }

    data class ClipboardPrompt(
        val code: String,
        val candidates: List<String>,
        val rawText: String,
        val fingerprint: String,
        val srcPkg: String?,
        val srcLabel: String?
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { !it }) toast("部分权限未授予，蓝牙搜索/拍照可能不可用")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        historyRepo = RecognitionHistoryRepository(this)
        collectionRepo = CollectionRepository(this)
        printPrefs = PrintPrefs(this)
        defaultKey = printPrefs.defaultPrinterKey
        printerManager = PrinterManager(this)
        refreshHistoryPreview()
        latestRecord?.pickupCode?.let { if (pickupCode.isBlank()) pickupCode = it }
        refreshCollectionState()

        requestRuntimePermissions()
        IslandNotificationHelper.init(this)
        applyPickupCodeFromIntent(intent)
        handleOpenPrintFromIntent(intent)
        handleCaptureIntent(intent)
        startAutoConnectScan()
        KeepAliveService.start(this)
        maybeAskBatteryWhitelist()
        AppPrefs.setTaskExcludedFromRecents(this, AppPrefs.isHideBackground(this))

        setPickupContent(
            title = {
                when (mainSelectedTab) {
                    0 -> "识别"
                    1 -> "记录"
                    else -> "我的"
                }
            },
            showBack = false,
            endActions = { backdrop, backdropAlpha, shadowAlpha ->
                if (mainSelectedTab == 1) {
                    if (backdrop != null) {
                        LiquidTopBarButton(
                            onClick = { showCollectionManage = true },
                            backdrop = backdrop,
                            icon = MiuixIcons.Settings,
                            contentDescription = "管理分类",
                            iconSize = 22.dp,
                            backdropAlpha = backdropAlpha,
                            shadowAlpha = shadowAlpha,
                        )
                    } else {
                        IconButton(onClick = { showCollectionManage = true }) {
                            Icon(
                                imageVector = MiuixIcons.Settings,
                                contentDescription = "管理分类",
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                }
            },
            bottomBar = { liquidBackdrop ->
                PickupBottomBar(
                    selectedTab = mainSelectedTab,
                    onTabSelected = {
                        mainSelectedTab = it
                        if (it != 1) showCollectionManage = false
                    },
                    liquidGlassBackdrop = liquidBackdrop,
                    addButton = {
                        if (liquidBackdrop != null) {
                            LiquidAddButton(
                                onClick = { showCaptureChooser = true },
                                backdrop = liquidBackdrop,
                            )
                        }
                    },
                )
            },
        ) { scrollBehavior, liquidBackdrop ->
            val bottomPickLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.GetContent()
            ) { uri ->
                showCaptureChooser = false
                if (uri != null) {
                    pendingOcrCategory = PrintCategory.PICKUP
                    mainSelectedTab = 0
                    handleImageUri(uri)
                }
            }
            if (showCaptureChooser) {
                OverlayDialog(
                    show = true,
                    title = "添加识别",
                    onDismissRequest = { showCaptureChooser = false },
                    liquidGlassBackdrop = liquidBackdrop,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "默认识别取件码，可选择拍照或从相册选取。",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        TextButton(
                            text = "拍照识别",
                            onClick = {
                                showCaptureChooser = false
                                pendingOcrCategory = PrintCategory.PICKUP
                                mainSelectedTab = 0
                                ensureCamera {
                                    startActivity(
                                        Intent(this@MainActivity, CameraCaptureActivity::class.java)
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                        TextButton(
                            text = "相册识别",
                            onClick = { bottomPickLauncher.launch("image/*") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(
                            text = "取消",
                            onClick = { showCaptureChooser = false },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            pendingSave?.let { pending ->
                SaveRecognizeDialog(
                    pending = pending,
                    categories = collectionCategories,
                    liquidBackdrop = liquidBackdrop,
                    onDismiss = {
                        pending.thumbFile?.delete()
                        pendingSave = null
                    },
                    onSave = { catId, alsoPrint -> savePendingRecognize(catId, alsoPrint) },
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (mainSelectedTab == 0) 2f else 0f)
                        .graphicsLayer { alpha = if (mainSelectedTab == 0) 1f else 0f }
                ) {
                    MainScreen(
                        scrollBehavior = scrollBehavior,
                        liquidBackdrop = liquidBackdrop,
                        pickupCode = pickupCode,
                        remark = remark,
                        recentRecords = recentRecords,
                        historyHasMore = historyTotalCount > 3,
                        clipboardPrompt = clipboardDialog,
                        onPickupCodeChange = { pickupCode = it },
                        onRemarkChange = { remark = it },
                        onSelectRecord = { record ->
                            record.pickupCode?.let { pickupCode = it }
                        },
                        onHistory = {
                            startActivity(Intent(this@MainActivity, HistoryActivity::class.java))
                        },
                        onPrint = { printCode() },
                        onClipboardYes = { prompt ->
                            pickupCode = prompt.code
                            if (!prompt.srcLabel.isNullOrBlank()) {
                                remark = HistoryLabels.cleanRemark(prompt.srcLabel).orEmpty()
                            }
                            saveClipboardToHistory(prompt)
                            clipboardDialog = null
                            toast("已填入取件码：${prompt.code}")
                            IslandNotificationHelper.showPickupIsland(
                                context = this@MainActivity,
                                code = prompt.code,
                                status = "识别完成",
                                remark = prompt.srcLabel,
                            )
                            pendingSave = PendingRecognizeSave(
                                code = prompt.code,
                                candidates = prompt.candidates,
                                thumbFile = null,
                                source = "clipboard",
                                defaultKind = CollectionKind.PICKUP,
                                suggestedSubtitle = null,
                                suggestedNote = HistoryLabels.cleanRemark(prompt.srcLabel),
                            )
                        },
                        onClipboardNo = { prompt ->
                            val prefs = getSharedPreferences("clipboard_probe", MODE_PRIVATE)
                            ClipboardProbe.markHandled(prefs, prompt.fingerprint)
                            clipboardDialog = null
                        },
                        onClipboardLater = { clipboardDialog = null },
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (mainSelectedTab == 1) 2f else 0f)
                        .graphicsLayer { alpha = if (mainSelectedTab == 1) 1f else 0f }
                ) {
                    CollectionScreen(
                        scrollBehavior = scrollBehavior,
                        liquidBackdrop = liquidBackdrop,
                        printPrefs = printPrefs,
                        categories = collectionCategories,
                        selectedCategoryId = selectedCollectionCategoryId,
                        items = collectionItems,
                        printerConnected = printerConnected,
                        showManage = showCollectionManage,
                        onShowManageChange = { showCollectionManage = it },
                        onSelectCategory = { selectedCollectionCategoryId = it },
                        onAddCategory = { name, kind ->
                            try {
                                val cat = collectionRepo.addCategory(name, kind)
                                refreshCollectionState()
                                selectedCollectionCategoryId = cat.id
                                toast("已添加分类：$name")
                            } catch (e: Exception) {
                                toast(e.message ?: "添加失败")
                            }
                        },
                        onRenameCategory = { id, name ->
                            if (collectionRepo.renameCategory(id, name)) {
                                refreshCollectionState()
                            } else toast("重命名失败")
                        },
                        onDeleteCategory = { id ->
                            if (collectionRepo.deleteCategory(id)) {
                                refreshCollectionState()
                                toast("已删除分类")
                            } else toast("至少保留一个分类")
                        },
                        onMoveCategory = { id, towardStart ->
                            collectionRepo.moveCategory(id, towardStart)
                            refreshCollectionState()
                        },
                        onAddItem = { title, subtitle, note, drink, shop ->
                            val catId = selectedCollectionCategoryId
                                ?: collectionCategories.firstOrNull()?.id
                                ?: return@CollectionScreen
                            collectionRepo.addItem(
                                categoryId = catId,
                                title = title,
                                subtitle = shop ?: subtitle,
                                note = note,
                                extras = buildMap {
                                    drink?.let { put("drinkName", it) }
                                },
                            )
                            refreshCollectionState()
                            toast("已收入收集")
                        },
                        onDeleteItem = { item ->
                            collectionRepo.deleteItem(item.id)
                            refreshCollectionState()
                        },
                        onPrintItem = { printCollectionItem(it) },
                        onTakePhoto = {
                            pendingOcrCategory = PrintCategory.TEA_CUP
                            ensureCamera {
                                startActivity(Intent(this@MainActivity, CameraCaptureActivity::class.java))
                            }
                        },
                        onImagePicked = { uri ->
                            pendingOcrCategory = PrintCategory.TEA_CUP
                            handleImageUri(uri)
                        },
                        onEditTeaLayout = {
                            startActivity(
                                Intent(this@MainActivity, PrintLayoutActivity::class.java)
                                    .putExtra(PrintLayoutActivity.EXTRA_CATEGORY, PrintCategory.TEA_CUP.id)
                            )
                        },
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (mainSelectedTab == 2) 2f else 0f)
                        .graphicsLayer { alpha = if (mainSelectedTab == 2) 1f else 0f }
                ) {
                    MineScreen(
                        scrollBehavior = scrollBehavior,
                        liquidBackdrop = liquidBackdrop,
                        status = printerStatus,
                        devices = devices.toList(),
                        defaultKey = defaultKey,
                        connectedAddress = connectedAddress,
                        onRefreshPrinters = {
                            ensureBluetooth {
                                requestRuntimePermissions()
                                pendingAutoConnect = true
                                autoConnectAttempted = false
                                printerManager.refreshDiscovery()
                                toast("正在搜索打印机…")
                            }
                        },
                        onDisconnect = { printerManager.disconnect() },
                        onConnect = { addr ->
                            ensureBluetooth {
                                if (!printerManager.connect(addr)) toast("连接请求失败")
                            }
                        },
                        onSetDefault = { addr ->
                            val key = printerKey(addr)
                            printPrefs.setDefaultPrinter(key, PrinterManager.displayName(addr))
                            defaultKey = key
                            toast("已设为默认：${PrinterManager.displayName(addr)}")
                            if (!printerManager.isConnected()) ensureBluetooth { printerManager.connect(addr) }
                        },
                        onPrintLayoutPickup = {
                            startActivity(
                                Intent(this@MainActivity, PrintLayoutActivity::class.java)
                                    .putExtra(PrintLayoutActivity.EXTRA_CATEGORY, PrintCategory.PICKUP.id)
                            )
                        },
                        onPrintLayoutTea = {
                            startActivity(
                                Intent(this@MainActivity, PrintLayoutActivity::class.java)
                                    .putExtra(PrintLayoutActivity.EXTRA_CATEGORY, PrintCategory.TEA_CUP.id)
                            )
                        },
                        onAccessibility = {
                            accessibilitySettingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onAddQsTiles = { QuickTileHelper.requestAddTiles(this@MainActivity) },
                        onKeepAlive = { enableKeepAliveFromMine() },
                        onPreferences = {
                            startActivity(Intent(this@MainActivity, PreferenceSettingsActivity::class.java))
                        },
                        onAbout = {
                            startActivity(Intent(this@MainActivity, AboutActivity::class.java))
                        },
                    )
                }
            }
        }
    }

    private fun headerTitle(): String {
        val connected = connectedAddress
        return when {
            connected != null -> PrinterManager.displayName(connected)
            !printPrefs.defaultPrinterName.isNullOrBlank() -> printPrefs.defaultPrinterName!!
            else -> getString(R.string.printer_idle)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyPickupCodeFromIntent(intent)
        handleOpenPrintFromIntent(intent)
        handleCaptureIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        CaptureBus.setCallback(this)
        PickupCaptureAccessibilityService.lastBitmap?.let { bmp ->
            PickupCaptureAccessibilityService.lastBitmap = null
            runOcr(bmp, source = "screenshot")
        }
        refreshHistoryPreview()
        clipboardPromptedThisResume = false
        window.decorView.postDelayed({ maybePromptClipboard() }, 350)
    }

    override fun onPause() {
        CaptureBus.setCallback(null)
        super.onPause()
    }

    override fun onDestroy() {
        printerManager.quit()
        super.onDestroy()
    }

    override fun onPrinterListChanged(list: List<PrinterAddress>) {
        devices.clear()
        devices.addAll(list)
        maybeAutoConnect(list)
    }

    override fun onPrinterState(message: String, connected: Boolean, address: PrinterAddress?) {
        printerStatus = message
        printerConnected = connected
        connectedAddress = address
        if (connected && address != null && printPrefs.defaultPrinterKey.isNullOrBlank()) {
            printPrefs.setDefaultPrinter(printerKey(address), PrinterManager.displayName(address))
            defaultKey = printPrefs.defaultPrinterKey
        }
        // recreate title by finishing content is hard; toast is enough — title updates next resume
    }

    override fun onPrintResult(success: Boolean, message: String) {
        toast(message)
        val code = pickupCode.trim()
        if (code.isNotEmpty()) {
            IslandNotificationHelper.showPickupIsland(
                context = this,
                code = code,
                status = if (success) "打印成功" else "打印失败",
                remark = remark.takeIf { it.isNotBlank() },
                autoCancelMs = if (success) 15_000L else null,
            )
        }
    }

    private fun applyPickupCodeFromIntent(intent: Intent?) {
        val code = intent?.getStringExtra(EXTRA_PICKUP_CODE)?.trim().orEmpty()
        if (code.isNotEmpty()) {
            pickupCode = code
            intent?.removeExtra(EXTRA_PICKUP_CODE)
        }
    }

    private fun handleOpenPrintFromIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PRINT, false) != true) return
        intent.removeExtra(EXTRA_OPEN_PRINT)
        mainSelectedTab = 0
        window.decorView.post {
            val code = pickupCode.trim()
            if (code.isEmpty()) {
                toast("暂无取件码")
                return@post
            }
            if (printerManager.isConnected()) {
                printCode()
            } else {
                toast("已填入 $code，请连接打印机后打印")
            }
        }
    }

    override fun onCaptured(bitmap: Bitmap) = runOcr(bitmap, source = "screenshot")
    override fun onFailed(message: String) = toast(message)

    private fun handleCaptureIntent(intent: Intent?) {
        if (intent == null) return
        val path = intent.getStringExtra(CameraCaptureActivity.EXTRA_BITMAP_PATH)
        if (!path.isNullOrBlank()) {
            intent.removeExtra(CameraCaptureActivity.EXTRA_BITMAP_PATH)
            val bmp = android.graphics.BitmapFactory.decodeFile(path)
            if (bmp != null) {
                val source = if (intent.getBooleanExtra(EXTRA_FROM_CAMERA_QUICK, false)) "camera_quick" else "camera"
                intent.removeExtra(EXTRA_FROM_CAMERA_QUICK)
                runOcr(bmp, source = source)
            }
        }
        if (intent.getBooleanExtra(EXTRA_FROM_CAPTURE, false)) {
            intent.removeExtra(EXTRA_FROM_CAPTURE)
            PickupCaptureAccessibilityService.lastBitmap?.let { bmp ->
                PickupCaptureAccessibilityService.lastBitmap = null
                runOcr(bmp, source = "screenshot")
            }
        }
    }

    private fun runOcr(bitmap: Bitmap, source: String) {
        if (recognizing) return
        recognizing = true
        val printCat = when {
            source == "screenshot" || source.startsWith("screenshot") -> PrintCategory.PICKUP
            else -> pendingOcrCategory
        }
        toast(if (printCat == PrintCategory.TEA_CUP) "正在识别取茶号…" else "正在识别取件码…")
        val captureTarget = if (source == "screenshot" || source.startsWith("screenshot")) {
            PickupCaptureAccessibilityService.consumeCaptureTarget(clear = true)
        } else {
            Triple(null, null, null)
        }
        val srcPkg = captureTarget.first
        var srcLabel = captureTarget.second?.takeIf { it.isNotBlank() }
            ?: srcPkg?.let { AppInfoHelper.resolveLabel(this, it) }
        val srcIcon = captureTarget.third
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.Default) { OcrHelper.recognize(bitmap) }
                val parsed = PickupCodeParser.parse(text)
                srcLabel = BrandHintHelper.refineLabelWithOcr(srcLabel, srcPkg, text) ?: srcLabel
                val tmp = File(cacheDir, "tmp_${System.currentTimeMillis()}.jpg")
                withContext(Dispatchers.IO) { ImageUtils.saveHistoryJpeg(bitmap, tmp) }

                if (parsed.code == null) {
                    toast(if (printCat == PrintCategory.TEA_CUP) "未识别到取茶号，可手动填写" else "未识别到取件码，可手动填写")
                    return@launch
                }

                val sourceTag = when {
                    source == "screenshot" && !srcLabel.isNullOrBlank() -> "screenshot:$srcLabel"
                    source == "screenshot" -> "screenshot"
                    else -> source
                }
                val defaultKind = when (printCat) {
                    PrintCategory.TEA_CUP -> CollectionKind.TEA_CUP
                    PrintCategory.PICKUP -> CollectionKind.PICKUP
                }
                val shop = if (defaultKind == CollectionKind.TEA_CUP) {
                    val brand = BrandHintHelper.guessMiniNameFromOcr(text)
                        ?: srcLabel?.substringAfter('·')?.trim()?.takeIf { it.isNotBlank() }
                    brand?.takeIf { it !in listOf("微信", "支付宝") } ?: "奶茶店"
                } else null

                pickupCode = parsed.code
                if (!srcLabel.isNullOrBlank() && remark.isBlank()) {
                    remark = HistoryLabels.cleanRemark(srcLabel).orEmpty()
                }
                // 仍写入识别历史，便于迁移前兼容；正式入库由弹窗确认
                historyRepo.add(
                    pickupCode = parsed.code,
                    candidates = parsed.candidates,
                    ocrText = text,
                    thumbJpeg = tmp,
                    source = sourceTag,
                    sourcePackage = srcPkg,
                )
                // historyRepo.add 可能挪走 tmp，再拷一份给弹窗用
                val thumbForSave = File(cacheDir, "save_${System.currentTimeMillis()}.jpg")
                val histLatest = historyRepo.latest()
                histLatest?.thumbPath?.let { path ->
                    runCatching { File(path).copyTo(thumbForSave, overwrite = true) }
                }
                refreshHistoryPreview()
                IslandNotificationHelper.showPickupIsland(
                    context = this@MainActivity,
                    code = parsed.code,
                    status = "识别完成",
                    remark = remark.takeIf { it.isNotBlank() } ?: srcLabel,
                    showActionButtons = source == "screenshot" || source.startsWith("screenshot"),
                    sourceIcon = srcIcon,
                )
                pendingSave = PendingRecognizeSave(
                    code = parsed.code,
                    candidates = parsed.candidates,
                    thumbFile = thumbForSave.takeIf { it.exists() },
                    source = sourceTag,
                    defaultKind = defaultKind,
                    suggestedSubtitle = shop,
                    suggestedNote = HistoryLabels.cleanRemark(srcLabel),
                )
                mainSelectedTab = 0
                toast("已识别：${parsed.code}，请选择分类保存")
            } catch (e: Exception) {
                toast("OCR 失败：${e.message}")
            } finally {
                recognizing = false
                pendingOcrCategory = PrintCategory.PICKUP
                if (srcIcon != null && !srcIcon.isRecycled) srcIcon.recycle()
            }
        }
    }

    private fun savePendingRecognize(categoryId: String, alsoPrint: Boolean) {
        val pending = pendingSave ?: return
        val cat = collectionRepo.categoryById(categoryId)
        val item = collectionRepo.addItem(
            categoryId = categoryId,
            title = pending.code,
            subtitle = when (cat?.kind) {
                CollectionKind.TEA_CUP -> pending.suggestedSubtitle
                else -> null
            },
            note = HistoryLabels.cleanRemark(pending.suggestedNote),
            thumbJpeg = pending.thumbFile,
            source = pending.source,
        )
        selectedCollectionCategoryId = categoryId
        refreshCollectionState()
        pendingSave = null
        pending.thumbFile?.delete()
        if (alsoPrint) {
            printCollectionItem(item)
        } else {
            toast("已保存到「${cat?.name ?: "记录"}」")
            mainSelectedTab = 1
        }
    }

    private fun handleImageUri(uri: Uri) {
        try {
            val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = false
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                @Suppress("DEPRECATION")
                android.provider.MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
            runOcr(bmp, source = "gallery")
        } catch (e: Exception) {
            toast("读取图片失败：${e.message}")
        }
    }

    private fun printCode() {
        val code = pickupCode.trim()
        if (code.isEmpty()) {
            toast("请先确认取件码")
            return
        }
        if (!printerManager.isConnected()) {
            toast("请先连接德佟打印机（「我的」里设置）")
            return
        }
        IslandNotificationHelper.showPickupIsland(
            context = this,
            code = code,
            status = "打印中",
            remark = remark.takeIf { it.isNotBlank() },
        )
        printerManager.printPickupCode(code, remark, printPrefs.loadLayout(PrintCategory.PICKUP))
    }

    private fun refreshCollectionState() {
        collectionCategories = collectionRepo.listCategories()
        collectionItems = collectionRepo.listItems()
        if (selectedCollectionCategoryId == null ||
            collectionCategories.none { it.id == selectedCollectionCategoryId }
        ) {
            selectedCollectionCategoryId = collectionCategories.firstOrNull()?.id
        }
    }

    private fun printCollectionItem(item: CollectionItem) {
        val code = item.title.trim()
        if (code.isEmpty()) {
            toast("条目标题为空，无法打印")
            return
        }
        if (!printerManager.isConnected()) {
            toast("请先在「我的」连接打印机")
            return
        }
        val cat = collectionRepo.categoryById(item.categoryId)
        val resolved = PrintLayoutResolver.resolve(printPrefs, cat, item)
        printerManager.printPickupCode(resolved.code, resolved.remark, resolved.config)
        val kindHint = when (cat?.kind) {
            CollectionKind.PICKUP -> "取件码"
            CollectionKind.TEA_CUP -> "杯贴"
            CollectionKind.TICKET -> "车票"
            else -> cat?.name ?: "记录"
        }
        toast("正在打印$kindHint：${resolved.code}")
    }

    private fun enableKeepAliveFromMine() {
        KeepAliveService.start(this)
        maybeAskBatteryWhitelist(force = true)
        try {
            val miui = Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
            if (miui.resolveActivity(packageManager) != null) {
                startActivity(miui)
            }
        } catch (_: Exception) {
        }
        toast("已开启保活；拍照/截屏请用系统快捷开关")
    }

    private fun maybePromptClipboard() {
        if (isFinishing || isDestroyed) return
        if (clipboardPromptedThisResume || clipboardDialog != null || recognizing) return
        val snap = ClipboardProbe.read(this) ?: return
        val prefs = getSharedPreferences("clipboard_probe", MODE_PRIVATE)
        if (ClipboardProbe.wasHandled(prefs, snap.fingerprint)) return
        val code = snap.code ?: return
        clipboardPromptedThisResume = true
        val (srcPkg, rawLabel) = PickupCaptureAccessibilityService.guessClipboardSourceApp()
        val srcLabel = when {
            !srcPkg.isNullOrBlank() -> AppInfoHelper.resolveLabel(this, srcPkg)
            !rawLabel.isNullOrBlank() -> rawLabel
            else -> null
        }
        clipboardDialog = ClipboardPrompt(
            code = code,
            candidates = snap.candidates,
            rawText = snap.rawText,
            fingerprint = snap.fingerprint,
            srcPkg = srcPkg,
            srcLabel = srcLabel
        )
    }

    private fun saveClipboardToHistory(prompt: ClipboardPrompt) {
        val prefs = getSharedPreferences("clipboard_probe", MODE_PRIVATE)
        ClipboardProbe.markHandled(prefs, prompt.fingerprint)
        lifecycleScope.launch {
            try {
                val label = when {
                    !prompt.srcPkg.isNullOrBlank() -> AppInfoHelper.resolveLabel(this@MainActivity, prompt.srcPkg)
                    else -> prompt.srcLabel
                }
                val sourceTag = if (!label.isNullOrBlank()) "clipboard:$label" else "clipboard"
                val tmp = withContext(Dispatchers.IO) {
                    val file = File(cacheDir, "tmp_clipboard_${System.currentTimeMillis()}.jpg")
                    if (!prompt.srcPkg.isNullOrBlank()) {
                        AppInfoHelper.saveIconThumbJpeg(this@MainActivity, prompt.srcPkg, file)
                            ?: run {
                                val bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(0xFFE8EEF5.toInt())
                                ImageUtils.saveJpeg(bmp, file, 70)
                                bmp.recycle()
                                file
                            }
                    } else {
                        val bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(0xFFE8EEF5.toInt())
                        ImageUtils.saveJpeg(bmp, file, 70)
                        bmp.recycle()
                        file
                    }
                }
                latestRecord = historyRepo.add(
                    pickupCode = prompt.code,
                    candidates = prompt.candidates,
                    ocrText = prompt.rawText,
                    thumbJpeg = tmp,
                    source = sourceTag,
                    sourcePackage = prompt.srcPkg
                )
                refreshHistoryPreview()
            } catch (_: Exception) {
            }
        }
    }

    private fun refreshHistoryPreview() {
        val all = historyRepo.list()
        historyTotalCount = all.size
        recentRecords = all.take(3)
        latestRecord = all.firstOrNull()
    }

    private fun startAutoConnectScan() {
        ensureBluetooth {
            pendingAutoConnect = true
            autoConnectAttempted = false
            printerManager.refreshDiscovery()
            printerStatus = "正在搜索并连接默认打印机…"
        }
    }

    private fun maybeAutoConnect(list: List<PrinterAddress>) {
        if (!pendingAutoConnect || autoConnectAttempted) return
        if (printerManager.isConnected()) {
            pendingAutoConnect = false
            return
        }
        if (list.isEmpty()) return
        val key = printPrefs.defaultPrinterKey
        val target = when {
            !key.isNullOrBlank() ->
                list.find { printerKey(it) == key }
                    ?: list.find { it.shownName == printPrefs.defaultPrinterName }
            list.size == 1 -> list.first().also {
                printPrefs.setDefaultPrinter(printerKey(it), PrinterManager.displayName(it))
                defaultKey = printPrefs.defaultPrinterKey
            }
            else -> null
        }
        if (target != null) {
            autoConnectAttempted = true
            pendingAutoConnect = false
            ensureBluetooth {
                toast("正在连接默认打印机：${PrinterManager.displayName(target)}")
                printerManager.connect(target)
            }
        } else if (list.size > 1 && key.isNullOrBlank()) {
            pendingAutoConnect = false
            toast("发现多台打印机，请点「设默认」指定开机自动连接")
        }
    }

    private fun maybeAskBatteryWhitelist(force: Boolean = false) {
        if (KeepAliveService.isIgnoringBatteryOptimizations(this) && !force) return
        val p = getSharedPreferences("keepalive", MODE_PRIVATE)
        if (!force && p.getBoolean("battery_asked", false)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
            p.edit().putBoolean("battery_asked", true).apply()
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun ensureBluetooth(block: () -> Unit) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            toast("设备不支持蓝牙")
            return
        }
        if (!adapter.isEnabled) {
            toast("请先打开蓝牙")
            return
        }
        block()
    }

    private fun ensureCamera(block: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            toast("请授予相机权限")
            return
        }
        block()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_FROM_CAPTURE = "from_capture"
        const val EXTRA_FROM_CAMERA_QUICK = "from_camera_quick"
        const val EXTRA_PICKUP_CODE = "pickup_code"
        const val EXTRA_OPEN_PRINT = "open_print"
        fun printerKey(address: PrinterAddress): String =
            address.macAddress?.takeIf { it.isNotBlank() } ?: address.shownName.orEmpty()
    }
}

@Composable
fun MainScreen(
    scrollBehavior: SharedScrollBehavior?,
    liquidBackdrop: Backdrop?,
    pickupCode: String,
    remark: String,
    recentRecords: List<RecognitionRecord>,
    historyHasMore: Boolean,
    clipboardPrompt: MainActivity.ClipboardPrompt?,
    onPickupCodeChange: (String) -> Unit,
    onRemarkChange: (String) -> Unit,
    onSelectRecord: (RecognitionRecord) -> Unit,
    onHistory: () -> Unit,
    onPrint: () -> Unit,
    onClipboardYes: (MainActivity.ClipboardPrompt) -> Unit,
    onClipboardNo: (MainActivity.ClipboardPrompt) -> Unit,
    onClipboardLater: () -> Unit,
) {
    var previewRecord by remember { mutableStateOf<RecognitionRecord?>(null) }
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }

    Scaffold(topBar = {}) { paddingValues ->
        val listState = rememberLazyListState()
        val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
        LaunchedEffect(imeBottom) {
            if (imeBottom > 0) {
                delay(50)
                listState.animateScrollToItem(index = 0, scrollOffset = Int.MAX_VALUE / 4)
            }
        }
        LazyColumn(
            state = listState,
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
                    text = "最近识别",
                    modifier = Modifier.offset(x = PageListDefaults.SmallTitleOffsetX)
                )
                Card(cornerRadius = 20.dp, insideMargin = PaddingValues(12.dp)) {
                    if (recentRecords.isEmpty()) {
                        Text(
                            text = "暂无识别记录，点底部「+」拍照或选相册",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        )
                    } else {
                        recentRecords.forEachIndexed { index, record ->
                            if (index > 0) Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectRecord(record) },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                val bmp = remember(record.thumbPath) {
                                    ImageUtils.loadBitmap(record.thumbPath)
                                }
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "识别缩略图",
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable { previewRecord = record },
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        text = record.pickupCode ?: "(未识别)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = HistoryLabels.sourceLabel(record.source),
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = timeFmt.format(Date(record.createdAt)),
                                        fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                        }
                        if (historyHasMore) {
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(
                                text = "更多",
                                onClick = onHistory,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColors(
                                    textColor = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                ),
                            )
                        }
                    }
                }

                SmallTitle(
                    text = "确认并打印",
                    modifier = Modifier.offset(x = PageListDefaults.SmallTitleOffsetX)
                )
                Card(cornerRadius = 20.dp, insideMargin = PaddingValues(16.dp)) {
                    NativeMiuixTextField(
                        value = pickupCode,
                        onValueChange = onPickupCodeChange,
                        label = "上门取件码",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    NativeMiuixTextField(
                        value = remark,
                        onValueChange = onRemarkChange,
                        label = "备注（可选）",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        text = "打印取件码",
                        onClick = onPrint,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    }

    previewRecord?.let { record ->
        OverlayDialog(
            show = true,
            title = "识别原图",
            onDismissRequest = { previewRecord = null },
            liquidGlassBackdrop = liquidBackdrop,
        ) {
            val fullBmp = remember(record.thumbPath) { ImageUtils.loadBitmap(record.thumbPath) }
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (fullBmp != null) {
                    Image(
                        bitmap = fullBmp.asImageBitmap(),
                        contentDescription = "识别原图",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 480.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        "原图不可用",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    text = "填入并关闭",
                    onClick = {
                        onSelectRecord(record)
                        previewRecord = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = "关闭",
                    onClick = { previewRecord = null },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    clipboardPrompt?.let { prompt ->
        OverlayDialog(
            show = true,
            title = "检测到剪切板取件码",
            onDismissRequest = onClipboardLater,
            liquidGlassBackdrop = liquidBackdrop,
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    text = buildString {
                        append(if (!prompt.srcLabel.isNullOrBlank()) "可能来自：${prompt.srcLabel}" else "来源未知")
                        append("\n\n取件码：${prompt.code}")
                        if (prompt.candidates.size > 1) {
                            append("\n其他候选：")
                            append(prompt.candidates.filter { it != prompt.code }.take(5).joinToString("、"))
                        }
                    },
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        text = "是，填入",
                        onClick = { onClipboardYes(prompt) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                    TextButton(
                        text = "不是",
                        onClick = { onClipboardNo(prompt) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveRecognizeDialog(
    pending: MainActivity.PendingRecognizeSave,
    categories: List<CollectionCategory>,
    liquidBackdrop: Backdrop?,
    onDismiss: () -> Unit,
    onSave: (categoryId: String, alsoPrint: Boolean) -> Unit,
) {
    val defaultId = remember(pending, categories) {
        categories.firstOrNull { it.kind == pending.defaultKind }?.id
            ?: categories.firstOrNull()?.id
    }
    var selectedId by remember(defaultId) { mutableStateOf(defaultId) }

    OverlayDialog(
        show = true,
        title = "保存到记录",
        onDismissRequest = onDismiss,
        liquidGlassBackdrop = liquidBackdrop,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "识别结果：${pending.code}",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Text(
                text = "选择分类",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                categories.forEach { cat ->
                    val selected = cat.id == selectedId
                    Surface(
                        modifier = Modifier
                            .clip(ContinuousRoundedRectangle(20.dp))
                            .clickable { selectedId = cat.id },
                        color = if (selected) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.surfaceVariant
                        },
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 14.dp)
                                .height(34.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = cat.name,
                                fontSize = 13.sp,
                                color = if (selected) {
                                    MiuixTheme.colorScheme.onPrimary
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantActions
                                },
                            )
                        }
                    }
                }
            }
            TextButton(
                text = "保存并打印",
                onClick = {
                    val id = selectedId ?: return@TextButton
                    onSave(id, true)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                enabled = selectedId != null,
            )
            TextButton(
                text = "仅保存",
                onClick = {
                    val id = selectedId ?: return@TextButton
                    onSave(id, false)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedId != null,
            )
            TextButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

object HistoryLabels {
    fun sourceLabel(source: String): String = when {
        source == "overlay" || source == "screenshot" -> "截屏"
        source.startsWith("screenshot:") ->
            "截屏·${source.removePrefix("screenshot:").trim()}"
        source == "camera" || source == "camera_quick" -> "拍照"
        source == "gallery" -> "相册"
        source == "clipboard" -> "剪切板"
        source.startsWith("clipboard:") ->
            "剪切板·${source.removePrefix("clipboard:").trim()}"
        else -> source
    }

    /** 用于备注：去掉 screenshot:/clipboard: 等技术前缀，只留应用名。 */
    fun remarkFromSource(source: String?): String? = cleanRemark(source)

    fun cleanRemark(note: String?): String? {
        var raw = note?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val prefixes = listOf(
            "screenshot:",
            "screenshot：",
            "clipboard:",
            "clipboard：",
            "截屏·",
            "截屏:",
            "截屏：",
            "剪切板·",
            "剪切板:",
            "剪切板：",
        )
        var stripped = true
        while (stripped) {
            stripped = false
            for (prefix in prefixes) {
                if (raw.length >= prefix.length &&
                    raw.substring(0, prefix.length).equals(prefix, ignoreCase = true)
                ) {
                    raw = raw.substring(prefix.length).trim()
                    stripped = true
                    break
                }
            }
        }
        if (raw.isBlank()) return null
        val technical = setOf(
            "screenshot", "clipboard", "overlay", "camera", "camera_quick",
            "gallery", "manual", "截屏", "剪切板",
        )
        if (technical.any { it.equals(raw, ignoreCase = true) }) return null
        return raw
    }
}
