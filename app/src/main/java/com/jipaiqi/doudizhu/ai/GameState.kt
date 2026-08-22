package com.jipaiqi.doudizhu.ai

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Player position in a Dou Dizhu game, matching DouZero's naming.
 *
 *  - LANDLORD:      initial 20 cards (17 + 3 bottom cards)
 *  - LANDLORD_UP:   the farmer who plays immediately *after* the landlord
 *  - LANDLORD_DOWN: the farmer who plays immediately *before* the landlord
 */
enum class Position(val douZeroName: String, val initialCards: Int) {
    LANDLORD("landlord", 20),
    LANDLORD_UP("landlord_up", 17),
    LANDLORD_DOWN("landlord_down", 17);

    val other: List<Position> get() = when (this) {
        LANDLORD -> listOf(LANDLORD_UP, LANDLORD_DOWN)
        LANDLORD_UP -> listOf(LANDLORD, LANDLORD_DOWN)
        LANDLORD_DOWN -> listOf(LANDLORD, LANDLORD_UP)
    }

    /** The other farmer (teammate) for a farmer position. */
    val teammate: Position get() = when (this) {
        LANDLORD_UP -> LANDLORD_DOWN
        LANDLORD_DOWN -> LANDLORD_UP
        LANDLORD -> error("landlord has no teammate")
    }
}

/**
 * Mutable, thread-safe Dou Dizhu game state maintained by the screen
 * recognizer.
 *
 * The state mirrors DouZero's `env/game.py::InfoSet` (perfect-info snapshot
 * minus the hidden cards the recognizer cannot see). Updates come from the
 * recognizer via [setMyHand] / [recordPlay] / [newGame]. Reads via
 * [toInfoSet] produce the feature encoder input used by [DouZeroEngine].
 *
 * Deduplication: every play is keyed by `(position, sortedCards)`. Repeated
 * recognitions of the same on-screen play are silently ignored. Plays are
 * also dropped if the cards were never in the remaining deck, which guards
 * against OCR/YOLO false positives corrupting the state.
 */
class GameState {

    private val lock = ReentrantLock()

    @Volatile var myPosition: Position = Position.LANDLORD
        private set

    /** Cards currently in my hand (most recent successful recognition). */
    @Volatile private var myHand: List<Int> = emptyList()

    /** Cards played by each position, in order. Used for "played_cards" feature. */
    private val playedByPosition: MutableMap<Position, MutableList<Int>> = mutableMapOf(
        Position.LANDLORD to mutableListOf(),
        Position.LANDLORD_UP to mutableListOf(),
        Position.LANDLORD_DOWN to mutableListOf()
    )

    /** Full history of plays, oldest first. Each entry is [Position, cards]. */
    private val actionSeq: MutableList<Pair<Position, List<Int>>> = mutableListOf()

    /** Last move by each position. Empty list if not yet played this round. */
    private val lastMoveByPosition: MutableMap<Position, List<Int>> = mutableMapOf(
        Position.LANDLORD to emptyList(),
        Position.LANDLORD_UP to emptyList(),
        Position.LANDLORD_DOWN to emptyList()
    )

    /** Most recent move that's currently "on the table" (the play I must beat). */
    @Volatile private var lastMoveOnTable: List<Int> = emptyList()

    /** The 3 bottom cards revealed to the landlord only, if known. */
    @Volatile private var bottomCards: List<Int>? = null

    /** Last recognized play signature for dedup. */
    private var lastSeenPlaySignature: String? = null

    // ---- Mutators ------------------------------------------------------

    fun setMyPosition(p: Position) = lock.withLock { myPosition = p }
    fun setBottomCards(cards: List<Int>) = lock.withLock { bottomCards = cards.sorted() }

    /** Replace my hand with [cards] (sorted, preserving duplicate ranks). */
    fun setMyHand(cards: List<Int>) = lock.withLock {
        myHand = cards.sorted()
    }

