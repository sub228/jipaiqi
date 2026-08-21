package com.jipaiqi.doudizhu.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import com.jipaiqi.doudizhu.ai.Position
import com.jipaiqi.doudizhu.databinding.FloatingPanelBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Floating overlay service. Adds a [WindowManager] view of type
 * [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] that floats on top
 * of the running card game and shows:
 *
 *   - 15 cells, one per rank, with the remaining count outside my hand
 *   - "断张" highlight when a rank is exhausted (count == 0)
 *   - Opponents' remaining card counts
 *   - One-line DouZero AI suggestion
 *
 * UI features:
 *   - Compact panel (small cells, tight padding) to avoid blocking the card area.
 *   - Minimize button (▾/▲) collapses everything to a single small badge
 *     showing just the AI suggestion summary.
 *   - Drag anywhere on the panel to reposition it.
 *   - Default position: top-right corner, away from the card play area.
 *
 * The close button stops the floating service.
 */
class FloatingWindowService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var binding: FloatingPanelBinding? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var minimized = false
    private var lastAiSummary = "AI：等待…"

    private val updateTask = object : Runnable {
        override fun run() { refresh(); mainHandler.postDelayed(this, REFRESH_MS) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showOverlay()
        mainHandler.post(updateTask)
        (application as JiPaiQiApp).core.onStateChanged = { mainHandler.post { refresh() } }
    }

    private fun showOverlay() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Default: top-right corner to stay away from:
            //   - the "my hand" region (bottom)
            //   - the table play region (center/middle)
            gravity = Gravity.TOP or Gravity.END
            x = 12
            y = 180
        }
        layoutParams = params

        val b = FloatingPanelBinding.inflate(LayoutInflater.from(this))
        binding = b
        rootView = b.root

        // Drag handle: drag anywhere (we consume when NOT on a clickable child).
        var startX = 0; var startY = 0
        var startRawX = 0f; var startRawY = 0f
        var dragging = false
        b.root.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    startRawX = ev.rawX; startRawY = ev.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startRawX
                    val dy = ev.rawY - startRawY
                    if (dragging || abs(dx) > 8 || abs(dy) > 8) {
                        dragging = true
                        params.x = (startX + dx).toInt()
                        params.y = (startY + dy).toInt()
                        runCatching { windowManager?.updateViewLayout(b.root, params) }
                    }
                }
            }
            // Don't consume if user is tapping a button (let click fire).
            false
        }

        // Close button.
        b.closeBtn.setOnClickListener { stopSelf() }

        // Minimize / expand toggle.
        b.minimizeBtn.setOnClickListener {
            minimized = !minimized
            applyMinimizedState()
        }

        windowManager?.addView(b.root, params)
    }

    private fun applyMinimizedState() {
        val b = binding ?: return
        if (minimized) {
            // Collapsed: hide content group, show AI summary in the title
            b.contentGroup.visibility = View.GONE
            b.minimizeBtn.text = "▲"
            // Use the role text area as the summary line.
            b.roleText.text = lastAiSummary
            b.roleText.setTextSize(9f)  // slightly bigger in badge mode
        } else {
            // Expanded: show everything
            b.contentGroup.visibility = View.VISIBLE
            b.minimizeBtn.text = "▾"
            // Refresh the correct role text next cycle (refresh() sets it).
            refresh()
        }
    }

    /** Refresh the displayed counts + AI line from [JiPaiQiApp.Core]. */
    private fun refresh() {
        val b = binding ?: return
        val core = (application as JiPaiQiApp).core
        val snapshot = runCatching { core.state.toInfoSet() }.getOrNull() ?: return

        // Update role text (in expanded mode).
        val roleLabel = "— " + when (snapshot.playerPosition) {
            Position.LANDLORD -> "地主"
            Position.LANDLORD_UP -> "农民(上)"
            Position.LANDLORD_DOWN -> "农民(下)"
        }
        if (!minimized) b.roleText.text = roleLabel

        // Update rank grid: remaining = total - myHand - played_all.
        val myHand = snapshot.playerHandCards.toSet()
        val allPlayed = snapshot.playedCards.values.flatten()
        for (rank in Card.ALL_RANKS) {
            val total = Card.TOTAL[rank]!!
            val used = myHand.count { it == rank } + allPlayed.count { it == rank }
            val remaining = (total - used).coerceAtLeast(0)
            val cell = cellForRank(b, rank) ?: continue
            cell.rankLabel.text = Card.label(rank)
            cell.countText.text = if (remaining == 0) "—" else remaining.toString()
            cell.rootView.setBackgroundResource(
                if (remaining == 0) R.drawable.floating_cell_bg_done
                else R.drawable.floating_cell_bg
            )
        }

        // Opponents line (expanded mode only — already handled in summary when minimized).
        val left = snapshot.numCardsLeft
        val mySize = snapshot.playerHandCards.size
        val meLabel = when (snapshot.playerPosition) {
            Position.LANDLORD -> "地主我"
            Position.LANDLORD_UP -> "上(我)"
            Position.LANDLORD_DOWN -> "下(我)"
        }
        val oppLine = if (snapshot.playerPosition == Position.LANDLORD) {
            "我${mySize}·上${left[Position.LANDLORD_UP]} / 下${left[Position.LANDLORD_DOWN]}"
        } else {
            val otherFarmer = if (snapshot.playerPosition == Position.LANDLORD_UP)
                Position.LANDLORD_DOWN else Position.LANDLORD_UP
            "${meLabel}${mySize}·地${left[Position.LANDLORD]} / 农${left[otherFarmer]}"
        }
        if (!minimized) b.opponentsLine.text = oppLine

        // AI recommendation (off-main thread, then update text).
        scope.launch {
            val rec: DouZeroEngine.Recommendation? = try {
                if (core.modelsPresent) core.douZero?.recommend(snapshot)
                else withContext(Dispatchers.Default) {
                    com.jipaiqi.doudizhu.ai.heuristicBestSingle(snapshot)
                }
            } catch (e: Exception) {
                Log.w(TAG, "AI recommend failed: ${e.message}"); null
            }
            val line = rec?.let { formatRec(it) } ?: "AI：等待识别手牌…"
            lastAiSummary = line
            mainHandler.post {
                if (minimized) {
                    // Show AI summary in the badge (role text position).
                    b.roleText.text = line
                } else {
                    b.aiLine.text = line
                }
            }
        }
    }

    private fun formatRec(rec: DouZeroEngine.Recommendation): String {
        if (rec.action.isEmpty()) return "AI：过"
        val label = rec.action.joinToString("") { Card.label(it) }
        val src = when (rec.source) {
            DouZeroEngine.Source.MODEL -> "AI"
            DouZeroEngine.Source.HEURISTIC -> "启发"
            DouZeroEngine.Source.PASS -> "过"
        }
        val n = rec.action.size
        return "$src：$label(${n}张)"
    }

    private fun cellForRank(b: FloatingPanelBinding, rank: Int): CellViews? {
        val id = when (rank) {
            Card.R3 -> R.id.cell_3
            Card.R4 -> R.id.cell_4
            Card.R5 -> R.id.cell_5
            Card.R6 -> R.id.cell_6
            Card.R7 -> R.id.cell_7
            Card.R8 -> R.id.cell_8
            Card.R9 -> R.id.cell_9
            Card.R10 -> R.id.cell_10
            Card.RJ -> R.id.cell_J
            Card.RQ -> R.id.cell_Q
            Card.RK -> R.id.cell_K
            Card.RA -> R.id.cell_A
            Card.R2 -> R.id.cell_2
            Card.BJ -> R.id.cell_BJ
            Card.RJOKER -> R.id.cell_RJ
            else -> return null
        }
        val cell = b.root.findViewById<LinearLayout>(id) ?: return null
        return CellViews(
            rootView = cell,
            rankLabel = cell.findViewById(R.id.rankLabel) ?: return null,
            countText = cell.findViewById(R.id.countText) ?: return null,
        )
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

        fun start(context: Context) {
            context.startService(Intent(context, FloatingWindowService::class.java))
        }
    }
}
