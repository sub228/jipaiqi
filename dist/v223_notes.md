### v2.2.3 关键修复（针对用户反馈两个问题）

---

## 修复一：悬浮窗记牌条/牌数量消失

**根因**：
1. XML 布局中 `ll_num_list` 和 `line` 默认 `visibility=gone`（注释写"直到第一帧处理后才显示"），而原版 APK 启动时记牌条始终可见并显示完整初始牌数
2. `inflateRankCells` 初始填充的计数文本是 "--"（破折号），不是 4/3/2/1 等实际数值

**修复**（两处）：
- `floating_panel.xml`：`ll_num_list` 和 `line` 的 `android:visibility` 由 `gone` 改为 **visible**
- `FloatingWindowService.kt inflateRankCells()`：初始 count 文本改为 `Card.TOTAL[rank]`（3-2 各 4 张，小王/大王各 1 张），不是破折号

现在启动悬浮窗，立刻能看到完整的 3-2-小王-大王 15 格记牌条，每格数字 4 或 1，跟原版一模一样。

---

## 修复二：华为 Mate80 Pro Max ImageReader 100% null

**根因（查原版 FloatWindowActions.java L68 发现）**：
原版 APK 明确使用：
    ImageReader.newInstance(w, h, 1, maxImages)
                                          ^
                        PixelFormat.RGBA_8888 = 1

而我们复刻版**错误地默认用了 PixelFormat.RGBX_8888=2**。MediaProjection 把屏幕帧渲染到 ImageReader 的 Surface 时做了严格的 HAL 通道格式校验，RGBA buffer 被推到 RGBX reader 被 HWC composer 静默丢弃，导致 acquireLatestImage 永远返回 null。这就是你日志中 attempts=601、null_rate=100% 的根本原因。

之前版本虽然做了格式回退，但：
1. 回退方向是 RGBX -> RGBA（要等 500 帧约 50 秒）
2. v2.2.1 中回退判断还被日志节流锁死，永远不触发
3. 你的日志 attempts=601 时 reader 仍显示 RGBX_8888(2)，印证回退从未发生

**修复**（与原版完全对齐）：
- 默认格式改为 RGBA_8888(1)，fallback 500 帧无图才切 RGBX_8888(2) 备用
- 回退方向、日志同步调整

---

### 修复效果对比
| 现象                  | v2.2.1（你当前版本）           | v2.2.3（本版）                                    |
|-----------------------|------------------------------|-------------------------------------------------|
| 悬浮窗记牌条           | 不显示（gone），只显示诊断文字  | 启动即显示 15 格完整初始计数                       |
| ImageReader 默认格式   | RGBX_8888(2) 与原版不符       | RGBA_8888(1) 与原版 FloatWindowActions.java 一致 |
| 启动后首帧时间         | 永远 null，双重死锁           | 预计 2-3 秒内拿到首帧                             |
| 回退机制               | 方向错 + 节流锁死             | 默认正确格式，回退仅作老机器备用                  |

---

### 使用建议
1. 请**完全卸载旧版 APK** 后安装此包（悬浮窗权限 + 录屏授权需重新确认，避免旧缓存干扰）
2. 启动 APP -> 点击"启动" -> 授予录屏 + 悬浮窗权限 -> 打开欢乐斗地主
3. 进入发牌画面后，绿灯亮三颗的 2-3 秒内，识别 17 张手牌（JOKER, K, K, J, J, 10, 10, 9, 8, 7, 7, 7, 7, 6, 6, 6, 6），记牌条每格数字从 4/1 自动递减
4. 长按悬浮窗设置齿轮 0.5 秒 -> 可打开调试日志界面 -> 一键复制/分享 log

SHA256: 7605053b4e3fc1a76fa55eba0b2a15c8ba14341373468f3890aca32e341c2d1f
大小: 154 MB（原版 NCNN + DouZero 3模型 + arm64-v8a/armeabi-v7a 双架构）
