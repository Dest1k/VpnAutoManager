package com.vpnauto.manager.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vpnauto.manager.R
import com.vpnauto.manager.databinding.FragmentSearchBinding
import com.vpnauto.manager.model.ServerConfig
import com.vpnauto.manager.util.PingTester
import com.vpnauto.manager.util.FoundSubscription
import com.vpnauto.manager.util.SubscriptionFinder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var adapter: FoundSubAdapter
    private var searchJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = FoundSubAdapter(
            onConnect = { sub -> connectToSubscription(sub) },
            onAddToList = { sub -> addToSubscriptions(sub) },
            onPing = { sub, holder -> pingSubscription(sub, holder) }
        )
        binding.rvSearchResults.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@SearchFragment.adapter
        }

        binding.btnSearch.setOnClickListener {
            if (searchJob?.isActive == true) return@setOnClickListener
            startSearch()
        }

        binding.btnCancelSearch.setOnClickListener {
            searchJob?.cancel()
            searchJob = null
            showIdle()
        }
    }

    // ─── Поиск ───────────────────────────────────────────────────
    private fun startSearch() {
        adapter.submitList(emptyList())
        binding.layoutProgress.visibility = View.VISIBLE
        binding.btnSearch.isEnabled = false
        binding.btnCancelSearch.visibility = View.VISIBLE
        binding.tvSearchSummary.visibility = View.GONE
        binding.progressSearch.max = 100
        binding.progressSearch.progress = 0

        val found = mutableListOf<FoundSubscription>()

        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            SubscriptionFinder.findAll(
                onProgress = { checked, total, name ->
                    val pct = (checked * 100 / total)
                    binding.progressSearch.progress = pct
                    binding.tvSearchStatus.text = "Проверяем ($checked/$total): $name"
                },
                onResult = { sub ->
                    found.add(sub)
                    val sorted = found.sortedByDescending { it.serverCount }
                    binding.tvSearchSummary.visibility = View.VISIBLE
                    binding.tvSearchSummary.text =
                        "Найдено: ${found.size} источников, " +
                        "${found.sumOf { it.serverCount }} серверов"
                    adapter.submitList(sorted.toList())
                }
            )
            showIdle()
            val total = found.sumOf { it.serverCount }
            binding.tvSearchSummary.visibility = View.VISIBLE
            binding.tvSearchSummary.text =
                "Поиск завершён: ${found.size} источников, $total серверов"
        }
    }

    private fun showIdle() {
        binding.layoutProgress.visibility = View.GONE
        binding.btnSearch.isEnabled = true
        binding.btnCancelSearch.visibility = View.GONE
    }

    // ─── Действия ────────────────────────────────────────────────

    private fun connectToSubscription(sub: FoundSubscription) {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.tvSearchStatus.text = "Загружаем серверы из ${sub.sourceName}..."
            binding.layoutProgress.visibility = View.VISIBLE

            val servers = SubscriptionFinder.fetchServers(sub)
            if (servers.isEmpty()) {
                Toast.makeText(requireContext(), "Нет доступных серверов", Toast.LENGTH_SHORT).show()
                binding.layoutProgress.visibility = View.GONE
                return@launch
            }

            binding.tvSearchStatus.text = "Пингуем ${servers.size} серверов..."
            val tested = PingTester.testServers(servers) { done, total ->
                binding.tvSearchStatus.text = "Пинг: $done / $total"
            }
            binding.layoutProgress.visibility = View.GONE

            val best = tested.firstOrNull { it.isReachable }
            if (best == null) {
                Toast.makeText(requireContext(), "Нет доступных серверов после пинга", Toast.LENGTH_LONG).show()
                return@launch
            }

            // Передать лучший сервер в ViewModel и подключиться
            viewModel.connectToServer(best)
            // Переключиться на вкладку лога
            (activity as? MainActivity)?.switchToLog()

            Toast.makeText(
                requireContext(),
                "Подключаемся: ${best.name} (${best.pingMs}ms)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun addToSubscriptions(sub: FoundSubscription) {
        viewModel.addCustomSubscription(sub.url, sub.description)
        Toast.makeText(
            requireContext(),
            "Добавлено: ${sub.sourceName}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun pingSubscription(sub: FoundSubscription, holder: FoundSubAdapter.ViewHolder) {
        viewLifecycleOwner.lifecycleScope.launch {
            holder.tvServerCount.text = "⏳ загружаем..."
            val servers = SubscriptionFinder.fetchServers(sub)
            if (servers.isEmpty()) {
                holder.tvServerCount.text = "0 серверов"
                return@launch
            }
            holder.tvServerCount.text = "⏳ пинг..."
            val tested = PingTester.testServers(servers.take(30))
            val reachable = tested.count { it.isReachable }
            holder.tvServerCount.text = "$reachable/${servers.size} живых"
        }
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        super.onDestroyView()
        _binding = null
    }
}

// ─── Адаптер ─────────────────────────────────────────────────────

class FoundSubAdapter(
    private val onConnect: (FoundSubscription) -> Unit,
    private val onAddToList: (FoundSubscription) -> Unit,
    private val onPing: (FoundSubscription, FoundSubAdapter.ViewHolder) -> Unit
) : ListAdapter<FoundSubscription, FoundSubAdapter.ViewHolder>(Diff) {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSourceName: TextView  = view.findViewById(R.id.tvSourceName)
        val tvDescription: TextView = view.findViewById(R.id.tvDescription)
        val tvServerCount: TextView = view.findViewById(R.id.tvServerCount)
        val btnConnect: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btnConnect)
        val btnAdd: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btnAddToList)
        val btnPing: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btnPing)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_found_subscription, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val sub = getItem(position)
        holder.tvSourceName.text = sub.sourceName
        holder.tvDescription.text = sub.description

        if (sub.serverCount > 0) {
            holder.tvServerCount.text = "${sub.serverCount} серверов"
            holder.tvServerCount.setBackgroundResource(R.drawable.bg_badge_green)
        } else {
            holder.tvServerCount.text = "проверяем..."
            holder.tvServerCount.setBackgroundResource(R.drawable.bg_protocol_label)
        }

        holder.btnConnect.setOnClickListener { onConnect(sub) }
        holder.btnAdd.setOnClickListener { onAddToList(sub) }
        holder.btnPing.setOnClickListener { onPing(sub, holder) }
    }

    companion object Diff : DiffUtil.ItemCallback<FoundSubscription>() {
        override fun areItemsTheSame(a: FoundSubscription, b: FoundSubscription) = a.url == b.url
        override fun areContentsTheSame(a: FoundSubscription, b: FoundSubscription) = a == b
    }
}
