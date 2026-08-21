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
 *   │ y < 0.15  : 地主底牌区 (3 bottom cards)  │ ← 归入地主手牌
 *   │                                           │
 *   │ y 0.18-0.24: 地主头像 (left  ≈ 10% 头像) │
 *   │ y 0.18-0.24: 农民上家头像 (right ≈ 90%)  │
 *   │                                           │
 *   │ y 0.22-0.40: 出牌显示区 (三列分玩家)     │
 *   │   x < 35%  → 地主出牌 (地主头像附近)      │
 *   │   x > 65%  → 农民上家出牌 (右上头像下)   │
 *   │   35-65%  → 农民下家出牌 (屏幕居中=我)   │
 *   │                                           │
 *   │ y > 0.58  : 我的手牌区 (bottom band)     │ ← setMyHand
 *   │                                           │
 *   │ y > 0.92  : 按钮区 (不出/提示/出牌),忽略 │
 *   └───────────────────────────────────────┘
 *
 * 出牌的归属不再用"跟 myPosition 相反"的启发式（那个会把地主牌记到农民身上！），
 * 而是用上面的 X 分栏。
 *
 * 自动身份检测（GameState.setMyHand 内）：
 *    手=20张 → 地主
 *    手=17张 → 农民(下)
 * 用户点过手动按钮 → 自动推断让位。
 */
