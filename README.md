# 上门取件码打印（德佟 P2）

基于 [德佟 Android LPAPI](https://detonger.com/#/sdk/detail?sdkID=16BC0F46-ACC4-4EBB-9340-47328936E779)（`LPAPI-2026-01-08-R.jar`）的上门取件码识别与标签打印 App。

目标机型：德佟 / 道臻 **P2** 等蓝牙热敏标签机（约 203dpi）。

## 功能概览

1. **打印机**：BLE 搜索、列表、刷新、连接 / 断开；可设默认打印机，启动后自动回连
2. **取码**：悬浮球 + **无障碍截屏**（优先）、拍照、相册选图
3. **OCR**：Google ML Kit 中文文字识别，解析上门取件码，可人工改后再打
4. **打印编排**：纸张尺寸、竖向 / 横向、行顺序拖拽交换、按元素调字号与对齐
5. **历史记录**：缩略图 + OCR 全文
6. **保活**：前台服务、开机启动、电池白名单提示（尽量保住无障碍截屏能力）

## 核心技术说明

### 1. 打印画布：德佟 LPAPI（毫米坐标系）

打印不是自己画 Bitmap 再发图，而是走官方 **LPAPI 矢量/位图画布**：

| 步骤 | API | 说明 |
|------|-----|------|
| 开任务 | `startJob(widthMm, heightMm, orientation)` | 创建标签画布；`orientation` 为 `0/90/180/270` |
| 对齐 | `setItemHorizontalAlignment` / `setItemVerticalAlignment` | 控制后续文字、条码在框内的对齐 |
| 文字 | `drawText(text, x, y, w, h, fontHeightMm, style)` | 坐标与字号单位均为 **毫米** |
| 一维码 | `draw1DBarcode(...)` | 同坐标系 |
| 提交 | `commitJob()` | 下发到打印机 |

横向打印时：排版仍按「上标题 / 中取件码 / 下备注」竖排；逻辑宽高互换后 `startJob(..., 90)`，横着看方向正确。

相关代码：`PrinterManager.kt`、`PrintLayoutEngine.kt`。

### 2. 预览画布：Android `Canvas`（自绘 View）

编排页预览用自定义 View `LabelPreviewView`：

- 按纸张毫米比例缩放到屏幕
- 用 `android.graphics.Canvas` + `Paint` 画白纸、文字、假条码、选中框
- 与打印共用 `PrintLayoutEngine.buildElements(...)`，保证预览布局与打印坐标一致

相关代码：`LabelPreviewView.kt`、`activity_print_layout.xml`。

### 3. OCR：Google ML Kit 中文文字识别

依赖：`com.google.mlkit:text-recognition-chinese:16.0.1`

流程：

1. `InputImage.fromBitmap(bitmap, 0)`
2. `TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())`
3. 得到全文 → `PickupCodeParser` 提取取件码（如「上门取件码」附近数字）
4. 结果进编辑框，确认后再 `printPickupCode`

相关代码：`OcrHelper.kt`、`PickupCodeParser.kt`、`MainActivity.runOcr`。

### 4. 截屏：无障碍 `takeScreenshot`（不用 MediaProjection）

部分 ROM（如小米）对系统录屏 / MediaProjection 会打隐私马赛克。本项目用：

- `AccessibilityService.takeScreenshot`（Android 11+）
- 悬浮球触发截取 → OCR

相关代码：`PickupCaptureAccessibilityService.kt`、`FloatingCaptureService.kt`。

## 使用步骤

1. Android Studio 打开工程，连接真机
2. 授予蓝牙、定位、相机、通知等权限
3. 系统设置 → 无障碍 → 开启「上门取件码打印」
4. 快递 App 打开取件码页 → 点悬浮球截取（或拍照 / 相册）
5. 核对取件码 → 连接打印机 →「打印取件码」
6. 「打印编排」可调纸张、方向、字号、对齐与行顺序，保存后生效

## 工程结构（主要）

```
app/src/main/java/com/pickup/print/
  PrinterManager.kt              # LPAPI 连接与打印画布
  PrintLayoutEngine.kt           # 毫米布局 / 行顺序 / 字号回流
  LabelPreviewView.kt            # 预览 Canvas
  OcrHelper.kt                   # ML Kit 中文 OCR
  PickupCodeParser.kt            # 取件码解析
  PickupCaptureAccessibilityService.kt  # 无障碍截屏
  PrintLayoutActivity.kt         # 编排 UI
  MainActivity.kt                # 主流程
app/libs/LPAPI-2026-01-08-R.jar  # 德佟 SDK
```

## SDK / 依赖

- **打印**：`app/libs/LPAPI-2026-01-08-R.jar`（德佟 / 道臻 LPAPI Android）
- **OCR**：Google ML Kit `text-recognition-chinese`
- **文档**：[德佟 SDK 详情](https://detonger.com/#/sdk/detail?sdkID=16BC0F46-ACC4-4EBB-9340-47328936E779)

## 标签与编排

- 纸张尺寸可自定义（mm），并提供常用快捷尺寸
- 竖向 / 横向切换；横向为排版宽高互换 + 打印旋转 90°
- 元素可拖到另一行交换顺序；字号 / 对齐按选中文字单独设置
- 字号过大时会等比缩小并垂直居中，避免顶边裁切

## 注意

- 小米等设备安装调试建议用 `pm install`，避免普通 `adb install` 被拦截
- 勿随意 `force-stop` App，以免无障碍服务被清掉
- 首次使用请允许电池优化白名单，便于悬浮球与截屏常驻
