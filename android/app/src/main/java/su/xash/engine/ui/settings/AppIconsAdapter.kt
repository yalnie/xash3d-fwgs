package su.xash.engine.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import su.xash.engine.R
import su.xash.engine.databinding.ItemAppIconBinding

class AppIconsAdapter(
	private val items: List<IconItem>,
	private var currentSelection: AppIconManager.AppIcon,
	private val onIconSelected: (AppIconManager.AppIcon) -> Unit
) : RecyclerView.Adapter<AppIconsAdapter.ViewHolder>() {

	data class IconItem(
	    val type: AppIconManager.AppIcon,
	    val displayName: String,
	    val drawableRes: Int
	)

	inner class ViewHolder(val binding: ItemAppIconBinding) : RecyclerView.ViewHolder(binding.root)

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
	    val binding = ItemAppIconBinding.inflate(LayoutInflater.from(parent.context), parent, false)
	    return ViewHolder(binding)
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
	    val item = items[position]
	    
	    holder.binding.tvIconName.text = item.displayName
	    holder.binding.imgIconPreview.setImageResource(item.drawableRes)
	    holder.binding.rbSelected.isChecked = (item.type == currentSelection)

	    holder.itemView.setOnClickListener {
	        if (currentSelection != item.type) {
	            val oldSelection = currentSelection
	            currentSelection = item.type
	            
	            val oldIndex = items.indexOfFirst { it.type == oldSelection }
	            if (oldIndex != -1) notifyItemChanged(oldIndex)
	            notifyItemChanged(position)
	            
	            onIconSelected(item.type)
	        }
	    }
	}

	override fun getItemCount(): Int = items.size
}
