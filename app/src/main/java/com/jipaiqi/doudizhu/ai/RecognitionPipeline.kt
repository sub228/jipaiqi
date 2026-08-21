package com.jipaiqi.doudizhu.ai

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import kotlin.math.abs

/**
 * Orchestrates a single recognition pass over one captured screen frame:
 * YOLO detects every card on screen → OCR cross-checks each detected
 * card's corner → detections are bucketed into the "my hand" and
 * "table play" regions → [GameState] is updated.
 *
 * Region split (configurable via [RegionConfig]):
 *   - "bottom"  : the human player's hand (used to set GameState.myHand).
 *   - "table"   : the area where the most recent play is shown (used to
 *                 call [GameState.recordPlay]).
 *
 * Deduplication: the last seen "table" play signature is cached; an
 * identical subsequent play is ignored (covers the situation where the
 * recognizer sees the same on-screen play across multiple frames).
 *
 * If YOLO is unavailable, falls back to pure OCR over the entire frame
 * and a coarse horizontal band split.
 */
class RecognitionPipeline(
    private val yolo: CardDetector?,
    private val ocr: CardOcr,
    private val state: GameState,
    val region: RegionConfig = RegionConfig.default(),
) {
    data class RegionConfig(
        /** y fraction at which "my hand" starts (0..1). */
        val handYStart: Float = 0.78f,
        /** y fraction where "table play" lives (top of band). */
        val tableYStart: Float = 0.30f,
        val tableYEnd: Float = 0.55f,
        /** Confidence required for YOLO when OCR disagrees. */
        val yoloWinConfidence: Float = 0.7f,
    ) {
        companion object {
            fun default() = RegionConfig()
        }
    }

    private var lastTableSig: String? = null

    suspend fun processFrame(frame: Bitmap): FrameResult {
        val h = frame.height
        val w = frame.width
        val cards = if (yolo != null) yoloDetect(frame) else ocrDetect(frame)
        if (cards.isEmpty()) return FrameResult(emptyList(), emptyList(), false)

        // Sort cards into regions.
        val hand = ArrayList<Int>()
        val table = ArrayList<Int>()
        for (c in cards) {
            val cy = (c.box.top + c.box.bottom) / 2f
            val yFrac = cy / h
            if (yFrac >= region.handYStart) {
                hand.add(c.rank)
            } else if (region.tableYStart <= yFrac && yFrac <= region.tableYEnd) {
                table.add(c.rank)
            }
        }

        // Update state.
        var handChanged = false
        if (hand.isNotEmpty()) {
            val sorted = hand.sorted()
            if (sorted != state.myHand()) {
                state.setMyHand(sorted)
                handChanged = true
            }
        }

        var playRecorded = false
        if (table.isNotEmpty()) {
            val sig = table.sorted().joinToString(",")
            if (sig != lastTableSig) {
                // Heuristic: assign the play to the "other" player relative
                // to me — i.e. the player whose turn it just was. For the
                // "must beat" semantics DouZero only cares about the cards
                // themselves, so this is OK.
                val player = if (state.myPosition == Position.LANDLORD)
                    Position.LANDLORD_UP else Position.LANDLORD_DOWN
                if (state.recordPlay(player, table)) playRecorded = true
                lastTableSig = sig
            }
        }
        return FrameResult(hand.sorted(), table.sorted(), playRecorded || handChanged)
    }

    private suspend fun yoloDetect(frame: Bitmap): List<CardOcr.RecognizedCard> {
        val dets = try { yolo!!.detect(frame) } catch (e: Exception) {
            Log.w(TAG, "YOLO detect failed: ${e.message}"); return emptyList()
        }
        if (dets.isEmpty()) return emptyList()
        val out = ArrayList<CardOcr.RecognizedCard>(dets.size)
        for (d in dets) {
            val rect = Rect(
                d.box.left.toInt(), d.box.top.toInt(),
                d.box.right.toInt(), d.box.bottom.toInt()
            )
            // Try OCR cross-check on the corner; fall back to YOLO's class.
            val ocrMatch = ocr.recognizeCorner(frame, rect)
            val rank = if (ocrMatch != null && ocrMatch.rank == d.rank) {
                d.rank  // both agree
            } else if (d.score >= region.yoloWinConfidence) {
                d.rank  // high-confidence YOLO wins
            } else {
                ocrMatch?.rank ?: d.rank  // OCR preferred at low confidence
            }
            out.add(CardOcr.RecognizedCard(rank, Card.label(rank), rect))
        }
        return out
    }

    private suspend fun ocrDetect(frame: Bitmap): List<CardOcr.RecognizedCard> {
        // Pure-OCR fallback: scan the whole frame.
        val all = ocr.recognizeWhole(frame)
        // Quick dedup by (rank, very rough box location).
        return all.distinctBy { it.rank to (it.box.left / 40 to it.box.top / 40) }
    }

    fun reset() {
        lastTableSig = null
    }

    data class FrameResult(
        val hand: List<Int>,
        val tablePlay: List<Int>,
        val stateChanged: Boolean,
    )

    companion object { private const val TAG = "RecognitionPipeline" }
}
