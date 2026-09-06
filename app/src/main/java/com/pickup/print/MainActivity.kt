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
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dothantech.printer.IDzPrinter.PrinterAddress
import com.pickup.print.databinding.ActivityMainBinding
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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { !it }) {
            toast("部分权限未授予，蓝牙搜索/拍照可能不可用")
        }
    }

    private val accessibilitySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshCaptureStatus()
        if (PickupCaptureAccessibilityService.isUsable(this)) {
            KeepAliveService.start(this)
            refreshCaptureStatus()
            toast("无障碍已就绪，可用通知栏「截屏」")
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
                refreshDefaultPrinterLabel()
                toast("已设为默认：${PrinterManager.displayName(address)}")
                if (!printerManager.isConnected()) {
                    ensureBluetooth { printerManager.connect(address) }
                }
            }
        )

        binding.rvPrinters.layoutManager = LinearLayoutManager(this)
        binding.rvPrinters.adapter = printerAdapter
        printerAdapter.setDefaultKey(printPrefs.defaultPrinterKey)
        refreshDefaultPrinterLabel()

        binding.btnRefreshPrinters.setOnClickListener {
            ensureBluetooth {
                requestRuntimePermissions()
                pendingAutoConnect = true
                autoConnectAttempted = false
                printerManager.refreshDiscovery()
                toast("正在搜索打印机…")
            }
        }
        binding.btnDisconnect.setOnClickListener { printerManager.disconnect() }
        binding.btnEnableAccessibility.setOnClickListener {
            accessibilitySettingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnKeepAlive.setOnClickListener { enableQuickEntry() }
        binding.btnTakePhoto.setOnClickListener {
            ensureCamera {
                cameraLauncher.launch(Intent(this, CameraCaptureActivity::class.java))
            }
        }
        binding.btnPickImage.setOnClickListener { pickImageLauncher.launch("image/*") }
        binding.btnHistory.setOnClickListener {
            historyLauncher.launch(Intent(this, HistoryActivity::class.java))
        }
        binding.btnPrintLayout.setOnClickListener {
            startActivity(Intent(this, PrintLayoutActivity::class.java))
        }
        binding.btnPrint.setOnClickListener { printCode() }
        binding.ivPreview.setOnClickListener {
            ImagePreviewDialog.showFromPath(this, latestThumbPath)
        }

        requestRuntimePermissions()
        handleCaptureIntent(intent)
        showLatestPreview(historyRepo.latest())
        startAutoConnectScan()
        // 启动即挂快捷通知（拍照 / 截屏）
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
        refreshCaptureStatus()
        PickupCaptureAccessibilityService.lastBitmap?.let { bmp ->
            PickupCaptureAccessibilityService.lastBitmap = null
            runOcr(bmp, source = "screenshot")
        }
        showLatestPreview(historyRepo.latest())
    }

    override fun onPause() {
        CaptureBus.setCallback(null)
        super.onPause()
    }

    override fun onDestroy() {
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
        binding.tvPrinterStatus.text = message
        binding.tvPrinterStatus.setTextColor(
            ContextCompat.getColor(this, if (connected) R.color.accent else R.color.muted)
        )
        printerAdapter.setConnected(connectedAddress)
        if (connected && connectedAddress != null) {
            // 首次成功连接且未设置默认时，自动记为默认
            if (printPrefs.defaultPrinterKey.isNullOrBlank()) {
                printPrefs.setDefaultPrinter(
                    PrinterAdapter.keyOf(connectedAddress),
                    PrinterManager.displayName(connectedAddress)
                )
                printerAdapter.setDefaultKey(printPrefs.defaultPrinterKey)
                refreshDefaultPrinterLabel()
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

    private fun enableQuickEntry() {
        KeepAliveService.start(this)
        maybeAskBatteryWhitelist(force = true)
        try {
            val miui = Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
            if (miui.resolveActivity(packageManager) != null) {
                startActivity(miui)
            }
        } catch (_: Exception) {
        }
        refreshCaptureStatus()
        toast("已开启通知栏快捷入口：拍照 / 截屏；点通知可回 App")
    }

    private fun maybeAskBatteryWhitelist(force: Boolean = false) {
        if (KeepAliveService.isIgnoringBatteryOptimizations(this) && !force) return
        if (!force && !shouldPromptBattery()) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
            markBatteryPrompted()
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
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

    private fun refreshCaptureStatus() {
        val keepOn = KeepAliveService.running
        val battOk = KeepAliveService.isIgnoringBatteryOptimizations(this)
        binding.tvQuickEntryStatus.text = when {
            keepOn && battOk -> "快捷通知：已开启（拍照 / 截屏）· 电池无限制"
            keepOn -> "快捷通知：已开启 · 建议设电池无限制"
            else -> "快捷通知：未开启（点下方按钮开启）"
        }
        binding.tvQuickEntryStatus.setTextColor(
            ContextCompat.getColor(this, if (keepOn) R.color.accent else R.color.muted)
        )

        val a11yOn = PickupCaptureAccessibilityService.isEnabledInSettings(this)
        val a11yLive = PickupCaptureAccessibilityService.isRunning()
        binding.tvAccessibilityStatus.text = when {
            a11yLive -> "无障碍：已开启且在线（截屏可用）"
            a11yOn -> "无障碍：已开启，重连中…（稍等或回前台）"
            else -> "无障碍：未开启（通知栏「截屏」必需）"
        }
        binding.tvAccessibilityStatus.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    a11yLive -> R.color.accent
                    a11yOn -> R.color.accent
                    else -> R.color.danger
                }
            )
        )
    }

    private fun runOcr(bitmap: Bitmap, source: String) {
        if (recognizing) return
        recognizing = true
        toast("正在识别取件码…")

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
                    source = source
                )
                showLatestPreview(record)

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
            binding.ivPreview.setImageBitmap(thumb)
            binding.rowLatestPreview.visibility = View.VISIBLE
            binding.tvLatestCodeHint.text = record.pickupCode ?: "(未识别到取件码 · 点历史看详情)"
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
            binding.tvPrinterStatus.text = "正在搜索并连接默认打印机…"
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
                // 仅一台时自动设为默认
                printPrefs.setDefaultPrinter(
                    PrinterAdapter.keyOf(it),
                    PrinterManager.displayName(it)
                )
                printerAdapter.setDefaultKey(printPrefs.defaultPrinterKey)
                refreshDefaultPrinterLabel()
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

    private fun refreshDefaultPrinterLabel() {
        val name = printPrefs.defaultPrinterName
        binding.tvDefaultPrinter.text = if (name.isNullOrBlank()) {
            "默认打印机：未设置（多台时请点「设默认」）"
        } else {
            "默认打印机：$name（启动自动连接）"
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

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_FROM_CAPTURE = "from_capture"
        const val EXTRA_FROM_CAMERA_QUICK = "from_camera_quick"
    }
}
