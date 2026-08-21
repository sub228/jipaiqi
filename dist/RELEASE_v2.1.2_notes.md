## 对照原版 APK 源码逐项核对 & 修复

参考 jadx 反编译原版王者记牌器：
- FramePipelineCoordinator.java（帧调度）
- NewFloatingWindowService.java -> buildStructuredYoloPayload（YOLO 检测结果原封不动传给 Flutter 做聚类+手牌行/出牌行识别）
- DouDiZhuGameData.DebugMsg（cardboxlength / clusterslength 对应 Dart 聚类算法）

### 3 个识别不准的根因，全部和原版不一致

| # | 项目 | v2.1.1 (之前) | v2.1.2 (和原版一致) | 对用户截图的具体影响 |
|---|---|---|---|---|
| 1 | YOLO 输入分辨率 | 自加 1080p cap, ImageReader 缩成最大边 1080px -> 对手 QJ109876 顺子实际高度从 48px 缩到 31px, 低于 YOLOv8-n head 最小 32px 可识别阈值 | 传**物理分辨率全图** (典型 1080x2400 直接喂给 NCNN)；不做任何预裁剪；并和原版一样 `yolo.Detect(bitmap, true)` (z-flag=true 由 C++ 侧内部做欢乐斗地主布局裁剪) | 你截图里右上角 QS JD 10C 9H 8C 7D 6C 顺子 7 张完全检不出 → 现在和下面 17 张手牌一起检到 |
| 2 | y-聚类 row-gap 阈值 | rowGap = 0.9 × median(h) → 一整行 17 张手牌被拆成上下两行假簇，两边都不满足 handRow 的 >=5 card & x-range>30% 条件 → hand = empty | rowGap = **0.45 × median(h)**（和原版 Dart card_box_merge 完全一致）；并且是否并入上一簇用 `y - lastRow.yMean` 判断（原版做法）而非用 yMax | 悬停牌面板一直显示「等待识别手牌」，状态点只有第一颗亮 |
| 3 | 手牌行选择规则 | 从下往上扫任意 >=5 cards 且 x-range>30% 的簇；边界平滑系数 α=0.70 | 先把 y<0.5h 的簇剔掉（排除对手区），在剩下的簇里挑 cards 数**最大**的 → 同分时取 yMean 最**高**的（原版 Flutter 写法）；fallback 仍用 screenAdaptions.json 66% 阈值；α=0.75 | 之前偶发把对手 7 张顺子误记为 my hand（现在 minHandCards = 17*0.4 = 6，7 张顺过对手区但不过 minHandCards）；换牌局后历史牌没清空（检测到 ≥15 cards 新手牌 & 之前 <15 → state.newGame() 全清）|

### 其它和原版一致化的细节
- ImageReader listener 回调：原版用独立 HandlerThread（THREAD_PRIORITY_DISPLAY）→ 我们也改了，不再用 MainLooper
- ImageReader maxImages：原版 3 → 我们也 3
- VirtualDisplay 回调 handler：原版传 capture thread handler → 我们也传（之前是 null）
- 方向：startCapture 时按 display.rotation 交换 W/H（和原版 prepareFrameBitmap MODE1/2 分支一致）
- GameState 新牌局重置：≥15 张新手牌 & 旧手牌 <15 → newGame()（原版 Flutter gameReset 事件）
- ScreenAdaptation：从 gameconfiguration.json 读 hand_card_nums（原版 doudizhu_3=17）→ 写 expectedHandCards 给聚类阈值用

### 构建 & 测试
- BUILD SUCCESSFUL in 27s
- JVM 单元测试：87 / 87 passed (Card, YoloLabelBridge, MoveDetector, MoveGenerator, MoveSelector, FeatureEncoder, GameState — 0 failures)
- APK 大小：154 MB (extractNativeLibs=true + useLegacyPackaging, 安装时 so 落到 /data/app, 100% 兼容国产机)
- MD5: a84b2eee00f9c92b596d1061f4541922
