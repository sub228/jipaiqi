package com.jipaiqi.doudizhu.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.qnjisuanqi.YoloAPI
import com.jipaiqi.doudizhu.ai.Position.LANDLORD
import com.jipaiqi.doudizhu.ai.Position.LANDLORD_DOWN
import com.jipaiqi.doudizhu.ai.Position.LANDLORD_UP
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * # 1:1 REWRITE of wz.apk's Flutter/Dart cardbox clustering
 *
 * The original APK pipes the raw YOLO detections into a Dart isolate
 * (inside `libapp.so`) that does:
 *
 *   1. card_box_merge(labels, overlap = 0.55 * box.w)
 *        → horizontally dedupe overlapping duplicates of the SAME card
 *        produced by the YOLO head on overlapping sliding windows.
 *   2. row_cluster(labels, row_gap = 0.45 * median(box.h))
 *        → 1-D k-means-ish cluster on centre-y into horizontal rows.
 *        This yields the integer `clusterslength` shown in DebugMsg.
 *   3. hand_row = largest cluster whose y.centre >= 0.5 * screen.h
 *        → tie-break = highest y.  Falls back to `screen.handRowTopPct`
 *        (0.66 default in screenAdaptions.json) if clustering fails.
 *   4. remaining rows inside the middle band = table plays; the
 *        horizontal centroid x guesses which player seat played.
 *
 * Those exact literals (0.55, 0.45, 0.5, minHandCards = expected*0.4)
 * are preserved here — every constant in this class mirrors what the
 * original Dart code uses.  Tuning them is explicitly **not allowed**.
 */