class RecognitionPipeline(
    private val yolo: CardDetector?,
    private val ocr: CardOcr,
    private val state: GameState,
    val region: RegionConfig = RegionConfig.default(),
) {
    data class RegionConfig(
        /** y fraction where "my hand" starts. */
        val handYStart: Float = 0.58f,
        /** Table / play area vertical band. */
        val tableYStart: Float = 0.22f,
        val tableYEnd: Float = 0.40f,
        /** Top strip: contains 地主"底牌" after bidding. */
        val bottomCardYEnd: Float = 0.14f,
        /** Button area at very bottom, cards there are UI. */
        val uiYStart: Float = 0.92f,
        /** X fractions for play attribution (vertical portrait layout). */
        val landlordXEnd: Float = 0.35f,
        val farmerUpXStart: Float = 0.65f,
        /** YOLO置信度阈值。欢乐斗地主牌很大，阈值低一些可以减少漏检。 */
        val yoloConfidence: Float = 0.30f,
        /** When YOLO and OCR disagree, YOLO wins at or above this score. */
        val yoloWinConfidence: Float = 0.55f,
    ) {
        companion object { fun default() = RegionConfig() }
    }

    /** (position, sortedCards) signature dedup key for plays already recorded. */
    private var lastPlayByPos: Map<Position, String> = Position.values().associateWith { "" }

    /** Last known set of bottom cards, prevents repeated setBottomCards(). */
    private var lastBottomSig: String? = null

    suspend fun processFrame(frame: Bitmap): FrameResult {
        val h = frame.height
        val w = frame.width
        val rawCards = if (yolo != null) yoloDetect(frame) else ocrDetect(frame)
        if (rawCards.isEmpty()) return FrameResult(emptyList(), emptyList(), false)

        // Spatial dedup: cards whose boxes overlap substantially (IoU ≥ 0.35)
        // and have the same rank are almost certainly the same physical card
        // recognized twice — keep the higher score.
        val cards = spatialDedup(rawCards)

        // ---- Bucket cards into regions + play ownership -------------------
        val hand = ArrayList<CardOcr.RecognizedCard>()
        val landlordPlays = ArrayList<Int>()
        val farmerUpPlays = ArrayList<Int>()
        val farmerDownPlays = ArrayList<Int>()
        val bottomCards = ArrayList<Int>()

        for (c in cards) {
            val cx = (c.box.left + c.box.right) / 2f
            val cy = (c.box.top + c.box.bottom) / 2f
            val yFrac = cy / h
            val xFrac = cx / w

            when {
                // UI bottom strip (不出/提示/出牌) → skip
                yFrac >= region.uiYStart -> Unit
                // My hand (bottom strip)
                yFrac >= region.handYStart -> hand.add(c)
                // Top strip = 地主底牌 (3 cards shown after bidding success)
                yFrac <= region.bottomCardYEnd -> bottomCards.add(c.rank)
                // Table play area → assign by X
                yFrac in region.tableYStart..region.tableYEnd -> {
                    when {
                        xFrac < region.landlordXEnd -> landlordPlays.add(c.rank)
                        xFrac >= region.farmerUpXStart -> farmerUpPlays.add(c.rank)
                        else -> farmerDownPlays.add(c.rank)
                    }
                }
                // cards between bottomCards and table, or between table and
                // hand: ignore (avatar names, timers, etc.)
            }
        }

        // ---- Update "my hand" (triggers auto-detect of role) --------------
        var changed = false
        if (hand.isNotEmpty()) {
            val sorted = hand.map { it.rank }.sorted()
            if (sorted != state.myHand()) {
                state.setMyHand(sorted)
                changed = true
            }
        }

        // ---- Record "底牌" in landlord hand (if the 3 cards are on screen) --
        if (bottomCards.size in 2..4) {
            val sorted = bottomCards.sorted()
            val sig = sorted.joinToString(",")
            if (sig != lastBottomSig) {
                state.setBottomCards(sorted)
                lastBottomSig = sig
                changed = true
            }
        }

        // ---- Record plays with their correctly assigned owner ------------
        val plays = listOf(
            Triple(Position.LANDLORD, landlordPlays, "地主"),
            Triple(Position.LANDLORD_UP, farmerUpPlays, "上家"),
            Triple(Position.LANDLORD_DOWN, farmerDownPlays, "下家"),
        )
        var anyPlayRecorded = false
        for ((pos, cards, _) in plays) {
            if (cards.isEmpty()) continue
            val sorted = cards.sorted()
            val sig = sorted.joinToString(",")
            if (lastPlayByPos[pos] == sig) continue
            if (state.recordPlay(pos, sorted)) {
                anyPlayRecorded = true
            }
            lastPlayByPos += pos to sig
        }

        // Hand was recalculated, notify
        val combined = (landlordPlays + farmerUpPlays + farmerDownPlays).sorted()
        return FrameResult(
            hand.map { it.rank }.sorted(),
            combined,
            changed || anyPlayRecorded,
        )
    }

    /**
     * Merge detections that are the same physical card: if two boxes have
     * IoU ≥ 0.35 and the same rank, keep the one with higher score.
     *
     * Cards with different ranks at the same box are also deduped by score.
     */
    private fun spatialDedup(
        cards: List<CardOcr.RecognizedCard>
    ): List<CardOcr.RecognizedCard> {
        if (cards.size <= 1) return cards
        // Sort by score descending, then greedily suppress weaker overlappers.
        val sorted = cards.sortedByDescending { it.score }
        val keep = ArrayList<CardOcr.RecognizedCard>(sorted.size)
        val suppressed = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            keep.add(sorted[i])
            for (j in (i + 1) until sorted.size) {
                if (suppressed[j]) continue
                if (sorted[i].rank != sorted[j].rank) continue
                if (boxIou(sorted[i].box, sorted[j].box) >= 0.35f) suppressed[j] = true
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
        val dets = try { yolo!!.detect(frame) } catch (e: Exception) {
            Log.w(TAG, "YOLO detect failed: ${e.message}"); return emptyList()
        }
        if (dets.isEmpty()) return emptyList()
        val out = ArrayList<CardOcr.RecognizedCard>(dets.size)
        for (d in dets) {
            if (d.score < region.yoloConfidence) continue
            val rect = Rect(
                d.box.left.toInt(), d.box.top.toInt(),
                d.box.right.toInt(), d.box.bottom.toInt()
            )
            // OCR cross-check on the card corner.
            val ocrMatch = runCatching { ocr.recognizeCorner(frame, rect) }.getOrNull()
            val rank = if (ocrMatch != null && ocrMatch.rank == d.rank) {
                d.rank
            } else if (d.score >= region.yoloWinConfidence) {
                d.rank  // high confidence YOLO wins
            } else {
                ocrMatch?.rank ?: d.rank
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
    }

    data class FrameResult(
        val hand: List<Int>,
        val tablePlay: List<Int>,
        val stateChanged: Boolean,
    )

    companion object { private const val TAG = "RecognitionPipeline" }
}
