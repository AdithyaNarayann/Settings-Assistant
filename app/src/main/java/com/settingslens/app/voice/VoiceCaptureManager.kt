package com.settingslens.app.voice

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

/**
 * Voice capture manager wrapping Android's SpeechRecognizer.
 *
 * Resilience & Looper Design:
 * - SpeechRecognizer requires execution strictly on the primary Looper thread.
 * - All actions (initialize, startListening, stopListening, cancel, destroy) are dispatched
 *   safely via a main-looper Handler to protect against thread-affinity crashes.
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
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setListener(listener: VoiceCaptureListener) {
        this.listener = listener
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

                speechRecognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                ) {
                    Log.i(TAG, "🎙️ [Speech Recognizer] Initialized on-device speech recognizer (offline-capable).")
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    Log.i(TAG, "🎙️ [Speech Recognizer] Initialized standard speech recognizer.")
                    SpeechRecognizer.createSpeechRecognizer(context)
                }

                speechRecognizer?.setRecognitionListener(createRecognitionListener())
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
     */
    fun startListening(languageCode: String = "en-IN") {
        mainHandler.post {
            if (isListening) {
                Log.d(TAG, "🎙️ [Already Listening] Active voice listening session already in progress.")
                return@post
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageCode)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            try {
                speechRecognizer?.startListening(intent)
                isListening = true
                Log.i(TAG, "👂 [Listening Started] Awaiting speech in language: $languageCode...")
            } catch (e: Exception) {
                isListening = false
                val errorMsg = "Could not activate microphone for listening: ${e.localizedMessage}"
                Log.e(TAG, "💥 [Start Listening Error] $errorMsg", e)
                listener?.onError(errorMsg)
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
                val humanExplanation = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Microphone audio recording error. Please check if another app is using the mic."
                    SpeechRecognizer.ERROR_CLIENT -> "Device speech client encountered a temporary glitch. Please try again."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for voice commands."
                    SpeechRecognizer.ERROR_NETWORK -> "Network issue encountered during speech recognition. Checking connection..."
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition network request timed out. Please try again."
                    SpeechRecognizer.ERROR_NO_MATCH -> "Couldn't quite catch what you said. Please speak again clearly."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice recognizer is still busy. Please wait a moment."
                    SpeechRecognizer.ERROR_SERVER -> "Speech server temporarily unavailable. Switching to on-device recognition..."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was detected. Tap the bubble to speak when ready."
                    else -> "Voice recognition notice (code $error). Please try again."
                }

                Log.w(TAG, "ℹ️ [Voice Notice] $humanExplanation (error code $error)")
                listener?.onError(humanExplanation)
            }

            override fun onResults(results: Bundle?) {
                isListening = false
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
