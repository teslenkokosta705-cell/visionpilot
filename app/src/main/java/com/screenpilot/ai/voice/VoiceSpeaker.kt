package com.screenpilot.ai.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.screenpilot.ai.config.SecurePrefs
import java.util.Locale

data class VoiceOption(
    val name: String,
    val label: String
)

class VoiceSpeaker(
    context: Context,
    private val prefs: SecurePrefs,
    private val onReady: (() -> Unit)? = null
) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var engineLabel = "Системный TTS"

    init {
        initTts()
    }

    private fun initTts() {
        val probe = TextToSpeech(appContext, null)
        val hasGoogle = probe.engines.any { it.name == GOOGLE_TTS_ENGINE }
        probe.shutdown()

        if (hasGoogle) {
            engineLabel = "Google Speech Services"
            tts = TextToSpeech(appContext, this, GOOGLE_TTS_ENGINE)
        } else {
            engineLabel = "Системный TTS по умолчанию"
            tts = TextToSpeech(appContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            val ru = Locale("ru", "RU")
            tts?.language = ru
            tts?.setSpeechRate(1.06f)
            tts?.setPitch(1.0f)

            val savedVoice = prefs.voiceName
            if (savedVoice.isNotBlank()) {
                applyVoiceByName(savedVoice)
            } else {
                selectBestDefaultVoice()
            }
            onReady?.invoke()
        } else {
            ready = false
        }
    }

    private fun selectBestDefaultVoice() {
        val voices = tts?.voices ?: return
        val best = voices
            .filter { it.locale.language == "ru" }
            .sortedWith(
                compareByDescending<Voice> { !it.isNetworkConnectionRequired }
                    .thenByDescending { it.quality }
            )
            .firstOrNull()
        if (best != null) {
            tts?.voice = best
        }
    }

    fun applyVoiceByName(voiceName: String) {
        val match = tts?.voices?.firstOrNull { it.name == voiceName }
        if (match != null) {
            tts?.voice = match
        }
    }

    fun getAvailableVoices(): List<VoiceOption> {
        if (!ready) return emptyList()
        val voices = tts?.voices ?: return emptyList()
        return voices
            .filter { it.locale.language == "ru" || it.locale.language == "en" }
            .sortedWith(
                compareBy<Voice> { if (it.locale.language == "ru") 0 else 1 }
                    .thenByDescending { it.quality }
                    .thenBy { it.name }
            )
            .map { v ->
                val netTag = if (v.isNetworkConnectionRequired) "сеть" else "офлайн"
                VoiceOption(
                    name = v.name,
                    label = "${v.locale.displayLanguage} · ${v.name.take(24)} ($netTag)"
                )
            }
    }

    fun getEngineName(): String = engineLabel

    fun speak(text: String, interrupt: Boolean = true) {
        if (!prefs.voiceEnabled || !ready || text.isBlank()) return
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text.trim(), mode, null, "sp_${System.currentTimeMillis()}")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    companion object {
        private const val GOOGLE_TTS_ENGINE = "com.google.android.tts"
    }
}
