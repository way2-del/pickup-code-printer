package com.pickup.print

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dothantech.printer.IDzPrinter.PrinterAddress
import com.pickup.print.databinding.ItemPrinterBinding

class PrinterAdapter(
    private val onConnect: (PrinterAddress) -> Unit,
    private val onSetDefault: (PrinterAddress) -> Unit
) : ListAdapter<PrinterAddress, PrinterAdapter.VH>(DIFF) {

    private var connectedKey: String? = null
    private var defaultKey: String? = null

    fun setConnected(address: PrinterAddress?) {
        connectedKey = address?.let { keyOf(it) }
        notifyDataSetChanged()
    }

    fun setDefaultKey(key: String?) {
        defaultKey = key
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPrinterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val key = keyOf(item)
        holder.bind(item, key == connectedKey, key == defaultKey)
    }

    inner class VH(private val binding: ItemPrinterBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PrinterAddress, connected: Boolean, isDefault: Boolean) {
            binding.root.isSelected = connected
            val name = PrinterManager.displayName(item)
            binding.tvName.text = if (isDefault) "★ $name" else name
            binding.tvAddress.text = item.macAddress ?: ""

            binding.btnDefault.text = if (isDefault) "默认中" else "设默认"
            binding.btnDefault.isEnabled = !isDefault
            binding.btnDefault.setOnClickListener { onSetDefault(item) }

            if (connected) {
                binding.btnConnect.text = "已连接"
                binding.btnConnect.isEnabled = false
                binding.btnConnect.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.accent)
                )
            } else {
                binding.btnConnect.text = "连接"
                binding.btnConnect.isEnabled = true
                binding.btnConnect.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.ink)
                )
                binding.btnConnect.setOnClickListener { onConnect(item) }
            }
        }
    }

    companion object {
        fun keyOf(address: PrinterAddress): String =
            address.macAddress?.takeIf { it.isNotBlank() } ?: address.shownName.orEmpty()

        private val DIFF = object : DiffUtil.ItemCallback<PrinterAddress>() {
            override fun areItemsTheSame(oldItem: PrinterAddress, newItem: PrinterAddress): Boolean =
                keyOf(oldItem) == keyOf(newItem)

            override fun areContentsTheSame(oldItem: PrinterAddress, newItem: PrinterAddress): Boolean =
                oldItem.shownName == newItem.shownName && oldItem.macAddress == newItem.macAddress
        }
    }
}
