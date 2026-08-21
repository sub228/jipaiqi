package com.jipaiqi.doudizhu.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TabHost
import android.widget.TabWidget
import android.widget.AdapterView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.jipaiqi.doudizhu.JiPaiQiApp
import com.jipaiqi.doudizhu.R
import com.jipaiqi.doudizhu.ai.GamePlatform
import com.jipaiqi.doudizhu.ai.ScreenAdaptation
import com.jipaiqi.doudizhu.util.DLog
import com.jipaiqi.doudizhu.util.DebugLogCollector
import com.jipaiqi.doudizhu.service.ScreenCaptureService
import java.io.File

/**
 * 「设置 + 调试日志」内嵌对话框（悬浮窗⚙️按钮长按弹出）
 *
 *  两个 Tab：
 *   Tab 1 — 🎮 游戏平台选择
 *      - 7 种常见平台单选，实时应用 (ScreenAdaptation.applyPlatform)
 *      - 每个平台显示 handRowTopPct% + 包名 + 说明
 *      - 当前选中项高亮 + 顶部 "自动识别结果：XXX" 状态行
 *
 *   Tab 2 — 📋 调试日志（APP 内置复制/分享，不用 adb）
 *      - 实时滚动文本框（最近 2000 行，每 800ms 刷新一次）
 *      - 顶部状态行：平台名 / NCNN args / detCnt / handCnt / 屏幕分辨率
 *      - 4 个按钮：复制全部 | 清空 | 分享文件 | ⚡强制触发1次检测快照
 */
