from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

import httpx
from telegram import Bot, InlineKeyboardButton, InlineKeyboardMarkup, Update
from telegram.constants import ParseMode

STRAVA_API_BASE = "https://www.strava.com/api/v3"
DEFAULT_STATE_FILE = Path(".strava-monitor-state.json")
DEFAULT_CONFIG_FILE = Path(".strava-monitor-config.json")


@dataclass
class UserState:
    last_activity_id: int | None = None
    last_activity_start: str | None = None


@dataclass
class MonitorState:
    users: dict[int, UserState] = field(default_factory=dict)
    last_update_id: int | None = None
    pending_token_chats: set[int] = field(default_factory=set)


@dataclass
class MonitorConfig:
    client_id: str | None = None
    client_secret: str | None = None
    telegram_bot_token: str | None = None
    users: dict[int, "UserConfig"] = field(default_factory=dict)


@dataclass
class UserConfig:
    chat_id: int
    access_token: str


@dataclass
class Activity:
    activity_id: int
    name: str
    start_date: str
    activity_type: str
    distance_m: float
    moving_time_s: int
    elapsed_time_s: int

    @classmethod
    def from_api(cls, payload: dict[str, Any]) -> "Activity":
        return cls(
            activity_id=payload["id"],
            name=payload.get("name", ""),
            start_date=payload.get("start_date"),
            activity_type=payload.get("type", ""),
            distance_m=float(payload.get("distance", 0.0)),
            moving_time_s=int(payload.get("moving_time", 0)),
            elapsed_time_s=int(payload.get("elapsed_time", 0)),
        )


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Monitor Strava for new activities and run actions.",
    )
    subparsers = parser.add_subparsers(dest="command")

    setup_parser = subparsers.add_parser(
        "setup", help="Guide setup and save Strava credentials."
    )
    setup_parser.add_argument(
        "--config-file",
        type=Path,
        default=DEFAULT_CONFIG_FILE,
        help=f"Config file path (default: {DEFAULT_CONFIG_FILE}).",
    )

    run_parser = subparsers.add_parser(
        "run", help="Run the activity monitor (default)."
    )
    run_parser.add_argument(
        "--poll-interval",
        type=int,
        default=300,
        help="Seconds between polls (default: 300).",
    )
    run_parser.add_argument(
        "--once",
        action="store_true",
        help="Run a single poll and exit.",
    )
    run_parser.add_argument(
        "--state-file",
        type=Path,
        default=DEFAULT_STATE_FILE,
        help=f"State file path (default: {DEFAULT_STATE_FILE}).",
    )
    run_parser.add_argument(
        "--log-existing",
        action="store_true",
        help="Log existing activities on first run instead of bootstrapping.",
    )
    run_parser.add_argument(
        "--per-page",
        type=int,
        default=30,
        help="Number of activities to fetch per poll (default: 30).",
    )
    run_parser.add_argument(
        "--config-file",
        type=Path,
        default=DEFAULT_CONFIG_FILE,
        help=f"Config file path (default: {DEFAULT_CONFIG_FILE}).",
    )

    args = parser.parse_args(argv)
    if args.command is None:
        args.command = "run"
    return args


def load_state(path: Path) -> MonitorState:
    if not path.exists():
        return MonitorState()
    with path.open("r", encoding="utf-8") as handle:
        payload = json.load(handle)
    users_payload = payload.get("users", {})
    users: dict[int, UserState] = {}
    for chat_id_str, user_payload in users_payload.items():
        try:
            chat_id = int(chat_id_str)
        except (TypeError, ValueError):
            continue
        users[chat_id] = UserState(
            last_activity_id=user_payload.get("last_activity_id"),
            last_activity_start=user_payload.get("last_activity_start"),
        )
    return MonitorState(
        users=users,
        last_update_id=payload.get("last_update_id"),
        pending_token_chats=set(payload.get("pending_token_chats", [])),
    )


def save_state(path: Path, state: MonitorState) -> None:
    payload = {
        "last_update_id": state.last_update_id,
        "pending_token_chats": sorted(state.pending_token_chats),
        "users": {
            str(chat_id): {
                "last_activity_id": user_state.last_activity_id,
                "last_activity_start": user_state.last_activity_start,
            }
            for chat_id, user_state in state.users.items()
        },
    }
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def load_config(path: Path) -> MonitorConfig:
    if not path.exists():
        return MonitorConfig()
    with path.open("r", encoding="utf-8") as handle:
        payload = json.load(handle)
    users_payload = payload.get("users", {})
    users: dict[int, UserConfig] = {}
    for chat_id_str, user_payload in users_payload.items():
        try:
            chat_id = int(chat_id_str)
        except (TypeError, ValueError):
            continue
        access_token = user_payload.get("access_token")
        if access_token:
            users[chat_id] = UserConfig(
                chat_id=chat_id,
                access_token=access_token,
            )
    return MonitorConfig(
        client_id=payload.get("client_id"),
        client_secret=payload.get("client_secret"),
        telegram_bot_token=payload.get("telegram_bot_token"),
        users=users,
    )


