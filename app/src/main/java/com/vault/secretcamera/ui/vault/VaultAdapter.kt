package com.vault.secretcamera.ui.vault

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.vault.secretcamera.R
import com.vault.secretcamera.data.VaultRepository
import com.vault.secretcamera.databinding.ItemVaultGridBinding
import com.vault.secretcamera.databinding.ItemVaultListBinding
import com.vault.secretcamera.model.VaultCategory
import com.vault.secretcamera.model.VaultItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VaultAdapter(
    private val repository: VaultRepository,
    private val onItemClick: (VaultItem) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<VaultItem>()
    var isSelectionMode = false
        private set

    var currentCategory: VaultCategory = VaultCategory.ALL
        private set

    fun submitList(newItems: List<VaultItem>, category: VaultCategory) {
        items.clear()
        items.addAll(newItems)
        currentCategory = category
        notifyDataSetChanged()
    }

    fun getSelectedItems(): List<VaultItem> = items.filter { it.isSelected }

    fun clearSelection() {
        isSelectionMode = false
        items.forEach { it.isSelected = false }
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    override fun getItemViewType(position: Int): Int {
        val item = items[position]
        return if (item.category == VaultCategory.PHOTOS || item.category == VaultCategory.VIDEOS) {
            VIEW_TYPE_GRID
        } else {
            VIEW_TYPE_LIST
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_GRID) {
            val binding = ItemVaultGridBinding.inflate(inflater, parent, false)
            GridViewHolder(binding)
        } else {
            val binding = ItemVaultListBinding.inflate(inflater, parent, false)
            ListViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is GridViewHolder) {
            holder.bind(item)
        } else if (holder is ListViewHolder) {
            holder.bind(item)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class GridViewHolder(private val binding: ItemVaultGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: VaultItem) {
            binding.ivVideoPlay.visibility = if (item.category == VaultCategory.VIDEOS) View.VISIBLE else View.GONE
            binding.cbSelected.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            binding.cbSelected.isChecked = item.isSelected

            binding.ivThumbnail.tag = item.id

            // 1. Instant cache check (0ms overhead)
            val cachedBmp = repository.getCachedThumbnail(item.id)
            if (cachedBmp != null) {
                binding.ivThumbnail.setImageBitmap(cachedBmp)
            } else {
                binding.ivThumbnail.setImageResource(
                    if (item.category == VaultCategory.VIDEOS) R.drawable.ic_video else R.drawable.ic_photo
                )

                // 2. High-speed lightweight async decrypt (15KB thumbnail only)
                CoroutineScope(Dispatchers.IO).launch {
                    val bmp = repository.getThumbnailBitmap(item)
                    if (bmp != null) {
                        withContext(Dispatchers.Main) {
                            if (binding.ivThumbnail.tag == item.id) {
                                binding.ivThumbnail.setImageBitmap(bmp)
                            }
                        }
                    }
                }
            }

            binding.root.setOnClickListener {
                if (isSelectionMode) {
                    item.isSelected = !item.isSelected
                    binding.cbSelected.isChecked = item.isSelected
                    onSelectionChanged(getSelectedItems().size)
                } else {
                    onItemClick(item)
                }
            }

            binding.root.setOnLongClickListener {
                if (!isSelectionMode) {
                    isSelectionMode = true
                    item.isSelected = true
                    notifyDataSetChanged()
                    onSelectionChanged(getSelectedItems().size)
                }
                true
            }
        }
    }

    inner class ListViewHolder(private val binding: ItemVaultListBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: VaultItem) {
            binding.tvFileName.text = item.originalName
            binding.tvFileSize.text = Formatter.formatFileSize(binding.root.context, item.fileSize)
            binding.tvFileDate.text = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(item.dateAdded))

            val iconRes = when (item.category) {
                VaultCategory.AUDIO -> R.drawable.ic_music
                VaultCategory.DOCUMENTS -> R.drawable.ic_document
                else -> R.drawable.ic_folder
            }
            binding.ivFileIcon.setImageResource(iconRes)

            binding.cbSelected.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            binding.cbSelected.isChecked = item.isSelected

            binding.root.setOnClickListener {
                if (isSelectionMode) {
                    item.isSelected = !item.isSelected
                    binding.cbSelected.isChecked = item.isSelected
                    onSelectionChanged(getSelectedItems().size)
                } else {
                    onItemClick(item)
                }
            }

            binding.root.setOnLongClickListener {
                if (!isSelectionMode) {
                    isSelectionMode = true
                    item.isSelected = true
                    notifyDataSetChanged()
                    onSelectionChanged(getSelectedItems().size)
                }
                true
            }
        }
    }

    companion object {
        const val VIEW_TYPE_GRID = 1
        const val VIEW_TYPE_LIST = 2
    }
}
