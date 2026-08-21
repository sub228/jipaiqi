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
 *   - Opponents' remaining card counts ( landlord=20 - played, farmer=17 - played )
 *   - One-line DouZero AI suggestion (e.g. "AI: K K K (3)")
 *
 * The user can drag the panel anywhere on screen. The close button stops
 * the floating service (but leaves the ScreenCaptureService running until
 * the user explicitly stops it from the main UI).
 */
class FloatingWindowService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var binding: FloatingPanelBinding? = null

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
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 240
        }

        val b = FloatingPanelBinding.inflate(LayoutInflater.from(this))
        binding = b
        rootView = b.root

        // Drag handle (touch + drag anywhere on the panel except the close button).
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
                        windowManager?.updateViewLayout(b.root, params)
                    }
                }
            }
            false
        }
        b.closeBtn.setOnClickListener { stopSelf() }
        windowManager?.addView(b.root, params)
    }

    /** Refresh the displayed counts + AI line from [JiPaiQiApp.Core]. */
    private fun refresh() {
        val b = binding ?: return
        val core = (application as JiPaiQiApp).core
        val snapshot = runCatching { core.state.toInfoSet() }.getOrNull() ?: return

        // Update role text.
        b.roleText.text = "— " + when (snapshot.playerPosition) {
            Position.LANDLORD -> "地主"
            Position.LANDLORD_UP -> "农民(上)"
            Position.LANDLORD_DOWN -> "农民(下)"
        }

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

        // Opponents line.
        val left = snapshot.numCardsLeft
        b.opponentsLine.text = "对手剩：${left[Position.LANDLORD_UP]} / ${left[Position.LANDLORD_DOWN]}" +
            if (snapshot.playerPosition == Position.LANDLORD) "" else " / 地:${left[Position.LANDLORD]}"

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
            mainHandler.post { b.aiLine.text = line }
        }
    }

    private fun formatRec(rec: DouZeroEngine.Recommendation): String {
        if (rec.action.isEmpty()) return "AI：过 (pass)"
        val label = rec.action.joinToString(" ") { Card.label(it) }
        val src = when (rec.source) {
            DouZeroEngine.Source.MODEL -> "AI"
            DouZeroEngine.Source.HEURISTIC -> "启发"
            DouZeroEngine.Source.PASS -> "过"
        }
        return "$src：$label"
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
