package com.jipaiqi.doudizhu.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the ORIGINAL YOLOv8 label bridge + card totals.
 * (No device required — all logic is pure math.)
 */
class YoloLabelBridgeTest {

    @Test fun labelNameToRank_coversAll15Ranks() {
        val pairs = listOf(
            "3" to Card.R3,   "4" to Card.R4,   "5" to Card.R5,
            "6" to Card.R6,   "7" to Card.R7,   "8" to Card.R8,
            "9" to Card.R9,   "10" to Card.R10, "J" to Card.RJ,
            "Q" to Card.RQ,   "K" to Card.RK,   "A" to Card.RA,
            "2" to Card.R2,   "BJ" to Card.BJ,  "RJ" to Card.RJOKER
        )
        for ((n, e) in pairs) assertEquals("label=$n", e, YoloLabelBridge.labelNameToRank(n))
    }

    @Test fun labelNameToRank_aliases() {
        // Chinese + shorthand + English-word variants must all decode.
        assertEquals(Card.BJ,     YoloLabelBridge.labelNameToRank("小王"))
        assertEquals(Card.BJ,     YoloLabelBridge.labelNameToRank("bj"))
        assertEquals(Card.BJ,     YoloLabelBridge.labelNameToRank("X"))
        assertEquals(Card.RJOKER, YoloLabelBridge.labelNameToRank("大王"))
        assertEquals(Card.RJOKER, YoloLabelBridge.labelNameToRank("rj"))
        assertEquals(Card.RJOKER, YoloLabelBridge.labelNameToRank("D"))
        assertEquals(Card.R10,    YoloLabelBridge.labelNameToRank("T"))
        // "1" is ambiguous raw OCR text; Card.fromText treats it as a cropped "10".
        assertEquals(Card.R10,    YoloLabelBridge.labelNameToRank("1"))
        assertEquals(Card.RA,     YoloLabelBridge.labelNameToRank("ACE"))
        assertEquals(Card.RJ,     YoloLabelBridge.labelNameToRank("JACK"))
        assertEquals(Card.RQ,     YoloLabelBridge.labelNameToRank("QUEEN"))
        assertEquals(Card.RK,     YoloLabelBridge.labelNameToRank("KING"))
    }

    @Test fun toRank_prefersLabelNameOverId() {
        // Even when `label` id is nonsense, a correct labelName wins.
        val obj = fakeObj(label = 999, labelName = "A", prob = 0.95f)
        assertEquals(Card.RA, YoloLabelBridge.toRank(obj))
    }

    /** Bucket ordering against gameconfiguration.json order X,D,2,A,K,Q,J,10,9,8,7,6,5,4,3. */
    @Test fun labelIdBucketing_matchesOriginalOrder() {
        assertEquals(Card.R2,     rankId(0))
        assertEquals(Card.R2,     rankId(3))
        assertEquals(Card.RA,     rankId(4))
        assertEquals(Card.RA,     rankId(7))
        assertEquals(Card.RK,     rankId(8))
        assertEquals(Card.RQ,     rankId(12))
        assertEquals(Card.RQ,     rankId(15))
        assertEquals(Card.RJ,     rankId(16))
        assertEquals(Card.RJ,     rankId(19))
        assertEquals(Card.R10,    rankId(20))
        assertEquals(Card.R10,    rankId(23))
        assertEquals(Card.R9,     rankId(24))
        assertEquals(Card.R9,     rankId(27))
        assertEquals(Card.R8,     rankId(28))
        assertEquals(Card.R7,     rankId(32))
        assertEquals(Card.R7,     rankId(35))
        assertEquals(Card.R6,     rankId(36))
        assertEquals(Card.R5,     rankId(40))
        assertEquals(Card.R5,     rankId(43))
        assertEquals(Card.R4,     rankId(44))
        assertEquals(Card.R4,     rankId(47))
        assertEquals(Card.R3,     rankId(48))
        assertEquals(Card.R3,     rankId(51))
        assertEquals(Card.BJ,     rankId(52))
        assertEquals(Card.RJOKER, rankId(53))
    }

    @Test fun all54Ids_mapIntoExactly15Ranks() {
        val seen = HashSet<Int>()
        for (id in 0..53) seen.add(rankId(id))
        assertEquals(15, seen.size)
        for (r in Card.ALL_RANKS) assertTrue("rank $r must be reachable", r in seen)
    }

    @Test fun cardTotals_sumTo54() {
        assertEquals(4 * 13 + 1 + 1, Card.TOTAL.values.sum())
        val ranks3to9plus = listOf(3,4,5,6,7,8,9,10,11,12,13,14,17)
        for (r in ranks3to9plus) assertEquals("rank $r should have 4 copies", 4, Card.TOTAL[r])
        assertEquals(1, Card.TOTAL[Card.BJ])
        assertEquals(1, Card.TOTAL[Card.RJOKER])
    }

    // -------- helpers --------
    private fun rankId(id: Int): Int = YoloLabelBridge.toRank(fakeObj(label = id))
    private fun fakeObj(label: Int, labelName: String? = null, prob: Float = 0.8f) =
        com.example.qnjisuanqi.YoloAPI.Obj().also {
            it.label = label; it.labelName = labelName; it.prob = prob
        }
}
