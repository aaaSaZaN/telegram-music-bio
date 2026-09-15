import asyncio
import logging
import signal
import sys
import time
import aiohttp
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
    """Fetch current playing track from Last.fm API."""
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
                logger.warning("Last.fm returned HTTP %s", resp.status)
                return None
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
        logger.warning("Error fetching Last.fm track: %s", e)
        return None


async def fetch_track_duration(session: aiohttp.ClientSession, artist: str, track: str) -> int | None:
    """Fetch track duration in seconds from Last.fm."""
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
        async with session.get(url, params=params, headers=headers, timeout=aiohttp.ClientTimeout(total=5)) as resp:
            if resp.status == 200:
                data = await resp.json()
                dur_ms = data.get("track", {}).get("duration", "0")
                dur_sec = int(dur_ms) // 1000
                return dur_sec if dur_sec > 0 else None
    except Exception:
        pass
    return None


def format_time(seconds: int) -> str:
    """Format seconds into MM:SS."""
    m, s = divmod(max(0, int(seconds)), 60)
    return f"{m:02d}:{s:02d}"


def format_bio(artist: str, track: str, elapsed_sec: int, total_sec: int | None) -> str:
    """Format bio: 'Сейчас играет: {artist} — {track} [{elapsed} / {total}]'."""
    time_str = format_time(elapsed_sec)
    if total_sec and total_sec > 0:
        time_part = f" [{time_str} / {format_time(total_sec)}]"
    else:
        time_part = f" [{time_str}]"

    prefix = "сейчас слушает: "
    available_chars = config.MAX_BIO_LENGTH - len(prefix) - len(time_part)
    content = f"{artist} — {track}"

    if len(content) > available_chars:
        content = content[: max(1, available_chars - 1)] + "…"

    return f"{prefix}{content}{time_part}"


async def main():
    global running

    if not config.API_ID or not config.API_HASH:
        logger.error("API_ID или API_HASH не заданы в .env! Скопируйте .env.example в .env и укажите ключи.")
        sys.exit(1)

    if not config.LASTFM_API_KEY or not config.LASTFM_USERNAME:
        logger.error("LASTFM_API_KEY или LASTFM_USERNAME не заданы в .env!")
        sys.exit(1)

    logger.info("Инициализация Telegram клиента...")
    client = TelegramClient(config.SESSION_NAME, config.API_ID, config.API_HASH)
    await client.start()

    # Получаем исходное описание профиля
    full_user = await client(GetFullUserRequest("me"))
    initial_bio = full_user.full_user.about or ""
    default_bio = config.DEFAULT_BIO if config.DEFAULT_BIO else initial_bio
    logger.info("Исходное био аккаунта: '%s'", default_bio)

    last_applied_bio = default_bio
    current_track_key = None
    track_start_time = 0.0
    track_duration = None

    async def shutdown(sig_name):
        nonlocal last_applied_bio
        logger.info("Получен сигнал %s, восстанавливаем исходное био...", sig_name)
        try:
            await client(UpdateProfileRequest(about=default_bio))
            logger.info("Исходное био восстановлено: '%s'", default_bio)
        except Exception as e:
            logger.error("Ошибка при восстановлении био: %s", e)
        finally:
            await client.disconnect()

    loop = asyncio.get_running_loop()
    for s in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(s, lambda s=s: asyncio.create_task(shutdown(s.name)))
        except NotImplementedError:
            pass

    logger.info(
        "✅ Запуск мониторинга 'Сейчас играет' (Last.fm: %s, интервал: %ds)",
        config.LASTFM_USERNAME,
        config.UPDATE_INTERVAL,
    )

    async with aiohttp.ClientSession() as http_session:
        try:
            while running and client.is_connected():
                track_info = await fetch_current_lastfm_track(http_session)

                if track_info:
                    artist, track_name = track_info
                    track_key = (artist.lower(), track_name.lower())

                    # Новый трек
                    if track_key != current_track_key:
                        current_track_key = track_key
                        track_start_time = time.time()
                        track_duration = await fetch_track_duration(http_session, artist, track_name)
                        logger.info("🎵 Обнаружен новый трек: %s — %s (длительность: %s)", artist, track_name, format_time(track_duration) if track_duration else "неизвестно")

                    elapsed_sec = int(time.time() - track_start_time)
                    if track_duration and elapsed_sec > track_duration:
                        elapsed_sec = track_duration

                    new_bio = format_bio(artist, track_name, elapsed_sec, track_duration)

                    if new_bio != last_applied_bio:
                        try:
                            await client(UpdateProfileRequest(about=new_bio))
                            last_applied_bio = new_bio
                            logger.info("🎧 Обновлено био: %s", new_bio)
                        except FloodWaitError as e:
                            logger.warning("Telegram FloodWait: ожидание %d сек...", e.seconds)
                            await asyncio.sleep(e.seconds)
                        except Exception as e:
                            logger.error("Ошибка при обновлении профиля Telegram: %s", e)
                else:
                    # Музыка на паузе или выключена
                    current_track_key = None
                    track_start_time = 0.0
                    track_duration = None

                    if last_applied_bio != default_bio:
                        try:
                            await client(UpdateProfileRequest(about=default_bio))
                            last_applied_bio = default_bio
                            logger.info("⏸ Музыка остановлена. Восстановлено био: '%s'", default_bio)
                        except FloodWaitError as e:
                            logger.warning("Telegram FloodWait: ожидание %d сек...", e.seconds)
                            await asyncio.sleep(e.seconds)
                        except Exception as e:
                            logger.error("Ошибка при восстановлении био: %s", e)

                await asyncio.sleep(config.UPDATE_INTERVAL)

        except asyncio.CancelledError:
            pass
        finally:
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
