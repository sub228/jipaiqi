package com.jipaiqi.doudizhu.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [MoveDetector] and [MoveSelector]. Verifies that
 * every DouZero move type (see env/utils.py TYPE_* constants and
 * env/move_detector.py `get_move_type`) is classified correctly, and
 * that [MoveSelector.beats] follows standard Dou Dizhu rules.
 */
class MoveDetectorTest {

    private fun typeOf(cards: List<Int>) = MoveDetector.getMoveType(cards)

    @Test
    fun emptyMoveIsPass() {
        assertEquals(MoveType.PASS, typeOf(emptyList()).type)
    }

    @Test
    fun singleCardClassification() {
        for (rank in listOf(Card.R3, Card.R5, Card.RJ, Card.R2, Card.BJ, Card.RJOKER)) {
            val info = typeOf(listOf(rank))
            assertEquals(MoveType.SINGLE, info.type)
            assertEquals(rank, info.rank)
        }
    }

    @Test
    fun pairClassification() {
        for (rank in listOf(Card.R3, Card.R7, Card.RA, Card.R2)) {
            val info = typeOf(listOf(rank, rank))
            assertEquals("rank $rank", MoveType.PAIR, info.type)
            assertEquals(rank, info.rank)
        }
    }

    @Test
    fun kingBombClassification() {
        val info = typeOf(listOf(Card.BJ, Card.RJOKER))
        assertEquals(MoveType.KING_BOMB, info.type)
    }

    @Test
    fun tripleClassification() {
        val info = typeOf(listOf(Card.R5, Card.R5, Card.R5))
        assertEquals(MoveType.TRIPLE, info.type)
        assertEquals(Card.R5, info.rank)
    }

    @Test
    fun bombClassification() {
        val info = typeOf(listOf(Card.R7, Card.R7, Card.R7, Card.R7))
        assertEquals(MoveType.BOMB, info.type)
        assertEquals(Card.R7, info.rank)
    }

    @Test
    fun tripleWithOneClassification() {
        // 5,5,5,7 (any order)
        val info = typeOf(listOf(Card.R5, Card.R5, Card.R5, Card.R7))
        assertEquals(MoveType.TRIPLE_WITH_ONE, info.type)
        assertEquals(Card.R5, info.rank)
    }

    @Test
    fun tripleWithPairClassification() {
        val info = typeOf(listOf(Card.R5, Card.R5, Card.R5, Card.R7, Card.R7))
        assertEquals(MoveType.TRIPLE_WITH_PAIR, info.type)
        assertEquals(Card.R5, info.rank)
    }

    @Test
    fun serialSingleClassification() {
        // 3,4,5,6,7
        val info = typeOf(listOf(Card.R3, Card.R4, Card.R5, Card.R6, Card.R7))
        assertEquals(MoveType.SERIAL_SINGLE, info.type)
        assertEquals(Card.R3, info.rank)
        assertEquals(5, info.len)
    }

    @Test
    fun serialPairClassification() {
        // 3,3,4,4,5,5
        val info = typeOf(listOf(Card.R3, Card.R3, Card.R4, Card.R4, Card.R5, Card.R5))
        assertEquals(MoveType.SERIAL_PAIR, info.type)
        assertEquals(Card.R3, info.rank)
        assertEquals(3, info.len)
    }

    @Test
    fun serialTripleClassification() {
        // 3,3,3,4,4,4
        val info = typeOf(listOf(Card.R3, Card.R3, Card.R3, Card.R4, Card.R4, Card.R4))
        assertEquals(MoveType.SERIAL_TRIPLE, info.type)
        assertEquals(Card.R3, info.rank)
        assertEquals(2, info.len)
    }

    @Test
    fun serialTripleWithSingleClassification() {
        // 3,3,3,4,4,4,7,8
        val info = typeOf(listOf(
            Card.R3, Card.R3, Card.R3,
            Card.R4, Card.R4, Card.R4,
            Card.R7, Card.R8
        ))
        assertEquals(MoveType.SERIAL_3_1, info.type)
        assertEquals(Card.R3, info.rank)
        assertEquals(2, info.len)
    }

    @Test
    fun serialTripleWithPairClassification() {
        // 3,3,3,4,4,4,7,7,8,8
        val info = typeOf(listOf(
            Card.R3, Card.R3, Card.R3,
            Card.R4, Card.R4, Card.R4,
            Card.R7, Card.R7, Card.R8, Card.R8
        ))
        assertEquals(MoveType.SERIAL_3_2, info.type)
        assertEquals(Card.R3, info.rank)
        assertEquals(2, info.len)
    }

    @Test
    fun bombWithTwoSinglesClassification() {
        // 5,5,5,5,3,7
        val info = typeOf(listOf(
            Card.R5, Card.R5, Card.R5, Card.R5, Card.R3, Card.R7
        ))
        assertEquals(MoveType.BOMB_WITH_TWO, info.type)
        assertEquals(Card.R5, info.rank)
    }

