#!/bin/bash
echo "==================================="
echo "🚀 Luna VPN - Запуск проекта"
echo "==================================="
echo ""

# Проверка xray
if [ -f "xray.exe" ]; then
    echo "✅ Xray установлен ($(ls -lh xray.exe | awk '{print $5}'))"
else
    echo "❌ Xray не найден!"
    exit 1
fi

# Запуск сервера
echo "🔄 Запуск Node.js сервера..."
pkill -f "node server.js" 2>/dev/null
node server.js > server.log 2>&1 &
sleep 2

# Проверка сервера
if curl -s http://127.0.0.1:8080/api/health > /dev/null; then
    echo "✅ Сервер запущен на http://127.0.0.1:8080"
else
    echo "❌ Ошибка запуска сервера"
    exit 1
fi

echo ""
echo "==================================="
echo "✅ Luna VPN успешно запущен!"
echo "==================================="
echo ""
echo "🌐 Веб-интерфейс: http://127.0.0.1:8080"
echo "🔌 SOCKS5: 127.0.0.1:1080"
echo "🔌 HTTP: 127.0.0.1:1081"
echo ""
echo "📱 Для сборки Android APK:"
echo "   cd v2rayNG/V2rayNG"
echo "   ./gradlew assembleDebug"
echo ""
