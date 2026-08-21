# 记牌器 AI — Dou Dizhu Card Counter with DouZero AI

An Android + HarmonyOS-compatible Dou Dizhu (斗地主) card counter that:

- Captures the screen via **MediaProjection** while the user plays any host
  Dou Dizhu app.
- Recognises every card on screen using a **YOLOv8 ONNX** detector
  (positions + class) cross-checked by **ML Kit OCR** on each card corner.
- Maintains a deduplicated game-state machine (my hand, played cards,
  opponent card counts, bomb count, last-15 action history).
- Recommends the **win-rate-maximising** play using the [DouZero](https://github.com/kwai/DouZero)
  reinforcement-learning model, converted to **ONNX** and run on-device
  via ONNX Runtime (with NNAPI acceleration where available).
- Shows everything in a draggable **floating overlay** that stays on top
  of the host game.

The pipeline matches the architecture in the project spec:

```
[MediaProjection 截屏]
        ↓
[图像预处理: letterbox / NCHW 归一化]
        ↓
[YOLO 检测牌位 + OCR 校验角标]
        ↓
[去重 (signature 比较) + 牌局状态更新]
        ↓
[DouZero ONNX 推理 → 最佳出牌]
        ↓
[WindowManager 悬浮窗: 剩余牌表 + AI 建议]
```

---

## 1. Repository layout

```
.
├── app/                              Android app module (Kotlin)
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/models/            ONNX models loaded at runtime
│       │   ├── landlord.onnx         (placeholder weights; see §4)
│       │   ├── landlord_up.onnx
│       │   └── landlord_down.onnx
│       ├── java/com/jipaiqi/doudizhu/
│       │   ├── JiPaiQiApp.kt          Application + shared runtime
│       │   ├── ai/
│       │   │   ├── Card.kt            Card constants + 54-dim encoder
│       │   │   ├── Move.kt            Move-type taxonomy + detector + selector
│       │   │   ├── MoveGenerator.kt   All-legal-moves enumerator (port of DouZero)
│       │   │   ├── GameState.kt       Deduped game-state tracker
│       │   │   ├── FeatureEncoder.kt Builds (z, x) tensors for ONNX
│       │   │   ├── DouZeroEngine.kt   ONNX Runtime wrapper + recommend()
│       │   │   ├── CardDetector.kt    YOLOv8 ONNX card detector
│       │   │   ├── CardOcr.kt          ML Kit text recognition wrapper
│       │   │   └── RecognitionPipeline.kt  YOLO+OCR orchestration
│       │   ├── service/
│       │   │   ├── ScreenCaptureService.kt     MediaProjection foreground
│       │   │   ├── FloatingWindowService.kt    WindowManager overlay
│       │   │   └── GameAccessibilityService.kt Optional role reader
│       │   └── ui/MainActivity.kt     Role picker + permission flow
│       └── res/                       Layouts, strings, drawables
└── tools/                            Python conversion utilities
    ├── requirements.txt
    ├── download_douzero_models.py    Pull .ckpt files from Google Drive
    ├── convert_douzero_to_onnx.py    .ckpt -> single-file ONNX
    ├── convert_yolo_to_onnx.py       YOLOv8 .pt -> ONNX (640×640)
    └── make_test_checkpoints.py      Generate placeholder .ckpt files
```

---

## 2. Build & install

### Prerequisites
- Android SDK 34 (build-tools 34.0.0, platform-tools, platforms;android-34).
  Set `ANDROID_HOME` to its root.
- JDK 17+ (the project uses Java/Kotlin 17 source-compat).
- Gradle 8.x (the repo uses the Kotlin DSL — no wrapper is checked in,
  so use a system Gradle, or run `gradle wrapper` once to generate one).

### Build the debug APK

```bash
export ANDROID_HOME=/path/to/android-sdk
gradle assembleDebug
```

The APK appears at `app/build/outputs/apk/debug/app-debug.apk`. Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Build a signed release APK

```bash
# Generate a keystore once:
keytool -genkeypair -keystore jipaiqi.keystore -alias jipaiqi \
  -keyalg RSA -keysize 2048 -validity 10000

# Then build:
gradle assembleRelease -Pandroid.injected.signing.store.file=jipaiqi.keystore \
                       -Pandroid.injected.signing.store.password=... \
                       -Pandroid.injected.signing.key.alias=jipaiqi \
                       -Pandroid.injected.signing.key.password=...
```

The release APK is at `app/build/outputs/apk/release/app-release.apk`.

### Run the JVM unit tests

Pure-logic components (`Card`, `MoveDetector`, `MoveSelector`,
`MoveGenerator`, `GameState`, `FeatureEncoder`) are covered by JVM
tests that don't need a device or an emulator:

```bash
gradle testDebugUnitTest
```

The test report is written to
`app/build/reports/tests/testDebugUnitTest/index.html`. The tests
verify:

- `Card.cardsToArray` matches DouZero `_cards2array` (column-major
  4×13 + 2 joker bits).
- `MoveDetector.getMoveType` classifies every one of the 14 DouZero
  move types (single, pair, triple, bomb, king bomb, triple-with-one,
  triple-with-pair, serial single/pair/triple, serial 3+1/3+2, bomb
  with two / two-pairs) and the BOMB_WITH_TWO_PAIRS edge case where
  the rank is `max` of the 4-count cards.
- `MoveSelector.beats` follows standard Dou Dizhu rules (same type +
  length + strictly higher rank; bomb beats non-bomb; king bomb
  beats bomb).
- `MoveGenerator.legalActions` returns every legal move when leading
  (no `pass` allowed) and only beating moves + `pass` when following,
  correctly allowing bombs/king bombs to overtake any non-bomb rival.
- `GameState` deduplication, impossibility rejection, `clearTable()`
  after consecutive empty frames, `newGame()` reset, `otherHandCards`
  correctly counting duplicates (a hand can legitimately hold 4 of a
  rank — must not collapse to a `Set`).
- `FeatureEncoder` produces the exact tensor shapes expected by
  DouZero: `(N, 5, 162)` for `z_batch` and `(N, 373)` for landlord /
  `(N, 484)` for farmers for `x_batch`, with the `x_no_action`
  prefix shared across all `N` actions and the trailing 54 floats
  matching `Card.cardsToArray(action)`.

---

## 3. Using the app

1. Launch **记牌器 AI** from the launcher.
2. Pick your role (地主 / 农民上 / 农民下).
3. Tap **开始记牌**. You'll be prompted for:
   - **显示在其他应用上层** — required for the floating overlay.
   - **屏幕录制** — required for MediaProjection.
4. Switch to your host Dou Dizhu game. The overlay appears with:
   - A 15-cell grid of remaining card counts (3..2, BJ, RJ).
   - "断张" highlight when a rank is exhausted.
   - Opponent card counts (e.g. `对手剩：12 / 9`).
   - One-line **AI 建议** showing the DouZero-recommended play.
5. Drag the overlay anywhere. Tap ✕ to close the overlay (the
   capture service keeps running until you tap **停止** in the main UI).

---

## 4. Models — getting real DouZero weights

The three ONNX files shipped in `app/src/main/assets/models/` were
generated by `tools/make_test_checkpoints.py` + `tools/convert_douzero_to_onnx.py`
using the **correct DouZero architecture** but **random initialised weights**.
This lets the entire inference pipeline run end-to-end on-device, but the
AI recommendations will be **nonsense** until you swap in the real
pretrained DouZero weights.

To get real recommendations:

```bash
cd tools
pip install -r requirements.txt

# 1. Download the three pretrained DouZero .ckpt files from Google Drive.
python download_douzero_models.py --dst ./_downloads

# 2. Convert them to single-file ONNX (overwrites the placeholder files).
python convert_douzero_to_onnx.py \
  --douzero-checkpoints ./_downloads \
  --output-dir ../app/src/main/assets/models

# 3. Rebuild the APK.
cd ..
gradle assembleDebug
```

### Model I/O contract (for the curious)

| Position        | Input `z` (LSTM)   | Input `x` (per-action)  | Output      |
|-----------------|--------------------|--------------------------|-------------|
| landlord        | `[N,5,162]`        | `[N,373]`                | `[N,1]`     |
| landlord_up     | `[N,5,162]`        | `[N,484]`                | `[N,1]`     |
| landlord_down   | `[N,5,162]`        | `[N,484]`                | `[N,1]`     |

`N` is the dynamic batch axis (number of legal actions for the current
game state). The app picks `argmax(values)` over the legal action list.

---

## 5. YOLO card detector — fine-tuning

A pre-trained YOLOv8n that detects COCO classes (person, car, ...) is
useless for card recognition. To produce a real card detector you must
fine-tune YOLOv8 on labelled Dou Dizhu screenshots.

A simple recipe:

1. **Collect screenshots** from the host game(s) you want to support —
   aim for a few hundred images across multiple resolutions and skins.
2. **Label them** with [Label Studio](https://labelstud.io/) or
   [Roboflow](https://roboflow.com/). Use 15 classes in this exact
   order (it matches `CardDetector.DEFAULT_CLASS_TO_RANK`):
   `3 4 5 6 7 8 9 10 J Q K A 2 BJ RJ`
3. **Export** the dataset in YOLOv8 format (`data.yaml` + `images/` + `labels/`).
4. **Train**:
   ```bash
   yolo detect train model=yolov8n.pt data=path/to/data.yaml epochs=100 imgsz=640
   ```
5. **Export to ONNX** for the app:
   ```bash
   python tools/convert_yolo_to_onnx.py \
     --weights path/to/runs/detect/train/weights/best.pt \
     --output app/src/main/assets/models/yolo_cards.onnx
   ```
6. Rebuild the APK.

If no `yolo_cards.onnx` is shipped in assets, the app falls back to a
pure-OCR pipeline (slightly less robust on overlapping cards but still
usable).

---

## 6. How the recognition + state pipeline works

### 6.1 Screen capture
`ScreenCaptureService` runs as a foreground service with
`foregroundServiceType="mediaProjection"` so Android keeps it alive. It
holds a `VirtualDisplay` mirrored to an `ImageReader` of pixel format
`RGBA_8888` (so frames can be blitted to a `Bitmap` with one
`copyPixelsFromBuffer` call — no YUV→RGB conversion needed).

### 6.2 Frame → cards
`RecognitionPipeline.processFrame(frame)` runs at most one frame at a
time (concurrent frames are dropped) to keep CPU load sustainable on
phones:

1. **YOLOv8 ONNX** (`CardDetector`) detects every card and its class.
2. For each detection, **ML Kit OCR** (`CardOcr.recognizeCorner`) reads
   the top-left corner. If OCR and YOLO agree, that rank is taken. If
   they disagree, the higher-confidence source wins (configurable via
   `RegionConfig.yoloWinConfidence`).
3. Cards are bucketed into "my hand" (bottom of the screen) and "table
   play" (middle band) by their bounding-box y-coordinate.

### 6.3 Deduplication & state update
- The "table play" signature is the sorted list of detected ranks.
- If it matches the previous frame's signature, the play is **not**
  re-counted (this is the dedup rule from the project spec).
- `GameState.recordPlay(position, cards)` is additionally guarded against
  replaying the same `(position, cards)` pair, against impossible rank
  counts (>4 of the same rank), etc.
- `GameState` exposes an `InfoSetSnapshot` (mirroring DouZero's
  `InfoSet`) used by the feature encoder.

### 6.4 AI recommendation
`FeatureEncoder.encode(snapshot)` produces `(z_batch, x_batch)` exactly
as in DouZero's `env/env.py::_get_obs_*`. `DouZeroEngine.recommend` runs
the ONNX model with NNAPI and returns the action with the highest
predicted value.

### 6.5 Floating overlay
`FloatingWindowService` uses `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`
to draw on top of every app. A periodic refresh (500 ms) re-reads the
`GameState` and re-runs the AI engine on a background coroutine, then
updates the cells and the AI-suggestion line on the main thread.

---

## 7. Permissions used

| Permission                                | Why                                   |
|-------------------------------------------|---------------------------------------|
| `SYSTEM_ALERT_WINDOW`                    | Floating overlay                      |
| `MEDIA_PROJECTION` + foreground type      | Screen capture                        |
| `FOREGROUND_SERVICE` + `_MEDIA_PROJECTION`| Keep capture alive                     |
| `WAKE_LOCK`                              | Keep CPU awake during a session        |
| `BIND_ACCESSIBILITY_SERVICE` (optional)  | Read role/bottom-cards from host UI   |
| `VIBRATE`                                | Haptic feedback on "断张" alerts        |

---

## 8. Limitations & known issues

- **Placeholder weights**: the shipped ONNX models have random weights.
  See §4 for swapping in real DouZero weights.
- **YOLO model is not shipped**: see §5 to fine-tune one. The app runs in
  pure-OCR mode until then.
- **No NDK / JNI**: the app uses pure ONNX Runtime (which itself ships
  prebuilt `.so` files). No custom native code is needed.
- **Host-app UI variations**: different Dou Dizhu apps render cards at
  different positions and scales. The `RegionConfig` defaults target the
  most common layout (bottom hand + middle table band); adjust the y
  fractions if your host differs.
- **OCR limitations**: ML Kit Latin OCR recognises 3..10, J, Q, K, A, 2,
  BJ, RJ (small joker is rendered as "BJ", big joker as "RJ" on screen).
  If the host game renders jokers as Chinese characters (小王/大王),
  make sure to update `Card.fromText` accordingly.

---

## 9. Credits

- [DouZero](https://github.com/kwai/DouZero) — the RL card-playing model
  whose architecture and feature encoder this project ports to Android.
- [ONNX Runtime](https://onnxruntime.ai/) — on-device inference engine.
- [Ultralytics YOLOv8](https://github.com/ultralytics/ultralytics) —
  the card detector backbone.
- [Google ML Kit](https://developers.google.com/ml-kit) — text recognition.

## 10. License

Source code is released under the MIT License. The pretrained DouZero
checkpoints (when downloaded via `download_douzero_models.py`) remain
under their original DouZero license terms — please consult the
[DouZero repository](https://github.com/kwai/DouZero) for details.
