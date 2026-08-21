package com.jipaiqi.doudizhu.ai

import android.content.Context
import android.graphics.Rect
import android.util.Log
import com.jipaiqi.doudizhu.JiPaiQiApp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Config for how to carve up a single captured frame of the host Dou Dizhu
 * app into logical regions.  Mirrors the original 王者记牌器
 * `assets/screenAdaptions.json` / `assets/gameconfiguration.json` files
 * which are bundled directly from the original APK.
 *
 * All percentages are 0..1 ratios of the **portrait** frame dimensions.
 */
class ScreenAdaptation private constructor(private val ctx: Context) {

    data class PctRect(val top: Float, val bottom: Float, val left: Float, val right: Float) {
        fun toPx(screenW: Int, screenH: Int): Rect = Rect(
            (left   * screenW).toInt().coerceAtLeast(0),
            (top    * screenH).toInt().coerceAtLeast(0),
            (right  * screenW).toInt().coerceAtMost(screenW),
            (bottom * screenH).toInt().coerceAtMost(screenH)
        )
    }

    /** Top-y (0..1) below which every detection counts as a hand card. */
    var handRowTopPct: Float = 0.66f
    /** Number of cards the player should hold at the very start of the
     *  hand.  Classic 3-player 斗地主 = 17; 4-player variants = 25 or 13.
     *  Populated from `gameconfiguration.json:hand_card_nums`.  Used by
     *  [NativeYoloPipeline] to set a *minimum* hand-cluster size so that
     *  an opponent's 7-card 顺子 doesn't get misclassified as "my hand". */
    var expectedHandCards: Int = 17
    /** Regions for the two opponent-count OCR strips (opponent head-shots). */
    var leftOpponentRect: PctRect  = PctRect(0.02f, 0.18f, 0.05f, 0.45f)
    var rightOpponentRect: PctRect = PctRect(0.02f, 0.18f, 0.55f, 0.95f)
    /** 底牌 (landlord-bottom-cards banner shown right after the deal). */
    var landlordCardsRect: PctRect = PctRect(0.05f, 0.20f, 0.30f, 0.70f)

    init { parseAssets() }

    private fun parseAssets() {
        // 1) gameconfiguration.json — mostly the 3-player/4-player rule map.
        runCatching {
            ctx.assets.open("gameconfiguration.json").bufferedReader().use { r ->
                val arr = JSONArray(r.readText())
                val threeP = (0 until arr.length()).map { arr.getJSONObject(it) }
                    .firstOrNull { it.optString("key") == "doudizhu_3" }
                if (threeP != null) {
                    val hands = threeP.optInt("hand_card_nums", -1)
                    if (hands in 10..40) expectedHandCards = hands
                    Log.i(TAG, "Loaded gameConfig: ${threeP.optString("name")} " +
                        "hand=$expectedHandCards total=${threeP.optInt("total_cards")}")
                }
            }
        }

        // 2) screenAdaptions.json — has hand row percentages.
        runCatching {
            ctx.assets.open("screenAdaptions.json").bufferedReader().use { r ->
                val root = JSONObject(r.readText())
                val hands = root.optJSONObject("handsArea")
                val default = hands?.optJSONArray("default")
                if (default != null && default.length() >= 4) {
                    // Format: [top, bottom, left, right] — "66%" / "max" strings
                    val top = pctToFloat(default.optString(0))
                    if (top > 0.0f) handRowTopPct = top
                }
            }
        }
        Log.i(TAG, "handRowTopPct=${(handRowTopPct*100).toInt()}%")
    }

    private fun pctToFloat(s: String): Float {
        val v = s.trim()
        if (v == "max") return 1f
        return v.removeSuffix("%").toFloatOrNull()?.div(100f) ?: 0f
    }

    companion object {
        private const val TAG = "ScreenAdaptation"
        val instance: ScreenAdaptation by lazy {
            ScreenAdaptation(JiPaiQiApp.instance.applicationContext)
        }
    }
}
