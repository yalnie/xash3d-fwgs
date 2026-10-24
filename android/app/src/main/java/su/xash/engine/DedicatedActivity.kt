package su.xash.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
import su.xash.engine.databinding.ActivityDedicatedBinding

class DedicatedActivity : AppCompatActivity() {

	private lateinit var binding: ActivityDedicatedBinding

	private var serviceMessenger: Messenger? = null
	private var isBound = false
	private var isServerRunning = false

	private var gameTitle: String = "XashDS"
	private var gameDir: String = "valve"
	private var launchExtras: Bundle? = null

	private val clientMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
		override fun handleMessage(msg: Message) {
			when (msg.what) {
				DedicatedService.MSG_STATE -> {
					val data = msg.data ?: return
					isServerRunning = data.getBoolean(DedicatedService.KEY_RUNNING, false)
					data.getString(DedicatedService.KEY_TITLE)?.let { gameTitle = it }
					data.getString(DedicatedService.KEY_GAMEDIR)?.let { gameDir = it }
					if (data.containsKey(DedicatedService.KEY_LOGS)) {
						val logs = data.getString(DedicatedService.KEY_LOGS) ?: ""
						binding.consoleTextView.text = parseColoredText(logs)
						scrollToBottom()
					}
					updateToolbarStatus()
				}
				DedicatedService.MSG_LOG_APPEND -> {
					val line = msg.data?.getString(DedicatedService.KEY_LOG_LINE) ?: return
					appendConsoleText(line)
				}
			}
		}
	})

	private val serviceConnection = object : ServiceConnection {
		override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
			val messenger = Messenger(service)
			serviceMessenger = messenger
			isBound = true

			try {
				val registerMsg = Message.obtain(null, DedicatedService.MSG_REGISTER_CLIENT).apply {
					replyTo = clientMessenger
				}
				messenger.send(registerMsg)
			} catch (e: RemoteException) {
				e.printStackTrace()
			}
		}

		override fun onServiceDisconnected(name: ComponentName?) {
			serviceMessenger = null
			isBound = false
			isServerRunning = false
			updateToolbarStatus()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		applyAppTheme()
		super.onCreate(savedInstanceState)

		binding = ActivityDedicatedBinding.inflate(layoutInflater)
		setContentView(binding.root)

		setSupportActionBar(binding.toolbar)
		binding.toolbar.setNavigationOnClickListener {
			finish()
		}

		setupQuickCommands()
		setupCommandInput()

		handleLaunchIntent(intent, isInitialCreate = savedInstanceState == null)
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		handleLaunchIntent(intent, isInitialCreate = false)
	}

	private fun handleLaunchIntent(intent: Intent?, isInitialCreate: Boolean) {
		val extras = intent?.extras
		if (extras != null && extras.containsKey("gamedir")) {
			launchExtras = Bundle(extras).apply {
				remove(DedicatedService.EXTRA_ATTACH_ONLY)
			}
			extras.getString("title")?.let { gameTitle = it }
			extras.getString("gamedir")?.let { gameDir = it }
		}

		updateToolbarStatus()

		val attachOnly = intent?.getBooleanExtra(DedicatedService.EXTRA_ATTACH_ONLY, false) == true
		if (isInitialCreate && !attachOnly && launchExtras != null) {
			startServerService()
		} else {
			bindToService()
		}
	}

	private fun startServerService() {
		val extras = launchExtras ?: return
		val serviceIntent = Intent(this, DedicatedService::class.java).apply {
			action = DedicatedService.ACTION_START
			putExtras(extras)
		}
		ContextCompat.startForegroundService(this, serviceIntent)
		bindToService()
	}

	private fun bindToService() {
		if (!isBound) {
			val bindIntent = Intent(this, DedicatedService::class.java)
			bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
		}
	}

	private fun unbindFromService() {
		if (isBound) {
			serviceMessenger?.let { messenger ->
				try {
					val msg = Message.obtain(null, DedicatedService.MSG_UNREGISTER_CLIENT).apply {
						replyTo = clientMessenger
					}
					messenger.send(msg)
				} catch (_: RemoteException) {
				}
			}
			try {
				unbindService(serviceConnection)
			} catch (_: IllegalArgumentException) {
			}
			serviceMessenger = null
			isBound = false
		}
	}

	private fun setupQuickCommands() {
		val quickCommands = listOf(
			"status" to true,
			"maps *" to true,
			"map " to false,
			"users" to true,
			"heartbeat" to true,
			"restart" to true
		)

		for ((cmd, immediate) in quickCommands) {
			val chip = Chip(this).apply {
				text = cmd.trim()
				isCheckable = false
				setOnClickListener {
					if (immediate) {
						sendCommand(cmd)
					} else {
						binding.commandInput.setText(cmd)
						binding.commandInput.setSelection(cmd.length)
						binding.commandInput.requestFocus()
					}
				}
			}
			binding.quickCommandsGroup.addView(chip)
		}
	}

	private fun setupCommandInput() {
		binding.sendButton.setOnClickListener {
			submitCurrentInput()
		}

		binding.commandInput.setOnEditorActionListener { _, actionId, event ->
			val isEnter = event != null &&
				event.keyCode == KeyEvent.KEYCODE_ENTER &&
				event.action == KeyEvent.ACTION_DOWN
			if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || isEnter) {
				submitCurrentInput()
				true
			} else {
				false
			}
		}
	}

	private fun submitCurrentInput() {
		val cmd = binding.commandInput.text?.toString()?.trim().orEmpty()
		if (cmd.isEmpty()) return
		binding.commandInput.text?.clear()
		sendCommand(cmd)
	}

	private fun sendCommand(cmd: String) {
		val messenger = serviceMessenger
		if (messenger == null) {
			appendConsoleText("^1Server service is not connected.^7\n")
			return
		}
		try {
			val msg = Message.obtain(null, DedicatedService.MSG_SEND_COMMAND).apply {
				data = Bundle().apply {
					putString(DedicatedService.KEY_COMMAND, cmd)
				}
			}
			messenger.send(msg)
		} catch (e: RemoteException) {
			appendConsoleText("^1Failed to send command: ${e.message}^7\n")
		}
	}

	private fun appendConsoleText(rawText: String) {
		val colored = parseColoredText(rawText)
		val editable = binding.consoleTextView.editableText
		if (editable != null) {
			editable.append(colored)
			if (editable.length > MAX_UI_LOG_CHARS) {
				editable.delete(0, editable.length - MAX_UI_LOG_CHARS)
			}
		} else {
			val builder = SpannableStringBuilder(binding.consoleTextView.text)
			builder.append(colored)
			if (builder.length > MAX_UI_LOG_CHARS) {
				builder.delete(0, builder.length - MAX_UI_LOG_CHARS)
			}
			binding.consoleTextView.setText(builder, android.widget.TextView.BufferType.EDITABLE)
		}
		scrollToBottom()
	}

	private fun scrollToBottom() {
		binding.consoleScrollView.post {
			binding.consoleScrollView.fullScroll(View.FOCUS_DOWN)
		}
	}

	private fun updateToolbarStatus() {
		supportActionBar?.title = "$gameTitle — ${getString(R.string.dedicated_server_title)}"
		supportActionBar?.subtitle = if (isServerRunning) {
			getString(R.string.dedicated_status_running, gameDir)
		} else {
			getString(R.string.dedicated_status_stopped)
		}
		invalidateOptionsMenu()
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.menu_dedicated, menu)
		return true
	}

	override fun onPrepareOptionsMenu(menu: Menu): Boolean {
		val toggleItem = menu.findItem(R.id.action_toggle_server)
		if (toggleItem != null) {
			if (isServerRunning) {
				toggleItem.setTitle(R.string.dedicated_stop)
				toggleItem.setIcon(R.drawable.ic_baseline_stop_24px)
				toggleItem.isVisible = true
			} else {
				toggleItem.setTitle(R.string.dedicated_start)
				toggleItem.setIcon(R.drawable.ic_baseline_play_arrow_24)
				toggleItem.isVisible = launchExtras != null
			}
		}
		return super.onPrepareOptionsMenu(menu)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.action_clear -> {
				binding.consoleTextView.text = ""
				serviceMessenger?.let { messenger ->
					try {
						messenger.send(Message.obtain(null, DedicatedService.MSG_CLEAR_LOGS))
					} catch (_: RemoteException) {
					}
				}
				true
			}
			R.id.action_toggle_server -> {
				if (isServerRunning) {
					serviceMessenger?.let { messenger ->
						try {
							messenger.send(Message.obtain(null, DedicatedService.MSG_STOP_SERVER))
						} catch (_: RemoteException) {
						}
					}
					val stopIntent = Intent(this, DedicatedService::class.java).apply {
						action = DedicatedService.ACTION_STOP
					}
					startService(stopIntent)
				} else {
					unbindFromService()
					startServerService()
				}
				true
			}
			else -> super.onOptionsItemSelected(item)
		}
	}

	override fun onStart() {
		super.onStart()
		bindToService()
	}

	override fun onStop() {
		super.onStop()
		if (!isServerRunning) {
			unbindFromService()
		}
	}

	override fun onDestroy() {
		unbindFromService()
		super.onDestroy()
	}

	private fun applyAppTheme() {
		val prefs = getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
		val themePref = prefs.getString("app_theme_preference", null)
		val defaultOption = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "dynamic_sys" else "legacy"
		val selectedOption = themePref ?: defaultOption

		when (selectedOption) {
			"legacy" -> {
				AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
				setTheme(R.style.Theme.App.Legacy)
			}
			"fixed_sys" -> {
				AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
				setTheme(R.style.Theme.App.Fixed)
			}
			"fixed_light" -> {
				AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
				setTheme(R.style.Theme.App.Fixed)
			}
			"fixed_dark" -> {
				AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
				setTheme(R.style.Theme.App.Fixed)
			}
			"dynamic_sys" -> {
				AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
				DynamicColors.applyToActivityIfAvailable(this)
			}
			"dynamic_light" -> {
				AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
				DynamicColors.applyToActivityIfAvailable(this)
			}
			"dynamic_dark" -> {
				AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
				DynamicColors.applyToActivityIfAvailable(this)
			}
		}
	}

	private fun parseColoredText(input: String): Spannable {
		val builder = SpannableStringBuilder()
		var currentColor: Int? = null
		var spanStart = 0
		var i = 0

		while (i < input.length) {
			if (input[i] == '^' && i + 1 < input.length && input[i + 1] in '0'..'7') {
				if (currentColor != null && builder.length > spanStart) {
					builder.setSpan(
						ForegroundColorSpan(currentColor),
						spanStart,
						builder.length,
						Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
					)
				}
				currentColor = quakeColor(input[i + 1] - '0')
				spanStart = builder.length
				i += 2
			} else {
				builder.append(input[i])
				i++
			}
		}

		if (currentColor != null && builder.length > spanStart) {
			builder.setSpan(
				ForegroundColorSpan(currentColor),
				spanStart,
				builder.length,
				Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
			)
		}

		return builder
	}

	private fun quakeColor(index: Int): Int? {
		return when (index) {
			0 -> Color.rgb(128, 128, 128) // Black/Grey
			1 -> Color.rgb(255, 85, 85)   // Red
			2 -> Color.rgb(85, 255, 85)   // Green
			3 -> Color.rgb(255, 255, 85)  // Yellow
			4 -> Color.rgb(85, 170, 255)  // Blue
			5 -> Color.rgb(85, 255, 255)  // Cyan
			6 -> Color.rgb(255, 85, 255)  // Magenta
			else -> null                  // Default (White)
		}
	}

	companion object {
		private const val MAX_UI_LOG_CHARS = 131072
	}
}
