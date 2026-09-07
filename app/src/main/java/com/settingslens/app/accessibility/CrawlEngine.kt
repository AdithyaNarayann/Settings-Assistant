package com.settingslens.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
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

        // Crawl bounds optimized for complete exhaustive tree coverage
        private const val MAX_DEPTH = 3
        private const val MAX_NODES = 1200
        private const val SETTLE_TIMEOUT_MS = 500L
        private const val BACK_SETTLE_MS = 200L
        private const val POST_CLICK_MIN_WAIT_MS = 80L

        /**
         * System Settings Intent map for direct fallback navigation.
         * If a top-level category row cannot be acquired on screen or fails to transition,
         * the engine launches the direct intent rather than skipping the entire category.
         */
        private val KNOWN_INTENT_ACTIONS = mapOf(
            "network & internet" to Settings.ACTION_WIRELESS_SETTINGS,
            "network" to Settings.ACTION_WIRELESS_SETTINGS,
            "connections" to Settings.ACTION_WIRELESS_SETTINGS,
            "wi-fi" to Settings.ACTION_WIFI_SETTINGS,
            "wifi" to Settings.ACTION_WIFI_SETTINGS,
            "wlan" to Settings.ACTION_WIFI_SETTINGS,
            "bluetooth & devices" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "display & brightness" to Settings.ACTION_DISPLAY_SETTINGS,
            "display" to Settings.ACTION_DISPLAY_SETTINGS,
            "sound & vibration" to Settings.ACTION_SOUND_SETTINGS,
            "sounds & vibration" to Settings.ACTION_SOUND_SETTINGS,
            "sound" to Settings.ACTION_SOUND_SETTINGS,
            "sounds" to Settings.ACTION_SOUND_SETTINGS,
            "notifications" to Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            "app notifications" to Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            "battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
            "ram & storage space" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            "security" to Settings.ACTION_SECURITY_SETTINGS,
            "privacy" to Settings.ACTION_PRIVACY_SETTINGS,
            "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "shortcuts & accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
            "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
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
            "sim lock",
            "google",             // Google settings — opens external Google app
            "screen time",        // Opens external Wellbeing app
            "digital wellbeing",  // Opens external Wellbeing app
            "parental controls",  // Opens external Family Link app
            "v-appstore",         // External OEM app store
            "easyshare"           // External OEM sharing app
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
            "recently opened apps",
            "device & app notifications",
            "app notifications",
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
            "work profile",
            "display at the top",
            "full screen display",
            "special app access",
            "unrestricted data",
            "modify system settings",
            "picture-in-picture",
            "install unknown apps",
            "display over other apps",
            "device admin apps",
            "default apps",
            "ringtones",
            "notification tone",
            "alarm tone"
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

            Log.d(TAG, "⏳ [Awaiting App Launch] Waiting for Settings main screen to appear...")
            val launchStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - launchStart < 3500L) {
                val pkg = service.rootInActiveWindow?.packageName?.toString() ?: ""
                if (isSettingsPackage(pkg)) {
                    Log.i(TAG, "✅ [Settings Active] Detected Settings foreground window ($pkg).")
                    break
                }
                delay(80)
            }
            delay(200)

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

        var currentRoot = service.rootInActiveWindow
        var packageName = currentRoot?.packageName?.toString() ?: ""
        if (!isSettingsPackage(packageName)) {
            if (depth == 0) {
                delay(400)
                currentRoot = service.rootInActiveWindow
                packageName = currentRoot?.packageName?.toString() ?: ""
            }
            if (!isSettingsPackage(packageName)) {
                Log.w(TAG, "🚪 [Foreign App Boundary] Navigated out of Settings into $packageName. Pressing Back to return...")
                if (!packageName.contains("settingslens")) {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    service.waitForWindowSettle(BACK_SETTLE_MS)
                }
                return
            }
        }
        val root = currentRoot ?: run {
            Log.w(TAG, "⚠️ [Window Unreadable] Cannot access root window at depth $depth. Skipping screen.")
            return
        }

        // If at depth > 0 and we unexpectedly find ourselves on the root homepage, unwind immediately
        if (depth > 0 && isHomepageActive(root)) {
            Log.w(TAG, "⚠️ [Unexpected Root Homepage at Depth $depth] Detected root Settings homepage while inside sub-screen. Unwinding stack.")
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
            if (depth == 0 && (item.label.contains("@") || item.label.lowercase().contains("account"))) continue

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
        if (isActionListScreen(screenTitle, depth, currentBreadcrumbs)) {
            Log.i(TAG, "📋 [Action-List Screen] '$screenTitle' contains action items (e.g. VPN profiles, Wi-Fi networks). " +
                    "Recording ${items.size} items without clicking any.")
            return
        }

        val maxCandidates = if (depth == 0) 50 else 10
        val navigationCandidates = items.filter {
            it.isNavigationCandidate &&
                    !shouldSkipItem(it.label) &&
                    !shouldSkipSubBranch(screenTitle, it.label)
        }.take(maxCandidates)
        Log.i(TAG, "🧭 [Branch Exploration] '$screenTitle' (depth $depth) has ${navigationCandidates.size} navigational sub-screens to explore.")

        for ((index, item) in navigationCandidates.withIndex()) {
            if (isCancelled) return
            if (discoveredNodes.size >= MAX_NODES) return

            val nodeId = screenNodeMap[item] ?: continue

            Log.d(TAG, "👉 [Branch ${index + 1}/${navigationCandidates.size} depth $depth] '${item.label}'")

            try {
                // Re-find target node on the live screen with resilient scrolling
                var liveNode = findNodeWithScrolling(item.selector, depth)
                var usedDirectIntent = false

                // At depth 0: If row is off-screen or cannot be acquired, fall back to known direct intent
                if (liveNode == null && depth == 0) {
                    val intentAction = matchIntentAction(item.label)
                    if (intentAction != null) {
                        Log.i(TAG, "🚀 [Direct Intent Launch] Target category '${item.label}' off-screen; launching $intentAction directly.")
                        try {
                            val intent = Intent(intentAction).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            service.startActivity(intent)
                            service.waitForWindowSettle(SETTLE_TIMEOUT_MS)
                            delay(POST_CLICK_MIN_WAIT_MS)
                            usedDirectIntent = true
                        } catch (e: Exception) {
                            Log.w(TAG, "Direct intent $intentAction failed: ${e.localizedMessage}")
                        }
                    }
                }

                if (liveNode == null && !usedDirectIntent) {
                    Log.w(TAG, "❓ [Control Unavailable] Could not acquire '${item.label}' on screen. Continuing to next.")
                    continue
                }

                // Snapshot current screen state BEFORE clicking
                val preClickRoot = service.rootInActiveWindow ?: continue
                val preClickTitle = NodeExtractor.extractScreenTitle(preClickRoot) ?: screenTitle
                val preClickItems = NodeExtractor.extractScreenItems(preClickRoot).map { it.label }
                val preClickSig = ScreenSignature.compute(preClickTitle, preClickItems)

                if (!usedDirectIntent && liveNode != null) {
                    val targetNode = if (liveNode.isClickable) liveNode else (findClickableAncestor(liveNode) ?: liveNode)
                    val nodeBounds = Rect()
                    liveNode.getBoundsInScreen(nodeBounds)
                    val targetBounds = Rect()
                    targetNode.getBoundsInScreen(targetBounds)

                    val tapX = if (nodeBounds.centerX() in 50..1030) nodeBounds.centerX().toFloat()
                    else if (targetBounds.centerX() in 50..1030) targetBounds.centerX().toFloat()
                    else null
                    val tapY = if (nodeBounds.centerY() in 100..2300) nodeBounds.centerY().toFloat()
                    else if (targetBounds.centerY() in 100..2300) targetBounds.centerY().toFloat()
                    else null

                    var clicked = try {
                        if (targetNode.isClickable) {
                            targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        } else false
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ [Click Failed] Error clicking '${item.label}': ${e.localizedMessage}")
                        false
                    }

                    if (!clicked && tapX != null && tapY != null) {
                        Log.d(TAG, "👆 [Gesture Tap Fallback] Dispatching tap at ($tapX, $tapY) for '${item.label}'")
                        clicked = service.clickAt(tapX, tapY)
                    }

                    service.waitForWindowSettle(250L)
                    delay(POST_CLICK_MIN_WAIT_MS)

                    // If screen hasn't transitioned, try gesture tap fallback
                    val midRoot = service.rootInActiveWindow
                    val midSig = if (midRoot != null) {
                        val t = NodeExtractor.extractScreenTitle(midRoot) ?: ""
                        val itms = NodeExtractor.extractScreenItems(midRoot).map { it.label }
                        ScreenSignature.compute(t, itms)
                    } else ""

                    if (midSig == preClickSig && tapX != null && tapY != null) {
                        Log.d(TAG, "👆 [Second Attempt: Tap] Screen did not transition with ACTION_CLICK, trying gesture tap for '${item.label}'")
                        service.clickAt(tapX, tapY)
                        service.waitForWindowSettle(250L)
                        delay(POST_CLICK_MIN_WAIT_MS)
                    }

                    // If still hasn't transitioned and depth == 0, direct intent fallback
                    if (depth == 0) {
                        val checkRoot = service.rootInActiveWindow
                        val checkSig = if (checkRoot != null) {
                            val t = NodeExtractor.extractScreenTitle(checkRoot) ?: ""
                            val itms = NodeExtractor.extractScreenItems(checkRoot).map { it.label }
                            ScreenSignature.compute(t, itms)
                        } else ""
                        if (checkSig == preClickSig) {
                            val intentAction = matchIntentAction(item.label)
                            if (intentAction != null) {
                                Log.i(TAG, "🚀 [Direct Intent After Click Failed] Launching $intentAction for '${item.label}'")
                                try {
                                    val intent = Intent(intentAction).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    service.startActivity(intent)
                                    service.waitForWindowSettle(SETTLE_TIMEOUT_MS)
                                    delay(POST_CLICK_MIN_WAIT_MS)
                                    usedDirectIntent = true
                                } catch (e: Exception) {
                                    Log.w(TAG, "Direct intent $intentAction failed: ${e.localizedMessage}")
                                }
                            }
                        }
                    }
                }

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
                val isSameScreen = (postClickSig == preClickSig)
                if (isSameScreen && !usedDirectIntent) {
                    Log.d(TAG, "ℹ️ [No Screen Transition] '${item.label}' did not open a distinct sub-screen. Skipping recursion.")
                    continue
                }

                if (postClickSig in visitedSignatures) {
                    Log.d(TAG, "🔁 [Existing Screen Returned] '${item.label}' opened already visited screen '$postClickTitle'. Skipping recursion.")
                    if (postClickSig != preClickSig) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        service.waitForWindowSettle(BACK_SETTLE_MS)
                        delay(POST_CLICK_MIN_WAIT_MS)
                        verifyReturnToParent(screenTitle, preClickSig, depth, postClickSig)
                    }
                    continue
                }

                // Screen transitioned: recurse down into the sub-screen (DFS)
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
                verifyReturnToParent(screenTitle, preClickSig, depth, postClickSig)

                // If device returned to root homepage while at depth > 0, stop and unwind DFS
                if (depth > 0 && isHomepageActive(service.rootInActiveWindow)) {
                    Log.w(TAG, "⚠️ [Accidental Return to Homepage] Device returned to Settings homepage while at depth $depth. Unwinding stack.")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ [Branch Navigation Error] Error exploring '${item.label}': ${e.localizedMessage}")
                recoverToSettings(screenTitle)
                if (depth > 0 && isHomepageActive(service.rootInActiveWindow)) {
                    Log.w(TAG, "⚠️ [Recovery returned to Homepage] Unwinding stack from depth $depth.")
                    return
                }
            }
        }
    }

    /**
     * Attempt to return to the Settings app after accidentally navigating to an external app.
     * Tries up to 2 BACK presses (never inside our own app!), then re-launches Settings as fallback.
     */
    private suspend fun recoverToSettings(expectedScreenTitle: String) {
        Log.d(TAG, "🔄 [Recovery] Attempting to return to '$expectedScreenTitle' in Settings app...")
        for (attempt in 1..2) {
            val root = service.rootInActiveWindow
            val pkg = root?.packageName?.toString() ?: ""
            if (isSettingsPackage(pkg)) {
                Log.i(TAG, "✅ [Recovery Success] Returned to Settings.")
                return
            }
            // NEVER press BACK if we landed in SettingsLens — that will finish MainActivity!
            if (pkg.contains("settingslens")) {
                break
            }
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            service.waitForWindowSettle(BACK_SETTLE_MS)
            delay(POST_CLICK_MIN_WAIT_MS)
        }

        // Re-launch the Settings app directly
        Log.w(TAG, "⚠️ [Recovery Fallback] Re-launching Settings app directly...")
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
     * Verify we returned to the parent screen.
     * Prevents over-shooting BACK presses so crawl never accidentally exits Settings.
     */
    private suspend fun verifyReturnToParent(
        parentTitle: String,
        parentSig: String,
        depth: Int,
        postClickSig: String? = null
    ) {
        val returnRoot = service.rootInActiveWindow ?: return
        val returnPkg = returnRoot.packageName?.toString() ?: ""

        // 1. If we landed outside Settings, recover immediately
        if (!isSettingsPackage(returnPkg)) {
            Log.w(TAG, "⚠️ [Post-Back Recovery] Ended up outside Settings ($returnPkg). Recovering...")
            recoverToSettings(parentTitle)
            return
        }

        // 2. If at depth 0, ensure we are on SettingsHomepageActivity
        if (depth == 0) {
            if (!isHomepageActive(returnRoot)) {
                Log.w(TAG, "⚠️ [Not At Root Homepage] Currently on sub-screen. Pressing BACK once...")
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                service.waitForWindowSettle(BACK_SETTLE_MS)
                delay(POST_CLICK_MIN_WAIT_MS)
            }

            val checkRoot = service.rootInActiveWindow
            val checkPkg = checkRoot?.packageName?.toString() ?: ""
            if (!isHomepageActive(checkRoot) || !isSettingsPackage(checkPkg)) {
                Log.w(TAG, "🏠 [Reset to Root Homepage] Re-launching root Settings homepage directly...")
                try {
                    val intent = Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    service.startActivity(intent)
                    service.waitForWindowSettle(SETTLE_TIMEOUT_MS)
                    delay(POST_CLICK_MIN_WAIT_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reset Settings root: ${e.localizedMessage}")
                }
            }
            return
        }

        // 3. For depth > 0:
        // We already performed 1 GLOBAL_ACTION_BACK.
        // We ONLY press BACK again if we are provably still stuck on the exact child screen.
        if (postClickSig != null) {
            val currentTitle = NodeExtractor.extractScreenTitle(returnRoot) ?: ""
            val currentItems = NodeExtractor.extractScreenItems(returnRoot).map { it.label }
            val currentSig = ScreenSignature.compute(currentTitle, currentItems)

            if (currentSig == postClickSig) {
                Log.w(TAG, "⚠️ [Still on Child Screen] Back press did not transition. Pressing BACK once more...")
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                service.waitForWindowSettle(BACK_SETTLE_MS)
                delay(POST_CLICK_MIN_WAIT_MS)
            }
        }
    }

    private fun isHomepageActive(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val pkg = root.packageName?.toString() ?: ""
        if (!isSettingsPackage(pkg)) return false
        val cls = root.className?.toString() ?: ""
        if (cls.contains("SettingsHomepageActivity")) return true
        val hasSearchBar = root.findAccessibilityNodeInfosByViewId("com.android.settings:id/search_bar").isNotEmpty()
        if (hasSearchBar) return true
        val hasHomepageContainer = root.findAccessibilityNodeInfosByViewId("com.android.settings:id/settings_homepage_container").isNotEmpty()
        return hasHomepageContainer
    }

    /**
     * Fast collect of settings items on the current screen.
     * At depth 0 (Home screen): scrolls up to 3 times to index all main categories.
     * At depth >= 1 (Sub-screens): scrolls up to 2 times.
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

        val maxScrolls = if (depth == 0) 3 else 2
        var scrolls = 0
        while (scrolls < maxScrolls) {
            val root = service.rootInActiveWindow ?: break
            val scrolled = service.swipeUp()
            if (!scrolled) break
            delay(200)
            val beforeCount = collected.size
            addCurrentItems()
            if (collected.size == beforeCount) {
                break
            }
            scrolls++
        }

        // Fast rewind back to top
        if (scrolls > 0) {
            for (i in 0 until scrolls + 1) {
                service.swipeDown()
                delay(120)
            }
            delay(100)
        }

        return collected
    }

    /**
     * Locate a node on screen with robust scrolling.
     * Checks visible screen first, then scrolls forward up to 4 times.
     * If not found, rewinds to top and scans downwards.
     */
    private suspend fun findNodeWithScrolling(selector: NodeSelector, depth: Int = 0): AccessibilityNodeInfo? {
        var root = service.rootInActiveWindow ?: return null
        var node = NodeExtractor.findClickableNode(root, selector)
        if (node != null) {
            ensureNodeCentered(node)
            return NodeExtractor.findClickableNode(service.rootInActiveWindow, selector) ?: node
        }

        // 1. Try scrolling forward up to 4 times
        var attempts = 0
        while (attempts < 4) {
            val scrolled = service.swipeUp()
            if (!scrolled) break
            delay(200)
            root = service.rootInActiveWindow ?: break
            node = NodeExtractor.findClickableNode(root, selector)
            if (node != null) {
                ensureNodeCentered(node)
                return NodeExtractor.findClickableNode(service.rootInActiveWindow, selector) ?: node
            }
            attempts++
        }

        // 2. If not found, rewind back to top and scan down
        for (i in 0 until 4) {
            service.swipeDown()
            delay(120)
        }
        delay(100)
        root = service.rootInActiveWindow ?: return null
        node = NodeExtractor.findClickableNode(root, selector)
        if (node != null) {
            ensureNodeCentered(node)
            return NodeExtractor.findClickableNode(service.rootInActiveWindow, selector) ?: node
        }

        attempts = 0
        while (attempts < 4) {
            val scrolled = service.swipeUp()
            if (!scrolled) break
            delay(200)
            root = service.rootInActiveWindow ?: break
            node = NodeExtractor.findClickableNode(root, selector)
            if (node != null) {
                ensureNodeCentered(node)
                return NodeExtractor.findClickableNode(service.rootInActiveWindow, selector) ?: node
            }
            attempts++
        }

        return null
    }

    private suspend fun ensureNodeCentered(node: AccessibilityNodeInfo) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.bottom > 2150) {
            service.swipeUp()
            delay(200)
        } else if (bounds.top < 200 && bounds.top > 0) {
            service.swipeDown()
            delay(200)
        }
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent
        var hops = 0
        while (parent != null && hops < 4) {
            val b = Rect()
            parent.getBoundsInScreen(b)
            if (parent.isClickable && b.height() in 40..450 && b.width() > 100) {
                return parent
            }
            parent = parent.parent
            hops++
        }
        return null
    }

    private fun isSettingsPackage(packageName: String): Boolean {
        if (packageName.contains("settingslens") || packageName == service.packageName) return false
        return packageName.contains("android.settings") ||
                packageName.contains("settings.intelligence") ||
                packageName.contains("vivo.settings") ||
                packageName.contains("com.samsung.android.settings") ||
                packageName.contains("settings")
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
    private fun isActionListScreen(title: String?, depth: Int = 0, breadcrumbs: List<String> = emptyList()): Boolean {
        if (title == null) return false
        val lower = title.lowercase().trim()
        if (ACTION_LIST_SCREEN_TITLES.any { lower.contains(it) }) return true
        if (depth >= 1 && (lower.contains("apps") || lower.contains("app list"))) return true
        if (depth >= 2 && (lower.contains("ringtone") || lower.contains("sound") || lower.contains("tone"))) return true
        if (breadcrumbs.any { it.contains("storage", ignoreCase = true) } && lower.contains("apps")) return true
        return false
    }

    /**
     * Skip clicking individual network SSIDs on Wi-Fi screen or paired devices on Bluetooth screen
     * to avoid triggering password dialogs or connection attempts.
     */
    private fun shouldSkipSubBranch(screenTitle: String, itemLabel: String): Boolean {
        val lowerScreen = screenTitle.lowercase()
        val lowerItem = itemLabel.lowercase()

        // On Wi-Fi screen, do not click individual network SSIDs
        if (lowerScreen.contains("wi-fi") || lowerScreen.contains("wifi") || lowerScreen.contains("wlan")) {
            val allowedWifiSubmenus = listOf("preference", "saved", "direct", "certificate", "manage", "advanced", "data usage", "network")
            if (!allowedWifiSubmenus.any { lowerItem.contains(it) }) {
                return true // Skip clicking SSIDs
            }
        }

        // On Bluetooth screen, do not click individual paired/available devices
        if (lowerScreen.contains("bluetooth")) {
            val allowedBtSubmenus = listOf("pair", "device name", "file", "advanced", "preference", "received")
            if (!allowedBtSubmenus.any { lowerItem.contains(it) }) {
                return true // Skip clicking device names
            }
        }

        return false
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
            ?: KNOWN_INTENT_ACTIONS.entries.find { lower.contains(it.key) || it.key.contains(lower) }?.value
    }

    private fun generateNodeId(): String {
        nodeCounter++
        return "node_$nodeCounter"
    }
}
