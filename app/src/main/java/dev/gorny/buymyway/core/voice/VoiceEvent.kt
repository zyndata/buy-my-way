package dev.gorny.buymyway.core.voice

/** Why dictation stopped; the screen turns each into one Polish sentence. */
enum class VoiceError {
    /** Nothing was said. */
    NO_SPEECH,

    /** Something was said and not understood. */
    NO_MATCH,

    /** The microphone could not be read. */
    AUDIO,

    /** The recognizer needed the network and did not have it. */
    NETWORK,

    /** The app may not record. */
    PERMISSION,

    /** Another app is using the recognizer. */
    BUSY,

    /** This phone's recognizer does not speak Polish. */
    LANGUAGE,

    /** Anything else, including a recognizer that is not there at all. */
    OTHER,
}

/**
 * What the review sheet listens with. The app's own is `data/voice/VoiceRecognizer`, wrapping
 * Android's `SpeechRecognizer`; a test hands the sheet one that says what a phone would have
 * heard, so the screens are tested without a microphone.
 */
interface VoiceSource {
    /** Starts one utterance. [onEvent] hears everything until [VoiceEvent.Heard] or [VoiceEvent.Failed]. */
    fun start(onEvent: (VoiceEvent) -> Unit)

    /** Ends the utterance and takes what was said so far. */
    fun stop()

    /** Gives the microphone back. */
    fun release()
}

/** What the recognizer tells the screen while an utterance is being said. */
sealed interface VoiceEvent {
    /** The microphone is on. */
    data object Listening : VoiceEvent

    /** Words heard so far, shown while speaking; they can still change. */
    data class Partial(val text: String) : VoiceEvent

    /** Speech ended and the recognizer is still working on it. */
    data object Thinking : VoiceEvent

    /** The finished utterance. The session is over. */
    data class Heard(val text: String) : VoiceEvent

    /** The session is over without a result. */
    data class Failed(val error: VoiceError) : VoiceEvent
}
