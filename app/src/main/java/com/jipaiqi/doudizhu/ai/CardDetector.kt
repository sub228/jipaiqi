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
 * YOLOv5 card detector running through ONNX Runtime.
 *
 * Pipeline (per captured frame):
 *   1. Letterbox-resize the bitmap to [inputSize]×[inputSize].
 *   2. Normalize to [0,1], arrange as NCHW float buffer.
 *   3. Run the ONNX session — output shape (1, anchors, 5+nc).
 *   4. Decode boxes (cx, cy, w, h) + objectness + class scores per anchor.
 *   5. NMS to dedupe overlapping boxes.
 *   6. Map class index → [Card] rank via [classToRank].
 *
 * Class index ordering (from YOLOv5 model names):
 *   0:A  1:2  2:3  3:4  4:5  5:6  6:7  7:8  8:9  9:10  10:J  11:Q  12:K  13:BJ  14:RJ
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
            val rawDetections = decodeYoloV5Output(out[0].value)
            out.close()
            // rawDetections: list of (cx, cy, w, h, score, classIndex)
            val boxes = postprocess(rawDetections, inputSize, scale, padW, padH, w, h)
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
     * Decode the YOLOv5 ONNX output.
     *
     * YOLOv5 output shape: (1, anchors, 5+nc) where:
     *   - anchors = num anchor grid cells (e.g. 25200 for 640x640)
     *   - 5 = cx, cy, w, h, objectness
     *   - nc = number of class scores
     *
     * OnnxRuntime-Java represents this as a 3-D array:
     *   Array<Array<FloatArray>> = [batch][anchors][channels]
     *
     * We transpose to per-anchor rows and compute final score = obj * cls_score.
     */
    private fun decodeYoloV5Output(raw: Any?): List<FloatArray> {
        // raw is Array<Array<FloatArray>> of shape [1][anchors][5+nc]
        val outer = raw as? Array<*> ?: return emptyList()
        if (outer.isEmpty()) return emptyList()
        val anchors = outer[0] as? Array<*> ?: return emptyList()
        if (anchors.isEmpty()) return emptyList()

        val nc = classToRank.size
        val channelsPerAnchor = 5 + nc  // cx, cy, w, h, obj, cls0..cls(nc-1)

        val out = ArrayList<FloatArray>()
        for (anchorIdx in anchors.indices) {
            val channelData = anchors[anchorIdx]
            val channelArr = when (channelData) {
                is FloatArray -> channelData
                is Array<*> -> FloatArray(channelData.size) { (channelData[it] as Number).toFloat() }
                else -> continue
            }
            if (channelArr.size < channelsPerAnchor) continue

            val cx = channelArr[0]
            val cy = channelArr[1]
            val w = channelArr[2]
            val h = channelArr[3]
            val obj = channelArr[4]

            // Find best class
            var bestClass = -1
            var bestScore = 0f
            for (c in 0 until nc) {
                val clsScore = channelArr[5 + c] * obj
                if (clsScore > bestScore) {
                    bestScore = clsScore
                    bestClass = c
                }
            }
            if (bestClass < 0) continue

            // row = [cx, cy, w, h, score, classIndex]
            val row = FloatArray(6)
            row[0] = cx
            row[1] = cy
            row[2] = w
            row[3] = h
            row[4] = bestScore
            row[5] = bestClass.toFloat()
            out.add(row)
        }
        return out
    }

    /**
     * Post-process decoded detections: filter by confidence, convert
     * from letterbox coords back to original bitmap coords, apply NMS.
     */
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
        for (row in rows) {
            // row = [cx, cy, w, h, score, classIndex]
            if (row.size < 6) continue
            val score = row[4]
            if (score < confThreshold) continue

            val cx = row[0]
            val cy = row[1]
            val bw = row[2]
            val bh = row[3]
            val classIdx = row[5].toInt()

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

            val rank = classToRank[classIdx] ?: continue
            candidates.add(Detection(rank, score, RectF(l, t, r, b)))
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
         * YOLOv5 class index → DouZero rank mapping.
         * Model class names: A,2,3,4,5,6,7,8,9,T,J,Q,K,X,D
         * Where T=10, X=小王(BJ), D=大王(RJ)
         */
        val DEFAULT_CLASS_TO_RANK: Map<Int, Int> = linkedMapOf(
            0 to Card.RA,    // A
            1 to Card.R2,    // 2
            2 to Card.R3,    // 3
            3 to Card.R4,    // 4
            4 to Card.R5,    // 5
            5 to Card.R6,    // 6
            6 to Card.R7,    // 7
            7 to Card.R8,    // 8
            8 to Card.R9,    // 9
            9 to Card.R10,   // T (10)
            10 to Card.RJ,   // J
            11 to Card.RQ,   // Q
            12 to Card.RK,   // K
            13 to Card.BJ,   // X (小王/Small Joker)
            14 to Card.RJOKER // D (大王/Big Joker)
        )

        /**
         * Load `models/yolo_cards.onnx` from the app's assets directory.
         * Returns null if the file is missing or loading fails.
         */
        fun load(
            context: Context,
            inputSize: Int = 640,
            classToRank: Map<Int, Int> = DEFAULT_CLASS_TO_RANK,
            confThreshold: Float = 0.25f,
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
                val inputName = session.inputNames.firstOrNull() ?: "input"
                val outputName = session.outputNames.firstOrNull() ?: "output"
                CardDetector(
                    env, session, inputName, outputName,
                    inputSize, classToRank, confThreshold, iouThreshold,
                ).also {
                    Log.i(TAG, "Loaded YOLOv5 card detector: $inputName -> $outputName")
                }
            } catch (e: Exception) {
                Log.w(TAG, "YOLO model not loaded; OCR-only mode. Reason: ${e.message}")
                null
            }
        }
    }
}
