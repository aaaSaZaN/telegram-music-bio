import os
from pathlib import Path
from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent
load_dotenv(BASE_DIR / ".env")

# Telegram credentials (https://my.telegram.org)
API_ID = int(os.getenv("API_ID", "0"))
API_HASH = os.getenv("API_HASH", "")
SESSION_NAME = str(BASE_DIR / os.getenv("SESSION_NAME", "tg_music_session"))

# Last.fm credentials (https://www.last.fm/api/account/create) - optional if using mobile app API
LASTFM_API_KEY = os.getenv("LASTFM_API_KEY", "")
LASTFM_USERNAME = os.getenv("LASTFM_USERNAME", "")

# Webhook API (для мобильного приложения / внешних плееров)
ENABLE_WEBHOOK = os.getenv("ENABLE_WEBHOOK", "true").lower() in ("true", "1", "yes")
WEBHOOK_HOST = os.getenv("WEBHOOK_HOST", "0.0.0.0")
WEBHOOK_PORT = int(os.getenv("WEBHOOK_PORT", "8088"))
WEBHOOK_SECRET = os.getenv("WEBHOOK_SECRET", "")

# Settings
DEFAULT_BIO = os.getenv("DEFAULT_BIO", "")  # If empty, saves current TG bio at startup
CHECK_INTERVAL = int(os.getenv("CHECK_INTERVAL", "5"))  # Check Last.fm status every N seconds
MAX_BIO_LENGTH = int(os.getenv("MAX_BIO_LENGTH", "70"))  # 70 for normal TG, 140 for TG Premium
