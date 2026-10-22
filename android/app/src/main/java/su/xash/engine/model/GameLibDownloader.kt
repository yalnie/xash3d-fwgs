package su.xash.engine.model

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

class GameLibDownloader(private val context: Context) {

	private val prefs get() = context.getSharedPreferences("gamelib_metadata", Context.MODE_PRIVATE)

	// converts Android's ABI to library-suffix's arch names
	fun getArch(): String? {
		for (abi in Build.SUPPORTED_ABIS) {
			when (abi) {
				"arm64-v8a" -> return "arm64"
				"armeabi-v7a" -> return "armv7l"
				"x86_64" -> return "amd64"
				"x86" -> return "i386"
			}
		}
		return null
	}

	fun getLibsBaseDir(): File = File(context.filesDir, "gamelibs")

	fun isDownloaded(gamedir: String): Boolean {
		val dir = File(getLibsBaseDir(), gamedir)
		if (dir.isDirectory && dir.list()?.isNotEmpty() == true)
			return true

		// zips extract to the case used in the manifest (e.g. "CAd"); the user's
		// folder name might differ in case, so do a case-insensitive lookup too.
		val base = getLibsBaseDir()
		val match = base.listFiles()?.firstOrNull {
			it.isDirectory && it.name.equals(gamedir, ignoreCase = true)
		}
		return match != null && (match.list()?.isNotEmpty() == true)
	}

	data class ManifestEntry(
		val modKey: String,
		val platformArch: String,
		val filename: String,
		val sha256: String,
		val sourceJson: String?,
		val isYalnieBuild: Boolean
	)

	data class SourceInfo(
		val url: String?,
		val branch: String?,
		val commit: String?
	)

	private fun fwgsCacheFile(): File = File(context.cacheDir, "hlsdk-manifest-fwgs.json")
	private fun yalnieCacheFile(): File = File(context.cacheDir, "hlsdk-manifest-yalnie.json")

	private suspend fun fetchCombinedManifests(): Pair<JSONObject?, JSONObject?> {
		val yalnie = fetchSingleManifest(YALNIE_MANIFEST_URL, yalnieCacheFile())
		val fwgs = fetchSingleManifest(FWGS_MANIFEST_URL, fwgsCacheFile())
		return fwgs to yalnie
	}

	private suspend fun fetchSingleManifest(urlStr: String, cacheFile: File): JSONObject? {
		var connection: HttpURLConnection? = null
		try {
			connection = URL(urlStr).openConnection() as HttpURLConnection
			connection.connectTimeout = 10000
			connection.readTimeout = 15000
			connection.instanceFollowRedirects = true
			connection.connect()

			if (connection.responseCode != HttpURLConnection.HTTP_OK) {
				return readCachedManifest(cacheFile)
			}

			val text = connection.inputStream.bufferedReader().use { it.readText() }
			val json = JSONObject(text)
			if (checkManifestVersion(json) != null) {
				return readCachedManifest(cacheFile)
			}
			cacheFile.writeText(text)
			return json
		} catch (e: Exception) {
			return readCachedManifest(cacheFile)
		} finally {
			connection?.disconnect()
		}
	}

	private fun readCachedManifest(cacheFile: File): JSONObject? {
		if (!cacheFile.exists())
			return null
		val json = try {
			JSONObject(cacheFile.readText())
		} catch (_: Exception) {
			return null
		}
		return if (checkManifestVersion(json) == null) json else null
	}

	private fun checkManifestVersion(json: JSONObject): IOException? {
		if (!json.has("version"))
			return IOException("Manifest missing 'version' field")
		val v = json.opt("version")
		if (v !is Int || v != MANIFEST_VERSION)
			return IOException("Unsupported manifest version: $v (expected $MANIFEST_VERSION)")
		return null
	}

	private fun lookupEntryCombined(fwgs: JSONObject?, yalnie: JSONObject?, gamedir: String): ManifestEntry? {
		val arch = getArch() ?: return null
		val platformArch = "android-$arch"

		if (yalnie != null) {
			val entry = extractEntryFromManifest(yalnie, gamedir, platformArch, true)
			if (entry != null) return entry
		}

		if (fwgs != null) {
			return extractEntryFromManifest(fwgs, gamedir, platformArch, false)
		}

		return null
	}