def save_config(path: Path, config: MonitorConfig) -> None:
    payload = {
        "client_id": config.client_id,
        "client_secret": config.client_secret,
        "telegram_bot_token": config.telegram_bot_token,
        "users": {
            str(chat_id): {
                "chat_id": user.chat_id,
                "access_token": user.access_token,
            }
            for chat_id, user in config.users.items()
        },
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def fetch_activities(client: httpx.Client, per_page: int) -> list[Activity]:
    response = client.get(
        f"{STRAVA_API_BASE}/athlete/activities",
        params={"per_page": per_page, "page": 1},
    )
    response.raise_for_status()
    payload = response.json()
    return [Activity.from_api(item) for item in payload]


def parse_start_date(start_date: str | None) -> datetime | None:
    if not start_date:
        return None
    try:
        return datetime.fromisoformat(start_date.replace("Z", "+00:00"))
    except ValueError:
        return None


def new_activities(
    activities: Iterable[Activity], state: UserState
) -> list[Activity]:
    if state.last_activity_id is None and state.last_activity_start is None:
        return list(activities)

    last_start_dt = parse_start_date(state.last_activity_start)
    new_items = []
    for activity in activities:
        if state.last_activity_id and activity.activity_id > state.last_activity_id:
            new_items.append(activity)
            continue
        if last_start_dt:
            activity_start = parse_start_date(activity.start_date)
            if activity_start and activity_start > last_start_dt:
                new_items.append(activity)
    return new_items


def format_activity(activity: Activity) -> str:
    distance_km = activity.distance_m / 1000.0
    return (
        f"{activity.activity_id} | {activity.name} | {activity.activity_type} | "
        f"{activity.start_date} | {distance_km:.2f} km | "
        f"moving {activity.moving_time_s}s"
    )


def prompt_input(prompt: str) -> str:
    return input(prompt).strip()


def run_setup(config_path: Path) -> int:
    print("Strava setup starting.")
    print("Create or view your Strava API app at:")
    print("https://www.strava.com/settings/api\n")

    client_id = prompt_input("Enter your Strava client ID: ")
    client_secret = prompt_input("Enter your Strava client secret: ")
    telegram_bot_token = prompt_input("Enter your Telegram bot token: ")

    if not client_id or not client_secret or not telegram_bot_token:
        print("Client ID, client secret, and Telegram bot token are required.")
        return 1

    config = MonitorConfig(
        client_id=client_id,
        client_secret=client_secret,
        telegram_bot_token=telegram_bot_token,
    )
    save_config(config_path, config)
    print(f"\nSaved configuration to {config_path}")
    return 0


def update_state_from_latest(state: UserState, activities: list[Activity]) -> None:
    if not activities:
        return
    latest = max(
        activities,
        key=lambda activity: parse_start_date(activity.start_date)
        or datetime.min.replace(tzinfo=timezone.utc),
    )
    state.last_activity_id = latest.activity_id
    state.last_activity_start = latest.start_date


def run_once(
    client: httpx.Client,
    state: UserState,
    per_page: int,
    log_existing: bool,
) -> list[Activity]:
    activities = fetch_activities(client, per_page)
    if not activities:
        logging.info("No activities returned from Strava.")
        return []

    if state.last_activity_id is None and state.last_activity_start is None:
        if log_existing:
            logging.info("Bootstrapping with existing activities (logging all).")
            new_items = activities
        else:
            logging.info("Bootstrapping state without logging existing activities.")
            update_state_from_latest(state, activities)
            return []
    else:
        new_items = new_activities(activities, state)

    if not new_items:
        logging.info("No new activities found.")
        return []

    update_state_from_latest(state, activities)
    return sorted(new_items, key=lambda item: item.start_date)


def run_async(coro: Any) -> Any:
    return asyncio.run(coro)


async def send_telegram_message(
    bot: Bot,
    chat_id: int,
    text: str,
    reply_markup: InlineKeyboardMarkup | None = None,
) -> None:
    await bot.send_message(
        chat_id=chat_id,
        text=text,
        reply_markup=reply_markup,
        parse_mode=ParseMode.MARKDOWN,
    )


async def fetch_telegram_updates(
    bot: Bot,
    offset: int | None,
) -> list[Update]:
    return await bot.get_updates(
        offset=offset,
        allowed_updates=["message", "callback_query"],
    )


async def answer_callback(bot: Bot, callback_query_id: str) -> None:
    await bot.answer_callback_query(callback_query_id)


def build_welcome_message() -> tuple[str, InlineKeyboardMarkup]:
    text = (
        "*Welcome to Strava Monitor!*\\n\\n"
        "I can watch your Strava activities and notify you here.\\n"
        "To get started, send me your Strava access token."
    )
    keyboard = InlineKeyboardMarkup(
        [
            [
                InlineKeyboardButton(
                    "Send Strava access token",
                    callback_data="request_token",
                )
            ]
        ]
    )
    return text, keyboard


def build_token_prompt() -> str:
    return (
        "Please send your Strava access token.\\n"
        "You can paste it directly or use: `/token <ACCESS_TOKEN>`."
    )


def handle_telegram_updates(
    bot: Bot,
    config: MonitorConfig,
    state: MonitorState,
    config_path: Path,
) -> bool:
    offset = state.last_update_id + 1 if state.last_update_id is not None else None
    updates = run_async(fetch_telegram_updates(bot, offset))
    if not updates:
        return False

    max_update_id = state.last_update_id or 0
    config_updated = False

    for update in updates:
        if update.update_id is not None:
            max_update_id = max(max_update_id, update.update_id)

        if update.callback_query:
            if not update.callback_query.message:
                continue
            chat_id = update.callback_query.message.chat_id
            if update.callback_query.data == "request_token":
                state.pending_token_chats.add(chat_id)
                run_async(answer_callback(bot, update.callback_query.id))
                run_async(
                    send_telegram_message(
                        bot,
                        chat_id,
                        build_token_prompt(),
                    )
                )
            continue

        message = update.message or update.edited_message
        if not message or not message.text:
            continue
        chat_id = message.chat_id
        text = message.text.strip()

        if text.startswith("/start"):
            welcome_text, keyboard = build_welcome_message()
            run_async(send_telegram_message(bot, chat_id, welcome_text, keyboard))
            state.pending_token_chats.add(chat_id)
            run_async(send_telegram_message(bot, chat_id, build_token_prompt()))
            continue

        token = None
        if text.startswith("/token"):
            token = text.removeprefix("/token").strip()
        elif text.startswith("/"):
            run_async(
                send_telegram_message(
                    bot,
                    chat_id,
                    "I didn't recognize that command. Use the button or `/token <ACCESS_TOKEN>`.",
                )
            )
            continue
        elif chat_id in state.pending_token_chats:
            token = text

        if not token:
            run_async(send_telegram_message(bot, chat_id, build_token_prompt()))
            continue

        config.users[chat_id] = UserConfig(chat_id=chat_id, access_token=token)
        config_updated = True
        state.pending_token_chats.discard(chat_id)
        run_async(
            send_telegram_message(
                bot,
                chat_id,
                "Thanks! I've saved your token and will notify you about new activities.",
            )
        )

    if config_updated:
        save_config(config_path, config)

    if max_update_id != state.last_update_id:
        state.last_update_id = max_update_id
        return True
    return config_updated


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])

    if args.command == "setup":
        return run_setup(args.config_file)

    config = load_config(args.config_file)
    if not config.telegram_bot_token:
        logging.error(
            "Missing Telegram bot token. Run `strava-monitor setup` to configure it."
        )
        return 2

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s | %(levelname)s | %(message)s",
    )

    state = load_state(args.state_file)

    telegram_bot = Bot(token=config.telegram_bot_token)
    while True:
        state_changed = handle_telegram_updates(
            telegram_bot,
            config,
            state,
            args.config_file,
        )

        if not config.users:
            logging.info(
                "No users configured yet. Send a token to the Telegram bot."
            )
        for user in list(config.users.values()):
            headers = {"Authorization": f"Bearer {user.access_token}"}
            user_state = state.users.setdefault(user.chat_id, UserState())
            with httpx.Client(headers=headers, timeout=30.0) as client:
                new_items = run_once(
                    client,
                    user_state,
                    args.per_page,
                    args.log_existing,
                )
            if new_items:
                for activity in new_items:
                    run_async(
                        send_telegram_message(
                            telegram_bot,
                            user.chat_id,
                            f"New activity: {format_activity(activity)}",
                        )
                    )
                state_changed = True

        if state_changed:
            save_state(args.state_file, state)

        if args.once:
            break

        time.sleep(args.poll_interval)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
