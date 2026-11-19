package com.hai.vocabhandbook.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Đọc từ tiếng Anh bằng bộ đọc văn bản có sẵn của máy (chạy offline nếu đã tải giọng) */
class SpeechManager(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(appContext, this)
    private var ready = false

    var isSpeakingAll by mutableStateOf(false)
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val r = tts.setLanguage(Locale.US)
        ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == LAST_ID) main.post { isSpeakingAll = false }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == LAST_ID) main.post { isSpeakingAll = false }
            }
        })
    }

    private fun checkReady(): Boolean {
        if (!ready) {
            Toast.makeText(
                appContext,
                "Máy chưa có giọng đọc tiếng Anh. Vào Cài đặt > Chuyển văn bản thành giọng nói để tải giọng English (US).",
                Toast.LENGTH_LONG
            ).show()
        }
        return ready
    }

    /** rate: 1.0 là tốc độ thường, 0.5 là chậm */
    fun speak(text: String, rate: Float = 0.9f) {
        stopAll()
        if (!checkReady()) return
        tts.setSpeechRate(rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "one")
    }

    fun speakAll(words: List<String>) {
        stopAll()
        if (words.isEmpty() || !checkReady()) return
        tts.setSpeechRate(0.9f)
        isSpeakingAll = true
        words.forEachIndexed { i, w ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val id = if (i == words.lastIndex) LAST_ID else "w$i"
            tts.speak(w, mode, null, id)
            if (i != words.lastIndex) tts.playSilentUtterance(400, TextToSpeech.QUEUE_ADD, "s$i")
        }
    }

    fun stopAll() {
        isSpeakingAll = false
        tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private companion object {
        const val LAST_ID = "last"
    }
}
