package dev.gorny.buymyway.data.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresPermission
import dev.gorny.buymyway.core.voice.VoiceError
import dev.gorny.buymyway.core.voice.VoiceEvent
import dev.gorny.buymyway.core.voice.VoiceSource

/**
 * Android's `SpeechRecognizer` for one language (PLAN.md *Voice input*), wrapped so the screen
 * sees only [VoiceEvent]. Nothing here keeps audio: `onBufferReceived` is ignored, no recorder
 * is opened, and the only thing that ever leaves the app is the recognizer's own text result
 * (Phase 7 acceptance).
 *
 * Offline is preferred, so a phone with the Polish pack installed never sends the speech
 * anywhere. A recognizer that answers "I do not have that language" is asked again without the
 * preference, once, and this instance stops preferring offline afterwards (STATE.md decision 74).
 *
 * Must be used from the main thread, as `SpeechRecognizer` requires.
 */
class VoiceRecognizer(private val context: Context) : VoiceSource {

    private var recognizer: SpeechRecognizer? = null
    private var listener: ((VoiceEvent) -> Unit)? = null
    private var preferOffline = true
    private var retried = false

    /** Whether this phone has a recognizer at all; the mic button hides when it has not. */
    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Starts one utterance. [onEvent] hears everything until [VoiceEvent.Heard] or [VoiceEvent.Failed]. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start(onEvent: (VoiceEvent) -> Unit) {
        listener = onEvent
        retried = false
        if (!available) {
            finish(VoiceError.OTHER)
            return
        }
        listen()
    }

    /** Ends the utterance and takes what was said so far: the second tap on the mic. */
    override fun stop() {
        recognizer?.stopListening()
    }

    /** Drops the utterance. */
    fun cancel() {
        listener = null
        recognizer?.cancel()
    }

    /** Gives the microphone back. The screen calls this when it goes away. */
    override fun release() {
        listener = null
        recognizer?.destroy()
        recognizer = null
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun listen() {
        val speech = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(callbacks)
            recognizer = it
        }
        speech.startListening(intent(context.packageName, preferOffline))
    }

    private fun emit(event: VoiceEvent) {
        listener?.invoke(event)
    }

    private fun finish(error: VoiceError) {
        val heard = listener
        listener = null
        heard?.invoke(VoiceEvent.Failed(error))
    }

    private fun finish(text: String) {
        val heard = listener
        listener = null
        heard?.invoke(VoiceEvent.Heard(text))
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = emit(VoiceEvent.Listening)

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        /** Raw audio from the recognizer. It is not read, not kept and not written anywhere. */
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = emit(VoiceEvent.Thinking)

        override fun onPartialResults(partialResults: Bundle?) {
            val text = best(partialResults)
            if (text.isNotBlank()) emit(VoiceEvent.Partial(text))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onResults(results: Bundle?) = finish(best(results))

        override fun onError(error: Int) {
            val mapped = errorOf(error)
            // A recognizer without the Polish pack is asked once more over the network.
            if (mapped == VoiceError.LANGUAGE && preferOffline && !retried) {
                preferOffline = false
                retried = true
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    listen()
                    return
                }
            }
            finish(mapped)
        }

        private fun best(results: Bundle?): String =
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
    }

    companion object {
        /** The one language the app speaks (CLAUDE.md: the UI is Polish). */
        const val LANGUAGE = "pl-PL"

        // Named here rather than taken from SpeechRecognizer: both arrived in API 31, and the
        // app runs from API 26, where reading the constants would be a lint error (InlinedApi).
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13

        /**
         * What is asked of the recognizer. Free-form Polish, partial results while speaking,
         * one alternative, and nothing that would store or return audio.
         */
        fun intent(packageName: String, preferOffline: Boolean): Intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }

        /** The recognizer's error codes as the screen understands them. */
        fun errorOf(error: Int): VoiceError = when (error) {
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceError.NO_SPEECH
            SpeechRecognizer.ERROR_NO_MATCH -> VoiceError.NO_MATCH
            SpeechRecognizer.ERROR_AUDIO -> VoiceError.AUDIO
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceError.NETWORK
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceError.PERMISSION
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceError.BUSY
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> VoiceError.LANGUAGE
            else -> VoiceError.OTHER
        }
    }
}