	private fun extractEntryFromManifest(manifest: JSONObject, gamedir: String, platformArch: String, isYalnie: Boolean): ManifestEntry? {
		val mods = manifest.optJSONObject("mods") ?: return null

		// Find mod key case-insensitively
		var modKey: String? = null
		val keys = mods.keys()
		while (keys.hasNext()) {
			val k = keys.next()
			if (k.equals(gamedir, ignoreCase = true)) {
				modKey = k
				break
			}
		}
		if (modKey == null)
			return null

		val builds = mods.optJSONObject(modKey)?.optJSONObject("builds") ?: return null
		val build = builds.optJSONObject(platformArch) ?: return null
		val filename = build.optString("filename", "")
		val sha256 = build.optString("sha256", "")
		if (filename.isEmpty() || sha256.isEmpty())
			return null

		val sourceJson = build.optJSONObject("source")?.toString()
		return ManifestEntry(modKey, platformArch, filename, sha256.lowercase(), sourceJson, isYalnie)
	}

	fun getSourceInfo(gamedir: String): SourceInfo? {
		val raw = prefs.getString("${gamedir}_source", null) ?: return null
		return try {
			val o = JSONObject(raw)
			SourceInfo(
				url = o.optString("url").ifEmpty { null },
				branch = o.optString("branch").ifEmpty { null },
				commit = o.optString("commit").ifEmpty { null },
			)
		} catch (_: Exception) {
			null
		}
	}

	fun getDownloadTime(gamedir: String): Long =
		prefs.getLong("${gamedir}_download_time", 0L)

	sealed class Lookup {
		data class Available(val entry: ManifestEntry) : Lookup()
		object NotInManifest : Lookup()
		data class Error(val cause: Throwable) : Lookup()
	}

	suspend fun lookupBuild(gamedir: String): Lookup {
		return withContext(Dispatchers.IO) {
			val (fwgs, yalnie) = fetchCombinedManifests()
			if (fwgs == null && yalnie == null)
				return@withContext Lookup.Error(IOException("Failed to fetch manifests from both sources"))
			val entry = lookupEntryCombined(fwgs, yalnie, gamedir)
				?: return@withContext Lookup.NotInManifest
			Lookup.Available(entry)
		}
	}

	suspend fun isUpdateAvailable(gamedir: String): Boolean {
		return withContext(Dispatchers.IO) {
			val (fwgs, yalnie) = fetchCombinedManifests()
			val entry = lookupEntryCombined(fwgs, yalnie, gamedir) ?: return@withContext false
			val storedSha = prefs.getString("${gamedir}_sha256", null)
			storedSha == null || !storedSha.equals(entry.sha256, ignoreCase = true)
		}
	}

	private fun urlForEntry(entry: ManifestEntry): String {
		val baseUrl = if (entry.isYalnieBuild) YALNIE_BASE_URL else FWGS_BASE_URL
		return "$baseUrl/${entry.filename}"
	}

