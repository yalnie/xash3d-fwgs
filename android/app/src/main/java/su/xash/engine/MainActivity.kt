package su.xash.engine

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Html
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import su.xash.engine.databinding.ActivityMainBinding
import su.xash.engine.model.AppUpdater
import su.xash.engine.util.CrashReports
import su.xash.engine.util.monospaceTextView
import su.xash.engine.util.showDownloadProgressDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var navController: NavController

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragmentContainerView) as NavHostFragment
        navController = navHostFragment.navController
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        checkNotificationPermission()

        CrashReports.prune(this)
        showPendingCrashReport()

        checkForEngineUpdate()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun checkForEngineUpdate() {
        val prefs = getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS)
            return

        val updater = AppUpdater(this)
        lifecycleScope.launch {
            val info = updater.checkForUpdate()
            prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            if (info == null)
                return@launch
            if (prefs.getInt(KEY_DISMISSED_BUILDNUM, -1) >= info.buildNum)
                return@launch

            showEngineUpdateDialog(updater, info.buildNum, info.changelog, prefs)
        }
    }

	private fun showEngineUpdateDialog(
		updater: AppUpdater,
		remoteBuildNum: Int,
		changelog: String?,
		prefs: android.content.SharedPreferences,
	) {
		val dialogView = layoutInflater.inflate(R.layout.dialog_engine_update, null)

		val tvTitle = dialogView.findViewById<TextView>(R.id.tvTitle)
		val tvCurrentVersion = dialogView.findViewById<TextView>(R.id.tvCurrentVersion)
		val tvNewVersion = dialogView.findViewById<TextView>(R.id.tvNewVersion)
		val tvChangelogHeader = dialogView.findViewById<TextView>(R.id.tvChangelogHeader)
		val tvChangelog = dialogView.findViewById<TextView>(R.id.tvChangelog)
		val btnLater = dialogView.findViewById<MaterialButton>(R.id.btnLater)
		val btnDownload = dialogView.findViewById<MaterialButton>(R.id.btnDownload)

		val layoutProgress = dialogView.findViewById<android.view.View>(R.id.layoutProgress)
		val progressBar = dialogView.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressBar)
		val tvProgressPercent = dialogView.findViewById<TextView>(R.id.tvProgressPercent)

		val currentHash = BuildConfig.GIT_HASH.ifEmpty { BuildConfig.VERSION_NAME }
		tvCurrentVersion.text = "b$currentHash"
		tvNewVersion.text = "b$remoteBuildNum"

		if (!changelog.isNullOrEmpty()) {
			tvChangelog.visibility = View.VISIBLE
			tvChangelog.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				Html.fromHtml(changelog, Html.FROM_HTML_MODE_COMPACT)
			} else {
				@Suppress("DEPRECATION")
				Html.fromHtml(changelog)
			}
		} else {
			tvChangelog.visibility = View.GONE
		}

		var isDownloading = false

		fun updateButtonState() {
			if (isDownloading) return
			val hasPermission = updater.canInstall()
			val hasApk = updater.hasDownloadedApk()

			when {
				!hasPermission -> {
					btnDownload.setText(R.string.engine_update_grant_permission)
					btnDownload.setIconResource(R.drawable.ic_baseline_lock_24px)
				}
				hasApk -> {
					btnDownload.setText(R.string.engine_update_install_now)
					btnDownload.setIconResource(R.drawable.ic_baseline_mobile_arrow_down_24px)
				}
				else -> {
					btnDownload.setText(R.string.engine_update_download)
					btnDownload.setIconResource(R.drawable.ic_baseline_download_24px)
				}
			}
		}

		updateButtonState()

		val dialog = MaterialAlertDialogBuilder(this)
			.setView(dialogView)
			.setCancelable(false)
			.create()

		dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

		val lifecycleObserver = object : DefaultLifecycleObserver {
			override fun onResume(owner: LifecycleOwner) {
				updateButtonState()
			}
		}
		lifecycle.addObserver(lifecycleObserver)

		dialog.setOnDismissListener {
			lifecycle.removeObserver(lifecycleObserver)
		}

		btnLater.setOnClickListener {
			if (!isDownloading) {
				prefs.edit().putInt(KEY_DISMISSED_BUILDNUM, remoteBuildNum).apply()
				dialog.dismiss()
			}
		}

		btnDownload.setOnClickListener {
			if (!updater.canInstall()) {
				val packageIntent = Intent(
					Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
					"package:$packageName".toUri()
				)
				try {
					startActivity(packageIntent)
				} catch (_: ActivityNotFoundException) {
					try {
						startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
					} catch (_: ActivityNotFoundException) {
					}
				}
			} else if (updater.hasDownloadedApk()) {
				updater.installDownloadedApk()
			} else {
				isDownloading = true
				btnLater.visibility = View.GONE
				btnDownload.isEnabled = false
				btnDownload.text = getString(R.string.engine_update_downloading)
				
				tvChangelogHeader.visibility = View.GONE
				tvChangelog.visibility = View.GONE
				layoutProgress.visibility = View.VISIBLE

				lifecycleScope.launch {
					withContext(Dispatchers.IO) {
						updater.downloadAndInstall { current, total ->
							runOnUiThread {
								if (total > 0) {
									val percent = ((current * 100) / total).toInt()
									progressBar.isIndeterminate = false
									progressBar.progress = percent
									tvProgressPercent.text = "%$percent"
								} else {
									progressBar.isIndeterminate = true
									tvProgressPercent.text = ""
								}
							}
						}
					}
					dialog.dismiss()
				}
			}
		}

		dialog.show()
	}

    private fun promptForInstallPermission() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.engine_update_permission_needed)
            .setMessage(R.string.engine_update_permission_message)
            .setPositiveButton(R.string.engine_update_open_settings) { _, _ ->
                val packageIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:$packageName".toUri()
                )
                try {
                    startActivity(packageIntent)
                } catch (_: ActivityNotFoundException) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                    } catch (_: ActivityNotFoundException) {
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    private fun showPendingCrashReport() {
        val pending = CrashReports.pendingStacktrace(this)
        if (!pending.exists() || pending.length() == 0L)
            return

        val historyDir = CrashReports.historyDir(this).apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val entryDir = File(historyDir, "crash-$ts").apply { mkdirs() }

        moveOrCopy(pending, File(entryDir, CrashReports.STACKTRACE_NAME))
        moveOrCopy(CrashReports.pendingSysinfo(this), File(entryDir, CrashReports.SYSINFO_NAME))
        moveOrCopy(CrashReports.pendingIntent(this), File(entryDir, CrashReports.INTENT_NAME))
        moveOrCopy(CrashReports.pendingEngineLog(this), File(entryDir, CrashReports.ENGINELOG_NAME))

        val entry = CrashReports.Entry(entryDir)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.crash_dialog_title)
            .setView(monospaceTextView(this, entry.summary()))
            .setPositiveButton(R.string.crash_share) { _, _ ->
                CrashReports.share(this, entry)
            }
            .setNegativeButton(R.string.crash_dismiss, null)
            .show()
    }

    private fun moveOrCopy(src: File, dst: File) {
        if (!src.exists())
            return

        if (src.renameTo(dst))
            return

        src.copyTo(dst, overwrite = true)
        src.delete()
    }

    companion object {
        private const val UPDATE_PREFS = "app_updater"
        private const val KEY_LAST_CHECK = "last_check_ms"
        private const val KEY_DISMISSED_BUILDNUM = "dismissed_buildnum"
        private const val CHECK_INTERVAL_MS = 0L
    }
}
