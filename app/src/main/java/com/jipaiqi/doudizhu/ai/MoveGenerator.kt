package com.jipaiqi.doudizhu.ai

import kotlin.math.max

/**
 * Generates all Dou Dizhu moves from a hand of cards. Direct port of DouZero's
 * `env/move_generator.py` `MovesGener` plus the `get_legal_card_play_actions`
 * logic from `env/game.py`.
 *
 * The DouZero move taxonomy has 14 valid move types + a 15th "wrong" sentinel:
 *  1  single              | [3]
 *  2  pair                | [3,3]
 *  3  triple              | [3,3,3]
 *  4  bomb                | [3,3,3,3]
 *  5  king bomb           | [BJ,RJ]
 *  6  triple + one        | [3,3,3,5]
 *  7  triple + pair       | [3,3,3,5,5]
 *  8  serial single       | [3,4,5,6,7]   (>=5 consecutive)
 *  9  serial pair         | [3,3,4,4,5,5] (>=3 consecutive)
 *  10 serial triple       | [3,3,3,4,4,4] (>=2 consecutive)
 *  11 serial 3+1          | [3,3,3,4,4,4,5,7] (>=2 consecutive triples)
 *  12 serial 3+2          | [3,3,3,4,4,4,5,5,7,7] (>=2 consecutive triples)
 *  13 bomb + two single   | [3,3,3,3,5,7]
 *  14 bomb + two pairs    | [3,3,3,3,5,5,7,7]
 *
 * "2" (rank 17) and jokers cannot be part of serials in standard Dou Dizhu,
 * matching DouZero's behavior because the COLUMN map excludes 17/20/30.
 */
class MoveGenerator(hand: List<Int>) {

    private val cardsList: List<Int> = hand
    private val cardsDict: Map<Int, Int>

    // Pre-computed basic move sets
    private val singleMoves: List<List<Int>>
    private val pairMoves: List<List<Int>>
    private val tripleMoves: List<List<Int>>
    private val bombMoves: List<List<Int>>
    private val kingBombMoves: List<List<Int>>

    init {
        val m = HashMap<Int, Int>()
        for (c in hand) m[c] = (m[c] ?: 0) + 1
        cardsDict = m

        singleMoves = cardsDict.keys.map { listOf(it) }
        pairMoves = cardsDict.filter { it.value >= 2 }.keys.map { listOf(it, it) }
        tripleMoves = cardsDict.filter { it.value >= 3 }.keys.map { listOf(it, it, it) }
        bombMoves = cardsDict.filter { it.value == 4 }.keys.map { listOf(it, it, it, it) }
        kingBombMoves =
            if (cardsDict[Card.BJ] != null && cardsDict[Card.RJOKER] != null)
                listOf(listOf(Card.BJ, Card.RJOKER))
            else emptyList()
    }

    // ---- Basic moves ----------------------------------------------------
    fun genType1Single(): List<List<Int>> = singleMoves
    fun genType2Pair(): List<List<Int>> = pairMoves
    fun genType3Triple(): List<List<Int>> = tripleMoves
    fun genType4Bomb(): List<List<Int>> = bombMoves
    fun genType5KingBomb(): List<List<Int>> = kingBombMoves

