package su.xash.engine.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import su.xash.engine.R
import su.xash.engine.databinding.FragmentAppIconsBinding

class AppIconsFragment : Fragment() {

	private var _binding: FragmentAppIconsBinding? = null
	private val binding get() = _binding!!

	override fun onCreateView(
		inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
	): View {
		_binding = FragmentAppIconsBinding.inflate(inflater, container, false)
		return binding.root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		val context = requireContext()
		val prefs = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
		val savedIconName = prefs.getString("app_icon_setting", AppIconManager.AppIcon.DEFAULT.name) ?: AppIconManager.AppIcon.DEFAULT.name
		val currentIcon = try { AppIconManager.AppIcon.valueOf(savedIconName) } catch (e: Exception) { AppIconManager.AppIcon.DEFAULT }

		val iconList = listOf(
			AppIconsAdapter.IconItem(AppIconManager.AppIcon.DEFAULT, getString(R.string.icon_name_default), R.mipmap.ic_launcher),
			AppIconsAdapter.IconItem(AppIconManager.AppIcon.XASH, getString(R.string.icon_name_xash), R.mipmap.ic_launcher_xash),
			AppIconsAdapter.IconItem(AppIconManager.AppIcon.PRIDE, getString(R.string.icon_name_pride), R.mipmap.ic_launcher_pride),
			AppIconsAdapter.IconItem(AppIconManager.AppIcon.TRANS, getString(R.string.icon_name_trans), R.mipmap.ic_launcher_trans),
			AppIconsAdapter.IconItem(AppIconManager.AppIcon.LAMBDA, getString(R.string.icon_name_lambda), R.mipmap.ic_launcher_lambda)
			AppIconsAdapter.IconItem(AppIconManager.AppIcon.SALIH, getString(R.string.icon_name_salih), R.mipmap.ic_launcher_salih)
		)

		val adapter = AppIconsAdapter(iconList, currentIcon) { selectedIcon ->
			prefs.edit().putString("app_icon_setting", selectedIcon.name).apply()
			AppIconManager.setAppIcon(context, selectedIcon)
		}

		binding.rvAppIcons.adapter = adapter
	}

	override fun onDestroyView() {
		super.onDestroyView()
		_binding = null
	}
}
