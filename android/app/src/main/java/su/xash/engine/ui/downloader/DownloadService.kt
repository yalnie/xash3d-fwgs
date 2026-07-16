package su.xash.engine.ui.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import su.xash.engine.R
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

class DownloadService : Service() {

	private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
	private var downloadJob: Job? = null
	
	private val isPaused = AtomicBoolean(false)
	private var activeGameId: String? = null
	private var activeGameName: String = ""
	private var selectedStrategy: String = "REPLACE"

	companion object {
		const val CHANNEL_ID = "download_channel"
		const val NOTIFICATION_ID = 101

		const val ACTION_START = "su.xash.engine.action.START"
		const val ACTION_PAUSE_RESUME = "su.xash.engine.action.PAUSE_RESUME"
		const val ACTION_STOP = "su.xash.engine.action.STOP"
		const val ACTION_PROGRESS = "su.xash.engine.action.PROGRESS"

		const val EXTRA_URL = "extra_url"
		const val EXTRA_GAME_ID = "extra_game_id"
		const val EXTRA_GAME_NAME = "extra_game_name"
		const val EXTRA_SIZE = "extra_size"
		const val EXTRA_HD_URL = "extra_hd_url"
		const val EXTRA_HD_SIZE = "extra_hd_size"
		const val EXTRA_STRATEGY = "extra_strategy"
		const val EXTRA_IS_HD = "extra_is_hd"

		const val STATUS_DOWNLOADING = "STATUS_DOWNLOADING"
		const val STATUS_UNZIPPING = "STATUS_UNZIPPING"
		const val STATUS_DELETING = "STATUS_DELETING"
		const val STATUS_SUCCESS = "STATUS_SUCCESS"
		const val STATUS_FAILED = "STATUS_FAILED"
		
		const val EXTRA_STATUS = "extra_status"
		const val EXTRA_PROGRESS = "extra_progress"
		const val EXTRA_STAGE_LABEL = "extra_stage_label"
		const val EXTRA_CURRENT_MB = "extra_current_mb"
		const val EXTRA_TOTAL_MB = "extra_total_mb"

		var isServiceRunning = false
			private set
		var lastKnownStatus: String? = null
			private set
		var lastKnownProgress = 0
			private set
		var lastKnownGameName = ""
			private set
	}

	override fun onCreate() {
		super.onCreate()
		isServiceRunning = true
		createNotificationChannel()
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		when (intent?.action) {
			ACTION_START -> {
				val url = intent.getStringExtra(EXTRA_URL) ?: ""
				val hdUrl = intent.getStringExtra(EXTRA_HD_URL) ?: ""
				activeGameId = intent.getStringExtra(EXTRA_GAME_ID)
				activeGameName = intent.getStringExtra(EXTRA_GAME_NAME) ?: ""
				val size = intent.getLongExtra(EXTRA_SIZE, 0L)
				val hdSize = intent.getLongExtra(EXTRA_HD_SIZE, 0L)
				selectedStrategy = intent.getStringExtra(EXTRA_STRATEGY) ?: "REPLACE"
				val isHdSelected = intent.getBooleanExtra(EXTRA_IS_HD, false)

				lastKnownGameName = activeGameName
				startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.downloading), 0))
				
