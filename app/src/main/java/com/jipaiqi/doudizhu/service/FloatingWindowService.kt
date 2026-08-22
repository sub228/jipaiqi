package com.jipaiqi.doudizhu.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.jipaiqi.doudizhu.JiPaiQiApp
import com.jipaiqi.doudizhu.R
import com.jipaiqi.doudizhu.ai.Card
import com.jipaiqi.doudizhu.ai.DouZeroEngine
import com.jipaiqi.doudizhu.ai.InfoSetSnapshot
import com.jipaiqi.doudizhu.ai.MoveDetector
import com.jipaiqi.doudizhu.ai.MoveType
import com.jipaiqi.doudizhu.ai.Position
import com.jipaiqi.doudizhu.databinding.FloatingCellBinding
import com.jipaiqi.doudizhu.databinding.FloatingPanelBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Floating overlay service. Adds a [WindowManager] view of type
 * [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] that faithfully
 * replicates the original 记牌器 app's floating panel:
 *
 *   - A dark rounded bar with: settings gear (toggles the function row),
 *     3 recording status dots (auth / frame / analyze), and either the
 *     "等待牌局正式开始" placeholder or the 15-rank counter grid
 *     (rank label / divider / remaining count per rank).
 *   - A function-pill row (AI建议 / 出牌历史 / 重置辅助 / 防作弊 / 缩小 / 退出).
 *   - A circular manual-reset button (toggled by 重置辅助).
 *   - An AI建议 overlay with up to 3 output lines (DouZero recommendation).
 *
 * The user can drag the panel anywhere on screen. 缩小 collapses it to a
 * small "显示浮窗" eye button; tapping that expands it back.
 */
