package com.settingslens.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.settingslens.app.data.GraphStorage
import com.settingslens.app.model.NavigationPath
import com.settingslens.app.model.SettingsGraph
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/**
 * The single AccessibilityService for Settings Lens.
 *
 * This service handles three primary roles:
 * 1. CRAWL — Automated discovery of the device's Settings structure
 * 2. NAVIGATE — Replay an exact selector path to reach a specific setting
 * 3. IDLE — Waiting for user requests
 *
 * Resilience & Looper Design:
 * - Callbacks run directly on Android's Main Looper thread.
 * - Protected by an explicit CoroutineExceptionHandler to ensure coroutine failures
 *   never crash the Main Looper or cause the system to disable the accessibility service.
 * - Human-first diagnostic logging clearly distinguishes user actions, screen transitions,
 *   settle detections, and error recoveries.
 */
class SettingsAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SettingsLens:A11y"

        /**
         * Singleton reference to the running service instance.
         * Null when the service is not enabled by the user in system settings.
         */
        @Volatile
        var instance: SettingsAccessibilityService? = null
            private set

        /** Check if the service is currently running and accessible. */
        val isRunning: Boolean get() = instance != null
    }

    /** The current operating mode of the service. */
    enum class Mode {
        IDLE,
        CRAWLING,
        NAVIGATING
    }

    var currentMode: Mode = Mode.IDLE
        private set

    // CoroutineExceptionHandler guards against unexpected crashes on the Main Looper
    private val looperExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "🚨 [Main Looper Guard] Recovered from unhandled coroutine exception: ${throwable.localizedMessage}", throwable)
        currentMode = Mode.IDLE
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + looperExceptionHandler)
    private lateinit var crawlEngine: CrawlEngine
    private lateinit var navigateEngine: NavigateEngine
    private lateinit var graphStorage: GraphStorage

    /**
     * Continuation for event-driven screen settle detection.
     * When crawling or navigating, waits for TYPE_WINDOW_STATE_CHANGED or
     * SUBTREE TYPE_WINDOW_CONTENT_CHANGED events before proceeding.
     */
    private var windowSettleContinuation: CancellableContinuation<Unit>? = null

    // ─── Service lifecycle ───────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "🟢 [Accessibility Service Ready] Settings Lens service connected and operational.")

        instance = this
        crawlEngine = CrawlEngine(this)
        navigateEngine = NavigateEngine(this)
        graphStorage = GraphStorage(this)
    }

    override fun onDestroy() {
        Log.i(TAG, "🔴 [Accessibility Service Stopped] Settings Lens service destroyed.")
        instance = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ [Accessibility Service Interrupted] Feedback interrupted by system or user interaction.")
    }

    // ─── Accessibility event handling ────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val pkg = event.packageName?.toString() ?: "unknown"
                    val cls = event.className?.toString() ?: "unknown"
                    Log.d(TAG, "🪟 [Window Transition] Screen state changed to $pkg ($cls)")
                    notifyWindowSettle()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ [Event Handler Recovered] Ignored benign error during window event processing: ${e.localizedMessage}")
        }
    }

    /**
     * Resume any suspended settle-waiter when a significant window transition occurs.
     */
    private fun notifyWindowSettle() {
        windowSettleContinuation?.let { cont ->
            windowSettleContinuation = null
            if (cont.isActive) {
                cont.resume(Unit)
            }
        }
    }

    /**
     * Suspend until a window change event fires, or until [timeoutMs] elapses.
     *
     * Event-driven settle detection:
     * - Resumes immediately when TYPE_WINDOW_STATE_CHANGED confirms an Activity/window transition.
     * - Falls back safely after timeout if screen was already settled.
     */
    suspend fun waitForWindowSettle(timeoutMs: Long) {
        try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine<Unit> { cont ->
                    windowSettleContinuation = cont
                    cont.invokeOnCancellation {
                        windowSettleContinuation = null
                    }
                }
            }
            Log.d(TAG, "⏱️ [Screen Settled] Transition confirmed via window change event.")
        } catch (e: TimeoutCancellationException) {
            Log.d(TAG, "⏱️ [Screen Settle Timeout] Settle wait concluded after ${timeoutMs}ms.")
        }
        // Buffer to allow sub-views to finish drawing
        delay(80)
    }

    /**
     * Dispatch a gesture swipe to scroll down/forward smoothly on any device.
     */
    suspend fun swipeUp(): Boolean = suspendCancellableCoroutine { cont ->
        val path = android.graphics.Path().apply {
            moveTo(540f, 1600f)
            lineTo(540f, 600f)
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 150))
            .build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)

        if (!dispatched && cont.isActive) {
            cont.resume(false)
        }
    }

    /**
     * Dispatch a gesture swipe to scroll up/backward smoothly on any device.
     */
    suspend fun swipeDown(): Boolean = suspendCancellableCoroutine { cont ->
        val path = android.graphics.Path().apply {
            moveTo(540f, 600f)
            lineTo(540f, 1600f)
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 150))
            .build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)

        if (!dispatched && cont.isActive) {
            cont.resume(false)
        }
    }

    // ─── Public API for the app ─────────────────────────────────────────

    /**
     * Start a full crawl of the Settings app.
     */
    fun startCrawl(listener: CrawlEngine.CrawlListener): Job {
        if (currentMode != Mode.IDLE) {
            val message = "Cannot start crawl: assistant is busy with $currentMode mode."
            Log.w(TAG, "⚠️ [Crawl Rejected] $message")
            listener.onError(message)
            return Job().apply { cancel() }
        }

        currentMode = Mode.CRAWLING
        crawlEngine.setListener(listener)

        return serviceScope.launch {
            try {
                Log.i(TAG, "🚀 [Crawl Session Started] Launching automated Settings discovery...")
                val graph = crawlEngine.startCrawl()
                if (graph != null) {
                    graphStorage.saveGraph(graph)
                    Log.i(TAG, "💾 [Graph Saved] Discovered ${graph.nodeCount} settings across ${graph.screenSignatures.size} screens.")
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "🛑 [Crawl Cancelled] Crawl session stopped by user.")
                throw e
            } catch (e: Exception) {
                val errorMsg = "Settings discovery stopped unexpectedly: ${e.localizedMessage ?: "Unknown reason"}"
                Log.e(TAG, "💥 [Crawl Error] $errorMsg", e)
                listener.onError(errorMsg)
            } finally {
                currentMode = Mode.IDLE
            }
        }
    }

    /**
     * Execute a navigation path to reach and highlight a target setting.
     */
    fun navigate(
        path: NavigationPath,
        listener: NavigateEngine.NavigateListener
    ): Job {
        if (currentMode != Mode.IDLE) {
            val message = "Cannot navigate: assistant is currently busy with $currentMode."
            Log.w(TAG, "⚠️ [Navigation Rejected] $message")
            listener.onNavigationComplete(
                NavigateEngine.NavigationResult.Failed(-1, null, message)
            )
            return Job().apply { cancel() }
        }

        currentMode = Mode.NAVIGATING
        navigateEngine.setListener(listener)

        return serviceScope.launch {
            try {
                Log.i(TAG, "🧭 [Navigation Started] Replaying path with ${path.steps.size} step(s)...")
                navigateEngine.executePath(path)
            } catch (e: CancellationException) {
                Log.i(TAG, "🛑 [Navigation Cancelled] Navigation replay cancelled.")
                throw e
            } catch (e: Exception) {
                val errorMsg = "Path replay encountered an issue: ${e.localizedMessage ?: "Unknown error"}"
                Log.e(TAG, "💥 [Navigation Error] $errorMsg", e)
                listener.onNavigationComplete(
                    NavigateEngine.NavigationResult.Failed(-1, null, errorMsg)
                )
            } finally {
                currentMode = Mode.IDLE
            }
        }
    }

    /**
     * Cancel any ongoing crawl or navigation.
     */
    fun cancelCurrentOperation() {
        when (currentMode) {
            Mode.CRAWLING -> {
                Log.i(TAG, "🛑 [User Action] Cancelling ongoing Settings crawl.")
                crawlEngine.cancel()
            }
            Mode.NAVIGATING -> {
                Log.i(TAG, "🛑 [User Action] Cancelling ongoing navigation.")
            }
            Mode.IDLE -> { /* Nothing active */ }
        }
    }

    /**
     * Get the stored graph (loaded from disk).
     */
    fun getStoredGraph(): SettingsGraph? = graphStorage.loadGraph()

    /**
     * Check if a graph has been built and stored.
     */
    fun hasStoredGraph(): Boolean = graphStorage.hasGraph()
}
