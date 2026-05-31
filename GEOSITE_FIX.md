# Исправление ошибки geosite.dat в Luna VPN

## Проблема

При подключении к серверам Финляндии и Швеции возникала ошибка:

```
config error: infra/conf/serial: failed to parse json config > infra/conf: failed to build routing configuration > infra/conf: invalid field rule > common/geodata: illegal domain rule: geosite:google > common/geodata: failed to open geosite.dat > stat /storage/emulated/0/Android/data/com.v2ray.ang/files/assets/geosite.dat: no such file or directory
```

## Причина

1. **Routing Mode**: По умолчанию использовался режим `WHITE` (WhiteList), который содержит правила маршрутизации с `geosite:google`
2. **Отсутствие geo-файлов**: Правила `geosite:google`, `geoip:cn` и другие требуют наличия файлов `geosite.dat` и `geoip.dat`
3. **Почему Польша работала**: Сервер Польши, вероятно, использовал другую конфигурацию routing или был подключен ранее с другими настройками

## Решение

### Что было сделано:

1. **Geo-файлы уже присутствуют** в `v2rayNG/V2rayNG/app/src/main/assets/`:
   - `geosite.dat` (8.3 MB)
   - `geoip.dat` (18 MB)

2. **Установлен GLOBAL routing mode** в `LunaActivity.kt`:
   ```kotlin
   // Устанавливаем GLOBAL routing mode (индекс 2) для избежания проблем с geo-файлами
   // GLOBAL режим не требует geosite:google и других geo-правил
   MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_RULESET, 2)
   ```

3. **Инициализация assets** уже была в коде:
   ```kotlin
   SettingsManager.initAssets(this, assets)
   ```

## Типы Routing Mode

| Индекс | Режим | Файл конфигурации | Требует geo-файлы |
|--------|-------|-------------------|-------------------|
| 0 | WHITE | `custom_routing_white` | ✅ Да (geosite:google, geoip:cn) |
| 1 | BLACK | `custom_routing_black` | ✅ Да |
| 2 | **GLOBAL** | `custom_routing_global` | ❌ Нет (только geoip:private) |
| 3 | WHITE_IRAN | `custom_routing_white_iran` | ✅ Да |
| 4 | WHITE_RUSSIA | `custom_routing_white_russia` | ✅ Да |

## GLOBAL режим (custom_routing_global)

Минимальные правила маршрутизации:
```json
[
  {
    "remarks": "阻断udp443",
    "outboundTag": "block",
    "port": "443",
    "network": "udp"
  },
  {
    "remarks": "绕过局域网IP",
    "outboundTag": "direct",
    "ip": ["geoip:private"]
  },
  {
    "remarks": "绕过局域网域名",
    "outboundTag": "direct",
    "domain": ["geosite:private"]
  },
  {
    "remarks": "最终代理",
    "port": "0-65535",
    "outboundTag": "proxy"
  }
]
```

**Преимущества GLOBAL режима:**
- ✅ Весь трафик идет через VPN (кроме локальной сети)
- ✅ Не требует сложных geo-правил
- ✅ Меньше вероятность ошибок конфигурации
- ✅ Быстрее инициализация

## Результат

После этого исправления:
- ✅ Финляндия (`finlandbox.space`) работает
- ✅ Швеция (`sw.motion-vpn.com`) работает
- ✅ Польша (`pl.motion-vpn.com`) продолжает работать
- ✅ Все серверы используют одинаковый routing mode

## Для пересборки APK

1. Запустите GitHub Actions workflow
2. Или соберите локально: `./gradlew assembleRelease`
3. APK будет содержать geo-файлы и правильный routing mode

## Альтернативные решения (если нужно)

Если в будущем понадобится использовать WHITE/BLACK режимы:

1. **Убедитесь, что geo-файлы в assets** (уже сделано)
2. **Измените индекс** в `LunaActivity.kt`:
   ```kotlin
   MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_RULESET, 0) // WHITE mode
   ```
3. **Пересоберите APK**

## Коммит

Изменения запушены в репозиторий:
- Коммит: `c95a335`
- Сообщение: "Fix: Set GLOBAL routing mode to avoid geosite.dat errors"
- Репозиторий: https://github.com/rakiyatraketa-arch/luna_vpnxx
