package com.vpnauto.manager.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.vpnauto.manager.databinding.ActivityMainTabsBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainTabsBinding
    val viewModel: MainViewModel by viewModels()

    private val vpnPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (it.resultCode == RESULT_OK) viewModel.onVpnPermissionGranted()
        else viewModel.onVpnPermissionDenied() }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainTabsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        requestNotificationPermission()
        setupTabs()
        viewModel.vpnPermissionNeeded.observe(this) { intent ->
            if (intent != null) vpnPermLauncher.launch(intent)
        }
        // Handle intent from widget/tile
        if (intent?.getStringExtra("action") == "connect") {
            viewModel.connectToBest()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun setupTabs() {
        binding.viewPager.adapter = PagerAdapter(this)
        binding.viewPager.offscreenPageLimit = 2
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, pos ->
            tab.text = when (pos) {
                0 -> "🏠"
                1 -> "🔍"
                2 -> "📊"
                3 -> "⚙️"
                4 -> "📋"
                else -> ""
            }
        }.attach()
        viewModel.vpnState.observe(this) { state ->
            if (state == VpnConnState.CONNECTING) switchToLog()
        }
    }

    fun switchToLog()    = binding.viewPager.setCurrentItem(4, true)
    fun switchToSearch() = binding.viewPager.setCurrentItem(1, true)
    fun switchToStats()  = binding.viewPager.setCurrentItem(2, true)

    private class PagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount() = 5
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> MainFragment()
            1 -> SearchFragment()
            2 -> StatsFragment()
            3 -> SettingsFragment()
            4 -> LogFragment()
            else -> MainFragment()
        }
    }
}
