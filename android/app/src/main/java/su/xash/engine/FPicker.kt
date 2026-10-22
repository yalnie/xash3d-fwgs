package su.xash.engine

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import java.io.File
import java.text.DateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale

class FPicker : Activity() {
	private lateinit var currentDir: File
	private lateinit var adapter: FileArrayAdapter
	private lateinit var delta: ListView
	private lateinit var mSelectBtn: Button

	companion object {
		val sdk: Int = Build.VERSION.SDK_INT
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		val prefs = getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
		val defaultTheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "dynamic_sys" else "fixed_sys"
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

		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_fpicker)

		val toolbar = findViewById<MaterialToolbar>(R.id.fpicker_toolbar)
		toolbar.setNavigationOnClickListener { finish() }

		val path = Environment.getExternalStorageDirectory().toString()
		currentDir = File(path)
		
		delta = findViewById(R.id.FileView)
		mSelectBtn = findViewById(R.id.button_fpicker_select)
		mSelectBtn.setOnClickListener { v -> onFileClick(v) }

		fill(currentDir)
	}

	private fun fill(folder: File) {
		mSelectBtn.isEnabled = false
		Fill(folder).execute()
	}

	@Suppress("DEPRECATION")
	private inner class Fill(private var folder: File) : AsyncTask<Void, Void, List<Item>>() {
		
		@Deprecated("Deprecated in Java")
		override fun doInBackground(vararg voids: Void): List<Item> {
			var dirs = folder.listFiles()
			val dir = ArrayList<Item>()

			while (dirs == null) {
				val parent = folder.parent
				folder = File(parent ?: Environment.getExternalStorageDirectory().toString())
				dirs = folder.listFiles()
			}

			for (ff in dirs) {
				val lastModDate = Date(ff.lastModified())
				val formater = DateFormat.getDateTimeInstance()
				val dateModify = formater.format(lastModDate)
				
				if (ff.isDirectory) {
					var isXashDir = false
					val fbuf = ff.listFiles()
					var buf = 0

					if (fbuf != null && fbuf.size < 20) {
						buf = fbuf.size
						for (valves in fbuf) {
							if (valves.isDirectory && valves.name.contains("valve")) {
								isXashDir = true
							}
						}
					}

					val numItem = resources.getQuantityString(R.plurals.item_plurals, buf, buf)
					dir.add(
						Item(
							ff.name, 
							numItem, 
							dateModify, 
							ff.absolutePath, 
							if (isXashDir) R.drawable.ic_baseline_xash_24px else R.drawable.ic_baseline_folder_24px
						)
					)
				}
			}

			dir.sort()

			if (folder.path.length > 1) {
				dir.add(0, Item("..", getString(R.string.parent_directory), "", folder.parent ?: "", R.drawable.ic_baseline_folder_24px))
			}

			return dir
		}

		@Deprecated("Deprecated in Java")
		override fun onPostExecute(dir: List<Item>) {
			title = getString(R.string.current_dir) + " " + folder.name

			adapter = FileArrayAdapter(this@FPicker, R.layout.row, dir)
			delta.adapter = adapter
			delta.setOnItemClickListener { _, _, position, _ ->
				val o = adapter.getItem(position)
				if (o != null) {
					currentDir = File(o.path)
					fill(currentDir)
				}
			}
			mSelectBtn.isEnabled = true
		}
	}

	fun onFileClick(v: View) {
		Toast.makeText(this, getString(R.string.chosen_path) + " " + currentDir, Toast.LENGTH_SHORT).show()
		val intent = Intent()
		intent.putExtra("GetPath", currentDir.toString())
		setResult(RESULT_OK, intent)
		finish()
	}
}

class FileArrayAdapter(
	private val c: Context,
	private val id: Int,
	private val items: List<Item>
) : ArrayAdapter<Item>(c, id, items) {

	override fun getItem(i: Int): Item? {
		return items[i]
	}

	override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
		var v = convertView
		if (v == null) {
			val vi = c.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
			v = vi.inflate(id, parent, false)
		}

		val finstance = items[position]
		
		val filename = v?.findViewById<TextView>(R.id.filename)
		val fileitems = v?.findViewById<TextView>(R.id.fileitems)
		val filedate = v?.findViewById<TextView>(R.id.filedate)
		val imageicon = v?.findViewById<ImageView>(R.id.fd_Icon1)

		if (finstance != null) {
			val image: Drawable? = ContextCompat.getDrawable(c, finstance.image)
			imageicon?.setImageDrawable(image)

			filename?.text = finstance.name
			fileitems?.text = finstance.data
			filedate?.text = finstance.date
		}
		
		return v!!
	}
}

data class Item(
	val name: String,
	val data: String,
	val date: String,
	val path: String,
	val image: Int
) : Comparable<Item> {
	override fun compareTo(other: Item): Int {
		return this.name.lowercase(Locale.getDefault()).compareTo(other.name.lowercase(Locale.getDefault()))
	}
}
