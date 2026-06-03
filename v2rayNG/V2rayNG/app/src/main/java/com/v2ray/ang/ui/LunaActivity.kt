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
        // VLESS ключи для разных стран — реальные рабочие ссылки пользователя.
        // Вписаны дословно (включая pqv/sid/spx), чтобы импорт совпадал 1:1
        // с тем, что работает в других клиентах.
        private const val FINLAND_LINK =
            "vless://dda41cb1-c9e9-4fb0-8ef8-5cf051d55003@finlandbox.space:443?type=tcp&encryption=none&security=reality&pbk=PXtzIrCwLrvgGHBRqZBB-mPOUvlwWiPbuGWsoloxDjc&fp=chrome&sni=www.max.ru&sid=74&spx=%2F&pqv=v4GieDSOTD4c1CDvhdpYW8miJgA17fA_xoCAH-_ndV7CVrv6Q0e2BR2vDuIlSDivmzB5zyFPVQiNIBjbtSlET1gQlSAztzNAyWY9UQ32vC8jlKLtHRXtIvLnbBSbExrpSmyoJUOLcKUIJzsyBwo_q9K8iHcKItHrMjtCzBNaoWZI5-9kh9UDchWlSznvK-lVkGHbYuHAjXoq21Tyuum8vCEyOEXZqPymNp265LWV9VB-2FhXN7tcjNjLTVuvr7NTef8RH8kes2vsfkA-RxS-o9vsMe6ERXFQwmNZm_q3kNpuaNJArQYDcP1q_q8F3IoqG9kU1gW5yv7Z7QIItSZkI1O9SJNTwBPLAUo-fRUvHwahrRYVoqNvpSCG720lbMFUdACFM_5IMN2DhDqNgNgRv4u3T7--6K0345Vuv2AiuarS17hFh-zmqjUdq4yCchpBuMMyZLS7M-Gb5aYMFgpNlgVKUfjFTYxy0yzq_3ZNFdezJfFHsutL-n80uix40nFbLQ_UdGWDx58zyyHkuKTqX-1SKK6dB2cwgCp6iKf8nDW8WgECxc8gkPPQbUFL5zRiYJy0lpe8mA0hWgmpzYYsA5GxhuoxeI9OLUllZLS9T2yTq_xq-FxKi8iuJFGTUIA6S5OVsgCNu0nT4VF1HDl8cxlQpnz5QltQ6S7Re3uYG4dSPslNVEN7XW--ow0Exv2XQCNb424q_4FsoJDPFiaDLRTRwLk7ePpBG04VeHqjgg8Hl0fIT3bD4LOnnpxUDBQogBSUJwu5s0X0twD6FJYA7r-39A_Sp0A9uFlh_c5_D6ZcBkcb6lGr6fpteEkTivYItOZJy804eP-7GmWUew9GynyVMW8SMj-_nOF8X3_wcBvn2m_zDpQ3AMbtYFahUoxDRuW8ANNzWaJrf-IQ4JlBrVMPCaAGav50GYFINrr0zRl-bue2AqkQhi7ViME1driUUomd1GGoxxgxIbZOkCDsHXUopiKmo20nlIELvQlsXsqGvLf-YlLncNNzy5wadFzIFf9kozww0yFisbHaMfn-gkkhZ9S-xyUnWmI_yyNSwf4FxLK-kGB43sCF27p76_MhcnasRjYGp3LT9VW8CQLzs0cq9F-gfxV7PY2DwLG6JBsigmut_6bHUNPw-waCGaXdyG8rXKOxO3AYP0YlWTYXpdP7UJrGZH2EkssRfIOoqEYGPRasorMRJSapTSXIMjKeE--ijNtCQQ5wYOuWmoMGjJAuR62Wws-L9q9PZ0RY_0Q1cRl4z2hDW5VptjdyZZKc5-ge8XSEzqUkydxrpDE7H4qvWBu3suHyMjtd3OHiINA88lSHdb7M6LVaGahIRsIfCSk_XkbUT1r0utHt5ngL6EV_EulWcrXEHmlhfRoVsPaLYXBPvzigIoC8Tnt6gQvMxbe2DjIPmJwPj-HmltBWX478jP6TDucab4Wwzijwq0XeK2n9Hp2k8hgBwV50c1h_z0g0ObHPkGp6iu67clyUqzTRhUXOa8C-wLhyO-hDPXLwVZAEAvmUb-TjiMAFFhZzwbrP79kAW1guqz6vXTppxvo4gTSjPOVK26phdbbH7poJ1A0Z3NI6cLShq9aX-Wi4ay3gTxGu6fZiItb0jMHun7s1UTURqlz3Obh96XFiq52PkGVTqLuJomcdUo4a3SvT8OZ-uA2KPqppLbuA6a2si8YCDO2x_uNjfxUd-exTTE8X9lWe9nrPOKRszwPsvRZvUYNMigWLhzpm6Rd3ljnUhY7wgBKjNE344H_6vzZ6m30HiSVOIkGOdVoZIhE7vhQIKsgTwMnWmDISpYMyRNDaH9jQEyqcd8CTDl4GLyO0iwDqFfr8ji2121DUecbqPzX5GywLRQ6MWsy7lGNO87DUkWaGa5GTngaRwvTuwD0XNCA_QR530LT5WvAQ46KM0DlwKymRGYilInVn39vSQuHp8szRjlML6ePMKO1OXQM03p4Itwdi9Lvas_dqjn9UScCaHEkDA7Up1-QA9QGIfghB5oOU8XV_1iSA7iB4-2Terw-jj42Ncy7gVptCDMuCu-KcgFh5dXixlS7TKKbqxlHeI2Bbb96VVdp0lgmJ5I6UpBiYEutqeLr9ko1KhEGeCObU68H2pDQmSzsgwjRZUQZssspBnGQMph5B6EBMnlX7HyXR7gXqebqhHSjRHN0OHADuL0ScdsVUGk6Rx7Xf_2uFnmYasrHauUA2h3gvzKSLnZQ4dgPWIMset0uPKX9yDlQnawUlAqiwkPDQ8dRDRyi3DSXcEzEYhNIJDreGSo23SPEPw4US7kPlCJauDrQqoKTrbWJPA2-kAxLU3ZMXq6YP_q2hJdQ5tEB6O_BKGdjXR5fWiA5_jISicso_SLyn82ZqIEea0DjzEE5M_UEVcBS1wf5ugbJrDz8wEFPKsPaTB4ZUR-9SyGc_TYqiStIelysDOltdEmqva4OCJupuoJNOTI0d3wVdupQwleg-yqlPzkrMM2qYt4FYmsehSkhlhf9emj61091HbL5HnrLgS9tvTpPEPVGi0wYzQnwAD8ODJ04-AZ1OMviG6nS0B-BulVoWzFUHNuEr7NKg2JfWoDb7VdqKCNwXdhDAk1P5dw7K1t0&flow=xtls-rprx-vision#vless%20filnlad-BY%20ZYNNC"

        private const val GERMANY_LINK =
            "vless://8b671692-edc3-4417-b648-d5569546ee0c@de.motion-vpn.com:443?flow=xtls-rprx-vision&type=tcp&headerType=none&security=reality&fp=chrome&sni=de.motion-vpn.com&pbk=KU9m48nhlZ2f45x5s4m9JcOQlffza1tGB2J8e_7yg1w#%F0%9F%87%A9%F0%9F%87%AA%D0%93%D0%B5%D1%80%D0%BC%D0%B0%D0%BD%D0%B8%D1%8F%20%7C%20WI-FI"

        private const val RUSSIA_LINK =
            "vless://8b671692-edc3-4417-b648-d5569546ee0c@ru.motion-vpn.com:443?security=reality&encryption=none&pbk=P1h8QpXRTeBoNu84lS6IaRwl_r5yINnyYXOSMzdDhmc&headerType=none&fp=chrome&type=tcp&flow=xtls-rprx-vision&sni=ru.motion-vpn.com#%F0%9F%87%B7%F0%9F%87%BA%20%D0%A0%D0%BE%D1%81%D1%81%D0%B8%D1%8F%20%5B%D0%98%D0%B3%D1%80%D1%8B%2C%20Discord%5D"

        // имя локации -> VLESS-ссылка
        private val SERVERS = linkedMapOf(
            "Финляндия" to FINLAND_LINK,
            "Германия" to GERMANY_LINK,
            "Россия" to RUSSIA_LINK
        )

        // Хосты для пинга (извлечены из VLESS ссылок)
        private val PING_HOSTS = mapOf(
            "Финляндия" to "finlandbox.space",
            "Германия" to "de.motion-vpn.com",
            "Россия" to "ru.motion-vpn.com"
        )

        private const val PING_PORT = 443
        private const val IP_CHECK_URL = "http://ip-api.com/json"

        // Ключ кэша версии набора серверов. Тяжёлый переимпорт ссылок выполняется
        // только когда этот хэш изменился (т.е. ссылки реально поменяли).
        private const val CONFIG_VERSION_KEY = "luna_servers_version"
    }

    private lateinit var webView: WebView
    private val mainViewModel: MainViewModel by viewModels()

    // имя локации -> guid импортированного сервера
    // Перестраивается атомарно (в фоне) — читается и из UI-, и из IO-потока.
    @Volatile
    private var guidMap: Map<String, String> = emptyMap()
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

        // Интерфейс показываем СРАЗУ — он не ждёт инициализации ядра/импорта.
        webView.loadUrl("file:///android_asset/luna/index.html")

        // Вся тяжёлая инициализация — в фоне, чтобы старт был мгновенным и без фризов:
        //  • копирование geosite.dat/geoip.dat (иначе xray падает на routing-правилах);
        //  • настройки режима (TUN, VPN, GLOBAL routing);
        //  • импорт/сверка серверов (парсинг ссылок, включая pqv) — только при смене ссылок.
        lifecycleScope.launch(Dispatchers.IO) {
            SettingsManager.initAssets(this@LunaActivity, this@LunaActivity.assets)
            MmkvManager.encodeSettings(AppConfig.PREF_USE_HEV_TUNNEL, false)
            MmkvManager.encodeSettings(AppConfig.PREF_MODE, AppConfig.VPN)
            MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_RULESET, 2)
            ensureServers()
            restoreLastServer()
        }
    }

    /** Версия набора серверов — меняется при любом изменении ссылок (для кэша переимпорта). */
    private fun serversVersion(): String = SERVERS.values.joinToString("|").hashCode().toString()

    /**
     * Гарантирует наличие наших VLESS-серверов и строит карту "локация -> guid".
     *
     * Оптимизация: тяжёлый переимпорт (парсинг ссылок, включая pqv) выполняется ТОЛЬКО когда
     * набор ссылок изменился (сверка по версии-хэшу). На обычном запуске карта восстанавливается
     * из уже сохранённых профилей — без удаления и перезаливки. Рассчитан на вызов в фоне (IO).
     */
    private fun ensureServers() {
        try {
            val hostToCountry = PING_HOSTS.entries.associate { (country, host) -> host to country }
            val desiredVersion = serversVersion()
            val storedVersion = MmkvManager.decodeSettingsString(CONFIG_VERSION_KEY)

            // Быстрый путь: ссылки не менялись — просто строим карту из существующих профилей.
            if (storedVersion == desiredVersion) {
                val map = buildGuidMap(hostToCountry)
                if (map.size == SERVERS.size) {
                    guidMap = map
                    return
                }
            }

            // Реконсиляция: удаляем ранее импортированные наши профили (чужие не трогаем)
            // и заливаем актуальные ссылки. Затем фиксируем версию, чтобы не повторять.
            val ourHosts = PING_HOSTS.values.toSet()
            for (guid in MmkvManager.decodeServerList("")) {
                val config = MmkvManager.decodeServerConfig(guid)
                if (config?.configType == com.v2ray.ang.enums.EConfigType.VLESS &&
                    config.server != null && ourHosts.contains(config.server)) {
                    MmkvManager.removeServer(guid)
                }
            }
            guidMap = importDefaultServer()
            MmkvManager.encodeSettings(CONFIG_VERSION_KEY, desiredVersion)
        } catch (e: Exception) {
            // не валим UI — connect() позже покажет ошибку
        }
    }

    /** Строит карту "локация -> guid" из уже сохранённых VLESS-профилей по совпадению хоста. */
    private fun buildGuidMap(hostToCountry: Map<String, String>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (guid in MmkvManager.decodeServerList("")) {
            val config = MmkvManager.decodeServerConfig(guid) ?: continue
            if (config.configType == com.v2ray.ang.enums.EConfigType.VLESS) {
                hostToCountry[config.server]?.let { country -> map[country] = guid }
            }
        }
        return map
    }

    /** Импортирует все VLESS серверы и возвращает карту "локация -> guid". */
    private fun importDefaultServer(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            // Импортируем каждую ссылку с append = true.
            // ВАЖНО: append = false заставляет importBatchConfig сначала УДАЛИТЬ все
            // серверы подписки "" и лишь потом добавить новый — поэтому в цикле
            // выживал только последний сервер (оставалась одна локация). С append = true
            // серверы накапливаются. Дубликаты исключены: ensureServers() заранее
            // удаляет ранее импортированные наши профили.
            SERVERS.forEach { (country, link) ->
                // Снимок списка ДО импорта именно этой ссылки — чтобы точно
                // определить добавленный guid независимо от порядка хранения.
                val before = MmkvManager.decodeServerList("").toSet()
                AngConfigManager.importBatchConfig(link, "", true)
                val newGuid = MmkvManager.decodeServerList("").firstOrNull { it !in before }
                if (newGuid != null) {
                    map[country] = newGuid
                }
            }
        } catch (e: Exception) {
            // игнорируем ошибки импорта
        }
        return map
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

    /** true если guid указывает на один из наших VLESS-серверов (Финляндия/Германия/Россия). */
    private fun isOurServer(guid: String): Boolean {
        val server = MmkvManager.decodeServerConfig(guid)?.server ?: return false
        return PING_HOSTS.values.any { server.contains(it) }
    }

    private fun connectFlow(country: String?) {
        // Корутина на главном диспетчере. ВСЯ работа с MMKV/диском/парсингом конфигов
        // (включая декод "тяжёлого" профиля с большим pqv) уносится в ОДИН IO-блок,
        // чтобы тап Connect не блокировал UI. На главном потоке остаются только
        // системный VPN-диалог и старт сервиса.
        lifecycleScope.launch {
            val guid = withContext(Dispatchers.IO) {
                // geo-файлы (идемпотентно, дёшево если уже скопированы) + готовность карты серверов.
                SettingsManager.initAssets(this@LunaActivity, this@LunaActivity.assets)
                if (guidMap.isEmpty()) ensureServers()

                var g = guidFor(country)
                // Если guid пуст или указывает на невалидный/чужой конфиг (устаревший guid
                // от удалённого сервера) — форсируем переимпорт.
                if (g.isNullOrEmpty() || !isOurServer(g)) {
                    MmkvManager.encodeSettings(CONFIG_VERSION_KEY, "") // сбросить кэш -> ensureServers перельёт
                    ensureServers()
                    g = guidFor(country)
                }

                if (g.isNullOrEmpty() || MmkvManager.decodeServerConfig(g) == null) {
                    null
                } else {
                    // Сохраняем выбранный сервер и страну ПЕРЕД подключением.
                    MmkvManager.setSelectServer(g)
                    MmkvManager.encodeSettings("luna_last_country", country ?: "")
                    g
                }
            }

            if (guid == null) {
                Toast.makeText(this@LunaActivity, "Нет сервера для подключения", Toast.LENGTH_SHORT).show()
                pushState(false)
                return@launch
            }
            pendingCountry = country

            if (SettingsManager.isVpnMode()) {
                val intent = VpnService.prepare(this@LunaActivity)
                if (intent == null) doStart() else requestVpnPermission.launch(intent)
            } else {
                doStart()
            }
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
