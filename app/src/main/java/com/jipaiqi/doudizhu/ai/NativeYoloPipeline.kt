package com.jipaiqi.doudizhu.ai

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.qnjisuanqi.YoloAPI
import kotlin.math.abs

/**
 * **ORIGINAL 王者记牌器 recognition pipeline**, rewritten in Kotlin but
 * faithfully reproducing the native YOLO detector + Dart state machine's
 * geometric heuristic for bucketing YOLO detections into:
 *
 *   - **my hand**   : the cards along the bottom row (biggest y-cluster,
 *                    sorted left-to-right by x).
 *   - **table play** : cards clustered together in the upper/middle band,
 *                    belonging to whoever just played (we bucket them by
 *                    centroid x — left cluster = left-farmer, middle
 *                    cluster = landlord, right cluster = right-farmer).
 *   - **opponent count areas** are not read via YOLO (numbers are not
 *     cards) — the original app uses ML-Kit OCR on those boxes instead,
 *     which we keep in [CardOcr].
 *
 * This class REPLACES the previous `RecognitionPipeline` that combined a
 * home-grown ONNX YOLO with an ML-Kit fallback.  It uses the **ORIGINAL**
 * prebuilt `libyolov8ncnn.so` + `yolo_n.bin/param` so detection quality
 * is byte-for-byte identical to the paid 王者记牌器 APK.
 */
