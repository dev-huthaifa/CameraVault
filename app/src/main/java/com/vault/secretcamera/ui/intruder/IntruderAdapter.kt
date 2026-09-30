package com.vault.secretcamera.ui.intruder

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.vault.secretcamera.R
import com.vault.secretcamera.data.VaultRepository
import com.vault.secretcamera.databinding.ItemIntruderLogBinding
import com.vault.secretcamera.model.IntruderLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class IntruderAdapter(
    private val repository: VaultRepository,
    private val onDeleteClick: (IntruderLog) -> Unit
) : RecyclerView.Adapter<IntruderAdapter.ViewHolder>() {

    private val items = mutableListOf<IntruderLog>()

    fun submitList(newItems: List<IntruderLog>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemIntruderLogBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemIntruderLogBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(log: IntruderLog) {
            val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
            binding.tvIntruderTime.text = dateStr
            binding.tvIntruderReason.text = log.reason

            val file = repository.getIntruderFile(log)
            if (file.exists() && file.length() > 0) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    binding.ivIntruderPhoto.setImageBitmap(bmp)
                } else {
                    binding.ivIntruderPhoto.setImageResource(R.drawable.ic_vault_lock)
                }
            } else {
                binding.ivIntruderPhoto.setImageResource(R.drawable.ic_vault_lock)
            }

            binding.btnDeleteIntruder.setOnClickListener {
                onDeleteClick(log)
            }
        }
    }
}
