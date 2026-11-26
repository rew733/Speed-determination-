package com.rew733.netthrottlemvp

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.rew733.netthrottlemvp.databinding.ItemAppBinding

data class AppEntry(
    val appName: String,
    val packageName: String,
    val icon: Drawable?,
    var isSelected: Boolean = false
)

class AppAdapter(
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

    private var allItems: List<AppEntry> = emptyList()
    private var visibleItems: List<AppEntry> = emptyList()

    fun submitApps(items: List<AppEntry>) {
        allItems = items
        visibleItems = items
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        val q = query.trim().lowercase()
        visibleItems = if (q.isEmpty()) {
            allItems
        } else {
            allItems.filter {
                it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
        notifyDataSetChanged()
    }

    fun getSelectedPackages(): ArrayList<String> {
        return ArrayList(allItems.filter { it.isSelected }.map { it.packageName })
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(visibleItems[position])
    }

    override fun getItemCount(): Int = visibleItems.size

    inner class AppViewHolder(
        private val binding: ItemAppBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AppEntry) {
            binding.tvAppName.text = item.appName
            binding.tvPackageName.text = item.packageName
            binding.ivAppIcon.setImageDrawable(item.icon)

            binding.swSelectApp.setOnCheckedChangeListener(null)
            binding.swSelectApp.isChecked = item.isSelected

            binding.swSelectApp.setOnCheckedChangeListener { _, checked ->
                item.isSelected = checked
                onSelectionChanged()
            }

            binding.root.setOnClickListener {
                val nextState = !item.isSelected
                item.isSelected = nextState
                binding.swSelectApp.isChecked = nextState
                onSelectionChanged()
            }
        }
    }
}
