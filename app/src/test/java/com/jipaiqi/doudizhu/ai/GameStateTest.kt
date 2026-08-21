package com.jipaiqi.doudizhu.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [GameState] — the in-memory Dou Dizhu state machine.
 *
 * Verifies:
 *  - recordPlay is idempotent for repeated identical plays (recognizer
 *    sees the same on-screen play across frames),
 *  - recordPlay rejects impossible plays (more than 4 copies of a rank),
 *  - clearTable() resets the play-to-beat so the leader can play freely,
 *  - otherHandCards() is the deck minus my hand minus all played cards,
 *  - newGame() resets all state,
 *  - numCardsLeft() and bombNum() update correctly.
 */
class GameStateTest {

    private fun newState(): GameState = GameState().apply {
        // Default myPosition = LANDLORD, myHand = empty
        setMyHand(listOf(
            Card.R3, Card.R4, Card.R5, Card.R6, Card.R7, Card.R8,
            Card.R9, Card.R10, Card.RJ, Card.RQ, Card.RK, Card.RA,
            Card.R2, Card.BJ, Card.RJOKER, Card.R3, Card.R3
        ))
    }

    @Test
    fun initialOtherHandIsEmptyIfMineHasAll() {
        val s = GameState()
        // Give the landlord every card in the deck -> other = empty
        val fullDeck = Card.ALL_RANKS.flatMap { r ->
            List(Card.TOTAL[r]!!) { r }
        }
        s.setMyHand(fullDeck)
        assertEquals(0, s.otherHandCards().size)
    }

    @Test
    fun otherHandCardsAreRemainingOfDeck() {
        val s = GameState()
        s.setMyHand(listOf(Card.R3, Card.R3, Card.R5, Card.BJ))
        val other = s.otherHandCards()
        // Remaining 3 count: 4 total - 2 in mine = 2 in other
        assertEquals(2, other.count { it == Card.R3 })
        // Remaining 5 count: 4 total - 1 = 3
        assertEquals(3, other.count { it == Card.R5 })
        // BJ: 1 - 1 = 0
        assertEquals(0, other.count { it == Card.BJ })
    }

    @Test
    fun recordPlayIsIdempotent() {
        val s = newState()
        val pos = Position.LANDLORD_UP
        val cards = listOf(Card.R5, Card.R5)
        assertTrue(s.recordPlay(pos, cards))
        assertFalse("second identical play must be rejected", s.recordPlay(pos, cards))
    }

    @Test
    fun recordPlayUpdatesLastMoveOnTable() {
        val s = newState()
        val cards = listOf(Card.R5, Card.R5, Card.R5)
        s.recordPlay(Position.LANDLORD_UP, cards)
        assertEquals(cards, s.lastMove())
        assertEquals(cards, s.lastMoveBy(Position.LANDLORD_UP))
    }

    @Test
    fun recordPlayRejectsImpossiblePlays() {
        val s = newState()
        // Try to play 5 aces when only 4 exist in the deck.
        val impossible = listOf(Card.RA, Card.RA, Card.RA, Card.RA, Card.RA)
        assertFalse(s.recordPlay(Position.LANDLORD_UP, impossible))
    }

    @Test
    fun recordPlayRejectsCardsInMyHandOnly() {
        // If I'm holding 4 aces, opponent cannot also play aces.
        val s = GameState().apply {
            setMyHand(listOf(Card.RA, Card.RA, Card.RA, Card.RA))
        }
        // Up tries to play an ace: myHand(4) + played(1) = 5 > 4, must reject.
        assertFalse(s.recordPlay(Position.LANDLORD_UP, listOf(Card.RA)))
    }

    @Test
    fun clearTableResetsLastMove() {
        val s = newState()
        s.recordPlay(Position.LANDLORD_UP, listOf(Card.R5))
        assertEquals(listOf(Card.R5), s.lastMove())
        s.clearTable()
        assertEquals(emptyList<Int>(), s.lastMove())
    }

    @Test
    fun clearTableAllowsLeadingToPlayFreely() {
        val s = newState()
        s.recordPlay(Position.LANDLORD_UP, listOf(Card.R5))
        s.clearTable()
        // rivalMove is now empty -> legalActions must NOT contain pass
        val snap = s.toInfoSet()
        assertFalse(snap.legalActions.any { it.isEmpty() })
    }