class SettingsAndLogDialog(context: Context) : Dialog(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var logView: TextView? = null
    private var platformStatus: TextView? = null
    private var logStatus: TextView? = null
    private var stopRefresh = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(0xE6_00_00_00.toInt()))
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.90f).toInt(),
            (context.resources.displayMetrics.heightPixels * 0.78f).toInt()
        )
        window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        )

        val root = buildLayout(context)
        setContentView(root)
    }

    override fun show() {
        super.show()
        stopRefresh = false
        refreshLogTask.run()
    }

    override fun dismiss() {
        stopRefresh = true
        mainHandler.removeCallbacksAndMessages(null)
        super.dismiss()
    }

    /* ------------------------------ layout ------------------------------ */

    private fun buildLayout(ctx: Context): View {
        val pad = dp(ctx, 12)
        val padSm = dp(ctx, 6)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xE6_0A_0A_14.toInt())
        }

        // ── 标题行 ──
        val title = TextView(ctx).apply {
            text = "⚙️ 设置 & 调试日志  (v2.2.2)"
            setTextColor(0xFF_F2_F2_F2.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, 0, 0, pad)
        }
        root.addView(title)

        // ── TabHost ──
        val host = TabHost(ctx, null).apply { id = View.generateViewId() }
        val tabs = TabWidget(ctx).apply { id = android.R.id.tabs }
        val tabContent = FrameLayoutCompat(ctx).apply { id = android.R.id.tabcontent }
        // build TabHost structure
        val tabHostInner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        tabHostInner.addView(tabs, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        tabHostInner.addView(tabContent, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        host.addView(tabHostInner)
        host.setup()

        val platformPage = buildPlatformPage(ctx)
        val logPage = buildLogPage(ctx)

        // TabHost requires content ids to be set; instead of using dummy FrameLayout,
        // let the content area swap the views via a FrameLayout wrapper.  Easier:
        // use setContent(android.R.id.tabcontent) with ViewFactory approach.
        host.addTab(host.newTabSpec("platform")
            .setIndicator("🎮 平台选择")
            .setContent { platformPage })
        host.addTab(host.newTabSpec("log")
            .setIndicator("📋 调试日志")
            .setContent { logPage })

        root.addView(host, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // ── 底部关闭按钮 ──
        val closeBtn = Button(ctx).apply {
            text = "关闭"
            setOnClickListener { dismiss() }
        }
        root.addView(closeBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = pad })

        return root
    }

    private class FrameLayoutCompat(ctx: Context) :
        android.widget.FrameLayout(ctx)

    /* --------------------------- Tab 1: 平台 --------------------------- */

    private fun buildPlatformPage(ctx: Context): View {
        val pad = dp(ctx, 10)
        val page = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, pad)
        }
        // 顶部状态
        platformStatus = TextView(ctx).apply {
            setBackgroundColor(0x22_00_DC_9A.toInt())
            setPadding(pad, pad / 2, pad, pad / 2)
            setTextColor(0xFF_9C_FF_C6.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        updatePlatformStatus()
        page.addView(platformStatus, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = pad })

        // 列表
        val listView = ListView(ctx)
        val platforms = GamePlatform.values().toList()
        val adapter = object : ArrayAdapter<GamePlatform>(
            ctx,
            android.R.layout.simple_list_item_2,
            android.R.id.text1,
            platforms
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val p = platforms[position]
                val t1 = v.findViewById<TextView>(android.R.id.text1)
                val t2 = v.findViewById<TextView>(android.R.id.text2)
                val sel = (p == ScreenAdaptation.instance.currentPlatform)
                t1.text = buildString {
                    if (sel) append("✅ ")
                    append(p.displayName)
                    append("   手牌线：${(p.handRowTopPct * 100).toInt()}%")
                }
                t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                val pkgStr = if (p.packageCandidates.isEmpty())
                    "（通用兜底）" else p.packageCandidates.joinToString(" / ")
                t2.text = buildString {
                    append("包名：$pkgStr")
                    if (p.description.isNotBlank()) append("\n").append(p.description)
                }
                t2.setTextColor(0xFF_AA_AA_AA.toInt())
                t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                t2.maxLines = 3
                if (sel) v.setBackgroundColor(0x33_00_DC_9A.toInt())
                return v
            }
        }
        listView.adapter = adapter
        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            val p = platforms[pos]
            GamePlatform.save(ctx, p)
            val changed = ScreenAdaptation.instance.applyPlatform(p)
            if (changed) {
                // 清掉 pipeline 旧状态，让聚类重新开始
                runCatching {
                    val core = (ctx.applicationContext as? JiPaiQiApp)?.core
                    core?.pipeline?.reset()
                    core?.nativePipeline?.reset()
                    core?.state?.newGame()
                }
            }
            DLog.i("SettingsDialog", "user selected platform=${p.name} changed=$changed")
            updatePlatformStatus()
            adapter.notifyDataSetChanged()
            Toast.makeText(ctx, "已切换到：${p.displayName}", Toast.LENGTH_SHORT).show()
        }
        page.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        return page
    }

    private fun updatePlatformStatus() {
        val s = ScreenAdaptation.instance
        val args = JiPaiQiApp.lastLoadArgs
        val picked = if (args == null) "—" else "(${args.first},${args.second},${args.third}) load=${JiPaiQiApp.lastLoadOk} init=${JiPaiQiApp.lastInitOk}"
        platformStatus?.text = buildString {
            append("当前平台：${s.currentPlatform.displayName}   （ID=${s.currentPlatform.name}）\n")
            append("手牌识别起点：顶部 ${(s.handRowTopPct * 100).toInt()}% 高度以下 （cutLinePx 根据屏幕实时计算）\n")
            append("预期手牌：${s.expectedHandCards} 张 / NCNN 加载参数：$picked")
        }
    }

    /* --------------------------- Tab 2: 日志 --------------------------- */

    private fun buildLogPage(ctx: Context): View {
        val pad = dp(ctx, 10)
        val padSm = dp(ctx, 6)
        val page = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // 状态行（比 Tab1 的行更详细）
        logStatus = TextView(ctx).apply {
            setBackgroundColor(0x22_FF_EB_3B.toInt())
            setPadding(pad, pad / 2, pad, pad / 2)
            setTextColor(0xFF_FF_EB_3B.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            updateLogStatus()
        }
        page.addView(logStatus, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = padSm })

        // 4 按钮横排
        val btnRow = HorizontalScrollView(ctx)
        val btnRowInner = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        listOf(
            makeBtn(ctx, "📋 复制全部") { copyAllLogToClipboard(ctx) },
            makeBtn(ctx, "🗑 清空") { clearAllLog(ctx) },
            makeBtn(ctx, "📤 分享文件") { shareLogFile(ctx) },
            makeBtn(ctx, "⚡ 强制检测快照", true) { runForcedSnapshot(ctx) }
        ).forEach { btn ->
            btnRowInner.addView(btn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = padSm
                topMargin = padSm
                bottomMargin = padSm
            })
        }
        btnRow.addView(btnRowInner)
        page.addView(btnRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = padSm })

        // 日志文本框（ScrollView + TextView）
        val scv = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true
            setBackgroundColor(0x22_1B_1B_2B.toInt())
        }
        logView = TextView(ctx).apply {
            setPadding(pad, pad, pad, pad)
            setTextColor(0xFF_D8_D8_D8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = android.graphics.Typeface.MONOSPACE
            setLineSpacing(1f, 0.95f)
            text = "(日志将在 1 秒内刷新…)"
        }
        scv.addView(logView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        page.addView(scv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        return page
    }

    private fun updateLogStatus() {
        val s = ScreenAdaptation.instance
        val app = (context.applicationContext as? JiPaiQiApp)
        val core = app?.core
        val det = ScreenCaptureService.sLastFrameDetections
        val hand = ScreenCaptureService.sLastFrameHandCount
        val d = app?.resources?.displayMetrics
        logStatus?.text = buildString {
            append("平台=${s.currentPlatform.name} handLine=${(s.handRowTopPct * 100).toInt()}% | ")
            append("screen=${d?.widthPixels}x${d?.heightPixels}@${d?.densityDpi}dpi | ")
            append("NCNN=${if (core?.nativeYoloReady == true) "OK" else "FAIL"} | ")
            append("detCnt=$det handCnt=$hand | ")
            append("lines=${DebugLogCollector.snapshotLines().size}")
        }
    }

    private val refreshLogTask = object : Runnable {
        override fun run() {
            if (stopRefresh) return
            logView?.let { tv ->
                val text = DebugLogCollector.snapshotString(
                    newestFirst = false,
                    limit = 2000,
                    header = "[jipaiqi v2.2.2 APP内置调试日志 — ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                        java.util.Locale.US).format(java.util.Date())}]"
                )
                val scv = tv.parent as? ScrollView
                val oldBottom = scv?.let { v ->
                    val diff = v.getChildAt(0).bottom - (v.height + v.scrollY)
                    diff < 50
                } ?: false
                tv.text = text
                if (oldBottom) scv?.post { scv.fullScroll(View.FOCUS_DOWN) }
            }
            updateLogStatus()
            mainHandler.postDelayed(this, 900L)
        }
    }

    private fun makeBtn(ctx: Context, text: String, primary: Boolean = false, onClick: () -> Unit): Button {
        return Button(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            if (primary) setBackgroundColor(0xFF_00_DC_9A.toInt())
            else setBackgroundColor(0x66_55_55_77.toInt())
            setTextColor(if (primary) Color.BLACK else 0xFF_E8_E8_E8.toInt())
            setOnClickListener { onClick() }
        }
    }

    /* --------------------------- 按钮动作 --------------------------- */

    private fun copyAllLogToClipboard(ctx: Context) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val all = DebugLogCollector.snapshotString(limit = 2500)
        cm.setPrimaryClip(ClipData.newPlainText("jipaiqi_debug_log", all))
        DLog.i("SettingsDialog", "copied ${all.lines().size} log lines to clipboard")
        Toast.makeText(ctx, "已复制 ${all.lines().size} 行日志到剪贴板", Toast.LENGTH_LONG).show()
    }

    private fun clearAllLog(ctx: Context) {
        DebugLogCollector.clearAll()
        DLog.i("SettingsDialog", "logs cleared by user")
        Toast.makeText(ctx, "已清空日志", Toast.LENGTH_SHORT).show()
    }

    private fun shareLogFile(ctx: Context) {
        val file = DebugLogCollector.shareFile()
        if (file == null || !file.exists() || file.length() == 0L) {
            // no disk file yet — dump in-memory ring to a fresh file
            val dir = ctx.externalCacheDir ?: ctx.cacheDir
            val tmp = File(dir, "jipaiqi_debug_dump_${System.currentTimeMillis()}.txt")
            tmp.writeText(DebugLogCollector.snapshotString(limit = 2500))
            sendIntent(ctx, tmp)
        } else {
            sendIntent(ctx, file)
        }
    }

    private fun sendIntent(ctx: Context, file: File) {
        val uri: Uri = runCatching {
            val auth = "${ctx.packageName}.fileprovider"
            FileProvider.getUriForFile(ctx, auth, file)
        }.getOrElse { Uri.fromFile(file) }
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(i, "分享 jipaiqi 调试日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun runForcedSnapshot(ctx: Context) {
        DLog.w("SettingsDialog",
            "═════════════════════════ 手动触发检测快照开始 ═════════════════════════")
        val app = (ctx.applicationContext as? JiPaiQiApp)
        val s = ScreenAdaptation.instance
        val core = app?.core
        DLog.w("SettingsDialog", "platform=${s.currentPlatform.name} " +
                "display=${s.currentPlatform.displayName} " +
                "handRowTopPct=${s.handRowTopPct} expected=${s.expectedHandCards}")
        DLog.w("SettingsDialog", "screen=${app?.resources?.displayMetrics?.widthPixels}x" +
                "${app?.resources?.displayMetrics?.heightPixels}@" +
                "${app?.resources?.displayMetrics?.densityDpi}dpi")
        DLog.w("SettingsDialog", "nativeYoloReady=${core?.nativeYoloReady} " +
                "pipeline=${core?.nativePipeline != null} args=${JiPaiQiApp.lastLoadArgs} " +
                "load=${JiPaiQiApp.lastLoadOk} init=${JiPaiQiApp.lastInitOk}")
        DLog.w("SettingsDialog", "last detCnt=${ScreenCaptureService.sLastFrameDetections} " +
                "handCnt=${ScreenCaptureService.sLastFrameHandCount}")
        // 提示用户：实际截屏/识别需要 MediaProjection 已经激活，如果服务在跑，
        // 下一帧自然会在 ~100ms 内被 captureHandler 拉到（所以我们只需要等 3 秒然后
        // 把结果打印到日志里）。
        Toast.makeText(ctx,
            "快照头已写入日志，若录屏正在运行，下一帧检测结果会在 ~1 秒后出现。" +
                    "如果仍 DETECT=0 请直接复制/分享",
            Toast.LENGTH_LONG).show()
    }

    private fun dp(ctx: Context, v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
        ctx.resources.displayMetrics
    ).toInt()
}
