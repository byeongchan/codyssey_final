"""Read only explicitly selected settings; never evaluate dotenv text or export values."""
import json
import os
from pathlib import Path

RSS_ENV_FILE = Path(__file__).resolve().parents[1] / ".env"
DEFAULT_FEEDS = json.dumps([{"name": "연합뉴스", "url": "https://www.yna.co.kr/rss/news.xml"}], ensure_ascii=False)
DEFAULT_HOSTS = "www.yna.co.kr,yna.co.kr"


def local_settings(defaults):
    values = {name: os.getenv(name, default) for name, default in defaults.items()}
    # Explicit local values replace stale variables inherited from a development shell.
    if RSS_ENV_FILE.is_file():
        for line in RSS_ENV_FILE.read_text(encoding="utf-8-sig").splitlines():
            name, separator, value = line.partition("=")
            name = name.strip()
            if not separator or name not in values:
                continue
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            values[name] = value
    return values


def rss_settings():
    values = local_settings({"RSS_FEEDS_JSON": DEFAULT_FEEDS, "RSS_ALLOWED_HOSTS": DEFAULT_HOSTS})
    return values["RSS_FEEDS_JSON"], values["RSS_ALLOWED_HOSTS"]
