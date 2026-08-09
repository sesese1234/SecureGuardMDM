package com.secureguard.mdm.astore.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.secureguard.mdm.receivers.InstallReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed class InstallResult {
    object Success : InstallResult()
    data class Failure(val error: String) : InstallResult()
}

@Singleton
class AppInstaller @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    /**
     * Installs an APK or archive file (XAPK, APKS, APKM, ZIP).
     * Automatically detects the file type and handles accordingly.
     */
    suspend fun install(file: File): InstallResult = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                return@withContext InstallResult.Failure("File not found: ${file.name}")
            }

            when {
                file.name.endsWith(".apk", ignoreCase = true) -> {
                    installSingleApk(file)
                }
                file.name.endsWith(".zip", ignoreCase = true) ||
                file.name.endsWith(".xapk", ignoreCase = true) ||
                file.name.endsWith(".apks", ignoreCase = true) ||
                file.name.endsWith(".apkm", ignoreCase = true) -> {
                    installFromArchive(file)
                }
                else -> {
                    InstallResult.Failure("Unsupported file format: ${file.name}")
                }
            }
        } catch (e: Exception) {
            InstallResult.Failure(e.localizedMessage ?: "Installation failed")
        }
    }
    
    /**
     * Installs a single APK file.
     */
    private suspend fun installSingleApk(file: File): InstallResult = withContext(Dispatchers.IO) {
        var session: PackageInstaller.Session? = null
        try {
            val packageManager = context.packageManager
            val packageInstaller = packageManager.packageInstaller
            
            // Get package name from APK
            val packageName = try {
                packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName
            } catch (e: Exception) {
                null
            }
            
            if (packageName == null) {
                return@withContext InstallResult.Failure("Cannot read package name from APK")
            }
            
            // Create installation session
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            // As device owner we can install unattended; without this Android 12+ replies
            // with STATUS_PENDING_USER_ACTION rather than performing the install.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            params.setAppPackageName(packageName)
            
            val sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)
            
            // Write APK to session
            session.openWrite(file.name, 0, file.length()).use { out ->
                FileInputStream(file).use { input ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            
            // Commit session.
            // FLAG_UPDATE_CURRENT must be kept on API 31+ as well, and the session id is
            // used as the request code: with a fixed code of 0 every install reused the
            // first PendingIntent, so status callbacks were delivered against a stale
            // intent and concurrent installs clobbered each other.
            val intent = Intent(context, InstallReceiver::class.java)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pendingIntent.intentSender)
            session.close()
            session = null
            
            InstallResult.Success
        } catch (e: Exception) {
            session?.abandon()
            InstallResult.Failure(e.localizedMessage ?: "APK installation failed")
        } finally {
            session?.close()
        }
    }
    
    /**
     * Extracts and installs APKs from an archive (XAPK, APKS, etc.).
     * Streams APKs directly from ZIP to PackageInstaller session.
     */
    private suspend fun installFromArchive(archiveFile: File): InstallResult = withContext(Dispatchers.IO) {
        var session: PackageInstaller.Session? = null
        val tempDir = File(context.filesDir, "cach/apks_temp/")
        
        try {
            // Clean up any previous temp files
            deleteTempDir(tempDir)
            tempDir.mkdirs()
            
            val packageManager = context.packageManager
            val packageInstaller = packageManager.packageInstaller
            var packageName: String? = null
            
            // First pass: extract first APK to read package name
            FileInputStream(archiveFile).use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null && packageName == null) {
                        if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                            val tempApk = File(tempDir, "temp.apk")
                            FileOutputStream(tempApk).use { out ->
                                val buffer = ByteArray(8192)
                                var read: Int
                                while (zis.read(buffer).also { read = it } > 0) {
                                    out.write(buffer, 0, read)
                                }
                            }
                            
                            packageName = try {
                                packageManager.getPackageArchiveInfo(tempApk.absolutePath, 0)?.packageName
                            } catch (e: Exception) {
                                null
                            }
                            
                            tempApk.delete()
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            
            if (packageName == null) {
                return@withContext InstallResult.Failure("No valid APKs found in archive")
            }
            
            // Create installation session
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            // As device owner we can install unattended; without this Android 12+ replies
            // with STATUS_PENDING_USER_ACTION rather than performing the install.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            params.setAppPackageName(packageName)
            
            val sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)
            
            // Second pass: stream all APKs to session
            var apkCount = 0
            FileInputStream(archiveFile).use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                            val apkName = entry.name.substringAfterLast('/')
                            val size = entry.size
                            
                            session.openWrite(apkName, 0, if (size > 0) size else -1).use { out ->
                                val buffer = ByteArray(8192)
                                var read: Int
                                while (zis.read(buffer).also { read = it } > 0) {
                                    out.write(buffer, 0, read)
                                }
                                session.fsync(out)
                            }
                            apkCount++
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            
            if (apkCount == 0) {
                session.abandon()
                return@withContext InstallResult.Failure("No APK files found in archive")
            }
            
            // Commit session.
            // FLAG_UPDATE_CURRENT must be kept on API 31+ as well, and the session id is
            // used as the request code: with a fixed code of 0 every install reused the
            // first PendingIntent, so status callbacks were delivered against a stale
            // intent and concurrent installs clobbered each other.
            val intent = Intent(context, InstallReceiver::class.java)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pendingIntent.intentSender)
            session.close()
            session = null
            
            InstallResult.Success
        } catch (e: Exception) {
            session?.abandon()
            InstallResult.Failure(e.localizedMessage ?: "Archive installation failed")
        } finally {
            session?.close()
            deleteTempDir(tempDir)
        }
    }
    
    /**
     * Deletes a directory and all its contents.
     */
    private fun deleteTempDir(dir: File) {
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    deleteTempDir(file)
                } else {
                    file.delete()
                }
            }
            dir.delete()
        }
    }
}
