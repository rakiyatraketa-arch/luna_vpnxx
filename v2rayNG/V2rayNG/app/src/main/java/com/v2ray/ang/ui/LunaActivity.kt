package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.net.TrafficStats
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
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

        // Копируем geosite.dat/geoip.dat из ассетов APK в рабочую папку ядра.
        // Без них xray падает с "config error: geosite.dat no such file" на routing-правилах.
        SettingsManager.initAssets(this, assets)

        // Включаем режим TUN (xray встроенный TUN вместо hev-socks5-tunnel)
        MmkvManager.encodeSettings(AppConfig.PREF_USE_HEV_TUNNEL, false)

        // Включаем VPN режим
        MmkvManager.encodeSettings(AppConfig.PREF_MODE, AppConfig.VPN)

        webView = WebView(this)
        setContentView(webView)
        // Снижаем накладные расходы рендеринга (вид не меняется):
        webView.overScrollMode = View.OVER_SCROLL_NEVER       // без свечения при перетягивании
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT              // кэш локальных ассетов
            setSupportZoom(false)                             // зум не нужен — меньше обработчиков жестов
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100                                    // игнорируем системное масштабирование шрифта
        }
        webView.addJavascriptInterface(LunaBridge(), "LunaBridge")

        // реальное состояние ядра -> в WebView
        mainViewModel.isRunning.observe(this) { running -> pushState(running == true) }
        mainViewModel.startListenBroadcast()

        // импортируем серверы (быстро, без сети для одиночных линков)
        ensureServers()

        // Восстанавливаем последний выбранный сервер
        restoreLastServer()

        webView.loadUrl("file:///android_asset/luna/index.html")
    }

    /** Импорт линков из SERVERS и построение map "локация -> guid". Дедуп по линку. */
    private fun ensureServers() {
        try {
            val existing = MmkvManager.decodeServerList("")

            // Удаляем все серверы кроме VLESS (Финляндия)
            if (existing.isNotEmpty()) {
                val toKeep = mutableListOf<String>()
                for (guid in existing) {
                    val config = MmkvManager.decodeServerConfig(guid)
                    // Оставляем только VLESS конфигурации с finlandbox.space
                    if (config != null &&
                        config.configType == com.v2ray.ang.enums.EConfigType.VLESS &&
                        config.server?.contains("finlandbox.space") == true) {
                        toKeep.add(guid)
                    } else {
                        // Удаляем лишние конфигурации
                        MmkvManager.removeServer(guid)
                    }
                }

                // Если не осталось нужных серверов, импортируем заново
                if (toKeep.isEmpty()) {
                    importDefaultServer()
                } else {
                    // Мапим существующий сервер на все локации
                    val mainGuid = toKeep.first()
                    SERVERS.keys.forEach { name -> guidMap[name] = mainGuid }
                }
            } else {
                // Нет серверов - импортируем дефолтный
                importDefaultServer()
            }
        } catch (e: Exception) {
            // не валим UI — connect() позже покажет ошибку
        }
    }

    /** Импортирует только дефолтный VLESS сервер Финляндии */
    private fun importDefaultServer() {
        try {
            val before = MmkvManager.decodeServerList("").toSet()
            AngConfigManager.importBatchConfig(DEFAULT_LINK, "", false)
            val guid = MmkvManager.decodeServerList("").firstOrNull { it !in before } ?: ""
            if (guid.isNotEmpty()) {
                // Мапим один сервер на все локации
                SERVERS.keys.forEach { name -> guidMap[name] = guid }
            }
        } catch (e: Exception) {
            // игнорируем ошибки импорта
        }
    }

    /** Восстанавливает последний выбранный сервер из настроек */
    private fun restoreLastServer() {
        try {
            val lastGuid = MmkvManager.getSelectServer()
            if (!lastGuid.isNullOrEmpty() && MmkvManager.decodeServerConfig(lastGuid) != null) {
                // Сервер существует, ничего не делаем
                return
            }
            // Если сервер не найден, выбираем первый доступный
            val firstGuid = guidMap.values.firstOrNull() ?: MmkvManager.decodeServerList("").firstOrNull()
            if (firstGuid != null) {
                MmkvManager.setSelectServer(firstGuid)
            }
        } catch (e: Exception) {
            // игнорируем ошибки восстановления
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
        // Сохраняем выбранный сервер ПЕРЕД подключением
        MmkvManager.setSelectServer(guid)
        // Сохраняем выбранную страну для восстановления
        MmkvManager.encodeSettings("luna_last_country", country ?: "")
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

    override fun onPause() {
        super.onPause()
        // Сохраняем текущий выбранный сервер при сворачивании
        saveCurrentState()
    }

    override fun onStop() {
        super.onStop()
        // Сохраняем состояние при остановке активности
        saveCurrentState()
    }

    override fun onDestroy() {
        // Сохраняем состояние перед уничтожением
        saveCurrentState()
        super.onDestroy()
    }

    /** Сохраняет текущее состояние конфигурации */
    private fun saveCurrentState() {
        try {
            val currentGuid = MmkvManager.getSelectServer()
            if (!currentGuid.isNullOrEmpty()) {
                // Проверяем что сервер существует
                val config = MmkvManager.decodeServerConfig(currentGuid)
                if (config != null) {
                    // Сервер валидный, сохраняем его как последний
                    MmkvManager.encodeSettings("luna_last_guid", currentGuid)
                }
            }
        } catch (e: Exception) {
            // игнорируем ошибки сохранения
        }
    }

    inner class LunaBridge {
        @JavascriptInterface
        fun connect(country: String?) {
            runOnUiThread { connectFlow(country) }
        }

        @JavascriptInterface
        fun disconnect() {
            runOnUiThread {
                // Сохраняем состояние перед отключением
                saveCurrentState()
                CoreServiceManager.stopVService(this@LunaActivity)
            }
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
