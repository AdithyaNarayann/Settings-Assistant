package com.settingslens.app.accessibility

import android.accessibilityservice.AccessibilityService
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

/**
 * Crawl engine: automates live discovery of the device's actual Settings app structure.
 *
 * Traversal Strategy:
 * - Depth-first discovery (DFS) bounded by max depth (5) and max total nodes (800).
 * - Detects cycles and avoids infinite loops using ScreenSignature (hash of title + labels).
 * - Strictly avoids volatile, authentication, or external branches (Accounts, Passwords, Biometrics, Google, Search, Legal).
 * - Comprehensive screen discovery: scrolls through scrollable screens using container actions and gesture swipes.
 * - Screen Transition Verification: confirms a new screen was actually opened before recursing,
 *   preventing false BACK presses that prematurely exited Settings to the launcher.
 * - Safe Backtracking: verifies return to parent screen before continuing loop iterations.
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

        // Crawl bounds optimized for speed and complete tree coverage
        private const val MAX_DEPTH = 3
        private const val MAX_NODES = 800
        private const val SETTLE_TIMEOUT_MS = 800L
        private const val BACK_SETTLE_MS = 350L
        private const val POST_CLICK_MIN_WAIT_MS = 150L

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
         * Strict blacklist for items that prompt for passwords, authenticate accounts,
         * open external app boundaries, or trigger system-altering resets.
         */
        private val SKIP_WORDS = listOf(
            "@",                  // Email addresses (e.g. user account headers like abha**2@gmail.com)
            "account",            // Accounts, Vivo account, Google account, Passwords & accounts
            "password",           // Passwords, Lock screen password, Privacy password
            "fingerprint",        // Fingerprint authentication
            "face unlock",        // Face recognition
            "face recognition",
            "screen lock",        // Screen lock PIN/Pattern
            "biometric",          // Biometrics
            "credential",         // Credentials
            "sign in",            // Sign in prompts
            "log in",             // Log in
            "login",
            "cloud",              // Vivo Cloud, Samsung Cloud, Google Cloud
            "suggestion",         // Switch setting suggestions, banner cards
            "suggestions",
            "search",             // Search settings
            "tips",
            "help",
            "feedback",
            "user guide",
            "user manual",
            "update",             // System update, software update
            "reset",              // Factory reset, reset options
            "developer options",  // Developer options (avoids disabling USB debugging)
            "about phone",        // About phone / device
            "about device",
            "legal",
            "terms of service",
            "privacy policy",
            "licenses",
            "licences",
            "emergency",          // Safety & emergency (avoids triggering SOS / contacts)
            "wallet",             // Google Wallet / Pay
            "pay",
            "sim lock",
            "google"              // Google settings — opens external Google app
        )

        /**
         * Screen titles where individual list items are action triggers (launch external apps,
         * connect to networks, pair devices) rather than navigation to deeper settings sub-screens.
         * When the crawler lands on one of these screens, it records all items but does NOT
         * click any of them.
         */
        private val ACTION_LIST_SCREEN_TITLES = listOf(
            "vpn",                // VPN profiles → clicking launches VPN app
            "installed apps",     // App list → clicking opens individual app (sometimes external)
            "all apps",
            "app info",
            "app management",
            "manage applications",
            "paired devices",     // Bluetooth paired list → clicking connects/disconnects
            "previously connected",
            "available devices",
            "saved networks",     // Wi-Fi saved networks
            "cast",               // Cast device list
            "sim cards",          // SIM card list
            "choose wallpaper",
            "users",              // User profiles
            "multiple users",
            "add account",        // Account type list → opens external auth flow
            "work profile"
        )
    }

    interface CrawlListener {
        fun onProgress(nodesDiscovered: Int, currentScreen: String?)
        fun onNodesUpdated(nodes: List<SettingsNode>)
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

            // Begin recursive traversal with root breadcrumbs
            crawlCurrentScreen(parentId = null, depth = 0, breadcrumbs = emptyList(), parentLabel = null)

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

    private suspend fun crawlCurrentScreen(
        parentId: String?,
        depth: Int,
        breadcrumbs: List<String>,
        parentLabel: String? = null
    ) {
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

        val rawTitle = NodeExtractor.extractScreenTitle(root)
        // If rawTitle is missing or generic "Settings", use the clicked parent label as the screen title
        val screenTitle = if (!rawTitle.isNullOrBlank() && !rawTitle.equals("Settings", ignoreCase = true)) {
            rawTitle
        } else {
            parentLabel ?: rawTitle ?: "Settings"
        }

        if (shouldSkipScreen(screenTitle)) {
            Log.d(TAG, "⏭️ [Screen Skipped] Intentionally skipping non-settings screen: '$screenTitle'")
            return
        }

        // Current breadcrumbs: exact trail of preference labels clicked to reach here
        val currentBreadcrumbs = if (depth > 0 && parentLabel != null && !breadcrumbs.contains(parentLabel)) {
            breadcrumbs + parentLabel
        } else {
            breadcrumbs
        }

        // Discover all items on this screen (fast collect, 1-2 scrolls max)
        val items = collectAllScreenItems(depth)
        if (items.isEmpty()) {
            Log.d(TAG, "ℹ️ [Empty Screen] No settings controls found on '$screenTitle'.")
            return
        }

        val itemLabels = items.map { it.label }
        val signature = ScreenSignature.compute(screenTitle, itemLabels)

        if (signature in visitedSignatures) {
            Log.d(TAG, "🔁 [Already Visited] Skipping previously crawled screen: '$screenTitle' (signature: $signature)")
            // Still link childIds to parent even though we won't re-record nodes
            if (parentId != null) {
                val existingNodeIds = discoveredNodes
                    .filter { it.screenSignature == signature }
                    .map { it.id }
                if (existingNodeIds.isNotEmpty()) {
                    discoveredNodes.find { it.id == parentId }?.childIds?.addAll(existingNodeIds)
                }
            }
            return
        }
        visitedSignatures.add(signature)
        screenSignatures.add(signature)

        Log.i(TAG, "📍 [Screen Discovered] '$screenTitle' (depth $depth, ${items.size} controls, path: ${currentBreadcrumbs.joinToString(" > ")})")
        listener?.onProgress(discoveredNodes.size, screenTitle)

        // Record nodes on this screen with their full location saved
        val screenNodeMap = mutableMapOf<NodeExtractor.ExtractedNode, String>()
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
                screenTitle = screenTitle,
                parentId = parentId,
                selector = item.selector,
                depth = depth,
                directIntentAction = intentAction,
                className = item.className,
                isClickable = item.isClickable,
                pathBreadcrumbs = currentBreadcrumbs
            )
            discoveredNodes.add(node)
            screenNodeIds.add(nodeId)
            screenNodeMap[item] = nodeId
        }

        if (parentId != null) {
            discoveredNodes.find { it.id == parentId }?.childIds?.addAll(screenNodeIds)
        }

        // Live stream discovered nodes so UI displays them immediately
        listener?.onNodesUpdated(discoveredNodes.toList())

        // Explore navigation branches (rows that lead to sub-screens)
        // Check if this screen is a pure "action-list" where ALL items are actions
        if (isActionListScreen(screenTitle)) {
            Log.i(TAG, "📋 [Action-List Screen] '$screenTitle' contains action items (e.g. VPN profiles, Wi-Fi networks). " +
                    "Recording ${items.size} items without clicking any.")
            return
        }

        val navigationCandidates = items.filter { it.isNavigationCandidate && !shouldSkipItem(it.label) }
        Log.i(TAG, "🧭 [Branch Exploration] '$screenTitle' (depth $depth) has ${navigationCandidates.size} navigational sub-screens to explore.")

        for ((index, item) in navigationCandidates.withIndex()) {
            if (isCancelled) return
            if (discoveredNodes.size >= MAX_NODES) return

            val nodeId = screenNodeMap[item] ?: continue

            Log.d(TAG, "👉 [Branch ${index + 1}/${navigationCandidates.size} depth $depth] '${item.label}'")

            // Re-find target node on the live screen
            val liveNode = findNodeWithScrolling(item.selector)
            if (liveNode == null) {
                Log.w(TAG, "❓ [Control Unavailable] Could not acquire '${item.label}' on screen. Continuing to next.")
                continue
            }

            // Snapshot current screen state BEFORE clicking
            val preClickRoot = service.rootInActiveWindow ?: continue
            val preClickTitle = NodeExtractor.extractScreenTitle(preClickRoot) ?: screenTitle
            val preClickItems = NodeExtractor.extractScreenItems(preClickRoot).map { it.label }
            val preClickSig = ScreenSignature.compute(preClickTitle, preClickItems)

            val clicked = try {
                liveNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ [Click Failed] Error clicking '${item.label}': ${e.localizedMessage}")
                false
            }

            if (!clicked) {
                // Try clicking the parent container instead
                val clickableParent = findClickableAncestor(liveNode)
                if (clickableParent != null) {
                    try {
                        clickableParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ [Click Refused] Target '${item.label}' and its ancestors refused click. Skipping.")
                        continue
                    }
                } else {
                    Log.w(TAG, "⚠️ [Click Refused] Target '${item.label}' refused ACTION_CLICK. Skipping.")
                    continue
                }
            }

            // Fast transition check: poll every 40ms up to 600ms
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 600L) {
                delay(40)
                val currentRoot = service.rootInActiveWindow ?: continue
                val currentPkg = currentRoot.packageName?.toString() ?: ""
                val currentTitle = NodeExtractor.extractScreenTitle(currentRoot) ?: ""
                if (!isSettingsPackage(currentPkg) || (currentTitle.isNotBlank() && !currentTitle.equals(preClickTitle, ignoreCase = true))) {
                    break
                }
            }
            delay(POST_CLICK_MIN_WAIT_MS)

            // Inspect post-click state
            val postClickRoot = service.rootInActiveWindow
            if (postClickRoot == null) {
                continue
            }

            // Check if we navigated outside of Settings
            val postClickPkg = postClickRoot.packageName?.toString() ?: ""
            if (!isSettingsPackage(postClickPkg)) {
                Log.w(TAG, "🚪 [Left Settings Boundary] Item '${item.label}' opened external app ($postClickPkg). Recovering...")
                recoverToSettings(screenTitle)
                continue
            }

            val postClickTitle = NodeExtractor.extractScreenTitle(postClickRoot) ?: ""
            val postClickItems = NodeExtractor.extractScreenItems(postClickRoot).map { it.label }
            val postClickSig = ScreenSignature.compute(postClickTitle, postClickItems)

            // Check content overlap to detect dismissed cards vs real sub-screens
            val overlap = if (postClickItems.isNotEmpty()) {
                postClickItems.count { it in preClickItems }.toDouble() / postClickItems.size
            } else 0.0

            val isSameScreen = (postClickSig == preClickSig) ||
                    (postClickTitle.equals(preClickTitle, ignoreCase = true) && overlap > 0.65)

            if (isSameScreen) {
                Log.d(TAG, "ℹ️ [No Screen Transition] '${item.label}' did not open a distinct sub-screen (overlap=${(overlap * 100).toInt()}%). Skipping recursion.")
                continue
            }

            // If we landed on a screen that was already crawled/visited (like when clicking an item
            // closes a dialog or returns to an ancestor screen), DO NOT RECURSE and DO NOT PRESS BACK!
            if (postClickSig in visitedSignatures) {
                Log.d(TAG, "🔁 [Existing Screen Returned] '${item.label}' closed dialog or returned to '$postClickTitle'. Skipping recursion without pressing back.")
                continue
            }

            // Screen transitioned: recurse down into the sub-screen (DFS)
            // Pass the item label appended to breadcrumbs so full location is preserved!
            val nextBreadcrumbs = currentBreadcrumbs + item.label
            Log.i(TAG, "📂 [Entering Sub-screen] '${item.label}' -> '$postClickTitle' (depth ${depth + 1}, path: ${nextBreadcrumbs.joinToString(" > ")})")
            crawlCurrentScreen(
                parentId = nodeId,
                depth = depth + 1,
                breadcrumbs = nextBreadcrumbs,
                parentLabel = item.label
            )

            // Return back to current parent screen
            Log.d(TAG, "🔙 [Returning to Parent] Backing out from '$postClickTitle' to '$screenTitle'")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            service.waitForWindowSettle(BACK_SETTLE_MS)
            delay(POST_CLICK_MIN_WAIT_MS)

            // Verify safe return to original screen
            verifyReturnToParent(screenTitle, postClickTitle)
        }
    }

    /**
     * Attempt to return to the Settings app after accidentally navigating to an external app.
     * Tries up to 3 BACK presses, then re-launches Settings as a last resort.
     */
    private suspend fun recoverToSettings(expectedScreenTitle: String) {
        Log.d(TAG, "🔄 [Recovery] Attempting to return to '$expectedScreenTitle' in Settings app...")
        for (attempt in 1..3) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            service.waitForWindowSettle(BACK_SETTLE_MS)
            delay(POST_CLICK_MIN_WAIT_MS)

            val root = service.rootInActiveWindow ?: continue
            val pkg = root.packageName?.toString() ?: ""
            if (isSettingsPackage(pkg)) {
                Log.i(TAG, "✅ [Recovery Success] Returned to Settings after $attempt BACK press(es).")
                return
            }
        }

        // Last resort: re-launch the Settings app
        Log.w(TAG, "⚠️ [Recovery Fallback] BACK presses didn't return to Settings. Force re-launching Settings app...")
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            service.startActivity(intent)
            service.waitForWindowSettle(SETTLE_TIMEOUT_MS)
            delay(POST_CLICK_MIN_WAIT_MS)
            Log.i(TAG, "✅ [Recovery Complete] Re-launched Settings app successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "💥 [Recovery Failed] Could not re-launch Settings: ${e.localizedMessage}")
        }
    }

    /**
     * Verify we returned to the parent screen. If outside Settings, recover to Settings.
     */
    private suspend fun verifyReturnToParent(parentTitle: String, childTitle: String) {
        val returnRoot = service.rootInActiveWindow ?: return
        val returnPkg = returnRoot.packageName?.toString() ?: ""

        // If we landed outside Settings (e.g. on our app or launcher), recover immediately
        if (!isSettingsPackage(returnPkg)) {
            Log.w(TAG, "⚠️ [Post-Back Recovery] Ended up outside Settings ($returnPkg). Recovering...")
            recoverToSettings(parentTitle)
        }
    }

    /**
     * Fast collect of settings items on the current screen.
     * At depth 0 (Home screen): scrolls up to 2 times to index main categories.
     * At depth >= 1 (Sub-screens): scrolls at most 1 time.
     */
    private suspend fun collectAllScreenItems(depth: Int): List<NodeExtractor.ExtractedNode> {
        val collected = mutableListOf<NodeExtractor.ExtractedNode>()
        val seenKeys = mutableSetOf<String>()

        fun addCurrentItems() {
            val root = service.rootInActiveWindow ?: return
            val current = NodeExtractor.extractScreenItems(root)
            for (item in current) {
                val key = "${item.label}|${item.subtitle}"
                if (seenKeys.add(key)) {
                    collected.add(item)
                }
            }
        }

        addCurrentItems()

        val maxScrolls = if (depth == 0) 2 else 1
        var scrolls = 0
        while (scrolls < maxScrolls) {
            val root = service.rootInActiveWindow ?: break
            var scrolled = NodeExtractor.scrollForward(root)
            if (!scrolled) {
                scrolled = service.swipeUp()
            }
            if (!scrolled) break
            delay(150)
            val beforeCount = collected.size
            addCurrentItems()
            if (collected.size == beforeCount) {
                break
            }
            scrolls++
        }

        // Fast rewind back to top
        if (scrolls > 0) {
            for (i in 0 until scrolls) {
                val root = service.rootInActiveWindow ?: break
                var scrolled = NodeExtractor.scrollBackward(root)
                if (!scrolled) {
                    scrolled = service.swipeDown()
                }
                if (!scrolled) break
                delay(120)
            }
        }

        return collected
    }

    /**
     * Locate a node on screen with minimal scrolling.
     * Checks visible screen first, then scrolls forward up to 2 times.
     */
    private suspend fun findNodeWithScrolling(selector: NodeSelector): AccessibilityNodeInfo? {
        var root = service.rootInActiveWindow ?: return null
        var node = NodeExtractor.findClickableNode(root, selector)
        if (node != null) return node

        // Try scrolling forward up to 2 times
        var attempts = 0
        while (attempts < 2) {
            var scrolled = NodeExtractor.scrollForward(root)
            if (!scrolled) {
                scrolled = service.swipeUp()
            }
            if (!scrolled) break
            delay(150)
            root = service.rootInActiveWindow ?: break
            node = NodeExtractor.findClickableNode(root, selector)
            if (node != null) return node
            attempts++
        }

        // If not found forward, try scrolling backward up to 2 times
        attempts = 0
        while (attempts < 2) {
            var scrolled = NodeExtractor.scrollBackward(root)
            if (!scrolled) {
                scrolled = service.swipeDown()
            }
            if (!scrolled) break
            delay(120)
            root = service.rootInActiveWindow ?: break
            node = NodeExtractor.findClickableNode(root, selector)
            if (node != null) return node
            attempts++
        }

        return null
    }

    /**
     * Walk up ancestor chain to locate the nearest clickable container.
     */
    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent
        var hops = 0
        while (parent != null && hops < 5) {
            if (parent.isClickable) return parent
            parent = parent.parent
            hops++
        }
        return null
    }

    private fun isSettingsPackage(packageName: String): Boolean {
        if (packageName == service.packageName || packageName.contains("settingslens")) {
            return false
        }
        return packageName.contains("com.android.settings") ||
                packageName.contains("settings.intelligence") ||
                packageName.contains("vivo.settings") ||
                packageName.contains("com.samsung.android.settings") ||
                (packageName.contains("settings") && !packageName.contains("settingslens"))
    }

    private fun shouldSkipScreen(title: String?): Boolean {
        if (title != null) {
            val lower = title.lowercase()
            if (SKIP_WORDS.any { lower.contains(it) }) return true
        }
        return false
    }

    /**
     * Detects screens where list items are action triggers (connect, launch, pair)
     * rather than navigation to deeper settings sub-screens.
     */
    private fun isActionListScreen(title: String?): Boolean {
        if (title == null) return false
        val lower = title.lowercase().trim()
        return ACTION_LIST_SCREEN_TITLES.any { lower.contains(it) }
    }

    private fun shouldSkipItem(label: String): Boolean {
        val lower = label.lowercase().trim()
        return SKIP_WORDS.any { lower.contains(it) } ||
                lower.startsWith("search") ||
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
