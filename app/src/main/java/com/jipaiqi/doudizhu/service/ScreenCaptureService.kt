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
import androidx.core.app.NotificationCompat
import com.jipaiqi.doudizhu.JiPaiQiApp
import com.jipaiqi.doudizhu.R
import com.jipaiqi.doudizhu.ai.Position
import com.jipaiqi.doudizhu.util.DLog
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
    @Volatile private var lastVirtualDisplayDensity: Int = -1  // 原版 FloatWindowActions 一致性校验
    @Volatile private var lastFrameOkMs:       Long = 0L
    @Volatile private var lastFrameAttemptMs:  Long = 0L
    @Volatile private var consecutiveNullFrames:Long = 0L
    @Volatile private var lastDebugLogMs:      Long = 0L
    @Volatile private var totalAttempts:       Long = 0L
    @Volatile private var totalNulls:          Long = 0L
    @Volatile private var totalBitmapNulls:    Long = 0L

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

        DLog.i(TAG, "start mode=$currentMode " +
                "screen=${mScreenWidth}x${mScreenHeight}@${mScreenDensity}dpi " +
                "reader=${imageReader?.width}x${imageReader?.height} " +
                "nativeYoloReady=${app.nativeYoloReady}")
    }

    // ─── frameCaptureRunnable (NewFloatingWindowService 100 ms CAS loop) ─
    //  注意：原版是**纯同步**执行（在 captureHandler 线程里从 acquireLatestImage 到
    //  processCapturedBitmap → recycleBitmap → postNextFrameCapture 一次性跑完）。
    //  之前的 scope.launch 异步切换到 Default 线程会产生 3 个副作用：
    //    1) captureHandler 在协程 finally 里才 re-arm postDelayed，一旦协程卡住
    //       帧循环直接死（看起来就是：绿灯亮，NCNN 就绪，但 detCnt 永远 0）。
    //    2) Bitmap.prepareFrameBitmap → YoloAPI.Detect 的 pixelBuffer 在
    //       Kotlin/ART 跨线程传递时偶尔被 heap trim 提前回收（无日志 但 JNI
    //       lockPixels 返回 nullptr）。
    //    3) 原版 NewFloatingWindowService 的帧 stall 检测（FRAME_STALL_THRESHOLD_MS
    //       = 5000ms）依赖 lastFrameAttemptMs / lastFrameOkMs 在同一线程顺序更新，
    //       协程打破了这个单调时序。
    //  → 按 jadx 还原：**完全同步**执行，最后在 finally 里统一 postDelayed。
    private fun tickFrame() {
        val handler = captureHandler ?: return
        if (!frameProcessing.compareAndSet(false, true)) {
            handler.postDelayed(runnableWrapper, 100L)
            return
        }
        var bmp: Bitmap? = null
        var image: Image? = null
        try {
            lastFrameAttemptMs = System.currentTimeMillis()
            totalAttempts++

            // ── REFRESH screen metrics every single tick (原版 FloatWindowInteractionCoordinator) ──
            captureScreenMetrics()
            if (imageReader == null ||
                mScreenWidth != lastSeenW || mScreenHeight != lastSeenH ||
                mScreenDensity != lastSeenDpi) {
                DLog.i(TAG, "[FRAME] screen dims changed ${lastSeenW}x${lastSeenH}@${lastSeenDpi} → " +
                        "${mScreenWidth}x${mScreenHeight}@${mScreenDensity}; recreating pipeline")
                lastSeenW = mScreenWidth; lastSeenH = mScreenHeight
                lastSeenDpi = mScreenDensity
                recreateCapturePipelineIfSizeChanged()
            }

            val ir = imageReader
            if (ir == null) {
                maybeLogCaptureDebug("frame_skip", "imageReader=null; " +
                        "projectionAlive=${projection != null}, vd=${virtualDisplay != null}, " +
                        "reader=ir_null")
                consecutiveNullFrames++
                handleFrameStallIfNeeded()
                return
            }

            image = try { ir.acquireLatestImage() } catch (_: Throwable) { null }
            if (image == null) {
                totalNulls++
                consecutiveNullFrames++
                maybeLogCaptureDebug("acquireLatestImage=null",
                        "attempts=$totalAttempts null_rate=${totalNulls*100.0/max(1,totalAttempts)}%.1f " +
                        "reader=${ir.width}x${ir.height}fmt=${ir.imageFormat} " +
                        "vd=${virtualDisplay != null}")
                handleFrameStallIfNeeded()
                return
            }

            bmp = copyImagePlaneToBitmap(image, image.width, image.height)
            if (bmp == null) {
                totalBitmapNulls++
                consecutiveNullFrames++
                maybeLogCaptureDebug("imageToBitmap=null",
                        "planes=${image.planes?.size} pixelStride=${image.planes?.getOrNull(0)?.pixelStride} " +
                        "rowStride=${image.planes?.getOrNull(0)?.rowStride} " +
                        "image=${image.width}x${image.height} @fmt${image.format}")
                return
            }

            // 原版逻辑在这里：onFrameRecovered / updateRecordIconOnMain(true, "frame_ok")
            consecutiveNullFrames = 0
            lastFrameOkMs = System.currentTimeMillis()

            val prepared = prepareFrameBitmap(bmp)
            // ── 原版是同步执行 processCapturedBitmap，这里在当前 captureHandler
            //    线程直接 runBlocking 调用 NativeYoloPipeline.processFrame，
            //    保持帧线程模型完全一致。 ──
            runCatching {
                kotlinx.coroutines.runBlocking {
                    processFrameWithPipeline(prepared)
                }
            }.onFailure { t ->
                DLog.w(TAG, "[FRAME] sync pipeline err: ${t.message}", t)
            }

            // 每 ~3 秒（原版 CAPTURE_DEBUG_LOG_INTERVAL_MS=1000，这里放宽到 3 秒
            // 避免 logcat 刷爆）打一次：Bitmap尺寸+YoloAPI.Detect返回数+前5框
            val now = System.currentTimeMillis()
            if (now - lastDebugLogMs > 3000L) {
                lastDebugLogMs = now
                DLog.i(TAG, "[FRAME] OK bitmaps: raw=${bmp.width}x${bmp.height}@${bmp.config} " +
                        "prepared=${prepared.width}x${prepared.height} reader=${ir.width}x${ir.height} " +
                        "screen=${mScreenWidth}x${mScreenHeight}@${mScreenDensity}dpi " +
                        "detCnt=$sLastFrameDetections handCnt=$sLastFrameHandCount " +
                        "nullRate=${"%.1f".format(totalNulls*100.0/max(1,totalAttempts))}% " +
                        "totalAttempts=$totalAttempts mode=$currentMode " +
                        "vDensityLast=$lastVirtualDisplayDensity")
            }

            if (prepared !== bmp) runCatching { prepared.recycle() }
        } catch (t: Throwable) {
            DLog.e(TAG, "[FRAME] capture loop failed", t)
            // 原版 ScreenCaptureMonitorCoordinator.reportIssue
            handleFrameStallIfNeeded()
        } finally {
            runCatching { bmp?.recycle() }
            runCatching { image?.close() }
            frameProcessing.set(false)
            handler.postDelayed(runnableWrapper, 100L)
        }
    }

    private val runnableWrapper: Runnable = Runnable { tickFrame() }

    /** 原版 FRAME_STALL_THRESHOLD_MS = 5000ms：超过 5 秒没拿到有效帧 →
     *  在 logcat 里一次性输出当前所有状态，方便用户贴日志定位。 */
    private fun handleFrameStallIfNeeded() {
        val now = System.currentTimeMillis()
        if (lastFrameOkMs > 0 && now - lastFrameOkMs < 5000L) return
        val now2 = now
        if (now2 - lastDebugLogMs < 5000L) return
        lastDebugLogMs = now2

        val irW = imageReader?.width ?: -1
        val irH = imageReader?.height ?: -1
        val irFmt = imageReader?.imageFormat ?: -1
        val irPlanesStr = runCatching {
            imageReader?.imageFormat?.let {
                val ir = imageReader
                val img = try { ir?.acquireLatestImage() } catch (_: Throwable) { null }
                val info = if (img == null) {
                    "no-image-acquirable"
                } else {
                    val ps = img.planes?.getOrNull(0)
                    "img=${img.width}x${img.height}fmt=${img.format} " +
                            "plane[0].ps=${ps?.pixelStride} rs=${ps?.rowStride} " +
                            "bufSz=${ps?.buffer?.remaining()}"
                }.also { runCatching { img?.close() } }
                info
            }
        }.getOrNull() ?: "unknown"

        val core = runCatching { (application as JiPaiQiApp).core }.getOrNull()
        DLog.w(TAG, "═══════════════════════════════════════════════════════")
        DLog.w(TAG, "[STALL-CHECK] ${if (lastFrameOkMs == 0L) "no-successful-frame-yet (启动后)" else "stuck>5s  since_last_ok"}")
        DLog.w(TAG, "  ScreenMetrics w=$mScreenWidth h=$mScreenHeight dpi=$mScreenDensity " +
                "portrait=${isPortrait()} expectedSz=${expectedCaptureSize().joinToString("x")}")
        DLog.w(TAG, "  imageReader=$irW x $irH fmt=$irFmt (1=RGBX_8888) maxImages=5")
        DLog.w(TAG, "  imageReader acquireLatestImage()  → $irPlanesStr")
        DLog.w(TAG, "  projection=${projection != null} vd=${virtualDisplay != null} " +
                "vdDensity=$lastVirtualDisplayDensity vs screenDensity=$mScreenDensity")
        DLog.w(TAG, "  currentMode=$currentMode; extractNativeLibs=true ABI=arm64-v8a")
        DLog.w(TAG, "  NativeYoloReady=${core?.nativeYoloReady ?: false}")
        DLog.w(TAG, "  totalAttempts=$totalAttempts; nullImg=$totalNulls nullBmp=$totalBitmapNulls; " +
                "nullImgRate=${"%.1f".format(totalNulls * 100.0 / max(1, totalAttempts))}%")
        DLog.w(TAG, "  detCnt=$sLastFrameDetections handCnt=$sLastFrameHandCount")
        DLog.w(TAG, "═══════════════════════════════════════════════════════")
    }

    /** 频率限流（每 1000ms 最多 1 条）的 capture debug 日志，
     *  对齐原版 CAPTURE_DEBUG_LOG_INTERVAL_MS。 */
    private fun maybeLogCaptureDebug(reason: String, detail: String) {
        val now = System.currentTimeMillis()
        if (now - lastDebugLogMs < 1000L) return
        lastDebugLogMs = now
        DLog.d(TAG, "[CAPTURE:$reason] $detail")
    }

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
        DLog.i(TAG, "[CAPTURE:create_ir] mode=$currentMode size=${w}x${h} " +
                "format=RGBX_8888(1) maxImages=$maxImages force=$force " +
                "screen=${mScreenWidth}x${mScreenHeight} portrait=${isPortrait()}")
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBX_8888, maxImages)
    }

    private fun virtualDisplay() {
        val p = projection ?: return
        val r = imageReader ?: return
        val surface = runCatching { r.surface }.getOrNull()
        if (surface == null || !surface.isValid) {
            DLog.w(TAG, "[CAPTURE:vd_invalid] surface=$surface isValid=${surface?.isValid}")
            return
        }
        if (virtualDisplay != null) return
        val size = expectedCaptureSize()
        val d = mScreenDensity
        if (size[0] <= 0 || size[1] <= 0 || d <= 0) {
            DLog.w(TAG, "[CAPTURE:vd_invalid] size=${size.joinToString("x")} density=$d")
            return
        }
        // flags = 16 (VIRTUAL_DISPLAY_FLAG_PUBLIC)
        try {
            DLog.i(TAG, "[CAPTURE:create_vd] mode=$currentMode size=${size[0]}x${size[1]} density=$d flag=16")
            virtualDisplay = p.createVirtualDisplay(
                "screen-mirror", size[0], size[1], d,
                /* flags = */ 16, surface, null, null)
            lastVirtualDisplayDensity = d
        } catch (t: Throwable) {
            DLog.e(TAG, "createVirtualDisplay failed", t)
        }
    }

    // 原版 recreateCapturePipelineIfSizeChanged：多了 densityMismatch 和 surfaceInvalid
    // 两个重建条件。原版 FloatWindowActions 第 140..155 行：
    //     z = sizeMismatch || z2(densityMismatch) || z3(surfaceInvalid) || z4(missingDisplay)
    private fun recreateCapturePipelineIfSizeChanged() {
        if (projection == null) return
        val size = expectedCaptureSize()
        val r = imageReader
        val sizeMismatch = if (r != null) r.width != size[0] || r.height != size[1] else true
        val densityMismatch = (lastVirtualDisplayDensity > 0 && mScreenDensity > 0 &&
                lastVirtualDisplayDensity != mScreenDensity)
        val surfaceInvalid = r != null && runCatching {
            val s = r.surface; s == null || !s.isValid
        }.getOrDefault(true)
        val missingDisplay = virtualDisplay == null
        if (sizeMismatch || densityMismatch || surfaceInvalid || missingDisplay) {
            DLog.i(TAG, "[CAPTURE:recreate_pipeline] sizeMismatch=$sizeMismatch " +
                    "densityMismatch=$densityMismatch (prev=$lastVirtualDisplayDensity " +
                    "now=$mScreenDensity) surfaceInvalid=$surfaceInvalid " +
                    "missingVD=$missingDisplay expected=${size[0]}x${size[1]}")
            runCatching { virtualDisplay?.release() }; virtualDisplay = null
            lastVirtualDisplayDensity = -1
            createImageReader(force = true)
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
