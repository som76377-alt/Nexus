package com.nexus.music.performance

import android.os.SystemClock
import android.util.Log
import com.nexus.music.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Lightweight debug-only monotonic tracing. It records timings only; it never changes app work. */
object NexusPerformance {
    private const val TAG = "NexusPerformance"
    private val enabled = BuildConfig.DEBUG
    private val sequence = AtomicLong()
    private val playbackTraces = ConcurrentHashMap<String, PlaybackTrace>()
    @Volatile private var appForeground = false

    data class Trace internal constructor(
        val id: String,
        val operation: String,
        internal val startedAtElapsedMs: Long,
        internal val foreground: Boolean
    )

    private data class PlaybackTrace(
        val trace: Trace,
        val recordedEvents: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
    )

    fun setForeground(foreground: Boolean) { appForeground = foreground }
    fun isForeground(): Boolean = appForeground

    /** Android elapsed-realtime clock, including time spent asleep. */
    fun nowMs(): Long = SystemClock.elapsedRealtime()

    fun begin(operation: String, foreground: Boolean, detail: String = ""): Trace {
        val trace = Trace("np-" + sequence.incrementAndGet(), operation, nowMs(), foreground)
        log(trace, "begin", detail)
        return trace
    }

    fun event(trace: Trace, event: String, detail: String = "") = log(trace, event, detail)

    fun finish(trace: Trace, result: String = "ok") = log(trace, "finish", "result=" + result)

    fun logEvent(event: String, detail: String = "") {
        if (!enabled) return
        runCatching {
            Log.i(TAG, ("event=" + event + " foreground=" + appForeground + " " + detail).trim())
        }
    }

    fun registerPlayback(sourceId: String, trace: Trace) {
        if (!enabled) return
        playbackTraces.clear()
        playbackTraces[sourceId] = PlaybackTrace(trace)
    }

    fun forgetPlayback(sourceId: String) {
        if (!enabled) return
        playbackTraces.remove(sourceId)
    }

    fun playbackEvent(sourceId: String, event: String, detail: String = ""): Boolean {
        if (!enabled) return false
        val record = playbackTraces[sourceId] ?: return false
        if (!record.recordedEvents.add(event)) return false
        log(record.trace, event, detail)
        if (record.recordedEvents.contains("player_ready") &&
            record.recordedEvents.contains("first_position_advance")
        ) {
            finish(record.trace, "playback_started")
            playbackTraces.remove(sourceId, record)
        }
        return true
    }

    private fun log(trace: Trace, event: String, detail: String) {
        if (!enabled) return
        val elapsedMs = nowMs() - trace.startedAtElapsedMs
        runCatching {
            Log.i(TAG, "id=" + trace.id + " op=" + trace.operation + " event=" + event +
                " elapsed_ms=" + elapsedMs + " foreground=" + trace.foreground + " " + detail.trim())
        }
    }
}
