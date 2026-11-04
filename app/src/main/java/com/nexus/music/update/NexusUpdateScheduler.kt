package com.nexus.music.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Schedules an immediate check and a battery-aware periodic fallback. */
object NexusUpdateScheduler {
    private const val PERIODIC_WORK = "nexus_periodic_update_check"
    private const val PUSH_WORK = "nexus_push_update_check"

    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<NexusUpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        manager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, periodic)

        val initial = OneTimeWorkRequestBuilder<NexusUpdateWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniqueWork("nexus_initial_update_check", ExistingWorkPolicy.KEEP, initial)

        // Push is activated automatically when google-services.json is supplied.
        runCatching {
            if (com.google.firebase.FirebaseApp.getApps(context).isNotEmpty()) {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().subscribeToTopic("nexus_updates")
            }
        }
    }

    fun requestFromPush(context: Context) {
        val request = OneTimeWorkRequestBuilder<NexusUpdateWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(PUSH_WORK, ExistingWorkPolicy.REPLACE, request)
    }
}
