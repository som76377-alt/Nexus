package com.nexus.music.ui

import android.util.Log
import android.view.Window
import com.nexus.music.BuildConfig
import androidx.metrics.performance.JankStats

/** Debug-only frame telemetry. It logs only janky frames and stores no user/media data. */
class DebugJankMonitor(window: Window) {
    private val stats: JankStats? = if (BuildConfig.DEBUG) {
        runCatching {
            JankStats.createAndTrack(window) { frame ->
                if (frame.isJank) {
                    Log.d(TAG, "Janky frame: ${frame.frameDurationUiNanos} ns")
                }
            }
        }.getOrNull()
    } else {
        null
    }

    fun setEnabled(enabled: Boolean) {
        stats?.isTrackingEnabled = enabled
    }

    private companion object {
        const val TAG = "NexusJank"
    }
}
