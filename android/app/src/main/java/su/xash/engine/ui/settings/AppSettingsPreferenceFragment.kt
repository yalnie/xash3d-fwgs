package su.xash.engine.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import su.xash.engine.BuildConfig
import su.xash.engine.FPicker
import su.xash.engine.MainActivity
import su.xash.engine.R
import su.xash.engine.model.Game
import su.xash.engine.util.GameDataDirectory
import su.xash.engine.util.GameDataDirectory.Result
import su.xash.engine.util.Prefs
import java.io.File

class AppSettingsPreferenceFragment : PreferenceFragmentCompat() {
	private var gameDirectoryJob: Job? = null
	private var gameDirectoryRequestId = 0
	// Only one error or confirmation dialog can be active at a time
	private var activeDialog: AlertDialog? = null

	private val appPreferences
		get() = requireContext().getSharedPreferences(Prefs.APP_PREFERENCES, Context.MODE_PRIVATE)

	private val internalPickerLauncher = registerForActivityResult(
		ActivityResultContracts.StartActivityForResult()
	) { result ->
		if (result.resultCode == Activity.RESULT_OK) {
			val selectedPath = result.data?.getStringExtra("GetPath")
			if (!selectedPath.isNullOrEmpty()) {
				handlePickedGameDirectoryFile(File(selectedPath))
			}
		}
	}

	private val gameDirectoryPicker = registerForActivityResult(
		ActivityResultContracts.OpenDocumentTree()
	) { uri ->
		if (uri == null)
			return@registerForActivityResult

		handlePickedGameDirectoryUri(uri)
	}

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		preferenceManager.sharedPreferencesName = Prefs.APP_PREFERENCES
		setPreferencesFromResource(R.xml.app_preferences, rootKey)
		val prefs = appPreferences

		val themePref = findPreference<ListPreference>("app_theme")
		if (prefs.getString("app_theme", null) == null) {
			val defaultTheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "dynamic_sys" else "fixed_sys"
			themePref?.value = defaultTheme
			prefs.edit().putString("app_theme", defaultTheme).apply()
		}

		themePref?.setOnPreferenceChangeListener { _, _ ->
			requireActivity().recreate()
			true
		}

		findPreference<GamePathPreference>(Prefs.GAME_PATH)?.apply {
			setOnPreferenceClickListener {
				when (prefs.getString("file_picker_type", "ask")) {
					"internal" -> openInternalPicker()
					"saf" -> openSafPicker()
					else -> showFilePickerSelectionDialog()
				}
				true
			}
			onResetClickListener = { resetGamePath() }
		}
		updateGamePathPreference()

		findPreference<Preference>("crash_logs")?.setOnPreferenceClickListener {
			findNavController().navigate(R.id.action_appSettingsFragment_to_crashLogsFragment)
			true
		}

		findPreference<Preference>("check_updates")?.apply {
			// Auto-update is only compiled into the continuous build; hide the
			// manual check elsewhere, since the updater is a no-op there.
			isVisible = BuildConfig.ENABLE_AUTO_UPDATE
			setOnPreferenceClickListener {
				(requireActivity() as MainActivity).checkForUpdatesManually()
				true
			}
		}

		val iconPref = findPreference<Preference>("open_app_icons_screen")
		val currentIcon = prefs.getString("app_icon_setting", "DEFAULT")

		iconPref?.summary = when (currentIcon) {
			"XASH" -> getString(R.string.icon_name_xash)
			"LEGACY" -> getString(R.string.icon_name_legacy)
			"PRIDE" -> getString(R.string.icon_name_pride)
			"TRANS" -> getString(R.string.icon_name_trans)
			"LAMBDA" -> getString(R.string.icon_name_lambda)
			"SALIH" -> getString(R.string.icon_name_salih)
			else -> getString(R.string.icon_name_default)
		}

