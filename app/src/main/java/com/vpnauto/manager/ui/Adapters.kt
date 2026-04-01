package com.vpnauto.manager.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vpnauto.manager.R
import com.vpnauto.manager.model.ServerConfig
import com.vpnauto.manager.model.Subscription

// ─── ServerListAdapter (группированный список, аналог v2rayNG) ───────────────

private const val TYPE_SUB_HEADER  = 0
private const val TYPE_SUB_SERVER  = 1
private const val TYPE_PING_HEADER = 2
private const val TYPE_PING_SERVER = 3

class ServerListAdapter(
    private val onConnect: (ServerConfig) -> Unit,
    private val onToggleGroup: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<ServerListItem> = emptyList()

    fun submitItems(newItems: List<ServerListItem>) {
        val old = items
        items = newItems
        // Простой DiffUtil по позициям (достаточно для этого кейса)
        val cb = object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(op: Int, np: Int): Boolean {
                val o = old[op]; val n = newItems[np]
                if (o::class != n::class) return false
                return when {
                    o is ServerListItem.SubHeader  && n is ServerListItem.SubHeader  -> o.sub.id == n.sub.id
                    o is ServerListItem.SubServer  && n is ServerListItem.SubServer  -> o.server.raw == n.server.raw
                    o is ServerListItem.PingedHeader && n is ServerListItem.PingedHeader -> true
                    o is ServerListItem.PingedServer && n is ServerListItem.PingedServer -> o.server.raw == n.server.raw
                    else -> false
                }
            }
            override fun areContentsTheSame(op: Int, np: Int): Boolean {
                val o = old[op]; val n = newItems[np]
                return when {
                    o is ServerListItem.SubHeader  && n is ServerListItem.SubHeader  ->
                        o.isExpanded == n.isExpanded && o.serverCount == n.serverCount
                    o is ServerListItem.SubServer  && n is ServerListItem.SubServer  ->
                        o.server.pingMs == n.server.pingMs && o.server.isReachable == n.server.isReachable
                    o is ServerListItem.PingedServer && n is ServerListItem.PingedServer ->
                        o.server.pingMs == n.server.pingMs
                    else -> true
                }
            }
        }
        DiffUtil.calculateDiff(cb).dispatchUpdatesTo(this)
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is ServerListItem.SubHeader   -> TYPE_SUB_HEADER
        is ServerListItem.SubServer   -> TYPE_SUB_SERVER
        is ServerListItem.PingedHeader -> TYPE_PING_HEADER
        is ServerListItem.PingedServer -> TYPE_PING_SERVER
    }

    // ─── ViewHolders ───────────────────────────────────────────────────────────

    inner class SubHeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvGroupName)
        val tvCount: TextView = view.findViewById(R.id.tvGroupCount)
        val tvArrow: TextView = view.findViewById(R.id.tvGroupArrow)
    }

    inner class SubServerHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvServerName)
        val tvHost: TextView = view.findViewById(R.id.tvServerHost)
        val tvProtocol: TextView = view.findViewById(R.id.tvProtocol)
        val tvPing: TextView = view.findViewById(R.id.tvPing)
        val btnConnect: android.widget.Button = view.findViewById(R.id.btnConnect)
        val card: CardView = view.findViewById(R.id.cardServer)
    }

    inner class PingHeaderHolder(view: View) : RecyclerView.ViewHolder(view)

    inner class PingServerHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvServerName)
        val tvHost: TextView = view.findViewById(R.id.tvServerHost)
        val tvProtocol: TextView = view.findViewById(R.id.tvProtocol)
        val tvPing: TextView = view.findViewById(R.id.tvPing)
        val btnConnect: android.widget.Button = view.findViewById(R.id.btnConnect)
        val card: CardView = view.findViewById(R.id.cardServer)
    }

    // ─── Create / Bind ─────────────────────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SUB_HEADER  -> SubHeaderHolder(inf.inflate(R.layout.item_server_group_header, parent, false))
            TYPE_PING_HEADER -> PingHeaderHolder(inf.inflate(R.layout.item_pinged_header, parent, false))
            TYPE_PING_SERVER -> PingServerHolder(inf.inflate(R.layout.item_server, parent, false))
            else             -> SubServerHolder(inf.inflate(R.layout.item_server, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ServerListItem.SubHeader -> {
                val h = holder as SubHeaderHolder
                h.tvName.text = item.sub.name
                val loaded = if (item.sub.serverCount > 0) "${item.sub.serverCount} конфигов" else "не загружена"
                h.tvCount.text = if (item.serverCount > 0) "$loaded · ${item.serverCount} непроверенных" else loaded
                h.tvArrow.text = if (item.isExpanded) "▲" else "▼"
                h.itemView.setOnClickListener { onToggleGroup(item.sub.id) }
            }
            is ServerListItem.SubServer -> {
                val h = holder as SubServerHolder
                bindServer(h.tvName, h.tvHost, h.tvProtocol, h.tvPing, h.btnConnect, h.card, item.server, position)
            }
            is ServerListItem.PingedHeader -> { /* статичный layout */ }
            is ServerListItem.PingedServer -> {
                val h = holder as PingServerHolder
                bindServer(h.tvName, h.tvHost, h.tvProtocol, h.tvPing, h.btnConnect, h.card, item.server, position)
            }
        }
    }

    private fun bindServer(
        tvName: TextView, tvHost: TextView, tvProtocol: TextView,
        tvPing: TextView, btnConnect: android.widget.Button, card: CardView,
        server: ServerConfig, position: Int
    ) {
        tvName.text = server.name.take(40)
        tvHost.text = "${server.host}:${server.port}"
        tvProtocol.text = server.protocol.uppercase()

        if (server.isReachable && server.pingMs > 0) {
            tvPing.text = "${server.pingMs}ms"
            tvPing.setTextColor(getPingColor(tvPing.context, server.pingMs))
        } else if (!server.isReachable && server.pingMs == -1L) {
            tvPing.text = "—"
            tvPing.setTextColor(tvPing.context.getColor(R.color.ping_unknown))
        } else {
            tvPing.text = "✗"
            tvPing.setTextColor(tvPing.context.getColor(R.color.ping_bad))
        }

        btnConnect.setOnClickListener { onConnect(server) }

        // Выделяем топ-3 пропингованных зелёным
        if (server.isReachable && position < 3) {
            card.setCardBackgroundColor(card.context.getColor(R.color.best_server_bg))
        } else {
            card.setCardBackgroundColor(card.context.getColor(R.color.default_card_bg))
        }
    }

    private fun getPingColor(context: android.content.Context, ping: Long) = when {
        ping < 150  -> context.getColor(R.color.ping_good)
        ping < 400  -> context.getColor(R.color.ping_ok)
        ping < 800  -> context.getColor(R.color.ping_warn)
        else        -> context.getColor(R.color.ping_bad)
    }
}

