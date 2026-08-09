package com.secureguard.mdm

import android.content.ComponentName
import android.content.pm.PackageManager
import com.emanuelef.remote_capture.PCAPdroid
import com.secureguard.mdm.boot.BootCompletedReceiver
import com.secureguard.mdm.utils.AppLogger
import dagger.hilt.android.HiltAndroidApp
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

@HiltAndroidApp
class SecureGuardApplication : PCAPdroid() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        AppLogger.i("Application", "App started. Logger initialized.")
        setupGlobalSslTrust()
        ensureBootReceiverEnabled()
    }

    /**
     * The receiver ships disabled in the manifest and is switched on when the password is
     * first created. Installs that never went through that path (upgrades from an older
     * build) had it left disabled, so no boot task ever ran and nothing was restored after
     * a reboot. Re-asserting it on every start is idempotent and cheap.
     */
    private fun ensureBootReceiverEnabled() {
        try {
            val receiver = ComponentName(this, BootCompletedReceiver::class.java)
            if (packageManager.getComponentEnabledSetting(receiver) !=
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            ) {
                packageManager.setComponentEnabledSetting(
                    receiver,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                AppLogger.i("Application", "BootCompletedReceiver enabled.")
            }
        } catch (e: Exception) {
            AppLogger.e("Application", "Failed to enable BootCompletedReceiver: ${e.message}")
        }
    }

    private fun setupGlobalSslTrust() {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
            })

            val sc = SSLContext.getInstance("SSL")
            sc.init(null, trustAllCerts, SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
            AppLogger.i("Application", "Global SSL trust established.")
        } catch (e: Exception) {
            AppLogger.e("Application", "Failed to setup global SSL trust: ${e.message}")
        }
    }
}