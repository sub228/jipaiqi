# v2.2.1 — 修复 ImageReader 永远 null 的致命根因 + 华为横屏日志刷屏

## 🚨 致命根因修复（记牌为0的直接原因）

### 1. ImageReader 必须绑定 OnImageAvailableListener（no-op 也行）
之前 v2.2.0 虽然修了华为分辨率切换 / SecurityException / 重建死循环，但漏了 AOSP MediaProjectionService 的一个硬约束：
- **只有当 ImageReader 调用了 `setOnImageAvailableListener(listener, handler)` 之后**，
  `BufferQueue` 的 producer-side flag `consumerIsBuffering` 才会被设置，
  MediaProjection 才会真的 `enqueueBuffer` 到 Surface。
- 没绑 listener 的话，即使 VirtualDisplay/Surface/ImageReader 全都创建成功（vd=true），
  producer 永远不推送帧 → `acquireLatestImage` 100% 返回 null。
- 这是 AOSP `MediaProjectionService.cpp` / `GraphicBufferProducer.cpp` 的硬行为，原版 wz.apk 虽然 listener 是空但依然绑定了。

修复：`ScreenCaptureService.createImageReaderLocked()` 成功后立即调用
`imageReader.setOnImageAvailableListener(imageAvailListener, captureHandler)`，
listener 本身 no-op（不处理业务，仅仅激活 BufferQueue）。

详见：[ScreenCaptureService.kt:428-443]
```kotlin
val hdr = captureHandler
if (hdr != null) {
    imageReader!!.setOnImageAvailableListener(imageAvailListener, hdr)
    DLog.i(TAG, "[CAPTURE:create_ir] setOnImageAvailableListener attached (no-op) to ...")
}
```

### 2. 自动格式回退 RGBX_8888(2) → RGBA_8888(1)
个别国产 ROM（特别是华为开启「智能分辨率」切换瞬间）Surface 生产者不支持 RGBX_8888，
会吞掉所有 buffer 不报错但永远 null。

修复逻辑：
- 启动后从未拿过成功帧（`lastFrameOkMs==0`）
- 总尝试次数 ≥ 500（约 50 秒全空）
- 只触发一次：`RGBX_8888(2) → RGBA_8888(1)` 强制整条管线重建。

详见：[ScreenCaptureService.kt:338-356] 顶部 STALL 检测。

### 3. getRealMetrics 横屏误报日志刷屏
华为 Mate80 Pro Max 天然横屏设备（`getRealMetrics` 返回 `W>H=2848x1320`），
之前代码每 100ms 都打印一条 `W/ [SCREEN] getRealMetrics returned landscape W>H(...)`，
导致 938 行日志里 800 多行全是这条完全相同的警告。

修复：新增 `lastRawW/lastRawH/lastRawOrientationMsgMs` 缓存变量，
仅在真实尺寸变化时打印，稳定后静默。

详见：[ScreenCaptureService.kt:742-786] captureScreenMetrics。

## 🛠️ 其它加固

### 4. VirtualDisplay 深度二次校验
`createVirtualDisplay()` 即使返回非 null，部分机型也会给你一个"假 vd"——
`display` 对象是 `null`（Surface 已脱链）。
v2.2.1 新增校验：
```kotlin
val vd = mgr!!.createVirtualDisplay(...)
val vdDisplay = vd.display
if (vdDisplay == null) {
    DLog.w(TAG, "[CAPTURE:create_vd] returned vd.display=null! Treating as FAILURE ...")
    return null
}
```

### 5. 版本号全面升级
| 位置 | 值 |
|---|---|
| build.gradle.kts versionCode/versionName | 221 / 2.2.1 |
| SettingsAndLogDialog 标题 | v2.2.1 |
| 日志导出 header | v2.2.1 |
| FloatingWindow 长按 DLog | v2.2.1 |
| 前台通知 channel/内容 | v2.2.1 + listener修复 |

## 🧪 预期行为（请用户实测）

1. ✅ 长按⚙️齿轮 → 打开「设置 & 调试日志 (v2.2.1)」
2. ✅ 日志不再 100ms/条 狂刷 `landscape W>H`（一分钟最多 3 条同尺寸日志）
3. ✅ 启动 10 秒内，`[CAPTURE:acquireLatestImage=null] attempts=N` 的 null_rate **从 100% → 开始下降**，
   出现真实 `success=X attempts=Y` 且 `YoloAPI.Detect` 返回非 0 数组。
4. ✅ 如 RGBX 仍不工作，约 50 秒后日志中会出现：
   `[STALL:FORMAT_FALLBACK] ... RGBX_8888(2) → switching to RGBA_8888(1) ...`
   之后帧会立刻开始进来。

## 📦 APK

包名: `com.jipaiqi.doudizhu`  (vCode=221, vName=2.2.1)

复制下载后安装：
```
adb install -r app-debug.apk
```
