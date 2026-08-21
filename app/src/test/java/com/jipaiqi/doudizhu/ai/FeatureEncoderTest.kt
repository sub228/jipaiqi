package com.jipaiqi.doudizhu.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [FeatureEncoder] — verifies the produced tensor shapes
 * match DouZero's `env/env.py::_get_obs_*`:
 *
 *  - z_batch shape = (N, 5, 162)
 *  - x_batch shape = (N, 373) for landlord, (N, 484) for farmer
 *  - x_no_action portion is shared across all N actions (verified via
 *    the last 54-emit slot per action being unique per action).
 *
 * Dimension breakdown:
 *  - landlord: myHand(54) + other(54) + lastAction(54) + upPlayed(54) +
 *              downPlayed(54) + upLeft(17) + downLeft(17) + bomb(15) = 319
 *  - farmer:    myHand(54) + other(54) + landlordPlayed(54) + teammatePlayed(54) +
 *              lastAction(54) + lastLandlord(54) + lastTeammate(54) +
 *              landlordLeft(20) + teammateLeft(17) + bomb(15) = 430
 *  + 54 (action encoding) => 373 / 484.
 */
class FeatureEncoderTest {

    private fun sampleSnapshot(
        position: Position,
        hand: List<Int> = listOf(Card.R3, Card.R4, Card.R5),
        lastMove: List<Int> = emptyList(),
    ): InfoSetSnapshot {
        val played: Map<Position, List<Int>> = Position.values().associateWith { emptyList() }
        val lastByPos: Map<Position, List<Int>> = Position.values().associateWith { emptyList() }
        val numLeft: Map<Position, Int> = Position.values().associateWith { it.initialCards }
        return InfoSetSnapshot(
            playerPosition = position,
            playerHandCards = hand,
            otherHandCards = emptyList(),
            playedCards = played,
            lastMove = lastMove,
            lastMoveByPosition = lastByPos,
            numCardsLeft = numLeft,
            bombNum = 0,
            cardPlayActionSeq = List(15) { emptyList() },
            bottomCards = null,
        )
    }

    @Test
    fun landlordXBatchIs373PerAction() {
        val snap = sampleSnapshot(Position.LANDLORD, lastMove = emptyList())
        val encoded = FeatureEncoder.encode(snap)
        assertNotNull(encoded)
        val n = snap.legalActions.size
        assertEquals(n, encoded!!.legalActions.size)
        // z shape
        assertEquals(3, encoded.zShape.size)
        assertEquals(n.toLong(), encoded.zShape[0])
        assertEquals(5L, encoded.zShape[1])
        assertEquals(162L, encoded.zShape[2])
        assertEquals(n * 5 * 162, encoded.zBatch.size)
        // x shape
        assertEquals(2, encoded.xShape.size)
        assertEquals(n.toLong(), encoded.xShape[0])
        assertEquals(FeatureEncoder.X_LANDLORD.toLong(), encoded.xShape[1])
        assertEquals(n * FeatureEncoder.X_LANDLORD, encoded.xBatch.size)
    }

    @Test
    fun farmerXBatchIs484PerAction() {
        for (farmer in listOf(Position.LANDLORD_UP, Position.LANDLORD_DOWN)) {
            val snap = sampleSnapshot(farmer, lastMove = emptyList())
            val encoded = FeatureEncoder.encode(snap)!!
            val n = snap.legalActions.size
            assertEquals(FeatureEncoder.X_FARMER.toLong(), encoded.xShape[1])
            assertEquals(n * FeatureEncoder.X_FARMER, encoded.xBatch.size)
        }
    }

    @Test
    fun zBatchIsSameAcrossActions() {
        // The historical-actions z tensor doesn't depend on the action.
        val snap = sampleSnapshot(Position.LANDLORD)
        val encoded = FeatureEncoder.encode(snap)!!
        val n = snap.legalActions.size
        val rowSize = 5 * 162
        val firstRow = encoded.zBatch.copyOfRange(0, rowSize)
        for (i in 1 until n) {
            val otherRow = encoded.zBatch.copyOfRange(i * rowSize, (i + 1) * rowSize)
            assertTrue("z must be identical across actions", firstRow.contentEquals(otherRow))
        }
    }

    @Test
    fun xNoActionPortionIsSameAcrossActions() {
        // The first (xDim - 54) floats of each x row must be identical
        // because x_no_action doesn't depend on the action.
        val snap = sampleSnapshot(Position.LANDLORD)
        val encoded = FeatureEncoder.encode(snap)!!
        val n = snap.legalActions.size
        val xDim = FeatureEncoder.X_LANDLORD
        val noActionLen = xDim - 54
        val firstNoAction = encoded.xBatch.copyOfRange(0, noActionLen)
        for (i in 1 until n) {
            val off = i * xDim
            val otherNoAction = encoded.xBatch.copyOfRange(off, off + noActionLen)
            assertTrue(
                "x_no_action must be identical across actions (row $i)",
                firstNoAction.contentEquals(otherNoAction)
            )
        }
    }

    @Test
    fun xActionPortionMatchesCardEncoding() {
        // The last 54 floats of each x row must equal Card.cardsToArray(action).
        val snap = sampleSnapshot(Position.LANDLORD)
        val encoded = FeatureEncoder.encode(snap)!!
        val n = snap.legalActions.size
        val xDim = FeatureEncoder.X_LANDLORD
        val noActionLen = xDim - 54
        for (i in 0 until n) {
            val action = encoded.legalActions[i]
            val expectedAction = Card.cardsToArray(action)
            val off = i * xDim + noActionLen
            val actual = encoded.xBatch.copyOfRange(off, off + 54)
            assertTrue(
                "action $i (${action.joinToString(",")}) encoding mismatch",
                expectedAction.contentEquals(actual)
            )
        }
    }

    @Test
    fun encodeReturnsNullForNoLegalActions() {
        // Hand with no cards but lastMove empty -> generator returns only empty+?
        // Actually, genAllMoves() of empty hand returns empty list.
        // But legalActions(leading) returns genAllMoves() (no pass), so empty.
        val snap = sampleSnapshot(Position.LANDLORD, hand = emptyList())
        assertNull(FeatureEncoder.encode(snap))
    }

    @Test
    fun landlordDimensionsBreakdown() {
        // 54 + 54 + 54 + 54 + 54 + 17 + 17 + 15 = 319 (+ 54 action = 373)
        val expected = 54 + 54 + 54 + 54 + 54 + 17 + 17 + 15
        assertEquals(319, expected)
        assertEquals(373, expected + 54)
        assertEquals(FeatureEncoder.X_LANDLORD, expected + 54)
    }

    @Test
    fun farmerDimensionsBreakdown() {
        // 54 + 54 + 54 + 54 + 54 + 54 + 54 + 20 + 17 + 15 = 430 (+ 54 = 484)
        val expected = 54 + 54 + 54 + 54 + 54 + 54 + 54 + 20 + 17 + 15
        assertEquals(430, expected)
        assertEquals(484, expected + 54)
        assertEquals(FeatureEncoder.X_FARMER, expected + 54)
    }
}
