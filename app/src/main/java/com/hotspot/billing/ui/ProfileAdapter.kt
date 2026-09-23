package com.hotspot.billing.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hotspot.billing.R
import com.hotspot.billing.db.DeviceProfile

/** Saved user profiles - one row per known device MAC. */
class ProfileAdapter(
    private var items: List<DeviceProfile> = emptyList(),
    private val onEdit: (DeviceProfile) -> Unit,
    private val onDelete: (DeviceProfile) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.VH>() {

    fun submit(newItems: List<DeviceProfile>) {
        items = newItems
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.p_title)
        val sub: TextView = v.findViewById(R.id.p_sub)
        val edit: Button = v.findViewById(R.id.p_edit)
        val delete: Button = v.findViewById(R.id.p_delete)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_profile, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]

        val title = p.label?.takeIf { it.isNotBlank() }
            ?: p.hostname?.takeIf { it.isNotBlank() }
            ?: p.mac
        holder.title.text = title

        val bits = mutableListOf<String>()
        p.phone?.takeIf { it.isNotBlank() }?.let { bits.add(it) }
        p.note?.takeIf { it.isNotBlank() }?.let { bits.add(it) }
        bits.add(p.mac)
        bits.add("seen ${UiFmt.time(p.lastSeen)}")
        holder.sub.text = bits.joinToString(" · ")

        holder.edit.setOnClickListener { onEdit(p) }
        holder.delete.setOnClickListener { onDelete(p) }
    }
}
