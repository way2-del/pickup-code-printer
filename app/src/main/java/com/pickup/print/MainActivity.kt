package com.pickup.print

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dothantech.printer.IDzPrinter.PrinterAddress
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pickup.print.databinding.ActivityMainBinding
import com.pickup.print.databinding.DialogPrinterMenuBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity(), PrinterManager.Listener, CaptureBus.Callback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var printerManager: PrinterManager
    private lateinit var printerAdapter: PrinterAdapter
    private lateinit var historyRepo: RecognitionHistoryRepository
    private lateinit var printPrefs: PrintPrefs

    private var recognizing = false
    private var latestThumbPath: String? = null
    private var autoConnectAttempted = false
    private var pendingAutoConnect = true
    private var clipboardDialog: AlertDialog? = null
    private var clipboardPromptedThisResume = false
    private var printerPopup: PopupWindow? = null
    private var printerMenuBinding: DialogPrinterMenuBinding? = null
    private var lastPrinterStatusMessage: String = ""
    private var printerConnected: Boolean = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { !it }) {
            toast("部分权限未授予，蓝牙搜索/拍照可能不可用")
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) handleImageUri(uri)
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val path = result.data?.getStringExtra(CameraCaptureActivity.EXTRA_BITMAP_PATH)
            ?: return@registerForActivityResult
        val bmp = android.graphics.BitmapFactory.decodeFile(path)
        if (bmp != null) runOcr(bmp, source = "camera")
    }

    private val historyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val code = result.data?.getStringExtra(HistoryActivity.EXTRA_USE_CODE)?.trim().orEmpty()
            if (code.isNotEmpty()) {
                binding.etPickupCode.setText(code)
                binding.etPickupCode.setSelection(code.length)
                toast("已填入历史取件码：$code")
            }
        }
        showLatestPreview(historyRepo.latest())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root)

        historyRepo = RecognitionHistoryRepository(this)
        printPrefs = PrintPrefs(this)
        printerManager = PrinterManager(this)
        printerAdapter = PrinterAdapter(
            onConnect = { address ->
                ensureBluetooth {
                    if (!printerManager.connect(address)) {
                        toast("连接请求失败")
                    }
                }
            },
            onSetDefault = { address ->
                val key = PrinterAdapter.keyOf(address)
                printPrefs.setDefaultPrinter(key, PrinterManager.displayName(address))
                printerAdapter.setDefaultKey(key)
                refreshPrinterHeader()
                toast("已设为默认：${PrinterManager.displayName(address)}")
                if (!printerManager.isConnected()) {
                    ensureBluetooth { printerManager.connect(address) }
                }
            }
        )
        printerAdapter.setDefaultKey(printPrefs.defaultPrinterKey)

        binding.btnPrinterMenu.setOnClickListener { showPrinterMenu() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnTakePhoto.setOnClickListener {
            ensureCamera {
                cameraLauncher.launch(Intent(this, CameraCaptureActivity::class.java))
            }
        }
        binding.btnPickImage.setOnClickListener { pickImageLauncher.launch("image/*") }
        binding.btnHistory.setOnClickListener {
            historyLauncher.launch(Intent(this, HistoryActivity::class.java))
        }
        binding.btnPrint.setOnClickListener { printCode() }
        binding.ivPreview.setOnClickListener {
            ImagePreviewDialog.showFromPath(this, latestThumbPath)
        }

        requestRuntimePermissions()
        handleCaptureIntent(intent)
        showLatestPreview(historyRepo.latest())
        refreshPrinterHeader()
        startAutoConnectScan()
        KeepAliveService.start(this)
        maybeAskBatteryWhitelist()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCaptureIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        CaptureBus.setCallback(this)
        PickupCaptureAccessibilityService.lastBitmap?.let { bmp ->
            PickupCaptureAccessibilityService.lastBitmap = null
            runOcr(bmp, source = "screenshot")
        }
        showLatestPreview(historyRepo.latest())
        refreshPrinterHeader()
        clipboardPromptedThisResume = false
        binding.root.postDelayed({ maybePromptClipboard() }, 350)
    }

    override fun onPause() {
        CaptureBus.setCallback(null)
        clipboardDialog?.dismiss()
        clipboardDialog = null
        dismissPrinterMenu()
        super.onPause()
    }

    override fun onDestroy() {
        dismissPrinterMenu()
        printerManager.quit()
        super.onDestroy()
    }

    override fun onPrinterListChanged(devices: List<PrinterAddress>) {
        printerAdapter.submitList(devices) {
            printerAdapter.setConnected(printerManager.connectedAddress)
            printerAdapter.setDefaultKey(printPrefs.defaultPrinterKey)
        }
        maybeAutoConnect(devices)
    }

    override fun onPrinterState(
        message: String,
        connected: Boolean,
        connectedAddress: PrinterAddress?
    ) {
        lastPrinterStatusMessage = message
        printerConnected = connected
        printerAdapter.setConnected(connectedAddress)
        refreshPrinterHeader()
        if (connected && connectedAddress != null) {
            if (printPrefs.defaultPrinterKey.isNullOrBlank()) {
                printPrefs.setDefaultPrinter(
                    PrinterAdapter.keyOf(connectedAddress),
                    PrinterManager.displayName(connectedAddress)
                )
                printerAdapter.setDefaultKey(printPrefs.defaultPrinterKey)
                refreshPrinterHeader()
            }
        }
    }

    override fun onPrintResult(success: Boolean, message: String) {
        toast(message)
    }

    override fun onCaptured(bitmap: Bitmap) {
        runOcr(bitmap, source = "screenshot")
    }

    override fun onFailed(message: String) {
        toast(message)
    }

    private fun showPrinterMenu() {
        if (printerPopup?.isShowing == true) {
            dismissPrinterMenu()
            return
        }
        val menu = DialogPrinterMenuBinding.inflate(LayoutInflater.from(this))
        printerMenuBinding = menu
        menu.rvPopupPrinters.layoutManager = LinearLayoutManager(this)
        menu.rvPopupPrinters.adapter = printerAdapter
        menu.btnPopupRefresh.setOnClickListener {
            ensureBluetooth {
                requestRuntimePermissions()
                pendingAutoConnect = true
                autoConnectAttempted = false
                printerManager.refreshDiscovery()
                toast("正在搜索打印机…")
            }
        }
        menu.btnPopupDisconnect.setOnClickListener { printerManager.disconnect() }
        syncPrinterMenuTexts()

        val width = binding.btnPrinterMenu.width.coerceAtLeast(
            (resources.displayMetrics.widthPixels * 0.88f).toInt()
        )
        val popup = PopupWindow(
            menu.root,
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 12f
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            isOutsideTouchable = true
            setOnDismissListener {
                printerPopup = null
                printerMenuBinding = null
            }
        }
        printerPopup = popup
        popup.showAsDropDown(binding.btnPrinterMenu, 0, 8, Gravity.START)

        // 打开时顺便刷一次设备列表
        ensureBluetooth {
            printerManager.refreshDiscovery()
        }
    }

    private fun dismissPrinterMenu() {
        printerPopup?.dismiss()
        printerPopup = null
        printerMenuBinding = null
    }

    private fun syncPrinterMenuTexts() {
        val menu = printerMenuBinding ?: return
        val status = lastPrinterStatusMessage.ifBlank {
            if (printerConnected) "已连接" else getString(R.string.printer_idle)
        }
        menu.tvPopupStatus.text = status
        menu.tvPopupStatus.setTextColor(
            ContextCompat.getColor(this, if (printerConnected) R.color.accent else R.color.muted)
        )
        val defaultName = printPrefs.defaultPrinterName
        menu.tvPopupDefault.text = if (defaultName.isNullOrBlank()) {
            "默认：未设置（多台时请点「设默认」）"
        } else {
            "默认：$defaultName（启动自动连接）"
        }
    }

    private fun refreshPrinterHeader() {
        val connected = printerManager.connectedAddress
        val title = when {
            connected != null -> PrinterManager.displayName(connected)
            !printPrefs.defaultPrinterName.isNullOrBlank() -> printPrefs.defaultPrinterName!!
            else -> getString(R.string.printer_idle)
        }
        binding.tvPrinterTitle.text = title
        binding.tvPrinterTitle.setTextColor(
            ContextCompat.getColor(this, if (printerConnected || connected != null) R.color.accent else R.color.ink)
        )

        val status = lastPrinterStatusMessage.ifBlank {
            when {
                connected != null -> "已连接 · 点此管理打印机"
                !printPrefs.defaultPrinterName.isNullOrBlank() -> "默认机未连接 · 点此搜索连接"
                else -> "点此选择 / 连接打印机"
            }
        }
        binding.tvPrinterStatus.text = status
        binding.tvPrinterStatus.setTextColor(
            ContextCompat.getColor(this, if (printerConnected) R.color.accent else R.color.muted)
        )
        syncPrinterMenuTexts()
    }

    private fun handleCaptureIntent(intent: Intent?) {
        if (intent == null) return
        val path = intent.getStringExtra(CameraCaptureActivity.EXTRA_BITMAP_PATH)
        if (!path.isNullOrBlank()) {
            intent.removeExtra(CameraCaptureActivity.EXTRA_BITMAP_PATH)
            val bmp = android.graphics.BitmapFactory.decodeFile(path)
            if (bmp != null) {
                val source = if (intent.getBooleanExtra(EXTRA_FROM_CAMERA_QUICK, false)) {
                    "camera_quick"
                } else {
                    "camera"
                }
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

    private fun maybeAskBatteryWhitelist(force: Boolean = false) {
        if (KeepAliveService.isIgnoringBatteryOptimizations(this) && !force) return
        if (!force && !shouldPromptBattery()) return
        try {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
            markBatteryPrompted()
        } catch (_: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun shouldPromptBattery(): Boolean {
        val p = getSharedPreferences("keepalive", MODE_PRIVATE)
        return !p.getBoolean("battery_asked", false)
    }

    private fun markBatteryPrompted() {
        getSharedPreferences("keepalive", MODE_PRIVATE).edit().putBoolean("battery_asked", true).apply()
    }

    private fun runOcr(bitmap: Bitmap, source: String) {
        if (recognizing) return
        recognizing = true
        toast("正在识别取件码…")

        val captureTarget = if (source == "screenshot" || source.startsWith("screenshot")) {
            PickupCaptureAccessibilityService.consumeCaptureTarget(clear = true)
        } else {
            null to null
        }
        val srcPkg = captureTarget.first
        val srcLabel = srcPkg?.let { AppInfoHelper.resolveLabel(this, it) }
            ?: captureTarget.second?.takeIf { it.isNotBlank() }
        val sourceTag = when {
            source == "screenshot" && !srcLabel.isNullOrBlank() -> "screenshot:$srcLabel"
            source == "screenshot" -> "screenshot"
            else -> source
        }

        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.Default) {
                    OcrHelper.recognize(bitmap)
                }
                val parsed = PickupCodeParser.parse(text)
                val thumb = withContext(Dispatchers.Default) {
                    ImageUtils.createThumbnail(bitmap, maxSide = 360)
                }
                val tmp = File(cacheDir, "tmp_${System.currentTimeMillis()}.jpg")
                withContext(Dispatchers.IO) {
                    ImageUtils.saveJpeg(thumb, tmp, quality = 80)
                }

                val record = historyRepo.add(
                    pickupCode = parsed.code,
                    candidates = parsed.candidates,
                    ocrText = text,
                    thumbJpeg = tmp,
                    source = sourceTag,
                    sourcePackage = srcPkg
                )
                showLatestPreview(record)
                if (!srcLabel.isNullOrBlank()) {
                    binding.etRemark.setText(srcLabel)
                }

                if (parsed.code != null) {
                    binding.etPickupCode.setText(parsed.code)
                    binding.etPickupCode.setSelection(parsed.code.length)
                    toast("已识别取件码：${parsed.code}")
                } else {
                    toast("未识别到取件码，可到历史查看 OCR 详情")
                }
            } catch (e: Exception) {
                toast("OCR 失败：${e.message}")
            } finally {
                recognizing = false
            }
        }
    }

    private fun showLatestPreview(record: RecognitionRecord?) {
        if (record == null) {
            binding.rowLatestPreview.visibility = View.GONE
            latestThumbPath = null
            return
        }
        latestThumbPath = record.thumbPath
        val thumb = ImageUtils.loadBitmap(record.thumbPath)
        if (thumb != null) {
            val isClipboard = record.source == "clipboard" || record.source.startsWith("clipboard:")
            binding.ivPreview.scaleType =
                if (isClipboard) android.widget.ImageView.ScaleType.FIT_CENTER
                else android.widget.ImageView.ScaleType.CENTER_CROP
            binding.ivPreview.setImageBitmap(thumb)
            binding.rowLatestPreview.visibility = View.VISIBLE
            val appHint = HistoryAdapter.sourceLabel(record.source)
            binding.tvLatestCodeHint.text =
                (record.pickupCode ?: "(未识别到取件码 · 点历史看详情)") + " · $appHint"
        } else {
            binding.rowLatestPreview.visibility = View.GONE
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
        val code = binding.etPickupCode.text?.toString()?.trim().orEmpty()
        if (code.isEmpty()) {
            toast("请先确认取件码")
            return
        }
        if (!printerManager.isConnected()) {
            toast("请先连接德佟打印机")
            return
        }
        val remark = binding.etRemark.text?.toString()
        printerManager.printPickupCode(code, remark, printPrefs.loadLayout())
    }

    private fun startAutoConnectScan() {
        ensureBluetooth {
            pendingAutoConnect = true
            autoConnectAttempted = false
            printerManager.refreshDiscovery()
            lastPrinterStatusMessage = "正在搜索并连接默认打印机…"
            refreshPrinterHeader()
        }
    }

    private fun maybeAutoConnect(devices: List<PrinterAddress>) {
        if (!pendingAutoConnect || autoConnectAttempted) return
        if (printerManager.isConnected()) {
            pendingAutoConnect = false
            return
        }
        if (devices.isEmpty()) return

        val defaultKey = printPrefs.defaultPrinterKey
        val target = when {
            !defaultKey.isNullOrBlank() ->
                devices.find { PrinterAdapter.keyOf(it) == defaultKey }
                    ?: devices.find { it.shownName == printPrefs.defaultPrinterName }
            devices.size == 1 -> devices.first().also {
                printPrefs.setDefaultPrinter(
                    PrinterAdapter.keyOf(it),
                    PrinterManager.displayName(it)
                )
                printerAdapter.setDefaultKey(printPrefs.defaultPrinterKey)
                refreshPrinterHeader()
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
        } else if (devices.isNotEmpty() && !defaultKey.isNullOrBlank()) {
            // 默认机尚未出现在列表，继续等 discovery
        } else if (devices.size > 1 && defaultKey.isNullOrBlank()) {
            pendingAutoConnect = false
            toast("发现多台打印机，请点「设默认」指定开机自动连接")
        }
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        needed += Manifest.permission.ACCESS_FINE_LOCATION
        needed += Manifest.permission.ACCESS_COARSE_LOCATION
        needed += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
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

    private fun saveClipboardToHistory(
        code: String,
        candidates: List<String>,
        ocrText: String,
        srcLabel: String?,
        srcPkg: String?
    ) {
        lifecycleScope.launch {
            try {
                val label = when {
                    !srcPkg.isNullOrBlank() -> AppInfoHelper.resolveLabel(this@MainActivity, srcPkg)
                    !srcLabel.isNullOrBlank() -> srcLabel
                    else -> null
                }
                val sourceTag = when {
                    !label.isNullOrBlank() -> "clipboard:$label"
                    else -> "clipboard"
                }
                val tmp = withContext(Dispatchers.IO) {
                    val file = File(cacheDir, "tmp_clipboard_${System.currentTimeMillis()}.jpg")
                    if (!srcPkg.isNullOrBlank()) {
                        AppInfoHelper.saveIconThumbJpeg(this@MainActivity, srcPkg, file)
                            ?: run {
                                val bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(0xFFE8EEF5.toInt())
                                ImageUtils.saveJpeg(bmp, file, quality = 70)
                                bmp.recycle()
                                file
                            }
                    } else {
                        val bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(0xFFE8EEF5.toInt())
                        ImageUtils.saveJpeg(bmp, file, quality = 70)
                        bmp.recycle()
                        file
                    }
                }
                val record = historyRepo.add(
                    pickupCode = code,
                    candidates = candidates,
                    ocrText = ocrText,
                    thumbJpeg = tmp,
                    source = sourceTag,
                    sourcePackage = srcPkg
                )
                showLatestPreview(record)
            } catch (_: Exception) {
                // 填入已成功，历史写入失败不打断主流程
            }
        }
    }

    private fun maybePromptClipboard() {
        if (isFinishing || isDestroyed) return
        if (clipboardPromptedThisResume) return
        if (clipboardDialog?.isShowing == true) return
        // 若刚从截屏/拍照回来，先别抢剪切板弹窗
        if (recognizing) return

        val snap = ClipboardProbe.read(this) ?: return
        val prefs = getSharedPreferences("clipboard_probe", MODE_PRIVATE)
        if (ClipboardProbe.wasHandled(prefs, snap.fingerprint)) return

        val code = snap.code
        if (code.isNullOrBlank()) return

        clipboardPromptedThisResume = true
        val (srcPkg, rawLabel) = PickupCaptureAccessibilityService.guessClipboardSourceApp()
        val srcLabel = when {
            !srcPkg.isNullOrBlank() -> AppInfoHelper.resolveLabel(this, srcPkg)
            !rawLabel.isNullOrBlank() -> rawLabel
            else -> null
        }
        val sourceLine = when {
            !srcLabel.isNullOrBlank() -> "可能来自：$srcLabel"
            !srcPkg.isNullOrBlank() -> "可能来自：$srcPkg"
            else -> "来源应用：未知（开启无障碍后可尝试识别）"
        }
        val preview = snap.rawText.replace('\n', ' ').trim().let {
            if (it.length > 120) it.take(120) + "…" else it
        }
        val message = buildString {
            append(sourceLine)
            append("\n\n剪切板内容：\n")
            append(preview)
            append("\n\n提取到的取件码：")
            append(code)
            if (snap.candidates.size > 1) {
                append("\n其他候选：")
                append(snap.candidates.filter { it != code }.take(5).joinToString("、"))
            }
            append("\n\n是否使用该取件码？")
        }

        clipboardDialog = MaterialAlertDialogBuilder(this)
            .setTitle("检测到剪切板取件码")
            .setMessage(message)
            .setPositiveButton("是，填入") { _, _ ->
                ClipboardProbe.markHandled(prefs, snap.fingerprint)
                binding.etPickupCode.setText(code)
                binding.etPickupCode.setSelection(code.length)
                if (!srcLabel.isNullOrBlank()) {
                    binding.etRemark.setText(srcLabel)
                }
                saveClipboardToHistory(
                    code = code,
                    candidates = snap.candidates,
                    ocrText = snap.rawText,
                    srcLabel = srcLabel,
                    srcPkg = srcPkg
                )
                toast("已填入取件码：$code")
            }
            .setNegativeButton("不是") { _, _ ->
                ClipboardProbe.markHandled(prefs, snap.fingerprint)
            }
            .setNeutralButton("稍后") { _, _ ->
                // 不标记，下次进 App 还可再问
            }
            .setOnDismissListener { clipboardDialog = null }
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_FROM_CAPTURE = "from_capture"
        const val EXTRA_FROM_CAMERA_QUICK = "from_camera_quick"
    }
}