// ─── SubscriptionAdapter ──────────────────────────────────────────────────────

class SubscriptionAdapter(
    private val onToggle:    (Subscription, Boolean) -> Unit,
    private val onImport:    (Subscription) -> Unit,
    private val onDelete:    (Subscription) -> Unit = { _ -> },
    private val onClear:     (Subscription) -> Unit = { _ -> },
    private val onPing:      (Subscription, ViewHolder) -> Unit = { _, _ -> },
    private val onSpeedTest: (Subscription, ViewHolder) -> Unit = { _, _ -> }
) : ListAdapter<Subscription, SubscriptionAdapter.ViewHolder>(SubDiffCallback) {

    private val pingResults = mutableMapOf<String, String>()

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvSubName)
        val tvUrl: TextView = view.findViewById(R.id.tvSubUrl)
        val tvCount: TextView = view.findViewById(R.id.tvSubCount)
        val tvLastUpdate: TextView = view.findViewById(R.id.tvSubLastUpdate)
        val tvPingResult: TextView = view.findViewById(R.id.tvSubPingResult)
        val toggle: com.google.android.material.switchmaterial.SwitchMaterial =
            view.findViewById(R.id.switchSubEnabled)
        val btnImport: android.widget.Button = view.findViewById(R.id.btnSubImport)
        val btnPing: com.google.android.material.button.MaterialButton =
            view.findViewById(R.id.btnSubPing)
        val btnSpeedTest: com.google.android.material.button.MaterialButton? =
            view.findViewById(R.id.btnSubSpeedTest)
        val btnDelete: com.google.android.material.button.MaterialButton? =
            view.findViewById(R.id.btnSubDelete)
        val btnClear: com.google.android.material.button.MaterialButton? =
            view.findViewById(R.id.btnSubClear)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_subscription, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val sub = getItem(position)
        holder.tvName.text = sub.name
        holder.tvUrl.text = sub.url.substringAfterLast("/")
        holder.tvCount.text = if (sub.serverCount > 0) "${sub.serverCount} конфигов" else "не загружена"
        holder.tvLastUpdate.text = if (sub.lastUpdated > 0) {
            java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(sub.lastUpdated))
        } else "—"
        holder.toggle.setOnCheckedChangeListener(null)
        holder.toggle.isChecked = sub.isEnabled
        holder.toggle.setOnCheckedChangeListener { _, checked -> onToggle(sub, checked) }
        holder.btnImport.setOnClickListener { onImport(sub) }
        holder.btnPing.setOnClickListener { onPing(sub, holder) }
        holder.btnSpeedTest?.setOnClickListener { onSpeedTest(sub, holder) }
        holder.btnDelete?.setOnClickListener { onDelete(sub) }
        holder.btnClear?.setOnClickListener { onClear(sub) }

        val result = pingResults[sub.id]
        if (result != null) {
            holder.tvPingResult.visibility = View.VISIBLE
            holder.tvPingResult.text = result
        } else {
            holder.tvPingResult.visibility = View.GONE
        }
    }

    fun setPingResult(subId: String, result: String) {
        pingResults[subId] = result
        val pos = currentList.indexOfFirst { it.id == subId }
        if (pos >= 0) notifyItemChanged(pos)
    }

    companion object SubDiffCallback : DiffUtil.ItemCallback<Subscription>() {
        override fun areItemsTheSame(a: Subscription, b: Subscription) = a.id == b.id
        override fun areContentsTheSame(a: Subscription, b: Subscription) = a == b
    }
}

