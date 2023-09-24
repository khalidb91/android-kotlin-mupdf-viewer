package com.khal.mupdf.viewer.app.ui.outline

import android.app.Activity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.artifex.mupdf.viewer.app.databinding.ItemOutlineBinding
import com.artifex.mupdf.viewer.model.OutlineItem

class OutlineAdapter(
    private val items: List<OutlineItem>,
    private val currentPage: Int = -1,
    private val onItemSelected: ((Int) -> Unit?)? = null,
) : RecyclerView.Adapter<OutlineAdapter.OutlineViewHolder>() {

    class OutlineViewHolder(
        private val binding: ItemOutlineBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: OutlineItem, currentPage: Int, onItemSelected: ((Int) -> Unit?)?) {
            binding.label.text = item.title.trim()
            binding.root.setOnClickListener {
                onItemSelected?.invoke(Activity.RESULT_FIRST_USER + item.page)
            }
        }

    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OutlineViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemOutlineBinding.inflate(inflater)
        return OutlineViewHolder(binding)
    }

    override fun getItemCount(): Int {
        return items.size
    }

    override fun onBindViewHolder(holder: OutlineViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, currentPage, onItemSelected)
    }

}