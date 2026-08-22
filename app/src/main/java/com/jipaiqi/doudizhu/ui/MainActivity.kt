package com.jipaiqi.doudizhu.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jipaiqi.doudizhu.JiPaiQiApp
import com.jipaiqi.doudizhu.LogStore
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
        LogStore.recordBootPath(bootLog)
        fun appendBoot(msg: String) {
            LogStore.append("[MainActivity] $msg")
            runCatching { bootLog.appendText("[${java.util.Date()}] MainActivity: $msg\n") }
            runCatching {
                java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    ), "jipaiqi_boot_log.txt"
                ).appendText("[${java.util.Date()}] MainActivity: $msg\n")
            }
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
            b.btnViewLog.setOnClickListener { showLogDialog() }
            appendBoot("Buttons wired")

            // Lazy-init the engine ASYNCHRONOUSLY on a background thread.
            // The original 王者记牌器 native YOLO (`libyolov8ncnn.so`) can
            // hard-crash the process on HarmonyOS 7.0 if loaded on the UI
            // thread — Init() may SIGSEGV on an incompatible ABI / missing
            // dependency.  Running it off-thread means the worst case is a
            // background crash that Android recovers from, while the UI
            // still comes up so the user can hit "查看调试日志" to see why
            // the recognizer never came online.
            Thread {
                runCatching {
                    (application as JiPaiQiApp).core.ensureReady()
                    appendBoot("core.ensureReady() returned (background)")
                    runOnUiThread {
                        appendBoot("refreshModelStatus on main")
                        refreshModelStatus()
                    }
                }.onFailure { ex ->
                    appendBoot("core.ensureReady() FAILED: ${ex.javaClass.name}: ${ex.message}")
                    val sw = java.io.StringWriter()
                    ex.printStackTrace(java.io.PrintWriter(sw))
                    if (sw.toString().isNotEmpty()) appendBoot(sw.toString())
                }
            }.apply { isDaemon = true; name = "core-ensureReady" }.start()
            appendBoot("=== onCreate exit OK ===")
        } catch (t: Throwable) {
            appendBoot("onCreate THREW: ${t.javaClass.name}: ${t.message}")
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            appendBoot(sw.toString())
            throw t
        }
    }

    /**
     * In-app log viewer.  Renders the in-memory LogStore ring buffer (plus
     * any on-disk boot/crash/logcat files that exist) into a scrollable
     * TextView with a "复制日志" button.  This exists precisely so the
     * user doesn't have to hunt for `/sdcard/Download/` or
     * `Android/data/<pkg>/cache/` — on HarmonyOS 7.0 those paths are
     * invisible to normal file managers, so the only reliable way to get
     * triage data out is to surface it inside the app itself.
     */
    private fun showLogDialog() {
        val content = LogStore.snapshotWithFiles()
        val tv = TextView(this).apply {
            text = content
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFFEEEEEE.toInt())
            setPadding(48, 32, 48, 32)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("调试日志  (${content.length} chars)")
            .setView(scroll)
            .setPositiveButton("复制日志") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE)
                    as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("jipaiqi_log", content))
                Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("刷新") { _, _ -> showLogDialog() }
            .setNegativeButton("关闭", null)
            .show()
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
