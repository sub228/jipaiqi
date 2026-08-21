package com.jipaiqi.doudizhu.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.jipaiqi.doudizhu.JiPaiQiApp
import com.jipaiqi.doudizhu.R
import com.jipaiqi.doudizhu.ai.Position
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground service that holds a [MediaProjection] session and pumps
 * captured screen frames into the [RecognitionPipeline] at a throttled
 * rate (~5 FPS to keep CPU/NPU load reasonable for sustained use).
 *
 * Frame lifecycle:
 *   ImageReader.OnImageAvailableListener -> acquireLatestImage() ->
 *     Image -> Bitmap -> pipeline.processFrame (suspend) -> notify Core
 *
 * Deduplication is handled inside [RecognitionPipeline]: if the table play
 * is unchanged from the previous frame, no state update is emitted.
 *
 * Started via [startForeground] with the mediaProjection service type so
 * the OS keeps the session alive while the user is in another app.
 */
class ScreenCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frameLock = Mutex()
    @Volatile private var processing = false

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> { stopCapture(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            ?: run { stopSelf(); return }
        startForegroundWithNotification()

        val app = (application as JiPaiQiApp).core
        app.ensureReady()

        // ── Dedicated capture thread ──────────────────────────────────────
        //
        // ORIGINAL wz.apk runs the ImageReader listener callback on a
        // dedicated HandlerThread ("capture" priority), never the UI
        // looper.  Using the main looper here caused 50–150 ms of frame
        // latency every time the user scrolled a recents / status-bar
        // overlay in Android 12+, which fed YOLO a partially-drawn bitmap.
        val ht = HandlerThread("screen-capture",
            android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        captureThread = ht
        captureHandler = Handler(ht.looper)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopCapture(); stopSelf() }
            }, captureHandler ?: Handler(Looper.getMainLooper()))
        }

        // ── Capture size = PHYSICAL device resolution ────────────────────
        //
        // ORIGINAL wz.apk feeds the **full** pixel buffer into
        // YoloAPI.Detect(bitmap, true).  The previous 1080p cap shrank
        // 欢乐斗地主 opponent hand cards (already small) below what the
        // YOLOv8-n head could resolve, producing zero detections for the
        // upper 2 player areas.  Real phones are typically 1080×2400 or
        // 1260×2800 — ncnn handles that fine (inference still < 70 ms).
        val (w, h, dpi) = displayMetrics()
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        imageReader!!.setOnImageAvailableListener({ reader -> onFrame(reader) },
            captureHandler!!)
        virtualDisplay = projection?.createVirtualDisplay(
            "JiPaiQi", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, captureHandler
        )
        Log.i(TAG, "Capture started: ${w}x${h} @ ${dpi}dpi (native resolution)")
    }

    private fun onFrame(reader: ImageReader) {
        if (processing) { // drop overlapping frames; YOLO (~60ms) is the bottleneck
            runCatching { reader.acquireLatestImage()?.close() }
            return
        }
        val image = reader.acquireLatestImage() ?: return
        scope.launch {
            try {
                processing = true
                val bitmap = imageToBitmap(image)
                if (bitmap != null) {
                    val core = (application as JiPaiQiApp).core
                    val changed = run {
                        val np = core.nativePipeline
                        if (np != null) {
                            val r = np.processFrame(bitmap)
                            // record telemetry so the status dots light up correctly
                            lastFrameHandCount = r.hand.size
                            lastFrameDetections = r.totalDetections
                            r.stateChanged
                        } else {
                            val r = core.pipeline?.processFrame(bitmap)
                            lastFrameHandCount = r?.hand?.size ?: 0
                            lastFrameDetections = r?.hand?.size ?: 0
                            r?.stateChanged ?: false
                        }
                    }
                    if (changed) core.notifyStateChanged()
                }
            } catch (e: Exception) {
                Log.w(TAG, "frame process error: ${e.message}", e)
            } finally {
                runCatching { image.close() }
                processing = false
            }
        }
    }

    // Live stats for the status-dot UI.
    @Volatile var lastFrameHandCount: Int = 0
    @Volatile var lastFrameDetections: Int = 0

    /** Convert an RGBA_8888 [Image] to a Bitmap without extra copies. */
    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val w = image.width
        val h = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * w
        val bmp = Bitmap.createBitmap(
            w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        bmp.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, w, h)
    }

    private fun stopCapture() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null; imageReader = null; projection = null
        scope.cancel()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun displayMetrics(): Triple<Int, Int, Int> {
        val dm = resources.displayMetrics
        // ── ORIGINAL wz.apk uses the physical device resolution verbatim. ──
        //
        // For years we shipped with a 1080p cap which looked "faster" on
        // paper but silently broke card detection: 欢乐斗地主 opponent
        // hands are roughly 40–48 px tall on a 1080×2400 device; scaling
        // below that drops them under the minimum size (~32 px) the
        // bundled yolov8n head can resolve.  Result: zero detections for
        // the QJ109876 user posted even though *native* detection sees it
        // just fine.
        //
        // Additionally we swap W/H so that the ImageReader is always
        // initialised in the CURRENT orientation of the display, which
        // matches what `prepareFrameBitmap` in the original wz.apk does
        // (Mode.MODE{1,2} skip rotation).
        val ctx = applicationContext
        val rot = (ctx.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)
            ?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val (w, h) = when (rot) {
            Surface.ROTATION_90, Surface.ROTATION_270 ->
                maxOf(dm.widthPixels, dm.heightPixels) to minOf(dm.widthPixels, dm.heightPixels)
            else -> dm.widthPixels to dm.heightPixels
        }
        return Triple(w.coerceAtLeast(480), h.coerceAtLeast(480), dm.densityDpi)
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Screen capture", NotificationManager.IMPORTANCE_LOW
            ))
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("记牌器 AI 正在运行")
            .setContentText("正在识别屏幕牌面")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "jiPaiQi_capture"
        private const val NOTIF_ID = 0x7713

        const val ACTION_START = "com.jipaiqi.START_CAPTURE"
        const val ACTION_STOP = "com.jipaiqi.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_POSITION = "position"

        fun start(
            context: Context,
            resultCode: Int,
            data: Intent,
            position: Position,
        ) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_POSITION, position.name)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
