package su.xash.engine.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object AppIconManager {

	enum class AppIcon(val aliasClassName: String) {
		DEFAULT("su.xash.engine.MainActivityDefault"),
		XASH("su.xash.engine.MainActivityXash"),
		PRIDE("su.xash.engine.MainActivityPride"),
		TRANS("su.xash.engine.MainActivityTrans"),
		LAMBDA("su.xash.engine.MainActivityLambda")
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
}
