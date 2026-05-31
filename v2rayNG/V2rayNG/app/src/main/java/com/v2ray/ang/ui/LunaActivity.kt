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
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        // VLESS ключи для разных стран
        // Точный рабочий формат из v2rayTun (с encryption=none и headerType=none)
        private const val FINLAND_LINK =
            "vless://dda41cb1-c9e9-4fb0-8ef8-5cf051d55003@finlandbox.space:443?encryption=none&flow=xtls-rprx-vision&type=tcp&headerType=none&security=reality&fp=chrome&sni=www.max.ru&pbk=PXtzIrCwLrvgGHBRqZBB-mPOUvlwWiPbuGWsoloxDjc&sid=74&spx=%2F#Finland"

        private const val SWEDEN_LINK =
            "vless://8b671692-edc3-4417-b648-d5569546ee0c@sw.motion-vpn.com:443?encryption=none&flow=xtls-rprx-vision&type=tcp&headerType=none&security=reality&fp=chrome&sni=sw.motion-vpn.com&pbk=8fymhqg_KSIkmSj-j-T-5OAsF7MbwAFrr8EoiXPKGkg&spx=%2F#Sweden"

        private const val GERMANY_LINK =
            "vless://8b671692-edc3-4417-b648-d5569546ee0c@de.motion-vpn.com:443?encryption=none&flow=xtls-rprx-vision&type=tcp&headerType=none&security=reality&fp=chrome&sni=de.motion-vpn.com&pbk=KU9m48nhlZ2f45x5s4m9JcOQlffza1tGB2J8e_7yg1w#Germany"

        private const val POLAND_LINK =
            "vless://8b671692-edc3-4417-b648-d5569546ee0c@pl.motion-vpn.com:443?encryption=none&flow=xtls-rprx-vision&type=tcp&headerType=none&security=reality&fp=chrome&sni=pl.motion-vpn.com&pbk=IY4hLHcko9ssHpOASf5giYZL4XMy0kGzkvi9n4PLtxg#Poland"

        // имя локации -> VLESS-ссылка
        private val SERVERS = linkedMapOf(
            "Финляндия" to FINLAND_LINK,
            "Швеция" to SWEDEN_LINK,
            "Германия" to GERMANY_LINK,
            "Польша" to POLAND_LINK
        )

        // Хосты для пинга (извлечены из VLESS ссылок)
        private val PING_HOSTS = mapOf(
            "Финляндия" to "finlandbox.space",
            "Швеция" to "sw.motion-vpn.com",
            "Германия" to "de.motion-vpn.com",
            "Польша" to "pl.motion-vpn.com"
        )

        private const val PING_PORT = 443
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

        // Устанавливаем GLOBAL routing mode (индекс 2) для избежания проблем с geo-файлами
        // GLOBAL режим не требует geosite:google и других geo-правил
        MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_RULESET, 2)

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

            // Удаляем все серверы кроме наших VLESS
            if (existing.isNotEmpty()) {
                val toKeep = mutableListOf<String>()
                for (guid in existing) {
                    val config = MmkvManager.decodeServerConfig(guid)
                    // Оставляем только VLESS конфигурации с нашими серверами
                    if (config != null &&
                        config.configType == com.v2ray.ang.enums.EConfigType.VLESS &&
                        (config.server?.contains("finlandbox.space") == true ||
                         config.server?.contains("motion-vpn.com") == true)) {
                        toKeep.add(guid)
                        // Мапим по хосту
                        SERVERS.forEach { (country, _) ->
                            val host = PING_HOSTS[country]
                            if (config.server == host) {
                                guidMap[country] = guid
                            }
                        }
                    } else {
                        // Удаляем лишние конфигурации
                        MmkvManager.removeServer(guid)
                    }
                }

                // Если не хватает серверов, импортируем недостающие
                if (guidMap.size < SERVERS.size) {
                    importDefaultServer()
                }
            } else {
                // Нет серверов - импортируем все
                importDefaultServer()
            }
        } catch (e: Exception) {
            // не валим UI — connect() позже покажет ошибку
        }
    }

    /** Импортирует все VLESS серверы */
    private fun importDefaultServer() {
        try {
            val before = MmkvManager.decodeServerList("").toSet()

            // Импортируем все серверы
            SERVERS.forEach { (country, link) ->
                AngConfigManager.importBatchConfig(link, "", false)
                val newGuids = MmkvManager.decodeServerList("").filter { it !in before }
                if (newGuids.isNotEmpty()) {
                    guidMap[country] = newGuids.last()
                }
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

    /** true если guid указывает на один из наших VLESS-серверов (Финляндия/Швеция/Германия/Польша). */
    private fun isOurServer(guid: String): Boolean {
        val server = MmkvManager.decodeServerConfig(guid)?.server ?: return false
        return server.contains("finlandbox.space") || server.contains("motion-vpn.com")
    }

    private fun connectFlow(country: String?) {
        if (guidMap.isEmpty()) ensureServers()
        var guid = guidFor(country)

        // Проверяем что guid декодируется в валидный VLESS-конфиг одного из наших серверов.
        // Если нет (устаревший guid от удалённого сервера) — переимпортируем серверы.
        if (guid.isNullOrEmpty() || !isOurServer(guid)) {
            guidMap.clear()
            ensureServers()
            guid = guidFor(country)
        }

        if (guid.isNullOrEmpty() || MmkvManager.decodeServerConfig(guid) == null) {
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

    /** Измерение пинга до сервера */
    private fun measurePing(country: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val host = PING_HOSTS[country] ?: return@launch
            val ping = try {
                val startTime = System.currentTimeMillis()
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(host, PING_PORT), 5000)
                }
                val endTime = System.currentTimeMillis()
                (endTime - startTime).toInt()
            } catch (e: Exception) {
                -1 // ошибка подключения
            }
            withContext(Dispatchers.Main) {
                webView.evaluateJavascript("window.lunaOnPing && window.lunaOnPing('$country', $ping)", null)
            }
        }
    }

    /**
     * Проверка внешнего IP ЧЕРЕЗ туннель.
     *
     * Важно: само приложение по умолчанию исключено из VPN (addDisallowedApplication),
     * поэтому обычный URL().openConnection() возвращал бы реальный IP в обход VPN.
     * Правильный путь — слать запрос через локальный HTTP-прокси ядра xray
     * (127.0.0.1:getHttpPort()), как это делает SpeedtestManager.getRemoteIPInfo().
     *
     * Сразу после connect туннель ещё поднимается, поэтому делаем несколько попыток.
     */
    private fun fetchIp() {
        lifecycleScope.launch(Dispatchers.IO) {
            var result = ""
            // до 6 попыток с паузой ~1.5с — ждём пока туннель реально начнёт роутить трафик
            for (attempt in 0 until 6) {
                if (attempt > 0) delay(1500)
                val httpPort = SettingsManager.getHttpPort()
                val content = if (httpPort != 0) {
                    HttpUtil.getUrlContent(
                        UrlContentRequest(
                            url = IP_CHECK_URL,
                            timeout = 8000,
                            httpPort = httpPort
                        )
                    )
                } else {
                    null
                }
                // ip-api.com отдаёт JSON с полем "query" (IP). Считаем ответ валидным если оно есть.
                if (!content.isNullOrBlank() && content.contains("\"query\"")) {
                    result = content
                    break
                }
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

        @JavascriptInterface
        fun requestPing(country: String) {
            measurePing(country)
        }
    }
}
