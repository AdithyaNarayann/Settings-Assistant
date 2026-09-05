package com.settingslens.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.settingslens.app.model.NodeSelector
import com.settingslens.app.model.SettingsGraph
import com.settingslens.app.model.SettingsNode
import kotlinx.coroutines.*
import java.time.Instant
import java.util.UUID

/**
 * Crawl engine: automates live discovery of the device's actual Settings app structure.
 *
 * Traversal Strategy:
 * - Depth-first discovery bounded by max depth (6) and max total nodes (800).
 * - Detects cycles and avoids infinite loops using ScreenSignature (hash of title + labels).
 * - Avoids volatile and external branches (Google Play Services, carrier accounts, search).
 * - Handles off-screen / scrollable items gracefully via automated forward scrolling.
 *
 * Human-First Logging:
 * - Clear log prefix: [SettingsLens:Crawl]
 * - Logs user-relatable milestones: controls detected, screens reached, branches skipped, recoveries.
 */
class CrawlEngine(
    private val service: SettingsAccessibilityService
) {
    companion object {
        private const val TAG = "SettingsLens:Crawl"

        // Crawl bounds
        private const val MAX_DEPTH = 6
        private const val MAX_NODES = 800
        private const val SETTLE_TIMEOUT_MS = 2000L
        private const val BACK_SETTLE_MS = 1000L
        private const val POST_CLICK_MIN_WAIT_MS = 500L

        /**
         * Known intent actions for top-level settings categories.
         * Enables instant direct-jump during navigation instead of walking from root.
         */
        private val KNOWN_INTENT_ACTIONS = mapOf(
            "wi-fi" to Settings.ACTION_WIFI_SETTINGS,
            "wifi" to Settings.ACTION_WIFI_SETTINGS,
            "wlan" to Settings.ACTION_WIFI_SETTINGS,
            "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "display" to Settings.ACTION_DISPLAY_SETTINGS,
            "sound" to Settings.ACTION_SOUND_SETTINGS,
            "sounds" to Settings.ACTION_SOUND_SETTINGS,
            "sound & vibration" to Settings.ACTION_SOUND_SETTINGS,
            "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
            "apps" to Settings.ACTION_APPLICATION_SETTINGS,
            "applications" to Settings.ACTION_APPLICATION_SETTINGS,
            "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "security" to Settings.ACTION_SECURITY_SETTINGS,
            "battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
            "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            "network" to Settings.ACTION_WIRELESS_SETTINGS,
            "connections" to Settings.ACTION_WIRELESS_SETTINGS,
            "notifications" to Settings.ACTION_APP_NOTIFICATION_SETTINGS,
        )

        /**
         * Labels indicating non-settings or external system branches to skip.
         * Avoids triggering Google account sync, legal disclosures, and search bars.
         */
        private val SKIP_LABELS = setOf(
            "about phone", "about device", "about tablet",
            "legal information", "legal", "privacy policy",
            "terms of service", "open source licenses",
            "open source licences", "regulatory information",
            "safety information", "samsung account", "vivo account",
            "google", "google services", "services & preferences",
            "accounts", "passwords & accounts",
            "tips and help", "tips & help",
            "search", "search settings"
        )
    }

    interface CrawlListener {
        fun onProgress(nodesDiscovered: Int, currentScreen: String?)
        fun onComplete(graph: SettingsGraph)
        fun onError(error: String)
    }

    private var listener: CrawlListener? = null
    private val discoveredNodes = mutableListOf<SettingsNode>()
    private val visitedSignatures = mutableSetOf<String>()
    private val screenSignatures = mutableSetOf<String>()
    private var nodeCounter = 0
    private var isCancelled = false

    fun setListener(listener: CrawlListener) {
        this.listener = listener
    }

    suspend fun startCrawl(): SettingsGraph? = withContext(Dispatchers.Main) {
        Log.i(TAG, "📱 [Crawl Initiated] Preparing to map Settings on ${Build.MANUFACTURER} ${Build.MODEL} (Android API ${Build.VERSION.SDK_INT})")
        isCancelled = false
        discoveredNodes.clear()
        visitedSignatures.clear()
        screenSignatures.clear()
        nodeCounter = 0

        try {
            // Launch the device Settings app
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            service.startActivity(intent)

            Log.d(TAG, "⏳ [Awaiting App Launch] Waiting for Settings main screen to settle...")
            service.waitForWindowSettle(SETTLE_TIMEOUT_MS)
            delay(POST_CLICK_MIN_WAIT_MS)

            // Begin recursive traversal
            crawlCurrentScreen(parentId = null, depth = 0)

            val graph = SettingsGraph(
                deviceManufacturer = Build.MANUFACTURER,
                deviceModel = Build.MODEL,
                androidVersion = Build.VERSION.SDK_INT,
                nodes = discoveredNodes.toList(),
                createdAt = Instant.now().toString(),
                screenSignatures = screenSignatures.toSet()
            )

            Log.i(TAG, "🎉 [Crawl Completed] Successfully discovered ${graph.nodeCount} settings across ${screenSignatures.size} unique screens.")
            listener?.onComplete(graph)
            graph
        } catch (e: CancellationException) {
            Log.i(TAG, "🛑 [Crawl Cancelled] Settings discovery was stopped.")
            throw e
        } catch (e: Exception) {
            val failureReason = "Discovery encountered an unexpected issue: ${e.localizedMessage ?: "Unknown error"}"
            Log.e(TAG, "💥 [Crawl Failure] $failureReason", e)
            listener?.onError(failureReason)
            null
        }
    }

    fun cancel() {
        isCancelled = true
    }

    private suspend fun crawlCurrentScreen(parentId: String?, depth: Int) {
        if (isCancelled) return
        if (depth > MAX_DEPTH) {
            Log.d(TAG, "🧱 [Depth Limit] Reached maximum crawl depth ($MAX_DEPTH), returning up the tree.")
            return
        }
        if (discoveredNodes.size >= MAX_NODES) {
            Log.i(TAG, "🎯 [Node Cap Reached] Discovered target threshold of $MAX_NODES settings. Wrapping up crawl.")
            return
        }

        val root = service.rootInActiveWindow ?: run {
            Log.w(TAG, "⚠️ [Window Unreadable] Cannot access root window at depth $depth. Skipping screen.")
            return
        }

        // Verify we haven't inadvertently navigated outside of the Settings app
        val packageName = root.packageName?.toString() ?: ""
        if (!isSettingsPackage(packageName)) {
            Log.w(TAG, "🚪 [Foreign App Boundary] Navigated out of Settings into $packageName. Pressing Back to return...")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            service.waitForWindowSettle(BACK_SETTLE_MS)
            return
        }

        // Screen identity and deduplication
        val screenTitle = NodeExtractor.extractScreenTitle(root) ?: "Settings"
        val items = NodeExtractor.extractScreenItems(root)
        val itemLabels = items.map { it.label }
        val signature = ScreenSignature.compute(screenTitle, itemLabels)

        if (signature in visitedSignatures) {
            Log.d(TAG, "🔁 [Already Visited] Skipping previously crawled screen: '$screenTitle' (signature: $signature)")
            return
        }
        visitedSignatures.add(signature)
        screenSignatures.add(signature)

        Log.i(TAG, "📍 [Screen Discovered] '$screenTitle' (depth $depth, ${items.size} controls visible)")
        listener?.onProgress(discoveredNodes.size, screenTitle)

        if (shouldSkipScreen(screenTitle)) {
            Log.d(TAG, "⏭️ [Screen Skipped] Intentionally skipping non-settings screen: '$screenTitle'")
            return
        }

        // Record nodes on this screen
        val screenNodeIds = mutableListOf<String>()
        for (item in items) {
            if (discoveredNodes.size >= MAX_NODES) break

            val nodeId = generateNodeId()
            val intentAction = if (depth == 0) matchIntentAction(item.label) else null

            val node = SettingsNode(
                id = nodeId,
                label = item.label,
                subtitle = item.subtitle,
                screenSignature = signature,
                parentId = parentId,
                selector = item.selector,
                depth = depth,
                directIntentAction = intentAction,
                className = item.className,
                isClickable = item.isClickable
            )
            discoveredNodes.add(node)
            screenNodeIds.add(nodeId)
        }

        if (parentId != null) {
            discoveredNodes.find { it.id == parentId }?.childIds?.addAll(screenNodeIds)
        }

        // Explore clickable children
        val clickableItems = items.filter { it.isClickable }
        for ((index, item) in clickableItems.withIndex()) {
            if (isCancelled) return
            if (discoveredNodes.size >= MAX_NODES) return

            if (shouldSkipItem(item.label)) {
                Log.d(TAG, "⏭️ [Item Skipped] Skipping non-essential control: '${item.label}'")
                continue
            }

            val nodeId = screenNodeIds.getOrNull(items.indexOf(item)) ?: continue

            Log.d(TAG, "👉 [Testing Item ${index + 1}/${clickableItems.size} at depth $depth] '${item.label}'")

            val currentRoot = service.rootInActiveWindow ?: continue

            // Re-find target node in active tree, with automated scroll fallback
            var liveNode = NodeExtractor.findClickableNode(currentRoot, item.selector)
            if (liveNode == null) {
                Log.d(TAG, "📜 [Scrolling for Item] Item '${item.label}' not visible; scrolling down to locate...")
                NodeExtractor.scrollForward(currentRoot)
                delay(300)
                val scrolledRoot = service.rootInActiveWindow
                liveNode = NodeExtractor.findClickableNode(scrolledRoot, item.selector)
            }

            if (liveNode == null) {
                Log.w(TAG, "❓ [Control Unavailable] Could not re-acquire '${item.label}' on screen. Continuing to next.")
                continue
            }

            val clicked = try {
                liveNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ [Click Failed] Error clicking '${item.label}': ${e.localizedMessage}")
                false
            }

            if (!clicked) {
                continue
            }

            service.waitForWindowSettle(SETTLE_TIMEOUT_MS)
            delay(POST_CLICK_MIN_WAIT_MS)

            // Recurse down this branch
            crawlCurrentScreen(parentId = nodeId, depth = depth + 1)

            // Return back to current parent screen
            Log.d(TAG, "🔙 [Returning to Parent] Backing out from '${item.label}' to '$screenTitle'")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            service.waitForWindowSettle(BACK_SETTLE_MS)
            delay(POST_CLICK_MIN_WAIT_MS)

            // Verify safe return to original screen
            val backRoot = service.rootInActiveWindow
            if (backRoot != null) {
                val backTitle = NodeExtractor.extractScreenTitle(backRoot)
                val backItems = NodeExtractor.extractScreenItems(backRoot)
                val backSig = ScreenSignature.compute(backTitle, backItems.map { it.label })
                if (backSig != signature) {
                    Log.w(TAG, "⚠️ [Screen Drift Detected] Did not land on expected screen after Back press (expected $signature, found $backSig). Attempting recovery Back press...")
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    service.waitForWindowSettle(BACK_SETTLE_MS)
                    delay(POST_CLICK_MIN_WAIT_MS)
                }
            }
        }
    }

    private fun isSettingsPackage(packageName: String): Boolean {
        return packageName.contains("settings") ||
                packageName.contains("com.android.settings") ||
                packageName == "com.samsung.android.settings.intelligence" ||
                packageName == "com.google.android.settings.intelligence"
    }

    private fun shouldSkipScreen(title: String?): Boolean {
        if (title != null && SKIP_LABELS.any { title.contains(it, ignoreCase = true) }) {
            return true
        }
        return false
    }

    private fun shouldSkipItem(label: String): Boolean {
        val lower = label.lowercase()
        return SKIP_LABELS.any { lower.contains(it) } ||
                lower == "search" ||
                lower.contains("see all") ||
                lower.contains("view all")
    }

    private fun matchIntentAction(label: String): String? {
        val lower = label.lowercase().trim()
        return KNOWN_INTENT_ACTIONS[lower]
            ?: KNOWN_INTENT_ACTIONS.entries.find { lower.contains(it.key) }?.value
    }

    private fun generateNodeId(): String {
        nodeCounter++
        return "node_$nodeCounter"
    }
}
