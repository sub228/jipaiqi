package com.jipaiqi.doudizhu.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

/**
 * YOLOv8-family card detector running through ONNX Runtime.
 *
 * Pipeline (per captured frame):
 *   1. Letterbox-resize the bitmap to [inputSize]×[inputSize].
 *   2. Normalize to [0,1], arrange as NCHW float buffer.
 *   3. Run the ONNX session — output shape (1, 4+nc, num_anchors).
 *   4. Decode boxes (cx, cy, w, h) + class scores per anchor.
 *   5. NMS to dedupe overlapping boxes.
 *   6. Map class index → [Card] rank via [classToRank].
 *
 * If no `models/yolo_cards.onnx` is shipped in assets, [Detector.isLoaded]
 * returns false and the recognition pipeline falls back to pure OCR.
 *
 * Class index ordering convention (defined in [classToRank], editable):
 *   0:3 1:4 2:5 ... 12:2 13:BJ 14:RJ
 */
class CardDetector private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
    private val outputName: String,
    private val inputSize: Int,
    private val classToRank: Map<Int, Int>,
    private val confThreshold: Float,
    private val iouThreshold: Float,
) {
    data class Detection(
        val rank: Int,
        val score: Float,
        /** Bounding box in the *original* bitmap coordinates. */
        val box: RectF,
    )

    val isLoaded: Boolean get() = true

    /**
     * Run inference on [frame] and return ranked detections.
     */
    fun detect(frame: Bitmap): List<Detection> {
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0) return emptyList()

        // ---- Preprocess: letterbox resize + NCHW normalization ----
        val (resized, scale, padW, padH) = letterbox(frame, inputSize)
        val inputData = FloatBuffer.allocate(inputSize * inputSize * 3)
        val pixels = IntArray(inputSize * inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        // NCHW layout
        val channelStride = inputSize * inputSize
        for (c in 0 until 3) { // R, G, B
            val shift = when (c) { 0 -> 16; 1 -> 8; else -> 0 } // extract channel
            for (i in pixels.indices) {
                val v = ((pixels[i] shr shift) and 0xFF) / 255f
                inputData.put(channelStride * c + i, v)
            }
        }

        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        val inputTensor = OnnxTensor.createTensor(env, inputData, shape)
        return try {
            val out = session.run(mapOf(inputName to inputTensor))
            val raw = decodeYoloOutput(out[0].value)
            out.close()
            // raw: list of (cx, cy, w, h, scores[nc])
            val boxes = postprocess(raw, inputSize, scale, padW, padH, w, h)
            boxes
        } finally {
            inputTensor.close()
        }
    }

    fun close() {
        runCatching { session.close() }
        runCatching { env.close() }
    }

    // ---- Helpers ------------------------------------------------------

    /**
     * Resize [src] to (size×size) preserving aspect ratio, padding the
     * remainder with 114 gray. Returns the resized bitmap plus the scale
     * factor and (padW, padH) used to map boxes back to the source frame.
     */
    private fun letterbox(src: Bitmap, size: Int): LetterboxResult {
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        val scale = minOf(size / sw, size / sh)
        val newW = (sw * scale).toInt().coerceAtLeast(1)
        val newH = (sh * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.rgb(114, 114, 114))
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        val rect = android.graphics.Rect(0, 0, newW, newH)
        canvas.drawBitmap(src, null, rect, paint)
        val padW = (size - newW) / 2
        val padH = (size - newH) / 2
        return LetterboxResult(out, scale, padW.toFloat(), padH.toFloat())
    }

    private data class LetterboxResult(
        val bitmap: Bitmap, val scale: Float, val padW: Float, val padH: Float
    )

    /**
     * Decode the YOLOv8 ONNX output. The output shape is (1, 4+nc, anchors):
     * dimension 1 indexes "channels" (box coords + class scores), dimension
     * 2 indexes anchors. OnnxRuntime-Java represents this 3-D float32
     * tensor as `Array<Array<FloatArray>>` (= Array<Array<float[]>>), where
     * the innermost `FloatArray` is the anchor dimension.
     *
     * We transpose it into the more convenient per-anchor layout
     * `List<FloatArray>` of length `channels`.
     */
    private fun decodeYoloOutput(raw: Any?): List<FloatArray> {
        // raw is Array<Array<FloatArray>> of shape [1][4+nc][anchors].
        val outer = raw as? Array<*> ?: return emptyList()
        if (outer.isEmpty()) return emptyList()
        val channels = outer[0] as? Array<*> ?: return emptyList()
        if (channels.isEmpty()) return emptyList()
        // Each channel entry is a FloatArray (primitive) of `anchors` floats.
        val firstChannel = channels[0]
        val rows = when (firstChannel) {
            is FloatArray -> firstChannel.size
            is Array<*> -> firstChannel.size
            else -> return emptyList()
        }
        if (rows == 0) return emptyList()
        val out = ArrayList<FloatArray>(rows)
        for (r in 0 until rows) {
            val row = FloatArray(channels.size)
            for (c in 0 until channels.size) {
                row[c] = when (val ch = channels[c]) {
                    is FloatArray -> ch[r]
                    is Array<*> -> (ch[r] as Number).toFloat()
                    else -> 0f
                }
            }
            out.add(row)
        }
        return out
    }

    private fun postprocess(
        rows: List<FloatArray>,
        inputSize: Int,
        scale: Float,
        padW: Float,
        padH: Float,
        origW: Int,
        origH: Int,
    ): List<Detection> {
        val candidates = ArrayList<Detection>()
        val nc = classToRank.size
        for (row in rows) {
            // row = [cx, cy, w, h, scores...]
            if (row.size < 4 + nc) continue
            var bestClass = -1
            var bestScore = confThreshold
            for (c in 0 until nc) {
                val s = row[4 + c]
                if (s > bestScore) { bestScore = s; bestClass = c }
            }
            if (bestClass < 0) continue
            val cx = row[0]
            val cy = row[1]
            val bw = row[2]
            val bh = row[3]
            // Letterbox -> original frame coords
            val x1 = ((cx - bw / 2f) - padW) / scale
            val y1 = ((cy - bh / 2f) - padH) / scale
            val x2 = ((cx + bw / 2f) - padW) / scale
            val y2 = ((cy + bh / 2f) - padH) / scale
            // Clamp to original frame
            val l = x1.coerceIn(0f, origW.toFloat())
            val t = y1.coerceIn(0f, origH.toFloat())
            val r = x2.coerceIn(0f, origW.toFloat())
            val b = y2.coerceIn(0f, origH.toFloat())
            val rank = classToRank[bestClass] ?: continue
            candidates.add(Detection(rank, bestScore, RectF(l, t, r, b)))
        }
        return nms(candidates)
    }

    /** Greedy non-maximum suppression by IoU. */
    private fun nms(dets: List<Detection>): List<Detection> {
        val sorted = dets.sortedByDescending { it.score }
        val keep = ArrayList<Detection>()
        val suppressed = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            keep.add(sorted[i])
            for (j in (i + 1) until sorted.size) {
                if (suppressed[j]) continue
                if (iou(sorted[i].box, sorted[j].box) > iouThreshold) {
                    suppressed[j] = true
                }
            }
        }
        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val l = maxOf(a.left, b.left)
        val t = maxOf(a.top, b.top)
        val r = minOf(a.right, b.right)
        val btm = minOf(a.bottom, b.bottom)
        val interW = (r - l).coerceAtLeast(0f)
        val interH = (btm - t).coerceAtLeast(0f)
        val inter = interW * interH
        val areaA = a.width() * a.height()
        val areaB = b.width() * b.height()
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    companion object {
        private const val TAG = "CardDetector"

        /**
         * Default YOLO class index → DouZero rank mapping.
         * Order: 3,4,5,6,7,8,9,10,J,Q,K,A,2,BJ,RJ
         */
        val DEFAULT_CLASS_TO_RANK: Map<Int, Int> = linkedMapOf(
            0 to Card.R3, 1 to Card.R4, 2 to Card.R5, 3 to Card.R6,
            4 to Card.R7, 5 to Card.R8, 6 to Card.R9, 7 to Card.R10,
            8 to Card.RJ, 9 to Card.RQ, 10 to Card.RK, 11 to Card.RA,
            12 to Card.R2, 13 to Card.BJ, 14 to Card.RJOKER
        )

        /**
         * Load `models/yolo_cards.onnx` from the app's assets directory.
         * Returns null if the file is missing or loading fails.
         */
        fun load(
            context: Context,
            inputSize: Int = 640,
            classToRank: Map<Int, Int> = DEFAULT_CLASS_TO_RANK,
            confThreshold: Float = 0.45f,
            iouThreshold: Float = 0.5f,
        ): CardDetector? {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            val assetName = "models/yolo_cards.onnx"
            return try {
                val modelDir = File(context.cacheDir, "yolo_models").apply { mkdirs() }
                val target = File(modelDir, "yolo_cards.onnx")
                context.assets.open(assetName).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                val session = env.createSession(target.absolutePath, opts)
                val inputName = session.inputNames.firstOrNull() ?: "images"
                val outputName = session.outputNames.firstOrNull() ?: "output0"
                CardDetector(
                    env, session, inputName, outputName,
                    inputSize, classToRank, confThreshold, iouThreshold,
                ).also {
                    Log.i(TAG, "Loaded YOLO card detector: $inputName -> $outputName")
                }
            } catch (e: Exception) {
                Log.w(TAG, "YOLO model not loaded; OCR-only mode. Reason: ${e.message}")
                null
            }
        }
    }
}
