package com.nexus.music.update

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Receives PackageInstaller status callbacks and opens Android's required approval screen.
 * The handoff is deferred until this Activity is resumed so the system confirmation UI is
 * launched from a visible Activity rather than from an early lifecycle callback.
 */
class UpdateInstallStatusActivity : Activity() {
    private var pendingConfirmationIntent: Intent? = null
    private var isResumed = false
    private var confirmationLaunchPosted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleInstallerStatus(intent)
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        launchConfirmationWhenVisible()
    }

    override fun onPause() {
        isResumed = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        confirmationLaunchPosted = false
        pendingConfirmationIntent = null
        handleInstallerStatus(intent)
    }

    private fun handleInstallerStatus(statusIntent: Intent?) {
        if (statusIntent == null) {
            finish()
            return
        }

        when (statusIntent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                pendingConfirmationIntent = parcelableIntent(statusIntent)
                if (pendingConfirmationIntent == null) {
                    showMessage("Android could not open the update approval screen.")
                    finish()
                } else {
                    launchConfirmationWhenVisible()
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                showMessage("Nexus was updated successfully.")
                finish()
            }

            else -> {
                val message = statusIntent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf(String::isNotBlank)
                    ?.take(MAX_STATUS_MESSAGE_CHARS)
                    ?: "Android could not install this update."
                showMessage("Update not installed: $message")
                finish()
            }
        }
    }

    private fun launchConfirmationWhenVisible() {
        val confirmationIntent = pendingConfirmationIntent ?: return
        if (!isResumed || confirmationLaunchPosted || isFinishing) return

        confirmationLaunchPosted = true
        window.decorView.post {
            if (isFinishing || isDestroyed) return@post
            try {
                startActivity(confirmationIntent)
            } catch (error: Exception) {
                showMessage(
                    "Android received the update, but its approval screen could not be opened. " +
                        "Reopen Nexus and try the update again."
                )
            } finally {
                pendingConfirmationIntent = null
                finish()
            }
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    @Suppress("DEPRECATION")
    private fun parcelableIntent(source: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            source.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            source.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        private const val MAX_STATUS_MESSAGE_CHARS = 180
    }
}
