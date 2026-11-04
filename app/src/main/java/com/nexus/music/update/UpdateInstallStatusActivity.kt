package com.nexus.music.update

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/** Receives PackageInstaller status callbacks and opens Android's required approval screen. */
class UpdateInstallStatusActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleInstallerStatus(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInstallerStatus(intent)
    }

    private fun handleInstallerStatus(statusIntent: Intent?) {
        if (statusIntent == null) {
            finish()
            return
        }

        when (statusIntent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmationIntent = parcelableIntent(statusIntent)
                if (confirmationIntent != null) {
                    startActivity(confirmationIntent)
                } else {
                    Toast.makeText(this, "Android could not open the update approval screen.", Toast.LENGTH_LONG).show()
                }
                finish()
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(this, "Nexus was updated successfully.", Toast.LENGTH_LONG).show()
                finish()
            }

            else -> {
                val message = statusIntent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "Android could not install this update."
                Toast.makeText(this, "Update not installed: $message", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun parcelableIntent(source: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            source.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            source.getParcelableExtra(Intent.EXTRA_INTENT)
        }
}
