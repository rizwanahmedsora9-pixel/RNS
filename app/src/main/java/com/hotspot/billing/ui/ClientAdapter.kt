package com.hotspot.billing.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.hotspot.billing.R
import com.hotspot.billing.db.Voucher

/** One device currently on the LAN, authorized (voucher) or waiting at the portal. */
data class ClientRow(
    val mac: String,
    val ip: String,
    val hostname: String,
    val label: String?,
    val voucher: Voucher?
)

/** "Online now" list. */
class ClientAdapter(
    private var items: List<ClientRow> = emptyList(),
    private val onProfile: (ClientRow) -> Unit,
    private val onKick: (ClientRow) -> Unit
) : RecyclerView.Adapter<ClientAdapter.VH>() {

    fun submit(newItems: List<ClientRow>) {
        items = newItems
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.c_title)
        val sub: TextView = v.findViewById(R.id.c_sub)
        val state: TextView = v.findViewById(R.id.c_state)
        val profile: Button = v.findViewById(R.id.c_profile)
        val kick: Button = v.findViewById(R.id.c_kick)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_client, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        val ctx = holder.itemView.context

        val name = row.label
            ?: row.hostname.takeIf { it.isNotEmpty() }
            ?: row.mac
        holder.title.text = name
        holder.sub.text = "${row.mac} · ${row.ip}"

        val voucher = row.voucher
        if (voucher != null) {
            holder.state.text = "${voucher.planName} · ${UiFmt.remaining(voucher.expiresAt)}"
            holder.state.setTextColor(ContextCompat.getColor(ctx, R.color.green))
            holder.kick.visibility = View.VISIBLE
        } else {
            holder.state.text = "connected - waiting at the portal for a voucher"
            holder.state.setTextColor(ContextCompat.getColor(ctx, R.color.amber))
            holder.kick.visibility = View.GONE
        }

        holder.profile.setOnClickListener { onProfile(row) }
        holder.kick.setOnClickListener { onKick(row) }
    }
}
