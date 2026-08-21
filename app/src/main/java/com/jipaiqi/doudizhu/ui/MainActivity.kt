package com.jipaiqi.doudizhu.ui

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jipaiqi.doudizhu.JiPaiQiApp
import com.jipaiqi.doudizhu.R
import com.jipaiqi.doudizhu.ai.Position
import com.jipaiqi.doudizhu.databinding.ActivityMainBinding
import com.jipaiqi.doudizhu.service.FloatingWindowService
import com.jipaiqi.doudizhu.service.ScreenCaptureService
import kotlinx.coroutines.launch

/**
 * Control panel for the card counter. Lets the user pick their role
 * (landlord / landlord_up / landlord_down), then drives the permission
 * flow (overlay + screen capture) and starts the foreground services.
 *
 * Permission chain on "Start":
 *   1. overlay : [Settings.ACTION_MANAGE_OVERLAY_PERMISSION]
 *   2. screen  : [MediaProjectionManager.createScreenCaptureIntent]
 *   3. start   : [ScreenCaptureService.start] + [FloatingWindowService.start]
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    /** Tracks whether the user actually tapped one of the specific role buttons. */
    private var roleManuallySelected: Boolean = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // ScreenCaptureService requires a position to pass through; we use
            // the user's pick if they chose one, else the default LANDLORD.
            // The real identity is overwritten as soon as the first frame with
            // my hand is recognized via GameState.setMyHand auto-detect.
            val pos = currentRoleOrHint()
            ScreenCaptureService.start(this, result.resultCode, result.data!!, pos)
            FloatingWindowService.start(this)
            updateStatusRunning()
        } else {
            Toast.makeText(this, getString(R.string.permission_capture_msg),
                Toast.LENGTH_LONG).show()
        }
    }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // The system doesn't give a result code for overlay settings; just
        // re-check.
        if (Settings.canDrawOverlays(this)) {
            askForScreenCapture()
        } else {
            Toast.makeText(this, getString(R.string.permission_overlay_msg),
                Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.roleGroup.check(R.id.roleAuto)
        b.roleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            roleManuallySelected = checkedId != R.id.roleAuto
        }
        b.btnStart.setOnClickListener { onStartClicked() }
        b.btnStop.setOnClickListener { onStopClicked() }

        // Lazy-init the engine in background so the first click is fast.
        (application as JiPaiQiApp).core.ensureReady()
        refreshModelStatus()
    }

    private fun onStartClicked() {
        val core = (application as JiPaiQiApp).core
        // Only call setMyPosition(explicit=true) if user checked a specific
        // (non-"自动") button.  Otherwise GameState.setMyHand is free to pick
        // between 地主 / 农民 based on the recognized hand size (20 / 17).
        if (roleManuallySelected) {
            core.state.setMyPosition(currentRoleOrHint(), explicit = true)
        } else {
            // Clear the explicit flag so auto-detect by hand count wins.
            core.state.positionExplicitlySet = false
        }
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayLauncher.launch(intent)
        } else {
            askForScreenCapture()
        }
    }

    private fun onStopClicked() {
        ScreenCaptureService.stop(this)
        stopService(Intent(this, FloatingWindowService::class.java))
        updateStatusIdle()
    }

    private fun askForScreenCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun currentRoleOrHint(): Position {
        return when (b.roleGroup.checkedButtonId) {
            R.id.roleUp -> Position.LANDLORD_UP
            R.id.roleDown -> Position.LANDLORD_DOWN
            R.id.roleLandlord -> Position.LANDLORD
            else -> Position.LANDLORD  // "自动" or no selection: hint only, gets overwritten
        }
    }

    private fun updateStatusRunning() {
        b.statusLabel.text = getString(R.string.status_running)
    }
    private fun updateStatusIdle() {
        b.statusLabel.text = getString(R.string.status_idle)
    }

    private fun refreshModelStatus() {
        val core = (application as JiPaiQiApp).core
        lifecycleScope.launch {
            // Run heavy load off the main thread.
            val hasDou = runCatching { core.douZero?.let { e ->
                Position.values().count { e.hasModel(it) } } ?: 0 }.getOrDefault(0)
            val hasYolo = core.yolo != null
            val txt = buildString {
                append("模型：DouZero ${hasDou}/3  ·  YOLO ")
                append(if (hasYolo) "✓" else "✗")
                if (!hasYolo && hasDou == 0) append("  (纯 OCR 模式)")
            }
            b.modelStatus.text = txt
            if (hasDou == 0 && !hasYolo) {
                Toast.makeText(this@MainActivity,
                    getString(R.string.no_models_msg), Toast.LENGTH_LONG).show()
            }
        }
    }
}
