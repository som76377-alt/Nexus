package com.nexus.music.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nexus.music.MainActivity
import java.io.IOException

class NexusUpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        when (val result = GitHubReleaseUpdater().checkForUpdate(applicationContext)) {
            is UpdateCheckResult.UpToDate -> {
                val preferences = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                preferences.getString(KEY_APK_PATH, null)?.let { java.io.File(it).delete() }
                preferences.edit()
                    .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
                    .remove(KEY_VERSION)
                    .remove(KEY_VERSION_CODE)
                    .remove(KEY_TAG)
                    .remove(KEY_NOTES)
                    .remove(KEY_URL)
                    .remove(KEY_SHA256)
                    .remove(KEY_APK_PATH)
                    .apply()
                Result.success()
            }
            is UpdateCheckResult.Available -> {
                val update = result.update
                val apk = GitHubReleaseUpdater().downloadAndVerify(applicationContext, update)
                applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
                    .putString(KEY_VERSION, update.versionName)
                    .putInt(KEY_VERSION_CODE, update.versionCode)
                    .putString(KEY_TAG, update.tagName)
                    .putString(KEY_NOTES, update.releaseNotes)
                    .putString(KEY_URL, update.apkUrl)
                    .putString(KEY_SHA256, update.sha256)
                    .putString(KEY_APK_PATH, apk.absolutePath).apply()
                notifyReady(update)
                Result.success()
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: IOException) {
        if (runAttemptCount < 5) Result.retry() else Result.failure()
    } catch (e: Exception) {
        Result.failure()
    }

    private fun notifyReady(update: NexusUpdate) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Nexus updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_UPDATE_READY, true)
        }
        val pending = PendingIntent.getActivity(applicationContext, update.versionCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Nexus ${update.versionName} is ready")
            .setContentText("Downloaded and verified. Tap to review installation.")
            .setContentIntent(pending).setAutoCancel(true).build()
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(applicationContext).notify(4102, notification)
        }
    }

    companion object {
        const val PREFS = "nexus_update_check"
        const val KEY_LAST_SUCCESS = "last_background_success_ms"
        const val KEY_VERSION = "background_version"
        const val KEY_VERSION_CODE = "background_version_code"
        const val KEY_TAG = "background_tag"
        const val KEY_NOTES = "background_notes"
        const val KEY_URL = "background_url"
        const val KEY_SHA256 = "background_sha256"
        const val KEY_APK_PATH = "background_apk_path"
        const val EXTRA_UPDATE_READY = "com.nexus.music.UPDATE_READY"
        private const val CHANNEL = "nexus_updates"
    }
}
