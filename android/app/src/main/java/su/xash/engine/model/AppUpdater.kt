package su.xash.engine.model

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import su.xash.engine.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

class AppUpdater(private val context: Context) {

    data class UpdateInfo(
        val buildNum: Int,
        val versionName: String,
        val commitHash: String,
        val changelog: String?,
        val downloadUrl: String
    )

    private var cachedDownloadUrl: String? = null

    val downloadedApkFile: File
        get() = File(context.cacheDir, "xash3d-fwgs-update.apk")

    fun hasDownloadedApk(): Boolean = downloadedApkFile.exists() && downloadedApkFile.length() > 0

    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    suspend fun checkForUpdate(): UpdateInfo? {
        if (!BuildConfig.ENABLE_AUTO_UPDATE) return null
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(UPDATE_JSON_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "Update JSON check failed: HTTP ${connection.responseCode}")
                    return@withContext null
                }

                val json = JSONObject(connection.inputStream.bufferedReader().readText())
                val latestVersion = json.optString("latest_version_name", "")
                val remoteVersionCode = json.optString("latest_version_code", "0").toIntOrNull() ?: 0
                val commitHash = json.optString("latest_version_commit", "")
                val changelog = json.optString("changelog", "")

                val platforms = json.optJSONObject("platforms")
                val androidPlatform = platforms?.optJSONObject("android")
                val downloadUrl = androidPlatform?.optString("download_url", "") ?: ""

                val localVersionCode = BuildConfig.VERSION_CODE
                Log.i(TAG, "Remote versionCode: $remoteVersionCode, local: $localVersionCode")

                if (remoteVersionCode > localVersionCode && downloadUrl.isNotEmpty()) {
                    cachedDownloadUrl = downloadUrl
                    UpdateInfo(
                        buildNum = remoteVersionCode,
                        versionName = latestVersion,
                        commitHash = commitHash,
                        changelog = changelog,
                        downloadUrl = downloadUrl
                    )
                } else {
                    if (hasDownloadedApk()) {
                        downloadedApkFile.delete()
                    }
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "Update check failed: ${e.message}")
                null
            } catch (e: JSONException) {
                Log.w(TAG, "Update check parse failed: ${e.message}")
                null
            } finally {
                connection?.disconnect()
            }
        }
    }

    suspend fun downloadAndInstall(
        customUrl: String? = null,
        onProgress: (Long, Long) -> Unit
    ): Result<Unit> {
        val targetUrl = customUrl ?: cachedDownloadUrl
        
        if (hasDownloadedApk() && targetUrl.isNullOrEmpty()) {
            return try {
                triggerInstall(downloadedApkFile)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        if (targetUrl.isNullOrEmpty()) {
            return Result.failure(IOException("Download URL is empty"))
        }

        return withContext(Dispatchers.IO) {
            val tempFile = downloadedApkFile
            var connection: HttpURLConnection? = null
            try {
                connection = URL(targetUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK)
                    return@withContext Result.failure(IOException("HTTP ${connection.responseCode}"))

                val total = connection.contentLengthLong
                var downloaded = 0L
                var lastEmit = 0L

                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0)
                                break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                                lastEmit = now
                                withContext(Dispatchers.Main) { onProgress(downloaded, total) }
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) { onProgress(downloaded, total) }

                Log.i(TAG, "Downloaded APK: ${tempFile.length()} bytes -> ${tempFile.absolutePath}")

                triggerInstall(tempFile)
                Result.success(Unit)
            } catch (e: CancellationException) {
                tempFile.delete()
                throw e
            } catch (e: IOException) {
                tempFile.delete()
                Result.failure(e)
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun installDownloadedApk() {
        if (hasDownloadedApk()) {
            triggerInstall(downloadedApkFile)
        }
    }

    private fun triggerInstall(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val statusIntent = Intent(INSTALL_ACTION).setPackage(context.packageName)
            val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, sessionId, statusIntent, piFlags)
            session.commit(pi.intentSender)
        }
    }

    companion object {
        private const val TAG = "AppUpdater"
        private const val PROGRESS_INTERVAL_MS = 100L
        const val INSTALL_ACTION = "su.xash.engine.INSTALL_RESULT"
        private const val UPDATE_JSON_URL = "https://xash3d.yalnie.workers.dev/"
    }
}
