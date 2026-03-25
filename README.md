# VPN Auto Manager

Android-приложение для автоматического обновления VPN-подписок из репозитория
[igareck/vpn-configs-for-russia](https://github.com/igareck/vpn-configs-for-russia)
и управления v2rayNG.

---

## Возможности

- 🔄 **Автообновление** подписок каждые N часов (WorkManager, работает в фоне)
- 📡 **TCP-пинг** серверов параллельно — показывает реальную задержку
- 🏆 **Выбор лучшего** сервера автоматически
- ▶️ **Быстрый импорт** конфигов и подписок в v2rayNG
- 📋 Поддержка всех протоколов: VLESS, VMess, Shadowsocks, Trojan, Hysteria2, TUIC
- ⚙️ Все 7 подписок из репозитория встроены (чёрные и белые списки)
- ➕ Добавление своих подписок

---

## Сборка в Android Studio

### Требования
- Android Studio Hedgehog или новее
- JDK 17
- Android SDK 34

### Шаги
1. Открыть папку `VpnAutoManager` в Android Studio
2. Дождаться синхронизации Gradle
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**
4. APK окажется в `app/build/outputs/apk/debug/`

---

## Использование

1. Установить **v2rayNG** с [GitHub](https://github.com/2dust/v2rayNG/releases)
2. Установить **VPN Auto Manager**
3. В VPN Auto Manager:
   - Включить нужные подписки (по умолчанию включена `⚫ VLESS телефон`)
   - Нажать **🔄 Обновить** — скачает конфиги и протестирует пинг
   - Нажать **⚡ Подключить** — откроет v2rayNG с лучшим сервером в буфере
   - В v2rayNG нажать "Добавить из буфера обмена"
4. Настроить интервал автообновления в **⚙️ Настройках**

### Импорт подписок напрямую в v2rayNG
Нажмите кнопку **→ v2rayNG** напротив любой подписки —
v2rayNG откроется и предложит добавить её.

---

## Структура проекта

```
app/src/main/java/com/vpnauto/manager/
├── model/
│   └── Models.kt           — ServerConfig, Subscription, ConfigParser
├── util/
│   ├── SubscriptionRepository.kt  — загрузка и кеширование подписок
│   ├── PingTester.kt              — параллельный TCP-пинг
│   └── V2RayController.kt         — управление v2rayNG через intents
├── worker/
│   └── VpnUpdateWorker.kt         — WorkManager задача (фоновое обновление)
├── service/
│   ├── UpdateService.kt           — Foreground сервис
│   └── BootReceiver.kt            — Автозапуск после перезагрузки
└── ui/
    ├── MainActivity.kt
    ├── MainViewModel.kt
    └── Adapters.kt                — ServerAdapter, SubscriptionAdapter
```

---

## Примечания

- На **Android 12+** WorkManager может запускаться с задержкой из-за ограничений батареи.
  Добавьте приложение в исключения: *Настройки → Батарея → VPN Auto → Без ограничений*.
- На **MIUI/One UI** дополнительно разрешите автозапуск в настройках безопасности.
- Для прямого управления v2rayNG через broadcast intents может потребоваться **Shizuku**
  на Android 13+. Без него приложение использует clipboard и Intent.ACTION_VIEW.
