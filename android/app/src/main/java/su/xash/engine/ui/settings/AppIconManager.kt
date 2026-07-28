package su.xash.engine.ui.settings

import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import su.xash.engine.R

object AppIconManager {

	enum class AppIcon(val aliasClassName: String) {
		DEFAULT("su.xash.engine.MainActivityDefault"),
		XASH("su.xash.engine.MainActivityXash"),
		PRIDE("su.xash.engine.MainActivityPride"),
		TRANS("su.xash.engine.MainActivityTrans"),
		LAMBDA("su.xash.engine.MainActivityLambda"),
		SALIH("su.xash.engine.MainActivitySalih")
	}

	fun setAppIcon(context: Context, targetIcon: AppIcon) {
		val packageManager = context.packageManager
		val packageName = context.packageName

		AppIcon.values().forEach { icon ->
			val componentName = ComponentName(packageName, icon.aliasClassName)
			val newState = if (icon == targetIcon) {
				PackageManager.COMPONENT_ENABLED_STATE_ENABLED
			} else {
				PackageManager.COMPONENT_ENABLED_STATE_DISABLED
			}
			packageManager.setComponentEnabledSetting(
				componentName,
				newState,
				PackageManager.DONT_KILL_APP
			)
		}
	}

	fun getNotificationIconRes(context: Context): Int {
		val prefs = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
		val savedIconName = prefs.getString("app_icon_setting", AppIcon.DEFAULT.name) ?: AppIcon.DEFAULT.name
		return when (savedIconName) {
			"XASH" -> R.mipmap.ic_launcher_xash
			"PRIDE" -> R.mipmap.ic_launcher_pride
			"TRANS" -> R.mipmap.ic_launcher_trans
			"LAMBDA" -> R.mipmap.ic_launcher_lambda
			"SALIH" -> R.mipmap.ic_launcher_salih
			else -> R.mipmap.ic_launcher
		}
	}
}
