# 🎧 Telegram Music Bio

Скрипт для автоматического обновления описания («О себе») в вашем профиле Telegram под играющую в данный момент музыку в **Spotify** (а также любом другом плеере, подключенном к Last.fm).

```text
сейчас слушает: ssshhhiiittt! — май [01:10 / 07:29]
```

### ✨ Особенности
* **100% бесплатно:** не требуется ни Spotify Premium, ни Telegram Premium.
* **Таймер прогресса:** отображает, сколько длится трек и общую длину композиции.
* **Авто-восстановление:** при паузе, переключении или выключении музыки автоматически возвращает ваше исходное описание профиля.
* **Контроль лимитов Telegram:** автоматически сокращает длинные названия песен, чтобы вписаться в лимит 70 символов (или 140 для Telegram Premium), не обрезая таймер.
* **Защита от FloodWait:** безопасный интервал обновления, защита от блокировок Telegram API.
* **Работает везде:** на телефоне, ПК, умной колонке или консоли — трек обновляется отовсюду.

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

LASTFM_API_KEY=ваш_lastfm_api_key
LASTFM_USERNAME=ваш_логин_lastfm

# Необязательные параметры:
DEFAULT_BIO=
UPDATE_INTERVAL=10
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

### 6. Запуск

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
