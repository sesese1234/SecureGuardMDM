package com.secureguard.mdm.boot.impl

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.preference.PreferenceManager
import androidx.core.content.ContextCompat
import com.secureguard.mdm.boot.api.BootTask
import com.secureguard.mdm.firewall.AppFirewallVpnService
import com.secureguard.mdm.utils.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Brings the internal firewall VPN back up after a reboot.
 *
 * AppFirewallVpnService returns START_STICKY, which only covers the service being killed
 * while the system is running — nothing restarted it after a power cycle, so the VPN was
 * silently down until the user opened the firewall screen and toggled it again.
 */
class FirewallVpnBootTask @Inject constructor(
    @ApplicationContext private val context: Context
) : BootTask {

    override suspend fun onBootCompleted() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean(PREF_FIREWALL_ACTIVE, false)) {
            FileLogger.log(TAG, "Firewall was not active before reboot. Nothing to do.")
            return
        }

        val blockedApps = prefs.getStringSet(AppFirewallVpnService.PREF_FIREWALL_BLOCKED_APPS, emptySet())
        if (blockedApps.isNullOrEmpty()) {
            FileLogger.log(TAG, "Firewall active but no apps are blocked. Nothing to do.")
            return
        }

        // VpnService.prepare returns null once consent has been granted, and consent
        // survives a reboot. If it returns non-null the consent dialog is required and
        // cannot be shown from here, so the service is left alone.
        if (VpnService.prepare(context) != null) {
            FileLogger.log(TAG, "VPN consent is not granted; cannot restore firewall silently.")
            return
        }

        try {
            FileLogger.log(TAG, "Restoring firewall VPN for ${blockedApps.size} app(s).")
            ContextCompat.startForegroundService(
                context,
                Intent(context, AppFirewallVpnService::class.java)
            )
        } catch (e: Exception) {
            FileLogger.log(TAG, "Failed to restore firewall VPN: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "FirewallVpnBootTask"

        /** Written by the firewall screen when the user turns the firewall on or off. */
        const val PREF_FIREWALL_ACTIVE = "firewall_vpn_active"
    }
}