	private suspend fun tryDownload(
		url: String,
		dest: File,
		onProgress: (Long, Long) -> Unit
	): Exception? {
		var connection: HttpURLConnection? = null
		try {
			connection = URL(url).openConnection() as HttpURLConnection
			connection.connectTimeout = 10000
			connection.readTimeout = 30000
			connection.instanceFollowRedirects = true
			connection.connect()

			val code = connection.responseCode
			if (code !in 200..299)
				return IOException("HTTP $code: $url")

			val total = connection.contentLengthLong
			var downloaded = 0L
			var lastEmit = 0L

			connection.inputStream.use { input ->
				FileOutputStream(dest).use { output ->
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

			return null
		} catch (e: Exception) {
			return e
		} finally {
			connection?.disconnect()
		}
	}

	suspend fun download(gamedir: String, onProgress: (Long, Long) -> Unit): Result<Unit> {
		return withContext(Dispatchers.IO) {
			val (fwgs, yalnie) = fetchCombinedManifests()
			if (fwgs == null && yalnie == null)
				return@withContext Result.failure(IOException("Failed to fetch manifests"))

			val entry = lookupEntryCombined(fwgs, yalnie, gamedir)
				?: return@withContext Result.failure(IOException("No build for '$gamedir' on ${getArch() ?: "this arch"}"))

			val url = urlForEntry(entry)
			val tempFile = File(context.cacheDir, entry.filename)

			try {
				coroutineContext.ensureActive()
				Log.i(TAG, "Downloading $url -> ${tempFile.absolutePath}")
				val error = tryDownload(url, tempFile, onProgress)
				if (error != null)
					return@withContext Result.failure(error)

				val actualSha = computeSHA256(tempFile)
				Log.i(TAG, "Downloaded ${tempFile.length()} bytes, SHA-256: $actualSha (expected ${entry.sha256})")

				if (!actualSha.equals(entry.sha256, ignoreCase = true))
					return@withContext Result.failure(IOException("SHA-256 mismatch: expected ${entry.sha256}, got $actualSha"))

				val destDir = getLibsBaseDir()
				destDir.mkdirs()
				val destCanon = destDir.canonicalPath

				// Remove any prior install (possibly under a differently-cased dir) so we
				// don't leave behind .so files from an older build.
				destDir.listFiles()?.forEach {
					if (it.isDirectory && it.name.equals(entry.modKey, ignoreCase = true))
						it.deleteRecursively()
				}

				Log.i(TAG, "Extracting to ${destDir.absolutePath}")

				val extractedFiles = mutableListOf<String>()
				ZipInputStream(tempFile.inputStream()).use { zip ->
					var zipEntry = zip.nextEntry
					while (zipEntry != null) {
						coroutineContext.ensureActive()
						if (!zipEntry.isDirectory) {
							val outFile = File(destDir, zipEntry.name)
							if (!outFile.canonicalPath.startsWith(destCanon + File.separator))
								throw SecurityException("Zip path traversal: ${zipEntry.name}")
							outFile.parentFile?.mkdirs()
							FileOutputStream(outFile).use { fos ->
								zip.copyTo(fos)
							}
							// ZipInputStream doesn't restore Unix permissions; shared libraries
							// need the execute bit so dlopen can mmap them with PROT_EXEC
							if (outFile.name.endsWith(".so"))
								outFile.setExecutable(true, false)
							extractedFiles.add("  ${outFile.absolutePath} (${outFile.length()} bytes, executable: ${outFile.canExecute()})")
						}
						zip.closeEntry()
						zipEntry = zip.nextEntry
					}
				}

				Log.i(TAG, "Extracted ${extractedFiles.size} files:")
				extractedFiles.forEach { Log.i(TAG, it) }

				prefs.edit()
					.putString("${gamedir}_sha256", entry.sha256)
					.putString("${gamedir}_filename", entry.filename)
					.putString("${gamedir}_source", entry.sourceJson)
					.putLong("${gamedir}_download_time", System.currentTimeMillis())
					.apply()

				Result.success(Unit)
			} catch (e: Exception) {
				// Best-effort: rewinding to a clean state on failure
				File(getLibsBaseDir(), entry.modKey).deleteRecursively()
				Result.failure(e)
			} finally {
				tempFile.delete()
			}
		}
	}

	fun logExistingLibs(gamedir: String) {
		val gameDir = File(getLibsBaseDir(), gamedir)
		val resolved = if (gameDir.exists()) {
			gameDir
		} else {
			getLibsBaseDir().listFiles()?.firstOrNull {
				it.isDirectory && it.name.equals(gamedir, ignoreCase = true)
			}
		}

		if (resolved == null) {
			Log.i(TAG, "Game libs dir does not exist for $gamedir")
			return
		}

		Log.i(TAG, "Existing game libs under ${resolved.absolutePath}:")
		resolved.walkTopDown().filter { it.isFile }.forEach {
			Log.i(TAG, "  ${it.absolutePath} (${it.length()} bytes)")
		}
		val storedSha = prefs.getString("${gamedir}_sha256", null)
		val downloadTime = prefs.getLong("${gamedir}_download_time", 0L)
		Log.i(TAG, "Archive SHA-256: $storedSha")
		if (downloadTime > 0L)
			Log.i(TAG, "Downloaded at: ${java.util.Date(downloadTime)}")
	}

	private fun computeSHA256(file: File): String {
		val digest = MessageDigest.getInstance("SHA-256")
		file.inputStream().use { input ->
			val buffer = ByteArray(65536)
			while (true) {
				val read = input.read(buffer)
				if (read < 0)
					break
				digest.update(buffer, 0, read)
			}
		}
		return digest.digest().joinToString("") { "%02x".format(it) }
	}

	companion object {
		private const val TAG = "GameLibDownloader"
		private const val PROGRESS_INTERVAL_MS = 100L
		
		private const val FWGS_BASE_URL = "https://github.com/FWGS/hlsdk-mega-build/releases/download/continuous"
		private const val FWGS_MANIFEST_URL = "$FWGS_BASE_URL/manifest.json"

		private const val YALNIE_BASE_URL = "https://github.com/yalnie/hlsdk-other-builds/releases/download/continuous"
		private const val YALNIE_MANIFEST_URL = "$YALNIE_BASE_URL/manifest.json"

		private const val MANIFEST_VERSION = 1
	}
}
