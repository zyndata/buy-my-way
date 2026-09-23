package dev.gorny.buymyway.data.voice

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gorny.buymyway.core.voice.VoiceError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What is asked of the system recognizer (PLAN.md Phase 7, task 1), and what the app declares
 * for it. The recognizer itself is not driven here: a microphone and a spoken sentence are the
 * owner's by-hand check.
 */
@RunWith(AndroidJUnit4::class)
class VoiceRecognizerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theRecognizerIsAskedForPolishWithPartialResults() {
        val intent = VoiceRecognizer.intent("dev.gorny.buymyway", preferOffline = true)

        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals("pl-PL", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals("pl-PL", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE))
        assertEquals(
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL),
        )
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
    }

    /** Offline is preferred; the retry after „I do not have that language" is the exception. */
    @Test
    fun theRetryDropsOnlyTheOfflinePreference() {
        val online = VoiceRecognizer.intent("dev.gorny.buymyway", preferOffline = false)

        assertFalse(online.hasExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE))
        assertEquals("pl-PL", online.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
    }

    /**
     * Nothing asks the recognizer for audio back, so no recording can reach the app: the
     * extras that would do it are absent (Phase 7 acceptance, „no audio is stored").
     */
    @Test
    fun nothingAsksForTheAudioItself() {
        val intent = VoiceRecognizer.intent("dev.gorny.buymyway", preferOffline = true)
        val audioExtras = listOf(
            "android.speech.extra.GET_AUDIO",
            "android.speech.extra.GET_AUDIO_FORMAT",
            "android.speech.extra.AUDIO_SOURCE",
            "android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT",
        )

        assertEquals(emptyList<String>(), audioExtras.filter { intent.hasExtra(it) })
    }

    @Test
    fun everyErrorCodeBecomesOneOfOurs() {
        assertEquals(VoiceError.NO_MATCH, VoiceRecognizer.errorOf(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(VoiceError.NO_SPEECH, VoiceRecognizer.errorOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(VoiceError.AUDIO, VoiceRecognizer.errorOf(SpeechRecognizer.ERROR_AUDIO))
        assertEquals(VoiceError.NETWORK, VoiceRecognizer.errorOf(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(VoiceError.NETWORK, VoiceRecognizer.errorOf(SpeechRecognizer.ERROR_NETWORK_TIMEOUT))
        assertEquals(VoiceError.PERMISSION, VoiceRecognizer.errorOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(VoiceError.BUSY, VoiceRecognizer.errorOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        // 12 and 13 are ERROR_LANGUAGE_NOT_SUPPORTED and ERROR_LANGUAGE_UNAVAILABLE (API 31).
        assertEquals(VoiceError.LANGUAGE, VoiceRecognizer.errorOf(12))
        assertEquals(VoiceError.LANGUAGE, VoiceRecognizer.errorOf(13))
        assertEquals(VoiceError.OTHER, VoiceRecognizer.errorOf(SpeechRecognizer.ERROR_SERVER))
        assertEquals(VoiceError.OTHER, VoiceRecognizer.errorOf(SpeechRecognizer.ERROR_CLIENT))
        assertEquals(VoiceError.OTHER, VoiceRecognizer.errorOf(99))
    }

    /** The microphone is asked for, and nothing else was added with it. */
    @Test
    fun theOnlyNewPermissionIsTheMicrophone() {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
        val declared = info.requestedPermissions.orEmpty().toSet()

        assertTrue(declared.contains("android.permission.RECORD_AUDIO"))
        // What a voice feature could have brought and did not.
        assertFalse(declared.contains("android.permission.CAPTURE_AUDIO_OUTPUT"))
        assertFalse(declared.contains("android.permission.MODIFY_AUDIO_SETTINGS"))
        assertFalse(declared.contains("android.permission.BLUETOOTH_CONNECT"))
    }
}
