package com.jipaiqi.doudizhu.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.util.EnumSet

/**
 * Loads the three converted DouZero ONNX models (landlord / landlord_up /
 * landlord_down) and runs inference to recommend the best play for the
 * current [GameState] snapshot.
 *
 * The models are converted from the official DouZero .ckpt checkpoints via
 * `tools/convert_douzero_to_onnx.py`. Each ONNX model expects:
 *
 *     inputs:
 *       z : float32[N, 5, 162]    (LSTM history input; N = #legal actions)
 *       x : float32[N, 373|484]    (per-action features; 373 for landlord,
 *                                   484 for farmer)
 *     output:
 *       values : float32[N, 1]     (per-action predicted value; higher=better)
 *
 * The engine picks argmax(values) → best legal action. If no model file is
 * present for the active position, [recommend] returns null and the caller
 * falls back to a heuristic (smallest single) — see [heuristicBestSingle].
 */
class DouZeroEngine private constructor(
    private val env: OrtEnvironment,
    private val sessions: Map<Position, OrtSession>,
    private val inputZ: String,
    private val inputX: String,
    private val outputName: String,
) {
    /** Whether a usable ONNX model exists for [position]. */
    fun hasModel(position: Position): Boolean = sessions.containsKey(position)

    /**
     * Recommend the best play for [snapshot]. Returns null if no model is
     * available for the snapshot's player position, or if there's only one
     * legal action (in which case the caller already knows it).
     */
    fun recommend(snapshot: InfoSetSnapshot): Recommendation? {
        val session = sessions[snapshot.playerPosition]
            ?: return null
        val encoded = FeatureEncoder.encode(snapshot) ?: return null
        val n = encoded.legalActions.size
        if (n == 1) {
            return Recommendation(
                action = encoded.legalActions[0],
                actionIndex = 0,
                value = Float.NaN,
                allValues = FloatArray(0),
                source = Source.MODEL,
            )
        }

        val zBuffer = FloatBuffer.wrap(encoded.zBatch)
        val xBuffer = FloatBuffer.wrap(encoded.xBatch)
        val zTensor = OnnxTensor.createTensor(
            env, zBuffer, encoded.zShape
        )
        val xTensor = OnnxTensor.createTensor(
            env, xBuffer, encoded.xShape
        )

        return try {
            val inputs = mapOf(inputZ to zTensor, inputX to xTensor)
            val output = session.run(inputs).use { it.get(0).value as FloatArray }
            val bestIdx = argmax(output)
            Recommendation(
                action = encoded.legalActions[bestIdx],
                actionIndex = bestIdx,
                value = output[bestIdx],
                allValues = output,
                source = Source.MODEL,
            )
        } finally {
            zTensor.close()
            xTensor.close()
        }
    }

    fun close() {
        sessions.values.forEach { runCatching { it.close() } }
        runCatching { env.close() }
    }

    private fun argmax(arr: FloatArray): Int {
        var best = 0
        var bestV = arr[0]
        for (i in 1 until arr.size) {
            if (arr[i] > bestV) { best = i; bestV = arr[i] }
        }
        return best
    }

    enum class Source { MODEL, HEURISTIC, PASS }

    data class Recommendation(
        val action: List<Int>,
        val actionIndex: Int,
        val value: Float,
        val allValues: FloatArray,
        val source: Source,
    )

    companion object {
        private const val TAG = "DouZeroEngine"

        /**
         * Load models from the app's `assets/models/` directory. Models are
         * expected to be named:
         *   - landlord.onnx
         *   - landlord_up.onnx
         *   - landlord_down.onnx
         * Missing files are silently skipped (a partial engine is returned).
         */
        fun load(context: Context): DouZeroEngine {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                // Use NNAPI for arm64 (Android Neural API). Falls back to CPU
                // on devices without a NNAPI-capable accelerator.
                try {
                    addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                } catch (e: Exception) {
                    Log.w(TAG, "NNAPI init failed, using CPU: ${e.message}")
                }
            }

            val sessions = HashMap<Position, OrtSession>()
            val modelDir = File(context.cacheDir, "douzero_models").apply { mkdirs() }
            val inputNames = HashMap<Position, Pair<String, String>>()

            for ((p, name) in mapOf(
                Position.LANDLORD to "landlord.onnx",
                Position.LANDLORD_UP to "landlord_up.onnx",
                Position.LANDLORD_DOWN to "landlord_down.onnx"
            )) {
                val assetPath = "models/$name"
                try {
                    val target = File(modelDir, name)
                    context.assets.open(assetPath).use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                    val session = env.createSession(target.absolutePath, opts)
                    sessions[p] = session
                    val zName = session.inputNames.firstOrNull() ?: "z"
                    val xName = session.inputNames.drop(1).firstOrNull() ?: "x"
                    inputNames[p] = zName to xName
                    Log.i(TAG, "Loaded $name: inputs=$zName,$xName outputs=${session.outputNames}")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not load $name (AI disabled for $p): ${e.message}")
                }
            }

            // Assume all models share input/output names; default if none loaded.
            val firstInputs = inputNames.values.firstOrNull()
                ?: ("z" to "x")
            val outputName = runCatching {
                sessions.values.firstOrNull()?.outputNames?.firstOrNull() ?: "values"
            }.getOrDefault("values")

            return DouZeroEngine(env, sessions, firstInputs.first, firstInputs.second, outputName)
        }
    }
}

/**
 * Heuristic fallback when no DouZero model is available: returns the
 * smallest single card from [hand] (or pass if the player must beat a play
 * that's too strong). This is intentionally simple — the real smarts come
 * from the ONNX model.
 */
fun heuristicBestSingle(snapshot: InfoSetSnapshot): DouZeroEngine.Recommendation {
    val hand = snapshot.playerHandCards
    if (hand.isEmpty()) {
        return DouZeroEngine.Recommendation(
            action = emptyList(),
            actionIndex = 0,
            value = Float.NaN,
            allValues = FloatArray(0),
            source = DouZeroEngine.Source.PASS,
        )
    }
    val last = snapshot.lastMove
    if (last.isEmpty()) {
        // Lead: play the smallest card.
        val smallest = listOf(hand.minOf { it })
        return DouZeroEngine.Recommendation(
            action = smallest,
            actionIndex = 0,
            value = Float.NaN,
            allValues = FloatArray(0),
            source = DouZeroEngine.Source.HEURISTIC,
        )
    }
    // Find the smallest single that beats last (if it's a single).
    val rInfo = MoveDetector.getMoveType(last)
    if (rInfo.type == MoveType.SINGLE) {
        val candidate = hand.filter { it > rInfo.rank }.minOrNull()
        if (candidate != null) {
            return DouZeroEngine.Recommendation(
                action = listOf(candidate),
                actionIndex = 0,
                value = Float.NaN,
                allValues = FloatArray(0),
                source = DouZeroEngine.Source.HEURISTIC,
            )
        }
    }
    return DouZeroEngine.Recommendation(
        action = emptyList(),
        actionIndex = 0,
        value = Float.NaN,
        allValues = FloatArray(0),
        source = DouZeroEngine.Source.PASS,
    )
}
