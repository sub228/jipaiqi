package com.jipaiqi.doudizhu.ai

/**
 * Dou Dizhu card representation, matching the integer encoding used by the
 * DouZero reference implementation (https://github.com/kwai/DouZero).
 *
 * Encoding:
 *   3..10  -> ranks 3..10      (4 copies each)
 *   11..14 -> J, Q, K, A      (4 copies each)
 *   17     -> "2"             (4 copies)
 *   20     -> Small Joker (BJ, 小王)   (1 copy)
 *   30     -> Big   Joker (RJ, 大王)   (1 copy)
 *
 * 12 "regular" ranks × 4 + 4 copies of "2" + 2 jokers = 54 cards total.
 *
 * The 54-dim binary feature used by DouZero is laid out as a 4x13 matrix
 * (Fortran-order flatten) followed by 2 joker bits.
 */
object Card {

    // ---- Rank constants (match DouZero env/env.py) ---------------------
    const val R3 = 3
    const val R4 = 4
    const val R5 = 5
    const val R6 = 6
    const val R7 = 7
    const val R8 = 8
    const val R9 = 9
    const val R10 = 10
    const val RJ = 11   // J
    const val RQ = 12   // Q
    const val RK = 13   // K
    const val RA = 14   // A
    const val R2 = 17   // 2
    const val BJ = 20   // Small joker
    const val RJOKER = 30 // Big joker

    /** All 15 rank categories in display/sort order, low -> high. */
    val ALL_RANKS: List<Int> = listOf(
        R3, R4, R5, R6, R7, R8, R9, R10, RJ, RQ, RK, RA, R2, BJ, RJOKER
    )

    /** Map rank -> column index in the 4x13 matrix used by DouZero. */
    val COLUMN: Map<Int, Int> = mapOf(
        R3 to 0, R4 to 1, R5 to 2, R6 to 3, R7 to 4, R8 to 5,
        R9 to 6, R10 to 7, RJ to 8, RQ to 9, RK to 10, RA to 11, R2 to 12
    )

    /** Maximum count of each rank. */
    val TOTAL: Map<Int, Int> = ALL_RANKS.associateWith { if (it == BJ || it == RJOKER) 1 else 4 }

    /** Total number of cards in a Dou Dizhu deck. */
    const val TOTAL_CARDS = 54

    // ---- Display helpers ------------------------------------------------
    private val DISPLAY: Map<Int, String> = mapOf(
        R3 to "3", R4 to "4", R5 to "5", R6 to "6", R7 to "7", R8 to "8",
        R9 to "9", R10 to "10", RJ to "J", RQ to "Q", RK to "K", RA to "A",
        R2 to "2", BJ to "BJ", RJOKER to "RJ"
    )

    /** Short human-readable label for a rank (e.g. "3", "J", "BJ"). */
    fun label(rank: Int): String = DISPLAY[rank] ?: "?"

    /** Full Chinese label for a rank (e.g. "小王", "大王"). */
    fun labelCn(rank: Int): String = when (rank) {
        BJ -> "小王"
        RJOKER -> "大王"
        else -> label(rank)
    }

    /**
     * Decode a single OCR/character into a rank, or null if unrecognized.
     * Accepts both ASCII ('3'..'9','0','1','J','j','Q','q','K','k','A','a','2')
     * and the Chinese joker markers (BJ/RJ). Used to interpret OCR output.
     */
    fun fromText(s: String): Int? = when (s.trim().uppercase()) {
        "3" -> R3
        "4" -> R4
        "5" -> R5
        "6" -> R6
        "7" -> R7
        "8" -> R8
        "9" -> R9
        "10", "1O", "1", "O" -> R10
        "J" -> RJ
        "Q" -> RQ
        "K" -> RK
        "A" -> RA
        "2" -> R2
        "BJ", "小王", "XW", "SJ", "JOKER" -> BJ
        "RJ", "大王", "DW", "BJ2", "BJJ", "JKR" -> RJOKER
        else -> null
    }

    /**
     * Convert a list of card integers to the 54-dim binary feature used by
     * DouZero (see _cards2array in env/env.py). Layout:
     *   - 13 columns × 4 rows in column-major order (52 entries)
     *   - 2 trailing joker bits (BJ, RJ)
     */
    fun cardsToArray(cards: List<Int>): FloatArray {
        val out = FloatArray(54)
        if (cards.isEmpty()) return out
        val counts = HashMap<Int, Int>()
        for (c in cards) counts[c] = (counts[c] ?: 0) + 1
        for ((card, n) in counts) {
            when (card) {
                BJ -> out[52] = 1f
                RJOKER -> out[53] = 1f
                else -> {
                    val col = COLUMN[card] ?: continue
                    // column-major: index = col*4 + row
                    for (row in 0 until n) out[col * 4 + row] = 1f
                }
            }
        }
        return out
    }
}
