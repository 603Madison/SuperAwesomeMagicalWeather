package app.northvane.mini.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Reads text aloud with the phone's own text-to-speech engine (no network).
 * The engine starts in the background; anything asked for before it's ready
 * is spoken once it is.
 */
class Speaker(context: Context) {
    /** Null while starting; false when the phone has no usable voice. */
    var available by mutableStateOf<Boolean?>(null)
        private set
    var speaking by mutableStateOf(false)
        private set
    private var pending: String? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        val ok = status == TextToSpeech.SUCCESS && engineLanguageOk()
        available = ok
        if (ok) pending?.let { speak(it) }
        pending = null
    }

    /** The phone's language if the engine has it, otherwise US English. */
    private fun engineLanguageOk(): Boolean {
        fun set(locale: Locale) = runCatching { tts.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE
        return set(Locale.getDefault()) || set(Locale.US)
    }

    /** Speaks [text], or stops if it's already speaking. */
    fun toggle(text: String) {
        if (speaking || tts.isSpeaking) {
            tts.stop()
            speaking = false
            return
        }
        speak(text)
    }

    private fun speak(text: String) {
        when (available) {
            null -> pending = text
            true -> {
                speaking = true
                tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { speaking = false }
                    @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { speaking = false }
                })
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "briefing")
            }
            false -> Unit
        }
    }

    fun shutdown() {
        runCatching { tts.stop(); tts.shutdown() }
    }
}
