package com.jipaiqi.doudizhu.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [MoveGenerator]. Verifies that all DouZero move types
 * are enumerated from a hand and that [legalActions] correctly:
 *  - returns all moves when leading (rivalMove empty), WITHOUT pass,
 *  - returns only same-type-and-length moves that beat the rival,
 *  - allows bombs/king bombs to overtake any non-bomb rival,
 *  - always allows pass when there's a rival move.
 *
 * The test hand is the standard 17-card farmer starter so we get
 * realistic behavior across single/pair/triple/serial patterns.
 */
class MoveGeneratorTest {

    // 17-card farmer hand: 3,3,3,4,5,5,6,7,8,9,9,9,J,J,Q,K,A,2
    private val sampleHand = listOf(
        Card.R3, Card.R3, Card.R3, Card.R4,
        Card.R5, Card.R5, Card.R6, Card.R7, Card.R8,
        Card.R9, Card.R9, Card.R9, Card.RJ, Card.RJ,
        Card.RQ, Card.RK, Card.RA, Card.R2
    ).let { it + listOf(Card.R2) }   // ensure 2 of '2'
        .let { it + listOf(Card.BJ, Card.RJOKER) }
        .sorted()

    private fun gen(hand: List<Int>) = MoveGenerator(hand)

    @Test
    fun singleMovesIncludeEachRank() {
        val g = gen(listOf(Card.R3, Card.R5, Card.RJ, Card.R2))
        val singles = g.genType1Single().map { it[0] }.toSet()
        assertEquals(setOf(Card.R3, Card.R5, Card.RJ, Card.R2), singles)
    }

    @Test
    fun pairMovesRequireTwoCopies() {
        val g = gen(listOf(Card.R3, Card.R3, Card.R5))
        val pairs = g.genType2Pair().map { it[0] }.toSet()
        assertEquals(setOf(Card.R3), pairs)
    }

    @Test
    fun tripleMovesRequireThreeCopies() {
        val g = gen(listOf(Card.R3, Card.R3, Card.R3, Card.R5, Card.R5))
        val triples = g.genType3Triple().map { it[0] }.toSet()
        assertEquals(setOf(Card.R3), triples)
    }

    @Test
    fun bombMovesRequireFourCopies() {
        val g = gen(listOf(Card.R3, Card.R3, Card.R3, Card.R3, Card.R5, Card.R5, Card.R5))
        val bombs = g.genType4Bomb().map { it[0] }.toSet()
        assertEquals(setOf(Card.R3), bombs)
    }

    @Test
    fun kingBombRequiresBothJokers() {
        val withJokers = gen(listOf(Card.BJ, Card.RJOKER, Card.R3))
        assertEquals(1, withJokers.genType5KingBomb().size)

        val withoutJokers = gen(listOf(Card.BJ, Card.R3))
        assertEquals(0, withoutJokers.genType5KingBomb().size)
    }

    @Test
    fun tripleWithOneCombinesTripleAndDifferentSingle() {
        val g = gen(listOf(Card.R3, Card.R3, Card.R3, Card.R5, Card.R7))
        val moves = g.genType6TripleWithOne()
        // 3,3,3 with 5 or 7 -> two distinct moves
        assertEquals(2, moves.size)
        for (m in moves) {
            assertEquals(4, m.size)
            assertEquals(3, m.count { it == Card.R3 })
            assertTrue(m.contains(Card.R5) || m.contains(Card.R7))
        }
    }

    @Test
    fun tripleWithPairCombinesTripleAndDifferentPair() {
        val g = gen(listOf(Card.R3, Card.R3, Card.R3, Card.R5, Card.R5, Card.R7, Card.R7))
        val moves = g.genType7TripleWithPair()
        // 3,3,3 with 5,5 or 7,7 -> two distinct moves
        assertEquals(2, moves.size)
        for (m in moves) {
            assertEquals(5, m.size)
            assertEquals(3, m.count { it == Card.R3 })
        }
    }

    @Test
    fun serialSingleFindsAllLengthsAtLeast5() {
        // 3,4,5,6,7,8 -> 1 length-5 (3-7, 4-8) and 1 length-6 (3-8) = 3 moves
        val g = gen(listOf(Card.R3, Card.R4, Card.R5, Card.R6, Card.R7, Card.R8))
        val moves = g.genType8SerialSingle()
        assertEquals(3, moves.size)
        for (m in moves) {
            assertTrue(m.size >= 5)
            assertTrue(MoveDetector.isContinuousSeq(m))
        }
    }

    @Test
    fun serialSingleHonorsMinLengthFive() {
        // 3,4,5,6 has length 4 < 5 -> no serial single move
        val g = gen(listOf(Card.R3, Card.R4, Card.R5, Card.R6))
        assertEquals(0, g.genType8SerialSingle().size)
    }

    @Test
    fun serialPairFindsConsecutivePairs() {
        // 3,3,4,4,5,5 -> one length-3 serial pair
        val g = gen(listOf(Card.R3, Card.R3, Card.R4, Card.R4, Card.R5, Card.R5))
        val moves = g.genType9SerialPair()
        assertEquals(1, moves.size)
        assertEquals(6, moves[0].size)
    }

