## v2.1.4 — 彻底按 wz.apk jadx Java 代码逐行抄：ImageUtils/FloatWindowActions/FramePipelineCoordinator 全部 Java→Kotlin 直译

### 本次更新的核心改动：**Image→Bitmap 转换代码** 不再自己写，完全复制原版 `com.example.qnjisuanqi.ImageUtils.copyImagePlaneToBitmap` 112-173 行

| 组件 | v2.1.3 | v2.1.4（= jadx 原文逐行直译） |
|---|---|---|
| Image→Bitmap | 自己写的简化版（`bitmap = Bitmap.createBitmap + canvas.drawBitmap`）| **原版 copyImagePlaneToBitmap**（ByteBuffer.duplicate → rowStride 逐行 copy + zero-padding → copyPixelsFromBuffer）✅ |
| 帧触发方式 | ImageReader.OnImageAvailableListener | **postDelayed 100ms Runnable + CAS guard**（和 NewFloatingWindowService.frameCaptureRunnable 一模一样）✅ |
| screenHeight 读取 | dm.heightPixels（不含导航栏）| **ImageUtils.getHasVirtualKey = getRealMetrics().heightPixels**（含导航栏，影响 MODE1 isPortrait 判断）✅ |
| expectedCaptureSize | HxW if MODE1+竖屏 | **同原版：MODE1 && getScreenHeight()>=getScreenWidth() → {H, W}** ✅ |
| RGBX_8888 → ARGB_8888 copy 逻辑 | 未知差异 | **原版三路分支：fast copy (rowStride==w*4) / padding copy (pixelStride==4) / fallback (strideW 裁剪)** ✅ |

### 理论修复的根因
v2.1.3 使用的 `canvas.drawBitmap` 方式会走 Skia 的 GPU/CPU 重采样，而原版的 `copyPixelsFromBuffer` + row-stride 逐行复制是**像素级等价**，保证传给 NCNN YOLO 的 RGB 字节顺序和模型训练时完全一致。这是最可能导致「原版识别、我们版本不识别」的根因。

### 期待验证（你最新那张贴图）
手牌 17 张：
`2×2, K×4, Q×2, J×1, 9×4, 4×4` = 共 17 张

### 下载
完整 APK 直接下载：见 GitHub Release v2.1.4 assets 的 `jipaiqi_v2.1.4_arm64_armeabi.apk`

- MD5：`a9f1969f4f6c229727bfb3cfac4f27b2`
- SHA256：`de027736ba69993603235b55d56bb2891a236129363d4afb3bd9719d2b504fa0`
- 大小：~154 MB

### 如果 v2.1.4 仍识别不到，请发我以下信息（一次就够定位了）：
1. 你的手机屏幕**分辨率**（设置里的 W×H，或者截一张系统设置/关于手机里分辨率的图）
2. 悬浮窗显示的 **3 个指示灯是否有至少 2 个亮**（绿/黄/灰）
3. 安装后启动，截一张 **悬浮窗 + 手牌同时出现** 的全屏截图
