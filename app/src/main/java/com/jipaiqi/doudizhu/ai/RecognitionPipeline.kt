package com.jipaiqi.doudizhu.ai

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Orchestrates a single recognition pass over one captured screen frame.
 *
 * Region split (欢乐斗地主 portrait layout, 竖屏):
 *   ┌───────────────────────────────────────┐
 *   │ y < 0.14  : 地主底牌区 (3 bottom cards)  │ ← 归入地主手牌
 *   │ y 0.14-0.22: 头像行 (地主左, 农民右上)   │
 *   │ y 0.22-0.42: 出牌区 (table play)       │ ← 聚类后按整体归属
 *   │ y 0.42-0.58: 中间按钮区 (忽略)         │
 *   │ y 0.58-0.92: 我的手牌区                │ ← setMyHand
 *   │ y > 0.92  : 系统UI (忽略)              │
 *   └───────────────────────────────────────┘
 *
 * 出牌归属: 按 y 聚类成"同一手牌", 用簇的**最左卡片**x坐标归属:
 *   最左卡 x < 33%  → 地主出牌 (出牌从左侧展开)
 *   最左卡 x > 67%  → 农民上家出牌 (出牌从右侧展开)
 *   其余           → 农民下家出牌 (中间展开 = 自己)
 *
 * 自动身份 (GameState.setMyHand):
 *   手=20张 → 地主
 *   手=17张 → 农民(下)
 * 用户手动选 → 自动让位。
 */
