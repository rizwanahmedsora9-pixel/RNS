package com.hotspot.billing.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.hotspot.billing.R
import com.hotspot.billing.db.Voucher
import com.hotspot.billing.db.VoucherStatus

/** Admin list of vouchers: status, binding, remaining time, per-row actions. */
class VoucherAdapter(
    private var items: List<Voucher> = emptyList(),
    private val onCopy: (Voucher) -> Unit,
    private val onShare: (Voucher) -> Unit,
    private val onExpire: (Voucher) -> Unit,
    private val onDelete: (Voucher) -> Unit
) : RecyclerView.Adapter<VoucherAdapter.VH>() {

    fun submit(newItems: List<Voucher>) {
        items = newItems
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val code: TextView = v.findViewById(R.id.v_code)
        val status: TextView = v.findViewById(R.id.v_status)
        val plan: TextView = v.findViewById(R.id.v_plan)
        val binding: TextView = v.findViewById(R.id.v_binding)
        val copy: Button = v.findViewById(R.id.v_copy)
        val share: Button = v.findViewById(R.id.v_share)
        val expire: Button = v.findViewById(R.id.v_expire)
        val delete: Button = v.findViewById(R.id.v_delete)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_voucher, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val v = items[position]
        val ctx = holder.itemView.context

        holder.code.text = v.code
        holder.plan.text = "${v.planName} · ${UiFmt.minutes(v.durationMinutes)} · " +
            "${v.rateKbit}/${v.ceilKbit} kbit/s"

        when (v.status) {
            VoucherStatus.UNUSED -> {
                holder.status.text = "UNUSED"
                holder.status.setTextColor(ContextCompat.getColor(ctx, R.color.amber))
                holder.binding.visibility = View.GONE
            }
            VoucherStatus.ACTIVE -> {
                holder.status.text = "ACTIVE"
                holder.status.setTextColor(ContextCompat.getColor(ctx, R.color.green))
                holder.binding.visibility = View.VISIBLE
                holder.binding.text = "${v.boundMac ?: "?"} / ${v.assignedIp ?: "?"} · ${UiFmt.remaining(v.expiresAt)}"
            }
            VoucherStatus.EXPIRED -> {
                holder.status.text = "EXPIRED"
                holder.status.setTextColor(ContextCompat.getColor(ctx, R.color.red))
                holder.binding.visibility =
                    if (v.boundMac != null || v.assignedIp != null) View.VISIBLE else View.GONE
                holder.binding.text = "${v.boundMac ?: "-"} / ${v.assignedIp ?: "-"}"
            }
        }

        holder.copy.setOnClickListener { onCopy(v) }
        holder.share.setOnClickListener { onShare(v) }
        holder.expire.setOnClickListener { onExpire(v) }
        holder.delete.setOnClickListener { onDelete(v) }
    }
}
