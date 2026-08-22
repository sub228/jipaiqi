package com.jipaiqi.doudizhu

import android.app.Application
import android.content.Context
import android.os.Build
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
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        // Install a global uncaught-exception handler that dumps the full
        // stack trace to a crash log file in external cache dir, so the
        // user can share it back when the app "just crashes" with no
        // visible error.  Without this, a hard crash leaves no trace.
        installCrashHandler()
        // Boot trace file: write a marker at each step of onCreate so we
        // can tell how far we got if the process dies mid-init.  Each line
        // is flushed immediately.
        val bootLog = File(externalCacheDir ?: cacheDir, "boot_log.txt")
        LogStore.recordBootPath(bootLog)
        // Also expose the path to the in-app "查看日志" dialog via LogStore.
        val appendBoot: (String) -> Unit = { msg ->
            LogStore.append(msg)   // ← always succeeds; survives even if fs is sandboxed
            runCatching { bootLog.appendText("[${Date()}] $msg\n") }
            // Also try writing to /sdcard/Download/ where the user can
            // actually see it on HarmonyOS / Android 11+ (Android/data/
            // is hidden from normal file managers there).
            runCatching {
                val pub = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    ), "jipaiqi_boot_log.txt"
                )
                pub.appendText("[${Date()}] $msg\n")
            }
            Log.i(TAG, msg)
        }
        appendBoot("=== JiPaiQiApp.onCreate enter ===")
        appendBoot("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        appendBoot("App versionCode: ${runCatching { packageManager.getPackageInfo(packageName, 0).versionCode }.getOrDefault(-1)}")
        // Start a logcat capture thread in the background — this catches
        // native crashes (SIGSEGV/SIGABRT) and anything else that bypasses
        // the Java UncaughtExceptionHandler.  Writes to logcat_capture.txt.
        startLogcatCapture(bootLog.parentFile!!)
        // Force ScreenAdaptation to parse once on the UI thread; reads from
        // assets/ which needs a live Context.
        runCatching { ScreenAdaptation.instance }
            .onFailure { appendBoot("ScreenAdaptation FAILED: ${it.message}") }
        appendBoot("ScreenAdaptation OK")
        core = Core(this)
        appendBoot("Core constructed")
        Log.i(TAG, "JiPaiQiApp initialized")
        appendBoot("=== JiPaiQiApp.onCreate exit OK ===")
    }

    /**
     * Spawn a background thread that runs `logcat -v time` and appends its
     * output to `logcat_capture.txt` in [dir].  This captures native-layer
     * crashes (SIGSEGV/SIGABRT from JNI code, e.g. when libyolov8ncnn.so
     * fails to initialize) which bypass the Java UncaughtExceptionHandler
     * and leave no Java stack trace.  Without this, a hard native crash
     * leaves zero trace and the user sees only "app keeps stopping".
     */
    private fun startLogcatCapture(dir: File) {
        Thread {
            runCatching {
                val outFile = File(dir, "logcat_capture.txt")
                LogStore.recordLogcatPath(outFile)
                // Clear the logcat buffer first so we only capture this run.
                val clear = ProcessBuilder("logcat", "-c")
                clear.redirectErrorStream(true).start().waitFor()
                // -v time = show timestamp; -v threadtime includes tid which
                // is more useful for crash triage.
                val pb = ProcessBuilder(
                    "logcat", "-v", "threadtime",
                    // Filter to only our app's logs + crash/fatal tags so
                    // the file stays small.  Remove the filter to see ALL
                    // system logs.
                    "JiPaiQiApp:V", "MainActivity:V", "ScreenCaptureService:V",
                    "FloatingWindowService:V", "AndroidRuntime:E",
                    "DEBUG:*", "libc:*", "art:E", "System.err:W",
                    "*:F"
                )
                pb.redirectErrorStream(true)
                val proc = pb.start()
                outFile.outputStream().use { out ->
                    proc.inputStream.use { inp ->
                        // Line-buffer: emit a LogStore line per logcat line
                        // so the in-app "查看日志" view mirrors what hit disk.
                        val sb = StringBuilder()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = inp.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            out.flush()
                            // Mirror into memory for the in-app viewer.
                            sb.setLength(0)
                            for (i in 0 until n) {
                                val c = buf[i].toInt().toChar()
                                if (c == '\n') {
                                    LogStore.append("[logcat] ${sb}")
                                    sb.setLength(0)
                                } else {
                                    sb.append(c)
                                }
                            }
                            if (sb.isNotEmpty()) LogStore.append("[logcat] $sb")
                        }
                    }
                }
                proc.waitFor()
            }.onFailure { Log.e(TAG, "logcat capture failed: ${it.message}", it) }
        }.apply {
            isDaemon = true
            name = "logcat-capture"
            start()
        }
    }

    /**
     * Write every uncaught exception's stack trace to
     * `externalCacheDir/crash_log_<timestamp>.txt` and then re-throw to the
     * default handler (so the system still shows the "app keeps stopping"
     * dialog).  The file path is logged via Log.e so it surfaces in logcat
     * too.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val dir = externalCacheDir ?: cacheDir
                val file = File(dir, "crash_log_$ts.txt")
                // Read version from PackageManager instead of BuildConfig —
                // BuildConfig lives in a secondary dex and may not be loaded
                // yet when the crash handler fires on app startup.
                val verName = runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrDefault("?")
                val verCode = runCatching {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0).versionCode
                }.getOrDefault(0)
                PrintWriter(file).use { w ->
                    w.println("=== JiPaiQi crash report ===")
                    w.println("Time: ${Date()}")
                    w.println("Thread: ${thread.name} (${thread.id})")
                    w.println("App version: $verName ($verCode)")
                    w.println("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
                    w.println()
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    w.println(sw.toString())
                }
                LogStore.recordCrashPath(file)
                LogStore.append("=== CRASH: ${throwable.javaClass.name}: ${throwable.message} ===")
                LogStore.append(StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString())
                Log.e(TAG, "CRASH logged to ${file.absolutePath}", throwable)
                // Also write to /sdcard/Download/ so it's visible on
                // HarmonyOS / Android 11+ where Android/data/ is hidden.
                runCatching {
                    val pubDir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                    val pubFile = File(pubDir, "jipaiqi_crash_log_$ts.txt")
                    pubFile.writeText(file.readText())
                    Log.e(TAG, "CRASH also logged to ${pubFile.absolutePath}")
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "JiPaiQiApp"
        @Volatile lateinit var instance: JiPaiQiApp
            private set
        /** SharedPreferences name for the native-YOLO crash counter. */
        private const val CRASH_PREFS = "native_yolo_state"
        /** Key inside [CRASH_PREFS] holding the current crash count. */
        private const val CRASH_KEY = "init_crash_count"
        /** Max SIGSEGV crashes before permanently skipping native YOLO. */
        private const val MAX_YOLO_CRASHES = 2
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
            //
            //    CRASH GUARD: libyolov8ncnn.so can SIGSEGV inside Init()
            //    on incompatible ABIs / API levels (e.g. HUAWEI API 36).
            //    A native signal crash kills the entire process regardless
            //    of which thread it runs on — runCatching cannot intercept
            //    it.  We persist a crash counter to SharedPreferences:
            //    before calling Init() we increment+commit the counter;
            //    if Init() succeeds we reset it to 0.  If the process is
            //    killed by SIGSEGV the incremented counter survives, and
            //    on the next launch we skip native YOLO entirely, falling
            //    back to the pure-OCR pipeline.
            val crashPrefs = app.getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
            val crashCount = crashPrefs.getInt(CRASH_KEY, 0)
            if (crashCount >= MAX_YOLO_CRASHES) {
                Log.w(TAG, "Native YOLO skipped — crashed $crashCount times " +
                    "(limit=$MAX_YOLO_CRASHES). Using OCR-only fallback.")
                nativeYoloReady = false
            } else {
                // Pre-increment BEFORE calling Init(): if Init() SIGSEGVs
                // the process dies but the incremented counter survives.
                crashPrefs.edit().putInt(CRASH_KEY, crashCount + 1).commit()
                nativeYolo = runCatching {
                    val api = YoloAPI()
                    val ok = api.Init()   // → loads yolo_n.bin/param via assets
                    // Init() returned without crashing — reset the counter.
                    crashPrefs.edit().putInt(CRASH_KEY, 0).commit()
                    Log.i(TAG, "Native YOLO Init() returned ok=$ok")
                    if (ok) api else null
                }.getOrElse { t ->
                    // Java exception (not SIGSEGV) — reset the counter.
                    crashPrefs.edit().putInt(CRASH_KEY, 0).commit()
                    Log.e(TAG, "Native YOLO failed: ${t.message}", t); null
                }
                nativeYoloReady = nativeYolo != null
            }

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

        /**
         * Reset the native-YOLO crash counter so the next [ensureReady]
         * call will attempt to load libyolov8ncnn.so again.  Call this
         * after an app update that may ship a fixed .so, or when the
         * user explicitly wants to retry the native detector.
         */
        fun resetNativeYoloCrashCounter() {
            val prefs = app.getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
            prefs.edit().putInt(CRASH_KEY, 0).commit()
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
