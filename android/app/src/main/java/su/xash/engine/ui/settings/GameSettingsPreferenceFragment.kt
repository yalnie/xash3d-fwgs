package su.xash.engine.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import su.xash.engine.R
import su.xash.engine.model.Game
import su.xash.engine.model.GameLibDownloader
import java.text.DateFormat
import java.util.Date

class GameSettingsPreferenceFragment(val game: Game) : PreferenceFragmentCompat() {
	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		preferenceManager.sharedPreferencesName = game.basedir.name;
		setPreferencesFromResource(R.xml.game_preferences, rootKey);

		val packageList = findPreference<ListPreference>("package_name")!!
		packageList.entries = arrayOf(getString(R.string.app_name))
		packageList.entryValues = arrayOf(requireContext().packageName)

		if (packageList.value == null) {
			packageList.setValueIndex(0);
		}

		if (game.basedir.name.equals("cstrike", ignoreCase = true)
			|| game.basedir.name.equals("czero", ignoreCase = true)) {
			val enableYaPBBots = findPreference<SwitchPreferenceCompat>("enable_yapb_bots")!!
			enableYaPBBots.isVisible = true
		}

		populateDownloadedBuildInfo()

		val separatePackages = findPreference<SwitchPreferenceCompat>("separate_libraries")!!
		val clientPackage = findPreference<ListPreference>("client_package")!!
		val serverPackage = findPreference<ListPreference>("server_package")!!
		separatePackages.setOnPreferenceChangeListener { _, newValue ->
			if (newValue == true) {
				packageList.isVisible = false
				clientPackage.isVisible = true
				serverPackage.isVisible = true
			} else {
				packageList.isVisible = true
				clientPackage.isVisible = false
				serverPackage.isVisible = false
			}

			true
		}
	}

	override fun onDisplayPreferenceDialog(preference: Preference) {
		if (preference is EditTextPreference) {
			val contextWithTheme = activity ?: requireContext()
			val builder = MaterialAlertDialogBuilder(contextWithTheme)
				.setTitle(preference.dialogTitle)
				.setIcon(preference.dialogIcon)
				.setNegativeButton(android.R.string.cancel, null)

			val inflater = LayoutInflater.from(contextWithTheme)
			val dialogView = inflater.inflate(androidx.preference.R.layout.preference_dialog_edittext, null)
			val editText = dialogView?.findViewById<android.widget.EditText>(android.R.id.edit)
			val messageView = dialogView?.findViewById<android.widget.TextView>(android.R.id.message)

			if (editText != null && dialogView != null) {
				editText.setText(preference.text)
				editText.setSelection(editText.text.length)

				editText.isSingleLine = true
				editText.maxLines = 1
				editText.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE

				messageView?.visibility = View.GONE

				val orangeColor = androidx.core.content.ContextCompat.getColor(contextWithTheme, R.color.hl_orange)
				editText.backgroundTintList = android.content.res.ColorStateList.valueOf(orangeColor)
				
				if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
					editText.textCursorDrawable?.setTint(orangeColor)
					editText.textSelectHandle?.setTint(orangeColor)
					editText.textSelectHandleLeft?.setTint(orangeColor)
					editText.textSelectHandleRight?.setTint(orangeColor)
				}

				builder.setView(dialogView)

				builder.setPositiveButton(android.R.string.ok) { dialog, _ ->
					val newValue = editText.text.toString()
					if (preference.callChangeListener(newValue)) {
						preference.text = newValue
					}
					dialog.dismiss()
				}
			} else {
				builder.setMessage(preference.dialogMessage)
				builder.setPositiveButton(android.R.string.ok, null)
			}

			val dialog = builder.create()
			dialog.show()
		} else {
			super.onDisplayPreferenceDialog(preference)
		}
	}

	private fun populateDownloadedBuildInfo() {
		val downloader = GameLibDownloader(requireContext())
		val source = downloader.getSourceInfo(game.basedir.name) ?: return
		val downloadedAt = downloader.getDownloadTime(game.basedir.name)

		val urlPref = findPreference<Preference>("source_url")!!
		urlPref.isVisible = true
		urlPref.summary = source.url ?: "—"
		urlPref.isEnabled = source.url != null
		urlPref.setOnPreferenceClickListener {
			source.url?.let {
				startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it)))
			}
			true
		}

		val branchPref = findPreference<Preference>("source_branch")!!
		branchPref.isVisible = true
		branchPref.summary = source.branch ?: "—"

		val commitPref = findPreference<Preference>("source_commit")!!
		commitPref.isVisible = true
		commitPref.summary = source.commit ?: "—"
		commitPref.isEnabled = source.commit != null && source.url != null
		commitPref.setOnPreferenceClickListener {
			// FIXME: GitHub-styled URL!
			val target = "${source.url!!.trimEnd('/')}/commit/${source.commit}"
			startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target)))
			true
		}

		val timePref = findPreference<Preference>("downloaded_at")!!
		timePref.isVisible = true
		timePref.summary = if (downloadedAt > 0L)
			DateFormat.getDateTimeInstance().format(Date(downloadedAt))
		else
			"—"
	}
}
