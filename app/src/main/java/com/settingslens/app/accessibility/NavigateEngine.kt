package com.settingslens.app.accessibility

import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.settingslens.app.model.NavigationPath
import com.settingslens.app.model.NavigationStep
import kotlinx.coroutines.*

/**
 * Navigate engine: replays a sequence of navigation steps to reach and highlight a target setting.
 *
 * Resilience & Diagnostics:
 * - Direct shortcut intents launched for top-level targets to reduce latency and hops.
 * - Automatic retry with forward-scrolling if a target control is located below the viewport fold.
 * - Final step highlights the target via Android's built-in accessibility focus rectangle.
 * - Human-first diagnostic logging explains exactly which step was reached, what was clicked,
 *   and clear recovery instructions if a screen layout shifted.
 */
class NavigateEngine(
    private val service: SettingsAccessibilityService
) {
    companion object {
        private const val TAG = "SettingsLens:Navigate"
        private const val SETTLE_TIMEOUT_MS = 2000L
        private const val POST_CLICK_WAIT_MS = 500L
        private const val MAX_FIND_RETRIES = 3
        private const val RETRY_DELAY_MS = 500L
    }

    sealed class NavigationResult {
        data class Success(val highlightedLabel: String?) : NavigationResult()
        data class Failed(val stepIndex: Int, val stepLabel: String?, val reason: String) : NavigationResult()
    }

    interface NavigateListener {
        fun onStepStarted(stepIndex: Int, totalSteps: Int, label: String?)
        fun onStepCompleted(stepIndex: Int)
        fun onNavigationComplete(result: NavigationResult)
    }

    private var listener: NavigateListener? = null

    fun setListener(listener: NavigateListener) {
        this.listener = listener
    }

    /**
     * Execute a navigation path to reach and highlight a target setting.
     */
    suspend fun executePath(path: NavigationPath): NavigationResult = withContext(Dispatchers.Main) {
        val totalSteps = path.steps.size
        Log.i(TAG, "🧭 [Path Replay Initiated] Beginning navigation with $totalSteps step(s)...")

        try {
            // Hop 0: Launch initial screen (prefer direct category intent if available)
            if (path.directIntentAction != null) {
                Log.i(TAG, "⚡ [Direct Shortcut] Launching category shortcut: ${path.directIntentAction}")
                launchIntent(path.directIntentAction)
            } else {
                Log.i(TAG, "🏠 [Settings Home] Opening main Settings screen...")
                launchIntent(Settings.ACTION_SETTINGS)
            }

            service.waitForWindowSettle(SETTLE_TIMEOUT_MS)
            delay(POST_CLICK_WAIT_MS)

            // Replay each hop
            for ((index, step) in path.steps.withIndex()) {
                val isLastStep = index == totalSteps - 1
                val targetName = step.label ?: step.clickTarget.text ?: "Setting"

                Log.i(TAG, "🚶 [Hop ${index + 1}/$totalSteps] Seeking: '$targetName'")
                listener?.onStepStarted(index, totalSteps, step.label)

                val targetNode = findTargetWithRetries(step)
                if (targetNode == null) {
                    val friendlyReason = "Could not locate '$targetName' on this screen. The device's settings menu may have changed since the graph was built."
                    Log.e(TAG, "❌ [Hop Failed] $friendlyReason (Selector: ${step.clickTarget})")
                    val result = NavigationResult.Failed(index, step.label, friendlyReason)
                    listener?.onNavigationComplete(result)
                    return@withContext result
                }

                if (isLastStep) {
                    // Final hop: highlight the control with system accessibility focus
                    highlightNode(targetNode)
                    Log.i(TAG, "🎯 [Highlight Complete] Reached target setting '$targetName' and applied accessibility focus rectangle.")
                    val result = NavigationResult.Success(step.label)
                    listener?.onStepCompleted(index)
                    listener?.onNavigationComplete(result)
                    return@withContext result
                } else {
                    // Intermediate hop: click to open sub-screen
                    val clickable = findClickableAncestor(targetNode)
                    val clicked = try {
                        clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ [Click Exception] Could not click '$targetName': ${e.localizedMessage}")
                        false
                    }

                    if (!clicked) {
                        val failureMsg = "Failed to click menu item '$targetName'. The control may be disabled or unresponsive."
                        Log.e(TAG, "❌ [Click Failed] $failureMsg")
                        val result = NavigationResult.Failed(index, step.label, failureMsg)
                        listener?.onNavigationComplete(result)
                        return@withContext result
                    }

                    Log.d(TAG, "✅ [Hop ${index + 1}/$totalSteps Done] Clicked '$targetName', awaiting screen transition...")
                    listener?.onStepCompleted(index)

                    service.waitForWindowSettle(SETTLE_TIMEOUT_MS)
                    delay(POST_CLICK_WAIT_MS)
                }
            }

            val result = NavigationResult.Success(null)
            listener?.onNavigationComplete(result)
            result

        } catch (e: CancellationException) {
            Log.i(TAG, "🛑 [Navigation Cancelled] User or service cancelled navigation path.")
            throw e
        } catch (e: Exception) {
            val errorMsg = "Navigation encountered an unexpected problem: ${e.localizedMessage ?: "Unknown error"}"
            Log.e(TAG, "💥 [Navigation Error] $errorMsg", e)
            val result = NavigationResult.Failed(-1, null, errorMsg)
            listener?.onNavigationComplete(result)
            result
        }
    }

    private suspend fun findTargetWithRetries(step: NavigationStep): AccessibilityNodeInfo? {
        repeat(MAX_FIND_RETRIES) { attempt ->
            val root = service.rootInActiveWindow
            if (root != null) {
                val node = NodeExtractor.findNodeBySelector(root, step.clickTarget)
                if (node != null) {
                    Log.d(TAG, "🔍 [Target Found] Located '${step.label}' on attempt ${attempt + 1}.")
                    return node
                }
            }

            if (attempt < MAX_FIND_RETRIES - 1) {
                if (root != null) {
                    Log.d(TAG, "📜 [Scrolling for Target] Control not visible on current screen; scrolling forward (attempt ${attempt + 1}/$MAX_FIND_RETRIES)...")
                    NodeExtractor.scrollForward(root)
                }
                delay(RETRY_DELAY_MS)
            }
        }
        return null
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        if (node.isClickable) return node

        var parent = node.parent
        var levels = 0
        while (parent != null && levels < 5) {
            if (parent.isClickable) return parent
            parent = parent.parent
            levels++
        }
        return node
    }

    private fun highlightNode(node: AccessibilityNodeInfo) {
        try {
            val root = service.rootInActiveWindow
            if (root != null) {
                val currentFocus = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                currentFocus?.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            }
            val focused = node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            Log.d(TAG, "🌟 [Focus Highlighting] ACTION_ACCESSIBILITY_FOCUS applied (success: $focused)")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ [Focus Highlight Warning] Unable to set accessibility focus rectangle: ${e.localizedMessage}")
        }
    }

    private fun launchIntent(action: String) {
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            service.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ [Intent Fallback] Could not launch intent '$action'. Falling back to default Settings root: ${e.localizedMessage}")
            if (action != Settings.ACTION_SETTINGS) {
                try {
                    val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    service.startActivity(fallback)
                } catch (fallbackEx: Exception) {
                    Log.e(TAG, "💥 [Launch Failed] Cannot open system Settings app: ${fallbackEx.localizedMessage}")
                }
            }
        }
    }
}
