package com.telematicslab.demo.sdk.motion

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Optional start/stop-only audio feedback using Android TextToSpeech.
 * Intentionally initialized lazily only when the user opts into voice cues,
 * and never speaks mid-drive to avoid driver distraction.
 */
class VoiceCueSpeaker(private val appContext: Context) {

    @Volatile
    private var tts: TextToSpeech? = null
    @Volatile
    private var ready: Boolean = false

    fun speak(message: String, enabled: Boolean) {
        if (!enabled) return
        val engine = tts
        if (engine == null) {
            try {
                tts = TextToSpeech(appContext) { status ->
                    ready = status == TextToSpeech.SUCCESS
                    if (ready) {
                        tts?.language = Locale.US
                        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "telematics_cue")
                    }
                }
            } catch (_: Exception) {
            }
            return
        }
        if (ready) {
            runCatching {
                engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, "telematics_cue")
            }
        }
    }

    fun shutdown() {
        ready = false
        runCatching { tts?.shutdown() }
        tts = null
    }
}