    /**
     * Record a play made by [position]. Idempotent: calling with the same
     * `(position, cards)` twice in a row has no effect (handles the
     * recognizer seeing the same on-screen play multiple times).
     *
     * Returns true if the play was accepted, false if rejected (duplicate
     * or impossible given the current state).
     */
    fun recordPlay(position: Position, cards: List<Int>): Boolean = lock.withLock {
        if (cards.isEmpty()) return false
        val sorted = cards.sorted()

        // Dedup: same position plays the same cards twice.
        val sig = "${position}:${sorted.joinToString(",")}"
        if (sig == lastSeenPlaySignature) return false
        // Dedup: don't re-add a play that's already in the history.
        if (actionSeq.any { it.first == position && it.second == sorted }) return false
        // Sanity: must not exceed 4 copies of a rank.
        val allOnTable = actionSeq.flatMap { it.second } + sorted + myHand
        val counts = HashMap<Int, Int>()
        for (c in allOnTable) counts[c] = (counts[c] ?: 0) + 1
        if (counts.entries.any { (k, v) -> (Card.TOTAL[k] ?: 4) < v }) return false

        playedByPosition[position]!!.addAll(sorted)
        actionSeq.add(position to sorted)
        lastMoveByPosition[position] = sorted
        lastMoveOnTable = sorted
        lastSeenPlaySignature = sig
        true
    }

    /** Pass turn (the player explicitly passes — still recorded as a no-op). */
    fun recordPass(position: Position) = lock.withLock {
        actionSeq.add(position to emptyList())
        // If the player before me passed AND their predecessor's play is now the
        // "lead" I must beat, the play on the table stays.
    }

    /**
     * Clear the "play on the table". Called by the recognizer when the
     * table region has been empty for several consecutive frames, which
     * signals that everyone passed and the next player leads (rival_move
     * becomes empty, so any move is legal again). Mirrors DouZero's
     * `get_last_move` returning [] after two consecutive passes.
     */
    fun clearTable() = lock.withLock {
        lastMoveOnTable = emptyList()
        lastSeenPlaySignature = null
    }

    /** Reset state for a new game. */
    fun newGame() = lock.withLock {
        playedByPosition.values.forEach { it.clear() }
        actionSeq.clear()
        lastMoveByPosition.keys.forEach { lastMoveByPosition[it] = emptyList() }
        lastMoveOnTable = emptyList()
        lastSeenPlaySignature = null
        bottomCards = null
        myHand = emptyList()
    }

    // ---- Reads --------------------------------------------------------

    /** The cards I am currently holding. */
    fun myHand(): List<Int> = lock.withLock { myHand.toList() }

    /**
     * Cards I cannot see: every card in the deck that is not in my hand and
     * not yet played. This is DouZero's `other_hand_cards` feature — the
     * union of the other two players' hands + unrevealed bottom cards (when
     * I'm a farmer).
     */
    fun otherHandCards(): List<Int> = lock.withLock {
        // NOTE: do NOT collapse to Set — duplicate ranks must be counted
        // (a hand can legitimately hold 4 copies of a rank).
        val mineCounts = HashMap<Int, Int>()
        for (c in myHand) mineCounts[c] = (mineCounts[c] ?: 0) + 1
        val playedCounts = HashMap<Int, Int>()
        for (c in playedByPosition.values.flatten()) {
            playedCounts[c] = (playedCounts[c] ?: 0) + 1
        }
        val out = ArrayList<Int>()
        for (rank in Card.ALL_RANKS) {
            val total = Card.TOTAL[rank]!!
            val inMine = mineCounts[rank] ?: 0
            val inPlayed = playedCounts[rank] ?: 0
            val remaining = total - inMine - inPlayed
            repeat(remaining.coerceAtLeast(0)) { out.add(rank) }
        }
        out
    }

    /** Cards played by each position so far (combined). */
    fun playedBy(position: Position): List<Int> = lock.withLock {
        playedByPosition[position]!!.toList()
    }

