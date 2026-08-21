package com.jipaiqi.doudizhu.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jipaiqi.doudizhu.JiPaiQiApp
import com.jipaiqi.doudizhu.ai.ScreenAdaptation
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Dual-channel (memory ring + disk) debug log collector.
 *
 * Every line emitted via [DLog] is appended:
 *   1. To an in-memory bounded deque (keeps the most recent [RING_MAX] lines,
 *      O(1) query + copy to clipboard / share sheet).
 *   2. Synchronously to `app_cache/debug_log_yyyyMMdd.txt` on disk so if the
 *      process crashes, the last hour is still recoverable.
 *
 * Exports:
 *   - [snapshotLines]: copy of current ring buffer (newest first option)
 *   - [shareFile]: flush + return a readable file path for FileProvider share
 *   - [clearAll]: wipe both ring + disk
 */
object DebugLogCollector {

    private const val RING_MAX = 2500
    private const val DISK_FLUSH_BATCH = 8
    private const val TAG = "DbgLog"

    enum class Level(val tag: String) { V("V"), D("D"), I("I"), W("W"), E("E") }

    data class Line(
        val ts: Long,
        val level: Level,
        val srcTag: String,
        val msg: String,
        val err: Throwable? = null
    ) {
        private companion object {
            private val FMT = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        }
        fun pretty(): String {
            val stack = err?.let { t ->
                "\n    " + t.stackTraceToString().replace("\n", "\n    ").trimEnd()
            } ?: ""
            return "[${FMT.format(Date(ts))}] ${level.tag}/${srcTag}: ${msg}${stack}"
        }
    }

    private val ring = ConcurrentLinkedDeque<Line>()
    private val size = AtomicLong(0L)
    private val pendingFlush = ArrayList<Line>(DISK_FLUSH_BATCH * 2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var diskFile: File? = null

    fun init(ctx: Context) {
        val dir = ctx.externalCacheDir ?: ctx.cacheDir
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val f = File(dir, "debug_log_${day}.txt")
        runCatching { if (!f.exists()) f.createNewFile() }
        diskFile = f
        i(TAG, "DebugLogCollector init, file=${f.absolutePath} maxRing=$RING_MAX")
    }

    /* ------------------------- append API ------------------------- */

    fun v(src: String, msg: String, t: Throwable? = null) = add(Level.V, src, msg, t)
    fun d(src: String, msg: String, t: Throwable? = null) = add(Level.D, src, msg, t)
    fun i(src: String, msg: String, t: Throwable? = null) = add(Level.I, src, msg, t)
    fun w(src: String, msg: String, t: Throwable? = null) = add(Level.W, src, msg, t)
    fun e(src: String, msg: String, t: Throwable? = null) = add(Level.E, src, msg, t)

    private fun add(level: Level, src: String, msg: String, t: Throwable?) {
        val line = Line(System.currentTimeMillis(), level, src, msg, t)
        // 1) mirror to logcat (so adb logcat still works as before)
        when (level) {
            Level.V -> Log.v(src, msg, t)
            Level.D -> Log.d(src, msg, t)
            Level.I -> Log.i(src, msg, t)
            Level.W -> Log.w(src, msg, t)
            Level.E -> Log.e(src, msg, t)
        }
        // 2) ring buffer (evict oldest when over cap)
        ring.addLast(line)
        var cur = size.incrementAndGet()
        while (cur > RING_MAX) {
            ring.pollFirst()
            cur = size.decrementAndGet()
            if (cur <= RING_MAX) break
        }
        // 3) disk — coalesce small writes with a short delay
        synchronized(pendingFlush) {
            pendingFlush.add(line)
            if (pendingFlush.size >= DISK_FLUSH_BATCH) {
                flushPendingLocked()
            } else {
                mainHandler.removeCallbacks(flushRunnable)
                mainHandler.postDelayed(flushRunnable, 350L)
            }
        }
    }

    private val flushRunnable = Runnable {
        synchronized(pendingFlush) { flushPendingLocked() }
    }

    private fun flushPendingLocked() {
        if (pendingFlush.isEmpty()) return
        val file = diskFile ?: run { pendingFlush.clear(); return }
        val text = buildString {
            for (l in pendingFlush) append(l.pretty()).append('\n')
        }
        pendingFlush.clear()
        runCatching { file.appendText(text) }.onFailure {
            Log.wtf(TAG, "debug_log flush failed", it)
        }
    }

    /* ------------------------- query / export ------------------------- */

    fun snapshotLines(newestFirst: Boolean = false, limit: Int = RING_MAX): List<Line> {
        val all = ArrayList(ring)
        val sub = if (newestFirst) all.takeLast(limit).reversed() else all.takeLast(limit)
        return sub
    }

    fun snapshotString(newestFirst: Boolean = false, limit: Int = RING_MAX, header: String? = null): String {
        val ls = snapshotLines(newestFirst, limit)
        val sb = StringBuilder(ls.size * 140 + 200)
        if (header != null) { sb.append(header).append('\n') }
        sb.append("── jipaiqi debug log (${ls.size} lines) ring_cap=$RING_MAX ──\n")
        val app = runCatching { JiPaiQiApp.instance }.getOrNull()
        if (app != null) {
            val core = runCatching { app.core }.getOrNull()
            sb.append("app: nativeYoloReady=${core?.nativeYoloReady ?: false} ")
            sb.append("ncnnArgs=${JiPaiQiApp.lastLoadArgs} loadOk=${JiPaiQiApp.lastLoadOk} initOk=${JiPaiQiApp.lastInitOk}\n")
            val np = core?.nativePipeline
            val screen = runCatching { ScreenAdaptation.instance }.getOrNull()
            sb.append("pipeline.last: detCnt=${np?.lastDetectCount ?: -1} " +
                    "handCnt=${np?.lastHandCount ?: -1} " +
                    "handRowTopPct=${screen?.handRowTopPct} " +
                    "platform=${screen?.currentPlatform?.name}\n")
        }
        for (l in ls) sb.append(l.pretty()).append('\n')
        return sb.toString()
    }

    fun shareFile(): File? {
        synchronized(pendingFlush) { flushPendingLocked() }
        return diskFile
    }

    fun clearAll() {
        ring.clear(); size.set(0L)
        synchronized(pendingFlush) { pendingFlush.clear() }
        val f = diskFile
        if (f != null) {
            runCatching { f.writeText("") }
        }
    }
}

/** Convenience aliases — drop-in `android.util.Log` replacements. */
object DLog {
    @JvmStatic fun v(tag: String, m: String, t: Throwable? = null) = DebugLogCollector.v(tag, m, t)
    @JvmStatic fun d(tag: String, m: String, t: Throwable? = null) = DebugLogCollector.d(tag, m, t)
    @JvmStatic fun i(tag: String, m: String, t: Throwable? = null) = DebugLogCollector.i(tag, m, t)
    @JvmStatic fun w(tag: String, m: String, t: Throwable? = null) = DebugLogCollector.w(tag, m, t)
    @JvmStatic fun e(tag: String, m: String, t: Throwable? = null) = DebugLogCollector.e(tag, m, t)
}
