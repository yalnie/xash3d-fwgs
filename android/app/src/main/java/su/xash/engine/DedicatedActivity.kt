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
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import su.xash.engine.databinding.ActivityDedicatedBinding
import su.xash.engine.ui.extrakeys.ExtraKeyButton
import su.xash.engine.ui.extrakeys.ExtraKeysInfo
import su.xash.engine.ui.extrakeys.ExtraKeysView
import su.xash.engine.ui.extrakeys.SpecialButton

class DedicatedActivity : AppCompatActivity() {

	private lateinit var binding: ActivityDedicatedBinding

	private var serviceMessenger: Messenger? = null
	private var isBound = false
	private var isServerRunning = false

	private var gameTitle: String = "XashDS"
	private var gameDir: String = "valve"
	private var launchExtras: Bundle? = null

	private val commandHistory = mutableListOf<String>()
	private var historyIndex = 0
	private var pendingDraft = ""
	private var isHandlingCtrlTypedChar = false

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

		setupExtraKeys()
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

	private fun setupExtraKeys() {
		binding.extraKeysView.extraKeysViewClient = object : ExtraKeysView.IExtraKeysView {
			override fun onExtraKeyButtonClick(
				view: View,
				buttonInfo: ExtraKeyButton,
				button: MaterialButton
			) {
				handleExtraKeyPress(buttonInfo)
			}

			override fun performExtraKeyButtonHapticFeedback(
				view: View,
				buttonInfo: ExtraKeyButton,
				button: MaterialButton
			): Boolean {
				return false
			}
		}

		val keysMatrix = arrayOf(
			arrayOf("ESC", "/", "-", "HOME", "UP", "END", "PGUP"),
			arrayOf("TAB", "CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "PGDN")
		)
		val extraKeysInfo = ExtraKeysInfo(keysMatrix)
		binding.extraKeysView.reload(extraKeysInfo)
	}

	private fun handleExtraKeyPress(buttonInfo: ExtraKeyButton) {
		val ctrlDown = binding.extraKeysView.readSpecialButton(SpecialButton.CTRL, true) == true
		val altDown = binding.extraKeysView.readSpecialButton(SpecialButton.ALT, true) == true
		val editText = binding.commandInput
		editText.requestFocus()

		when (val key = buttonInfo.key) {
			"ESC" -> {
				editText.text?.clear()
				historyIndex = commandHistory.size
				pendingDraft = ""
			}
			"TAB" -> {
				autocompleteCommand()
			}
			"HOME" -> {
				editText.setSelection(0)
			}
			"END" -> {
				editText.setSelection(editText.text?.length ?: 0)
			}
			"UP" -> {
				navigateHistory(-1)
			}
			"DOWN" -> {
				navigateHistory(1)
			}
			"LEFT" -> {
				val text = editText.text?.toString().orEmpty()
				val cursor = editText.selectionStart.coerceAtLeast(0)
				if (cursor > 0) {
					val newPos = if (ctrlDown || altDown) {
						findPreviousWordBoundary(text, cursor)
					} else {
						cursor - 1
					}
					editText.setSelection(newPos)
				}
			}
			"RIGHT" -> {
				val text = editText.text?.toString().orEmpty()
				val cursor = editText.selectionEnd.coerceAtLeast(0)
				if (cursor < text.length) {
					val newPos = if (ctrlDown || altDown) {
						findNextWordBoundary(text, cursor)
					} else {
						cursor + 1
					}
					editText.setSelection(newPos)
				}
			}
			"PGUP" -> {
				val page = (binding.consoleScrollView.height * 0.8f).toInt().coerceAtLeast(100)
				binding.consoleScrollView.smoothScrollBy(0, -page)
			}
			"PGDN" -> {
				val page = (binding.consoleScrollView.height * 0.8f).toInt().coerceAtLeast(100)
				binding.consoleScrollView.smoothScrollBy(0, page)
			}
			else -> {
				val editable = editText.editableText ?: return
				val start = editText.selectionStart.coerceAtLeast(0)
				val end = editText.selectionEnd.coerceAtLeast(0)
				editable.replace(minOf(start, end), maxOf(start, end), key)
			}
		}
	}

	private fun navigateHistory(delta: Int) {
		if (commandHistory.isEmpty()) return
		val editText = binding.commandInput
		if (historyIndex == commandHistory.size) {
			pendingDraft = editText.text?.toString().orEmpty()
		}
		val newIndex = (historyIndex + delta).coerceIn(0, commandHistory.size)
		if (newIndex == historyIndex) return
		historyIndex = newIndex
		val nextText = if (historyIndex == commandHistory.size) {
			pendingDraft
		} else {
			commandHistory[historyIndex]
		}
		editText.setText(nextText)
		editText.setSelection(nextText.length)
	}

	private fun autocompleteCommand() {
		val editText = binding.commandInput
		val current = editText.text?.toString().orEmpty()
		val trimmed = current.trimStart()
		if (trimmed.isEmpty()) {
			appendConsoleText("^3Commands: ${SERVER_COMMANDS.joinToString(", ")}^7\n")
			return
		}
		if (trimmed.contains(' ')) return

		val matches = SERVER_COMMANDS.filter { it.startsWith(trimmed, ignoreCase = true) }
		when {
			matches.size == 1 -> {
				val completed = matches[0] + " "
				editText.setText(completed)
				editText.setSelection(completed.length)
			}
			matches.size > 1 -> {
				val commonPrefix = longestCommonPrefix(matches)
				if (commonPrefix.length > trimmed.length) {
					editText.setText(commonPrefix)
					editText.setSelection(commonPrefix.length)
				}
				appendConsoleText("^3Matches: ${matches.joinToString("  ")}^7\n")
			}
		}
	}

	private fun longestCommonPrefix(strings: List<String>): String {
		if (strings.isEmpty()) return ""
		var prefix = strings[0]
		for (i in 1 until strings.size) {
			while (!strings[i].startsWith(prefix, ignoreCase = true)) {
				prefix = prefix.substring(0, prefix.length - 1)
				if (prefix.isEmpty()) return ""
			}
		}
		return prefix
	}

	private fun findPreviousWordBoundary(text: String, cursor: Int): Int {
		var i = cursor - 1
		while (i > 0 && text[i].isWhitespace()) i--
		while (i > 0 && !text[i - 1].isWhitespace()) i--
		return i.coerceAtLeast(0)
	}

	private fun findNextWordBoundary(text: String, cursor: Int): Int {
		var i = cursor
		while (i < text.length && !text[i].isWhitespace()) i++
		while (i < text.length && text[i].isWhitespace()) i++
		return i.coerceAtMost(text.length)
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

		binding.commandInput.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

			override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
				if (isHandlingCtrlTypedChar || count != 1 || before != 0 || s == null) return
				val ctrlActive = binding.extraKeysView.readSpecialButton(SpecialButton.CTRL, true) == true
				if (!ctrlActive) return

				val typedChar = s[start].lowercaseChar()
				isHandlingCtrlTypedChar = true
				try {
					val editable = binding.commandInput.editableText ?: return
					// Remove the literal character that was just typed while CTRL was active
					editable.delete(start, start + 1)
					handleCtrlCombination(typedChar)
				} finally {
					isHandlingCtrlTypedChar = false
				}
			}

			override fun afterTextChanged(s: Editable?) {}
		})
	}

	private fun handleCtrlCombination(ch: Char) {
		val editText = binding.commandInput
		when (ch) {
			'c', 'u' -> {
				editText.text?.clear()
			}
			'l' -> {
				binding.consoleTextView.text = ""
				serviceMessenger?.let { messenger ->
					try {
						messenger.send(Message.obtain(null, DedicatedService.MSG_CLEAR_LOGS))
					} catch (_: RemoteException) {
					}
				}
			}
			'a' -> {
				editText.setSelection(0)
			}
			'e' -> {
				editText.setSelection(editText.text?.length ?: 0)
			}
			'w' -> {
				val editable = editText.editableText ?: return
				val cursor = editText.selectionStart.coerceAtLeast(0)
				if (cursor > 0) {
					val prevWord = findPreviousWordBoundary(editable.toString(), cursor)
					editable.delete(prevWord, cursor)
				}
			}
		}
	}

	private fun submitCurrentInput() {
		val cmd = binding.commandInput.text?.toString()?.trim().orEmpty()
		if (cmd.isEmpty()) return
		if (commandHistory.lastOrNull() != cmd) {
			commandHistory.add(cmd)
		}
		historyIndex = commandHistory.size
		pendingDraft = ""
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
		binding.extraKeysView.stopScheduledExecutors()
		unbindFromService()
		super.onDestroy()
	}

	private fun applyAppTheme() {
		val prefs = getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
		val defaultTheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			"dynamic_sys"
		} else {
			"fixed_sys"
		}

		val themeMode = prefs.getString("app_theme", defaultTheme) ?: defaultTheme

		when {
			themeMode.contains("light") -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
			themeMode.contains("dark") -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
			else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
		}

		if (themeMode == "legacy") {
			setTheme(R.style.Theme_App_Legacy)
		} else {
			setTheme(R.style.Theme_App_Fixed)
		}

		if (themeMode.startsWith("dynamic") && DynamicColors.isDynamicColorAvailable()) {
			DynamicColors.applyToActivityIfAvailable(this)
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

		private val SERVER_COMMANDS = listOf(
			"addip",
			"apropos",
			"banid",
			"changelevel",
			"cmdlist",
			"cvarlist",
			"echo",
			"exec",
			"exit",
			"find",
			"heartbeat",
			"hostname",
			"kick",
			"listid",
			"listip",
			"log",
			"map",
			"maps",
			"maxplayers",
			"mp_allowmonsters",
			"mp_autocrosshair",
			"mp_decals",
			"mp_falldamage",
			"mp_flashlight",
			"mp_footsteps",
			"mp_forcerespawn",
			"mp_fraglimit",
			"mp_friendlyfire",
			"mp_teamlist",
			"mp_teamplay",
			"mp_timelimit",
			"mp_weaponstay",
			"pausable",
			"quit",
			"rcon_password",
			"reload",
			"removeid",
			"removeip",
			"restart",
			"say",
			"serverinfo",
			"stats",
			"status",
			"sv_accelerate",
			"sv_aim",
			"sv_airaccelerate",
			"sv_allow_download",
			"sv_allow_upload",
			"sv_Bounce",
			"sv_cheats",
			"sv_clienttrace",
			"sv_ClipMode",
			"sv_friction",
			"sv_gravity",
			"sv_lan",
			"sv_lan_rate",
			"sv_logbans",
			"sv_maxclients",
			"sv_maxrate",
			"sv_maxspeed",
			"sv_maxunlag",
			"sv_minrate",
			"sv_nat",
			"sv_password",
			"sv_proxies",
			"sv_region",
			"sv_send_logos",
			"sv_send_resources",
			"sv_stepsize",
			"sv_stopspeed",
			"sv_timeout",
			"sv_unlag",
			"sv_unlagpush",
			"sv_unlagsamples",
			"sv_uploadmax",
			"sv_voiceenable",
			"sv_wateraccelerate",
			"sv_wateramp",
			"sv_waterfriction",
			"sv_zmax",
			"sys_ticrate",
			"users",
			"version",
			"writeid",
			"writeip"
		)
	}
}
