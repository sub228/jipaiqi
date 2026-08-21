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
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
import android.util.DisplayMetrics
import android.util.Log
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
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * # 1:1 PORT of wz.apk
 *   FloatWindowActions.java
 *   + FramePipelineCoordinator.java
 *   + NewFloatingWindowService.java (frame loop + ImageUtils.imageToBitmap)
 *
 * Literal constants preserved verbatim:
 *   ImageReader format = 1 (RGBX_8888)
 *   VirtualDisplay flags = 16 (VIRTUAL_DISPLAY_FLAG_PUBLIC)
 *   maxImages = 5 (MODE1/3/4) or 12 (MODE2)
 *   expectedCaptureSize = {H, W} iff MODE1 && screenHeight >= screenWidth
 *   MODE3  -> rotateMinus90AndScale(bmp, 1.0f)   [i.e. rotate -90]
 *   MODE4  -> rotateMinus90AndScale1(bmp, 1.0f)  [i.e. rotate -270 = +90]
 *   imageToBitmap = copyImagePlaneToBitmap, exact padding & row stride logic
 *   YoloAPI.Detect(bitmap, true) — z = true fixed for 斗地主 mode profile
 */
class ScreenCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frameProcessing = AtomicBoolean(false)
    private val frameLock = Mutex()

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    // ── Mirror wz.apk ScreenMetrics (FloatWindowLayoutHelper.captureAndStoreScreenMetrics) ──
    @Volatile private var mScreenWidth: Int = 0
    @Volatile private var mScreenHeight: Int = 0
    @Volatile private var mScreenDensity: Int = 0

    enum class Mode { MODE1, MODE2, MODE3, MODE4 }
    /** 斗地主 = MODE1 by default.  The model weights for yolo_n were trained
     *  on mode1 screen captures so we must not rotate anything. */
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
    // Lifecycle: start foreground, then init capture pipeline.
    // ─────────────────────────────────────────────────────────────────
    private fun startCapture(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent = intent.getParcelableExtra(EXTRA_RESULT_DATA)
            ?: run { stopSelf(); return }
        startForegroundWithNotification()

        val app = (application as JiPaiQiApp).core
        app.ensureReady()

        // Screen-capture handler thread at PRIORITY_DISPLAY to match wz.apk.
        val ht = HandlerThread("screen-capture",
            android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        captureThread = ht
        captureHandler = Handler(ht.looper)

        captureScreenMetrics()

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopCapture(); stopSelf() }
            }, captureHandler!!)
        }

        // ── FloatWindowActions.createImageReader + virtualDisplay ─────
        createImageReader(force = true)
        virtualDisplay()

        // ── wz.apk uses a periodic Runnable (postNextFrameCapture 100ms)
        //    rather than ImageReader.setOnImageAvailableListener to avoid
        //    stalls when the ImageReader surface back-pressures.  We mirror
        //    exactly: postDelayed 100ms loop with CAS guard.
        captureHandler?.post(runnableWrapper)

        Log.i(TAG, "pipeline started mode=$currentMode " +
                "screen=${mScreenWidth}x${mScreenHeight}@${mScreenDensity}dpi " +
                "reader=${imageReader?.width}x${imageReader?.height} " +
                "nativeYoloReady=${app.nativeYoloReady}")
    }

    // ─────────────────────────────────────────────────────────────────
    // Frame loop — identical to NewFloatingWindowService.frameCaptureRunnable
    // Declared as a private method then wrapped in a Runnable to avoid the
    // Kotlin compiler recursive-type problem with anonymous self-reference.
    // ─────────────────────────────────────────────────────────────────
    private fun tickFrame() {
        val handler = captureHandler ?: return
        if (!frameProcessing.compareAndSet(false, true)) {
            handler.postDelayed(runnableWrapper, 100L)
            return
        }
        try {
            if (imageReader == null) {
                recreateCapturePipelineIfSizeChanged()
            } else {
                val image = try { imageReader!!.acquireLatestImage() }
                catch (_: Throwable) { null }
                if (image != null) {
                    var bmp: Bitmap? = null
                    var released = false
                    try {
                        bmp = copyImagePlaneToBitmap(image, image.width, image.height)
                        if (bmp != null) {
                            val prepared = prepareFrameBitmap(bmp)
                            val finalBmp = bmp
                            scope.launch {
                                try {
                                    processFrameWithPipeline(prepared)
                                } catch (t: Throwable) {
                                    Log.w(TAG, "pipeline error: ${t.message}", t)
                                } finally {
                                    if (prepared !== finalBmp) runCatching { prepared?.recycle() }
                                    runCatching { finalBmp.recycle() }
                                    if (!released) { released = true; runCatching { image.close() } }
                                    frameProcessing.set(false)
                                    captureHandler?.postDelayed(runnableWrapper, 100L)
                                }
                            }
                            return
                        }
                    } finally {
                        if (!released) { released = true; runCatching { image.close() } }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "frame loop error: ${t.message}", t)
        }
        frameProcessing.set(false)
        handler.postDelayed(runnableWrapper, 100L)
    }

    private val runnableWrapper: Runnable = Runnable { tickFrame() }

    @Volatile var lastFrameHandCount: Int = 0
    @Volatile var lastFrameDetections: Int = 0
    @Volatile var lastFrameYoloReturned: Int = 0

    private suspend fun processFrameWithPipeline(prepared: Bitmap) {
        if (!frameLock.tryLock()) return
        try {
            val core = (application as JiPaiQiApp).core
            val changed = core.nativePipeline?.let { np ->
                val r = np.processFrame(prepared)
                lastFrameHandCount = r.hand.size
                lastFrameDetections = r.totalDetections
                lastFrameYoloReturned = r.totalDetections
                r.stateChanged
            } ?: run {
                val r = core.pipeline?.processFrame(prepared)
                lastFrameHandCount = r?.hand?.size ?: 0
                lastFrameDetections = r?.hand?.size ?: 0
                lastFrameYoloReturned = 0
                r?.stateChanged ?: false
            }
            if (changed) core.notifyStateChanged()
        } finally {
            frameLock.unlock()
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // FloatWindowActions — image reader + virtual display verbatim
    // ─────────────────────────────────────────────────────────────────
    private fun createImageReader(force: Boolean) {
        val size = expectedCaptureSize()
        val w = size[0]; val h = size[1]
        if (w <= 0 || h <= 0) return
        val existing = imageReader
        if (existing != null && !force &&
            existing.width == w && existing.height == h) return
        if (existing != null) runCatching { existing.close() }

        val maxImages = if (currentMode == Mode.MODE2) 12 else 5
        // format = 1 = PixelFormat.RGBX_8888 (wz.apk hard-coded literal).
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBX_8888, maxImages)
        Log.i(TAG, "createImageReader size=${w}x$h mode=$currentMode maxImages=$maxImages")
    }

    private fun virtualDisplay() {
        val p = projection ?: return
        val r = imageReader ?: return
        val surface = runCatching { r.surface }.getOrNull()
        if (surface == null || !surface.isValid) return
        if (virtualDisplay != null) return
        val size = expectedCaptureSize()
        val d = mScreenDensity
        if (size[0] <= 0 || size[1] <= 0 || d <= 0) return
        // flags = 16 (VIRTUAL_DISPLAY_FLAG_PUBLIC) — wz.apk literal.
        try {
            virtualDisplay = p.createVirtualDisplay(
                "screen-mirror", size[0], size[1], d,
                /* flags = */ 16, surface, null, null)
            Log.i(TAG, "virtualDisplay created ${size[0]}x${size[1]} d=$d flag=16")
        } catch (t: Throwable) {
            Log.e(TAG, "createVirtualDisplay failed", t)
        }
    }

    private fun recreateCapturePipelineIfSizeChanged() {
        val size = expectedCaptureSize()
        val d = mScreenDensity
        val r = imageReader
        val sizeMismatch = if (r != null) r.width != size[0] || r.height != size[1] else true
        val missingDisplay = virtualDisplay == null
        if (sizeMismatch || missingDisplay) {
            runCatching { virtualDisplay?.release() }; virtualDisplay = null
            createImageReader(force = true)
            virtualDisplay()
        }
    }

    // wz.apk FloatWindowActions.expectedCaptureSize lines 158..166.
    private fun expectedCaptureSize(): IntArray {
        // Original: if (currentMode == MODE1 && isPortrait()) return {H, W}.
        val portrait = isPortrait()
        return if (currentMode == Mode.MODE1 && portrait) intArrayOf(mScreenHeight, mScreenWidth)
               else intArrayOf(mScreenWidth, mScreenHeight)
    }

    // wz.apk NewFloatingWindowService.isPortrait — *NOT* based on display
    // rotation.  It's a pure size check so a phone rotated to landscape
    // physically but reporting a w>=h capture still counts as "landscape".
    private fun isPortrait(): Boolean = mScreenHeight >= mScreenWidth

    // ─────────────────────────────────────────────────────────────────
    // FramePipelineCoordinator.prepareFrameBitmap
    // MODE3  -> true  -> rotateMinus90AndScale  (bmp, 1.0f)  => -90°
    // MODE4  -> false -> rotateMinus90AndScale1 (bmp, 1.0f)  => -270° = +90°
    // ─────────────────────────────────────────────────────────────────
    private fun prepareFrameBitmap(bitmap: Bitmap): Bitmap {
        if (bitmap.isRecycled) return bitmap
        return when (currentMode) {
            Mode.MODE3 -> rotateAndScaleBitmap(bitmap, 1.0f, -90.0f)
            Mode.MODE4 -> rotateAndScaleBitmap(bitmap, 1.0f, -270.0f)
            else       -> bitmap
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // ImageUtils.copyImagePlaneToBitmap (wz.apk lines 112..173)
    // ─────────────────────────────────────────────────────────────────
    private fun copyImagePlaneToBitmap(image: Image, i2: Int, i3: Int): Bitmap? {
        val planes: Array<Image.Plane>? = image.planes
        if (planes == null || planes.isEmpty()) return null
        val plane = planes[0]
        val duplicate: ByteBuffer = plane.buffer.duplicate()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val i6 = i2 * 4
        val position = duplicate.position()
        val limit = duplicate.limit()
        val max = max(0, limit - position)
        val createBitmap: Bitmap = Bitmap.createBitmap(i2, i3, Bitmap.Config.ARGB_8888)
        if (pixelStride == 4 && rowStride == i6 && max >= i6 * i3) {
            duplicate.position(position)
            duplicate.limit(position + i6 * i3)
            createBitmap.copyPixelsFromBuffer(duplicate)
            return createBitmap
        }
        if (pixelStride == 4 && rowStride > 0) {
            val out = ByteArray(i6 * i3)
            var outIdx = 0
            val tmp = ByteArray(i6)
            for (i7 in 0 until i3) {
                val i8 = i7 * rowStride + position
                if (i8 < limit) {
                    val i4 = max(0, min(limit, i8 + i6) - i8)
                    if (i4 > 0) {
                        duplicate.limit(limit)
                        duplicate.position(i8)
                        duplicate.limit(i8 + i4)
                        duplicate.get(tmp, 0, i4)
                        System.arraycopy(tmp, 0, out, outIdx, i4)
                        outIdx += i4
                    }
                }
                val i9 = i6 - (outIdx - i7 * i6)
                if (i9 > 0) outIdx += i9
            }
            createBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(out))
            return createBitmap
        }
        if (pixelStride > 0 && rowStride > 0) {
            val j2 = rowStride.toLong() * i3
            if (max < j2) { createBitmap.recycle(); return null }
            val strideW = (rowStride - pixelStride * i2) / pixelStride + i2
            val bmp2 = Bitmap.createBitmap(strideW, i3, Bitmap.Config.ARGB_8888)
            val dup2 = plane.buffer.duplicate()
            dup2.position(position)
            dup2.limit(position + j2.toInt())
            bmp2.copyPixelsFromBuffer(dup2)
            val out = Bitmap.createBitmap(bmp2, 0, 0, i2, i3)
            bmp2.recycle(); createBitmap.recycle()
            return out
        }
        createBitmap.recycle()
        return null
    }

    // ─────────────────────────────────────────────────────────────────
    // ImageUtils.rotateAndScaleBitmap (wz.apk lines 175..207)
    // ─────────────────────────────────────────────────────────────────
    private fun rotateAndScaleBitmap(bitmap: Bitmap, scale: Float, degrees: Float): Bitmap {
        val w = bitmap.width
        val scaledW = (w * scale).toInt()
        val h = bitmap.height
        val scaledH = (h * scale).toInt()
        val m = Matrix()
        m.preTranslate(-w / 2.0f, -h / 2.0f)
        m.postScale(scale, scale)
        m.postRotate(degrees)
        // For ±90 rotations the output canvas is HxW of the scaled dims.
        val outW = (h * scale).toInt()
        val outH = (w * scale).toInt()
        val cfg = bitmap.config ?: Bitmap.Config.ARGB_8888
        val out = Bitmap.createBitmap(max(1, outW), max(1, outH), cfg)
        val canvas = Canvas(out)
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            isAntiAlias = true
            setXfermode(PorterDuffXfermode(PorterDuff.Mode.SRC))
        }
        m.postTranslate(outW / 2.0f, outH / 2.0f)
        canvas.drawBitmap(bitmap, m, paint)
        bitmap.recycle()
        return out
    }

    // ─────────────────────────────────────────────────────────────────
    // ScreenMetrics (FloatWindowLayoutHelper.captureAndStoreScreenMetrics)
    // mScreenWidth  = displayMetrics.widthPixels  (getDefaultDisplay().getMetrics)
    // mScreenHeight = getRealMetrics().heightPixels (ImageUtils.getHasVirtualKey)
    // ─────────────────────────────────────────────────────────────────
    private fun captureScreenMetrics() {
        val wm = applicationContext.getSystemService(Context.WINDOW_SERVICE)
                as? android.view.WindowManager
        if (wm == null) {
            val dm = resources.displayMetrics
            mScreenWidth = dm.widthPixels
            mScreenHeight = dm.heightPixels
            mScreenDensity = dm.densityDpi
            return
        }
        val dm = DisplayMetrics()
        runCatching { wm.defaultDisplay.getMetrics(dm) }
        mScreenWidth = dm.widthPixels
        mScreenDensity = dm.densityDpi
        // getHasVirtualKey = invoke Display#getRealMetrics and return heightPixels
        mScreenHeight = try {
            val real = DisplayMetrics()
            val cls = Class.forName("android.view.Display")
            cls.getMethod("getRealMetrics", DisplayMetrics::class.java)
                .invoke(wm.defaultDisplay, real)
            real.heightPixels
        } catch (_: Throwable) {
            dm.heightPixels
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Cleanup + Notification helpers
    // ─────────────────────────────────────────────────────────────────
    private fun stopCapture() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null; imageReader = null; projection = null
        scope.cancel()
    }
    override fun onDestroy() { stopCapture(); super.onDestroy() }

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
            .setContentText("识别：原版 NCNN YOLOv8 — 斗地主(MODE1) RGBX_8888+flag=16")
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
            try { context.startService(i) } catch (_: Throwable) {}
            FloatingWindowService.stop(context)
        }
    }
}