    @Test
    fun serialTripleFindsConsecutiveTriples() {
        val g = gen(listOf(Card.R3, Card.R3, Card.R3, Card.R4, Card.R4, Card.R4))
        val moves = g.genType10SerialTriple()
        assertEquals(1, moves.size)
        assertEquals(6, moves[0].size)
    }

    @Test
    fun bombWithTwoCombinesBombAndAnyTwoSingles() {
        // 3,3,3,3 + 5,7 -> one bomb-with-two of 3
        val g = gen(listOf(Card.R3, Card.R3, Card.R3, Card.R3, Card.R5, Card.R7))
        val moves = g.genType13BombWithTwo()
        assertEquals(1, moves.size)
        assertEquals(6, moves[0].size)
        assertEquals(4, moves[0].count { it == Card.R3 })
    }

    @Test
    fun bombWithTwoPairsCombinesBombAndTwoDistinctPairs() {
        // 3,3,3,3 + (5,5),(7,7) -> one move
        val g = gen(listOf(
            Card.R3, Card.R3, Card.R3, Card.R3,
            Card.R5, Card.R5, Card.R7, Card.R7
        ))
        val moves = g.genType14BombWithTwoPairs()
        assertEquals(1, moves.size)
        assertEquals(8, moves[0].size)
        assertEquals(4, moves[0].count { it == Card.R3 })
    }

    @Test
    fun leadingHandReturnsAllMovesWithoutPass() {
        val g = gen(listOf(Card.R3, Card.R4, Card.R5, Card.R5, Card.RJ))
        val actions = g.legalActions(emptyList())
        // All moves should be present; pass (empty list) MUST NOT be present
        // because the leader is forced to play.
        assertTrue("must contain at least one single", actions.any { it.size == 1 })
        assertFalse("pass must not be a leading action", actions.any { it.isEmpty() })
        // Distinct.
        assertEquals(actions.toSet().size, actions.size)
    }

    @Test
    fun followingMustBeatRivalOrPass() {
        // Rival: single 5
        // Hand: 3,5,7 -> can play 7 (beats 5) or pass.
        val g = gen(listOf(Card.R3, Card.R5, Card.R7))
        val actions = g.legalActions(listOf(Card.R5))
        // All actions must either be pass (empty) or beat the rival.
        for (a in actions) {
            if (a.isEmpty()) continue  // pass is legal
            assertTrue("action $a must beat rival", MoveSelector.beats(a, listOf(Card.R5)))
        }
        // Pass must always be an option when there's a rival.
        assertTrue("pass must be allowed", actions.any { it.isEmpty() })
        // 7 beats 5, 3 doesn't, 5 doesn't (equal rank).
        assertTrue("7 should be a legal action", actions.any { it == listOf(Card.R7) })
    }

    @Test
    fun followingCanAlwaysBombNonBombRival() {
        // Rival: single 5
        // Hand: 3,3,3,3 -> bomb is legal even though type differs.
        val g = gen(listOf(Card.R3, Card.R3, Card.R3, Card.R3))
        val actions = g.legalActions(listOf(Card.R5))
        assertTrue("bomb should be legal", actions.any {
            MoveDetector.getMoveType(it).type == MoveType.BOMB
        })
    }

    @Test
    fun followingHigherBombBeatsLowerBomb() {
        val g = gen(listOf(Card.R7, Card.R7, Card.R7, Card.R7))
        val rival = listOf(Card.R5, Card.R5, Card.R5, Card.R5)
        val actions = g.legalActions(rival)
        assertTrue("higher bomb should beat rival", actions.any {
            MoveDetector.getMoveType(it).type == MoveType.BOMB &&
                MoveSelector.beats(it, rival)
        })
    }

    @Test
    fun followingKingBombBeatsBomb() {
        val g = gen(listOf(Card.BJ, Card.RJOKER))
        val rival = listOf(Card.R5, Card.R5, Card.R5, Card.R5)
        val actions = g.legalActions(rival)
        assertTrue("king bomb should beat bomb", actions.any {
            MoveDetector.getMoveType(it).type == MoveType.KING_BOMB
        })
    }

    @Test
    fun legalActionsAreAllDistinct() {
        val g = gen(sampleHand)
        val rival = listOf(Card.R5)
        val actions = g.legalActions(rival)
        assertEquals(actions.toSet().size, actions.size)
    }

    @Test
    fun genAllMovesIsSupersetOfEachIndividualType() {
        val g = gen(sampleHand)
        val all = g.genAllMoves().map { it.sorted() }.toSet()
        for (move in g.genType1Single()) assertTrue(move.toString(), all.contains(move))
        for (move in g.genType2Pair()) assertTrue(move.toString(), all.contains(move))
        for (move in g.genType3Triple()) assertTrue(move.toString(), all.contains(move))
        for (move in g.genType4Bomb()) assertTrue(move.toString(), all.contains(move))
        for (move in g.genType5KingBomb()) assertTrue(move.toString(), all.contains(move))
    }
}
