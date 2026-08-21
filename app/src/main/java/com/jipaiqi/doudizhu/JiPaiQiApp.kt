package com.jipaiqi.doudizhu

import android.app.Application
import android.util.Log
import com.example.qnjisuanqi.YoloAPI
import com.jipaiqi.doudizhu.ai.CardDetector
import com.jipaiqi.doudizhu.ai.CardOcr
import com.jipaiqi.doudizhu.ai.DouZeroEngine
import com.jipaiqi.doudizhu.ai.GameState
import com.jipaiqi.doudizhu.ai.NativeYoloPipeline
import com.jipaiqi.doudizhu.ai.Position
import com.jipaiqi.doudizhu.ai.RecognitionPipeline
import com.jipaiqi.doudizhu.ai.ScreenAdaptation

/**
 * Application singleton + shared runtime state.
 *
 * Detection priority (in order of fidelity):
 *   1. **ORIGINAL 王者记牌器 YOLOv8 (NCNN)** — `libyolov8ncnn.so` +
 *      `yolo_n.bin/param` shipped inside the APK.  This is the same model
 *      the paid app uses, detection quality is identical.
 *   2. Fallback: home-grown ONNX YOLO + ML-Kit OCR recognizer.
 */
class JiPaiQiApp : Application() {

    lateinit var core: Core
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Force ScreenAdaptation to parse once on the UI thread; reads from
        // assets/ which needs a live Context.
        runCatching { ScreenAdaptation.instance }
        core = Core(this)
        Log.i(TAG, "JiPaiQiApp initialized")
    }

    companion object {
        private const val TAG = "JiPaiQiApp"
        @Volatile lateinit var instance: JiPaiQiApp
            private set
    }

    class Core(private val app: Application) {
        val state: GameState = GameState()
        var douZero: DouZeroEngine? = null
            private set
        /** ORIGINAL 王者记牌器 NCNN YOLO bridge — when loaded this is the
         *  PREFERRED detector. */
        var nativeYolo: YoloAPI? = null
            private set
        /** Home-grown ONNX YOLO (placeholder; used if native fails to load). */
        var yolo: CardDetector? = null
            private set
        var ocr: CardOcr? = null
            private set
        /** New-style native YOLO pipeline (clustering-based rows). */
        var nativePipeline: NativeYoloPipeline? = null
            private set
        /** Legacy fallback pipeline. */
        var pipeline: RecognitionPipeline? = null
            private set

        @Volatile var ready = false
            private set
        @Volatile var modelsPresent = false
            private set
        /** True iff the ORIGINAL native YOLO loaded successfully.  The UI
         *  uses this to drive the "YOLO" status indicator chip. */
        @Volatile var nativeYoloReady = false
            private set

        @Volatile var onStateChanged: (() -> Unit)? = null

        @Synchronized
        fun ensureReady() {
            if (ready) return
            // 1) DouZero engine (ONNX).  Even with placeholder weights the
            //    inference pipeline runs, so the UI has something to show.
            douZero = runCatching { DouZeroEngine.load(app) }.getOrNull()

            // 2) ORIGINAL native YOLO (libyolov8ncnn.so).  This is the
            //    preferred recognizer.  It calls the ORIGINAL C++ NCNN
            //    trained weights (yolo_n.bin/param) straight from assets.
            nativeYolo = runCatching {
                val api = YoloAPI()
                val ok = api.Init()   // → loads yolo_n.bin/param via assets
                Log.i(TAG, "Native YOLO Init() returned ok=$ok")
                if (ok) api else null
            }.getOrElse { t ->
                Log.e(TAG, "Native YOLO failed: ${t.message}", t); null
            }
            nativeYoloReady = nativeYolo != null

            // 3) ML Kit OCR fallback (handles opponent-count boxes).
            ocr = runCatching { CardOcr() }.getOrNull()

            // 4) Wire up the active pipeline.
            val ocrSafe = ocr ?: CardOcr()
            if (nativeYoloReady && nativeYolo != null) {
                nativePipeline = NativeYoloPipeline(nativeYolo!!, ocrSafe, state)
            }
            if (nativePipeline == null) {
                // Legacy fallback: self-trained YOLO + OCR.
                yolo = runCatching { CardDetector.load(app) }.getOrNull()
                pipeline = RecognitionPipeline(yolo, ocrSafe, state)
            }

            modelsPresent = douZero?.let {
                it.hasModel(Position.LANDLORD) || it.hasModel(Position.LANDLORD_UP) ||
                it.hasModel(Position.LANDLORD_DOWN)
            } ?: false
            ready = true
            Log.i(TAG, "Core ready: nativeYolo=$nativeYoloReady " +
                "yolo=${yolo != null} douZero=${douZero != null} models=$modelsPresent")
        }

        fun notifyStateChanged() { onStateChanged?.invoke() }

        @Synchronized
        fun shutdown() {
            runCatching { yolo?.close() }
            runCatching { ocr?.close() }
            runCatching { douZero?.close() }
            yolo = null; ocr = null; douZero = null
            nativeYolo = null; nativePipeline = null; pipeline = null
            nativeYoloReady = false; ready = false
        }
    }
}