    @Test
    fun newGameResetsAllState() {
        val s = newState()
        s.recordPlay(Position.LANDLORD_UP, listOf(Card.R5, Card.R5))
        s.recordPlay(Position.LANDLORD_DOWN, listOf(Card.R7, Card.R7, Card.R7, Card.R7))
        assertTrue(s.lastMove().isNotEmpty())
        assertTrue(s.playedBy(Position.LANDLORD_UP).isNotEmpty())

        s.newGame()
        assertTrue(s.myHand().isEmpty())
        assertTrue(s.lastMove().isEmpty())
        assertTrue(s.playedBy(Position.LANDLORD_UP).isEmpty())
        assertEquals(0, s.bombNum())
    }

    @Test
    fun bombNumCountsBombsAndKingBombs() {
        // Use an empty hand so the plays don't collide with sanity limits.
        val s = GameState()
        s.recordPlay(Position.LANDLORD_UP, listOf(Card.R5, Card.R5, Card.R5, Card.R5))
        assertEquals(1, s.bombNum())
        s.recordPlay(Position.LANDLORD_DOWN, listOf(Card.BJ, Card.RJOKER))
        assertEquals(2, s.bombNum())
    }

    @Test
    fun numCardsLeftReflectsInitialAndPlayed() {
        val s = newState()
        // Up has 17 - played (3 fives) = 14 left after one play.
        s.recordPlay(Position.LANDLORD_UP, listOf(Card.R5, Card.R5, Card.R5))
        val left = s.numCardsLeft()
        assertEquals(14, left[Position.LANDLORD_UP])
        assertEquals(17, left[Position.LANDLORD_DOWN])
        assertEquals(20, left[Position.LANDLORD])  // landlord starts with 20
    }

    @Test
    fun actionSeq15PadsTo15WithEmptyPlays() {
        val s = newState()
        val seq = s.actionSeq15()
        assertEquals(15, seq.size)
        assertTrue(seq.all { it.isEmpty() })
    }

    @Test
    fun actionSeq15KeepsLast15Actions() {
        val s = newState()
        for (rank in 3..20) {  // play 18 times
            s.recordPlay(Position.LANDLORD_UP, listOf(rank))
        }
        val seq = s.actionSeq15()
        assertEquals(15, seq.size)
        // The oldest 3 plays should be dropped, leaving 4..20.
        val nonEmpty = seq.filter { it.isNotEmpty() }
        assertEquals(15, nonEmpty.size)
        assertEquals(listOf(5), seq[0])  // first kept = rank 5
    }

    @Test
    fun isReadyRequiresNonEmptyHand() {
        val s = GameState()
        assertFalse(s.isReady())
        s.setMyHand(listOf(Card.R3))
        assertTrue(s.isReady())
    }

    @Test
    fun setMyHandPreservesDuplicatesAndSorts() {
        val s = GameState()
        s.setMyHand(listOf(Card.R5, Card.R3, Card.R3, Card.R3))
        // Cards must keep duplicate ranks (3 threes are a valid triple).
        val hand = s.myHand()
        assertEquals(listOf(Card.R3, Card.R3, Card.R3, Card.R5), hand)
    }

    @Test
    fun toInfoSetProducesConsistentSnapshot() {
        val s = newState()
        s.recordPlay(Position.LANDLORD_UP, listOf(Card.R5, Card.R5))
        val snap1 = s.toInfoSet()
        val snap2 = s.toInfoSet()
        assertEquals(snap1.playerHandCards, snap2.playerHandCards)
        assertEquals(snap1.playedCards, snap2.playedCards)
        assertEquals(snap1.lastMove, snap2.lastMove)
        assertEquals(snap1.bombNum, snap2.bombNum)
    }

    @Test
    fun snapshotLegalActionsMatchGenerator() {
        val s = GameState().apply {
            setMyHand(listOf(Card.R3, Card.R4, Card.R5))
        }
        val snap = s.toInfoSet()
        val expected = MoveGenerator(listOf(Card.R3, Card.R4, Card.R5))
            .legalActions(emptyList())
        assertEquals(expected.toSet(), snap.legalActions.toSet())
    }
}
