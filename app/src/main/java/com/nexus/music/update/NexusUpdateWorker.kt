package com.nexus.music.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Compatibility stub for WorkManager jobs created by older Nexus versions.
 * It intentionally does no network work: updates are now checked and downloaded
 * in the foreground with visible progress from MainActivity.
 */
class NexusUpdateWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = Result.success()
}
