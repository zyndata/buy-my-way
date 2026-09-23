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