    @Test
    fun bombWithTwoPairsClassification() {
        // 5,5,5,5,3,3,7,7
        val info = typeOf(listOf(
            Card.R5, Card.R5, Card.R5, Card.R5,
            Card.R3, Card.R3, Card.R7, Card.R7
        ))
        assertEquals(MoveType.BOMB_WITH_TWO_PAIRS, info.type)
        assertEquals(Card.R5, info.rank)
    }

    @Test
    fun bombWithTwoPairsTakesMaxFourCountRank() {
        // DouZero: rank = max of the 4-count cards.
        // Two bombs -> 8 cards: 3,3,3,3 + 7,7,7,7 -> rank should be 7.
        val info = typeOf(listOf(
            Card.R3, Card.R3, Card.R3, Card.R3,
            Card.R7, Card.R7, Card.R7, Card.R7
        ))
        assertEquals(MoveType.BOMB_WITH_TWO_PAIRS, info.type)
        assertEquals(Card.R7, info.rank)
    }

    @Test
    fun isContinuousSeqWorks() {
        assertTrue(MoveDetector.isContinuousSeq(listOf(3, 4, 5)))
        assertTrue(MoveDetector.isContinuousSeq(listOf(3, 4, 5, 6, 7)))
        assertTrue(MoveDetector.isContinuousSeq(listOf(11, 12, 13, 14)))
        assertFalse(MoveDetector.isContinuousSeq(listOf(3, 5, 7)))
        assertFalse(MoveDetector.isContinuousSeq(listOf(3, 4, 6)))
        // Edge cases
        assertTrue(MoveDetector.isContinuousSeq(listOf(5)))
        assertTrue(MoveDetector.isContinuousSeq(emptyList()))
    }
}

class MoveSelectorTest {

    @Test
    fun higherSingleBeatsLower() {
        assertTrue(MoveSelector.beats(listOf(Card.R5), listOf(Card.R3)))
        assertFalse(MoveSelector.beats(listOf(Card.R3), listOf(Card.R5)))
    }

    @Test
    fun sameRankSameTypeDoesNotBeat() {
        assertFalse(MoveSelector.beats(listOf(Card.R5), listOf(Card.R5)))
    }

    @Test
    fun bombBeatsNonBomb() {
        assertTrue(MoveSelector.beats(
            listOf(Card.R5, Card.R5, Card.R5, Card.R5),
            listOf(Card.R3, Card.R4, Card.R5, Card.R6, Card.R7)
        ))
    }

    @Test
    fun higherBombBeatsLowerBomb() {
        val low = listOf(Card.R5, Card.R5, Card.R5, Card.R5)
        val high = listOf(Card.R7, Card.R7, Card.R7, Card.R7)
        assertTrue(MoveSelector.beats(high, low))
        assertFalse(MoveSelector.beats(low, high))
    }

    @Test
    fun kingBombBeatsBomb() {
        val king = listOf(Card.BJ, Card.RJOKER)
        val bomb = listOf(Card.R5, Card.R5, Card.R5, Card.R5)
        assertTrue(MoveSelector.beats(king, bomb))
        assertFalse(MoveSelector.beats(bomb, king))
    }

    @Test
    fun differentTypeOfSameSizeDoesNotBeat() {
        // Pair of 7 cannot beat a pair of 7 played differently (same rank).
        // Triple cannot beat pair.
        assertFalse(MoveSelector.beats(
            listOf(Card.R5, Card.R5, Card.R5),
            listOf(Card.R3, Card.R3)
        ))
    }

    @Test
    fun longerSerialDoesNotBeatShorter() {
        // Length must match for a serial to beat a serial.
        val short = listOf(Card.R3, Card.R4, Card.R5, Card.R6, Card.R7)
        val long = listOf(Card.R3, Card.R4, Card.R5, Card.R6, Card.R7, Card.R8)
        assertFalse(MoveSelector.beats(long, short))
    }

    @Test
    fun higherSerialBeatsLower() {
        val low = listOf(Card.R3, Card.R4, Card.R5, Card.R6, Card.R7)
        val high = listOf(Card.R4, Card.R5, Card.R6, Card.R7, Card.R8)
        assertTrue(MoveSelector.beats(high, low))
    }

    @Test
    fun filterBeatingRemovesNonBeatingMoves() {
        val rival = listOf(Card.R5)  // single 5
        val candidates = listOf(
            listOf(Card.R3), listOf(Card.R4), listOf(Card.R6), listOf(Card.R7),
            listOf(Card.R5, Card.R5),  // pair, doesn't beat single
        )
        val beaten = MoveSelector.filterBeating(candidates, rival)
        assertEquals(2, beaten.size)  // R6, R7
    }
}
