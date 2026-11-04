package com.nexus.music.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

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
                }
            }
        val sessionId = installer.createSession(params)

        try {
            installer.openSession(sessionId).use { session ->
                apkFile.inputStream().buffered().use { input ->
                    session.openWrite("nexus-update.apk", 0, apkFile.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }

                val statusIntent = Intent(context, UpdateInstallStatusActivity::class.java)
                    .setAction(ACTION_INSTALL_STATUS)
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    sessionId,
                    statusIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(pendingIntent.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }

    private const val ACTION_INSTALL_STATUS = "com.nexus.music.UPDATE_INSTALL_STATUS"
}

class InstallPermissionRequiredException : IOException(
    "Allow Nexus to install updates from this source in Android settings, then try again."
)
