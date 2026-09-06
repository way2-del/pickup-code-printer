package com.pickup.print

import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import com.dothantech.lpapi.LPAPI
import com.dothantech.printer.IDzPrinter
import com.dothantech.printer.IDzPrinter.PrinterAddress
import com.dothantech.printer.IDzPrinter.PrinterState
import com.dothantech.printer.IDzPrinter.PrintProgress
import com.dothantech.printer.IDzPrinter.ProgressInfo

/**
 * 封装德佟 / 道臻 LPAPI（P2 等蓝牙标签机）。
 * 文档参考：https://detonger.com/#/sdk/detail?sdkID=16BC0F46-ACC4-4EBB-9340-47328936E779
 */
class PrinterManager(private val listener: Listener) {

    interface Listener {
        fun onPrinterListChanged(devices: List<PrinterAddress>)
        fun onPrinterState(message: String, connected: Boolean, connectedAddress: PrinterAddress?)
        fun onPrintResult(success: Boolean, message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val devices = LinkedHashMap<String, PrinterAddress>()
    private var connectingName: String? = null
    var connectedAddress: PrinterAddress? = null
        private set

    private val callback = object : LPAPI.Callback {
        override fun onStateChange(address: PrinterAddress?, state: PrinterState?) {
            Log.d(TAG, "onStateChange: $state")
            when (state) {
                PrinterState.Connected, PrinterState.Connected2 -> {
                    mainHandler.post {
                        connectingName = null
                        connectedAddress = address
                        val name = address?.shownName ?: api.getPrinterName() ?: "打印机"
                        listener.onPrinterState("已连接：$name", true, address)
                    }
                }
                PrinterState.Disconnected -> {
                    mainHandler.post {
                        connectingName = null
                        connectedAddress = null
                        listener.onPrinterState("未连接打印机", false, null)
                    }
                }
                PrinterState.Connecting -> {
                    mainHandler.post {
                        listener.onPrinterState("正在连接…", false, null)
                    }
                }
                else -> Unit
            }
        }

        override fun onProgressInfo(info: ProgressInfo?, obj: Any?) = Unit

        override fun onPrinterDiscovery(address: PrinterAddress?, obj: Any?) {
            if (address == null) return
            val key = address.macAddress?.takeIf { it.isNotBlank() } ?: address.shownName ?: return
            synchronized(devices) {
                if (devices.containsKey(key)) return
                devices[key] = address
            }
            mainHandler.post {
                listener.onPrinterListChanged(snapshot())
            }
        }

        override fun onPrintProgress(
            address: PrinterAddress?,
            data: IDzPrinter.PrintData?,
            progress: PrintProgress?,
            addiInfo: Any?
        ) {
            when (progress) {
                PrintProgress.Success -> mainHandler.post {
                    listener.onPrintResult(true, "打印成功")
                }
                PrintProgress.Failed -> mainHandler.post {
                    listener.onPrintResult(false, "打印失败")
                }
                else -> Unit
            }
        }
    }

    val api: LPAPI = LPAPI.Factory.createInstance(callback)

    fun snapshot(): List<PrinterAddress> = synchronized(devices) { devices.values.toList() }

    fun refreshDiscovery() {
        api.stopDiscovery()
        synchronized(devices) { devices.clear() }
        listener.onPrinterListChanged(emptyList())
        api.discovery()
    }

    fun stopDiscovery() {
        api.stopDiscovery()
    }

    fun connect(address: PrinterAddress): Boolean {
        stopDiscovery()
        connectingName = address.shownName
        listener.onPrinterState("正在连接：${address.shownName}", false, null)
        return api.openPrinterByAddress(address)
    }

    fun disconnect() {
        api.closePrinter()
        connectedAddress = null
        listener.onPrinterState("未连接打印机", false, null)
    }

    fun addressKey(address: PrinterAddress?): String? {
        if (address == null) return null
        return address.macAddress?.takeIf { it.isNotBlank() } ?: address.shownName
    }

    fun isConnected(): Boolean {
        val state = api.printerState ?: return false
        return state == PrinterState.Connected || state == PrinterState.Connected2
    }

    fun quit() {
        stopDiscovery()
        api.quit()
    }

    /**
     * 按用户保存的纸张尺寸 / 预设 / 显示内容打印取件码。
     */
    fun printPickupCode(code: String, remark: String?, layout: PrintLayoutConfig): Boolean {
        if (!isConnected()) {
            listener.onPrintResult(false, "请先连接打印机")
            return false
        }
        val clean = code.trim()
        if (clean.isEmpty()) {
            listener.onPrintResult(false, "取件码为空")
            return false
        }

        val width = layout.paperWidthMm.toDouble()
        val height = layout.paperHeightMm.toDouble()
        api.startJob(width, height, 0)

        // 预览里文字是画布居中；LPAPI 默认左对齐，这里改为区域内水平/垂直居中
        api.setItemHorizontalAlignment(LPAPI.ItemAlignment.CENTER)
        api.setItemVerticalAlignment(LPAPI.ItemAlignment.MIDDLE)

        val elements = PrintLayoutEngine.buildElements(layout, clean, remark)
        for (el in elements) {
            when (el.kind) {
                PrintLayoutEngine.ElementBox.Kind.BARCODE -> {
                    api.draw1DBarcode(
                        el.text,
                        LPAPI.BarcodeType.AUTO,
                        el.x, el.y, el.w, el.h, el.fontMm
                    )
                }
                else -> {
                    api.drawText(el.text, el.x, el.y, el.w, el.h, el.fontMm)
                }
            }
        }

        val ok = api.commitJob()
        if (!ok) {
            listener.onPrintResult(false, "提交打印任务失败")
        }
        return ok
    }

    companion object {
        private const val TAG = "PrinterManager"

        fun displayName(address: PrinterAddress): String {
            return if (!TextUtils.isEmpty(address.shownName)) address.shownName else address.macAddress ?: "未知设备"
        }
    }
}
