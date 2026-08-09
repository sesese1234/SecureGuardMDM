package com.secureguard.mdm.astore.downloader

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import com.secureguard.mdm.utils.AppLogger
import com.secureguard.mdm.receivers.InstallReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.*
import java.net.URL
import java.net.URLDecoder
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.*

private const val TAG = "AstoreDownloader"
private const val INSTALL_SESSION_NAME = "AstoreInstallSession"

sealed class AstoreDownloadProgress {
    data class Downloading(val progress: Int) : AstoreDownloadProgress()
    object Installing : AstoreDownloadProgress()
    object Completed : AstoreDownloadProgress()
    data class Error(val message: String) : AstoreDownloadProgress()
}

/**
 * Download result containing the file path and package name
 */
data class DownloadResult(
    val file: File,
    val packageName: String?,
    val fileName: String
)

@Singleton
class AstoreDownloader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connections = ConcurrentHashMap<String, HttpsURLConnection>()
    private val canceledDownloads = ConcurrentHashMap<String, Boolean>()

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
    })

    private val sslSocketFactory: SSLSocketFactory by lazy {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        sslContext.socketFactory
    }

    /**
     * Downloads an APK or XAPK file with resume support and trust-all SSL.
     * 
     * @param packageName The package name for base naming if filename resolution fails
     * @param urlString The download URL
     * @return Flow of download progress
     */
    fun downloadApk(packageName: String, urlString: String): Flow<AstoreDownloadProgress> = callbackFlow {
        canceledDownloads[urlString] = false
        var finalFilename = ""

        // We skip HEAD request because CDNs like APKPure block it and return 403.

        if (finalFilename.isEmpty()) {
            finalFilename = getFileNameFromUrl(urlString)
        }

        if (finalFilename.isEmpty()) {
            finalFilename = "${packageName}_update.apk"
        }

        val externalFilesDir = context.filesDir
        val tempFile = File(externalFilesDir, "$finalFilename.tmp")

        if (canceledDownloads[urlString] == true) {
            close()
            return@callbackFlow
        }

        val maxRetries = 3
        var lastException: Exception? = null

        for (attempt in 1..maxRetries) {
            if (canceledDownloads[urlString] == true) break

            var input: InputStream? = null
            var output: RandomAccessFile? = null
            var connection: HttpsURLConnection? = null

            try {
                val url = URL(urlString)
                connection = url.openConnection() as HttpsURLConnection
                connection.sslSocketFactory = sslSocketFactory
                
                val userAgent = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/137.0 Mobile"
                connection.setRequestProperty("User-Agent", userAgent)
                
                var existingFileSize = 0L
                if (tempFile.exists()) {
                    existingFileSize = tempFile.length()
                    connection.setRequestProperty("Range", "bytes=$existingFileSize-")
                    AppLogger.d(TAG, "Resuming download for $packageName from $existingFileSize bytes (attempt $attempt)")
                } else {
                    connection.setRequestProperty("Connection", "Close")
                }
                
                connection.connectTimeout = 30000
                connection.readTimeout = 60000
                connections[urlString] = connection
                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode == HttpsURLConnection.HTTP_OK || responseCode == HttpsURLConnection.HTTP_PARTIAL) {
                    output = RandomAccessFile(tempFile, "rw")

                    if (responseCode == HttpsURLConnection.HTTP_OK) {
                        existingFileSize = 0
                        output.setLength(0)
                    } else {
                        output.seek(existingFileSize)
                    }

                    val currentContentLen = connection.contentLength
                    val totalLength = if (currentContentLen != -1) currentContentLen.toLong() + existingFileSize else 0L
                    input = connection.inputStream

                    val data = ByteArray(8192)
                    var total = existingFileSize
                    var count: Int

                    while (input.read(data).also { count = it } != -1) {
                        if (canceledDownloads[urlString] == true) break
                        output.write(data, 0, count)
                        total += count
                        
                        if (totalLength > 0) {
                            val progress = (total * 100 / totalLength).toInt()
                            trySend(AstoreDownloadProgress.Downloading(progress))
                        }
                    }

                    if (canceledDownloads[urlString] != true) {
                        trySend(AstoreDownloadProgress.Downloading(100))
                        val finalFile = File(externalFilesDir, finalFilename)
                        tempFile.renameTo(finalFile)
                        
                        AppLogger.d(TAG, "Download complete for $packageName. File: ${finalFile.name}")
                        
                        // Determine if this is an XAPK archive (ZIP containing .apk files)
                        // or a regular APK. Both are ZIP files, so we check the actual
                        // contents: if the ZIP contains .apk entries, it's a split APK archive.
                        val isXapk = isXapkArchive(finalFile)
                        AppLogger.d(TAG, "File type for $packageName: ${if (isXapk) "XAPK archive" else "regular APK"}")
                        
                        trySend(AstoreDownloadProgress.Installing)
                        
                        if (isXapk) {
                            try {
                                installXapkSilently(finalFile, packageName)
                            } catch (e: Exception) {
                                AppLogger.w(TAG, "XAPK install failed, trying as regular APK: ${e.message}")
                                installApkSilently(finalFile)
                            }
                        } else {
                            installApkSilently(finalFile)
                        }
                        
                        trySend(AstoreDownloadProgress.Completed)
                    }
                    // Download succeeded, break out of retry loop
                    lastException = null
                    break
                } else {
                    val errorMsg = "Server returned code $responseCode"
                    AppLogger.e(TAG, errorMsg)
                    trySend(AstoreDownloadProgress.Error(errorMsg))
                    break // Don't retry on HTTP errors (4xx, 5xx)
                }
            } catch (e: Exception) {
                lastException = e
                AppLogger.w(TAG, "Download attempt $attempt/$maxRetries failed for $packageName: ${e.message}")
                
                if (attempt < maxRetries && canceledDownloads[urlString] != true) {
                    // Wait before retrying, the temp file will be used for resume
                    AppLogger.d(TAG, "Retrying download for $packageName in 2 seconds...")
                    kotlinx.coroutines.delay(2000)
                }
            } finally {
                try {
                    output?.close()
                    input?.close()
                    connection?.disconnect()
                } catch (ignored: Exception) {}
                connections.remove(urlString)
            }
        }

        // If all retries failed, report the error
        if (lastException != null) {
            AppLogger.e(TAG, "Download failed for $packageName after $maxRetries attempts", lastException)
            trySend(AstoreDownloadProgress.Error(lastException?.message ?: "Download failed after $maxRetries attempts"))
        }

        // The work is finished here. Without this close() the flow stayed open on
        // awaitClose forever, so the collector never returned, the package was never
        // released from activeInstalls, and every later attempt to update it was
        // dropped by the in-flight guard — the update just appeared to hang.
        close()

        awaitClose { connections[urlString]?.disconnect() }
    }.flowOn(Dispatchers.IO)

    private fun getFilenameFromHeader(connection: HttpsURLConnection): String {
        val contentDisposition = connection.getHeaderField("Content-Disposition")
        if (!contentDisposition.isNullOrEmpty()) {
            try {
                val index = contentDisposition.lowercase().indexOf("filename=")
                if (index != -1) {
                    var result = contentDisposition.substring(index + 9)
                    if (result.startsWith("\"")) {
                        result = result.substring(1, result.lastIndexOf("\""))
                    } else if (result.contains(";")) {
                        result = result.substring(0, result.indexOf(";"))
                    }
                    return result
                }
            } catch (e: Exception) {}
        }
        return ""
    }

    private fun getFileNameFromUrl(urlString: String): String {
        try {
            val uri = Uri.parse(urlString)
            val contentDisposition = uri.getQueryParameter("response-content-disposition")

            if (contentDisposition != null && contentDisposition.contains("filename=")) {
                val index = contentDisposition.indexOf("filename=")
                var fileName = contentDisposition.substring(index + 9)
                fileName = fileName.replace("\"", "")
                return URLDecoder.decode(fileName, "UTF-8")
            }

            val lastPathSegment = uri.lastPathSegment
            if (lastPathSegment != null) {
                // Check if the URL path indicates XAPK format (e.g., /b/XAPK/com.package.name)
                val urlPath = uri.path?.uppercase() ?: ""
                val hasFileExtension = lastPathSegment.matches(Regex(".*\\.(apk|xapk|apks|apkm|zip)$", RegexOption.IGNORE_CASE))
                
                return when {
                    hasFileExtension -> lastPathSegment
                    urlPath.contains("/XAPK/") -> "$lastPathSegment.xapk"
                    else -> "$lastPathSegment.apk"
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error parsing URL for filename: $e")
        }
        return ""
    }

    fun cancelDownload(urlString: String) {
        canceledDownloads[urlString] = true
        connections[urlString]?.disconnect()
    }
    
    /**
     * Check if a file is a ZIP file (XAPK)
     */
    private fun isZipFile(file: File): Boolean {
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(2)
                fis.read(header)
                header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Check if a file is an XAPK archive (a ZIP that contains .apk files inside).
     * Regular APKs are also ZIP files, but they contain classes.dex, AndroidManifest.xml, etc.
     * XAPK archives contain actual .apk files as entries.
     */
    private fun isXapkArchive(file: File): Boolean {
        return try {
            java.util.zip.ZipFile(file).use { zip ->
                zip.entries().asSequence().any { entry ->
                    !entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)
                }
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Install an XAPK file by extracting and installing APKs.
     * Uses ZipFile instead of ZipInputStream because ZipInputStream can't handle
     * STORED entries with EXT descriptors (throws "only DEFLATED entries can have EXT descriptor").
     * ZipFile reads the central directory and handles these correctly.
     */
    private suspend fun installXapkSilently(xapkFile: File, expectedPackageName: String) {
        val extractDir = File(context.filesDir, "${expectedPackageName}_extracted")
        extractDir.mkdirs()
        
        try {
            java.util.zip.ZipFile(xapkFile).use { zipFile ->
                zipFile.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                    .forEach { entry ->
                        val entryName = entry.name.substringAfterLast('/')
                        val entryFile = File(extractDir, entryName)
                        zipFile.getInputStream(entry).use { input ->
                            FileOutputStream(entryFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
            }
            
            val apkFiles = extractDir.listFiles()?.filter { it.name.endsWith(".apk") } ?: emptyList()
            if (apkFiles.isEmpty()) throw Exception("No APK files found in XAPK")
            
            AppLogger.d(TAG, "Extracted ${apkFiles.size} APKs from XAPK for $expectedPackageName")
            installMultipleApks(apkFiles, expectedPackageName)
            
            // Only delete the original XAPK after successful installation
            if (xapkFile.exists()) xapkFile.delete()
        } finally {
            extractDir.deleteRecursively()
        }
    }
    
    private suspend fun installMultipleApks(apkFiles: List<File>, expectedPackageName: String) {
        var session: PackageInstaller.Session? = null
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            params.setAppPackageName(expectedPackageName)
            
            val sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)
            
            apkFiles.forEach { apkFile ->
                val apkName = apkFile.name
                FileInputStream(apkFile).use { fileInputStream ->
                    session.openWrite(apkName, 0, apkFile.length()).use { sessionOutputStream ->
                        fileInputStream.copyTo(sessionOutputStream)
                        session.fsync(sessionOutputStream)
                    }
                }
            }

            val intent = Intent(context, InstallReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pendingIntent.intentSender)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Multi-APK installation failed.", e)
            session?.abandon()
            throw e
        } finally {
            session?.close()
        }
    }

    private suspend fun installApkSilently(apkFile: File) {
        var session: PackageInstaller.Session? = null
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            // Without this, Android 12+ answers the commit with STATUS_PENDING_USER_ACTION
            // instead of installing, which is why single-APK updates never landed.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)

            FileInputStream(apkFile).use { fileInputStream ->
                session.openWrite(INSTALL_SESSION_NAME, 0, apkFile.length()).use { sessionOutputStream ->
                    fileInputStream.copyTo(sessionOutputStream)
                    session.fsync(sessionOutputStream)
                }
            }

            val intent = Intent(context, InstallReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pendingIntent.intentSender)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Silent installation failed.", e)
            session?.abandon()
            throw e
        } finally {
            session?.close()
            if (apkFile.exists()) apkFile.delete()
        }
    }

    /**
     * Downloads an APK file to internal storage without installing.
     */
    suspend fun downloadApkOnly(packageName: String, urlString: String): DownloadResult? = withContext(Dispatchers.IO) {
        try {
            val outputFile = File(context.filesDir, "${packageName}_${System.currentTimeMillis()}.apk")
            val url = URL(urlString)
            val connection = url.openConnection() as HttpsURLConnection
            connection.sslSocketFactory = sslSocketFactory
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.connect()

            if (connection.responseCode in 200..299) {
                connection.inputStream.use { input ->
                    FileOutputStream(outputFile).use { output ->
                        input.copyTo(output)
                    }
                }
                DownloadResult(outputFile, packageName, outputFile.name)
            } else null
        } catch (e: Exception) {
            AppLogger.e(TAG, "Download failed for $packageName", e)
            null
        }
    }

    suspend fun installDownloadedApk(apkFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!apkFile.exists()) return@withContext false
            installApkSilently(apkFile)
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to install APK: ${apkFile.absolutePath}", e)
            false
        }
    }
}
