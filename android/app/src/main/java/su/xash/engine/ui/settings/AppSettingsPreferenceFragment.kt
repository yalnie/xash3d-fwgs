package su.xash.engine.ui.settings

import android.content.Context
import android.os.Bundle
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import su.xash.engine.R

class AppSettingsPreferenceFragment() : PreferenceFragmentCompat() {
	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		preferenceManager.sharedPreferencesName = "app_preferences";
		setPreferencesFromResource(R.xml.app_preferences, rootKey);

		findPreference<Preference>("crash_logs")?.setOnPreferenceClickListener {
			findNavController().navigate(R.id.action_appSettingsFragment_to_crashLogsFragment)
			true
		}

		val iconPref = findPreference<Preference>("open_app_icons_screen")
		val prefs = requireContext().getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
		val currentIcon = prefs.getString("app_icon_setting", "DEFAULT")
		
		iconPref?.summary = when(currentIcon) {
			"XASH" -> getString(R.string.icon_name_xash)
			"PRIDE" -> getString(R.string.icon_name_pride)
			"TRANS" -> getString(R.string.icon_name_trans)
			"LAMBDA" -> getString(R.string.icon_name_lambda)
			else -> getString(R.string.icon_name_default)
		}

		iconPref?.setOnPreferenceClickListener {
			findNavController().navigate(R.id.action_appSettingsFragment_to_appIconsFragment)
			true
		}
	}
}
