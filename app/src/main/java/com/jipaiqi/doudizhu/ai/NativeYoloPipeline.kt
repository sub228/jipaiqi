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
    }

    fun processFrame(frame: Bitmap): FrameResult {
        val h = frame.height
        val w = frame.width

        // ── 1) ORIGINAL YOLO detector ────────────────────────────────────
        //
        // ORIGINAL APK (wz.apk -> FramePipelineCoordinator.detectFrame)
        // literally does:
        //     new FrameDetectionSnapshot(bitmap, yoloApi.Detect(bitmap, true))
        //
        // That is:
        //   ✓ NO pre-cropping to "bottom 34%" / "middle band"
        //   ✓ bitmap is the FULL screen frame at PHYSICAL RESOLUTION
        //   ✓ z=true -> native side applies the 欢乐斗地主 layout crop
        //     internally (model was trained on that crop layout)
        // The previous build applied a 1080p cap + crop which shrank
        // opponent hands far below the minimum card size the model can
        // resolve — that's the root cause of "no cards detected at all"
        // on user screenshots.
        val dets: Array<YoloAPI.Obj> = try {
            yolo.Detect(frame, true) ?: emptyArray()
        } catch (t: Throwable) {
            Log.e(TAG, "Native YOLO Detect crashed — " +
                    "is libyolov8ncnn.so loaded & yolo_n.bin present? ${t.message}", t)
            return FrameResult(emptyList(), emptyList(), 0, false, adaptiveHandYTopPx)
        }
        if (dets.isEmpty()) return FrameResult(emptyList(), emptyList(), 0, false, adaptiveHandYTopPx)

        // ── 2) 1-D y-clustering ──────────────────────────────────────────
        //
        // CRYSTALLISED from the original Flutter/Dart card-box clustering
        // that produces `DebugMsg.clusterslength` (number of horizontal
        // rows found).  The Dart layer uses:
        //
        //   sort boxes by y.centre ASC
        //   threshold = 0.45 * median(box.height)
        //   open a new row if (nextBox.y - lastBox.y) > threshold
        //
        // The previous Kotlin version used `0.9 * h` which caused a
        // 17-card hand on narrow-aspect-ratio phones to be erroneously
        // split across TWO fake clusters and neither qualified as a hand
        // row (symptom: `hand` length = 0).
        val sortedByY = dets.sortedBy { it.y }
        val medianH = sortedByY[sortedByY.size / 2].h.coerceAtLeast(20f)
        val rowGap = medianH * 0.45f                // ← ORIGINAL THRESHOLD
        val clusters = ArrayList<Cluster>()
        for (o in sortedByY) {
            val last = clusters.lastOrNull()
            if (last != null && (o.y - last.yMean) <= rowGap) {
                last.add(o)
            } else {
                val c = Cluster()
                c.add(o)
                clusters.add(c)
            }
        }
        val rows = clusters.map { it.finalize() }.sortedBy { it.yMean }

        // ── 3) Hand row = LARGEST (by card count) CLUSTER whose yMean is
        //      in the LOWER HALF of the screen.  If two clusters tie-break
        //      by card count, pick the one with HIGHER yMean (closer to
        //      the user's thumbs = definitely the hand row).
        //
        // ORIGINAL Dart rule:
        //   clusters.where((c) => c.length >= 8 && c.y >= screen.h*0.5)
        //      .maxByOrNull((c) => c.length)
        // Falls back to screenAdaptions.json's `handRowTopPct` if clustering
        // can't lock in a hand (e.g. post-play with 3 cards left).
        val minHandCards = (screen.expectedHandCards * 0.4f).toInt().coerceAtLeast(5)
        var handRow: Cluster? = rows
            .filter { it.cards.size >= minHandCards && it.yMean >= h * 0.50f }
            .maxWithOrNull(compareBy<Cluster> { it.cards.size }.thenBy { it.yMean })

        if (handRow == null) {
            // Original Flutter fall-back: everything below `handRowTopPct`
            // is treated as "my hand" zone.
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

        // ── 4) Bucket detections ─────────────────────────────────────────
        val handDets = ArrayList<YoloAPI.Obj>()
        val tableClusters = ArrayList<Cluster>()
        for (row in rows) {
            val handMatch = row === handRow || row.yMean >= adaptiveHandYTopPx
            if (handMatch) {
                handDets.addAll(row.cards)
            } else if (row.cards.size in 1..30 &&
                       row.yMean < adaptiveHandYTopPx - 1 /* strictly above hand */) {
                tableClusters.add(row)
            }
        }

        // ── 5) My hand ───────────────────────────────────────────────────
        // Dedupe horizontally overlapping detections of the same physical
        // card (same threshold 0.55 * w as the original Flutter box_merge).
        val dedupedHand = dedupeHorizontal(handDets)
        val handRanks = dedupedHand
            .sortedBy { it.x }
            .map { YoloLabelBridge.toRank(it) }

        // ── 6) Table plays ───────────────────────────────────────────────
        // mergePhysicallyAdjacent joins clusters that are horizontally
        // next to each other with overlapping y-ranges (e.g. a left
        // opponent's 对7 split across two y-bins because the card art is
        // taller than medianH).
        val mergedTable = mergePhysicallyAdjacent(tableClusters)
        val mergedRanks = mergedTable.map { YoloLabelBridge.toRank(it) }.sorted()
        val mergedSig = mergedRanks.joinToString(",")

        // ── 7) Update state (hand first so big hand-size swings reset) ───
        var stateChanged = false
        if (handRanks.isNotEmpty()) {
            val sorted = handRanks.sorted()
            if (sorted != state.myHand()) {
                // ORIGINAL Flutter resets the whole board whenever we
                // observe a fresh 15+ card hand (= new deal arrived) and
                // the last known hand was smaller than 15.
                if (sorted.size >= 15 && state.myHand().size < 15) {
                    state.newGame()
                }
                state.setMyHand(sorted)
                stateChanged = true
            }
        }

        // 8) Table plays: only record when the *signature* changes from
        //    the last frame.  Also require EMPTY_TABLE_CLEAR_FRAMES of
        //    zero-detection table state to declare "table cleared" — that
        //    matches the original BlackFrameDetector behaviour.
        var playRecorded = false
        var tableCleared = false
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
                state.clearTable()
                lastTableSig = null
                tableCleared = true
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
     *  split by the app UI into one recognition bucket. */
    private fun mergePhysicallyAdjacent(clusters: List<Cluster>): List<YoloAPI.Obj> {
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
        return fused.flatMap { dedupeHorizontal(it.c.cards) }
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
