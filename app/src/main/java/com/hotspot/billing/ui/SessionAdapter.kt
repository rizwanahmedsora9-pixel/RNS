package com.hotspot.billing.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hotspot.billing.R
import com.hotspot.billing.db.UserSession

/** Read-only session history. [labels] maps MAC -> operator-facing device name. */
class SessionAdapter(
    private var items: List<UserSession> = emptyList(),
    private var labels: Map<String, String> = emptyMap()
) : RecyclerView.Adapter<SessionAdapter.VH>() {

    fun submit(newItems: List<UserSession>, newLabels: Map<String, String>) {
        items = newItems
        labels = newLabels
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.s_title)
        val sub: TextView = v.findViewById(R.id.s_sub)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        val name = labels[s.mac] ?: s.deviceLabel ?: s.mac
        holder.title.text = "$name · ${s.voucherCode}"
        val range = "${UiFmt.time(s.connectedAt)} - " +
            (s.disconnectedAt?.let { UiFmt.time(it) } ?: "still online")
        holder.sub.text = "${s.ip} · $range"
    }
}
