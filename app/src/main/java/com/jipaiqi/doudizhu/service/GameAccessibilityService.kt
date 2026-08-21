package com.jipaiqi.doudizhu.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.jipaiqi.doudizhu.JiPaiQiApp
import com.jipaiqi.doudizhu.ai.Position

/**
 * Optional accessibility service. Default-disabled; the user must enable it
 * from system Settings → Accessibility.
 *
 * Used to read two pieces of information that pure screen recognition cannot
 * reliably extract:
 *
 *   1. The player's role (landlord / landlord_up / landlord_down) — typically
 *      shown as a chip in the host game's UI.
 *   2. The bottom (3 landlord) cards — only visible on the landlord's screen.
 *
 * The service inspects [AccessibilityEvent] source nodes via
 * [getRootInActiveWindow] and pushes any discovered "role" / "bottom card"
 * info into the shared [JiPaiQiApp.Core.state].
 *
 * IMPORTANT: this is intentionally a best-effort helper. Different Dou Dizhu
 * host apps use different view hierarchies; we don't try to match a specific
 * host. Anything we find is fed to the GameState, anything we miss is
 * covered by the screen-recognition pipeline.
 */
class GameAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val src = event?.source ?: return
        val core = (application as? JiPaiQiApp)?.core ?: return
        try {
            val root = rootInActiveWindow ?: return
            scanForRole(root, core)
        } catch (e: Exception) {
            Log.w(TAG, "accessibility scan: ${e.message}")
        }
    }

    private fun scanForRole(
        root: android.view.accessibility.AccessibilityNodeInfo,
        core: JiPaiQiApp.Core,
    ) {
        // Walk the tree, look for text nodes containing role markers.
        val texts = ArrayList<String>()
        collectTexts(root, texts, depth = 0)
        for (t in texts) {
            when {
                t.contains("地主") && !t.contains("农民") ->
                    core.state.setMyPosition(Position.LANDLORD)
                t.contains("上家") || t.contains("农民上") ->
                    core.state.setMyPosition(Position.LANDLORD_UP)
                t.contains("下家") || t.contains("农民下") ->
                    core.state.setMyPosition(Position.LANDLORD_DOWN)
            }
        }
    }

    private fun collectTexts(
        node: android.view.accessibility.AccessibilityNodeInfo,
        out: MutableList<String>,
        depth: Int,
    ) {
        if (depth > 12) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTexts(child, out, depth + 1)
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility interrupted")
    }

    companion object { private const val TAG = "GameAccessibility" }
}
