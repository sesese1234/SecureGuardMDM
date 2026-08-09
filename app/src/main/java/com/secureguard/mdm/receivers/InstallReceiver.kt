package com.secureguard.mdm.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.secureguard.mdm.R

class InstallReceiver : BroadcastReceiver() {
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)

        // The installer asks for confirmation instead of installing whenever the session
        // could not run unattended. This was previously treated as a plain failure, so
        // the install was simply dropped and the user only saw an error.
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmationIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            }
            if (confirmationIntent != null) {
                confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirmationIntent)
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.update_toast_failed), Toast.LENGTH_LONG).show()
                }
            }
            return
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            // The framework reports the package under EXTRA_PACKAGE_NAME; the old
            // "package_name" key was always absent, so this never matched.
            val installedPackageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)

            // אם החבילה שהותקנה היא NoPhone, הקפץ בקשה להגדיר כברירת מחדל
            if (installedPackageName == "org.fossify.phone") {
                Toast.makeText(context, R.string.toast_nophone_installed, Toast.LENGTH_LONG).show()
                val changeDialerIntent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                changeDialerIntent.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, installedPackageName)
                changeDialerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(changeDialerIntent)
            } else {
                Toast.makeText(context, R.string.update_toast_success, Toast.LENGTH_SHORT).show()
            }
        } else {
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            Toast.makeText(context, context.getString(R.string.update_toast_failed) + ": " + message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        /**
         * Returns the set of package names that are incompatible with the app store
         * (e.g. packages blocked by MDM policy or known incompatible packages).
         */
        fun getIncompatiblePackages(context: Context): Set<String> {
            // Return empty set by default - can be extended to read from settings/policy
            return emptySet()
        }
    }
}