    fun genType6TripleWithOne(): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        for (t in tripleMoves) {
            val tRank = t[0]
            for (s in singleMoves) {
                if (s[0] != tRank) out.add(t + s)
            }
        }
        return out
    }

    fun genType7TripleWithPair(): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        for (t in tripleMoves) {
            val tRank = t[0]
            for (p in pairMoves) {
                if (p[0] != tRank) out.add(t + p)
            }
        }
        return out
    }

    // ---- Serial moves ---------------------------------------------------

    /**
     * Generate serial moves of `repeat` consecutive ranks.
     *  - repeat=1: serial single
     *  - repeat=2: serial pair
     *  - repeat=3: serial triple
     *
     * `cards` is the candidate rank list to search for sequences in.
     * `minSerial` is the minimum sequence length.
     * If `repeatNum` > 0, only sequences of exactly that length are returned.
     */
    private fun genSerialMoves(
        cards: List<Int>, minSerial: Int, repeat: Int, repeatNum: Int = 0
    ): List<List<Int>> {
        val singleCards = cards.toSortedSet().toList()
        if (singleCards.isEmpty()) return emptyList()

        // Find consecutive runs.
        val records = ArrayList<IntArray>() // intArrayOf(startIndex, length)
        var start = 0
        var i = 0
        var longest = 1
        while (i < singleCards.size) {
            if (i + 1 < singleCards.size && singleCards[i + 1] - singleCards[i] == 1) {
                longest++
                i++
            } else {
                records.add(intArrayOf(start, longest))
                i++
                start = i
                longest = 1
            }
        }

        val moves = ArrayList<List<Int>>()
        for (rec in records) {
            val (st, len) = rec[0] to rec[1]
            if (len < minSerial) continue
            val longestList = singleCards.subList(st, st + len)

            if (repeatNum == 0) {
                var steps = minSerial
                while (steps <= len) {
                    var idx = 0
                    while (steps + idx <= len) {
                        val target = ArrayList<Int>(steps * repeat)
                        for (r in 0 until repeat) {
                            for (k in 0 until steps) target.add(longestList[idx + k])
                        }
                        moves.add(target.sorted())
                        idx++
                    }
                    steps++
                }
            } else {
                if (len < repeatNum) continue
                var idx = 0
                while (idx + repeatNum <= len) {
                    val target = ArrayList<Int>(repeatNum * repeat)
                    for (r in 0 until repeat) {
                        for (k in 0 until repeatNum) target.add(longestList[idx + k])
                    }
                    moves.add(target.sorted())
                    idx++
                }
            }
        }
        return moves
    }

    fun genType8SerialSingle(repeatNum: Int = 0): List<List<Int>> =
        genSerialMoves(cardsList, MIN_SERIAL_SINGLE, repeat = 1, repeatNum = repeatNum)

    fun genType9SerialPair(repeatNum: Int = 0): List<List<Int>> {
        val pairRanks = cardsDict.filter { it.value >= 2 }.keys.toList()
        return genSerialMoves(pairRanks, MIN_SERIAL_PAIR, repeat = 2, repeatNum = repeatNum)
    }

    fun genType10SerialTriple(repeatNum: Int = 0): List<List<Int>> {
        val tripleRanks = cardsDict.filter { it.value >= 3 }.keys.toList()
        return genSerialMoves(tripleRanks, MIN_SERIAL_TRIPLE, repeat = 3, repeatNum = repeatNum)
    }

    fun genType11Serial31(repeatNum: Int = 0): List<List<Int>> {
        val s3Moves = genType10SerialTriple(repeatNum = repeatNum)
        val out = ArrayList<List<Int>>()
        for (s3 in s3Moves) {
            val s3Set = s3.toSet()
            val newCards = cardsList.filter { it !in s3Set }
            for (combo in combinations(newCards, s3Set.size)) {
                out.add(s3 + combo)
            }
        }
        return out.distinctBy { it.sorted() }
    }

    fun genType12Serial32(repeatNum: Int = 0): List<List<Int>> {
        val s3Moves = genType10SerialTriple(repeatNum = repeatNum)
        val out = ArrayList<List<Int>>()
        val pairSet = cardsDict.filter { it.value >= 2 }.keys.toMutableList().apply { sort() }
        for (s3 in s3Moves) {
            val s3Set = s3.toSet()
            val candidates = pairSet.filter { it !in s3Set }
            for (combo in combinations(candidates, s3Set.size)) {
                val base = ArrayList<Int>(s3)
                for (c in combo) { base.add(c); base.add(c) }
                out.add(base.sorted())
            }
        }
        return out
    }

    fun genType13BombWithTwo(): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        for (fc in bombMoves) {
            val rank = fc[0]
            val rest = cardsList.filter { it != rank }
            for (combo in combinations(rest, 2)) {
                out.add(listOf(rank, rank, rank, rank) + combo)
            }
        }
        return out.distinctBy { it.sorted() }
    }

    fun genType14BombWithTwoPairs(): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        for (fc in bombMoves) {
            val rank = fc[0]
            val pairRanks = cardsDict.filter { it.key != rank && it.value >= 2 }.keys.toList()
            for (combo in combinations(pairRanks, 2)) {
                out.add(listOf(rank, rank, rank, rank) + listOf(combo[0], combo[0], combo[1], combo[1]))
            }
        }
        return out
    }

    /** All possible moves from the current hand (no rival-move filter). */
    fun genAllMoves(): List<List<Int>> = buildList {
        addAll(genType1Single())
        addAll(genType2Pair())
        addAll(genType3Triple())
        addAll(genType4Bomb())
        addAll(genType5KingBomb())
        addAll(genType6TripleWithOne())
        addAll(genType7TripleWithPair())
        addAll(genType8SerialSingle())
        addAll(genType9SerialPair())
        addAll(genType10SerialTriple())
        addAll(genType11Serial31())
        addAll(genType12Serial32())
        addAll(genType13BombWithTwo())
        addAll(genType14BombWithTwoPairs())
    }

    /**
     * Legal actions for the current hand against [rivalMove]:
     *  - if rivalMove is empty (we lead): all moves + pass([])
     *  - else: same-type-and-length moves that strictly beat rivalMove
     *          + any bomb (unless rivalMove is itself a bomb, in which case
     *            only stronger bombs) + pass([])
     */
    fun legalActions(rivalMove: List<Int>): List<List<Int>> {
        if (rivalMove.isEmpty()) {
            return genAllMoves() + listOf(emptyList())
        }
        val rInfo = MoveDetector.getMoveType(rivalMove)
        if (rInfo.type == MoveType.WRONG) return listOf(emptyList())

        // Same-type-and-length moves that beat rival.
        val sameTypeBeating: List<List<Int>> = when (rInfo.type) {
            MoveType.SINGLE -> MoveSelector.filterBeating(genType1Single(), rivalMove)
            MoveType.PAIR -> MoveSelector.filterBeating(genType2Pair(), rivalMove)
            MoveType.TRIPLE -> MoveSelector.filterBeating(genType3Triple(), rivalMove)
            MoveType.BOMB -> MoveSelector.filterBeating(genType4Bomb(), rivalMove) +
                genType5KingBomb()
            MoveType.KING_BOMB -> emptyList()
            MoveType.TRIPLE_WITH_ONE -> genType6TripleWithOne().filter {
                MoveSelector.beats(it, rivalMove)
            }
            MoveType.TRIPLE_WITH_PAIR -> genType7TripleWithPair().filter {
                MoveSelector.beats(it, rivalMove)
            }
            MoveType.SERIAL_SINGLE -> genType8SerialSingle(repeatNum = rInfo.len).filter {
                MoveSelector.beats(it, rivalMove)
            }
            MoveType.SERIAL_PAIR -> genType9SerialPair(repeatNum = rInfo.len).filter {
                MoveSelector.beats(it, rivalMove)
            }
            MoveType.SERIAL_TRIPLE -> genType10SerialTriple(repeatNum = rInfo.len).filter {
                MoveSelector.beats(it, rivalMove)
            }
            MoveType.SERIAL_3_1 -> genType11Serial31(repeatNum = rInfo.len).filter {
                MoveSelector.beats(it, rivalMove)
            }
            MoveType.SERIAL_3_2 -> genType12Serial32(repeatNum = rInfo.len).filter {
                MoveSelector.beats(it, rivalMove)
            }
            MoveType.BOMB_WITH_TWO -> genType13BombWithTwo().filter {
                MoveSelector.beats(it, rivalMove)
            }
            MoveType.BOMB_WITH_TWO_PAIRS -> genType14BombWithTwoPairs().filter {
                MoveSelector.beats(it, rivalMove)
            }
            else -> emptyList()
        }

        val out = ArrayList<List<Int>>(sameTypeBeating)
        // Any bomb beats any non-bomb; king bomb beats any bomb.
        if (rInfo.type != MoveType.BOMB && rInfo.type != MoveType.KING_BOMB) {
            out += genType4Bomb()
            out += genType5KingBomb()
        }
        // Pass is always legal when there is a rival move.
        out += listOf(emptyList())

        // Sort each move (DouZero normalizes this way too).
        return out.map { it.sorted() }.distinctBy { it }
    }

    // ---- Helper: n choose k combinations (port of douzero.env.utils.select)
    private fun <T> combinations(items: List<T>, k: Int): List<List<T>> {
        if (k < 0 || k > items.size) return emptyList()
        val result = ArrayList<List<T>>()
        val idx = IntArray(k) { it }
        if (k == 0) {
            result.add(emptyList())
            return result
        }
        while (true) {
            result.add(idx.map { items[it] })
            var i = k - 1
            while (i >= 0 && idx[i] == items.size - k + i) i--
            if (i < 0) break
            idx[i]++
            for (j in (i + 1) until k) idx[j] = idx[j - 1] + 1
        }
        return result
    }
}
