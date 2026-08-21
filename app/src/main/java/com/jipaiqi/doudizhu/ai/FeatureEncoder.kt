package com.jipaiqi.doudizhu.ai

/**
 * Encodes the game state into the float tensors expected by the converted
 * DouZero ONNX models. Direct port of DouZero `env/env.py::_get_obs_*`
 * feature builders (see Tables 4 & 5 of https://arxiv.org/pdf/2106.06135.pdf).
 *
 * For each candidate legal action the encoder produces:
 *   z  : (5, 162)  — last 15 plays reshaped as 5×(3×54). Same for every action.
 *   x  : (373)     — landlord, or
 *      (484)      — farmer. The first 319 (landlord) / 430 (farmer) entries
 *                    are shared across all actions ("x_no_action"); the last
 *                    54 entries encode the candidate action itself.
 *
 * The ONNX model is then called as
 *     y = model(z_batch=(N,5,162), x_batch=(N, 373|484))   →  (N, 1)
 * where N = number of legal actions, and the best action is argmax(y).
 */
object FeatureEncoder {

    /** LSTM input shape: 5 timesteps × 162 features (= 3 plays × 54 dim). */
    const val Z_ROWS = 5
    const val Z_COLS = 162

    const val X_LANDLORD = 373
    const val X_FARMER = 484

    /** One-hot array for "this player has k cards left" (max = [maxCards]). */
    private fun oneHotCardsLeft(left: Int, maxCards: Int): FloatArray {
        val arr = FloatArray(maxCards)
        val idx = (left - 1).coerceIn(0, maxCards - 1)
        arr[idx] = 1f
        return arr
    }

    /** One-hot for the bomb count (0..14). */
    private fun oneHotBomb(bombNum: Int): FloatArray {
        val arr = FloatArray(15)
        arr[bombNum.coerceIn(0, 14)] = 1f
        return arr
    }

    /** Encode the last 15 actions as a (5, 162) matrix in row-major order. */
    private fun encodeActionSeq(seq15: List<List<Int>>): FloatArray {
        // 15 actions × 54 dim = 810 floats, grouped into 5 rows of 3×54.
        val out = FloatArray(Z_ROWS * Z_COLS) // row-major (5, 162)
        for (row in 0 until Z_ROWS) {
            // rows 0..4 correspond to actions 0..14 grouped in triples.
            for (col in 0 until 3) {
                val actionIdx = row * 3 + col
                if (actionIdx < seq15.size) {
                    val enc = Card.cardsToArray(seq15[actionIdx])
                    val dstOffset = row * Z_COLS + col * 54
                    System.arraycopy(enc, 0, out, dstOffset, 54)
                }
            }
        }
        return out
    }

    /**
     * Build the batched (z_batch, x_batch) tensors for [snapshot].
     *
     * Returns null if there are no legal actions (shouldn't happen, since
     * pass is always legal).
     */
    fun encode(snapshot: InfoSetSnapshot): EncodedObservation? {
        val legal = snapshot.legalActions
        if (legal.isEmpty()) return null
        val n = legal.size

        val z = encodeActionSeq(snapshot.cardPlayActionSeq)         // (5, 162) row-major
        val zBatch = FloatArray(n * Z_ROWS * Z_COLS)               // (n, 5, 162) row-major
        for (i in 0 until n) {
            System.arraycopy(z, 0, zBatch, i * z.size, z.size)
        }

        val xNoAction: FloatArray = when (snapshot.playerPosition) {
            Position.LANDLORD -> encodeLandlordXNoAction(snapshot)
            Position.LANDLORD_UP -> encodeFarmerXNoAction(snapshot, teammate = Position.LANDLORD_DOWN)
            Position.LANDLORD_DOWN -> encodeFarmerXNoAction(snapshot, teammate = Position.LANDLORD_UP)
        }

        val xDim = xNoAction.size + 54
        require(xDim == X_LANDLORD || xDim == X_FARMER) {
            "feature dim $xDim not in {$X_LANDLORD, $X_FARMER}"
        }
        val xBatch = FloatArray(n * xDim) // (n, xDim)
        for ((i, action) in legal.withIndex()) {
            val dstOffset = i * xDim
            System.arraycopy(xNoAction, 0, xBatch, dstOffset, xNoAction.size)
            val actionEnc = Card.cardsToArray(action)
            System.arraycopy(actionEnc, 0, xBatch, dstOffset + xNoAction.size, 54)
        }

        return EncodedObservation(
            position = snapshot.playerPosition,
            zBatch = zBatch,
            xBatch = xBatch,
            zShape = longArrayOf(n.toLong(), Z_ROWS.toLong(), Z_COLS.toLong()),
            xShape = longArrayOf(n.toLong(), xDim.toLong()),
            legalActions = legal
        )
    }

    private fun encodeLandlordXNoAction(s: InfoSetSnapshot): FloatArray {
        val myHand = Card.cardsToArray(s.playerHandCards)
        val other = Card.cardsToArray(s.otherHandCards)
        val lastAction = Card.cardsToArray(s.lastMove)
        val upPlayed = Card.cardsToArray(s.playedCards[Position.LANDLORD_UP]!!)
        val downPlayed = Card.cardsToArray(s.playedCards[Position.LANDLORD_DOWN]!!)
        val upLeft = oneHotCardsLeft(s.numCardsLeft[Position.LANDLORD_UP]!!, 17)
        val downLeft = oneHotCardsLeft(s.numCardsLeft[Position.LANDLORD_DOWN]!!, 17)
        val bomb = oneHotBomb(s.bombNum)
        return concatFloats(myHand, other, lastAction, upPlayed, downPlayed, upLeft, downLeft, bomb)
    }

    /**
     * Farmer-side x_no_action. Same shape for both farmer positions; the only
     * difference is which "teammate" position feeds the team-specific fields.
     */
    private fun encodeFarmerXNoAction(s: InfoSetSnapshot, teammate: Position): FloatArray {
        val landlord = Position.LANDLORD
        val myHand = Card.cardsToArray(s.playerHandCards)
        val other = Card.cardsToArray(s.otherHandCards)
        val landlordPlayed = Card.cardsToArray(s.playedCards[landlord]!!)
        val teammatePlayed = Card.cardsToArray(s.playedCards[teammate]!!)
        val lastAction = Card.cardsToArray(s.lastMove)
        val lastLandlord = Card.cardsToArray(s.lastMoveByPosition[landlord]!!)
        val lastTeammate = Card.cardsToArray(s.lastMoveByPosition[teammate]!!)
        val landlordLeft = oneHotCardsLeft(s.numCardsLeft[landlord]!!, 20)
        val teammateLeft = oneHotCardsLeft(s.numCardsLeft[teammate]!!, 17)
        val bomb = oneHotBomb(s.bombNum)
        return concatFloats(
            myHand, other, landlordPlayed, teammatePlayed,
            lastAction, lastLandlord, lastTeammate,
            landlordLeft, teammateLeft, bomb
        )
    }

    private fun concatFloats(vararg arrays: FloatArray): FloatArray {
        val total = arrays.sumOf { it.size }
        val out = FloatArray(total)
        var off = 0
        for (a in arrays) {
            System.arraycopy(a, 0, out, off, a.size)
            off += a.size
        }
        return out
    }
}

/** Output of [FeatureEncoder.encode], ready to be fed to [DouZeroEngine]. */
data class EncodedObservation(
    val position: Position,
    val zBatch: FloatArray,           // (n, 5, 162) row-major
    val xBatch: FloatArray,           // (n, 373|484) row-major
    val zShape: LongArray,
    val xShape: LongArray,
    val legalActions: List<List<Int>>
)
