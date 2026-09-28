import asyncio
import logging
import signal
import sys
import aiohttp
from aiohttp import web
from telethon import TelegramClient
from telethon.tl.functions.account import UpdateProfileRequest
from telethon.tl.functions.users import GetFullUserRequest
from telethon.errors import FloodWaitError

import config

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger("tg-music-bio")

running = True


async def fetch_current_lastfm_track(session: aiohttp.ClientSession):
    """
    Fetch current playing track from Last.fm API.

    Returns:
        (artist, track_name) if track is currently playing.
        None if Last.fm responded 200 and no track is playing.
        False if an API or network error occurred.
    """
    if not config.LASTFM_API_KEY or not config.LASTFM_USERNAME:
        return None

    url = "https://ws.audioscrobbler.com/2.0/"
    params = {
        "method": "user.getrecenttracks",
        "user": config.LASTFM_USERNAME,
        "api_key": config.LASTFM_API_KEY,
        "format": "json",
        "limit": "1",
    }
    headers = {"User-Agent": f"TgMusicBio/1.0 ({config.LASTFM_USERNAME})"}

    try:
        async with session.get(url, params=params, headers=headers, timeout=aiohttp.ClientTimeout(total=8)) as resp:
            if resp.status != 200:
                logger.warning("Last.fm вернул HTTP %s (временный сбой API)", resp.status)
                return False
            data = await resp.json()
            recent = data.get("recenttracks", {})
            tracks = recent.get("track", [])
            if isinstance(tracks, dict):
                tracks = [tracks]
            if not tracks:
                return None

            first_track = tracks[0]
            if first_track.get("@attr", {}).get("nowplaying") == "true":
                artist = first_track.get("artist", {}).get("#text", "").strip()
                track_name = first_track.get("name", "").strip()
                if artist and track_name:
                    return artist, track_name
            return None
    except Exception as e:
        logger.warning("Ошибка при запросе к Last.fm (временный сбой сети): %s", e)
        return False


async def fetch_track_duration(session: aiohttp.ClientSession, artist: str, track: str) -> int | None:
    """Fetch track duration in seconds from Last.fm, with fallback to Deezer API."""
    # 1. Пробуем получить длительность из базы Last.fm
    if config.LASTFM_API_KEY:
        url = "https://ws.audioscrobbler.com/2.0/"
        params = {
            "method": "track.getInfo",
            "artist": artist,
            "track": track,
            "api_key": config.LASTFM_API_KEY,
            "format": "json",
        }
        headers = {"User-Agent": f"TgMusicBio/1.0 ({config.LASTFM_USERNAME})"}

        try:
            async with session.get(url, params=params, headers=headers, timeout=aiohttp.ClientTimeout(total=4)) as resp:
                if resp.status == 200:
                    data = await resp.json()
                    dur_ms = data.get("track", {}).get("duration", "0")
                    dur_sec = int(dur_ms) // 1000
                    if dur_sec > 0:
                        return dur_sec
        except Exception:
            pass

    # 2. Fallback: у многих новинок, ремиксов и инди-треков в Last.fm длительность равна 0.
    # Опрашиваем публичный каталог Deezer для точного получения длины композиции.
    try:
        query = f"{artist} {track}".strip()
        deezer_url = "https://api.deezer.com/search"
        headers = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
        async with session.get(deezer_url, params={"q": query, "limit": 3}, headers=headers, timeout=aiohttp.ClientTimeout(total=4)) as resp:
            if resp.status == 200:
                data = await resp.json()
                for item in data.get("data", []):
                    d_sec = item.get("duration")
                    if d_sec and int(d_sec) > 0:
                        return int(d_sec)
    except Exception:
        pass

    return None


def format_time(seconds: int) -> str:
    """Format seconds into MM:SS."""
    m, s = divmod(max(0, int(seconds)), 60)
    return f"{m:02d}:{s:02d}"


def format_bio(artist: str, track: str, total_sec: int | None) -> str:
    """Format bio: 'сейчас слушает: {artist} — {track} ({duration})'."""
    prefix = "сейчас слушает: "
    time_part = f" ({format_time(total_sec)})" if total_sec and total_sec > 0 else ""

    available_chars = config.MAX_BIO_LENGTH - len(prefix) - len(time_part)
    content = f"{artist} — {track}" if artist else track

    if len(content) > available_chars:
        content = content[: max(1, available_chars - 1)] + "…"

    return f"{prefix}{content}{time_part}"


