package com.jipaiqi.doudizhu.ai

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * ML Kit text recognition wrapper used as the OCR fallback (or
 * cross-check) for the YOLO card detector.
 *
 * ML Kit Latin bundled model recognises "3".."10", "J", "Q", "K", "A", "2"
 * plus the joker markers "BJ"/"RJ" if rendered as text on screen. On
 * modern Android the recogniser runs entirely on-device without Play
 * Services because the model is bundled inside the AAR.
 *
 * Two entry points:
 *   - [recognizeWhole] : scan the whole frame, return every recognised
 *     card rank + its bounding box (used when YOLO is unavailable).
 *   - [recognizeCorner] : OCR only the top-left corner crop of a card
 *     (used to refine YOLO detections).
 */
class CardOcr {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    data class RecognizedCard(
        val rank: Int,
        val text: String,
        val box: Rect,
        /** 0..1 confidence; OCR doesn't give one so we always supply 1f. */
        val score: Float = 1f,
    )

    /** Recognise every card-rank token in [bitmap]. */
    suspend fun recognizeWhole(bitmap: Bitmap): List<RecognizedCard> {
        val text = process(bitmap) ?: return emptyList()
        val out = ArrayList<RecognizedCard>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                for (el in line.elements) {
                    val t = el.text.trim()
                    if (t.isEmpty()) continue
                    // Element may pack multiple tokens like "JQ"; split per char.
                    if (t.length <= 1 || t.equals("10", ignoreCase = true)) {
                        Card.fromText(t)?.let {
                            out.add(RecognizedCard(it, t, el.boundingBox ?: Rect()))
                        }
                    } else {
                        for (ch in t) {
                            Card.fromText(ch.toString())?.let {
                                out.add(RecognizedCard(it, ch.toString(), el.boundingBox ?: Rect()))
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    /** OCR only the top-left corner crop of a card (ROI of [box] in [bitmap]). */
    suspend fun recognizeCorner(bitmap: Bitmap, box: Rect): RecognizedCard? {
        // Take top-left ~40% of the card as the corner ROI.
        val roiW = (box.width() * 0.45).toInt().coerceAtLeast(8)
        val roiH = (box.height() * 0.45).toInt().coerceAtLeast(8)
        val left = box.left
        val top = box.top
        val right = (left + roiW).coerceAtMost(bitmap.width)
        val bottom = (top + roiH).coerceAtMost(bitmap.height)
        if (right <= left || bottom <= top) return null
        val crop = Bitmap.createBitmap(
            bitmap, left, top, right - left, bottom - top, null, true
        )
        val text = process(crop) ?: return null
        for (el in text.textBlocks.flatMap { it.lines }.flatMap { it.elements }) {
            val t = el.text.trim()
            if (t.isEmpty()) continue
            val rank = Card.fromText(t) ?: continue
            return RecognizedCard(rank, t, el.boundingBox ?: Rect())
        }
        return null
    }

    private suspend fun process(bitmap: Bitmap): com.google.mlkit.vision.text.Text? =
        suspendCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result -> cont.resume(result) }
                .addOnFailureListener { _ -> cont.resume(null) }
        }

    fun close() = recognizer.close()
}
