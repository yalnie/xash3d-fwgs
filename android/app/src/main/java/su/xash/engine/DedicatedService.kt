package su.xash.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.AssetManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import su.xash.engine.ui.settings.AppIconManager
import su.xash.engine.util.CrashReports
import kotlin.system.exitProcess

class DedicatedService : Service() {

	private val mainHandler = Handler(Looper.getMainLooper())
	private val clients = mutableListOf<Messenger>()
	private val logBuffer = StringBuilder()

	private var isServerRunning = false
	private var hasStartedEngine = false
	private var stopRequested = false
	private var engineThread: Thread? = null

	private var gameTitle: String = "XashDS"
	private var gameDir: String = "valve"
	private var callingPackageName: String? = null
	private var lastStartIntent: Intent? = null

	private val incomingMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
		override fun handleMessage(msg: Message) {
			when (msg.what) {
				MSG_REGISTER_CLIENT -> {
					val client = msg.replyTo ?: return
					if (!clients.contains(client)) {
						clients.add(client)
					}
					sendStateToClient(client, includeLogs = true)
				}
				MSG_UNREGISTER_CLIENT -> {
					val client = msg.replyTo ?: return
					clients.remove(client)
				}
				MSG_SEND_COMMAND -> {
					val cmd = msg.data?.getString(KEY_COMMAND) ?: return
					handleCommand(cmd)
				}
				MSG_CLEAR_LOGS -> {
					logBuffer.clear()
					broadcastState(includeLogs = true)
				}
				MSG_STOP_SERVER -> {
					requestStopServer()
				}
			}
		}
	})

	override fun onCreate() {
		super.onCreate()
		instance = this
		createNotificationChannel()
	}

	override fun onBind(intent: Intent?): IBinder? {
		return incomingMessenger.binder
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		when (intent?.action) {
			ACTION_STOP -> {
				requestStopServer()
				return START_NOT_STICKY
			}
			ACTION_START -> {
				lastStartIntent = Intent(intent)
				startServerIfNeeded(intent)
			}
			else -> {
				if (intent != null && intent.hasExtra("gamedir")) {
					lastStartIntent = Intent(intent)
					startServerIfNeeded(intent)
				}
			}
		}
		return START_NOT_STICKY
	}

	private fun startServerIfNeeded(intent: Intent) {
		val requestedTitle = intent.getStringExtra("title")
		val requestedGameDir = intent.getStringExtra("gamedir") ?: "valve"
		val argvString = intent.getStringExtra("argv") ?: "-console -log"

		// Extract -game <mod> if present for display
		val modMatch = Regex("""-game\s+(\S+)""").find(argvString)
		val displayMod = modMatch?.groupValues?.getOrNull(1) ?: requestedGameDir

		gameDir = displayMod
		gameTitle = requestedTitle ?: displayMod

		startForegroundCompat(buildNotification(getString(R.string.dedicated_status_running, gameDir)))

		if (isServerRunning) {
			broadcastState(includeLogs = false)
			return
		}

		if (hasStartedEngine) {
			appendLog("^3Restarting dedicated server process...^7\n")
		}

		callingPackageName = intent.getStringExtra("package")
		val gameLibDir = intent.getStringExtra("gamelibdir")
		val roDir = intent.getStringExtra("rodir")
		val pakFile = intent.getStringExtra("pakfile")
		val baseDir = intent.getStringExtra("basedir")
			?: getExternalFilesDir(null)?.absolutePath
			?: filesDir.absolutePath
		val envArray = intent.getStringArrayExtra("env")

		CrashReports.writeSystemInfo(this)
		CrashReports.writeIntentInfo(
			this,
			buildString {
				append("DedicatedService\n")
				append("gamedir=").append(requestedGameDir).append('\n')
				append("argv=").append(argvString).append('\n')
				append("basedir=").append(baseDir).append('\n')
				if (gameLibDir != null) append("gamelibdir=").append(gameLibDir).append('\n')
				if (callingPackageName != null) append("package=").append(callingPackageName).append('\n')
			}
		)

		try {
			System.loadLibrary("SDL2")
			System.loadLibrary("xash")
		} catch (t: Throwable) {
			Log.e(TAG, "Failed to load native libraries", t)
			appendLog("^1Failed to load native libraries: ${t.message}^7\n")
			stopForegroundCompat()
			stopSelf()
			return
		}

		nativeSetenv("XASH3D_CRASH_DIR", CrashReports.pendingDir(this).absolutePath)
		nativeSetenv("XASH3D_GAME", requestedGameDir)
		if (gameLibDir != null) {
			nativeSetenv("XASH3D_GAMELIBDIR", gameLibDir)
		}
		if (roDir != null) {
			nativeSetenv("XASH3D_RODIR", roDir)
		}
		if (pakFile != null) {
			nativeSetenv("XASH3D_EXTRAS_PAK2", pakFile)
		}
		nativeSetenv("XASH3D_BASEDIR", baseDir)

		if (envArray != null) {
			var i = 0
			while (i + 1 < envArray.size) {
				nativeSetenv(envArray[i], envArray[i + 1])
				i += 2
			}
		}

		val parsedArgs = argvString.trim()
			.split(Regex("\\s+"))
			.filter { it.isNotEmpty() }

		val finalArgs = mutableListOf("xash")
		if (!parsedArgs.contains("-dedicated")) {
			finalArgs.add("-dedicated")
		}
		finalArgs.addAll(parsedArgs)

		isServerRunning = true
		hasStartedEngine = true
		stopRequested = false

		appendLog("^2Starting $gameTitle dedicated server (${finalArgs.joinToString(" ")})...^7\n")
		broadcastState(includeLogs = false)

		engineThread = Thread({
			val exitCode = try {
				nativeMain(finalArgs.toTypedArray())
			} catch (t: Throwable) {
				Log.e(TAG, "Dedicated server crashed", t)
				-1
			}

			mainHandler.post {
				isServerRunning = false
				appendLog("\n^3[Server stopped (exit code $exitCode)]^7\n")
				broadcastState(includeLogs = false)
				stopForegroundCompat()
				stopSelf()
			}
		}, "XashDedicatedThread").apply {
			isDaemon = false
			start()
		}
	}

	private fun handleCommand(rawCmd: String) {
		val trimmed = rawCmd.trim()
		if (trimmed.isEmpty()) return

		appendLog("^5> $trimmed^7\n")
		if (!isServerRunning) {
			appendLog("^1Server is not running.^7\n")
			return
		}

		nativeSendCommand("$trimmed\n")

		if (trimmed.equals("quit", ignoreCase = true) || trimmed.equals("exit", ignoreCase = true)) {
			stopRequested = true
		}
	}

	private fun requestStopServer() {
		if (!isServerRunning) {
			stopForegroundCompat()
			stopSelf()
			return
		}

		if (stopRequested) return
		stopRequested = true

		appendLog("^3Stopping server...^7\n")
		try {
			nativeSendCommand("quit\n")
		} catch (t: Throwable) {
			Log.w(TAG, "Error sending quit command", t)
		}

		// Fallback timeout if the engine thread doesn't exit promptly
		mainHandler.postDelayed({
			if (isServerRunning) {
				isServerRunning = false
				appendLog("^3[Server force stopped]^7\n")
				broadcastState(includeLogs = false)
				stopForegroundCompat()
				stopSelf()
			}
		}, 2000L)
	}

	// Called from native code via JNI (engine/platform/android/android.c)
	@Keep
	@Suppress("unused")
	fun onConsoleOutput(bytes: ByteArray) {
		val text = String(bytes, Charsets.UTF_8)
		mainHandler.post {
			appendLog(text)
		}
	}

	@Keep
	@Suppress("unused")
	fun onConsoleOutput(text: String) {
		mainHandler.post {
			appendLog(text)
		}
	}

	// Called from native code via JNI (filesystem/android.c)
	@Keep
	@Suppress("unused")
	fun getCallingPackage(): String? {
		return callingPackageName
	}

	// Called from native code via JNI (filesystem/android.c)
	@Keep
	@Suppress("unused")
	fun getAssets(isEngine: Boolean): AssetManager? {
		if (isEngine) {
			return assets
		}
		val pkg = callingPackageName ?: return null
		return try {
			packageManager.getResourcesForApplication(pkg).assets
		} catch (e: Exception) {
			e.printStackTrace()
			null
		}
	}

	// Called from native code via JNI (filesystem/android.c)
	@Keep
	@Suppress("unused")
	fun getAssetsList(isEngine: Boolean, path: String): Array<String> {
		val am = getAssets(isEngine) ?: return emptyArray()
		return try {
			am.list(path) ?: emptyArray()
		} catch (e: Exception) {
			e.printStackTrace()
			emptyArray()
		}
	}

	private fun appendLog(text: String) {
		logBuffer.append(text)
		if (logBuffer.length > MAX_LOG_CHARS) {
			val excess = logBuffer.length - MAX_LOG_CHARS
			val newlineIdx = logBuffer.indexOf("\n", excess)
			val cutIndex = if (newlineIdx in 0..(excess + 512)) newlineIdx + 1 else excess
			logBuffer.delete(0, cutIndex)
		}

		val deadClients = mutableListOf<Messenger>()
		for (client in clients) {
			try {
				val msg = Message.obtain(null, MSG_LOG_APPEND).apply {
					data = Bundle().apply {
						putString(KEY_LOG_LINE, text)
					}
				}
				client.send(msg)
			} catch (e: RemoteException) {
				deadClients.add(client)
			}
		}
		clients.removeAll(deadClients)
	}

	private fun broadcastState(includeLogs: Boolean) {
		val deadClients = mutableListOf<Messenger>()
		for (client in clients) {
			if (!sendStateToClient(client, includeLogs)) {
				deadClients.add(client)
			}
		}
		clients.removeAll(deadClients)
	}

	private fun sendStateToClient(client: Messenger, includeLogs: Boolean): Boolean {
		return try {
			val msg = Message.obtain(null, MSG_STATE).apply {
				data = Bundle().apply {
					putBoolean(KEY_RUNNING, isServerRunning)
					putString(KEY_TITLE, gameTitle)
					putString(KEY_GAMEDIR, gameDir)
					if (includeLogs) {
						putString(KEY_LOGS, logBuffer.toString())
					}
				}
			}
			client.send(msg)
			true
		} catch (e: RemoteException) {
			false
		}
	}

	private fun createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = NotificationChannel(
				CHANNEL_ID,
				getString(R.string.dedicated_server_title),
				NotificationManager.IMPORTANCE_LOW
			).apply {
				setShowBadge(false)
			}
			val nm = getSystemService(NotificationManager::class.java)
			nm?.createNotificationChannel(channel)
		}
	}

	private fun buildNotification(statusText: String): Notification {
		val openIntent = Intent(this, DedicatedActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
			lastStartIntent?.extras?.let { putExtras(it) }
			putExtra(EXTRA_ATTACH_ONLY, true)
		}
		val openPendingIntent = PendingIntent.getActivity(
			this,
			0,
			openIntent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)

		val stopIntent = Intent(this, DedicatedService::class.java).apply {
			action = ACTION_STOP
		}
		val stopPendingIntent = PendingIntent.getService(
			this,
			1,
			stopIntent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)

		val iconRes = AppIconManager.getNotificationIconRes(this)

		return NotificationCompat.Builder(this, CHANNEL_ID)
			.setContentTitle("$gameTitle — ${getString(R.string.dedicated_server_title)}")
			.setContentText(statusText)
			.setSmallIcon(iconRes)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setContentIntent(openPendingIntent)
			.addAction(
				R.drawable.ic_baseline_stop_24px,
				getString(R.string.dedicated_stop),
				stopPendingIntent
			)
			.build()
	}

	private fun startForegroundCompat(notification: Notification) {
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				startForeground(
					NOTIFICATION_ID,
					notification,
					ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
				)
			} else {
				startForeground(NOTIFICATION_ID, notification)
			}
		} catch (e: Exception) {
			Log.e(TAG, "Failed to start foreground service", e)
		}
	}

	private fun stopForegroundCompat() {
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				stopForeground(STOP_FOREGROUND_REMOVE)
			} else {
				@Suppress("DEPRECATION")
				stopForeground(true)
			}
		} catch (e: Exception) {
			Log.w(TAG, "Failed to stop foreground", e)
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		instance = null

		if (hasStartedEngine) {
			// Reset native C static/global state in the :dedicated process
			mainHandler.postDelayed({
				exitProcess(0)
			}, 150L)
		}
	}

	@Keep
	private external fun nativeMain(argv: Array<String>): Int

	companion object {
		private const val TAG = "DedicatedService"
		private const val CHANNEL_ID = "xash_dedicated_channel"
		private const val NOTIFICATION_ID = 1002
		private const val MAX_LOG_CHARS = 131072

		const val ACTION_START = "su.xash.engine.DedicatedService.START"
		const val ACTION_STOP = "su.xash.engine.DedicatedService.STOP"
		const val EXTRA_ATTACH_ONLY = "attach_only"

		const val MSG_REGISTER_CLIENT = 1
		const val MSG_UNREGISTER_CLIENT = 2
		const val MSG_SEND_COMMAND = 3
		const val MSG_CLEAR_LOGS = 4
		const val MSG_STOP_SERVER = 5

		const val MSG_STATE = 10
		const val MSG_LOG_APPEND = 11

		const val KEY_RUNNING = "running"
		const val KEY_TITLE = "title"
		const val KEY_GAMEDIR = "gamedir"
		const val KEY_LOGS = "logs"
		const val KEY_LOG_LINE = "log_line"
		const val KEY_COMMAND = "command"

		@Volatile
		private var instance: DedicatedService? = null

		// Called from native code via JNI (filesystem/android.c)
		@Keep
		@JvmStatic
		@Suppress("unused")
		fun getContext(): Context? = instance

		@Keep
		@JvmStatic
		external fun nativeSendCommand(cmd: String)

		@Keep
		@JvmStatic
		external fun nativeSetenv(name: String, value: String)
	}
}
