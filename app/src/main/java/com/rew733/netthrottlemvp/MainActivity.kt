package com.rew733.netthrottlemvp

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.rew733.netthrottlemvp.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: AppAdapter
    private var isThrottlingActive = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            launchThrottleService()
        } else {
            Toast.makeText(this, "لم يتم منح إذن VPN", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupControls()
        loadInstalledApps()
    }

    private fun setupRecyclerView() {
        adapter = AppAdapter {
            updateUiState()
        }
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter
    }

    private fun setupControls() {
        binding.etSearch.doAfterTextChanged { text ->
            adapter.filter(text?.toString().orEmpty())
        }

        binding.btnToggle.setOnClickListener {
            if (isThrottlingActive) {
                stopThrottleService()
            } else {
                requestVpnAndStart()
            }
        }

        updateUiState()
    }

    private fun loadInstalledApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = packageManager
                val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledApplications(0)
                }

                installed
                    .filter { appInfo ->
                        appInfo.packageName != packageName &&
                            (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                    }
                    .map { appInfo ->
                        AppEntry(
                            appName = pm.getApplicationLabel(appInfo).toString(),
                            packageName = appInfo.packageName,
                            icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull(),
                            isSelected = false
                        )
                    }
                    .sortedBy { it.appName.lowercase() }
            }
            adapter.submitApps(apps)
        }
    }

    private fun requestVpnAndStart() {
        val selected = adapter.getSelectedPackages()
        if (selected.isEmpty()) {
            Toast.makeText(this, "اختر تطبيقاً واحداً على الأقل أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            launchThrottleService()
        }
    }

    private fun launchThrottleService() {
        val selected = adapter.getSelectedPackages()
        if (selected.isEmpty()) return

        val intent = Intent(this, ThrottleVpnService::class.java).apply {
            action = ThrottleVpnService.ACTION_START
            putStringArrayListExtra(ThrottleVpnService.EXTRA_PACKAGES, selected)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        isThrottlingActive = true
        updateUiState()
    }

    private fun stopThrottleService() {
        val intent = Intent(this, ThrottleVpnService::class.java).apply {
            action = ThrottleVpnService.ACTION_STOP
        }
        startService(intent)
        isThrottlingActive = false
        updateUiState()
    }

    private fun updateUiState() {
        val count = adapter.getSelectedPackages().size
        if (isThrottlingActive) {
            binding.btnToggle.text = getString(R.string.stop_throttle)
            binding.tvStatus.text = "${getString(R.string.status_active)} ($count)"
        } else {
            binding.btnToggle.text = getString(R.string.start_throttle)
            binding.tvStatus.text = if (count > 0) {
                "جاهز — تم تحديد $count تطبيق"
            } else {
                getString(R.string.status_idle)
            }
        }
    }
}