    /** Number of cards remaining in each player's hand. */
    fun numCardsLeft(): Map<Position, Int> = lock.withLock {
        val init = Position.values().associateWith { it.initialCards }
        init.mapValues { (pos, n) ->
            (n - playedByPosition[pos]!!.size).coerceAtLeast(0)
        }
    }

    /** Total bombs played so far (bomb + king bomb). */
    fun bombNum(): Int = lock.withLock {
        actionSeq.count { (_, cards) ->
            cards.isNotEmpty() && run {
                val t = MoveDetector.getMoveType(cards).type
                t == MoveType.BOMB || t == MoveType.KING_BOMB
            }
        }
    }

    /** The most recent play on the table that I need to beat (empty = I lead). */
    fun lastMove(): List<Int> = lock.withLock { lastMoveOnTable.toList() }

    /** Last play by [position]. Empty list if none this round. */
    fun lastMoveBy(position: Position): List<Int> = lock.withLock {
        lastMoveByPosition[position]?.toList() ?: emptyList()
    }

    /** The last 15 actions, padded with empty plays, oldest first. */
    fun actionSeq15(): List<List<Int>> = lock.withLock {
        val recent = actionSeq.takeLast(15).map { it.second }
        if (recent.size >= 15) recent
        else List<List<Int>>(15 - recent.size) { emptyList() } + recent
    }

    /**
     * The last 15 actions WITH the position who made each play, oldest first.
     * Padded with `(LANDLORD, [])` to keep the length at 15.  Used by the
     * floating window's history panel to render "地主 / 上家 / 下家" tags
     * alongside the card list — without this, [actionSeq15] only returns the
     * card ranks, losing who played them.
     */
    fun actionSeq15WithPosition(): List<Pair<Position, List<Int>>> = lock.withLock {
        val recent: List<Pair<Position, List<Int>>> = actionSeq.takeLast(15)
            .map { it.first to it.second.toList() }
        if (recent.size >= 15) {
            recent
        } else {
            val pad: List<Pair<Position, List<Int>>> = List(15 - recent.size) {
                Position.LANDLORD to emptyList()
            }
            pad + recent
        }
    }

    /** True if we have enough information to attempt an AI recommendation. */
    fun isReady(): Boolean = myHand.isNotEmpty() && myPosition != null

    /**
     * Build the immutable Infoset snapshot consumed by the feature encoder.
     * Captures all relevant state atomically under the lock.
     */
    fun toInfoSet(): InfoSetSnapshot = lock.withLock {
        InfoSetSnapshot(
            playerPosition = myPosition,
            playerHandCards = myHand.toList(),
            otherHandCards = otherHandCards(),
            playedCards = playedByPosition.mapValues { it.value.toList() },
            lastMove = lastMoveOnTable.toList(),
            lastMoveByPosition = lastMoveByPosition.mapValues { it.value.toList() },
            numCardsLeft = numCardsLeft(),
            bombNum = bombNum(),
            cardPlayActionSeq = actionSeq15(),
            cardPlayActionSeqWithPosition = actionSeq15WithPosition(),
            bottomCards = bottomCards
        )
    }
}

/**
 * Immutable snapshot of the game state from a single player's perspective,
 * taken atomically under [GameState]'s lock. Mirrors DouZero's InfoSet fields
 * used by the feature encoder (see env/env.py).
 */
data class InfoSetSnapshot(
    val playerPosition: Position,
    val playerHandCards: List<Int>,
    val otherHandCards: List<Int>,
    val playedCards: Map<Position, List<Int>>,
    val lastMove: List<Int>,
    val lastMoveByPosition: Map<Position, List<Int>>,
    val numCardsLeft: Map<Position, Int>,
    val bombNum: Int,
    val cardPlayActionSeq: List<List<Int>>,
    val cardPlayActionSeqWithPosition: List<Pair<Position, List<Int>>> = emptyList(),
    val bottomCards: List<Int>?
) {
    val legalActions: List<List<Int>> by lazy {
        MoveGenerator(playerHandCards).legalActions(lastMove)
    }
}