async def main():
    global running

    if not config.API_ID or not config.API_HASH:
        logger.error("API_ID или API_HASH не заданы в .env! Скопируйте .env.example в .env и укажите ключи.")
        sys.exit(1)

    logger.info("Инициализация Telegram клиента...")
    client = TelegramClient(config.SESSION_NAME, config.API_ID, config.API_HASH)
    await client.start()

    # Определение дефолтного описания профиля
    full_user = await client(GetFullUserRequest("me"))
    initial_bio = full_user.full_user.about or ""

    if config.DEFAULT_BIO:
        default_bio = config.DEFAULT_BIO
    elif "сейчас слушает" in initial_bio.lower() or "сейчас ничего не слушает" in initial_bio.lower():
        default_bio = ""
    else:
        default_bio = initial_bio

    logger.info("Дефолтное био при паузе/выключении: '%s'", default_bio)

    last_applied_bio = default_bio
    current_track_key = None
    is_spotify_playing = False
    phone_state = {"artist": "", "track": "", "duration": None, "playing": False}

    runner = None

    async def shutdown(sig_name):
        nonlocal last_applied_bio, runner
        logger.info("Получен сигнал %s, восстанавливаем исходное био...", sig_name)
        try:
            await client(UpdateProfileRequest(about=default_bio))
            logger.info("Исходное био восстановлено: '%s'", default_bio)
        except Exception as e:
            logger.error("Ошибка при восстановлении био: %s", e)
        finally:
            if runner:
                await runner.cleanup()
            await client.disconnect()

    loop = asyncio.get_running_loop()
    for s in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(s, lambda s=s: asyncio.create_task(shutdown(s.name)))
        except NotImplementedError:
            pass

    # ==========================================
    # Webhook API для мобильного приложения (Android)
    # ==========================================
    if config.ENABLE_WEBHOOK:
        async def handle_health(request):
            return web.json_response({
                "status": "ok",
                "app": "TelegramMusicBio",
                "current_bio": last_applied_bio,
                "spotify_playing": is_spotify_playing,
                "phone_playing": bool(phone_state.get("playing")),
            })

        async def handle_now_playing(request):
            nonlocal last_applied_bio, current_track_key, phone_state

            # Проверка секретного ключа (если задан)
            if config.WEBHOOK_SECRET:
                token = request.headers.get("X-Api-Key")
                if not token:
                    auth_header = request.headers.get("Authorization", "")
                    if auth_header.startswith("Bearer "):
                        token = auth_header[7:].strip()
                if token != config.WEBHOOK_SECRET:
                    logger.warning("Попытка неавторизованного запроса к Webhook API")
                    return web.json_response({"error": "Unauthorized"}, status=401)

            try:
                data = await request.json()
            except Exception:
                return web.json_response({"error": "Invalid JSON"}, status=400)

            artist = str(data.get("artist", "")).strip()
            track = str(data.get("track", "") or data.get("title", "")).strip()
            is_playing = bool(data.get("playing", True))
            raw_duration = data.get("duration")

            duration_sec = None
            if raw_duration:
                try:
                    if isinstance(raw_duration, (int, float)):
                        duration_sec = int(raw_duration)
                    elif isinstance(raw_duration, str):
                        if ":" in raw_duration:
                            parts = raw_duration.split(":")
                            if len(parts) == 2:
                                duration_sec = int(parts[0]) * 60 + int(parts[1])
                        else:
                            duration_sec = int(raw_duration)
                except Exception:
                    pass

            phone_state = {
                "artist": artist,
                "track": track,
                "duration": duration_sec,
                "playing": is_playing,
            }

            # ПРИОРИТЕТ SPOTIFY:
            # Если сейчас играет Spotify, статус телефона запоминается, но не перебивает био
            if is_spotify_playing:
                logger.info(
                    "📱 [Webhook] Телефон прислал трек ('%s — %s'), но сейчас играет Spotify (приоритет у Spotify)",
                    artist, track
                )
                return web.json_response({
                    "status": "ok",
                    "priority": "spotify",
                    "bio": last_applied_bio,
                })

            if is_playing and (artist or track):
                track_key = (artist.lower(), track.lower())
                if track_key != current_track_key:
                    current_track_key = track_key
                    new_bio = format_bio(artist, track, duration_sec)
                    if new_bio != last_applied_bio:
                        try:
                            await client(UpdateProfileRequest(about=new_bio))
                            last_applied_bio = new_bio
                            logger.info("📱 [Webhook] Обновлено био в Telegram: %s", new_bio)
                        except FloodWaitError as e:
                            logger.warning("Telegram FloodWait: ожидание %d сек...", e.seconds)
                            return web.json_response({"status": "flood_wait", "wait_seconds": e.seconds}, status=429)
                        except Exception as e:
                            logger.error("Ошибка при обновлении профиля Telegram: %s", e)
                            return web.json_response({"error": str(e)}, status=500)
            else:
                # Пауза или плеер закрыт на телефоне
                current_track_key = None
                if last_applied_bio != default_bio:
                    try:
                        await client(UpdateProfileRequest(about=default_bio))
                        last_applied_bio = default_bio
                        logger.info("📱 [Webhook] Музыка остановлена. Восстановлено дефолтное био: '%s'", default_bio)
                    except FloodWaitError as e:
                        logger.warning("Telegram FloodWait: ожидание %d сек...", e.seconds)
                        return web.json_response({"status": "flood_wait", "wait_seconds": e.seconds}, status=429)
                    except Exception as e:
                        logger.error("Ошибка при восстановлении био: %s", e)

            return web.json_response({"status": "ok", "bio": last_applied_bio})

        app = web.Application()
        app.router.add_get("/health", handle_health)
        app.router.add_post("/api/now-playing", handle_now_playing)

        runner = web.AppRunner(app)
        await runner.setup()
        site = web.TCPSite(runner, config.WEBHOOK_HOST, config.WEBHOOK_PORT)
        await site.start()
        logger.info("🚀 Webhook API запущен на http://%s:%d/api/now-playing", config.WEBHOOK_HOST, config.WEBHOOK_PORT)

    # Режим работы
    has_lastfm = bool(config.LASTFM_API_KEY and config.LASTFM_USERNAME)
    if has_lastfm:
        logger.info(
            "✅ Запуск мониторинга Last.fm / Spotify (%s, проверка каждые %ds, высший приоритет)",
            config.LASTFM_USERNAME,
            config.CHECK_INTERVAL,
        )
    else:
        logger.info("ℹ️ Last.fm не настроен. Мониторинг работает только через Webhook API.")

    async with aiohttp.ClientSession() as http_session:
        try:
            while running and client.is_connected():
                if has_lastfm:
                    track_info = await fetch_current_lastfm_track(http_session)

                    # При временных сетевых сбоях не сбрасываем статус
                    if track_info is not False:
                        if track_info is not None:
                            # Spotify сейчас активно воспроизводит трек
                            is_spotify_playing = True
                            artist, track_name = track_info
                            track_key = (artist.lower(), track_name.lower())

                            if track_key != current_track_key:
                                current_track_key = track_key
                                track_duration = await fetch_track_duration(http_session, artist, track_name)
                                dur_text = format_time(track_duration) if track_duration else "неизвестно"
                                logger.info("🎵 [Spotify] Новый трек: %s — %s (длина: %s)", artist, track_name, dur_text)

                                new_bio = format_bio(artist, track_name, track_duration)
                                if new_bio != last_applied_bio:
                                    try:
                                        await client(UpdateProfileRequest(about=new_bio))
                                        last_applied_bio = new_bio
                                        logger.info("🎧 [Spotify] Обновлено био в Telegram: %s", new_bio)
                                    except FloodWaitError as e:
                                        logger.warning("Telegram FloodWait: ожидание %d сек...", e.seconds)
                                        await asyncio.sleep(e.seconds)
                                    except Exception as e:
                                        logger.error("Ошибка при обновлении профиля Telegram: %s", e)
                        else:
                            # Spotify остановлен / на паузе
                            was_spotify = is_spotify_playing
                            is_spotify_playing = False

                            if was_spotify:
                                logger.info("⏸ [Spotify] Воспроизведение Spotify остановлено.")
                                # Если в данный момент играет музыка на телефоне, бесшовно переключаемся на неё
                                if phone_state.get("playing") and (phone_state.get("artist") or phone_state.get("track")):
                                    phone_artist = phone_state["artist"]
                                    phone_track = phone_state["track"]
                                    phone_dur = phone_state.get("duration")
                                    current_track_key = (phone_artist.lower(), phone_track.lower())
                                    new_bio = format_bio(phone_artist, phone_track, phone_dur)
                                    if new_bio != last_applied_bio:
                                        try:
                                            await client(UpdateProfileRequest(about=new_bio))
                                            last_applied_bio = new_bio
                                            logger.info("📱 [Fallback] Переключено на играющий трек с телефона: %s", new_bio)
                                        except Exception as e:
                                            logger.error("Ошибка при переключении на телефон: %s", e)
                                else:
                                    current_track_key = None
                                    if last_applied_bio != default_bio:
                                        try:
                                            await client(UpdateProfileRequest(about=default_bio))
                                            last_applied_bio = default_bio
                                            logger.info("⏸ Восстановлено дефолтное био: '%s'", default_bio)
                                        except FloodWaitError as e:
                                            logger.warning("Telegram FloodWait: ожидание %d сек...", e.seconds)
                                            await asyncio.sleep(e.seconds)
                                        except Exception as e:
                                            logger.error("Ошибка при восстановлении био: %s", e)

                await asyncio.sleep(config.CHECK_INTERVAL)

        except asyncio.CancelledError:
            pass
        finally:
            if runner:
                await runner.cleanup()
            if client.is_connected():
                try:
                    await client(UpdateProfileRequest(about=default_bio))
                except Exception:
                    pass
                await client.disconnect()
            logger.info("Скрипт завершил работу.")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except (KeyboardInterrupt, SystemExit):
        pass
