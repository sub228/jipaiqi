package com.jipaiqi.doudizhu.ai

import android.content.Context
import com.jipaiqi.doudizhu.util.DLog

/**
 * 游戏平台 → 记牌区域百分比配置。
 *
 * 每个平台（欢乐斗地主/微乐斗地主/JJ斗地主等）的界面布局不同：
 *   - 手牌区距离顶部的高度（handRowTopPct）
 *   - 左右对手头像&剩余牌数标签位置（leftOpponentRect / rightOpponentRect）
 *   - 地主底牌条位置
 *   - 默认牌型（3人/4人/跑得快等）
 *
 * 原版 apk 中的 screenAdaptions.json 包含 `appCategories` 字段，
 * 以「手牌 17 张 + 对手牌数框位置」联合特征来**自动识别**当前运行的平台。
 * 复刻版为了稳定起见，**默认全自动**，同时允许用户在设置页手动指定。
 */
enum class GamePlatform(
    val displayName: String,
    val packageCandidates: List<String>,
    val handRowTopPct: Float,
    val leftOpponentRect: ScreenAdaptation.PctRect,
    val rightOpponentRect: ScreenAdaptation.PctRect,
    val landlordCardsRect: ScreenAdaptation.PctRect,
    val expectedHandCards: Int = 17,
    val description: String = ""
) {
    /** 腾讯 欢乐斗地主（用户截图就是这个），界面最宽，手牌 y 最大 */
    HLDDZ(
        displayName = "欢乐斗地主 (腾讯)",
        packageCandidates = listOf(
            "com.qqgame.hlddz",
            "com.tencent.tmgp.doudizhu",
            "com.tencent.ldder"
        ),
        handRowTopPct = 0.66f,
        leftOpponentRect = ScreenAdaptation.PctRect(0.02f, 0.20f, 0.02f, 0.30f),
        rightOpponentRect = ScreenAdaptation.PctRect(0.02f, 0.20f, 0.70f, 0.98f),
        landlordCardsRect = ScreenAdaptation.PctRect(0.28f, 0.42f, 0.30f, 0.70f),
        description = "欢乐斗地主经典新手场/不洗牌/天地癞子 默认通用"
    ),

    /** 微乐斗地主 — 牌稍靠上 */
    WEILE(
        displayName = "微乐斗地主",
        packageCandidates = listOf("com.weile.ddz", "com.wisdom.ddz.game"),
        handRowTopPct = 0.62f,
        leftOpponentRect = ScreenAdaptation.PctRect(0.03f, 0.22f, 0.03f, 0.35f),
        rightOpponentRect = ScreenAdaptation.PctRect(0.03f, 0.22f, 0.65f, 0.97f),
        landlordCardsRect = ScreenAdaptation.PctRect(0.30f, 0.44f, 0.25f, 0.75f)
    ),

    /** JJ 斗地主 (存在于原版 appCategories.cards_17 -> jjdoudizhu) */
    JJ(
        displayName = "JJ斗地主",
        packageCandidates = listOf("cn.jj", "cn.jj.client", "com.jj.games.ddz"),
        handRowTopPct = 0.66f,
        leftOpponentRect = ScreenAdaptation.PctRect(0.02f, 0.18f, 0.05f, 0.45f),
        rightOpponentRect = ScreenAdaptation.PctRect(0.02f, 0.18f, 0.55f, 0.95f),
        landlordCardsRect = ScreenAdaptation.PctRect(0.05f, 0.20f, 0.30f, 0.70f),
        description = "原版 screenAdaptions.json appCategories['cards_17'] 定义"
    ),

    /** 途游斗地主 */
    TUYOU(
        displayName = "途游斗地主",
        packageCandidates = listOf("com.tuyoogame.ddz", "com.youxi.ddz"),
        handRowTopPct = 0.64f,
        leftOpponentRect = ScreenAdaptation.PctRect(0.02f, 0.18f, 0.05f, 0.40f),
        rightOpponentRect = ScreenAdaptation.PctRect(0.02f, 0.18f, 0.60f, 0.95f),
        landlordCardsRect = ScreenAdaptation.PctRect(0.28f, 0.45f, 0.30f, 0.70f)
    ),

    /** 抖音小程序 / 抖音极速斗地主 */
    DY_MINI(
        displayName = "抖音斗地主 (小程序/APP)",
        packageCandidates = listOf("com.ss.android.ugc.aweme", "com.douyin.ddzmini"),
        handRowTopPct = 0.68f,
        leftOpponentRect = ScreenAdaptation.PctRect(0.05f, 0.20f, 0.05f, 0.38f),
        rightOpponentRect = ScreenAdaptation.PctRect(0.05f, 0.20f, 0.62f, 0.95f),
        landlordCardsRect = ScreenAdaptation.PctRect(0.30f, 0.46f, 0.28f, 0.72f)
    ),

    /** 微信小程序斗地主（同屏幕比例下通用） */
    WX_MINI(
        displayName = "微信斗地主 (小程序)",
        packageCandidates = listOf("com.tencent.mm"),
        handRowTopPct = 0.68f,
        leftOpponentRect = ScreenAdaptation.PctRect(0.04f, 0.20f, 0.04f, 0.38f),
        rightOpponentRect = ScreenAdaptation.PctRect(0.04f, 0.20f, 0.62f, 0.96f),
        landlordCardsRect = ScreenAdaptation.PctRect(0.32f, 0.48f, 0.28f, 0.72f)
    ),

    /** 通用（按原版 assets/screenAdaptions.json 的 handsArea.default=66%）—— 默认兜底 */
    GENERIC(
        displayName = "通用 (自动识别, 默认)",
        packageCandidates = emptyList(),
        handRowTopPct = 0.66f,
        leftOpponentRect = ScreenAdaptation.PctRect(0.02f, 0.18f, 0.05f, 0.45f),
        rightOpponentRect = ScreenAdaptation.PctRect(0.02f, 0.18f, 0.55f, 0.95f),
        landlordCardsRect = ScreenAdaptation.PctRect(0.05f, 0.20f, 0.30f, 0.70f),
        description = "按原版 JSON 默认配置；如果你的平台不在上方列表，请选这个并反馈"
    );

    companion object {
        private const val SP_KEY = "selected_game_platform_v219"
        private const val SP_NAME = "jipaiqi_settings"
        val DEFAULT = GENERIC

        fun current(ctx: Context): GamePlatform {
            val sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            val saved = sp.getString(SP_KEY, null)?.takeIf { it.isNotBlank() }
            if (saved != null) {
                val v = runCatching { valueOf(saved) }.getOrNull()
                if (v != null) return v
            }
            // 1) 没保存过时，尝试按前台 app 包名匹配
            val pkg = currentForegroundPackage(ctx)
            val matched = values().firstOrNull {
                it.packageCandidates.any { c -> pkg?.startsWith(c) == true || c == pkg }
            }
            return matched ?: DEFAULT
        }

        fun save(ctx: Context, p: GamePlatform) {
            ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit().putString(SP_KEY, p.name).apply()
            DLog.i("GamePlatform", "user set platform=${p.name} (${p.displayName})")
        }

        private fun currentForegroundPackage(ctx: Context): String? {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: return null
            val tasks = runCatching { am.getRunningTasks(1) }.getOrNull()
            val top = tasks?.firstOrNull()?.topActivity ?: return null
            return top.packageName
        }
    }
}
