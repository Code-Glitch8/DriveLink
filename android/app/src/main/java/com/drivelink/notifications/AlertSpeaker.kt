package com.drivelink.notifications

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Voice alerts with de-duplication and priority, so a busy road does not become a stream of speech. */
class AlertSpeaker(context: Context) {
    @Volatile private var ready = false
    var enabled = true
    private val lastSpoken = HashMap<String, Long>()
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ready = true
            }
        }
    }

    /** priority 3 = emergency/high risk (interrupts), lower values queue. */
    @Synchronized
    fun speak(key: String, text: String, priority: Int, cooldownMs: Long = 15_000) {
        if (!enabled || !ready) return
        val now = System.currentTimeMillis()
        if (now - (lastSpoken[key] ?: 0L) < cooldownMs) return
        lastSpoken[key] = now
        tts?.speak(text, if (priority >= 3) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, key)
    }

    fun shutdown() { tts?.stop(); tts?.shutdown(); tts = null }
}
