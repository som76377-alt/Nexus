package com.nexus.music.update

import android.content.Context
import androidx.work.WorkManager

/**
 * Retires the old background updater. Update checks and APK downloads now happen
 * in MainActivity so the user can see the result and the download progress.
 *
 * Keep the legacy unique-work names so installations upgrading from older builds
 * can cancel their already-persisted WorkManager jobs.
 */
object NexusUpdateScheduler {
    private const val PERIODIC_WORK = "nexus_periodic_update_check"
    private const val INITIAL_WORK = "nexus_initial_update_check"
    private const val PUSH_WORK = "nexus_push_update_check"

    fun disableBackgroundUpdates(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(PERIODIC_WORK)
        manager.cancelUniqueWork(INITIAL_WORK)
        manager.cancelUniqueWork(PUSH_WORK)
    }
}
