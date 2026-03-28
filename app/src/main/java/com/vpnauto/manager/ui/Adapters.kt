package com.vpnauto.manager.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vpnauto.manager.R
import com.vpnauto.manager.model.ServerConfig
import com.vpnauto.manager.model.Subscription

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

        // Пинг с цветовым кодированием
        if (server.pingMs > 0) {
            holder.tvPing.text = "${server.pingMs}ms"
            holder.tvPing.setTextColor(getPingColor(holder.tvPing.context, server.pingMs))
        } else if (!server.isReachable && server.pingMs == -1L) {
            holder.tvPing.text = "—"
            holder.tvPing.setTextColor(
                holder.tvPing.context.getColor(R.color.ping_unknown))
        } else {
            holder.tvPing.text = "✗"
            holder.tvPing.setTextColor(
                holder.tvPing.context.getColor(R.color.ping_bad))
        }

        holder.btnConnect.setOnClickListener { onConnect(server) }

        // Выделить топ-3 доступных зеленоватым фоном
        if (server.isReachable && position < 3) {
            holder.card.setCardBackgroundColor(
                holder.card.context.getColor(R.color.best_server_bg))
        } else {
            holder.card.setCardBackgroundColor(
                holder.card.context.getColor(R.color.default_card_bg))
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

class SubscriptionAdapter(
    private val onToggle: (Subscription, Boolean) -> Unit,
    private val onImport: (Subscription) -> Unit,
    private val onPing: (Subscription, ViewHolder) -> Unit = { _, _ -> },
    private val onSpeedTest: (Subscription, ViewHolder) -> Unit = { _, _ -> }
) : ListAdapter<Subscription, SubscriptionAdapter.ViewHolder>(SubDiffCallback) {

    // Хранит последние результаты пинга по id подписки
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
        val btnSpeedTest: com.google.android.material.button.MaterialButton =
            view.findViewById(R.id.btnSubSpeedTest)
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
        holder.btnSpeedTest.setOnClickListener { onSpeedTest(sub, holder) }

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
