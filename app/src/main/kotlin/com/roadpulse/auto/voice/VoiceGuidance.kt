package com.roadpulse.auto.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import com.roadpulse.auto.engine.GuidanceState
import java.util.Locale

/**
 * Spoken turn-by-turn prompts driven by [GuidanceState] transitions, using Android's built-in
 * `TextToSpeech`. Replaces Google Navigation SDK's
 * `Navigator.setAudioGuidance(VOICE_ALERTS_AND_GUIDANCE)` - a complete black box with no
 * app-owned code behind it (confirmed via grep: zero `android.speech.tts` usage anywhere in the
 * codebase before this class), so this is a from-scratch build, not a port.
 *
 * Phrase selection and per-maneuver announcement thresholds live in [VoiceGuidancePlanner] (pure,
 * unit-tested); this class only owns the `TextToSpeech` engine and its lifecycle, degrading
 * silently to a no-op if no TTS engine is installed or initialization fails - the app's on-screen
 * guidance text remains fully functional regardless, per the requirement that voice guidance must
 * never be a hard dependency.
 */
class VoiceGuidance(
    context: Context,
) {
    private val planner = VoiceGuidancePlanner()
    private var engine: TextToSpeech? = null
    private var isReady = false
    private var isMuted = false

    init {
        engine =
            TextToSpeech(context.applicationContext) { status ->
                isReady = status == TextToSpeech.SUCCESS
                if (isReady) {
                    // App UI text is English-only (see values/strings.xml, no values-de/) -
                    // matching Locale.US already used for formatting elsewhere in the codebase
                    // (e.g. MainActivity/SettingsActivity distance/size formatting).
                    val result = engine?.setLanguage(Locale.US)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        isReady = false
                    }
                }
            }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        if (muted) engine?.stop()
    }

    fun onGuidanceState(state: GuidanceState) {
        val phrase = planner.plan(state) ?: return
        if (!isReady || isMuted) return
        engine?.speak(phrase, TextToSpeech.QUEUE_ADD, null, phrase.hashCode().toString())
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        isReady = false
    }
}
