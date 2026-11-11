package com.nexus.music.update

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Hands a verified APK to Android's package installer; the user confirms the system prompt. */
object NexusUpdateInstaller {
    suspend fun install(context: Context, apkFile: File) = withContext(Dispatchers.IO) {
        if (!apkFile.isFile || apkFile.length() <= 0L) {
            throw IOException("The verified update APK is no longer available.")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            throw InstallPermissionRequiredException()
        }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setSize(apkFile.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAppPackageName(context.packageName)
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }
        val sessionId = installer.createSession(params)

        try {
            installer.openSession(sessionId).use { session ->
                apkFile.inputStream().buffered().use { input ->
                    session.openWrite("nexus-update.apk", 0, apkFile.length()).use { output ->
                        input.copyTo(output, INSTALL_BUFFER_BYTES)
                        session.fsync(output)
                    }
                }

                // PackageInstaller sends status through this explicit, internal Activity.
                // Android 15+ requires the PendingIntent creator to explicitly delegate BAL
                // privileges or the system may silently block the callback Activity.
                val statusIntent = Intent(context, UpdateInstallStatusActivity::class.java)
                    .setAction(ACTION_INSTALL_STATUS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
                val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag

                val statusPendingIntent = if (Build.VERSION.SDK_INT >= 35) {
                    val options = ActivityOptions.makeBasic().apply {
                        setPendingIntentCreatorBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                    }
                    PendingIntent.getActivity(
                        context,
                        sessionId,
                        statusIntent,
                        pendingIntentFlags,
                        options.toBundle()
                    )
                } else {
                    PendingIntent.getActivity(
                        context,
                        sessionId,
                        statusIntent,
                        pendingIntentFlags
                    )
                }

                session.commit(statusPendingIntent.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }

    private const val ACTION_INSTALL_STATUS = "com.nexus.music.UPDATE_INSTALL_STATUS"
    private const val INSTALL_BUFFER_BYTES = 32 * 1024
}

class InstallPermissionRequiredException : IOException(
    "Allow Nexus to install updates from this source in Android settings, then try again."
)
