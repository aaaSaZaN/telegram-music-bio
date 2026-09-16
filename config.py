import os
from pathlib import Path
from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent
load_dotenv(BASE_DIR / ".env")

# Telegram credentials (https://my.telegram.org)
API_ID = int(os.getenv("API_ID", "0"))
API_HASH = os.getenv("API_HASH", "")
SESSION_NAME = str(BASE_DIR / os.getenv("SESSION_NAME", "tg_music_session"))

# Last.fm credentials (https://www.last.fm/api/account/create)
LASTFM_API_KEY = os.getenv("LASTFM_API_KEY", "")
LASTFM_USERNAME = os.getenv("LASTFM_USERNAME", "")

# Settings
DEFAULT_BIO = os.getenv("DEFAULT_BIO", "")  # If empty, saves current TG bio at startup
CHECK_INTERVAL = int(os.getenv("CHECK_INTERVAL", "5"))  # Check music status every N seconds
MAX_BIO_LENGTH = int(os.getenv("MAX_BIO_LENGTH", "70"))  # 70 for normal TG, 140 for TG Premium