class RecognitionPipeline(
    private val yolo: CardDetector?,
    private val ocr: CardOcr,
    private val state: GameState,
    val region: RegionConfig = RegionConfig.default(),
) {
    data class RegionConfig(
        val handYStart: Float = 0.58f,
        val tableYStart: Float = 0.22f,
        val tableYEnd: Float = 0.42f,
        val bottomCardYEnd: Float = 0.14f,
        val uiYStart: Float = 0.92f,
        /** 出牌归属: 地主出牌的最左卡x必然<33%(地主头像在左边) */
        val landlordXEnd: Float = 0.33f,
        /** 出牌归属: 农民上家出牌的最右卡x必然>67%(右上头像) */
        val farmerUpXStart: Float = 0.67f,
        val yoloConfidence: Float = 0.25f,
        val yoloWinConfidence: Float = 0.55f,
        /** 出牌聚类: y坐标差小于此比例视为同一出牌 */
        val clusterYThreshold: Float = 0.03f,
    ) {
        companion object { fun default() = RegionConfig() }
    }

    /** (position, sortedCards) → last signature per position. */
    private var lastPlayByPos: Map<Position, String> = Position.values().associateWith { "" }
    private var lastBottomSig: String? = null
    /** Last hand signature for change detection. */
    private var lastHandSig: String = ""

    suspend fun processFrame(frame: Bitmap): FrameResult {
        val h = frame.height
        val w = frame.width
        val rawCards = if (yolo != null) {
            yoloDetect(frame)
        } else {
            ocrDetect(frame)
        }
        if (rawCards.isEmpty()) return FrameResult(emptyList(), emptyList(), false)

        val cards = spatialDedup(rawCards)

        // ---- Step 1: classify each card into broad region ----------------
        val handCards = ArrayList<CardOcr.RecognizedCard>()
        val tableCards = ArrayList<CardOcr.RecognizedCard>()
        val bottomCards = ArrayList<Int>()

        for (c in cards) {
            val cy = (c.box.top + c.box.bottom) / 2f
            val yFrac = cy / h

            when {
                yFrac >= region.uiYStart -> Unit  // skip system UI
                yFrac >= region.handYStart -> handCards.add(c)
                yFrac <= region.bottomCardYEnd -> bottomCards.add(c.rank)
                yFrac in region.tableYStart..region.tableYEnd -> tableCards.add(c)
                // Everything else (mid-screen, buttons, avatars) → skip
            }
        }

        // ---- Step 2: Cluster table cards into plays ----------------------
        // A "play" is a group of cards with similar y-coordinates (same row).
        // We group by y-proximity, then attribute the whole cluster by its
        // leftmost card's x position (how Dou Dizhu fans work).
        val plays = clusterPlays(tableCards, w)

        // Attribute each cluster:
        //   leftmost.x < 33% → LANDLORD
        //   leftmost.x > 67% → LANDLORD_UP
        //   otherwise         → LANDLORD_DOWN
        val landlordPlays = ArrayList<Int>()
        val farmerUpPlays = ArrayList<Int>()
        val farmerDownPlays = ArrayList<Int>()

        for (cluster in plays) {
            if (cluster.isEmpty()) continue
            val sorted = cluster.sortedBy { (it.box.left + it.box.right) / 2f }
            val leftmost = sorted.first()
            val leftmostX = (leftmost.box.left + leftmost.box.right) / 2f / w

            when {
                leftmostX < region.landlordXEnd -> landlordPlays.addAll(cluster.map { it.rank })
                leftmostX >= region.farmerUpXStart -> farmerUpPlays.addAll(cluster.map { it.rank })
                else -> farmerDownPlays.addAll(cluster.map { it.rank })
            }
        }

        // ---- Step 3: Update my hand (triggers auto-detect) --------------
        var changed = false
        if (handCards.isNotEmpty()) {
            val sorted = handCards.map { it.rank }.sorted()
            val sig = sorted.joinToString(",")
            if (sig != lastHandSig) {
                state.setMyHand(sorted)
                lastHandSig = sig
                changed = true
                Log.d(TAG, "Hand: ${sorted.size} cards: ${sorted.joinToString(",")}")
            }
        }

        // ---- Step 4: Record bottom cards (地主底牌) --------------------
        if (bottomCards.size in 2..4) {
            val sorted = bottomCards.sorted()
            val sig = sorted.joinToString(",")
            if (sig != lastBottomSig) {
                state.setBottomCards(sorted)
                lastBottomSig = sig
                changed = true
            }
        }

        // ---- Step 5: Record plays with cluster-correct attribution ------
        data class PlayEntry(val pos: Position, val cards: List<Int>)
        val playEntries = listOf(
            PlayEntry(Position.LANDLORD, landlordPlays),
            PlayEntry(Position.LANDLORD_UP, farmerUpPlays),
            PlayEntry(Position.LANDLORD_DOWN, farmerDownPlays),
        )
        var anyPlayRecorded = false
        for (entry in playEntries) {
            if (entry.cards.isEmpty()) continue
            val sorted = entry.cards.sorted()
            val sig = sorted.joinToString(",")
            if (sig == lastPlayByPos[entry.pos]) continue
            if (state.recordPlay(entry.pos, sorted)) {
                anyPlayRecorded = true
                Log.d(TAG, "Recorded play by ${entry.pos}: ${sorted.joinToString(",")}")
            }
            lastPlayByPos += entry.pos to sig
        }

        val combined = (landlordPlays + farmerUpPlays + farmerDownPlays).sorted()
        return FrameResult(
            handCards.map { it.rank }.sorted(),
            combined,
            changed || anyPlayRecorded,
        )
    }

    /**
     * Cluster table cards into plays (same y-row = same play).
     * Returns list of clusters, each cluster is a list of cards from one play.
     */
    private fun clusterPlays(
        cards: List<CardOcr.RecognizedCard>,
        screenW: Int,
    ): List<List<CardOcr.RecognizedCard>> {
        if (cards.isEmpty()) return emptyList()
        // Sort by y coordinate (top to bottom)
        val sorted = cards.sortedBy { (it.box.top + it.box.bottom) / 2f }
        val clusters = ArrayList<ArrayList<CardOcr.RecognizedCard>>()
        var currentCluster = ArrayList<CardOcr.RecognizedCard>()
        var currentAvgY = 0f

        for (c in sorted) {
            val cy = (c.box.top + c.box.bottom) / 2f
            if (currentCluster.isEmpty()) {
                currentCluster.add(c)
                currentAvgY = cy
            } else {
                val yFracDiff = abs(cy - currentAvgY) / screenW  // normalized
                if (yFracDiff < region.clusterYThreshold || currentCluster.size == 1) {
                    currentCluster.add(c)
                    // Update running average
                    currentAvgY = currentCluster.map {
                        (it.box.top + it.box.bottom) / 2f
                    }.average().toFloat()
                } else {
                    // Check if this card is far enough from current cluster
                    // Only start new cluster if card is clearly on a different row
                    val cardHeight = c.box.height().toFloat().coerceAtLeast(1f)
                    if (abs(cy - currentAvgY) > cardHeight * 0.8f) {
                        clusters.add(currentCluster)
                        currentCluster = ArrayList()
                        currentCluster.add(c)
                        currentAvgY = cy
                    } else {
                        // Close enough to join current cluster
                        currentCluster.add(c)
                        currentAvgY = currentCluster.map {
                            (it.box.top + it.box.bottom) / 2f
                        }.average().toFloat()
                    }
                }
            }
        }
        if (currentCluster.isNotEmpty()) clusters.add(currentCluster)

        // Filter out 1-card clusters that are likely noise (not real plays)
        // unless we only have single-card plays total
        return if (clusters.size <= 1) clusters
        else clusters.filter { it.size >= 1 }  // keep all for now
    }

    /**
     * Spatial dedup: merge detections that are the same physical card.
     * IoU ≥ 0.35 with same rank → keep higher score.
     * IoU ≥ 0.50 with different rank → keep higher score (YOLO double-detect).
     */
    private fun spatialDedup(
        cards: List<CardOcr.RecognizedCard>
    ): List<CardOcr.RecognizedCard> {
        if (cards.size <= 1) return cards
        val sorted = cards.sortedByDescending { it.score }
        val keep = ArrayList<CardOcr.RecognizedCard>(sorted.size)
        val suppressed = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            keep.add(sorted[i])
            for (j in (i + 1) until sorted.size) {
                if (suppressed[j]) continue
                val iou = boxIou(sorted[i].box, sorted[j].box)
                if (sorted[i].rank == sorted[j].rank && iou >= 0.35f) {
                    suppressed[j] = true
                } else if (iou >= 0.50f) {
                    // Different rank, high overlap → suppress the weaker one
                    suppressed[j] = true
                }
            }
        }
        return keep
    }

    private fun boxIou(a: Rect, b: Rect): Float {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val bot = min(a.bottom, b.bottom)
        val iw = (r - l).coerceAtLeast(0)
        val ih = (bot - t).coerceAtLeast(0)
        val inter = iw * ih
        val areaA = a.width() * a.height()
        val areaB = b.width() * b.height()
        val union = areaA + areaB - inter
        return if (union <= 0) 0f else inter.toFloat() / union
    }

    // ---------------------------------------------------------------------
    // Detection backends
    // ---------------------------------------------------------------------

    private suspend fun yoloDetect(frame: Bitmap): List<CardOcr.RecognizedCard> {
        val dets = try {
            yolo!!.detect(frame)
        } catch (e: Exception) {
            Log.w(TAG, "YOLO detect failed: ${e.message}")
            return emptyList()
        }
        if (dets.isEmpty()) {
            Log.d(TAG, "YOLO: no detections"); return emptyList()
        }
        Log.d(TAG, "YOLO: ${dets.size} raw detections")
        val out = ArrayList<CardOcr.RecognizedCard>(dets.size)
        for (d in dets) {
            if (d.score < region.yoloConfidence) continue
            val rect = Rect(
                d.box.left.toInt(), d.box.top.toInt(),
                d.box.right.toInt(), d.box.bottom.toInt()
            )
            // OCR cross-check for high precision on ambiguous cards
            val ocrMatch = runCatching { ocr.recognizeCorner(frame, rect) }.getOrNull()
            val rank = when {
                ocrMatch != null && ocrMatch.rank == d.rank -> d.rank
                d.score >= region.yoloWinConfidence -> d.rank
                ocrMatch != null -> ocrMatch.rank
                else -> d.rank
            }
            out.add(CardOcr.RecognizedCard(rank, Card.label(rank), rect, d.score))
        }
        return out
    }

    private suspend fun ocrDetect(frame: Bitmap): List<CardOcr.RecognizedCard> {
        val all = ocr.recognizeWhole(frame)
        return all.distinctBy { it.rank to (it.box.left / 40 to it.box.top / 40) }
    }

    fun reset() {
        lastPlayByPos = Position.values().associateWith { "" }
        lastBottomSig = null
        lastHandSig = ""
    }

    data class FrameResult(
        val hand: List<Int>,
        val tablePlay: List<Int>,
        val stateChanged: Boolean,
    )

    companion object { private const val TAG = "RecogPipeline" }
}
