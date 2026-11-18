package com.nexus.music.catalog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PriorityExecutionGateTest {
    @Test
    fun foregroundWaiterRunsBeforeQueuedBackgroundWaiter() = runBlocking {
        val gate = PriorityExecutionGate()
        val entered = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(ExtractorPriority.FOREGROUND) {
                entered.complete(Unit)
                releaseHolder.await()
            }
        }
        entered.await()

        val order = mutableListOf<String>()
        val background = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(ExtractorPriority.BACKGROUND) { order += "background" }
        }
        val foreground = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(ExtractorPriority.FOREGROUND) { order += "foreground" }
        }

        releaseHolder.complete(Unit)
        holder.join()
        foreground.join()
        background.join()

        assertEquals(listOf("foreground", "background"), order)
    }

    @Test
    fun cancellingQueuedWaiterDoesNotStrandTheGate() = runBlocking {
        val gate = PriorityExecutionGate()
        val entered = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(ExtractorPriority.FOREGROUND) {
                entered.complete(Unit)
                releaseHolder.await()
            }
        }
        entered.await()

        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(ExtractorPriority.BACKGROUND) { error("Cancelled waiter must not run") }
        }
        cancelled.cancelAndJoin()

        val completed = CompletableDeferred<Unit>()
        val foreground = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(ExtractorPriority.FOREGROUND) { completed.complete(Unit) }
        }
        releaseHolder.complete(Unit)
        holder.join()
        foreground.join()

        completed.await()
    }
}
