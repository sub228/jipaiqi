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
import kotlin.math.max
import kotlin.math.min

/**
 * # 1:1 PORT of wz.apk Java layer
 *
 * Literal constants & data flow copied VERBATIM from jadx:
 *
 *   FloatWindowActions.java     — ImageReader.newInstance(w, h, RGBX_8888=1, 5)
 *                               — projection.createVirtualDisplay(…, flag=16, …)
 *                               — expectedCaptureSize()
 *                               — MODE1 && isPortrait() → swap WxH → {H, W}
 *   ImageUtils.java             — copyImagePlaneToBitmap(Image, i2, i3) lines 112..173
 *                                 (ByteBuffer.duplicate + obtainExactRgbaBuffer +
 *                                  obtainZeroPaddingBuffer + rowStride loop)
 *                               — rotateAndScaleBitmap lines 175..207
 *   FramePipelineCoordinator.java — prepareFrameBitmap → MODE3 rot-90, MODE4 rot-270
 *                                 — YoloAPI.Detect(bitmap, z=true)
 *   NewFloatingWindowService.java — frameCaptureRunnable 100ms CAS polling,
 *                                   postDelayed reschedule.
 *
 * This class intentionally contains zero "optimisations", "sensible defaults"
 * or "cleanups" relative to the jadx output.  Every conditional, every
 * branch, every temp variable name mirrors the source.
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

    // ── FloatWindowLayoutHelper.captureAndStoreScreenMetrics globals ──
    @Volatile private var mScreenWidth:  Int = 0
    @Volatile private var mScreenHeight: Int = 0
    @Volatile private var mScreenDensity:Int = 0
    @Volatile private var lastSeenW:    Int = -1
    @Volatile private var lastSeenH:    Int = -1
    @Volatile private var lastSeenDpi:  Int = -1

    /** 斗地主固定为 MODE1 (wz.apk default) */
    enum class Mode { MODE1, MODE2, MODE3, MODE4 }
    private var currentMode: Mode = Mode.MODE1

    // ── ImageUtils thread-local reusable buffers (verbatim) ──────────
    private val exactRgbaBuffer = ThreadLocal<ByteBuffer>()
    private val zeroPaddingBuf = ThreadLocal<ByteArray>()

    private fun obtainExactRgbaBuffer(i2: Int): ByteBuffer {
        var b = exactRgbaBuffer.get()
        if (b == null || b.capacity() < i2) {
            b = ByteBuffer.allocateDirect(i2)
            exactRgbaBuffer.set(b)
        }
        b.clear(); b.limit(i2)
        return b
    }

    private fun obtainZeroPaddingBuffer(i2: Int): ByteArray {
        var a = zeroPaddingBuf.get()
        if (a == null || a.size < i2) {
            a = ByteArray(i2)
            zeroPaddingBuf.set(a)
        }
        return a
    }

    // Reusable rotation paint — mirrors ImageUtils.ROTATION_PAINT.
    private val rotationPaint = Paint(
        Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG or Paint.ANTI_ALIAS_FLAG
    ).apply { setXfermode(PorterDuffXfermode(PorterDuff.Mode.SRC)) }
    private val rotationMatrix = Matrix()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP  -> { stopCapture(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent = intent.getParcelableExtra(EXTRA_RESULT_DATA)
            ?: run { stopSelf(); return }
        startForegroundWithNotification()

        val app = (application as JiPaiQiApp).core
        app.ensureReady()

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

        createImageReader(force = true)
        virtualDisplay()

        captureHandler?.post(runnableWrapper)

        Log.i(TAG, "start mode=$currentMode " +
                "screen=${mScreenWidth}x${mScreenHeight}@${mScreenDensity}dpi " +
                "reader=${imageReader?.width}x${imageReader?.height} " +
                "nativeYoloReady=${app.nativeYoloReady}")
    }

    // ─── frameCaptureRunnable (NewFloatingWindowService 100 ms CAS loop) ─
    private fun tickFrame() {
        val handler = captureHandler ?: return
        if (!frameProcessing.compareAndSet(false, true)) {
            handler.postDelayed(runnableWrapper, 100L)
            return
        }
        try {
            // ── REFRESH screen metrics every single tick. ───────────────
            //    wz.apk FloatWindowInteractionCoordinator::handleConfigurationChanged
            //    re-reads DisplayMetrics whenever the underlying surface is
            //    recreated.  On Huawei "智能分辨率" devices, screen WxH can
            //    change mid-hand from 2848×1320 to 2340×1080 (and vice
            //    versa) without a ConfigurationChanged broadcast, so the
            //    only reliable way is to poll each frame.
            captureScreenMetrics()
            if (imageReader == null ||
                mScreenWidth != lastSeenW || mScreenHeight != lastSeenH ||
                mScreenDensity != lastSeenDpi) {
                Log.i(TAG, "screen dims changed $lastSeenW x $lastSeenH → " +
                        "$mScreenWidth x $mScreenHeight; recreating pipeline")
                lastSeenW = mScreenWidth; lastSeenH = mScreenHeight
                lastSeenDpi = mScreenDensity
                recreateCapturePipelineIfSizeChanged()
            }

            val image = imageReader?.let { r ->
                try { r.acquireLatestImage() } catch (_: Throwable) { null }
            }

            if (image != null) {
                var released = false
                var bmp: Bitmap? = null
                try {
                    bmp = copyImagePlaneToBitmap(image, image.width, image.height)
                    if (bmp != null) {
                        val prepared = prepareFrameBitmap(bmp)
                        val finalBmp = bmp
                        scope.launch {
                            try {
                                processFrameWithPipeline(prepared)
                            } catch (t: Throwable) {
                                Log.w(TAG, "pipeline err: ${t.message}", t)
                            } finally {
                                if (prepared !== finalBmp) runCatching { prepared.recycle() }
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
        } catch (t: Throwable) {
            Log.w(TAG, "frame loop error: ${t.message}", t)
        }
        frameProcessing.set(false)
        handler.postDelayed(runnableWrapper, 100L)
    }

    private val runnableWrapper: Runnable = Runnable { tickFrame() }

    @Volatile var lastFrameHandCount:   Int = 0
    @Volatile var lastFrameDetections:  Int = 0

    private suspend fun processFrameWithPipeline(prepared: Bitmap) {
        if (!frameLock.tryLock()) return
        try {
            val core = (application as JiPaiQiApp).core
            val changed = core.nativePipeline?.let { np ->
                val r = np.processFrame(prepared)
                lastFrameHandCount = r.hand.size
                lastFrameDetections = r.totalDetections
                r.stateChanged
            } ?: run {
                val r = core.pipeline?.processFrame(prepared)
                lastFrameHandCount = r?.hand?.size ?: 0
                lastFrameDetections = r?.hand?.size ?: 0
                r?.stateChanged ?: false
            }
            // Always re-render the floating window after each frame so the
            // diagnostic "NCNN=XX框" overlay refreshes even when the game
            // state has not changed (e.g. waiting for the first hand).
            sLastFrameHandCount = lastFrameHandCount
            sLastFrameDetections = lastFrameDetections
            core.notifyStateChanged()
        } finally {
            frameLock.unlock()
        }
    }

    // ── FloatWindowActions literal methods ───────────────────────────
    private fun createImageReader(force: Boolean) {
        val size = expectedCaptureSize()
        val w = size[0]; val h = size[1]
        if (w <= 0 || h <= 0) return
        val existing = imageReader
        if (existing != null && !force &&
            existing.width == w && existing.height == h) return
        if (existing != null) runCatching { existing.close() }

        val maxImages = if (currentMode == Mode.MODE2) 12 else 5
        // format = 1 = PixelFormat.RGBX_8888 (wz.apk hard-coded literal)
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBX_8888, maxImages)
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
        // flags = 16 (VIRTUAL_DISPLAY_FLAG_PUBLIC)
        try {
            virtualDisplay = p.createVirtualDisplay(
                "screen-mirror", size[0], size[1], d,
                /* flags = */ 16, surface, null, null)
        } catch (t: Throwable) {
            Log.e(TAG, "createVirtualDisplay failed", t)
        }
    }

    private fun recreateCapturePipelineIfSizeChanged() {
        val size = expectedCaptureSize()
        val r = imageReader
        val sizeMismatch = if (r != null) r.width != size[0] || r.height != size[1] else true
        val missingDisplay = virtualDisplay == null
        if (sizeMismatch || missingDisplay) {
            runCatching { virtualDisplay?.release() }; virtualDisplay = null
            createImageReader(force = true)
            // Recreate virtualDisplay ONLY when dimensions are valid.
            if (mScreenWidth > 0 && mScreenHeight > 0 && mScreenDensity > 0) virtualDisplay()
        }
    }

    // wz.apk FloatWindowActions.expectedCaptureSize lines 158..166.
    private fun expectedCaptureSize(): IntArray {
        val portrait = isPortrait()
        return if (currentMode == Mode.MODE1 && portrait) intArrayOf(mScreenHeight, mScreenWidth)
               else intArrayOf(mScreenWidth, mScreenHeight)
    }
    private fun isPortrait(): Boolean = mScreenHeight >= mScreenWidth

    // FramePipelineCoordinator.prepareFrameBitmap (jadx lines 26..35)
    private fun prepareFrameBitmap(bitmap: Bitmap): Bitmap {
        if (bitmap.isRecycled) return bitmap
        return when (currentMode) {
            Mode.MODE3 -> rotateAndScaleBitmap(bitmap, 1.0f, -90.0f)
            Mode.MODE4 -> rotateAndScaleBitmap(bitmap, 1.0f, -270.0f)
            else       -> bitmap
        }
    }

    // ── ImageUtils.copyImagePlaneToBitmap lines 112..173 VERBATIM ────
    //    Differences from v2.1.5 implementation that MATTER:
    //      * obtainExactRgbaBuffer returns a DIRECT ByteBuffer (mandatory for
    //        Bitmap.copyPixelsFromBuffer on API 33+ / Huawei Kirin 9010).
    //        v2.1.5 used ByteBuffer.wrap(heap byte[]) which silently
    //        writes garbage on recent ART runtimes.
    //      * obtainExactRgbaBuffer.clear() before use (resets position=0,
    //        limit=capacity) — our v2.1.5 code skipped this, so successive
    //        frames sometimes started copying from a non-zero offset.
    //      * zero-padding write uses obtainZeroPaddingBuffer() per row, same
    //        exact branch layout as lines 144..150 of the original.
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
            val buf = obtainExactRgbaBuffer(i6 * i3)
            for (i7 in 0 until i3) {
                val i8 = i7 * rowStride + position
                val i4: Int = if (i8 < limit) {
                    val i = max(0, min(limit, i8 + i6) - i8)
                    if (i > 0) {
                        duplicate.limit(limit)
                        duplicate.position(i8)
                        duplicate.limit(i8 + i)
                        buf.put(duplicate)
                    }
                    i
                } else 0
                if (i4 < i6) {
                    val i9 = i6 - i4
                    buf.put(obtainZeroPaddingBuffer(i9), 0, i9)
                }
            }
            buf.flip()
            createBitmap.copyPixelsFromBuffer(buf)
            return createBitmap
        }
        if (pixelStride > 0 && rowStride > 0) {
            val j2: Long = rowStride.toLong() * i3
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

    // ImageUtils.rotateAndScaleBitmap lines 175..207
    private fun rotateAndScaleBitmap(bitmap: Bitmap, scale: Float, degrees: Float): Bitmap {
        val w = bitmap.width
        val i2 = (w * scale).toInt()
        val h = bitmap.height
        val r2 = (h * scale).toInt()
        rotationMatrix.reset()
        rotationMatrix.preTranslate(-w / 2.0f, -r2 / 2.0f)
        rotationMatrix.postScale(scale, scale)
        rotationMatrix.postRotate(degrees)
        // After ±90° rotation output canvas is H x W of the scaled dims.
        val outW = (h * scale).toInt()
        val outH = (w * scale).toInt()
        val cfg = bitmap.config ?: Bitmap.Config.ARGB_8888
        val out = Bitmap.createBitmap(max(1, outW), max(1, outH), cfg)
        val canvas = Canvas(out)
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        rotationMatrix.postTranslate(outW / 2.0f, outH / 2.0f)
        canvas.drawBitmap(bitmap, rotationMatrix, rotationPaint)
        bitmap.recycle()
        return out
    }

    // captureAndStoreScreenMetrics — getMetrics for width, getRealMetrics for height
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

    // ── cleanup + notification helpers ───────────────────────────────
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
                CHANNEL_ID, "记牌器(v2.1.7 原版NCNN管线)",
                NotificationManager.IMPORTANCE_LOW
            ))
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("记牌器 AI 运行中")
            .setContentText("原版 wz.apk NCNN YOLOv8：RGBX_8888 + flag=16 + loadModel(0,0,6) + obtainExactRgbaBuffer")
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

        // Companion-level snapshot mirrors so the floating window (a totally
        // separate Service) can reach the last-frame diagnostics without a
        // custom binder / Broadcast.  Updated each frame inside
        // processFrameWithPipeline.
        @JvmStatic @Volatile var sLastFrameHandCount:   Int = 0
        @JvmStatic @Volatile var sLastFrameDetections:  Int = 0

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
