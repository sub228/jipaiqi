package com.jipaiqi.doudizhu

import android.app.Application
import android.util.Log
import com.jipaiqi.doudizhu.ai.CardDetector
import com.jipaiqi.doudizhu.ai.CardOcr
import com.jipaiqi.doudizhu.ai.DouZeroEngine
import com.jipaiqi.doudizhu.ai.GameState
import com.jipaiqi.doudizhu.ai.Position
import com.jipaiqi.doudizhu.ai.RecognitionPipeline

/**
 * Application singleton + shared runtime state for the card counter.
 *
 * The Activity, the ScreenCaptureService (foreground), and the
 * FloatingWindowService all access [core] for the live [GameState],
 * the [DouZeroEngine], and the [RecognitionPipeline]. This avoids
 * passing large objects through Intent extras and lets the foreground
 * service keep running even if the user navigates away from the app.
 */
class JiPaiQiApp : Application() {

    lateinit var core: Core
        private set

    override fun onCreate() {
        super.onCreate()
        core = Core(this)
        instance = this
        Log.i(TAG, "JiPaiQiApp initialized")
    }

    companion object {
        private const val TAG = "JiPaiQiApp"
        @Volatile private var instance: JiPaiQiApp? = null
        fun get(): JiPaiQiApp =
            instance ?: error("JiPaiQiApp not yet created")
    }

    /**
     * Holder for the long-lived runtime objects. Heavy initialization
     * (ONNX session creation, ML Kit recognizer) happens lazily on first
     * [ensureReady] call to keep app startup fast.
     */
    class Core(private val app: Application) {
        val state: GameState = GameState()
        var douZero: DouZeroEngine? = null
            private set
        var yolo: CardDetector? = null
            private set
        var ocr: CardOcr? = null
            private set
        var pipeline: RecognitionPipeline? = null
            private set

        @Volatile var ready = false
            private set

        @Volatile var modelsPresent = false
            private set

        /** Listener invoked whenever the game state changes. Used by the floating UI. */
        @Volatile var onStateChanged: (() -> Unit)? = null

        fun ensureReady() {
            if (ready) return
            synchronized(this) {
                if (ready) return
                douZero = runCatching { DouZeroEngine.load(app) }.getOrNull()
                yolo = runCatching { CardDetector.load(app) }.getOrNull()
                ocr = CardOcr()
                pipeline = RecognitionPipeline(yolo, ocr!!, state)
                modelsPresent = douZero?.let { it.hasModel(Position.LANDLORD) ||
                    it.hasModel(Position.LANDLORD_UP) ||
                    it.hasModel(Position.LANDLORD_DOWN) } ?: false
                ready = true
                Log.i(TAG, "Core ready: douZero=${douZero != null} yolo=${yolo != null} " +
                    "modelsPresent=$modelsPresent")
            }
        }

        fun notifyStateChanged() { onStateChanged?.invoke() }

        fun shutdown() {
            runCatching { yolo?.close() }
            runCatching { ocr?.close() }
            runCatching { douZero?.close() }
            yolo = null; ocr = null; douZero = null; pipeline = null; ready = false
        }
    }
}
