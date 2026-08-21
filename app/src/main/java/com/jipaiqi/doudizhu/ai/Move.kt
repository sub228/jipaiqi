package com.jipaiqi.doudizhu.ai

/**
 * Dou Dizhu move types — ported from DouZero `env/utils.py` and
 * `env/move_detector.py`. Used by [MoveGenerator] to enumerate legal
 * plays and by [beats] to determine whether one move beats another.
 */
object MoveType {
    const val PASS = 0
    const val SINGLE = 1
    const val PAIR = 2
    const val TRIPLE = 3
    const val BOMB = 4
    const val KING_BOMB = 5
    const val TRIPLE_WITH_ONE = 6
    const val TRIPLE_WITH_PAIR = 7
    const val SERIAL_SINGLE = 8
    const val SERIAL_PAIR = 9
    const val SERIAL_TRIPLE = 10
    const val SERIAL_3_1 = 11
    const val SERIAL_3_2 = 12
    const val BOMB_WITH_TWO = 13
    const val BOMB_WITH_TWO_PAIRS = 14
    const val WRONG = 15
}

/** Minimum length for serial moves (no 4-card or 2-card straights). */
const val MIN_SERIAL_SINGLE = 5
const val MIN_SERIAL_PAIR = 3
const val MIN_SERIAL_TRIPLE = 2

/**
 * Classified move: type + identifying rank + serial length (when applicable).
 * Mirrors the dict returned by DouZero `get_move_type`.
 */
data class MoveInfo(val type: Int, val rank: Int = 0, val len: Int = 0)

object MoveDetector {

    /** Whether [move] is a strictly increasing sequence by 1. */
    fun isContinuousSeq(move: List<Int>): Boolean {
        if (move.size < 2) return true
        for (i in 0 until move.size - 1) {
            if (move[i + 1] - move[i] != 1) return false
        }
        return true
    }

    /** Classify a move (list of card integers). Empty list = PASS. */
    fun getMoveType(move: List<Int>): MoveInfo {
        val n = move.size
        if (n == 0) return MoveInfo(MoveType.PASS)
        if (n == 1) return MoveInfo(MoveType.SINGLE, move[0])

        // counts: rank -> count
        val counts = HashMap<Int, Int>()
        for (c in move) counts[c] = (counts[c] ?: 0) + 1

        if (n == 2) {
            return if (move[0] == move[1]) MoveInfo(MoveType.PAIR, move[0])
            else if (move.contains(Card.BJ) && move.contains(Card.RJOKER)) MoveInfo(MoveType.KING_BOMB)
            else MoveInfo(MoveType.WRONG)
        }
        if (n == 3) {
            return if (counts.size == 1) MoveInfo(MoveType.TRIPLE, move[0])
            else MoveInfo(MoveType.WRONG)
        }
        if (n == 4) {
            return if (counts.size == 1) MoveInfo(MoveType.BOMB, move[0])
            else if (counts.size == 2 &&
                (move[0] == move[1] && move[1] == move[2] ||
                    move[1] == move[2] && move[2] == move[3])
            ) MoveInfo(MoveType.TRIPLE_WITH_ONE, move[1])
            else MoveInfo(MoveType.WRONG)
        }

        // n >= 5
        if (isContinuousSeq(move.sorted())) {
            return MoveInfo(MoveType.SERIAL_SINGLE, move.min(), n)
        }
        if (n == 5) {
            return if (counts.size == 2) MoveInfo(MoveType.TRIPLE_WITH_PAIR, move[2])
            else MoveInfo(MoveType.WRONG)
        }

        // count -> #ranks-with-that-count
        val byCount = HashMap<Int, Int>()
        for ((_, c) in counts) byCount[c] = (byCount[c] ?: 0) + 1

        if (n == 6 && (counts.size == 2 || counts.size == 3) && byCount[4] == 1 &&
            (byCount[2] == 1 || byCount[1] == 2)
        ) {
            return MoveInfo(MoveType.BOMB_WITH_TWO, move[2])
        }
        if (n == 8 && ((counts.size == 3 || counts.size == 2) &&
                    (byCount[4] == 1 && byCount[2] == 2) || byCount[4] == 2)
        ) {
            val rank = counts.entries.first { it.value == 4 }.key
            return MoveInfo(MoveType.BOMB_WITH_TWO_PAIRS, rank)
        }

        val mdkeys = counts.keys.sorted()
        if (counts.size == byCount[2] && isContinuousSeq(mdkeys)) {
            return MoveInfo(MoveType.SERIAL_PAIR, mdkeys[0], mdkeys.size)
        }
        if (counts.size == byCount[3] && isContinuousSeq(mdkeys)) {
            return MoveInfo(MoveType.SERIAL_TRIPLE, mdkeys[0], mdkeys.size)
        }

        // Serial 3+1 / 3+2
        if ((byCount[3] ?: 0) >= MIN_SERIAL_TRIPLE) {
            val serial3 = counts.filter { it.value == 3 }.keys.sorted()
            val single = counts.filter { it.value == 1 }.keys
            val pair = counts.filter { it.value == 2 }.keys
            if (serial3.isNotEmpty() && single.isNotEmpty() || pair.isNotEmpty()) {
                if (isContinuousSeq(serial3)) {
                    if (serial3.size == single.size + pair.size * 2) {
                        return MoveInfo(MoveType.SERIAL_3_1, serial3[0], serial3.size)
                    }
                    if (serial3.size == pair.size && counts.size == serial3.size * 2) {
                        return MoveInfo(MoveType.SERIAL_3_2, serial3[0], serial3.size)
                    }
                }
            }
        }
        return MoveInfo(MoveType.WRONG)
    }
}

object MoveSelector {

    /** Whether [mine] strictly beats [rival]. Assumes both moves have valid type. */
    fun beats(mine: List<Int>, rival: List<Int>): Boolean {
        if (rival.isEmpty()) return mine.isNotEmpty()
        val m = MoveDetector.getMoveType(mine)
        val r = MoveDetector.getMoveType(rival)
        if (m.type == MoveType.WRONG || r.type == MoveType.WRONG) return false

        // King bomb beats everything except nothing beats a king bomb.
        if (m.type == MoveType.KING_BOMB) return r.type != MoveType.KING_BOMB
        if (r.type == MoveType.KING_BOMB) return false

        // Bombs beat any non-bomb.
        if (m.type == MoveType.BOMB && r.type != MoveType.BOMB) return true
        if (m.type != MoveType.BOMB && r.type == MoveType.BOMB) return false

        // Same type, same length: higher rank wins.
        if (m.type != r.type) return false
        if (m.len != r.len) return false
        return m.rank > r.rank
    }

    /**
     * Filter [moves] down to those that strictly beat [rival]. Same type,
     * same serial length, strictly higher rank. Bombs are added separately
     * by [MoveGenerator.legalActions].
     */
    fun filterBeating(moves: List<List<Int>>, rival: List<Int>): List<List<Int>> {
        if (rival.isEmpty()) return moves
        return moves.filter { beats(it, rival) }
    }
}