class FloatingWindowService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var binding: FloatingPanelBinding? = null
    private var params: WindowManager.LayoutParams? = null
    private val cells = HashMap<Int, CellViews>()
    private var vibrator: Vibrator? = null

    /** Toggle states for the function pills (true = ON, checkmark shown). */
    private var aiPanelOn = true
    private var historyOn = true
    private var resetOn = false
    private var antiCheatOn = true

    private val updateTask = object : Runnable {
        override fun run() { refresh(); mainHandler.postDelayed(this, REFRESH_MS) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        showOverlay()
        mainHandler.post(updateTask)
        (application as JiPaiQiApp).core.onStateChanged = { mainHandler.post { refresh() } }
    }

    private fun showOverlay() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 240
        }
        params = p

        val b = FloatingPanelBinding.inflate(LayoutInflater.from(this))
        binding = b
        rootView = b.root

        inflateRankCells(b)
        wireDragHandle(b, p)
        wireButtons(b)

        windowManager?.addView(b.root, p)
        // Apply default toggle states so the UI matches the original (AI建议 +
        // 防作弊 on by default; their panels show on first refresh).
        applyToggleStates(b)
    }

    /** Inflate the 15 rank cells (3..2, BJ, RJ) into the numList container. */
    private fun inflateRankCells(b: FloatingPanelBinding) {
        val inflater = LayoutInflater.from(this)
        b.numList.removeAllViews()
        cells.clear()
        for (rank in Card.ALL_RANKS) {
            val cell = FloatingCellBinding.inflate(inflater, b.numList, false).root
            val label = cell.findViewById<TextView>(R.id.rankLabel)
            val count = cell.findViewById<TextView>(R.id.countText)
            label.text = Card.label(rank)
            // Initial count = full deck count per rank (4 for ranks, 1 for jokers).
            // Matches the original APK's "fresh deal" display before any card
            // has been recognized — counters then decrement as cards land.
            count.text = Card.TOTAL[rank].toString()
            b.numList.addView(cell)
            cells[rank] = CellViews(cell, label, count)
        }
    }

    /**
     * Drag handle: touch + drag anywhere on the panel. A tap (no movement)
     * falls through to child click handlers so the buttons still work.
     */
    private fun wireDragHandle(b: FloatingPanelBinding, p: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0
        var startRawX = 0f; var startRawY = 0f
        var dragging = false
        b.root.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x; startY = p.y
                    startRawX = ev.rawX; startRawY = ev.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startRawX
                    val dy = ev.rawY - startRawY
                    if (dragging || abs(dx) > DRAG_SLOPE || abs(dy) > DRAG_SLOPE) {
                        dragging = true
                        p.x = (startX + dx).toInt()
                        p.y = (startY + dy).toInt()
                        windowManager?.updateViewLayout(b.root, p)
                    }
                }
            }
            false   // let child views receive click events
        }
    }

    /** Wire up every pill button + the settings gear + collapsed expander. */
    private fun wireButtons(b: FloatingPanelBinding) {
        // Settings gear -> toggle the function-pill row.
        b.setting.setOnClickListener { toggle(b.showset) }

        b.llHead6.setOnClickListener {      // AI建议
            aiPanelOn = !aiPanelOn
            applyToggleStates(b); refresh()
        }
        b.llHead1.setOnClickListener {      // 出牌历史
            historyOn = !historyOn
            applyToggleStates(b); refresh()
        }
        b.llHead5.setOnClickListener {      // 重置辅助
            resetOn = !resetOn
            applyToggleStates(b)
        }
        b.llHead8.setOnClickListener {      // 防作弊
            antiCheatOn = !antiCheatOn
            applyToggleStates(b)
        }
        b.llHead4.setOnClickListener { shrink(b) }    // 缩小
        b.llHead7.setOnClickListener { stopSelf() }   // 退出

        // Collapsed "显示浮窗" -> expand back.
        b.popWindow.setOnClickListener { expand(b) }
        b.tvXuanfu.setOnClickListener { expand(b) }

        // Manual reset circle -> reset game state.
        b.manualResetBtn.setOnClickListener {
            val core = (application as JiPaiQiApp).core
            core.state.newGame()
            core.pipeline?.reset()
            refresh()
        }
    }

    /** Reflect [aiPanelOn]/[historyOn]/[resetOn]/[antiCheatOn] in the UI. */
    private fun applyToggleStates(b: FloatingPanelBinding) {
        b.ivHead6.visibility = if (aiPanelOn) View.VISIBLE else View.GONE
        b.ivHead1.visibility = if (historyOn) View.VISIBLE else View.GONE
        b.ivHead5.visibility = if (resetOn) View.VISIBLE else View.GONE
        b.ivHead8.visibility = if (antiCheatOn) View.VISIBLE else View.GONE
        b.aiMessage.visibility = if (aiPanelOn) View.VISIBLE else View.GONE
        b.showLogList.visibility = if (historyOn) View.VISIBLE else View.GONE
        b.manualResetBtn.visibility = if (resetOn) View.VISIBLE else View.GONE
    }

    private fun shrink(b: FloatingPanelBinding) {
        b.showset.visibility = View.GONE
        b.mainBar.visibility = View.GONE
        b.popWindow.visibility = View.VISIBLE
    }

    private fun expand(b: FloatingPanelBinding) {
        b.popWindow.visibility = View.GONE
        b.mainBar.visibility = View.VISIBLE
    }

    private fun toggle(v: View) {
        v.visibility = if (v.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    /** Refresh the displayed counts, status dots, history, and AI lines. */
    private fun refresh() {
        val b = binding ?: return
        val core = (application as JiPaiQiApp).core
        val snapshot = runCatching { core.state.toInfoSet() }.getOrNull() ?: return

        updateStatusDots(b, core)

        val hasHand = snapshot.playerHandCards.isNotEmpty()
        // (v2.2.4) 始终显示记牌条 (ll_num_list) 与分隔线 (line)，与原版
        //   APK 行为一致。无手牌时显示满牌数；手牌/出牌识别后自动递减。
        //   不再在 "等待牌局正式开始" 与 "记牌条" 之间二选一。
        b.nostart.visibility = View.GONE
        b.llNumList.visibility = View.VISIBLE
        b.line.visibility = View.VISIBLE

        // ── Diagnostic overlay: surface the last NCNN frame statistics.
        //    放在 3 个指示灯下方的 record_status_text（原本显示 "异常"
        //    的小文字），不挤占记牌条空间；有手牌时保留 last 状态不自动隐藏。
        runCatching {
            val statusTv = b.recordStatusText
            val detCnt = ScreenCaptureService.sLastFrameDetections
            val handCnt = ScreenCaptureService.sLastFrameHandCount
            val prefix: String = when {
                hasHand -> "已识别${snapshot.playerHandCards.size}张"
                detCnt >= 20 -> "NCNN=${detCnt}框/聚类中…"
                detCnt > 0 -> "NCNN=${detCnt}框/handCnt=$handCnt…"
                core.nativeYoloReady -> "原版NCNN已就绪(等发牌或出牌画面)…"
                else -> "识别核心未就绪…"
            }
            statusTv.text = prefix
            statusTv.setTextColor(0xFF58E882.toInt())   // 绿色，与原版一致
            statusTv.textSize = 9f                       // 8sp → 9sp，看得清
            statusTv.visibility = View.VISIBLE
        }

        // Remaining = total - myHand - played_all, per rank.
        // NOTE: don't collapse to Set — duplicate ranks must be counted.
        val myHandCounts = HashMap<Int, Int>()
        for (c in snapshot.playerHandCards) myHandCounts[c] = (myHandCounts[c] ?: 0) + 1
        val allPlayed = snapshot.playedCards.values.flatten()
        val playedCounts = HashMap<Int, Int>()
        for (c in allPlayed) playedCounts[c] = (playedCounts[c] ?: 0) + 1
        for (rank in Card.ALL_RANKS) {
            val total = Card.TOTAL[rank]!!
            val used = (myHandCounts[rank] ?: 0) + (playedCounts[rank] ?: 0)
            val remaining = (total - used).coerceAtLeast(0)
            val cell = cells[rank] ?: continue
            cell.countText.text = if (remaining == 0) "0" else remaining.toString()
            cell.countText.setTextColor(getColor(if (remaining == 0) R.color.bad else R.color.text_primary))
        }

        updateHistory(b, snapshot)
        updateAiPanel(b, snapshot, core)

        // 防作弊: buzz when an opponent's remaining count hits a suspicious value.
        if (antiCheatOn) checkAntiCheat(snapshot)
    }

    /** auth=overlay granted, frame=capture running, analyze=pipeline active. */
    private fun updateStatusDots(b: FloatingPanelBinding, core: JiPaiQiApp.Core) {
        val authOk = Settings.canDrawOverlays(this)
        val frameOk = core.ready
        val analyzeOk = core.pipeline != null && core.ready
        b.recordAuthIndicator.setBackgroundResource(
            if (authOk) R.drawable.floating_record_indicator_dot_ok
            else R.drawable.floating_record_indicator_dot_err
        )
        b.recordFrameIndicator.setBackgroundResource(
            if (frameOk) R.drawable.floating_record_indicator_dot_ok
            else R.drawable.floating_record_indicator_dot
        )
        b.recordAnalyzeIndicator.setBackgroundResource(
            if (analyzeOk) R.drawable.floating_record_indicator_dot_ok
            else R.drawable.floating_record_indicator_dot
        )
        b.recordStatusText.visibility = if (authOk && frameOk) View.GONE else View.VISIBLE
    }

    /** Render the last few plays as gold-tagged history lines. */
    private fun updateHistory(b: FloatingPanelBinding, snapshot: InfoSetSnapshot) {
        val container = b.historyCards
        container.removeAllViews()
        // Use the position-tagged action sequence so we can label each line
        // with who played it (地主 / 上家 / 下家 / 我).  Falls back to the
        // plain rank-only sequence when the position info is missing.
        val seq = snapshot.cardPlayActionSeqWithPosition
            .filter { it.second.isNotEmpty() }
            .takeLast(MAX_HISTORY_LINES)
        if (seq.isEmpty()) return
        for ((position, play) in seq.asReversed()) {
            val moveType = MoveDetector.getMoveType(play).type
            val actionTag = when (moveType) {
                MoveType.BOMB, MoveType.KING_BOMB -> "炸"
                MoveType.PASS -> "过"
                else -> "出"
            }
            val who = positionLabel(position, snapshot.playerPosition)
            val label = "$who·$actionTag"
            val cards = play.joinToString(" ") { Card.label(it) }
            container.addView(buildHistoryRow(label, cards))
        }
    }

    /** Label a seat from the user's perspective (myself = 我). */
    private fun positionLabel(pos: Position, me: Position): String = when (pos) {
        me -> "我"
        Position.LANDLORD -> "地主"
        Position.LANDLORD_UP -> "上家"
        Position.LANDLORD_DOWN -> "下家"
    }

    /** One gold-tagged history row: [tag] [cards]. Matches float_history_item.xml. */
    private fun buildHistoryRow(label: String, cards: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 2 }
        }
        val tag = TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.text_primary))
            setBackgroundColor(getColor(R.color.gold_label))
            textSize = 8f
            setPadding(2, 1, 4, 1)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 4 }
        }
        val body = TextView(this).apply {
            text = cards
            setTextColor(getColor(R.color.text_primary))
            setBackgroundColor(getColor(R.color.gold_label))
            textSize = 8f
            setMaxWidth(350)
            setPadding(0, 0, 0, 0)
        }
        row.addView(tag); row.addView(body)
        return row
    }

    /** Run DouZero (or heuristic) and fill aioutput0/1/2. */
    private fun updateAiPanel(b: FloatingPanelBinding, snapshot: InfoSetSnapshot, core: JiPaiQiApp.Core) {
        if (!aiPanelOn) return
        // Snapshot the hand size on the UI thread so the async block doesn't
        // race with a state update between showing "AI正在准备中..." and
        // actually publishing the recommendation.
        val handSize = snapshot.playerHandCards.size
        if (handSize == 0) {
            b.aioutput0.text = ""
            b.aioutput1.text = "AI：等待识别手牌…"
            b.aioutput2.text = ""
            return
        }
        b.aioutput1.text = "AI正在准备中..."
        scope.launch {
            val rec: DouZeroEngine.Recommendation? = try {
                if (core.modelsPresent) core.douZero?.recommend(snapshot)
                else withContext(Dispatchers.Default) {
                    com.jipaiqi.doudizhu.ai.heuristicBestSingle(snapshot)
                }
            } catch (e: Exception) {
                Log.w(TAG, "AI recommend failed: ${e.message}"); null
            }
            mainHandler.post {
                if (rec == null) {
                    b.aioutput0.text = ""
                    b.aioutput1.text = "AI：暂无建议"
                    b.aioutput2.text = ""
                    return@post
                }
                val info = MoveDetector.getMoveType(rec.action)
                b.aioutput0.text = "推荐：" + formatAction(rec.action)
                b.aioutput1.text = "牌型：" + moveTypeLabel(info.type)
                // Score line: DouZero models emit a per-action value (higher
                // = better); show it as a 2-decimal score for parity with
                // the original APK's "评分" feature.  Heuristic/PASS paths
                // leave the score blank because there's no value to show.
                val sourceStr = when (rec.source) {
                    DouZeroEngine.Source.MODEL -> "DouZero"
                    DouZeroEngine.Source.HEURISTIC -> "启发"
                    DouZeroEngine.Source.PASS -> "过牌"
                }
                val scoreStr = if (rec.source == DouZeroEngine.Source.MODEL
                    && !rec.value.isNaN()) {
                    " 评分:" + "%.2f".format(rec.value)
                } else ""
                b.aioutput2.text = "来源：$sourceStr$scoreStr"
            }
        }
    }

    private fun formatAction(action: List<Int>): String =
        if (action.isEmpty()) "过" else action.joinToString(" ") { Card.label(it) }

    private fun moveTypeLabel(type: Int): String = when (type) {
        MoveType.PASS -> "过牌"
        MoveType.SINGLE -> "单张"
        MoveType.PAIR -> "对子"
        MoveType.TRIPLE -> "三张"
        MoveType.BOMB -> "炸弹"
        MoveType.KING_BOMB -> "王炸"
        MoveType.TRIPLE_WITH_ONE -> "三带一"
        MoveType.TRIPLE_WITH_PAIR -> "三带二"
        MoveType.SERIAL_SINGLE -> "顺子"
        MoveType.SERIAL_PAIR -> "连对"
        MoveType.SERIAL_TRIPLE -> "飞机"
        MoveType.SERIAL_3_1 -> "飞机带单"
        MoveType.SERIAL_3_2 -> "飞机带对"
        MoveType.BOMB_WITH_TWO -> "四带二"
        MoveType.BOMB_WITH_TWO_PAIRS -> "四带两对"
        else -> "未知"
    }

    /** Buzz briefly if an opponent has very few cards left (cheat-pattern alert). */
    private fun checkAntiCheat(snapshot: InfoSetSnapshot) {
        val minOpp = snapshot.numCardsLeft.filter { it.key != snapshot.playerPosition }
            .values.minOrNull() ?: return
        if (minOpp in 1..2 && System.currentTimeMillis() - lastBuzzMs > BUZZ_COOLDOWN_MS) {
            lastBuzzMs = System.currentTimeMillis()
            vibrate(40)
        }
    }

    private fun vibrate(ms: Long) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else @Suppress("DEPRECATION") v.vibrate(ms)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(updateTask)
        runCatching { rootView?.let { windowManager?.removeView(it) } }
        (application as JiPaiQiApp).core.onStateChanged = null
        scope.cancel()
        super.onDestroy()
    }

    private data class CellViews(
        val rootView: View,
        val rankLabel: TextView,
        val countText: TextView,
    )

    companion object {
        private const val TAG = "FloatingWindowService"
        private const val REFRESH_MS = 500L
        private const val DRAG_SLOPE = 8
        private const val MAX_HISTORY_LINES = 6
        private const val BUZZ_COOLDOWN_MS = 3000L
        @Volatile private var lastBuzzMs: Long = 0L

        fun start(context: Context) {
            context.startService(Intent(context, FloatingWindowService::class.java))
        }
    }
}
