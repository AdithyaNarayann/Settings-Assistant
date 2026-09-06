package com.settingslens.app.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Voice capture manager wrapping Android's SpeechRecognizer.
 *
 * Resilience & Looper Design:
 * - SpeechRecognizer requires execution strictly on the primary Looper thread.
 * - All actions (initialize, startListening, stopListening, cancel, destroy) are dispatched
 *   safely via a main-looper Handler to protect against thread-affinity crashes.
 * - Uses standard system SpeechRecognizer first with cloud + offline fallback to prevent
 *   error code 13 (ERROR_LANGUAGE_UNAVAILABLE) from strict on-device constraints.
 * - Defaults to device system locale instead of hardcoded region codes.
 * - Translates obscure integer error codes into empathetic, human-friendly diagnostics.
 */
class VoiceCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "SettingsLens:Voice"
    }

    interface VoiceCaptureListener {
        fun onReadyForSpeech()
        fun onPartialResult(partialTranscript: String)
        fun onResult(transcript: String)
        fun onError(errorMessage: String)
        fun onVolumeChanged(rmsdB: Float)
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var listener: VoiceCaptureListener? = null
    private var isListening = false
    private var hasAttemptedFallback = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setListener(listener: VoiceCaptureListener) {
        this.listener = listener
    }

    /**
     * Creates the best available SpeechRecognizer instance.
     * Prefers standard system recognizer to avoid error 13 (ERROR_LANGUAGE_UNAVAILABLE).
     */
    private fun createRecognizerInstance(): SpeechRecognizer? {
        // 1. Try standard system speech recognizer (uses default speech service, online + offline)
        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            if (recognizer != null) {
                Log.i(TAG, "🎙️ [Speech Recognizer] Initialized system default SpeechRecognizer.")
                return recognizer
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ [Speech Init] Failed to create default SpeechRecognizer: ${e.localizedMessage}")
        }

        // 2. Try explicit Google Quicksearchbox recognition service
        try {
            val googleComponent = ComponentName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
            )
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context, googleComponent)
            if (recognizer != null) {
                Log.i(TAG, "🎙️ [Speech Recognizer] Initialized Google app SpeechRecognizer.")
                return recognizer
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ [Speech Init] Failed to create Google SpeechRecognizer: ${e.localizedMessage}")
        }

        // 3. Fallback to on-device recognition if available on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            try {
                val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                if (recognizer != null) {
                    Log.i(TAG, "🎙️ [Speech Recognizer] Initialized on-device SpeechRecognizer as fallback.")
                    return recognizer
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ [Speech Init] Failed to create on-device SpeechRecognizer: ${e.localizedMessage}")
            }
        }

        return null
    }

    /**
     * Initialize SpeechRecognizer safely on the Main Looper.
     */
    fun initialize(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            val errorMsg = "Speech recognition is not installed or available on this device."
            Log.e(TAG, "❌ [Speech Unavailable] $errorMsg")
            listener?.onError(errorMsg)
            return false
        }

        mainHandler.post {
            try {
                destroyInternal()
                speechRecognizer = createRecognizerInstance()
                speechRecognizer?.setRecognitionListener(createRecognitionListener())
                if (speechRecognizer == null) {
                    val errorMsg = "Could not initialize any speech recognition engine on device."
                    Log.e(TAG, "💥 [Speech Init Failure] $errorMsg")
                    listener?.onError(errorMsg)
                }
            } catch (e: Exception) {
                val errorMsg = "Failed to initialize voice recognition engine: ${e.localizedMessage}"
                Log.e(TAG, "💥 [Speech Init Failure] $errorMsg", e)
                listener?.onError(errorMsg)
            }
        }
        return true
    }

    /**
     * Begin listening for voice queries.
     * Defaults to device's default locale (e.g. en-US, en-IN) rather than hardcoding.
     */
    fun startListening(languageCode: String? = null) {
        hasAttemptedFallback = false
        startListeningInternal(languageCode)
    }

    private fun startListeningInternal(languageCode: String?) {
        mainHandler.post {
            if (isListening) {
                Log.d(TAG, "🎙️ [Already Listening] Active voice listening session already in progress.")
                return@post
            }

            if (speechRecognizer == null) {
                Log.w(TAG, "⚠️ [Speech Recognizer Null] Re-initializing speech recognizer before listening...")
                speechRecognizer = createRecognizerInstance()
                speechRecognizer?.setRecognitionListener(createRecognitionListener())
            }

            val defaultLocaleTag = Locale.getDefault().toLanguageTag()
            val targetLang = languageCode?.takeIf { it.isNotBlank() } ?: defaultLocaleTag

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                if (targetLang.isNotBlank()) {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLang)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, targetLang)
                }
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // Disallow forcing offline-only mode so missing offline packs don't crash with error 13
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }

            try {
                speechRecognizer?.startListening(intent)
                isListening = true
                Log.i(TAG, "👂 [Listening Started] Awaiting speech in language: $targetLang...")
            } catch (e: Exception) {
                isListening = false
                val errorMsg = "Could not activate microphone for listening: ${e.localizedMessage}"
                Log.e(TAG, "💥 [Start Listening Error] $errorMsg", e)
                listener?.onError(errorMsg)
            }
        }
    }

    /**
     * Fallback listening without explicit language extras when error 12 or 13 occurs.
     */
    private fun retryListeningWithSystemDefaults() {
        mainHandler.post {
            if (isListening) return@post

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }

            try {
                speechRecognizer?.startListening(intent)
                isListening = true
                Log.i(TAG, "👂 [Listening Fallback] Retrying listening with system default speech model...")
            } catch (e: Exception) {
                isListening = false
                Log.e(TAG, "💥 [Fallback Listening Error] ${e.localizedMessage}", e)
                listener?.onError("Could not activate microphone: ${e.localizedMessage}")
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            if (isListening) {
                try {
                    speechRecognizer?.stopListening()
                    Log.d(TAG, "⏹️ [Listening Stopped] User finished speaking or mic stopped.")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ [Stop Error] Error stopping speech recognizer: ${e.localizedMessage}")
                }
                isListening = false
            }
        }
    }

    fun cancel() {
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ [Cancel Error] Error cancelling speech recognizer: ${e.localizedMessage}")
            }
            isListening = false
            hasAttemptedFallback = false
        }
    }

    fun destroy() {
        mainHandler.post {
            destroyInternal()
        }
    }

    private fun destroyInternal() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ [Destroy Error] Cleaned up speech recognizer with notice: ${e.localizedMessage}")
        } finally {
            speechRecognizer = null
            isListening = false
            hasAttemptedFallback = false
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "🟢 [Microphone Active] Listening for voice commands now.")
                listener?.onReadyForSpeech()
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "🗣️ [Voice Detected] User started speaking...")
            }

            override fun onRmsChanged(rmsdB: Float) {
                listener?.onVolumeChanged(rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "🤫 [Speech Ended] Audio captured, finalizing transcription...")
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false

                // Error 13 (ERROR_LANGUAGE_UNAVAILABLE) or 12 (ERROR_LANGUAGE_NOT_SUPPORTED)
                // If this occurs, automatically retry once with system defaults without disturbing the user
                if ((error == 13 || error == 12) && !hasAttemptedFallback) {
                    hasAttemptedFallback = true
                    Log.w(TAG, "⚠️ [Speech Notice] Language model not ready (code $error). Retrying with system defaults...")
                    retryListeningWithSystemDefaults()
                    return
                }

                val humanExplanation = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Microphone audio recording error. Please check if another app is using the mic."
                    SpeechRecognizer.ERROR_CLIENT -> "Device speech client encountered a temporary glitch. Please try again."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for voice commands."
                    SpeechRecognizer.ERROR_NETWORK -> "Network issue encountered during speech recognition. Checking connection..."
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition network request timed out. Please try again."
                    SpeechRecognizer.ERROR_NO_MATCH -> "Couldn't quite catch what you said. Please speak again clearly."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice recognizer is still busy. Please wait a moment."
                    SpeechRecognizer.ERROR_SERVER -> "Speech server temporarily unavailable. Please try again in a moment."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was detected. Tap the bubble to speak when ready."
                    10 /* ERROR_TOO_MANY_REQUESTS */ -> "Too many voice requests. Please wait a moment and try again."
                    11 /* ERROR_SERVER_DISCONNECTED */ -> "Speech server disconnected. Please try again."
                    12 /* ERROR_LANGUAGE_NOT_SUPPORTED */ -> "Speech recognition language is not supported on this device."
                    13 /* ERROR_LANGUAGE_UNAVAILABLE */ -> "Speech recognition language is currently unavailable. Please check Google Speech settings."
                    14 /* ERROR_CANNOT_CHECK_SUPPORT */ -> "Could not check speech recognition language support."
                    15 /* ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS */ -> "Cannot monitor speech language download."
                    else -> "Voice recognition notice (code $error). Please try again."
                }

                Log.w(TAG, "ℹ️ [Voice Notice] $humanExplanation (error code $error)")
                listener?.onError(humanExplanation)
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                hasAttemptedFallback = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = matches?.firstOrNull()?.trim()
                if (!transcript.isNullOrBlank()) {
                    Log.i(TAG, "📝 [Voice Transcribed] User said: \"$transcript\"")
                    listener?.onResult(transcript)
                } else {
                    Log.d(TAG, "❓ [Empty Result] Recognition completed with no audible words detected.")
                    listener?.onError("No words detected. Please try again.")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()?.trim()
                if (!partial.isNullOrBlank()) {
                    Log.d(TAG, "💬 [Partial Speech] \"$partial\"")
                    listener?.onPartialResult(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }
}
