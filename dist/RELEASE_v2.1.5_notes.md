## v2.1.5 — Mate80 屏(2848×1320)+智能分辨率修正 + 悬浮窗 analyze 绿灯修复 + handRow 阈值回退到 screenAdaptions.json 66%

### 本次三大修复

| # | 修复 | 影响现象 |
|---|---|---|
| 1 | FloatingWindowService 指示灯 `analyzeOk` 加上 `core.nativePipeline != null` 判断 | **悬浮窗三个指示灯第三个（分析）从灰变绿**，和主页面 "YOLO ✓(原版NCNN)" 一致 ✅ |
| 2 | NativeYoloPipeline handRow 阈值 `yMean >= 0.5h` → 改为 `yMean >= 0.66h`（screenAdaptions.json 原文字面常量 handsArea.default `66%`） | **Mate80 Pro Max 在 2848×1320 这种高度>宽度的直屏上，不再把对手出牌/数字框识别成"手牌"**，正确锁定屏幕底部 34% 区域为我的手牌 ✅ |
| 3 | 每帧循环调用 `captureAndStoreScreenMetrics()` 重新读取 Display（原版 FloatWindowInteractionCoordinator `handleConfigurationChanged` 流程），并在 tickFrame 中判断 screenWidth/screenHeight 变化后重建 ImageReader | **华为智能分辨率（2848↔2340 动态切换）下，不会因为启动时是 1080P 后来切到 2848，ImageReader 尺寸不匹配导致 YOLO 喂了错位图** ✅ |

### 你当前最新截图的期待识别结果（对照校验）
手牌 20 张（地主）：
`BJOKER RJOKER 2×1 A×3 K×3 8×4 7×1 6×1 5×3 4×1 3×1` = **共 20 张**（完全正确 = 识别OK）

### 这版验证步骤（只需 30 秒）
1. 安装后，主页面看 3 个指示灯 → **第三个 analyze 应该亮绿**
2. 打开欢乐斗地主进入发牌后看悬浮窗
   - 若还没亮数 → 把你悬浮窗的 "AI：等待识别手牌…" 的整屏截图发我（要包含：悬浮窗 + 顶部欢乐斗地主完整栏 + 手牌完整一行）
   - 若亮数了但数不对 → 截图发我显示的数字和实际手牌

### 下载
直接下完整 APK：见 Release Assets 的 `jipaiqi_v2.1.5_arm64_armeabi.apk`
- MD5：`d26c0126a1839c26a70c53f97e40cc07`
- SHA256：`69bfaa2d8cccbea322d8e3e164939534284536ace3a54516bc09e73dcd04bfe9`
- 大小：约 154 MB
