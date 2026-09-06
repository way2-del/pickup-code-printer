# 上门取件码打印（德佟 P2）

基于 [德佟 Android LPAPI](https://detonger.com/#/sdk/detail?sdkID=16BC0F46-ACC4-4EBB-9340-47328936E779)（`LPAPI-2026-01-08-R.jar`）的上门取件码识别与打印 App。

## 功能

1. **打印机**：蓝牙搜索德佟 P2 等设备，列表展示，手动刷新 / 手动连接 / 断开
2. **取码**：无障碍截屏（Android 11+）/ 拍照 / 相册选图
3. **OCR**：ML Kit 中文识别，自动提取「上门取件码」等字段，可人工修改
4. **打印**：按标签格式打印标题「上门取件码」+ 大号码 + 一维码

## 使用步骤

1. 用 Android Studio 打开本工程，连接真机
2. 授予蓝牙、定位、相机权限
3. 系统设置 → 无障碍 → 开启「上门取件码打印」
4. 打开快递 App 显示取件码 → 回到本 App 点「无障碍截屏识别」（或拍照）
5. 核对编辑框中的取件码 → 刷新并连接打印机 →「打印取件码」

## SDK 说明

- JAR：`app/libs/LPAPI-2026-01-08-R.jar`
- 官方文档与 Demo 来自德佟/道臻 LPAPI Android SDK
- 核心调用：`discovery` / `openPrinterByAddress` / `startJob` / `drawText` / `draw1DBarcode` / `commitJob`

## 标签尺寸

默认 `50mm × 40mm`（适配 P2 常用宽度），可在 `PrinterManager.printPickupCode` 中调整。
