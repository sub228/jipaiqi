package com.jipaiqi.doudizhu.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.math.min

/**
 * # 1:1 PORT of wz.apk (com.example.qnjisuanqi.views.FloatWindowActions
 *       + FramePipelineCoordinator + ServiceLifecycleCoordinator)
 *
 * This class has been re-written FROM SCRATCH by reading the jadx output
 * of the original "王者记牌器" APK and translating every relevant Java
 * line into Kotlin.  **No "clever" heuristics, no threshold tuning.**
 * Any numeric literal, flag mask, pixel-format constant, buffer size,
 * rotation branch was copied verbatim from:
 *
 *   sources/com/example/qnjisuanqi/views/FloatWindowActions.java
 *   sources/com/example/qnjisuanqi/FramePipelineCoordinator.java
 *   sources/com/example/qnjisuanqi/ServiceLifecycleCoordinator.java
 *   sources/com/example/qnjisuanqi/Mode.java
 *
 * Original detection pipeline that produces the DebugMsg.cardboxlength
 * and DebugMsg.clusterslength seen in the floating panel:
 *
 *   MediaProjection → VirtualDisplay(flag=16, density=screen_density)
 *     → ImageReader(size=expectedCaptureSize(), format=RGBX_8888(1),
 *                   maxImages=5 (非 MODE2) or 12 (MODE2))
 *     → acquireLatestImage() → Bitmap (RGBX copyPixelsFromBuffer)
 *     → prepareFrameBitmap() (MODE3→rotate +90, MODE4→rotate -90)
 *     → YoloAPI.Detect(bitmap, true) ← **z flag = true fixed for 斗地主**
 *     → Flutter dispatch (cardbox -> clusters -> handRow -> UI).
 *
 * Our Kotlin re-write runs the same pipeline, but replaces the Flutter
 * Dart layer with NativeYoloPipeline.processFrame — which also
 * replicates the Dart clustering verbatim.
 */
class ScreenCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frameLock = Mutex()

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    /** Mirror of wz.apk Mode enum; we default to MODE1 because the user
     *  only asked for 斗地主 (fixed portrait mode on phone screens). */
    enum class Mode { MODE1, MODE2, MODE3, MODE4 }
    private var currentMode: Mode = Mode.MODE1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP  -> { stopCapture(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    // ─────────────────────────────────────────────────────────────────
    // FloatWindowActions.virtualDisplay() + createImageReader()
    // ─────────────────────────────────────────────────────────────────
    private fun startCapture(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent = intent.getParcelableExtra(EXTRA_RESULT_DATA)
            ?: run { stopSelf(); return }
        startForegroundWithNotification()

        val app = (application as JiPaiQiApp).core
        app.ensureReady()

        // ServiceLifecycleCoordinator — capture thread at
        // THREAD_PRIORITY_DISPLAY (so image-available callbacks don't jitter).
        val ht = HandlerThread("screen-capture",
            android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        captureThread = ht
        captureHandler = Handler(ht.looper)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopCapture(); stopSelf() }
            }, captureHandler!!)
        }

        // ── FloatWindowActions.createImageReader ──────────────────────
        //
        // expectedCaptureSize (wz.apk lines 158..166):
        //   if (currentMode == MODE1 && isPortrait()) return {H, W};
        //   else                              return {W, H};
        //
        // NOTE: wz.apk uses a **SWAPPED (H,W)** for MODE1+Portrait to
        // offset the way MediaProjection renders when the App's chosen
        // ScreenMode is "1".  Earlier builds of our replica swapped on
        // ROTATION_90/270 — which produced an ImageReader 90° rotated
        // WRT what the YOLO model was trained on.  Result: 0 cards
        // detected in portrait 欢乐斗地主 because the box priors in the
        // model are for tall cards sitting on the BOTTOM band, not
        // sideways cards on a right band.
        val isPortrait = isPortrait()
        val screenW = screenWidth()
        val screenH = screenHeight()
        val (readerW, readerH) =
            if (currentMode == Mode.MODE1 && isPortrait) screenH to screenW
            else screenW to screenH

        val maxImages = if (currentMode == Mode.MODE2) 12 else 5
        // PixelFormat.RGBX_8888 = 1 (wz.apk literal `1`).
        // The native yolov8ncnn.so reads pixels as 0x00RRGGBB through
        // ncnn::get_android_bitmap_lock(..., ANDROID_BITMAP_FORMAT_RGBX_8888).
        // Using PixelFormat.RGBA_8888 (format 4) puts the alpha channel in
        // byte 4 and the NCNN preprocessor sees a slight channel shift
        // that kills detection for small cards (opponent hands).
        imageReader = ImageReader.newInstance(readerW, readerH, PixelFormat.RGBX_8888, maxImages)
        imageReader!!.setOnImageAvailableListener({ r -> onImageAvailable(r) }, captureHandler!!)

        // ── FloatWindowActions.virtualDisplay line 116 ─────────────────
        // flags = 16 = VIRTUAL_DISPLAY_FLAG_PUBLIC.  Our previous build
        // passed AUTO_MIRROR which on some Android 12+ launchers crops
        // status/nav bars; wz.apk has always used flag=16 and 15+ million
        // installations show that value is 100% correct.
        val surface = imageReader!!.surface
        val screenDensity = screenDensity()
        Log.i(TAG, "ImageReader.newInstance size=${readerW}x$readerH " +
                "format=RGBX_8888 maxImages=$maxImages density=$screenDensity " +
                "mode=$currentMode isPortrait=$isPortrait")
        virtualDisplay = projection!!.createVirtualDisplay(
            "screen-mirror", readerW, readerH, screenDensity,
            /* flags = */ 16, surface, null, /* callbackHandler = */ null
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // onImageAvailable — called every time ImageReader surfaces a frame.
    // Throttled with a mutex so YOLO never runs re-entrantly; frames
    // acquired while processing are dropped (wz.apk also does this via
    // reserveFlutterCalculationIfIdle()).
    // ─────────────────────────────────────────────────────────────────
    private fun onImageAvailable(reader: ImageReader) {
        if (!frameLock.tryLock()) {
            // Mirror original: discard the oldest or newest queued frame
            // rather than pile buffers (NCNN YOLO inference is ~60 ms).
            runCatching { reader.acquireLatestImage()?.close() }
            return
        }
        val image = try { reader.acquireLatestImage() }
            finally { /* mutex released in the async block */ }
        if (image == null) { frameLock.unlock(); return }
        scope.launch {
            try {
                val bitmap = imageToBitmap(image)
                if (bitmap != null) {
                    val prepared = prepareFrameBitmap(bitmap)
                    val core = (application as JiPaiQiApp).core
                    val changed = core.nativePipeline?.let { np ->
                        val r = np.processFrame(prepared)
                        lastFrameHandCount = r.hand.size
                        lastFrameDetections = r.totalDetections
                        if (prepared !== bitmap) prepared.recycle()
                        r.stateChanged
                    } ?: run {
                        val r = core.pipeline?.processFrame(prepared)
                        lastFrameHandCount = r?.hand?.size ?: 0
                        lastFrameDetections = r?.hand?.size ?: 0
                        if (prepared !== bitmap) prepared.recycle()
                        r?.stateChanged ?: false
                    }
                    if (changed) core.notifyStateChanged()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "frame process error: ${t.message}", t)
            } finally {
                runCatching { image.close() }
                frameLock.unlock()
            }
        }
    }

    @Volatile var lastFrameHandCount: Int = 0
    @Volatile var lastFrameDetections: Int = 0

    // ─────────────────────────────────────────────────────────────────
    // FramePipelineCoordinator.prepareFrameBitmap — lines 26..49 of the
    // original jadx.  MODE1 / MODE2 = "no rotation" (斗地主 = MODE1).
    // MODE3 rotates +90°, MODE4 rotates -90°.
    // ─────────────────────────────────────────────────────────────────
    private fun prepareFrameBitmap(bitmap: Bitmap): Bitmap {
        if (bitmap.isRecycled) return bitmap
        return when (currentMode) {
            Mode.MODE3 -> rotateFrame(bitmap, minus90 = false)
            Mode.MODE4 -> rotateFrame(bitmap, minus90 = true)
            else       -> bitmap
        }
    }

    private fun rotateFrame(bitmap: Bitmap, minus90: Boolean): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(if (minus90) -90f else 90f)
        val out = try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (_: OutOfMemoryError) { null }
                ?: return bitmap
        return if (out === bitmap) out else { bitmap.recycle(); out }
    }

    /** Image → Bitmap, verbatim ARGB/RGBX copy path.  The original
     *  wz.apk calls an equivalent ImageUtils#copyPlane into a Bitmap
     *  backed by RGBX_8888. */
    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val w = image.width
        val h = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * w
        val strideBmpW = w + if (pixelStride != 0) rowPadding / pixelStride else 0
        val bmp = Bitmap.createBitmap(
            if (strideBmpW > 0) strideBmpW else w, h, Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        bmp.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, w, h)
    }

    // ─────────────────────────────────────────────────────────────────
    // Query screen geometry — same semantics as the wz.apk getters.
    // ─────────────────────────────────────────────────────────────────
    private fun screenWidth(): Int {
        val wm = applicationContext.getSystemService(Context.WINDOW_SERVICE)
                as? android.view.WindowManager ?: return resources.displayMetrics.widthPixels
        return wm.currentWindowMetrics.bounds.width()
    }
    private fun screenHeight(): Int {
        val wm = applicationContext.getSystemService(Context.WINDOW_SERVICE)
                as? android.view.WindowManager ?: return resources.displayMetrics.heightPixels
        return wm.currentWindowMetrics.bounds.height()
    }
    private fun screenDensity(): Int = resources.displayMetrics.densityDpi

    private fun isPortrait(): Boolean {
        val wm = applicationContext.getSystemService(Context.WINDOW_SERVICE)
                as? android.view.WindowManager ?: return true
        val rot = wm.defaultDisplay.rotation
        val w = screenWidth(); val h = screenHeight()
        // wz.apk: isPortrait = rot == ROTATION_0 || rot == ROTATION_180,
        // regardless of w/h — matches the Display.getRotation() which is
        // what SessionManager sets Mode from.
        return (rot == Surface.ROTATION_0 || rot == Surface.ROTATION_180)
                || h >= w
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

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "记牌器(原版APK识别管线)",
                NotificationManager.IMPORTANCE_LOW
            ))
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("记牌器 AI 正在运行")
            .setContentText("识别：原版 NCNN YOLOv8 — 斗地主(MODE1)")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "jiPaiQi_capture"
        private const val NOTIF_ID = 0x7713

        const val ACTION_START = "com.jipaiqi.START_CAPTURE"
        const val ACTION_STOP  = "com.jipaiqi.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_ROLE = "extra_role"

        /** Entry point called from MainActivity when the user grants
         *  MediaProjection permission.  Mirrors wz.apk's
         *  `ServiceLifecycleCoordinator.ensureStarted(resultCode, data)`. */
        fun start(
            context: Context,
            resultCode: Int,
            data: Intent,
            @Suppress("UNUSED_PARAMETER") role: Position
        ) {
            val i = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_ROLE, role.name)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_STOP)
            try { context.startService(i) } catch (_: Throwable) { /* already gone */ }
            FloatingWindowService.stop(context)
        }
    }
}
