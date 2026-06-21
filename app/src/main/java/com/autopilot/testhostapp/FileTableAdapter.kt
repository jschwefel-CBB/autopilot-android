package com.autopilot.testhostapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FileTableAdapter(
    private val files: List<String>,
    private val onRowClick: (String) -> Unit
) : RecyclerView.Adapter<FileTableAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val label: TextView = itemView.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val filename = files[position]
        holder.label.text = filename
        // Each row item's contentDescription is "row-<filename>"
        holder.itemView.contentDescription = "row-$filename"
        holder.itemView.setOnClickListener {
            onRowClick(filename)
        }
    }

    override fun getItemCount(): Int = files.size
}