				if (downloadJob == null) {
					isPaused.set(false)
					startDownloadSequence(url, size, hdUrl, hdSize, isHdSelected)
				}
			}
			ACTION_PAUSE_RESUME -> {
				if (lastKnownStatus == STATUS_DOWNLOADING) {
					val paused = !isPaused.get()
					isPaused.set(paused)
					val labelRes = if (paused) R.string.source_speed_slow else R.string.downloading
					sendProgressBroadcast(STATUS_DOWNLOADING, lastKnownProgress, getString(labelRes), "", "")
				}
			}
			ACTION_STOP -> {
				cancelAndCleanup()
			}
		}
		return START_NOT_STICKY
	}

	private fun startDownloadSequence(url: String, size: Long, hdUrl: String, hdSize: Long, isHd: Boolean) {
		downloadJob = serviceScope.launch {
			val outputDir = File(Environment.getExternalStorageDirectory(), "xash")
			if (!outputDir.exists()) outputDir.mkdirs()

			if (selectedStrategy == "CLEAN_INSTALL" && activeGameId != null) {
				updateState(STATUS_DELETING, 0, getString(R.string.downloader_deleting_format, activeGameName))
				val targetGameDir = File(outputDir, activeGameId!!)
				withContext(Dispatchers.IO) { targetGameDir.deleteRecursively() }
			}

			var overallSuccess: Boolean
			val baseLabel = getString(R.string.download_stage_base)

			overallSuccess = handleStage(url, activeGameId!!, outputDir, baseLabel, size)

			if (overallSuccess && isHd && hdUrl.isNotEmpty()) {
				val hdLabel = getString(R.string.download_stage_hd)
				overallSuccess = handleStage(hdUrl, activeGameId!!, outputDir, hdLabel, hdSize)
			}

			if (overallSuccess) {
				updateState(STATUS_SUCCESS, 100, getString(R.string.download_success, activeGameName))
			} else {
				if (downloadJob?.isCancelled != true) {
					updateState(STATUS_FAILED, 0, getString(R.string.download_failed))
				}
			}
			stopSelf()
		}
	}

	private suspend fun handleStage(urlString: String, targetFolder: String, outputDir: File, stageLabel: String, definedSize: Long): Boolean {
		val branchName = if (urlString.contains("_hd")) "${targetFolder}_hd" else targetFolder
		val tempZipFile = File(cacheDir, "${branchName}_temp.zip")

		withContext(Dispatchers.IO) {
			if (tempZipFile.exists()) tempZipFile.delete()
		}

		updateState(STATUS_DOWNLOADING, 0, stageLabel)

		val downloadSuccess = withContext(Dispatchers.IO) {
			try {
				val url = URL(urlString)
				val connection = url.openConnection() as HttpURLConnection
				connection.connect()
				if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext false

				val input = connection.inputStream
				val output = FileOutputStream(tempZipFile)
				val data = ByteArray(4096)
				var total: Long = 0
				var count: Int
				val totalMbText = if (definedSize > 0) String.format("%.2f", definedSize.toDouble() / (1024 * 1024)) else "?.??"

				while (input.read(data).also { count = it } != -1) {
					if (downloadJob?.isCancelled == true) {
						output.close(); input.close(); return@withContext false
					}
					while (isPaused.get()) {
						if (downloadJob?.isCancelled == true) {
							output.close(); input.close(); return@withContext false
						}
						delay(500)
					}

					total += count
					val currentMbText = String.format("%.2f", total.toDouble() / (1024 * 1024))
					val progressPercent = if (definedSize > 0) (total * 100 / definedSize).toInt().coerceAtMost(100) else 0

					if (downloadJob?.isCancelled != true) {
						updateState(STATUS_DOWNLOADING, progressPercent, stageLabel, currentMbText, totalMbText)
					}
					output.write(data, 0, count)
				}
				output.flush(); output.close(); input.close()
				true
			} catch (e: Exception) {
				e.printStackTrace()
				false
			}
		}

		if (!downloadSuccess || downloadJob?.isCancelled == true) {
			withContext(Dispatchers.IO) { tempZipFile.delete() }
			return false
		}

		updateState(STATUS_UNZIPPING, 0, stageLabel)
		val extractSuccess = withContext(Dispatchers.IO) {
			try {
				val prefix1 = "goldsrc-valve-$branchName/"
				val prefix2 = "$branchName/"
				ZipInputStream(tempZipFile.inputStream().buffered()).use { zis ->
					var entry = zis.nextEntry
					while (entry != null) {
						if (downloadJob?.isCancelled == true) return@withContext false
						val strippedPath = when {
							entry.name.startsWith(prefix1) -> entry.name.substring(prefix1.length)
							entry.name.startsWith(prefix2) -> entry.name.substring(prefix2.length)
							else -> entry.name
						}
						if (strippedPath.isNotEmpty()) {
							val targetGameDir = File(outputDir, targetFolder)
							val destFile = File(targetGameDir, strippedPath)
							if (!destFile.canonicalPath.startsWith(targetGameDir.canonicalPath + File.separator)) return@withContext false
							
							if (entry.isDirectory) {
								destFile.mkdirs()
							} else {
								if (selectedStrategy == "SKIP_EXISTING" && destFile.exists()) {
									zis.closeEntry(); entry = zis.nextEntry; continue
								}
								destFile.parentFile?.mkdirs()
								FileOutputStream(destFile).use { fos -> zis.copyTo(fos) }
							}
						}
						zis.closeEntry()
						entry = zis.nextEntry
					}
				}
				true
			} catch (e: Exception) {
				e.printStackTrace()
				false
			}
		}

		withContext(Dispatchers.IO) { tempZipFile.delete() }
		return extractSuccess
	}

	private fun cancelAndCleanup() {
		downloadJob?.cancel()
		serviceScope.launch(Dispatchers.IO) {
			cacheDir?.listFiles()?.forEach { file -> 
				if (file.name.endsWith("_temp.zip")) file.delete() 
			}
			if (selectedStrategy != "SKIP_EXISTING" && activeGameId != null) {
				val outputDir = File(Environment.getExternalStorageDirectory(), "xash")
				File(outputDir, activeGameId!!).deleteRecursively()
			}
			withContext(Dispatchers.Main) {
				updateState(STATUS_FAILED, 0, getString(R.string.operation_cancelled))
				stopSelf()
			}
		}
	}

	private fun updateState(status: String, progress: Int, stageLabel: String, currentMb: String = "", totalMb: String = "") {
		lastKnownStatus = status
		lastKnownProgress = progress
		
		val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
		val message = when(status) {
			STATUS_DOWNLOADING -> getString(R.string.downloader_status_format, stageLabel, currentMb, totalMb, progress)
			STATUS_UNZIPPING -> getString(R.string.downloader_extracting_format, stageLabel)
			STATUS_DELETING -> stageLabel
			else -> stageLabel
		}
		notificationManager.notify(NOTIFICATION_ID, buildNotification(message, progress))
		sendProgressBroadcast(status, progress, stageLabel, currentMb, totalMb)
	}

	private fun sendProgressBroadcast(status: String, progress: Int, stageLabel: String, currentMb: String, totalMb: String) {
		val intent = Intent(ACTION_PROGRESS).apply {
			putExtra(EXTRA_STATUS, status)
			putExtra(EXTRA_PROGRESS, progress)
			putExtra(EXTRA_STAGE_LABEL, stageLabel)
			putExtra(EXTRA_CURRENT_MB, currentMb)
			putExtra(EXTRA_TOTAL_MB, totalMb)
			putExtra(EXTRA_GAME_NAME, activeGameName)
		}
		sendBroadcast(intent)
	}

	private fun buildNotification(content: String, progress: Int): Notification {
		val isIndeterminate = lastKnownStatus == STATUS_UNZIPPING || lastKnownStatus == STATUS_DELETING
		return NotificationCompat.Builder(this, CHANNEL_ID)
			.setContentTitle(activeGameName.ifEmpty { getString(R.string.app_name) })
			.setContentText(content)
			.setSmallIcon(R.drawable.ic_baseline_cloud_24px)
			.setProgress(100, progress, isIndeterminate)
			.setOngoing(true)
			.build()
	}

	private fun createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channelName = getString(R.string.app_downloader)
			val channel = NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW)
			val manager = getSystemService(NotificationManager::class.java)
			manager?.createNotificationChannel(channel)
		}
	}

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onDestroy() {
		super.onDestroy()
		isServiceRunning = false
		downloadJob?.cancel()
	}
}