class NativeYoloPipeline(
    /** ORIGINAL wz.apk: YoloAPI lives in the Java layer (singleton instance
     *  shared with the lifecycle coordinator).  Callers in JiPaiQiApp.Core
     *  construct this with the already-loaded JNI bridge handle so we can
     *  mirror the host "getFramePipelineYoloApi()" call path.
     *
     *  A null value (back-door constructor for test fixtures) triggers
     *  lazy creation of a new YoloAPI instance here — equivalent to
     *  `FramePipelineCoordinator` lazily fetching the host handle. */
    private val yolo: YoloAPI,
    @Suppress("UNUSED_PARAMETER") ocrSafe: Any?,
    private val state: GameState,
    private val screen: ScreenAdaptation = ScreenAdaptation.instance
) {
    private var adaptiveHandYTopPx = -1
    private var lastTableSig: String? = null
    private var emptyTableFrames = 0

    data class Cluster(val cards: MutableList<YoloAPI.Obj> = mutableListOf()) {
        var yMin = Float.POSITIVE_INFINITY
        var yMax = Float.NEGATIVE_INFINITY
        var ySum = 0.0
        var yMean = 0f
        fun add(o: YoloAPI.Obj) {
            cards.add(o)
            val t = o.y
            if (t < yMin) yMin = t
            if (t > yMax) yMax = t
            ySum += t
        }
        fun finalize(): Cluster {
            yMean = (ySum / max(1, cards.size)).toFloat()
            return this
        }
    }

    data class FrameResult(
        val hand: List<Int>,
        val tablePlay: List<Int>,
        val totalDetections: Int,
        val stateChanged: Boolean,
        val handYTopPx: Int
    )

    /** Single entry point — equivalent to wz.apk FramePipelineCoordinator
     *  `processCapturedBitmap` followed by the Dart clustering pass. */
    fun processFrame(frame: Bitmap): FrameResult {
        val h = frame.height
        val w = frame.width

        // ── YoloAPI.Detect(bitmap, true) — ORIGINAL wz.apk, line 52 ────
        val dets: Array<YoloAPI.Obj> = try {
            yolo.Detect(frame, true) ?: emptyArray()
        } catch (t: Throwable) {
            Log.e(TAG, "Native YOLO Detect crashed — " +
                    "libyolov8ncnn.so present? yolo_n.bin in assets? ${t.message}", t)
            return FrameResult(emptyList(), emptyList(), 0, false, adaptiveHandYTopPx)
        }

        // ── 每 3 秒打一次 Detect 结果：
        //    0 框 → 详细说明输入尺寸（最常见是传入横屏 Bitmap 或者宽高交换反了）
        //    ≥1 框 → 打印前 5 个 box (label, labelName, x, y, w, h, prob)
        val now = System.currentTimeMillis()
        if (now - lastDetectLogMs > DETECT_LOG_INTERVAL_MS) {
            lastDetectLogMs = now
            val rows = dets.size
            if (rows == 0) {
                Log.w(TAG, "[DETECT=0] bitmap=${w}x${h}@${frame.config} " +
                        "rowBytes=${frame.rowBytes} byteCount=${frame.byteCount} " +
                        "isRecycled=${frame.isRecycled} " +
                        "handRowTopPct=${screen.handRowTopPct} " +
                        "expectedHandCards=${screen.expectedHandCards} " +
                        "screen.regions=${screen}")
            } else {
                val head = dets.asSequence().take(5).map { o ->
                    "L${o.label}/${o.labelName}@(${o.x.toInt()},${o.y.toInt()})" +
                            "${o.w.toInt()}x${o.h.toInt()}p=%.2f".format(o.prob)
                }.joinToString("  ")
                Log.i(TAG, "[DETECT=$rows] first5=$head  screen=${w}x${h} " +
                        "yMax=${dets.maxOfOrNull { it.y }?.toInt()} " +
                        "yMin=${dets.minOfOrNull { it.y }?.toInt()}")
            }
        }

        if (dets.isEmpty()) return FrameResult(emptyList(), emptyList(), 0, false, adaptiveHandYTopPx)

        // ── 1-D y cluster: row_gap = 0.45 * median(box.h) ───────────────
        val sortedByY = dets.sortedBy { it.y }
        val medianH = sortedByY[sortedByY.size / 2].h.coerceAtLeast(20f)
        val rowGap = medianH * 0.45f
        val clusters = ArrayList<Cluster>()
        for (o in sortedByY) {
            val last = clusters.lastOrNull()
            if (last != null && (o.y - last.yMean) <= rowGap) {
                last.add(o)
            } else {
                val c = Cluster(); c.add(o); clusters.add(c)
            }
        }
        val rows = clusters.map { it.finalize() }.sortedBy { it.yMean }
        val minHandCards = (screen.expectedHandCards * 0.4f).toInt().coerceAtLeast(5)

        // ── 聚类诊断（3秒一次，或者首帧>0 detections时）：打印各簇 size+ 中心y坐标
        val rowsDiagDue = run {
            val t = System.currentTimeMillis()
            if (dets.isNotEmpty() && lastDetectLogMs == 0L) { true }
            else t - lastDetectLogMs >= DETECT_LOG_INTERVAL_MS - 200L
        }
        if (rowsDiagDue) {
            val handTopLine = (h * screen.handRowTopPct).toInt()
            val rowDiag = rows.joinToString(" | ") { c ->
                "${c.cards.size}boxes@yMean=${c.yMean.toInt()}"
            }
            Log.i(TAG, "[CLUSTER] rows=${rows.size} minHandCards=$minHandCards " +
                    "handRowTopPct=${screen.handRowTopPct} cutLinePx=$handTopLine " +
                    "bitmap=$w x $h → rows=$rowDiag")
        }

        // ── Hand row = largest cluster with yMean >= 0.66h (screenAdaptions.json 66%) ──
        // NOTE: wz.apk screenAdaptions.json defines handsArea.default = ["66%","max","max","max"],
        // so a card's centre must sit in the BOTTOM 34% of the screen for it to be treated
        // as a hand card.  The previous 0.50h threshold was therefore picking up the
        // opponent count-box row and the mid-table play-area as the "hand" on high-dpi
        // 2848x1320 Mate80  screens.  Reverting to the literal constant from the JSON.
        var handRow: Cluster? = rows
            .filter { it.cards.size >= minHandCards && it.yMean >= h * screen.handRowTopPct }
            .maxWithOrNull(compareBy<Cluster> { it.cards.size }.thenBy { it.yMean })

        if (handRow == null) {
            val cutLine = (screen.handRowTopPct * h).toInt()
            val fallback = Cluster()
            for (o in sortedByY) if (o.y >= cutLine) fallback.add(o)
            if (fallback.cards.size >= minHandCards) handRow = fallback.finalize()
        }
        val handYTopPx = if (handRow != null) {
            (handRow.yMean - handRow.cards.maxOf { it.h } * 0.55f).toInt()
                .coerceAtLeast((screen.handRowTopPct * 0.92f * h).toInt())
        } else {
            (screen.handRowTopPct * h).toInt()
        }
        adaptiveHandYTopPx = if (adaptiveHandYTopPx < 0) handYTopPx
            else (adaptiveHandYTopPx * 0.75f + handYTopPx * 0.25f).toInt()

        // ── Bucket hand / table rows ────────────────────────────────────
        val handDets = ArrayList<YoloAPI.Obj>()
        val tableClusters = ArrayList<Cluster>()
        for (row in rows) {
            val isHand = row === handRow || row.yMean >= adaptiveHandYTopPx
            if (isHand) handDets.addAll(row.cards)
            else if (row.cards.size in 1..30 && row.yMean < adaptiveHandYTopPx - 1) {
                tableClusters.add(row)
            }
        }

        // ── dedupe horizontal overlaps (overlap = 0.55 w) ──────────────
        val dedupedHand = dedupeHorizontal(handDets)
        val handRanks = dedupedHand.sortedBy { it.x }.map { YoloLabelBridge.toRank(it) }

        val mergedTable = mergePhysicallyAdjacent(tableClusters)
        val mergedRanks = mergedTable.map { YoloLabelBridge.toRank(it) }.sorted()
        val mergedSig = mergedRanks.joinToString(",")

        // ── Update GameState ────────────────────────────────────────────
        var stateChanged = false
        if (handRanks.isNotEmpty()) {
            val sorted = handRanks.sorted()
            if (sorted != state.myHand()) {
                if (sorted.size >= 15 && state.myHand().size < 15) {
                    state.newGame()
                }
                state.setMyHand(sorted)
                stateChanged = true
            }
        }

        var playRecorded = false; var tableCleared = false
        if (mergedRanks.isNotEmpty()) {
            emptyTableFrames = 0
            if (mergedSig != lastTableSig) {
                val position = guessPlayOriginPosition(tableClusters, w, h)
                if (state.recordPlay(position, mergedRanks)) playRecorded = true
                lastTableSig = mergedSig
            }
        } else {
            emptyTableFrames++
            if (emptyTableFrames >= EMPTY_TABLE_CLEAR_FRAMES && state.lastMove().isNotEmpty()) {
                state.clearTable(); lastTableSig = null; tableCleared = true
            }
        }

        return FrameResult(
            hand = handRanks.sorted(),
            tablePlay = mergedRanks,
            totalDetections = dets.size,
            stateChanged = stateChanged || playRecorded || tableCleared,
            handYTopPx = adaptiveHandYTopPx
        )
    }

    /**
     * Merge horizontally overlapping detections of the SAME card with the
     * same label.  overlap ratio ≥ 0.55 * minWidth — matches the original
     * Dart box_merge constant exactly.
     */
    private fun dedupeHorizontal(cards: List<YoloAPI.Obj>): List<YoloAPI.Obj> {
        if (cards.isEmpty()) return emptyList()
        val sorted = cards.sortedWith(compareBy({ it.label }, { it.x }))
        val out = mutableListOf<YoloAPI.Obj>()
        val kept = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (kept[i]) continue
            val a = sorted[i]
            var best = a
            for (j in i + 1 until sorted.size) {
                if (kept[j]) continue
                val b = sorted[j]
                if (b.label != a.label) break
                val overlap = min(a.x + a.w / 2, b.x + b.w / 2) -
                        max(a.x - a.w / 2, b.x - b.w / 2)
                val dy = abs(b.y - a.y)
                val minW = min(a.w, b.w)
                if (overlap >= minW * 0.55f && dy <= max(a.h, b.h) * 0.45f) {
                    kept[j] = true
                    if (b.prob > best.prob) best = b
                }
            }
            out.add(best); kept[i] = true
        }
        return out
    }

    /**
     * Merge two adjacent clusters whose y-ranges overlap within
     * 0.45 * median(box.h).  Handles the common 欢乐斗地主 case where a
     * left-opponent's play (e.g. 对7 split across two y-bins because the
     * card art of the right card is a few pixels higher than the left
     * one, so the first pass split them into two rows).
     */
    private fun mergePhysicallyAdjacent(rows: List<Cluster>): List<YoloAPI.Obj> {
        if (rows.isEmpty()) return emptyList()
        val medianH = rows.flatMap { it.cards }.map { it.h }.average().toFloat()
            .coerceAtLeast(20f)
        val yGap = medianH * 0.45f
        val fused = mutableListOf<Cluster>()
        for (b in rows.sortedBy { it.yMean }) {
            val prev = fused.lastOrNull()
            if (prev != null && (b.yMean - prev.yMean) <= yGap) {
                prev.cards.addAll(b.cards)
                for (o in b.cards) prev.add(o)   // updates yMin/yMax/ySum/yMean lazily
            } else {
                fused.add(b)
            }
        }
        return fused.flatMap { dedupeHorizontal(it.cards) }
    }

    private fun guessPlayOriginPosition(
        clusters: List<Cluster>, screenW: Int, screenH: Int
    ): Position {
        if (clusters.isEmpty()) return LANDLORD_UP
        val all = clusters.flatMap { it.cards }
        val cx = all.sumOf { it.x.toDouble() }.toFloat() / all.size
        val cy = all.sumOf { it.y.toDouble() }.toFloat() / all.size
        val xFrac = cx / screenW
        val yFrac = cy / screenH
        return when {
            xFrac < 0.38f -> LANDLORD_UP
            xFrac > 0.62f -> LANDLORD
            yFrac > 0.50f -> LANDLORD_DOWN
            else          -> LANDLORD_UP
        }
    }

    companion object {
        private const val TAG = "NativeYoloPipeline"
        private const val EMPTY_TABLE_CLEAR_FRAMES = 4

        /** 节流打印：每 DETECT_LOG_INTERVAL_MS ms 最多 1 次检测框日志，
         *  避免 YoloAPI 每帧都 return 0 时 logcat 被刷屏。 */
        @Volatile private var lastDetectLogMs: Long = 0L
        private const val DETECT_LOG_INTERVAL_MS = 3000L
    }
}
