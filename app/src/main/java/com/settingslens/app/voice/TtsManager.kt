package com.settingslens.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * Text-to-speech manager for conversational questions, confirmations, and guidance.
 *
 * Resilience & Diagnostics:
 * - Attempts Malayalam (`ml-IN`) voice synthesis first.
 * - If Malayalam speech data is absent on device, automatically and cleanly falls back
 *   to Indian English (`en-IN`) or default English (`en-US`).
 * - Utterance progress and synthesis issues translated to clear human diagnostics.
 */
class TtsManager(context: Context) {

    companion object {
        private const val TAG = "SettingsLens:TTS"
    }

    interface TtsListener {
        fun onReady()
        fun onSpeakingStarted(utteranceId: String)
        fun onSpeakingDone(utteranceId: String)
        fun onError(utteranceId: String, errorMessage: String)
    }

    private var tts: TextToSpeech? = null
    private var listener: TtsListener? = null
    var isInitialized: Boolean = false
        private set

    init {
        try {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isInitialized = true
                    setupLanguage()
                    listener?.onReady()
                    Log.i(TAG, "🔊 [TTS Ready] Text-to-speech engine ready for voice feedback.")
                } else {
                    Log.w(TAG, "⚠️ [TTS Init Notice] Speech engine initialization reported status code: $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 [TTS Init Error] Could not initialize TextToSpeech: ${e.localizedMessage}", e)
        }
    }

    fun setListener(listener: TtsListener) {
        this.listener = listener
    }

    private fun setupLanguage() {
        try {
            // Attempt Malayalam language pack first
            val malayalamResult = tts?.setLanguage(Locale("ml", "IN"))
            if (malayalamResult == TextToSpeech.LANG_MISSING_DATA ||
                malayalamResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Log.i(TAG, "🌐 [TTS Language Fallback] Malayalam voice data not installed; switching to English (India).")
                val englishResult = tts?.setLanguage(Locale("en", "IN"))
                if (englishResult == TextToSpeech.LANG_MISSING_DATA ||
                    englishResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.i(TAG, "🌐 [TTS Language Fallback] Using device default English voice.")
                    tts?.setLanguage(Locale.US)
                }
            } else {
                Log.i(TAG, "🌐 [TTS Language Active] Malayalam voice data detected and enabled.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ [TTS Language Warning] Language selection notice: ${e.localizedMessage}")
        }

        // Set up progress tracking
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                utteranceId?.let {
                    Log.d(TAG, "🗣️ [Speaking Started] Utterance: $it")
                    listener?.onSpeakingStarted(it)
                }
            }

            override fun onDone(utteranceId: String?) {
                utteranceId?.let {
                    Log.d(TAG, "✅ [Speaking Finished] Utterance: $it")
                    listener?.onSpeakingDone(it)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let {
                    Log.w(TAG, "⚠️ [Speaking Glitch] Utterance playback encountered a minor glitch: $it")
                    listener?.onError(it, "Speech synthesis interrupted.")
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                val reason = when (errorCode) {
                    TextToSpeech.ERROR_SYNTHESIS -> "Audio synthesis failed."
                    TextToSpeech.ERROR_SERVICE -> "TTS service connection issue."
                    TextToSpeech.ERROR_OUTPUT -> "Audio output device issue."
                    TextToSpeech.ERROR_NETWORK -> "Network issue fetching voice data."
                    TextToSpeech.ERROR_NETWORK_TIMEOUT -> "Network timed out fetching voice."
                    TextToSpeech.ERROR_INVALID_REQUEST -> "Invalid speech request."
                    TextToSpeech.ERROR_NOT_INSTALLED_YET -> "Voice data is still downloading."
                    else -> "Speech output notice (code $errorCode)."
                }
                Log.w(TAG, "⚠️ [Speaking Warning] $reason (Utterance: $utteranceId)")
                utteranceId?.let { listener?.onError(it, reason) }
            }
        })
    }

    /**
     * Speak text immediately, interrupting any previous utterance.
     */
    fun speak(text: String): String {
        val utteranceId = UUID.randomUUID().toString()
        if (!isInitialized) {
            Log.w(TAG, "⚠️ [TTS Not Ready] Speech engine not yet initialized. Skipping speech: \"$text\"")
            return utteranceId
        }

        try {
            Log.i(TAG, "📢 [Assistant Speaking] \"$text\"")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } catch (e: Exception) {
            Log.e(TAG, "💥 [TTS Speak Exception] Error speaking text: ${e.localizedMessage}", e)
        }
        return utteranceId
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ [TTS Stop Notice] ${e.localizedMessage}")
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            Log.i(TAG, "🛑 [TTS Engine Shutdown] TextToSpeech resources released.")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ [TTS Shutdown Notice] ${e.localizedMessage}")
        } finally {
            tts = null
            isInitialized = false
        }
    }
}
