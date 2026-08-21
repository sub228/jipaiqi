## v2.1.3 — 记牌核心=原版APK 1:1逐行抄（FloatWindowActions + FramePipelineCoordinator）

### 本次更新：彻底回归原版 wz.apk 代码，不做任何"聪明"改动

与 v2.1.2 的唯一区别 = 所有数字/格式/flag 都是 jadx 原文逐字复制，不再有任何"近似"：

- ImageReader 格式：原版 1 (RGBX_8888) → v2.1.3 PixelFormat.RGBX_8888 = 1 ✅
- MODE1+竖屏 reader 宽高：原版 {screenH, screenW}（交换）→ v2.1.3 完全一致 ✅
- VirtualDisplay flag：原版 16 (VIRTUAL_DISPLAY_FLAG_PUBLIC) → v2.1.3 16 ✅
- maxImages (非MODE2)：原版 5 → v2.1.3 5 ✅
- maxImages (MODE2)：原版 12 → v2.1.3 12 ✅
- box_merge 水平重叠阈值：原版 0.55 * min(w) → v2.1.3 0.55 * min(w) ✅
- box_merge 同框 y 差阈值：原版 0.45 * max(h) → v2.1.3 0.45 * max(h) ✅
- row_cluster rowGap：原版 0.45 * median(h) → v2.1.3 0.45 * median(h) ✅
- handRow 规则：原版 最大簇且 yMean >= 0.5h → v2.1.3 完全一致 ✅
- YoloAPI.Detect z flag：原版 true（斗地主固定）→ v2.1.3 true ✅
- JNI Obj 构造签名：原版 (Lcom/example/qnjisuanqi/YoloAPI;)V（非静态内部类）→ v2.1.3 完全一致 ✅
- so 打包：原版 extractNativeLibs=true + useLegacyPackaging=true → v2.1.3 完全一致 ✅
- ABI 过滤：原版 arm64-v8a + armeabi-v7a（原版无 x86_64）→ v2.1.3 完全一致 ✅
- MODE3/MODE4 旋转：原版 MODE3=+90，MODE4=-90（斗地主MODE1不旋转）→ v2.1.3 完全一致 ✅

### 用户截图验证目标（对应本次两张截图）

截图1 手牌 17 张（期待识别结果）：
RJOKER * 1, 2 * 2, A * 1, K * 2, Q * 1, J * 2, 10 * 1, 8 * 3, 7 * 3, 5 * 1 = 17张

截图2 对手出牌（期待识别结果 = 左上地主出牌）：
4 * 3, 5 * 2（三带一对）

### 文件清单（GitHub Release Assets）
- v213_split_0 ... v213_split_8：9 个分卷（18MB * 8 + 10MB * 1）
- v213_安卓合并.sh：安卓端合并脚本
- 合并后 APK MD5：6ced3eef8664fa218a6a7b9bd88c863e
- 合并后 APK SHA256：56ebf3d4d272f373ad7ac44095d841a6698a37ee821cac44b486c5c6bddcfa2f

### 安卓端合并安装步骤
1. 把 9 个 v213_split_* 和 v213_安卓合并.sh 全部下载到 /sdcard/Download
2. MT 管理器 → 左侧进入 Download → 右下角菜单 → 终端
3. 输入 sh v213_安卓合并.sh
4. 看到 MD5 6ced3eef... 即合并成功 → 点击合并好的 APK 安装

本版完全复刻原版 APK 的记牌管线，理论精度 = 原版王者记牌器 100%。
