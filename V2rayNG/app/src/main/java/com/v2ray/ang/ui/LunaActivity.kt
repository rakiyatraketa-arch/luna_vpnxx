package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.net.TrafficStats
import android.net.VpnService
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Luna VPN — экран на WebView поверх ядра v2rayNG.
 *
 * Реальный туннель VLESS+Reality держит нативное ядро xray (через VpnService),
 * управление идёт через CoreServiceManager. Эта Activity:
 *  - грузит HTML-интерфейс из assets/luna/index.html;
 *  - даёт JS мост window.LunaBridge (connect/disconnect/getTraffic/requestIp);
 *  - импортирует VLESS-ссылку(и) и выбирает сервер;
 *  - отдаёт обратно в JS реальное состояние (window.lunaOnState) и IP (window.lunaOnIp).
 *
 * Все 4 локации по умолчанию указывают на один рабочий линк — впиши свои
 * реальные ссылки в SERVERS, и дропдаун станет настоящим мультисерверным.
 */
class LunaActivity : AppCompatActivity() {

    companion object {
        private const val DEFAULT_LINK =
            "vless://dda41cb1-c9e9-4fb0-8ef8-5cf051d55003@finlandbox.space:443?security=reality&encryption=none&pbk=PXtzIrCwLrvgGHBRqZBB-mPOUvlwWiPbuGWsoloxDjc&headerType=none&fp=chrome&type=tcp&flow=xtls-rprx-vision&sni=www.max.ru&sid=74#dasd"

        // имя локации -> VLESS-ссылка
        private val SERVERS = linkedMapOf(
            "Германия (Быстрый)" to DEFAULT_LINK,
            "Нидерланды" to DEFAULT_LINK,        // ← подставь свой линк для NL
            "США (Нью-Йорк)" to DEFAULT_LINK,    // ← подставь свой линк для US
            "Сингапур" to DEFAULT_LINK,          // ← подставь свой линк для SG
        )

        private const val IP_CHECK_URL = "http://ip-api.com/json"
    }

    private lateinit var webView: WebView
    private val mainViewModel: MainViewModel by viewModels()

    // имя локации -> guid импортированного сервера
    private val guidMap = mutableMapOf<String, String>()
    private var pendingCountry: String? = null

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                doStart()
            } else {
                pushState(false)
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.addJavascriptInterface(LunaBridge(), "LunaBridge")

        // реальное состояние ядра -> в WebView
        mainViewModel.isRunning.observe(this) { running -> pushState(running == true) }
        mainViewModel.startListenBroadcast()

        // импортируем серверы (быстро, без сети для одиночных линков)
        ensureServers()

        webView.loadUrl("file:///android_asset/luna/index.html")
    }

    /** Импорт линков из SERVERS и построение map "локация -> guid". Дедуп по линку. */
    private fun ensureServers() {
        try {
            val existing = MmkvManager.decodeServerList("")
            if (existing.isEmpty()) {
                val linkToGuid = mutableMapOf<String, String>()
                for ((name, link) in SERVERS) {
                    val guid = linkToGuid.getOrPut(link) {
                        val before = MmkvManager.decodeServerList("").toSet()
                        AngConfigManager.importBatchConfig(link, "", true)
                        MmkvManager.decodeServerList("").firstOrNull { it !in before } ?: ""
                    }
                    if (guid.isNotEmpty()) guidMap[name] = guid
                }
            } else {
                // сервера уже есть — мапим имена на существующие по порядку
                val names = SERVERS.keys.toList()
                existing.forEachIndexed { i, g -> if (i < names.size) guidMap[names[i]] = g }
                if (guidMap.isEmpty()) names.forEach { guidMap[it] = existing.first() }
            }
        } catch (e: Exception) {
            // не валим UI — connect() позже покажет ошибку
        }
    }

    private fun guidFor(country: String?): String? {
        if (country != null) guidMap[country]?.let { return it }
        return guidMap.values.firstOrNull() ?: MmkvManager.decodeServerList("").firstOrNull()
    }

    private fun connectFlow(country: String?) {
        if (guidMap.isEmpty()) ensureServers()
        val guid = guidFor(country)
        if (guid.isNullOrEmpty()) {
            Toast.makeText(this, "Нет сервера для подключения", Toast.LENGTH_SHORT).show()
            pushState(false)
            return
        }
        MmkvManager.setSelectServer(guid)
        pendingCountry = country

        if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) doStart() else requestVpnPermission.launch(intent)
        } else {
            doStart()
        }
    }

    private fun doStart() {
        CoreServiceManager.startVService(this)
    }

    private fun pushState(running: Boolean) {
        runOnUiThread {
            webView.evaluateJavascript("window.lunaOnState && window.lunaOnState($running)", null)
        }
    }

    /** Проверка внешнего IP через уже поднятый туннель (трафик приложения тоже идёт в tun). */
    private fun fetchIp() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                URL(IP_CHECK_URL).openConnection().apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                }.getInputStream().bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                ""
            }
            withContext(Dispatchers.Main) {
                val esc = result
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", " ")
                    .replace("\r", " ")
                webView.evaluateJavascript("window.lunaOnIp && window.lunaOnIp('$esc')", null)
            }
        }
    }

    inner class LunaBridge {
        @JavascriptInterface
        fun connect(country: String?) {
            runOnUiThread { connectFlow(country) }
        }

        @JavascriptInterface
        fun disconnect() {
            runOnUiThread { CoreServiceManager.stopVService(this@LunaActivity) }
        }

        @JavascriptInterface
        fun isRunning(): Boolean = mainViewModel.isRunning.value == true

        @JavascriptInterface
        fun getTraffic(): String {
            val rx = TrafficStats.getTotalRxBytes().coerceAtLeast(0)
            val tx = TrafficStats.getTotalTxBytes().coerceAtLeast(0)
            return "$rx,$tx"
        }

        @JavascriptInterface
        fun requestIp() {
            fetchIp()
        }
    }
}