// ─── (Legacy) ServerAdapter — оставлен для обратной совместимости ─────────────

class ServerAdapter(
    private val onConnect: (ServerConfig) -> Unit,
    private val onPing: (ServerConfig) -> Unit
) : ListAdapter<ServerConfig, ServerAdapter.ViewHolder>(DiffCallback) {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: CardView = view.findViewById(R.id.cardServer)
        val tvName: TextView = view.findViewById(R.id.tvServerName)
        val tvHost: TextView = view.findViewById(R.id.tvServerHost)
        val tvProtocol: TextView = view.findViewById(R.id.tvProtocol)
        val tvPing: TextView = view.findViewById(R.id.tvPing)
        val btnConnect: android.widget.Button = view.findViewById(R.id.btnConnect)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_server, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val server = getItem(position)
        holder.tvName.text = server.name.take(40)
        holder.tvHost.text = "${server.host}:${server.port}"
        holder.tvProtocol.text = server.protocol.uppercase()
        if (server.pingMs > 0) {
            holder.tvPing.text = "${server.pingMs}ms"
            holder.tvPing.setTextColor(getPingColor(holder.tvPing.context, server.pingMs))
        } else if (!server.isReachable && server.pingMs == -1L) {
            holder.tvPing.text = "—"
            holder.tvPing.setTextColor(holder.tvPing.context.getColor(R.color.ping_unknown))
        } else {
            holder.tvPing.text = "✗"
            holder.tvPing.setTextColor(holder.tvPing.context.getColor(R.color.ping_bad))
        }
        holder.btnConnect.setOnClickListener { onConnect(server) }
        if (server.isReachable && position < 3) {
            holder.card.setCardBackgroundColor(holder.card.context.getColor(R.color.best_server_bg))
        } else {
            holder.card.setCardBackgroundColor(holder.card.context.getColor(R.color.default_card_bg))
        }
    }

    private fun getPingColor(context: android.content.Context, ping: Long) = when {
        ping < 150  -> context.getColor(R.color.ping_good)
        ping < 400  -> context.getColor(R.color.ping_ok)
        ping < 800  -> context.getColor(R.color.ping_warn)
        else        -> context.getColor(R.color.ping_bad)
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ServerConfig>() {
        override fun areItemsTheSame(a: ServerConfig, b: ServerConfig) = a.raw == b.raw
        override fun areContentsTheSame(a: ServerConfig, b: ServerConfig) =
            a.pingMs == b.pingMs && a.isReachable == b.isReachable
    }
}
