package app.inkwell.ui.screens.novel

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Thin wrapper over TextToSpeech: speaks one paragraph at a time and reports when it finished. */
class NovelTts(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var gen = 0
    private var pending: (() -> Unit)? = null
    private var onDone: (() -> Unit)? = null

    var ready by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set
    /** False when the device has no TTS engine installed (speech would silently never start). */
    val hasEngine: Boolean get() = runCatching { tts?.engines?.isNotEmpty() == true }.getOrDefault(false)

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { finished(id) }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) { finished(id) }
                    })
                    pending?.invoke(); pending = null
                } else failed = true
            }
        }
    }

    private fun finished(id: String?) {
        main.post { if (id == "n$gen") onDone?.invoke() }
    }

    /** Speak [text]; [done] fires on the main thread when it finishes naturally (not when interrupted). */
    fun speak(text: String, rate: Float, pitch: Float, done: () -> Unit) {
        val go = {
            val t = tts
            if (t != null) {
                gen++
                onDone = done
                t.setSpeechRate(rate); t.setPitch(pitch)
                val max = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500)
                val chunks = chunk(text, max)
                val id = "n$gen"
                chunks.forEachIndexed { i, c ->
                    // only the last chunk carries the id we listen for
                    val uid = if (i == chunks.lastIndex) id else "$id-$i"
                    t.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), uid)
                }
            }
        }
        if (ready) go() else pending = go
    }

    fun stop() { gen++; onDone = null; pending = null; tts?.stop() }

    fun release() {
        stop()
        tts?.shutdown(); tts = null
    }

    private fun chunk(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > max) {
            var cut = rest.lastIndexOf(". ", max)
            if (cut < max / 2) cut = rest.lastIndexOf(' ', max)
            if (cut <= 0) cut = max
            out += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) out += rest.trim()
        return out
    }
}