		iconPref?.setOnPreferenceClickListener {
			findNavController().navigate(R.id.action_appSettingsFragment_to_appIconsFragment)
			true
		}
	}

	private fun openInternalPicker() {
		cancelPendingGameDirectorySelection()
		val intent = Intent(requireContext(), FPicker::class.java)
		internalPickerLauncher.launch(intent)
	}

	private fun openSafPicker() {
		cancelPendingGameDirectorySelection()
		try {
			gameDirectoryPicker.launch(null)
		} catch (e: ActivityNotFoundException) {
			// Some devices have no document picker
			showGameDirectoryError(R.string.game_data_location_picker_unavailable)
		}
	}

	private fun showFilePickerSelectionDialog() {
		val options = arrayOf(
			getString(R.string.file_picker_internal),
			getString(R.string.file_picker_saf)
		)
		showDialog(
			MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.select_file_picker_title)
				.setItems(options) { _, which ->
					when (which) {
						0 -> openInternalPicker()
						1 -> openSafPicker()
					}
				}
				.setNegativeButton(android.R.string.cancel, null)
		)
	}

	private fun handlePickedGameDirectoryUri(uri: Uri) {
		cancelPendingGameDirectorySelection()
		val requestId = gameDirectoryRequestId
		val context = requireContext()

		// Resolving the URI and scanning the picked folder for game
		// directories hits the filesystem and can be slow on large folders,
		// so keep it off the main thread.
		gameDirectoryJob = lifecycleScope.launch {
			val result = withContext(Dispatchers.IO) {
				GameDataDirectory.resolve(context, uri)
			}
			if (requestId != gameDirectoryRequestId) return@launch

			val success = when (result) {
				is Result.Success -> result
				is Result.Failure -> {
					showGameDirectoryError(result.messageRes, *result.formatArgs.toTypedArray())
					return@launch
				}
			}

			validateAndSaveGameDirectory(success.directory, requestId)
		}
	}

	private fun handlePickedGameDirectoryFile(directory: File) {
		cancelPendingGameDirectorySelection()
		val requestId = gameDirectoryRequestId

		gameDirectoryJob = lifecycleScope.launch {
			validateAndSaveGameDirectory(directory, requestId)
		}
	}

	private suspend fun validateAndSaveGameDirectory(directory: File, requestId: Int) {
		// The library expects a root containing game directories, not a single game's directory
		val isSingleGame = withContext(Dispatchers.IO) {
			Game.checkIfGamedir(directory) != null
		}
		if (requestId != gameDirectoryRequestId) return
		if (isSingleGame) {
			showGameDirectoryError(R.string.game_data_location_select_parent)
			return
		}

		val hasGames = withContext(Dispatchers.IO) { hasGameDirectories(directory) }
		if (requestId != gameDirectoryRequestId) return

		when (hasGames) {
			null -> showGameDirectoryError(R.string.game_data_location_unreadable)
			true -> saveGamePath(directory, requestId)
			false -> confirmGamePathWithoutGames(directory, requestId)
		}
	}

	// Returns null if the directory cannot be listed
	private fun hasGameDirectories(directory: File): Boolean? = try {
		directory.listFiles()?.any { it.isDirectory && Game.checkIfGamedir(it) != null }
	} catch (e: SecurityException) {
		null
	}

	private fun cancelPendingGameDirectorySelection() {
		gameDirectoryRequestId++
		gameDirectoryJob?.cancel()
		gameDirectoryJob = null
		activeDialog?.dismiss()
		activeDialog = null
	}

	private fun saveGamePath(directory: File, requestId: Int) {
		if (requestId != gameDirectoryRequestId) return

		// Selecting the default directory is equivalent to resetting the preference
		if (Prefs.isDefaultGamePath(directory)) {
			appPreferences.edit().remove(Prefs.GAME_PATH).apply()
		} else {
			appPreferences.edit().putString(Prefs.GAME_PATH, directory.absolutePath).apply()
		}

		updateGamePathPreference()
	}

	private fun resetGamePath() {
		cancelPendingGameDirectorySelection()
		appPreferences.edit().remove(Prefs.GAME_PATH).apply()
		updateGamePathPreference()
	}

	private fun updateGamePathPreference() {
		val gamePath = appPreferences.getString(Prefs.GAME_PATH, null)
		findPreference<GamePathPreference>(Prefs.GAME_PATH)?.apply {
			summary = gamePath ?: Prefs.defaultGamePath.absolutePath
			isResetEnabled = gamePath != null
		}
	}

	private fun confirmGamePathWithoutGames(directory: File, requestId: Int) {
		if (requestId != gameDirectoryRequestId) return
		showDialog(
			MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.game_data_location_empty_title)
				.setMessage(R.string.game_data_location_empty_message)
				.setPositiveButton(R.string.game_data_location_use_anyway) { _, _ ->
					saveGamePath(directory, requestId)
				}
				.setNegativeButton(android.R.string.cancel, null)
		)
	}

	override fun onDestroyView() {
		cancelPendingGameDirectorySelection()
		super.onDestroyView()
	}

	private fun showGameDirectoryError(messageRes: Int, vararg formatArgs: Any) {
		val message = if (formatArgs.isEmpty()) {
			getString(messageRes)
		} else {
			getString(messageRes, *formatArgs)
		}

		showDialog(
			MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.game_data_location)
				.setMessage(message)
				.setPositiveButton(android.R.string.ok, null)
		)
	}

	// Track the dialog so onDestroyView() can dismiss it
	private fun showDialog(builder: MaterialAlertDialogBuilder) {
		activeDialog?.dismiss()

		val dialog = builder.create()
		dialog.setOnDismissListener {
			if (activeDialog === dialog) activeDialog = null
		}
		activeDialog = dialog
		dialog.show()
	}
}
