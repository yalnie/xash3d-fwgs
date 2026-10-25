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
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import su.xash.engine.databinding.ActivityDedicatedBinding
import su.xash.engine.ui.extrakeys.ConsoleEditText
import su.xash.engine.ui.extrakeys.ExtraKeyButton
import su.xash.engine.ui.extrakeys.ExtraKeysConstants
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
	private var isDispatchingSyntheticKey = false

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
				DedicatedService.MSG_AUTOCOMPLETE_RESULT -> {
					val completed = msg.data?.getString(DedicatedService.KEY_AUTOCOMPLETE_TEXT) ?: return
					binding.commandInput.setText(completed)
					binding.commandInput.setSelection(completed.length)
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

			override fun onSpecialButtonToggled(
				specialButton: SpecialButton,
				isActive: Boolean,
				isLocked: Boolean
			) {
				if (isActive) {
					// Finalize any active IME composing span so the next soft-keyboard
					// press is intercepted cleanly as a modified key event.
					binding.commandInput.text?.let { BaseInputConnection.removeComposingSpans(it) }
					val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
					imm?.restartInput(binding.commandInput)
				}
			}
		}

		val keysMatrix = arrayOf(
			arrayOf("ESC", "/", "-", "HOME", "UP", "END", "PGUP"),
			arrayOf("TAB", "CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "PGDN")
		)
		val extraKeysInfo = ExtraKeysInfo(keysMatrix)
		binding.extraKeysView.reload(extraKeysInfo)
	}

	private fun readMetaStateFromExtraKeys(): Int {
		val ctrlDown = binding.extraKeysView.readSpecialButton(SpecialButton.CTRL, true) == true
		val altDown = binding.extraKeysView.readSpecialButton(SpecialButton.ALT, true) == true
		val shiftDown = binding.extraKeysView.readSpecialButton(SpecialButton.SHIFT, true) == true
		var metaState = 0
		if (ctrlDown) {
			metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
		}
		if (altDown) {
			metaState = metaState or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
		}
		if (shiftDown) {
			metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
		}
		return metaState
	}

	private fun dispatchRealKeyEvent(keyCode: Int, metaState: Int = 0): Boolean {
		val now = SystemClock.uptimeMillis()
		val downEvent = KeyEvent(
			now,
			now,
			KeyEvent.ACTION_DOWN,
			keyCode,
			0,
			metaState,
			KeyCharacterMap.VIRTUAL_KEYBOARD,
			0,
			0
		)
		val upEvent = KeyEvent(
			now,
			now,
			KeyEvent.ACTION_UP,
			keyCode,
			0,
			metaState,
			KeyCharacterMap.VIRTUAL_KEYBOARD,
			0,
			0
		)
		isDispatchingSyntheticKey = true
		return try {
			val handledDown = binding.commandInput.dispatchKeyEvent(downEvent)
			binding.commandInput.dispatchKeyEvent(upEvent)
			handledDown
		} finally {
			isDispatchingSyntheticKey = false
		}
	}

	private fun handleExtraKeyPress(buttonInfo: ExtraKeyButton) {
		val metaState = readMetaStateFromExtraKeys()
		val ctrlDown = (metaState and KeyEvent.META_CTRL_ON) != 0
		val altDown = (metaState and KeyEvent.META_ALT_ON) != 0
		val editText = binding.commandInput
		editText.requestFocus()
		editText.text?.let { BaseInputConnection.removeComposingSpans(it) }

		when (val key = buttonInfo.key) {
			"ESC" -> {
				dispatchRealKeyEvent(KeyEvent.KEYCODE_ESCAPE, metaState)
				if (editText.hasSelection()) {
					editText.setSelection(editText.selectionEnd)
				} else if (!editText.text.isNullOrEmpty()) {
					editText.text?.clear()
					historyIndex = commandHistory.size
					pendingDraft = ""
				} else {
					val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
					imm?.hideSoftInputFromWindow(editText.windowToken, 0)
				}
			}
			"TAB" -> {
				requestEngineAutoComplete()
			}
			"HOME" -> {
				if (ctrlDown) {
					binding.consoleScrollView.fullScroll(View.FOCUS_UP)
				} else {
					dispatchRealKeyEvent(KeyEvent.KEYCODE_MOVE_HOME, metaState)
				}
			}
			"END" -> {
				if (ctrlDown) {
					binding.consoleScrollView.fullScroll(View.FOCUS_DOWN)
				} else {
					dispatchRealKeyEvent(KeyEvent.KEYCODE_MOVE_END, metaState)
				}
			}
			"UP" -> {
				if (ctrlDown || altDown) {
					val lineStep = (binding.consoleTextView.lineHeight * 3).coerceAtLeast(48)
					binding.consoleScrollView.smoothScrollBy(0, -lineStep)
				} else {
					navigateHistory(-1)
				}
			}
			"DOWN" -> {
				if (ctrlDown || altDown) {
					val lineStep = (binding.consoleTextView.lineHeight * 3).coerceAtLeast(48)
					binding.consoleScrollView.smoothScrollBy(0, lineStep)
				} else {
					navigateHistory(1)
				}
			}
			"LEFT" -> {
				val effectiveMeta = if (altDown && !ctrlDown) {
					(metaState and KeyEvent.META_ALT_MASK.inv()) or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
				} else {
					metaState
				}
				dispatchRealKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT, effectiveMeta)
			}
			"RIGHT" -> {
				val effectiveMeta = if (altDown && !ctrlDown) {
					(metaState and KeyEvent.META_ALT_MASK.inv()) or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
				} else {
					metaState
				}
				dispatchRealKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, effectiveMeta)
			}
			"PGUP" -> {
				if (ctrlDown) {
					binding.consoleScrollView.fullScroll(View.FOCUS_UP)
				} else {
					val page = (binding.consoleScrollView.height * 0.8f).toInt().coerceAtLeast(100)
					binding.consoleScrollView.smoothScrollBy(0, -page)
				}
			}
			"PGDN" -> {
				if (ctrlDown) {
					binding.consoleScrollView.fullScroll(View.FOCUS_DOWN)
				} else {
					val page = (binding.consoleScrollView.height * 0.8f).toInt().coerceAtLeast(100)
					binding.consoleScrollView.smoothScrollBy(0, page)
				}
			}
			else -> {
				val keyCode = ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS[key]
				if (keyCode != null) {
					dispatchRealKeyEvent(keyCode, metaState)
				} else if (key.length == 1 && (ctrlDown || altDown)) {
					handleModifiedCharacter(key[0], metaState)
				} else {
					val editable = editText.editableText ?: return
					val start = editText.selectionStart.coerceAtLeast(0)
					val end = editText.selectionEnd.coerceAtLeast(0)
					editable.replace(minOf(start, end), maxOf(start, end), key)
				}
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

	private fun requestEngineAutoComplete() {
		val messenger = serviceMessenger
		if (messenger == null || !isServerRunning) {
			return
		}
		val current = binding.commandInput.text?.toString().orEmpty()
		try {
			val msg = Message.obtain(null, DedicatedService.MSG_AUTOCOMPLETE).apply {
				data = Bundle().apply {
					putString(DedicatedService.KEY_AUTOCOMPLETE_TEXT, current)
				}
			}
			messenger.send(msg)
		} catch (_: RemoteException) {
		}
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

		binding.commandInput.consoleKeyListener = object : ConsoleEditText.OnConsoleKeyListener {
			override fun isSpecialModifierActive(): Boolean {
				return binding.extraKeysView.isAnySpecialButtonActive
			}

			override fun onModifiedCharTyped(ch: Char): Boolean {
				val metaState = readMetaStateFromExtraKeys()
				if (metaState == 0) return false
				return handleModifiedCharacter(ch, metaState)
			}

			override fun onModifiedBackspace(): Boolean {
				val metaState = readMetaStateFromExtraKeys()
				if (metaState == 0) return false
				deletePreviousWord()
				return true
			}

			override fun onConsoleKeyDown(keyCode: Int, event: KeyEvent): Boolean {
				if (isDispatchingSyntheticKey) return false

				val extraMeta = readMetaStateFromExtraKeys()
				val combinedMeta = event.metaState or extraMeta
				val ctrlDown = (combinedMeta and KeyEvent.META_CTRL_ON) != 0
				val altDown = (combinedMeta and KeyEvent.META_ALT_ON) != 0

				when (keyCode) {
					KeyEvent.KEYCODE_TAB -> {
						requestEngineAutoComplete()
						return true
					}
					KeyEvent.KEYCODE_DPAD_UP -> {
						if (ctrlDown || altDown) {
							val lineStep = (binding.consoleTextView.lineHeight * 3).coerceAtLeast(48)
							binding.consoleScrollView.smoothScrollBy(0, -lineStep)
						} else {
							navigateHistory(-1)
						}
						return true
					}
					KeyEvent.KEYCODE_DPAD_DOWN -> {
						if (ctrlDown || altDown) {
							val lineStep = (binding.consoleTextView.lineHeight * 3).coerceAtLeast(48)
							binding.consoleScrollView.smoothScrollBy(0, lineStep)
						} else {
							navigateHistory(1)
						}
						return true
					}
					KeyEvent.KEYCODE_PAGE_UP -> {
						if (ctrlDown) {
							binding.consoleScrollView.fullScroll(View.FOCUS_UP)
						} else {
							val page = (binding.consoleScrollView.height * 0.8f).toInt().coerceAtLeast(100)
							binding.consoleScrollView.smoothScrollBy(0, -page)
						}
						return true
					}
					KeyEvent.KEYCODE_PAGE_DOWN -> {
						if (ctrlDown) {
							binding.consoleScrollView.fullScroll(View.FOCUS_DOWN)
						} else {
							val page = (binding.consoleScrollView.height * 0.8f).toInt().coerceAtLeast(100)
							binding.consoleScrollView.smoothScrollBy(0, page)
						}
						return true
					}
					KeyEvent.KEYCODE_ESCAPE -> {
						val editText = binding.commandInput
						if (!editText.text.isNullOrEmpty()) {
							editText.text?.clear()
							historyIndex = commandHistory.size
							pendingDraft = ""
							return true
						}
					}
					KeyEvent.KEYCODE_DEL -> {
						if (ctrlDown || altDown) {
							deletePreviousWord()
							return true
						}
					}
				}

				if (ctrlDown || altDown) {
					val unicodeChar = event.getUnicodeChar( event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv() )
					if (unicodeChar > 0) {
						return handleModifiedCharacter(unicodeChar.toChar(), combinedMeta)
					} else if (extraMeta != 0) {
						return dispatchRealKeyEvent(keyCode, combinedMeta)
					}
				}

				return false
			}
		}
	}

	private fun deletePreviousWord() {
		val editText = binding.commandInput
		val editable = editText.editableText ?: return
		val start = editText.selectionStart.coerceAtLeast(0)
		val end = editText.selectionEnd.coerceAtLeast(0)
		if (start != end) {
			editable.delete(minOf(start, end), maxOf(start, end))
			return
		}
		if (start > 0) {
			val prevWord = findPreviousWordBoundary(editable.toString(), start)
			editable.delete(prevWord, start)
		}
	}

	private fun clearConsoleLogs() {
		binding.consoleTextView.text = ""
		serviceMessenger?.let { messenger ->
			try {
				messenger.send(Message.obtain(null, DedicatedService.MSG_CLEAR_LOGS))
			} catch (_: RemoteException) {
			}
		}
	}

	private fun handleModifiedCharacter(rawChar: Char, metaState: Int): Boolean {
		val editText = binding.commandInput
		val ctrlDown = (metaState and KeyEvent.META_CTRL_ON) != 0
		val altDown = (metaState and KeyEvent.META_ALT_ON) != 0
		val ch = rawChar.lowercaseChar()

		if (ctrlDown) {
			when (ch) {
				'a' -> {
					val len = editText.text?.length ?: 0
					if (len > 0 && (editText.selectionStart != 0 || editText.selectionEnd != len)) {
						dispatchRealKeyEvent(KeyEvent.KEYCODE_A, metaState)
					} else {
						editText.setSelection(0)
					}
					return true
				}
				'c' -> {
					if (editText.hasSelection()) {
						dispatchRealKeyEvent(KeyEvent.KEYCODE_C, metaState)
					} else {
						val current = editText.text?.toString().orEmpty()
						if (current.isNotEmpty()) {
							appendConsoleText("^5> $current^1^C^7\n")
							editText.text?.clear()
							historyIndex = commandHistory.size
							pendingDraft = ""
						}
					}
					return true
				}
				'x' -> {
					if (!editText.hasSelection() && !editText.text.isNullOrEmpty()) {
						editText.selectAll()
					}
					dispatchRealKeyEvent(KeyEvent.KEYCODE_X, metaState)
					return true
				}
				'v' -> {
					dispatchRealKeyEvent(KeyEvent.KEYCODE_V, metaState)
					return true
				}
				'z' -> {
					dispatchRealKeyEvent(KeyEvent.KEYCODE_Z, metaState)
					return true
				}
				'y' -> {
					dispatchRealKeyEvent(KeyEvent.KEYCODE_Y, metaState)
					return true
				}
				'l' -> {
					clearConsoleLogs()
					return true
				}
				'u' -> {
					val editable = editText.editableText ?: return true
					val cursor = editText.selectionStart.coerceAtLeast(0)
					if (cursor > 0) {
						editable.delete(0, cursor)
					} else {
						editable.clear()
					}
					return true
				}
				'k' -> {
					val editable = editText.editableText ?: return true
					val cursor = editText.selectionStart.coerceAtLeast(0)
					if (cursor < editable.length) {
						editable.delete(cursor, editable.length)
					}
					return true
				}
				'w' -> {
					deletePreviousWord()
					return true
				}
				'e' -> {
					editText.setSelection(editText.text?.length ?: 0)
					return true
				}
				'b' -> {
					dispatchRealKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT, 0)
					return true
				}
				'f' -> {
					dispatchRealKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
					return true
				}
				'p' -> {
					navigateHistory(-1)
					return true
				}
				'n' -> {
					navigateHistory(1)
					return true
				}
				'h' -> {
					dispatchRealKeyEvent(KeyEvent.KEYCODE_DEL, 0)
					return true
				}
				'd' -> {
					if (editText.text.isNullOrEmpty()) {
						sendCommand("quit")
					} else {
						dispatchRealKeyEvent(KeyEvent.KEYCODE_FORWARD_DEL, 0)
					}
					return true
				}
			}
		}

		if (altDown && !ctrlDown) {
			when (ch) {
				'b' -> {
					dispatchRealKeyEvent(
						KeyEvent.KEYCODE_DPAD_LEFT,
						KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
					)
					return true
				}
				'f' -> {
					dispatchRealKeyEvent(
						KeyEvent.KEYCODE_DPAD_RIGHT,
						KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
					)
					return true
				}
				'd' -> {
					val editable = editText.editableText ?: return true
					val cursor = editText.selectionStart.coerceAtLeast(0)
					if (cursor < editable.length) {
						val nextWord = findNextWordBoundary(editable.toString(), cursor)
						editable.delete(cursor, nextWord)
					}
					return true
				}
				'<', ',' -> {
					if (commandHistory.isNotEmpty()) {
						navigateHistory(-commandHistory.size)
					}
					return true
				}
				'>', '.' -> {
					if (commandHistory.isNotEmpty()) {
						navigateHistory(commandHistory.size)
					}
					return true
				}
			}
		}

		val keyCode = when (ch) {
			in 'a'..'z' -> KeyEvent.KEYCODE_A + (ch - 'a')
			in '0'..'9' -> KeyEvent.KEYCODE_0 + (ch - '0')
			'/' -> KeyEvent.KEYCODE_SLASH
			'-' -> KeyEvent.KEYCODE_MINUS
			'=' -> KeyEvent.KEYCODE_EQUALS
			'[' -> KeyEvent.KEYCODE_LEFT_BRACKET
			']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
			'\\' -> KeyEvent.KEYCODE_BACKSLASH
			';' -> KeyEvent.KEYCODE_SEMICOLON
			'\'' -> KeyEvent.KEYCODE_APOSTROPHE
			',' -> KeyEvent.KEYCODE_COMMA
			'.' -> KeyEvent.KEYCODE_PERIOD
			'`' -> KeyEvent.KEYCODE_GRAVE
			' ' -> KeyEvent.KEYCODE_SPACE
			else -> null
		}

		if (keyCode != null) {
			dispatchRealKeyEvent(keyCode, metaState)
			return true
		}

		return false
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
	}
}
