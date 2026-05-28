package com.v2ray.ang.ui.luna

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.SystemClock
import android.view.animation.DecelerateInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.databinding.ActivityLunaBinding
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.HelperBaseActivity
import com.v2ray.ang.ui.MainActivity
import com.v2ray.ang.ui.SettingsActivity
import com.v2ray.ang.ui.SubSettingActivity
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max

class LunaActivity : HelperBaseActivity() {

    private val binding by lazy { ActivityLunaBinding.inflate(layoutInflater) }
    private val mainViewModel: MainViewModel by viewModels()

    private var statsJob: Job? = null
    private var timerJob: Job? = null
    private var sessionStartElapsed: Long = 0L
    private var lastQueryTime: Long = 0L

    private val requestVpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == RESULT_OK) startVpn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.lunaBtn.setOnClickListener { onConnectClick() }
        binding.lunaBtnMenu.setOnClickListener { showMenu() }
        binding.lunaBtnServers.setOnClickListener { openServers() }
        binding.lunaIpRow.setOnClickListener { /* could expand for IP details */ }

        mainViewModel.isRunning.observe(this) { isRunning ->
            applyState(isRunning)
        }
        mainViewModel.updateTestResultAction.observe(this) { result ->
            updatePingText(result)
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
        mainViewModel.reloadServerList()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) { }

        // Reflect whatever state the core is already in
        applyState(CoreServiceManager.isRunning())
        refreshServerLabel()

        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            showFirstRunDialog()
        }

        // Subtle entrance fade for the stats card
        binding.lunaStatsCard.alpha = 0f
        binding.lunaStatsCard.translationY = 60f
        binding.lunaStatsCard.animate()
            .alpha(1f).translationY(0f).setDuration(450)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    override fun onResume() {
        super.onResume()
        refreshServerLabel()
        applyState(CoreServiceManager.isRunning())
    }

    private fun onConnectClick() {
        binding.lunaBtn.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            showFirstRunDialog()
            return
        }
        if (CoreServiceManager.isRunning()) {
            CoreServiceManager.stopVService(this)
        } else {
            binding.lunaMoon.setState(MoonView.State.CONNECTING)
            binding.lunaStatusText.text = getString(R.string.luna_status_connecting)
            binding.lunaStatusText.setTextColor(ContextCompat.getColor(this, R.color.luna_cyan))
            if (SettingsManager.isVpnMode()) {
                val intent = VpnService.prepare(this)
                if (intent == null) startVpn() else requestVpnPermission.launch(intent)
            } else {
                startVpn()
            }
        }
    }

    private fun startVpn() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.luna_no_server)
            return
        }
        CoreServiceManager.startVService(this)
    }

    private fun applyState(isRunning: Boolean) {
        if (isRunning) {
            binding.lunaMoon.setState(MoonView.State.ON)
            binding.lunaStatusText.text = getString(R.string.luna_status_protected)
            binding.lunaStatusText.setTextColor(ContextCompat.getColor(this, R.color.luna_status_protected))
            binding.lunaBtn.isSelected = true
            startStatsLoop()
            startTimer()
            updateIpFromCurrentServer(isRunning = true)
            // schedule a ping shortly after connect
            binding.root.postDelayed({ mainViewModel.testCurrentServerRealPing() }, 1500)
        } else {
            binding.lunaMoon.setState(MoonView.State.OFF)
            binding.lunaStatusText.text = getString(R.string.luna_status_unprotected)
            binding.lunaStatusText.setTextColor(ContextCompat.getColor(this, R.color.luna_status_unprotected))
            binding.lunaBtn.isSelected = false
            stopStatsLoop()
            stopTimer()
            resetStats()
            updateIpFromCurrentServer(isRunning = false)
        }
    }

    private fun refreshServerLabel() {
        val guid = MmkvManager.getSelectServer()
        val profile = guid?.let { MmkvManager.decodeServerConfig(it) }
        binding.lunaServerName.text = profile?.remarks?.takeIf { it.isNotBlank() }
            ?: getString(R.string.luna_no_server)
    }

    private fun updateIpFromCurrentServer(isRunning: Boolean) {
        val guid = MmkvManager.getSelectServer()
        val profile = guid?.let { MmkvManager.decodeServerConfig(it) }
        val addr = profile?.let { "${it.server ?: "—"}:${it.serverPort ?: "—"}" } ?: "—"
        binding.lunaIpValue.text = if (isRunning) addr else "—"
    }

    private fun resetStats() {
        binding.lunaTvDownload.text = "0 KB/s"
        binding.lunaTvUpload.text = "0 KB/s"
        binding.lunaTvTime.text = "00:00:00"
        binding.lunaTvPing.text = "— ms"
        binding.lunaTvPing.setTextColor(ContextCompat.getColor(this, R.color.luna_text_primary))
    }

    private fun startStatsLoop() {
        if (statsJob?.isActive == true) return
        lastQueryTime = System.currentTimeMillis()
        statsJob = lifecycleScope.launch {
            // small delay so traffic stats have a chance to accumulate
            delay(800)
            while (isActive) {
                val now = System.currentTimeMillis()
                val deltaSec = max(0.5, (now - lastQueryTime) / 1000.0)
                lastQueryTime = now

                var up = 0L
                var down = 0L
                withContext(Dispatchers.IO) {
                    try {
                        CoreServiceManager.queryAllOutboundTrafficStats().forEach { stat ->
                            if (stat.tag.startsWith(AppConfig.TAG_PROXY)) {
                                when (stat.direction) {
                                    AppConfig.UPLINK -> up += stat.value
                                    AppConfig.DOWNLINK -> down += stat.value
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        // core may be transitioning, ignore
                    }
                }
                val upRate = (up / deltaSec).toLong()
                val downRate = (down / deltaSec).toLong()
                binding.lunaTvDownload.text = downRate.toSpeedString()
                binding.lunaTvUpload.text = upRate.toSpeedString()
                delay(2000)
            }
        }
    }

    private fun stopStatsLoop() {
        statsJob?.cancel()
        statsJob = null
    }

    private fun startTimer() {
        if (timerJob?.isActive == true) return
        sessionStartElapsed = SystemClock.elapsedRealtime()
        timerJob = lifecycleScope.launch {
            while (isActive) {
                val secs = (SystemClock.elapsedRealtime() - sessionStartElapsed) / 1000
                val h = secs / 3600
                val m = (secs % 3600) / 60
                val s = secs % 60
                binding.lunaTvTime.text = String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
                delay(1000)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun updatePingText(raw: String?) {
        val text = raw ?: return
        binding.lunaTvPing.text = text
        // try to parse a number out of "12 ms" or "ping: 12"
        val ms = Regex("(-?\\d+)").find(text)?.groupValues?.firstOrNull()?.toIntOrNull()
        val color = when {
            ms == null || ms < 0 -> R.color.luna_text_primary
            ms < 80 -> R.color.luna_ping_good
            ms < 150 -> R.color.luna_ping_warn
            else -> R.color.luna_ping_bad
        }
        binding.lunaTvPing.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun showMenu() {
        val items = arrayOf(
            getString(R.string.luna_open_server_list),
            getString(R.string.luna_paste_link),
            getString(R.string.luna_settings),
            getString(R.string.luna_open_logs),
            getString(R.string.luna_about),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openServers()
                    1 -> importFromClipboard()
                    2 -> startActivity(Intent(this, SettingsActivity::class.java))
                    3 -> startActivity(Intent(this, com.v2ray.ang.ui.LogcatActivity::class.java))
                    4 -> startActivity(Intent(this, com.v2ray.ang.ui.AboutActivity::class.java))
                }
            }
            .show()
    }

    private fun openServers() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun showFirstRunDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.luna_first_run_title)
            .setMessage(R.string.luna_first_run_msg)
            .setPositiveButton(R.string.luna_paste_clipboard) { _, _ -> importFromClipboard() }
            .setNeutralButton(R.string.luna_enter_manually) { _, _ ->
                // Open subscription setting → user can add manually from there
                startActivity(Intent(this, SubSettingActivity::class.java))
            }
            .setCancelable(true)
            .show()
    }

    private fun importFromClipboard() {
        val clip = try {
            Utils.getClipboard(this)
        } catch (_: Throwable) {
            null
        }
        if (clip.isNullOrBlank()) {
            toastError(R.string.luna_import_failed)
            return
        }
        lifecycleScope.launch {
            val (count, _) = withContext(Dispatchers.IO) {
                try {
                    AngConfigManager.importBatchConfig(clip, "", true)
                } catch (_: Throwable) {
                    Pair(0, 0)
                }
            }
            if (count > 0) {
                toast(R.string.luna_import_ok)
                mainViewModel.reloadServerList()
                refreshServerLabel()
            } else {
                toastError(R.string.luna_import_failed)
            }
        }
    }

    override fun onDestroy() {
        stopStatsLoop()
        stopTimer()
        super.onDestroy()
    }
}