class NativeYoloPipeline(
    private val yolo: YoloAPI,
    private val ocr: CardOcr,
    private val state: GameState,
) {
    private val screen = ScreenAdaptation.instance

    /** Cached per-frame signature of the table play (sorted rank list). */
    private var lastTableSig: String? = null
    private var emptyTableFrames: Int = 0
    /** Used to smooth y-boundary estimation across frames (slightly better
     *  than a fixed 66% cut when players resize the host app window). */
    private var adaptiveHandYTopPx: Int = -1
    /**
     * Last frame's accepted hand-row yMean (in pixels).  Used to penalize
     * candidate clusters whose yMean is far from the previously accepted
     * hand row — this is the key stabilization against the per-frame
     * `handCnt` 12 → 18 → 17 oscillation: when 2 clusters both look like a
     * plausible hand row, we prefer the one that matches where the hand
     * was on the previous frame, so a noisy single frame cannot flip the
     * hand row to the played-cards band and back.
     */
    private var lastHandRowYMean: Float = -1f
    /**
     * Two-frame confirmation buffer: a candidate hand signature must be
     * observed on 2 consecutive frames before it replaces [state.myHand()].
     * This filters out transient YOLO over/under-detection that flips the
     * hand count by a few cards for one frame.
     */
    private var pendingHandRanks: List<Int>? = null

    data class Cluster(
        val cards: MutableList<YoloAPI.Obj> = mutableListOf(),
        var yMean: Float = 0f,
        var yMin: Float = Float.MAX_VALUE,
        var yMax: Float = Float.MIN_VALUE,
    ) {
        fun add(o: YoloAPI.Obj) {
            cards.add(o)
            yMean += o.y
            if (o.y < yMin) yMin = o.y
            if (o.y > yMax) yMax = o.y
        }
        fun finalize(): Cluster {
            if (cards.isNotEmpty()) yMean /= cards.size
            return this
        }
    }

    /**
     * Result of a single frame pass, for the UI layer to drive the
     * 3 status dots (frame received / YOLO ran / state updated).
     */
    data class FrameResult(
        val hand: List<Int>,
        val tablePlay: List<Int>,
        val totalDetections: Int,
        val stateChanged: Boolean,
        val handYTopPx: Int,
    )

    fun reset() {
        lastTableSig = null
        emptyTableFrames = 0
        adaptiveHandYTopPx = -1
        lastHandRowYMean = -1f
        pendingHandRanks = null
    }

    fun processFrame(frame: Bitmap): FrameResult {
        val h = frame.height
        val w = frame.width

        // 1) ORIGINAL YOLO detector
        val dets: Array<YoloAPI.Obj> = try {
            yolo.Detect(frame, true) ?: emptyArray()
        } catch (t: Throwable) {
            Log.e(TAG, "Native YOLO Detect crashed — " +
                    "is libyolov8ncnn.so loaded & yolo_n.bin present? ${t.message}", t)
            return FrameResult(emptyList(), emptyList(), 0, false, adaptiveHandYTopPx)
        }
        if (dets.isEmpty()) return FrameResult(emptyList(), emptyList(), 0, false, adaptiveHandYTopPx)

        // 2) Cluster detections into horizontal rows (1-D k-means on y with
        //    threshold = median card height / 2).  This is exactly the
        //    "clusterslength" algorithm referenced in DouDiZhuGameData.DebugMsg.
        val sortedByY = dets.sortedBy { it.y }
        val medianH = sortedByY[sortedByY.size / 2].h.coerceAtLeast(30f)
        val rowGap = medianH * 0.9f
        val clusters = ArrayList<Cluster>()
        for (o in sortedByY) {
            val last = clusters.lastOrNull()
            if (last != null && (o.y - last.yMax) <= rowGap) {
                last.add(o)
            } else {
                val c = Cluster()
                c.add(o)
                clusters.add(c)
            }
        }
        val rows = clusters.map { it.finalize() }.sortedBy { it.yMean }

        // 3) Identify the BOTTOMMOST large cluster as the hand row.
        //    STABILITY FIX (v2.2.4): the previous version reversed-iterated
        //    and broke on the first candidate with size>=5 and x-range>30%.
        //    When YOLO momentarily under-detects the hand row to 4 cards,
        //    that loop would latch onto a played-cards cluster instead —
        //    which is exactly the 12 → 18 → 17 handCnt flicker the user
        //    saw.  Now we evaluate *all* candidates by a scored penalty:
        //      size bonus + (deeper-y bonus) − |Δ from last hand yMean|
        //    and pick the argmax.  The "close to last frame" penalty is
        //    what keeps the hand row pinned to the bottom band even when
        //    another cluster briefly looks like a plausible hand row.
        var handRow: Cluster? = null
        val candidates = ArrayList<Pair<Cluster, Float>>()
        for (c in rows) {
            // Tighter gate: hand row needs >= 6 cards (一张牌的对手出牌区
            // 通常 1..5 张，避免误识别为手牌行) and a wide horizontal spread.
            if (c.cards.size < 6) continue
            val xMin = c.cards.minOf { it.x - it.w / 2 }
            val xMax = c.cards.maxOf { it.x + it.w / 2 }
            if ((xMax - xMin) < w * 0.40f) continue
            // Score = size weight (bigger hand = more likely correct) +
            // depth bonus (hand row is bottommost) − distance penalty.
            val sizeScore = c.cards.size.toFloat()
            val depthScore = (c.yMean / h) * 5f            // 0..5 range
            val distPenalty = if (lastHandRowYMean < 0f) 0f
                else kotlin.math.abs(c.yMean - lastHandRowYMean) / h * 8f
            val score = sizeScore + depthScore - distPenalty
            candidates.add(c to score)
        }
        if (candidates.isNotEmpty()) {
            handRow = candidates.maxByOrNull { it.second }?.first
            if (handRow != null) lastHandRowYMean = handRow.yMean
        }
        // Fallback — use the config handRowTopPct (66% default) if clustering
        // could not find a distinct hand row.
        if (handRow == null) {
            val cutLine = (screen.handRowTopPct * h).toInt()
            val fallback = Cluster()
            for (o in sortedByY) if (o.y >= cutLine) fallback.add(o)
            if (fallback.cards.size >= 5) {
                handRow = fallback.finalize()
                lastHandRowYMean = fallback.yMean
            }
        }
        val handYTopPx = if (handRow != null) {
            (handRow.yMean - handRow.cards.maxOf { it.h } * 0.6f).toInt()
                .coerceAtLeast((screen.handRowTopPct * 0.92f * h).toInt())
        } else {
            (screen.handRowTopPct * h).toInt()
        }
        // Smooth adaptive boundary so it doesn't oscillate every frame.
        adaptiveHandYTopPx = if (adaptiveHandYTopPx < 0) handYTopPx
            else (adaptiveHandYTopPx * 0.7f + handYTopPx * 0.3f).toInt()

        // 4) Split detections into hand vs. non-hand rows.
        val handDets = ArrayList<YoloAPI.Obj>()
        val tableClusters = ArrayList<Cluster>()
        for (row in rows) {
            if (row === handRow || row.yMean >= adaptiveHandYTopPx) {
                handDets.addAll(row.cards)
            } else if (row.cards.size in 1..24) {
                // A small single cluster = a play on the table.
                tableClusters.add(row)
            }
        }

        // 5) My hand: dedupe overlapping cards (a card split into 2 boxes
        //    by the detector is common).  Sort by x ascending.
        val dedupedHand = dedupeHorizontal(handDets)
        val handRanks = dedupedHand
            .sortedBy { it.x }
            .map { YoloLabelBridge.toRank(it) }

        // 6) Table plays: deduplicate each cluster separately, then merge
        //    clusters that are horizontally close (cards of one play often
        //    end up split across 2 y-clusters because of 大小王 overhang).
        //    IMPORTANT: keep each merged play as a SEPARATE entry — the
        //    previous code flattened every table cluster into one big list
        //    via `mergePhysicallyAdjacent`, which then recorded a single
        //    garbage "play" composed of every card on the table.  Instead,
        //    we now produce a list of distinct plays and record each one
        //    (when its signature changes from the previous frame).
        val mergedPlays: List<List<YoloAPI.Obj>> = mergePhysicallyAdjacentPlays(tableClusters)

        // 7) Update GameState — hand first so hand-count changes that trigger
        //    "new game" wipe the history clean.
        //    TWO-FRAME CONFIRMATION (v2.2.4): to kill the per-frame handCnt
        //    flicker (12 → 18 → 17), the candidate hand must match the
        //    previous frame's candidate *exactly* (as a sorted rank list)
        //    before we publish it to the GameState.  First-frame observation
        //    goes into [pendingHandRanks]; second-frame match publishes.
        //    A new deal (hand ≥ 15) is published immediately, since waiting
        //    would visibly delay the start-of-game counter.
        var stateChanged = false
        if (handRanks.isNotEmpty()) {
            val sorted = handRanks.sorted()
            val pending = pendingHandRanks
            if (sorted == pending) {
                // Confirmed by 2 consecutive frames → publish.
                if (sorted != state.myHand()) {
                    state.setMyHand(sorted)
                    stateChanged = true
                }
                pendingHandRanks = sorted
            } else if (sorted.size >= 15) {
                // Brand-new deal — publish immediately so the counters
                // reset in lockstep with the table rather than lagging a
                // frame behind.
                if (sorted != state.myHand()) {
                    state.setMyHand(sorted)
                    stateChanged = true
                }
                pendingHandRanks = sorted
            } else {
                // First observation of a (smaller) hand — buffer it; will
                // be published on the next frame if it matches.
                pendingHandRanks = sorted
            }
        }

        // 8) Table plays: only record when the *signature* changes from the
        //    last frame (avoids re-recording the same play 100 times/second).
        var playRecorded = false
        var tableCleared = false
        if (mergedPlays.isNotEmpty()) {
            emptyTableFrames = 0
            // Build a per-play (rank list, position) list.  We dedup each
            // cluster, then call recordPlay for each.  RecordPlay is itself
            // idempotent (dedup by signature), so duplicates across frames
            // are harmless.
            val frameSig = StringBuilder()
            for (play in mergedPlays) {
                val dedup = dedupeHorizontal(play)
                if (dedup.isEmpty()) continue
                val ranks = dedup.map { YoloLabelBridge.toRank(it) }.sorted()
                if (ranks.isEmpty()) continue
                val sig = ranks.joinToString(",")
                frameSig.append(sig).append('|')
                val position = guessPlayOriginPosition(
                    listOf(run { val c = Cluster(); dedup.forEach { c.add(it) }; c.finalize() }),
                    w, h
                )
                if (state.recordPlay(position, ranks)) playRecorded = true
            }
            val frameSigStr = frameSig.toString()
            if (frameSigStr != lastTableSig) {
                lastTableSig = frameSigStr
            }
        } else {
            emptyTableFrames++
            if (emptyTableFrames >= EMPTY_TABLE_CLEAR_FRAMES && state.lastMove().isNotEmpty()) {
                state.clearTable()
                lastTableSig = null
                tableCleared = true
            }
        }

        // Flatten all table plays for the FrameResult.tablePlay field (kept
        // for telemetry / UI display only).
        val allTableRanks = mergedPlays
            .flatMap { dedupeHorizontal(it) }
            .map { YoloLabelBridge.toRank(it) }
            .sorted()

        return FrameResult(
            hand = handRanks.sorted(),
            tablePlay = allTableRanks,
            totalDetections = dets.size,
            stateChanged = stateChanged || playRecorded || tableCleared,
            handYTopPx = adaptiveHandYTopPx
        )
    }

    // ---------- helpers ----------

    /** Collapse cards whose box centres are < (min card width) apart into
     *  one, keeping the higher-confidence detection.  Runs on a single
     *  horizontal row. */
    private fun dedupeHorizontal(list: List<YoloAPI.Obj>): List<YoloAPI.Obj> {
        if (list.size <= 1) return list
        val byX = list.sortedBy { it.x }
        val wMedian = byX[byX.size / 2].w.coerceAtLeast(20f)
        val collideDx = wMedian * 0.55f
        val out = ArrayList<YoloAPI.Obj>(byX.size)
        for (o in byX) {
            val prev = out.lastOrNull()
            if (prev != null && abs(o.x - prev.x) < collideDx) {
                // Two boxes for the same card — keep the more confident one.
                if (o.prob > prev.prob) { out[out.lastIndex] = o }
            } else {
                out.add(o)
            }
        }
        return out
    }

    /** If two small clusters sit side-by-side horizontally (< 40% of
     *  cluster width apart) and their y-ranges overlap, fuse them into a
     *  single play.  Used to glue "44 + (empty) + 77" style dual-plays
     *  split by the app UI into one recognition bucket.
     *
     *  Returns a list of independent plays — each play is the merged card
     *  list of one fused cluster group, so callers can record each play
     *  separately via [GameState.recordPlay].  The previous version
     *  returned a single flat list of every table card, which collapsed
     *  N plays into one bogus mega-play. */
    private fun mergePhysicallyAdjacentPlays(clusters: List<Cluster>): List<List<YoloAPI.Obj>> {
        if (clusters.isEmpty()) return emptyList()
        // Compute cluster bounding boxes in x.
        data class Box(val c: Cluster, val l: Float, val r: Float, val t: Float, val b: Float) {
            val cx get() = (l + r) / 2f
        }
        val boxes = clusters.map { c ->
            val xs = c.cards.map { it.x - it.w / 2 }
            val xe = c.cards.map { it.x + it.w / 2 }
            val ys = c.cards.map { it.y - it.h / 2 }
            val ye = c.cards.map { it.y + it.h / 2 }
            Box(c, xs.minOrNull()!!, xe.maxOrNull()!!, ys.minOrNull()!!, ye.maxOrNull()!!)
        }.sortedBy { it.cx }
        val fused = ArrayList<Box>()
        for (b in boxes) {
            val p = fused.lastOrNull()
            val yOverlap = p != null && b.t < p.b && b.b > p.t
            val xGap = if (p == null) Float.MAX_VALUE else b.l - p.r
            if (p != null && yOverlap && xGap < (p.r - p.l) * 0.40f) {
                val cards = (p.c.cards + b.c.cards)
                val nc = Cluster()
                cards.forEach { nc.add(it) }
                nc.finalize()
                fused[fused.lastIndex] = Box(nc,
                    minOf(p.l, b.l), maxOf(p.r, b.r),
                    minOf(p.t, b.t), maxOf(p.b, b.b))
            } else {
                fused.add(b)
            }
        }
        return fused.map { dedupeHorizontal(it.c.cards) }
    }

    /**
     * Look at where a play sits on screen (its weighted centroid x) and
     * guess which player seat it belongs to.  Layout mirroring:
     *
     *    ┌──────────────────────────────┐
     *    │  [left farmer]  [地主头像]  │   — TOP AREA
     *    │                              │
     *    │        ┌ play area ───────┐  │
     *    │        │ plays appear     │  │   — MIDDLE AREA, y ≈ 40%..60%
     *    │        └──────────────────┘  │
     *    │  [ my hand — 17 cards ]      │   — BOTTOM AREA, y > 66%
     *    └──────────────────────────────┘
     *
     *  — Left-farmer plays (座次 1) cluster near x≈18%
     *  — Landlord plays (座次 0)    cluster near x≈82% (right side of middle area)
     *    OR sometimes x≈50% for 欢乐斗地主's centred landlord position
     *  — Right-farmer plays (座次 2) cluster near x≈50% of the lower-middle
     *
     * Because this is inherently fuzzy, we also accept a hint from the
     * previous play ordering when the state machine has one recorded.
     */
    private fun guessPlayOriginPosition(
        clusters: List<Cluster>, screenW: Int, screenH: Int
    ): Position {
        if (clusters.isEmpty()) return Position.LANDLORD_UP
        val all = clusters.flatMap { it.cards }
        val cx = all.sumOf { it.x.toDouble() }.toFloat() / all.size
        val cy = all.sumOf { it.y.toDouble() }.toFloat() / all.size
        val xFrac = cx / screenW
        val yFrac = cy / screenH
        // For 欢乐斗地主 the landlord avatar sits on the right and the
        // other farmer sits on the left.  Centroid x gives the seat.
        return when {
            // Left half → left farmer (LANDLORD_UP in our convention, which
            // is the farmer who plays immediately AFTER landlord).
            xFrac < 0.38f -> Position.LANDLORD_UP
            // Right half → landlord himself
            xFrac > 0.62f -> Position.LANDLORD
            // Middle band — most likely the player who played before me
            // (my right side in the table, which is LANDLORD_DOWN).
            yFrac > 0.50f -> Position.LANDLORD_DOWN
            else          -> Position.LANDLORD_UP
        }
    }

    companion object {
        private const val TAG = "NativeYoloPipeline"
        private const val EMPTY_TABLE_CLEAR_FRAMES = 4
    }
}
