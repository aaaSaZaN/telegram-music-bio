import asyncio
import sys
from telethon import TelegramClient
import config

async def main():
    if not config.API_ID or not config.API_HASH:
        print("❌ Ошибка: API_ID или API_HASH не заданы в .env!")
        print("Скопируйте .env.example в .env и укажите ваши ключи с https://my.telegram.org")
        sys.exit(1)

    print(f"=== Авторизация в Telegram (API_ID: {config.API_ID}) ===")
    client = TelegramClient(config.SESSION_NAME, config.API_ID, config.API_HASH)
    await client.start()
    me = await client.get_me()
    print(f"\n✅ Авторизация успешна!")
    print(f"👤 Аккаунт: {me.first_name} (@{me.username}, ID: {me.id})")
    print(f"💾 Сессия сохранена: {config.SESSION_NAME}.session\n")
    await client.disconnect()

if __name__ == "__main__":
    asyncio.run(main())
