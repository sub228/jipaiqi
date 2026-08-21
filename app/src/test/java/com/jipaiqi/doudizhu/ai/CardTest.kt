package com.jipaiqi.doudizhu.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for [Card] encoding helpers. Verifies that the 54-dim
 * binary feature layout matches DouZero `_cards2array` in env/env.py:
 *
 *   - 4x13 matrix in column-major ("Fortran") order
 *   - 2 trailing joker bits (BJ at index 52, RJ at index 53)
 *   - empty list -> all zeros
 */
class CardTest {

    @Test
    fun emptyCardsProduceZeroArray() {
        val arr = Card.cardsToArray(emptyList())
        assertEquals(54, arr.size)
        for (v in arr) assertEquals(0f, v, 0f)
    }

    @Test
    fun singleThreeIsAtColumn0Row0() {
        // _cards2array([3]) -> matrix[0,0] = 1, all else zero, jokers=0
        val arr = Card.cardsToArray(listOf(Card.R3))
        assertEquals(1f, arr[0], 0f)
        for (i in 1 until 52) assertEquals(0f, arr[i], 0f)
        assertEquals(0f, arr[52], 0f)
        assertEquals(0f, arr[53], 0f)
    }

    @Test
    fun fourOfARankFillsAllFourRowsInColumn() {
        // 4 copies of rank 3 -> column 0 has [1,1,1,1] at indices 0..3
        val arr = Card.cardsToArray(listOf(Card.R3, Card.R3, Card.R3, Card.R3))
        for (row in 0 until 4) assertEquals(1f, arr[row], 0f)
        for (i in 4 until 54) assertEquals(0f, arr[i], 0f)
    }

    @Test
    fun ranksMapToExpectedColumns() {
        // Rank N maps to column N-3 (3->col0, 4->col1, ..., A(14)->col11, 2(17)->col12)
        for ((rank, col) in Card.COLUMN) {
            val arr = Card.cardsToArray(listOf(rank))
            val expectedIdx = col * 4 + 0  // first row of that column
            assertEquals("rank $rank col $col", 1f, arr[expectedIdx], 0f)
            // Spot-check one off-column index is zero.
            if (col < 12) assertEquals(0f, arr[expectedIdx + 4], 0f)
        }
    }

    @Test
    fun jokersAreInTrailingBits() {
        val arr = Card.cardsToArray(listOf(Card.BJ, Card.RJOKER))
        for (i in 0 until 52) assertEquals(0f, arr[i], 0f)
        assertEquals(1f, arr[52], 0f)  // BJ -> index 52
        assertEquals(1f, arr[53], 0f)  // RJ -> index 53
    }

    @Test
    fun mixedHandEncodesColumnMajor() {
        // Hand: 3,3,5,J,A,BJ -> expected layout
        val hand = listOf(Card.R3, Card.R3, Card.R5, Card.RJ, Card.RA, Card.BJ)
        val arr = Card.cardsToArray(hand)
        // Build the expected column-major array.
        val expected = FloatArray(54)
        // col 0 (R3) row 0,1 -> indices 0,1
        expected[0] = 1f; expected[1] = 1f
        // col 2 (R5) row 0 -> index 8 (col2*4 + 0)
        expected[8] = 1f
        // col 8 (RJ) row 0 -> index 32
        expected[32] = 1f
        // col 11 (RA) row 0 -> index 44
        expected[44] = 1f
        // BJ -> index 52
        expected[52] = 1f
        assertArrayEquals(expected, arr, 0f)
    }

    @Test
    fun fromTextDecodesAsciiAndChinese() {
        assertEquals(Card.R3, Card.fromText("3"))
        assertEquals(Card.R10, Card.fromText("10"))
        assertEquals(Card.R10, Card.fromText("1O"))   // OCR fallback
        assertEquals(Card.RJ, Card.fromText("J"))
        assertEquals(Card.RJ, Card.fromText("j"))
        assertEquals(Card.R2, Card.fromText("2"))
        assertEquals(Card.BJ, Card.fromText("小王"))
        assertEquals(Card.BJ, Card.fromText("BJ"))
        assertEquals(Card.RJOKER, Card.fromText("大王"))
        assertEquals(Card.RJOKER, Card.fromText("RJ"))
        assertNull(Card.fromText("xx"))
    }

    @Test
    fun labelsMatchDouzeroDisplay() {
        assertEquals("3", Card.label(Card.R3))
        assertEquals("10", Card.label(Card.R10))
        assertEquals("J", Card.label(Card.RJ))
        assertEquals("Q", Card.label(Card.RQ))
        assertEquals("K", Card.label(Card.RK))
        assertEquals("A", Card.label(Card.RA))
        assertEquals("2", Card.label(Card.R2))
        assertEquals("BJ", Card.label(Card.BJ))
        assertEquals("RJ", Card.label(Card.RJOKER))
        // Chinese aliases for jokers.
        assertEquals("小王", Card.labelCn(Card.BJ))
        assertEquals("大王", Card.labelCn(Card.RJOKER))
        assertEquals("5", Card.labelCn(Card.R5))
    }

    @Test
    fun totalDeckSumsToFiftyFour() {
        var total = 0
        for (rank in Card.ALL_RANKS) total += Card.TOTAL[rank]!!
        assertEquals(Card.TOTAL_CARDS, total)
    }

    @Test
    fun allRanksAreSortedLowToHigh() {
        val ranks = Card.ALL_RANKS
        for (i in 1 until ranks.size) {
            assert(ranks[i] > ranks[i - 1]) { "rank $i not sorted" }
        }
    }
}
