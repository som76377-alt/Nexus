package com.nexus.music.ui

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.view.HapticFeedbackConstants
import android.view.View

/** User-facing effects preferences. No audio focus or sound playback is used. */
class NexusExperienceSettings(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("nexus_experience", Context.MODE_PRIVATE)

    var reduceEffects: Boolean
        get() = preferences.getBoolean(KEY_REDUCE_EFFECTS, false)
        set(value) { preferences.edit().putBoolean(KEY_REDUCE_EFFECTS, value).apply() }

    var hapticsEnabled: Boolean
        get() = preferences.getBoolean(KEY_HAPTICS, true)
        set(value) { preferences.edit().putBoolean(KEY_HAPTICS, value).apply() }

    fun effectsShouldBeReduced(): Boolean {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val power = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return reduceEffects || manager?.isLowRamDevice == true || power?.isPowerSaveMode == true
    }

    companion object {
        private const val KEY_REDUCE_EFFECTS = "reduce_effects_v1"
        private const val KEY_HAPTICS = "haptics_enabled_v1"
    }
}

interface UiSoundPolicy {
    fun play(action: Action, musicIsPlaying: Boolean)
    enum class Action { PLAY, PAUSE, SEEK, SAVE }
}

/** Intentionally silent. UI sounds are not part of the first redesign release. */
object SilentUiSoundPolicy : UiSoundPolicy {
    override fun play(action: UiSoundPolicy.Action, musicIsPlaying: Boolean) = Unit
}

object NexusHaptics {
    fun confirm(view: View, settings: NexusExperienceSettings) {
        if (settings.hapticsEnabled) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    fun seekStep(view: View, settings: NexusExperienceSettings) {
        if (settings.hapticsEnabled) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}
