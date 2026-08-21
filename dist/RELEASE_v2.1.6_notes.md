## v2.1.6 — 找到识别为0的真正根因：缺少原版 `Yolov8Ncnn.loadModel()` 调用

### 🔴 之前一直没发现的代码遗漏（jadx 证据）

在 jadx 反编译原版 `wz.apk` 的 **NewFloatingWindowService.java lines 786..792**：
```java
Yolov8Ncnn yolov8ncnn = new Yolov8Ncnn();
boolean r6 = yolov8ncnn.loadModel(assets, aiModel, cpuGpu, platform);  // ← 真正从 yolo_n.bin/.param 加载模型的一步！
YoloAPI yoloAPI = new YoloAPI();
boolean r7 = yoloAPI.Init();   // ← Init() 只是把已经 loadModel() 好的 NCNN Net 做 warmup
if (r6 && r7) aiModelLoaded = true;
```

我们之前的版本**只调了 `YoloAPI.Init()`，完全没调 `Yolov8Ncnn.loadModel(assets, 0, 0, 6)`**！导致 NCNN 的 C++ 全局句柄始终是空指针，`libyolov8ncnn.so` 根本没读取 assets 里的 `yolo_n.bin`/`yolo_n.param` — 这就是为什么 3 个绿灯都亮，但识别框永远 0 的根因。

### ✅ 本次修复
1. **新增 `Yolov8Ncnn.java`** 逐行抄 jadx 文件（`com.example.qnjisuanqi.Yolov8Ncnn`）
2. **`JiPaiQiApp.core.ensureReady()` 调整顺序**：`Yolov8Ncnn.loadModel(0,0,6)` → **通过后再** `YoloAPI.Init()`
   - `0 = aiModel`（yolo_n，斗地主默认）
   - `0 = cpuGpu`（CPU，无需 Vulkan 额外 so）
   - `6 = platform`（MODE1 斗地主平台）

### 对照你最新那张贴图期待输出
手牌 17 张：`2♣, K♠K♣, J♠J♥J♣J♦, 9♠9♥9♦, 5♠5♣5♦, 4♦, 3♠3♥3♣`
地主出牌：`3♦`（单张 3）

### 下载
完整 APK：见 Release v2.1.6 Assets 的 `jipaiqi_v2.1.6_arm64_armeabi.apk`

- MD5：`62b6851147ea96784c21b968d068ed58`
- SHA256：`7a38242a0f5b014ee4e2b23124e02176bf2d15c33f7b23579cbaab0580a48cd8`
- 大小：161 MB

v2.1.6 是**理论上可以直接用**的版本了：NCNN so 加载 + yolo_n.bin/param 权重读取 + RGBX_8888 的像素转 + handRow=66% + MODE1宽高交换 = 全部 jadx 逐行对齐。
