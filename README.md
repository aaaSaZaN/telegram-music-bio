# 🎧 Telegram Music Bio

Скрипт и легковесное Android-приложение для автоматического обновления описания («О себе») в вашем профиле Telegram под играющую в данный момент музыку (**Spotify**, **Яндекс.Музыка**, **VK Музыка**, **Poweramp**, **YouTube Music** и любые офлайн-плееры).

```text
сейчас слушает: ssshhhiiittt! — май (07:29)
```

### ✨ Особенности
* **100% бесплатно:** не требуется ни Spotify Premium, ни Telegram Premium.
* **Без блокировок и FloodWait:** био обновляется только при смене песни или паузе (1 запрос на песню вместо спама запросами), поэтому Telegram не блокирует смену статуса.
* **📱 Android-приложение:** перехватывает медиа-сессии из любых плееров на телефоне (Яндекс, VK, Poweramp, YouTube) и отправляет по защищенному Webhook API.
* **🔋 Нулевой жор батареи:** Android-сервис работает по чистому event-driven механизму ОС (`NotificationListenerService`) и засыпает, когда музыка не меняется.
* **Быстрый отклик:** смена трека или пауза отображаются моментально.
* **Длительность композиции:** отображает общую длину трека `(03:45)`.
* **Авто-восстановление:** при паузе или выключении музыки автоматически возвращает исходное описание профиля.
* **Контроль лимитов Telegram:** автоматически сокращает длинные названия песен под лимит 70 символов (или 140 для Telegram Premium).
* **Работает везде:** на телефоне (через APK), на ПК, умной колонке или консоли (через Last.fm scrobbling).

---

## 🚀 Быстрый старт

### 1. Клонирование и установка зависимостей
Требуется **Python 3.10+**.

```bash
git clone https://github.com/aaaSaZaN/telegram-music-bio.git
cd telegram-music-bio

# Создаем виртуальное окружение
python3 -m venv venv
source venv/bin/activate  # На Windows: venv\Scripts\activate

# Устанавливаем зависимости
pip install -r requirements.txt
```

---

### 2. Получение ключей Telegram API (1 минута)
1. Перейдите на [my.telegram.org](https://my.telegram.org) и войдите по номеру телефона.
2. Откройте раздел **API development tools**.
3. Создайте приложение (любое имя, например `MusicBio`).
4. Скопируйте **`api_id`** и **`api_hash`**.

---

### 3. Подключение Last.fm к Spotify (2 минуты)
*Spotify умеет бесплатно транслировать треки в Last.fm в реальном времени, а Last.fm бесплатно отдает их через API.*

1. Зарегистрируйтесь на [last.fm](https://www.last.fm/join), если у вас еще нет аккаунта.
2. Перейдите в [last.fm/settings/applications](https://www.last.fm/settings/applications) и нажмите **Connect** напротив **Spotify Scrobbling** (нажмите «Принять/Agree»).
3. Перейдите на страницу создания API-ключа: [last.fm/api/account/create](https://www.last.fm/api/account/create).
   * Заполните **Application name** (например `TelegramBio`) и **Application description**.
   * Остальные поля можно оставить пустыми.
   * Нажмите **Submit** и скопируйте полученный **API Key**.

---

### 4. Настройка `.env`
Скопируйте пример файла конфигурации:

```bash
cp .env.example .env
```

Откройте `.env` и вставьте ваши данные:
```ini
API_ID=ваш_api_id
API_HASH=ваш_api_hash
SESSION_NAME=tg_music_session

# Last.fm (для Spotify, веб-плееров и ПК):
LASTFM_API_KEY=ваш_lastfm_api_key
LASTFM_USERNAME=ваш_логин_lastfm

# Webhook API (для Android-приложения):
WEBHOOK_HOST=0.0.0.0
WEBHOOK_PORT=8088
WEBHOOK_SECRET=любой_придуманный_секретный_ключ

# Необязательные параметры:
DEFAULT_BIO=
CHECK_INTERVAL=5
MAX_BIO_LENGTH=70
```

> **Примечание:** Если `DEFAULT_BIO` оставить пустым, скрипт автоматически запомнит ваше текущее описание профиля при первом запуске и будет возвращать его каждый раз, когда музыка выключается или ставится на паузу.

---

### 5. Разовая авторизация в Telegram
Запустите скрипт авторизации:

```bash
python3 login.py
```
* Введите номер телефона аккаунта (в формате `+7...`).
* Введите код подтверждения из Telegram.
* Создастся файл сессии `tg_music_session.session`. Повторно вводить код больше не потребуется.

---

### 6. Запуск сервера

#### Локальный запуск (Mac / Linux / Windows):
```bash
python3 main.py
```

#### Запуск на Linux-сервере (systemd, 24/7):
В репозитории уже есть готовый юнит-файл `tg-music-bio.service`.

1. Скопируйте проект в `/opt/tg-music-bio` (или отредактируйте пути в `tg-music-bio.service`).
2. Установите и запустите службу:
```bash
sudo cp tg-music-bio.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now tg-music-bio
```

* Проверить статус: `systemctl status tg-music-bio`
* Смотреть логи: `journalctl -u tg-music-bio -f`

---

## 📱 Android-приложение (TG Music Sync)

В директории `android/` находится легковесное нативное Android-приложение на Jetpack Compose, которое перехватывает воспроизведение из **любых плееров** (Яндекс.Музыка, VK Музыка, Poweramp, YouTube Music, Samsung Music, AIMP, офлайн-плееры) и передает трек в бота через Webhook.

### Преимущества приложения:
* **0% нагрузки на батарею:** приложение не висит в бесконечном цикле опроса, а спит до тех пор, пока Android OS сама не передаст событие смены трека (`NotificationListenerService`).
* **Без сторонних зависимостей:** никаких тяжелых библиотек, только стандартные системные вызовы.
* **Приватность:** никаких зашитых секретов в коде — адрес сервера и токен задаются в интерфейсе приложения и хранятся в защищенном хранилище телефона.

### Сборка и установка:
```bash
cd android

# Собрать debug APK
./gradlew assembleDebug

# Установить на подключенный телефон через ADB:
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Настройка на телефоне:
1. Запустите приложение **TG Music Sync** на телефоне.
2. Предоставьте **Доступ к уведомлениям** (необходимо Android для чтения названия трека из медиа-уведомления).
3. Отключите **Оптимизацию батареи** для приложения (важно для Samsung OneUI, чтобы система не глушила фоновый сервис).
4. Укажите:
   * **Адрес сервера:** `http://ip_вашего_сервера:8088`
   * **Секретный ключ:** ваш `WEBHOOK_SECRET` из `.env` файла сервера.
5. Нажмите **«Проверить»** (убедитесь, что сервер возвращает `200 OK`) и нажмите **«Сохранить»**.

---

## 🔌 Webhook API

Бот поднимает встроенный асинхронный HTTP-сервер на базе `aiohttp`:

* **`GET /health`** — Проверка работоспособности сервиса и текущего био в Telegram.
* **`POST /api/now-playing`** — Обновление играющего трека.
  * Заголовки: `Content-Type: application/json`, `X-Api-Key: <WEBHOOK_SECRET>` (или `Authorization: Bearer <WEBHOOK_SECRET>`).
  * Тело запроса:
    ```json
    {
      "artist": "ssshhhiiittt!",
      "track": "май",
      "duration": 449,
      "playing": true
    }
    ```
  * Если `playing: false` или трек выключен, бот автоматически восстанавливает исходное био.
