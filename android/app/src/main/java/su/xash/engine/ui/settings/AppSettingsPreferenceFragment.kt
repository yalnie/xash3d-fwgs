package su.xash.engine.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.fragment.findNavController
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import su.xash.engine.FPicker
import su.xash.engine.R
import java.io.File

class AppSettingsPreferenceFragment : PreferenceFragmentCompat() {

	private val folderPickerLauncher = registerForActivityResult(
		ActivityResultContracts.StartActivityForResult()
	) { result ->
		if (result.resultCode == Activity.RESULT_OK) {
			val selectedPath = result.data?.getStringExtra("GetPath")
			if (!selectedPath.isNullOrEmpty()) {
				saveAndUpdateGamePath(selectedPath)
			}
		}
	}

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		preferenceManager.sharedPreferencesName = "app_preferences"
		setPreferencesFromResource(R.xml.app_preferences, rootKey)
		val prefs = requireContext().getSharedPreferences("app_preferences", Context.MODE_PRIVATE)

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

		val gamePathPref = findPreference<Preference>("game_path")
		val defaultPath = File(Environment.getExternalStorageDirectory(), "xash").absolutePath
		val currentPath = prefs.getString("game_path", defaultPath) ?: defaultPath
		gamePathPref?.summary = currentPath

		gamePathPref?.setOnPreferenceClickListener {
			val intent = Intent(requireContext(), FPicker::class.java)
			folderPickerLauncher.launch(intent)
			true
		}

		findPreference<Preference>("crash_logs")?.setOnPreferenceClickListener {
			findNavController().navigate(R.id.action_appSettingsFragment_to_crashLogsFragment)
			true
		}

		val iconPref = findPreference<Preference>("open_app_icons_screen")
		val currentIcon = prefs.getString("app_icon_setting", "DEFAULT")
		
		iconPref?.summary = when(currentIcon) {
			"XASH" -> getString(R.string.icon_name_xash)
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

	private fun saveAndUpdateGamePath(path: String) {
		val prefs = requireContext().getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
		prefs.edit().putString("game_path", path).apply()
		findPreference<Preference>("game_path")?.summary = path
	}
}
