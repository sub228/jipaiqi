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

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val pos = currentRole()
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
        val bootLog = java.io.File(externalCacheDir ?: cacheDir, "boot_log.txt")
        fun appendBoot(msg: String) {
            runCatching { bootLog.appendText("[${java.util.Date()}] MainActivity: $msg\n") }
            android.util.Log.i("MainActivity", msg)
        }
        appendBoot("=== onCreate enter ===")
        try {
            b = ActivityMainBinding.inflate(layoutInflater)
            appendBoot("ActivityMainBinding inflated")
            setContentView(b.root)
            appendBoot("setContentView OK")

            b.roleGroup.check(R.id.roleLandlord)
            b.btnStart.setOnClickListener { onStartClicked() }
            b.btnStop.setOnClickListener { onStopClicked() }
            appendBoot("Buttons wired")

            // Lazy-init the engine in background so the first click is fast.
            (application as JiPaiQiApp).core.ensureReady()
            appendBoot("core.ensureReady() returned")
            refreshModelStatus()
            appendBoot("=== onCreate exit OK ===")
        } catch (t: Throwable) {
            appendBoot("onCreate THREW: ${t.javaClass.name}: ${t.message}")
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            appendBoot(sw.toString())
            throw t
        }
    }

    private fun onStartClicked() {
        val core = (application as JiPaiQiApp).core
        core.state.setMyPosition(currentRole())
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

    private fun currentRole(): Position {
        return when (b.roleGroup.checkedButtonId) {
            R.id.roleUp -> Position.LANDLORD_UP
            R.id.roleDown -> Position.LANDLORD_DOWN
            else -> Position.LANDLORD
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
            // PREFERENCE: original-native-YOLO ✓ > custom-ONNX-YOLO ✓ > none
            val yoloBadge = when {
                core.nativeYoloReady -> "✓(原版NCNN)"
                core.yolo != null   -> "✓(ONNX)"
                else                -> "✗"
            }
            val txt = buildString {
                append("模型：DouZero ${hasDou}/3  ·  YOLO ").append(yoloBadge)
                if (!core.nativeYoloReady && core.yolo == null) append("  (纯 OCR 模式)")
            }
            b.modelStatus.text = txt
            if (hasDou == 0 && !core.nativeYoloReady && core.yolo == null) {
                Toast.makeText(this@MainActivity,
                    getString(R.string.no_models_msg), Toast.LENGTH_LONG).show()
            }
        }
    }
}
