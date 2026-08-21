package com.jipaiqi.doudizhu.ai

import com.example.qnjisuanqi.YoloAPI

/**
 * Bridge the ORIGINAL 王者记牌器 YOLOv8-n label universe (0..53) to the
 * DouZero-compatible rank integers defined in [Card].
 *
 * The original APK's native `libyolov8ncnn.so` returns either an int
 * `label` id 0..53 OR a `labelName` string.  We trust `labelName` first
 * (more human readable / auditable) and fall back to the int id.
 *
 * Ordering match against the original model: the NCNN param file outputs
 * 54 classes.  We derive rank mapping in TWO ways:
 *   (A) **labelName string → Card rank** is authoritative because the
 *       native layer always populates labelName as one of the 15
 *       canonical textual labels.
 *   (B) **int id → rank bucketing** works by collapsing id / 4 into a
 *       0..12 rank bucket (with jokers at the last two positions).
 *       The exact bucket sequence follows the same order as
 *       `gameconfiguration.json`'s `cards_total_count` list:
 *           bucket 0..3   → "2" → R2
 *           bucket 4..7   → "A" → RA
 *           bucket 8..11  → "K" → RK
 *           bucket 12..15 → "Q" → RQ
 *           bucket 16..19 → "J" → RJ
 *           bucket 20..23 → "10" → R10
 *           bucket 24..27 → "9" → R9
 *           bucket 28..31 → "8" → R8
 *           bucket 32..35 → "7" → R7
 *           bucket 36..39 → "6" → R6
 *           bucket 40..43 → "5" → R5
 *           bucket 44..47 → "4" → R4
 *           bucket 48..51 → "3" → R3
 *           bucket 52      → Small Joker → BJ
 *           bucket 53      → Big Joker → RJOKER
 */
object YoloLabelBridge {

    fun toRank(obj: YoloAPI.Obj): Int {
        val ln = obj.labelName?.trim()?.uppercase()
        if (!ln.isNullOrEmpty()) {
            Card.fromText(ln)?.let { return it }
        }
        return labelIdToRankBucket(obj.label)
    }

    /** Exposed for testing. */
    fun labelNameToRank(labelName: String): Int {
        // "1" is ambiguous: Card.fromText maps it to R10 (because the raw OCR
        // often produces "1" for a cropped "10") but the native YOLO label
        // name space uses "A" for Ace.  However if the user's config ever
        // explicitly says "1" as an Ace labelName, we need RA.  We solve
        // this with a priority override:
        val trimmed = labelName.trim()
        if (trimmed == "1") return Card.R10   // match Card.fromText convention
        if (trimmed.uppercase() == "ACE") return Card.RA
        val r = Card.fromText(trimmed)
        return r ?: when (trimmed.uppercase()) {
            "T", "TEN" -> Card.R10
            "JACK"      -> Card.RJ
            "QUEEN"     -> Card.RQ
            "KING"      -> Card.RK
            "BLACK_JOKER", "BLACKJOKER", "SJOKER", "LJ", "X" -> Card.BJ
            "RED_JOKER", "REDJOKER", "BJOKER", "D" -> Card.RJOKER
            else -> {
                val d = trimmed.firstOrNull()?.digitToIntOrNull()
                if (d != null && d in 3..9) d else Card.R3
            }
        }
    }

    private fun labelIdToRankBucket(id: Int): Int {
        val clamped = id.coerceIn(0, 53)
        return when (clamped) {
            52 -> Card.BJ
            53 -> Card.RJOKER
            else -> when (clamped / 4) {
                0  -> Card.R2
                1  -> Card.RA
                2  -> Card.RK
                3  -> Card.RQ
                4  -> Card.RJ
                5  -> Card.R10
                6  -> Card.R9
                7  -> Card.R8
                8  -> Card.R7
                9  -> Card.R6
                10 -> Card.R5
                11 -> Card.R4
                12 -> Card.R3
                else -> Card.R3
            }
        }
    }
}
