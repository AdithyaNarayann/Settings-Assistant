package com.settingslens.app.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import com.settingslens.app.R
import com.settingslens.app.accessibility.NavigateEngine
import com.settingslens.app.accessibility.SettingsAccessibilityService
import com.settingslens.app.data.ApiClient
import com.settingslens.app.data.GraphStorage
import com.settingslens.app.data.LocalResolver
import com.settingslens.app.data.ResolveRequest
import com.settingslens.app.model.ResolveResponse
import com.settingslens.app.voice.TtsManager
import com.settingslens.app.voice.VoiceCaptureManager
import kotlinx.coroutines.*
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Floating bubble overlay service.
 *
 * Resilience & Looper Design:
 * - Operates as a foreground service with low-importance persistent notification.
 * - WindowManager view operations are guarded against stale tokens and detached view exceptions.
 * - CoroutineScope is protected with a CoroutineExceptionHandler to ensure network or parsing
 *   exceptions never crash the Main Looper or kill the service.
 * - Human-first diagnostic logging clearly traces the end-to-end user loop:
 *   Bubble Tap -> Voice Audio -> STT -> Backend Resolve -> Accessibility Replay -> Focus Highlight.
 */
class BubbleService : Service() {

    companion object {
        private const val TAG = "SettingsLens:Bubble"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "settings_lens_bubble"
        private var activeInstance: BubbleService? = null

        val isRunning: Boolean
            get() = activeInstance != null

        fun onPackageChanged(pkg: String, isSettings: Boolean) {
            activeInstance?.let { service ->
                service.ensureAccessibilityOverlay()
                service.updateVisibilityForPackage(pkg, isSettings)
            }
        }

        fun checkVisibilityNow() {
            val a11y = com.settingslens.app.accessibility.SettingsAccessibilityService.instance
            val pkg = a11y?.getActivePackage() ?: ""
            val inSettings = a11y?.isSettingsPackage(pkg) ?: false
            activeInstance?.updateVisibilityForPackage(pkg, inSettings)
        }

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "⚠️ [Overlay Permission Missing] Cannot start bubble without overlay permission.")
                return
            }
            val intent = Intent(context, BubbleService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "💥 [Start Service Failed] Unable to launch BubbleService: ${e.localizedMessage}", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, BubbleService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ [Stop Service Notice] ${e.localizedMessage}")
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private var targetWindowManager: WindowManager? = null
    private var bubbleView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var voiceCapture: VoiceCaptureManager
    private lateinit var ttsManager: TtsManager
    private lateinit var graphStorage: GraphStorage
    private val mainHandler = Handler(Looper.getMainLooper())

    // CoroutineExceptionHandler protects against crashes on the Main Looper
    private val looperExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "🚨 [Main Looper Guard] Recovered from unhandled coroutine error: ${throwable.localizedMessage}", throwable)
        setBubbleState(BubbleState.IDLE)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + looperExceptionHandler)
    private var currentGraphId: String? = null
    private var clarificationRound = 0
    private var conversationState: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🟢 [Bubble Service Started] Initializing floating assistant bubble...")
        activeInstance = this

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        graphStorage = GraphStorage(this)
        voiceCapture = VoiceCaptureManager(this)
        ttsManager = TtsManager(this)

        try {
            createNotificationChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    0
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            Log.d(TAG, "🔔 [Foreground Notification] Bubble service foreground notification posted.")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ [FGS Notice] Foreground service notification skipped: ${e.localizedMessage}")
        }

        voiceCapture.initialize()
        setupBubbleView()
        setupVoiceCallbacks()
    }

    override fun onDestroy() {
        Log.i(TAG, "🔴 [Bubble Service Stopped] Cleaning up overlay views and resources...")
        activeInstance = null
        serviceScope.cancel()

        bubbleView?.let { view ->
            try {
                (targetWindowManager ?: windowManager).removeView(view)
                Log.d(TAG, "🧹 [Overlay Removed] Floating view detached from WindowManager.")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ [Overlay Removal Notice] View was already detached: ${e.localizedMessage}")
            }
            bubbleView = null
        }

        voiceCapture.destroy()
        ttsManager.shutdown()
        super.onDestroy()
    }

    fun ensureAccessibilityOverlay() {
        val a11y = com.settingslens.app.accessibility.SettingsAccessibilityService.instance ?: return
        if (::layoutParams.isInitialized && layoutParams.type != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
            Log.i(TAG, "🔄 [Upgrading Overlay] Upgrading overlay window to TYPE_ACCESSIBILITY_OVERLAY for full Settings visibility...")
            bubbleView?.let { view ->
                try {
                    (targetWindowManager ?: windowManager).removeView(view)
                } catch (e: Exception) {
                    // benign
                }
            }
            bubbleView = null
            setupBubbleView()
        }
    }

    private fun setupBubbleView() {
        try {
            val a11y = com.settingslens.app.accessibility.SettingsAccessibilityService.instance
            val wm = if (a11y != null) {
                a11y.getSystemService(WINDOW_SERVICE) as WindowManager
            } else {
                windowManager
            }
            targetWindowManager = wm
            val windowType = if (a11y != null) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }

            val view = LayoutInflater.from(a11y ?: this).inflate(R.layout.overlay_bubble, null)
            bubbleView = view

            val sizePx = (56 * resources.displayMetrics.density).toInt()
            layoutParams = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 60
                y = 350
            }

            val activePkg = a11y?.getActivePackage() ?: ""
            val currentlyInSettings = a11y?.isSettingsPackage(activePkg) ?: false

            if (currentlyInSettings) {
                view.visibility = View.VISIBLE
                layoutParams.width = sizePx
                layoutParams.height = sizePx
                layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                view.visibility = View.GONE
                layoutParams.width = 0
                layoutParams.height = 0
                layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            Log.i(TAG, "🫧 [Bubble Setup] Active pkg: '$activePkg', inSettings: $currentlyInSettings -> visibility: ${if (currentlyInSettings) "VISIBLE" else "GONE"} (type: $windowType)")

            setupTouchListener(view)
            wm.addView(view, layoutParams)
            Log.i(TAG, "🫧 [Bubble Attached] Floating overlay attached with window type $windowType.")
        } catch (e: Exception) {
            Log.e(TAG, "💥 [Overlay Attachment Failed] Cannot display bubble: ${e.localizedMessage}", e)
        }
    }

    fun updateVisibilityForPackage(pkg: String, isSettings: Boolean) {
        mainHandler.post {
            bubbleView?.let { view ->
                val targetVisibility = if (isSettings) View.VISIBLE else View.GONE
                val sizePx = (56 * resources.displayMetrics.density).toInt()
                val targetWidth = if (isSettings) sizePx else 0
                val targetHeight = if (isSettings) sizePx else 0

                if (view.visibility != targetVisibility || layoutParams.width != targetWidth) {
                    view.visibility = targetVisibility
                    try {
                        layoutParams.width = targetWidth
                        layoutParams.height = targetHeight
                        if (isSettings) {
                            layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                        } else {
                            layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        }
                        (targetWindowManager ?: windowManager).updateViewLayout(view, layoutParams)
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ [Layout Update Notice] ${e.localizedMessage}")
                    }
                    Log.i(TAG, "🫧 [Bubble Visibility Updated] Package: $pkg, inSettings: $isSettings -> visibility: ${if (isSettings) "VISIBLE" else "GONE"}")
                }
            }
        }
    }

    private fun setupTouchListener(view: View) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (Math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                            isClick = false
                        }
                        layoutParams.x = (initialX + dx).toInt()
                        layoutParams.y = (initialY + dy).toInt()
                        try {
                            bubbleView?.let { (targetWindowManager ?: windowManager).updateViewLayout(it, layoutParams) }
                        } catch (e: Exception) {
                            // Suppress benign layout update exception during drag
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            onBubbleTapped()
                        } else {
                            snapToEdge()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun snapToEdge() {
        val view = bubbleView ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val targetX = if (layoutParams.x + view.width / 2 < screenWidth / 2) {
            0
        } else {
            screenWidth - view.width
        }

        ValueAnimator.ofInt(layoutParams.x, targetX).apply {
            duration = 200
            addUpdateListener {
                layoutParams.x = it.animatedValue as Int
                try {
                    bubbleView?.let { (targetWindowManager ?: windowManager).updateViewLayout(it, layoutParams) }
                } catch (e: Exception) {
                    // Suppress animation update if view detached
                }
            }
            start()
        }
    }

    // ─── Voice flow ─────────────────────────────────────────────────────

    private fun onBubbleTapped() {
        Log.i(TAG, "👆 [Bubble Tapped] User initiated voice command session.")
        clarificationRound = 0
        conversationState = null

        val graph = graphStorage.loadGraph()
        currentGraphId = graph?.graphId

        if (graph == null) {
            Log.w(TAG, "⚠️ [Graph Missing] No settings graph discovered yet.")
            ttsManager.speak("Please open the Settings Lens app and build your settings graph first.")
            return
        }

        setBubbleState(BubbleState.LISTENING)
        voiceCapture.startListening()
    }

    private fun setupVoiceCallbacks() {
        voiceCapture.setListener(object : VoiceCaptureManager.VoiceCaptureListener {
            override fun onReadyForSpeech() {
                setBubbleState(BubbleState.LISTENING)
            }

            override fun onPartialResult(partialTranscript: String) {
                Log.d(TAG, "👂 [Hearing Voice] \"$partialTranscript\"")
            }

            override fun onResult(transcript: String) {
                Log.i(TAG, "💡 [Speech Captured] Request: \"$transcript\"")
                setBubbleState(BubbleState.THINKING)
                resolveTranscript(transcript)
            }

            override fun onError(errorMessage: String) {
                Log.w(TAG, "⚠️ [Voice Input Notice] $errorMessage")
                setBubbleState(BubbleState.IDLE)

                // Only speak if it wasn't a silent timeout
                if (!errorMessage.contains("No speech", ignoreCase = true) &&
                    !errorMessage.contains("No words", ignoreCase = true)
                ) {
                    ttsManager.speak(errorMessage)
                }
            }

            override fun onVolumeChanged(rmsdB: Float) {
                // Subtle pulse hook
            }
        })
    }

    private fun resolveTranscript(transcript: String) {
        val graph = graphStorage.loadGraph()
        if (graph == null) {
            setBubbleState(BubbleState.IDLE)
            ttsManager.speak("No settings graph found. Please build the graph in the app first.")
            return
        }

        serviceScope.launch {
            // Step 1: Ultra-fast on-device match (< 50ms)
            val localResponse = LocalResolver.resolve(graph, transcript)
            if (localResponse.isResolved && (localResponse.confidence ?: 0.0) >= 0.70) {
                val conf = ((localResponse.confidence ?: 1.0) * 100).toInt()
                Log.i(TAG, "⚡ [Instant Match] On-device resolution succeeded with $conf% confidence in <50ms!")
                handleResolveResponse(localResponse)
                return@launch
            }

            // Step 2: Try backend with 2.0s fast timeout if local was low confidence
            var resolvedOnline = false
            try {
                var graphId = currentGraphId ?: graph.graphId
                if (graphId == null) {
                    val uploadResponse = withTimeout(1500L) {
                        withContext(Dispatchers.IO) { ApiClient.api.uploadGraph(graph) }
                    }
                    graphId = uploadResponse.graphId
                    graph.graphId = graphId
                    graphStorage.saveGraph(graph)
                    currentGraphId = graphId
                }

                if (graphId != null) {
                    Log.d(TAG, "🌐 [Querying Assistant] Querying backend resolver with 2.0s timeout...")
                    val response = withTimeout(2000L) {
                        withContext(Dispatchers.IO) {
                            ApiClient.api.resolve(
                                ResolveRequest(
                                    graphId = graphId,
                                    transcript = transcript,
                                    conversationState = conversationState
                                )
                            )
                        }
                    }
                    resolvedOnline = true
                    handleResolveResponse(response)
                }
            } catch (e: Exception) {
                Log.w(TAG, "🌐 [Backend Notice] Online resolution fast check: ${e.localizedMessage}")
            }

            // Step 3: Local resolution fallback
            if (!resolvedOnline) {
                Log.i(TAG, "📱 [Offline Resolver] Using best local match for: \"$transcript\"")
                handleResolveResponse(localResponse)
            }
        }
    }

    private fun handleResolveResponse(response: ResolveResponse) {
        when {
            response.isResolved && response.path != null -> {
                val confidencePct = ((response.confidence ?: 1.0) * 100).toInt()
                Log.i(TAG, "🎯 [Setting Resolved] Match found with $confidencePct% confidence. Navigating...")
                setBubbleState(BubbleState.NAVIGATING)

                val service = SettingsAccessibilityService.instance
                if (service == null) {
                    val a11yMissing = "Accessibility Service is not enabled. Please enable it in Settings to allow navigation."
                    Log.w(TAG, "⚠️ [A11y Inactive] $a11yMissing")
                    ttsManager.speak(a11yMissing)
                    setBubbleState(BubbleState.IDLE)
                    return
                }

                service.navigate(response.path, object : NavigateEngine.NavigateListener {
                    override fun onStepStarted(stepIndex: Int, totalSteps: Int, label: String?) {
                        Log.d(TAG, "🧭 [Step ${stepIndex + 1}/$totalSteps] $label")
                    }

                    override fun onStepCompleted(stepIndex: Int) {}

                    override fun onNavigationComplete(result: NavigateEngine.NavigationResult) {
                        when (result) {
                            is NavigateEngine.NavigationResult.Success -> {
                                val name = result.highlightedLabel ?: "target setting"
                                Log.i(TAG, "✨ [Navigation Succeeded] Reached and highlighted: $name")
                                setBubbleState(BubbleState.IDLE)
                            }

                            is NavigateEngine.NavigationResult.Failed -> {
                                Log.w(TAG, "❌ [Navigation Failed] ${result.reason}")
                                ttsManager.speak("I couldn't navigate to that setting. The screen layout may have changed. Please tap Rebuild Graph in the app.")
                                setBubbleState(BubbleState.IDLE)
                            }
                        }
                    }
                })
            }

            response.isClarification && response.question != null -> {
                if (clarificationRound >= 1) {
                    Log.i(TAG, "🛑 [Clarification Cap] Reached 1 clarification round cap for demo. Proceeding safely.")
                    ttsManager.speak("Let me try opening the closest matching setting.")
                    setBubbleState(BubbleState.IDLE)
                } else {
                    clarificationRound++
                    conversationState = response.conversationState
                    Log.i(TAG, "❓ [Asking Clarification] Assistant asking: \"${response.question}\"")

                    ttsManager.speak(response.question)

                    serviceScope.launch {
                        delay(3500) // Allow TTS to vocalize the question
                        setBubbleState(BubbleState.LISTENING)
                        voiceCapture.startListening()
                    }
                }
            }

            else -> {
                Log.d(TAG, "❓ [Unrecognized Query] Backend returned no confident match.")
                ttsManager.speak("I'm not sure which setting you're looking for. Could you describe it differently?")
                setBubbleState(BubbleState.IDLE)
            }
        }
    }

    // ─── Bubble visual state ────────────────────────────────────────────

    private enum class BubbleState {
        IDLE, LISTENING, THINKING, NAVIGATING
    }

    private fun setBubbleState(state: BubbleState) {
        val iconView = bubbleView?.findViewById<ImageView>(R.id.bubbleIcon) ?: return
        when (state) {
            BubbleState.IDLE -> {
                iconView.alpha = 1.0f
            }
            BubbleState.LISTENING -> {
                iconView.alpha = 0.7f
            }
            BubbleState.THINKING -> {
                iconView.alpha = 0.5f
            }
            BubbleState.NAVIGATING -> {
                iconView.alpha = 0.3f
            }
        }
    }

    // ─── Notification ───────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Settings Lens Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Settings Lens floating assistant bubble active."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Settings Lens Active")
                .setContentText("Tap the floating bubble anytime to ask for a setting.")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Settings Lens Active")
                .setContentText("Tap the floating bubble anytime to ask for a setting.")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        }
    }
}
