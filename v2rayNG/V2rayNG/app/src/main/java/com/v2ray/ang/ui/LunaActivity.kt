package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.TrafficStats
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
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
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

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
        // JSON-конфиги серверов из betatest (минифицированы для importBatchConfig)
        private const val NIDERLANDS_JSON =
            """{"remarks":"🇳🇱 НИДЕРЛАНДЫ","log":{"loglevel":"warning"},"policy":{"levels":{"0":{"connIdle":86400,"uplinkOnly":2,"downlinkOnly":5}},"system":{"statsInboundUplink":false,"statsInboundDownlink":false}},"dns":{"queryStrategy":"UseIP","servers":["1.1.1.1","1.0.0.1"]},"inbounds":[{"tag":"socks","port":10808,"listen":"127.0.0.1","protocol":"socks","settings":{"auth":"noauth","udp":true},"sniffing":{"enabled":true,"destOverride":["http","tls","quic"],"routeOnly":false}},{"tag":"http","port":10809,"listen":"127.0.0.1","protocol":"http","settings":{"allowTransparent":false},"sniffing":{"enabled":true,"destOverride":["http","tls","quic"],"routeOnly":false}}],"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"87.58.210.202","port":443,"users":[{"id":"b26ea3f1-2b4a-488e-88e3-6a2d53948612","encryption":"none","flow":"xtls-rprx-vision"}]}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"show":false,"serverName":"apex-vpn.space","fingerprint":"firefox","publicKey":"yorIh_8ynxvblP-UesrdyInTF7JM2rJ3S_ddJO4ITHQ","shortId":"deaaa71eea0044","spiderX":"/"}}},{"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}],"routing":{"domainMatcher":"hybrid","domainStrategy":"AsIs","rules":[{"type":"field","outboundTag":"direct","protocol":["bittorrent"]}]}}"""

        private const val SWITZERLAND_JSON =
            """{"remarks":"🇨🇭 Швейцария | WI-FI","dns":{"queryStrategy":"UseIP","servers":["1.1.1.1","1.0.0.1"]},"inbounds":[{"listen":"127.0.0.1","port":10808,"protocol":"socks","settings":{"auth":"noauth","udp":true},"sniffing":{"destOverride":["http","tls","quic"],"enabled":true,"routeOnly":false},"tag":"socks"},{"listen":"127.0.0.1","port":10809,"protocol":"http","settings":{"allowTransparent":false},"sniffing":{"destOverride":["http","tls","quic"],"enabled":true,"routeOnly":false},"tag":"http"}],"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"sd.motion-vpn.com","port":443,"users":[{"encryption":"none","flow":"xtls-rprx-vision","id":"8b671692-edc3-4417-b648-d5569546ee0c"}]}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"fingerprint":"firefox","publicKey":"KU9m48nhlZ2f45x5s4m9JcOQlffza1tGB2J8e_7yg1w","serverName":"sd.motion-vpn.com"}},"tag":"proxy"},{"protocol":"freedom","tag":"direct"},{"protocol":"blackhole","tag":"block"}],"routing":{"domainMatcher":"hybrid","domainStrategy":"IPIfNonMatch","rules":[{"outboundTag":"direct","protocol":["bittorrent"],"type":"field"}]}}"""

        private const val GERMANY_JSON =
            """{"remarks":"🇩🇪 ГЕРМАНИЯ","dns":{"queryStrategy":"UseIP","servers":["1.1.1.1","1.0.0.1"]},"inbounds":[{"listen":"127.0.0.1","port":10808,"protocol":"socks","settings":{"auth":"noauth","udp":true},"sniffing":{"destOverride":["http","tls","quic"],"enabled":true,"routeOnly":false},"tag":"socks"},{"listen":"127.0.0.1","port":10809,"protocol":"http","settings":{"allowTransparent":false},"sniffing":{"destOverride":["http","tls","quic"],"enabled":true,"routeOnly":false},"tag":"http"}],"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"de1.motion-vpn.com","port":443,"users":[{"encryption":"none","flow":"xtls-rprx-vision","id":"8b671692-edc3-4417-b648-d5569546ee0c"}]}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"fingerprint":"firefox","publicKey":"WxbUVzJnN7jvIf1zMkCD93RzdMo8K1voxWjplVkc1Bw","serverName":"de1.motion-vpn.com"}},"tag":"proxy"},{"protocol":"freedom","tag":"direct"},{"protocol":"blackhole","tag":"block"}],"routing":{"domainMatcher":"hybrid","domainStrategy":"IPIfNonMatch","rules":[{"outboundTag":"direct","protocol":["bittorrent"],"type":"field"}]}}"""

        private const val RUSSIA_JSON =
            """{"remarks":"🇷🇺 РОССИЯ","dns":{"queryStrategy":"UseIP","servers":["1.1.1.1","1.0.0.1"]},"inbounds":[{"listen":"127.0.0.1","port":10808,"protocol":"socks","settings":{"auth":"noauth","udp":true},"sniffing":{"destOverride":["http","tls","quic"],"enabled":true,"routeOnly":false},"tag":"socks"},{"listen":"127.0.0.1","port":10809,"protocol":"http","settings":{"allowTransparent":false},"sniffing":{"destOverride":["http","tls","quic"],"enabled":true,"routeOnly":false},"tag":"http"}],"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"noderu2.motion-vpn.com","port":443,"users":[{"encryption":"none","flow":"xtls-rprx-vision","id":"8b671692-edc3-4417-b648-d5569546ee0c"}]}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"fingerprint":"firefox","publicKey":"NhIxhHDxYR9HEhlnDcacIVg8S4Z5lw8aWg6HZIUeBzo","serverName":"noderu2.motion-vpn.com"}},"tag":"proxy"},{"protocol":"freedom","tag":"direct"},{"protocol":"blackhole","tag":"block"}],"routing":{"domainMatcher":"hybrid","domainStrategy":"IPIfNonMatch","rules":[{"outboundTag":"direct","protocol":["bittorrent"],"type":"field"}]}}"""

        private const val LTE_JSON =
            """{"dns":{"queryStrategy":"UseIP","servers":["1.1.1.1","1.0.0.1"]},"inbounds":[{"listen":"127.0.0.1","port":10808,"protocol":"socks","settings":{"auth":"noauth","udp":true},"sniffing":{"destOverride":["http","tls","quic"],"enabled":true,"routeOnly":false},"tag":"socks"},{"listen":"127.0.0.1","port":10809,"protocol":"http","settings":{"allowTransparent":false,"auth":"noauth"},"sniffing":{"destOverride":["http","tls","quic"],"enabled":true,"routeOnly":false},"tag":"http"}],"log":{"loglevel":"warning"},"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"158.160.86.177","port":443,"users":[{"encryption":"none","flow":"xtls-rprx-vision","id":"7fc09d49-24f8-4ccb-b86d-41fc310fac06"}]}]},"streamSettings":{"network":"tcp","realitySettings":{"fingerprint":"chrome","publicKey":"vUqDEx0U33StHVKNQz11H_FsuwVmfqwUZlRNv0zmvzY","serverName":"ads.x5.ru"},"security":"reality","tcpSettings":{}},"tag":"proxy"},{"protocol":"freedom","tag":"direct"},{"protocol":"blackhole","tag":"block"}],"remarks":"LTE OBХОД","routing":{"domainMatcher":"hybrid","domainStrategy":"IPIfNonMatch","rules":[{"ip":["geoip:private"],"outboundTag":"block","type":"field"},{"domain":["geosite:private"],"outboundTag":"block","type":"field"},{"outboundTag":"block","protocol":["bittorrent"],"type":"field"},{"network":"udp","outboundTag":"block","port":443,"type":"field"},{"domain":["geosite:meta","geosite:instagram","geosite:facebook","domain:cdninstagram.com","domain:fbcdn.net"],"outboundTag":"proxy","type":"field"},{"ip":["geoip:ru"],"outboundTag":"direct","type":"field"},{"domain":["geosite:category-ru","geosite:category-gov-ru","geosite:yandex","geosite:vk","geosite:mailru","regexp:.*\\.ru$","regexp:.*\\.xn--p1ai$","regexp:.*\\.su$","full:go.yandex","keyword:yandex","domain:oneme.ru","domain:max.ru","domain:tracker-api.vk-analytics.ru","domain:wechat.com","domain:weixin.qq.com","domain:wx.qq.com","domain:servicewechat.com","domain:wxqcloud.qq.com","domain:tc.qq.com","domain:qpic.cn","domain:dldir1.qq.com","domain:1cfresh.com","domain:1internet.tv","domain:2gis.ae","domain:2gis.am","domain:2gis.az","domain:2gis.by","domain:2gis.com","domain:2gis.com.cy","domain:2gis.cz","domain:2gis.ge","domain:2gis.kg","domain:2gis.kz","domain:2gis.tj","domain:2gis.ua","domain:2gis.uz","domain:avito.st","domain:baltinvestbank.com","domain:bank131.com","domain:bankstoday.net","domain:bcs-bank.com","domain:donationalerts.com","domain:edadeal.io","domain:emias.info","domain:fix-price.com","domain:gazprombank.com","domain:jivochat.com","domain:jivosite.com","domain:kari.com","domain:kaspersky.com","domain:kk.bank","domain:koronapay.com","domain:lenta.com","domain:lmru.tech","domain:megamarket.tech","domain:moex.com","domain:mycdn.me","domain:okko.tv","domain:okko.sport","domain:ozonusercontent.com","domain:qiwi.com","domain:roscosmos.bank","domain:sberbank.com","domain:sovcombank.com","domain:tamtam.chat","domain:taplink.cc","domain:tbank-online.com","domain:timeweb.com","domain:timeweb.cloud","domain:tochka.com","domain:tochka-tech.com","domain:turbopages.org","domain:unistream.com","domain:userapi.com","domain:vk.com","domain:vk.cc","domain:vk.me","domain:vk.link","domain:vk.team","domain:vk.company","domain:vkcache.com","domain:vkgo.app","domain:vklive.app","domain:vkmessenger.com","domain:vkmessenger.app","domain:vkuser.net","domain:vkuseraudio.com","domain:vkuseraudio.net","domain:vkuserlive.net","domain:vkuservideo.com","domain:vkuservideo.net","domain:vk-apps.com","domain:vk-cdn.me","domain:vk-cdn.net","domain:vk-portal.net","domain:vtb.com","domain:vtb24.com","domain:vtb.digital","domain:vtb.promo","domain:vtbcareer.com","domain:vtbrussia.com","domain:wbapi.com","domain:wbbasket.net","domain:wbstatic.net","domain:wbx-static.com","domain:webvisor.com","domain:webvisor.org","domain:whoosh.bike","domain:wildberries.eu","domain:x5.tech","domain:yads.tech","domain:yastat.net","domain:yastatic.net","domain:youla.io","domain:zaim.com","domain:redheadsound.studio","domain:zentotem.net"],"outboundTag":"direct","type":"field"}]}}"""

        // Локальные запасные конфиги (используются если бэкенд недоступен)
        private val FALLBACK_SERVERS = linkedMapOf(
            "Нидерланды" to NIDERLANDS_JSON,
            "Германия" to GERMANY_JSON,
            "Россия" to RUSSIA_JSON,
            "Швейцария" to SWITZERLAND_JSON,
            "LTE ОБХОД" to LTE_JSON
        )

        private val FALLBACK_PING_HOSTS = mapOf(
            "Нидерланды" to "87.58.210.202",
            "Германия" to "de1.motion-vpn.com",
            "Россия" to "noderu2.motion-vpn.com",
            "Швейцария" to "sd.motion-vpn.com",
            "LTE ОБХОД" to "158.160.86.177"
        )

        private const val PING_PORT = 443
        private const val IP_CHECK_URL = "http://ip-api.com/json"
        private const val CONFIG_VERSION_KEY = "luna_servers_version"
        private const val REMOTE_CONFIGS_KEY = "luna_remote_configs"
    }

    private lateinit var webView: WebView
    private val mainViewModel: MainViewModel by viewModels()

    // имя локации -> guid импортированного сервера
    // Перестраивается атомарно (в фоне) — читается и из UI-, и из IO-потока.
    @Volatile
    private var guidMap: Map<String, String> = emptyMap()
    private var pendingCountry: String? = null

    @Volatile
    private var activeServers: LinkedHashMap<String, String> = LinkedHashMap(FALLBACK_SERVERS)
    @Volatile
    private var activePingHosts: Map<String, String> = FALLBACK_PING_HOSTS

    // Корутина пуша трафика (сбор счётчиков в фоне, чтобы не блокировать UI WebView).
    @Volatile
    private var trafficJob: kotlinx.coroutines.Job? = null

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
            // Разрешаем локальной странице (file://) делать fetch к нашему HTTPS-бэкенду
            // профиля/оплаты (CORS на сервере открыт). Без этого file:// не может в сеть.
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
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
            fetchAndApplyRemoteConfigs()
        }
    }

    private fun serversVersion(): String = activeServers.values.joinToString("|").hashCode().toString()

    private fun loadRemoteServersIfAny() {
        val raw = MmkvManager.decodeSettingsString(REMOTE_CONFIGS_KEY) ?: return
        try {
            val configs = org.json.JSONObject(raw).getJSONArray("configs")
            if (configs.length() == 0) return
            val newServers = LinkedHashMap<String, String>()
            val newPing = mutableMapOf<String, String>()
            for (i in 0 until configs.length()) {
                val c = configs.getJSONObject(i)
                newServers[c.getString("country")] = c.getString("json")
                val host = c.optString("host", "")
                if (host.isNotEmpty()) newPing[c.getString("country")] = host
            }
            if (newServers.isNotEmpty()) {
                activeServers = newServers
                if (newPing.isNotEmpty()) activePingHosts = newPing
            }
        } catch (_: Exception) {}
    }

    private fun fetchAndApplyRemoteConfigs() {
        try {
            val conn = java.net.URL("${BuildConfig.BACKEND_URL}/api/configs")
                .openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) return
            val text = conn.inputStream.bufferedReader().readText()
            val jo = org.json.JSONObject(text)
            val configs = jo.getJSONArray("configs")
            if (configs.length() == 0) return
            val newServers = LinkedHashMap<String, String>()
            val newPing = mutableMapOf<String, String>()
            for (i in 0 until configs.length()) {
                val c = configs.getJSONObject(i)
                newServers[c.getString("country")] = c.getString("json")
                val host = c.optString("host", "")
                if (host.isNotEmpty()) newPing[c.getString("country")] = host
            }
            if (newServers.isEmpty()) return
            MmkvManager.encodeSettings(REMOTE_CONFIGS_KEY, text)
            val remoteVersion = newServers.values.joinToString("|").hashCode().toString()
            val localVersion = activeServers.values.joinToString("|").hashCode().toString()
            if (remoteVersion != localVersion) {
                activeServers = newServers
                if (newPing.isNotEmpty()) activePingHosts = newPing
                MmkvManager.encodeSettings(CONFIG_VERSION_KEY, "")
                ensureServers()
            }
        } catch (_: Exception) {}
    }

    private fun ensureServers() {
        try {
            loadRemoteServersIfAny()
            val desiredVersion = serversVersion()
            val storedVersion = MmkvManager.decodeSettingsString(CONFIG_VERSION_KEY)

            if (storedVersion == desiredVersion) {
                val map = activeServers.keys.mapNotNull { country ->
                    val g = MmkvManager.decodeSettingsString("luna_guid_$country")
                    if (!g.isNullOrEmpty() && MmkvManager.decodeServerConfig(g) != null) country to g
                    else null
                }.toMap()
                if (map.size == activeServers.size) {
                    guidMap = map
                    return
                }
            }

            activeServers.keys.forEach { country ->
                val oldGuid = MmkvManager.decodeSettingsString("luna_guid_$country")
                if (!oldGuid.isNullOrEmpty() && MmkvManager.decodeServerConfig(oldGuid) != null) {
                    MmkvManager.removeServer(oldGuid)
                }
                MmkvManager.encodeSettings("luna_guid_$country", "")
            }
            guidMap = importDefaultServer()
            MmkvManager.encodeSettings(CONFIG_VERSION_KEY, desiredVersion)
        } catch (e: Exception) {
            // не валим UI — connect() позже покажет ошибку
        }
    }

    private fun importDefaultServer(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            activeServers.forEach { (country, link) ->
                val before = MmkvManager.decodeServerList("").toSet()
                AngConfigManager.importBatchConfig(link, "", true)
                val newGuid = MmkvManager.decodeServerList("").firstOrNull { it !in before }
                if (newGuid != null) {
                    map[country] = newGuid
                    MmkvManager.encodeSettings("luna_guid_$country", newGuid)
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

    private fun isOurServer(guid: String): Boolean {
        return activeServers.keys.any { country ->
            MmkvManager.decodeSettingsString("luna_guid_$country") == guid
        }
    }

    private fun connectFlow(country: String?) {
        // Корутина на главном диспетчере. ВСЯ работа с MMKV/диском/парсингом конфигов
        // (включая декод "тяжёлого" профиля с большим pqv) уносится в ОДИН IO-блок,
        // чтобы тап Connect не блокировал UI. На главном потоке остаются только
        // системный VPN-диалог и старт сервиса.
        lifecycleScope.launch {
            val guid = withContext(Dispatchers.IO) {
                // initAssets не нужен здесь повторно — уже вызван при onCreate в фоне.
                // При пустой карте серверов восстановить её (редкий кейс первого запуска).
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

    /**
     * Пуш трафика в WebView. Счётчики читаются на ФОНОВОМ потоке (Dispatchers.IO),
     * в главный поток выходим только на короткий evaluateJavascript с готовыми числами.
     */
    private fun startTrafficPush() {
        if (trafficJob?.isActive == true) return
        trafficJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val rx = TrafficStats.getTotalRxBytes().coerceAtLeast(0)
                val tx = TrafficStats.getTotalTxBytes().coerceAtLeast(0)
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("window.lunaOnTraffic && window.lunaOnTraffic($rx, $tx)", null)
                }
                delay(1500)
            }
        }
    }

    private fun stopTrafficPush() {
        trafficJob?.cancel()
        trafficJob = null
    }

    private fun pushState(running: Boolean) {
        runOnUiThread {
            webView.evaluateJavascript("window.lunaOnState && window.lunaOnState($running)", null)
        }
    }

    /** Измерение пинга до сервера */
    private fun measurePing(country: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val host = activePingHosts[country] ?: return@launch
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
        stopTrafficPush()
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

        /**
         * Запускает ПУШ трафика: каждые ~1.5с собираем счётчики в ФОНЕ и пушим в WebView.
         * Раньше JS дёргал синхронный getTraffic() с UI-потока WebView — это блокировало
         * рендерер на IPC и давало периодический микрофриз. Теперь главный поток не ждёт.
         */
        @JavascriptInterface
        fun startTraffic() {
            startTrafficPush()
        }

        @JavascriptInterface
        fun stopTraffic() {
            stopTrafficPush()
        }

        @JavascriptInterface
        fun requestIp() {
            fetchIp()
        }

        @JavascriptInterface
        fun requestPing(country: String) {
            measurePing(country)
        }

        /**
         * Стабильный HWID устройства = SHA-256(ANDROID_ID + packageName), hex (32 симв.).
         * Привязка профиля к устройству делается на бэкенде по этому HWID.
         * Вычисление мгновенное, без сети/диска.
         */
        @JavascriptInterface
        fun getHwid(): String {
            return try {
                val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
                val raw = androidId + "|" + BuildConfig.APPLICATION_ID
                val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
                digest.joinToString("") { "%02X".format(it) }.take(32)
            } catch (e: Exception) {
                ""
            }
        }

        /** Открывает ссылку оплаты во внешнем браузере (СБП/карта). */
        @JavascriptInterface
        fun openUrl(url: String?) {
            val u = url?.trim().orEmpty()
            if (u.isEmpty()) return
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(u))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this@LunaActivity, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
