package com.jipaiqi.doudizhu.ai

import android.content.Context
import android.graphics.Rect
import com.jipaiqi.doudizhu.JiPaiQiApp
import com.jipaiqi.doudizhu.util.DLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Config for how to carve up a single captured frame of the host Dou Dizhu
 * app into logical regions.  Mirrors the original 王者记牌器
 * `assets/screenAdaptions.json` / `assets/gameconfiguration.json` files
 * which are bundled directly from the original APK.
 *
 * All percentages are 0..1 ratios of the **portrait** frame dimensions.
 *
 * 2.1.9 新增：支持按 [GamePlatform] 重载屏幕各区域百分比。
 *   - 默认：自动从 [GamePlatform.current] 读取（优先用户保存，否则按当前前台包名匹配，最后兜底 GENERIC=66%）
 *   - 用户在设置页切换平台时 → 调 [applyPlatform] 立即生效，pipeline 下一帧就用新的 handRowTopPct
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
        private set
    /** Number of cards the player should hold at the very start of the
     *  hand.  Classic 3-player 斗地主 = 17; 4-player variants = 25 or 13.
     *  Populated from `gameconfiguration.json:hand_card_nums`.  Used by
     *  [NativeYoloPipeline] to set a *minimum* hand-cluster size so that
     *  an opponent's 7-card 顺子 doesn't get misclassified as "my hand". */
    var expectedHandCards: Int = 17
        private set
    /** Regions for the two opponent-count OCR strips (opponent head-shots). */
    var leftOpponentRect: PctRect  = PctRect(0.02f, 0.18f, 0.05f, 0.45f)
        private set
    var rightOpponentRect: PctRect = PctRect(0.02f, 0.18f, 0.55f, 0.95f)
        private set
    /** 底牌 (landlord-bottom-cards banner shown right after the deal). */
    var landlordCardsRect: PctRect = PctRect(0.05f, 0.20f, 0.30f, 0.70f)
        private set

    /** 当前生效的平台（用户/自动识别结果） */
    var currentPlatform: GamePlatform = GamePlatform.DEFAULT
        private set

    init { parseAssets(); applyPlatform(GamePlatform.current(ctx), silent = true) }

    /**
     * 切换平台（设置页里用户点选 / 启动时自动按包名匹配）。
     * 返回 true 表示真的变更过（调用者可据此把 pipeline 清状态）。
     */
    fun applyPlatform(p: GamePlatform, silent: Boolean = false): Boolean {
        val changed = currentPlatform != p
        currentPlatform = p
        // 平台值优先覆盖 JSON 默认值
        handRowTopPct = p.handRowTopPct
        leftOpponentRect = p.leftOpponentRect
        rightOpponentRect = p.rightOpponentRect
        landlordCardsRect = p.landlordCardsRect
        if (p.expectedHandCards in 5..30) expectedHandCards = p.expectedHandCards
        if (!silent) {
            DLog.i(TAG, "applyPlatform: ${p.displayName} " +
                    "handRowTopPct=${(handRowTopPct*100).toInt()}% " +
                    "expectedHandCards=$expectedHandCards (changed=$changed)")
        }
        return changed
    }

    /* -------------------- assets parse (兜底 + 原版规则) -------------------- */

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
                    DLog.i(TAG, "Loaded gameConfig: ${threeP.optString("name")} " +
                        "hand=$expectedHandCards total=${threeP.optInt("total_cards")}")
                }
            }
        }.onFailure { DLog.w(TAG, "gameconfiguration.json parse fail", it) }

        // 2) screenAdaptions.json — has hand row percentages.
        runCatching {
            ctx.assets.open("screenAdaptions.json").bufferedReader().use { r ->
                val root = JSONObject(r.readText())
                val hands = root.optJSONObject("handsArea")
                val default = hands?.optJSONArray("default")
                if (default != null && default.length() >= 4) {
                    val top = pctToFloat(default.optString(0))
                    if (top > 0.0f) handRowTopPct = top
                }
            }
        }.onFailure { DLog.w(TAG, "screenAdaptions.json parse fail", it) }
        DLog.i(TAG, "assets defaults: handRowTopPct=${(handRowTopPct*100).toInt()}%")
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
