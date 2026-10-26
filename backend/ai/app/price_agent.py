"""Trusted evidence explanations and bounded RSS enrichment. No tool access for the model."""
import hashlib
import html
import ipaddress
import json
import os
import re
import socket
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone, timedelta
from urllib.parse import urlparse
from pydantic import BaseModel, ConfigDict, Field
from pathlib import Path
from typing import Literal, Annotated
from .gemini import GeminiExplanationProvider
from .rss_settings import rss_settings
from .news_relevance import category_hits

SYSTEM_PROMPT = (Path(__file__).parent / "prompts" / "price_agent.txt").read_text(encoding="utf-8")


class AgentText(BaseModel):
    model_config = ConfigDict(extra="forbid")
    decision: Literal["BUY", "WAIT", "INSUFFICIENT_DATA"]
    headline: str = Field(min_length=1, max_length=200)
    reasoning: list[Annotated[str, Field(min_length=1, max_length=300)]] = Field(max_length=8)
    risks: list[Annotated[str, Field(min_length=1, max_length=300)]] = Field(max_length=8)
    wait_condition: str | None = Field(max_length=500)
    data_limitations: list[Annotated[str, Field(min_length=1, max_length=300)]] = Field(max_length=8)
    answer: str = Field(min_length=1, max_length=2000)


class GeminiPriceAgent(GeminiExplanationProvider):
    request_timeout = 30.0
    response_timeout = 35.0
    def explain(self, evidence, intent, question="", history=None):
        prompt = SYSTEM_PROMPT
        assessment = evidence["assessment"]
        schema = AgentText.model_json_schema()
        schema["properties"]["decision"]["enum"] = [assessment["decision"]]
        schema["properties"]["wait_condition"] = (
            {"type": "null"} if assessment.get("wait_condition") is None
            else {"type": "string", "enum": [assessment["wait_condition"]]}
        )
        payload = {"systemInstruction": {"parts": [{"text": prompt}]}, "contents": [{"role": "user", "parts": [{"text": json.dumps({"intent": intent, "evidence": evidence,"question":question,"history":history or []}, ensure_ascii=False)}]}],
                   "generationConfig": {"responseMimeType": "application/json", "responseJsonSchema": schema, "maxOutputTokens": 1800}}
        response = self.transport(payload)
        candidates = response.get("candidates", [])
        if len(candidates) != 1 or candidates[0].get("finishReason") != "STOP":
            raise ValueError("Incomplete agent response")
        raw = "".join(p["text"] for p in candidates[0].get("content", {}).get("parts", []) if not p.get("thought") and isinstance(p.get("text"), str))
        if len(raw) > 12000:
            raise ValueError("Agent response limit")
        result = AgentText.model_validate_json(raw)
        if result.decision != assessment["decision"] or result.wait_condition != assessment.get("wait_condition"):
            raise ValueError("Agent changed server decision")
        if not result.headline.strip() or not result.answer.strip() or any(not text.strip() for text in result.reasoning + result.risks + result.data_limitations):
            raise ValueError("Blank agent text")
        # Never let model omissions hide server-owned limitations or risks.
        result.risks = list(dict.fromkeys(assessment.get("risks", []) + result.risks))[:8]
        result.data_limitations = list(dict.fromkeys(assessment.get("data_limitations", []) + result.data_limitations))[:8]
        return result.model_dump()


_rss_lock = threading.Lock()
_rss_cache = None


def _public_url(value, hosts):
    parsed = urlparse(value)
    if parsed.scheme != "https" or parsed.hostname not in hosts or parsed.username or parsed.password or parsed.port not in (None, 443):
        raise ValueError("Invalid configured feed")
    addresses = socket.getaddrinfo(parsed.hostname, 443, type=socket.SOCK_STREAM)
    if not addresses or any(not ipaddress.ip_address(row[4][0]).is_global for row in addresses):
        raise ValueError("Non-public feed")
    return parsed


def _fetch_feed(feed, hosts):
    import feedparser
    import httpx
    _public_url(feed["url"], hosts)
    with httpx.Client(timeout=3, trust_env=False, follow_redirects=False) as client:
        with client.stream("GET", feed["url"], headers={"User-Agent": "PRIZM-RSS/1.0"}) as response:
            response.raise_for_status()
            body = bytearray()
            started = time.monotonic()
            for chunk in response.iter_bytes():
                body.extend(chunk)
                if len(body) > 262144 or time.monotonic() - started > 4:
                    raise ValueError("RSS limit")
    parsed = feedparser.parse(bytes(body))
    if parsed.bozo and not parsed.entries:
        raise ValueError("RSS parse failed")
    now = datetime.now(timezone.utc)
    entries = []
    for item in parsed.entries[:100]:
        date = item.get("published_parsed")  # updated-only dates are not treated as publication dates.
        link = item.get("link", "")
        target = urlparse(link)
        if not date or target.scheme != "https" or not target.hostname or target.username or target.password or target.port not in (None, 443):
            continue
        # Links remain on the configured publisher's domain (or its subdomains).
        host = urlparse(feed["url"]).hostname
        if target.hostname not in hosts and target.hostname != host and not target.hostname.endswith("." + host):
            continue
        published = datetime(*date[:6], tzinfo=timezone.utc)
        if published > now + timedelta(minutes=5) or published < now - timedelta(days=90):
            continue
        clean = lambda text: html.unescape(re.sub(r"<[^>]*>", " ", str(text)))[:800].strip()
        title = clean(item.get("title", ""))[:250]
        if title:
            entries.append({"title": title, "summary": clean(item.get("summary", "")), "url": link, "publisher": feed["name"][:100], "publishedAt": published.isoformat()})
    return entries


def news_for(title, keywords=None):
    global _rss_cache
    raw, allowed_hosts = rss_settings()
    hosts = set(filter(None, (s.strip().lower() for s in allowed_hosts.split(","))))
    config = hashlib.sha256((raw + str(sorted(hosts))).encode()).hexdigest()
    with _rss_lock:
        if not _rss_cache or _rss_cache["config"] != config or _rss_cache["expires"] < time.monotonic():
            feeds = json.loads(raw)
            if not isinstance(feeds, list) or len(feeds) > 3 or any(not isinstance(f, dict) or not isinstance(f.get("name"), str) or not isinstance(f.get("url"), str) for f in feeds):
                raise ValueError("Invalid RSS configuration")
            articles, failures = [], 0
            if feeds:
                with ThreadPoolExecutor(max_workers=3) as pool:
                    futures = [pool.submit(_fetch_feed, feed, hosts) for feed in feeds]
                    for future in futures:
                        try:
                            articles.extend(future.result())
                        except Exception:
                            failures += 1
            state = "NOT_CONFIGURED" if not feeds else "FAILED" if failures == len(feeds) else "PARTIAL" if failures else "READY"
            _rss_cache = {"config": config, "expires": time.monotonic() + (60 if failures else 900), "articles": articles, "state": state, "checkedAt": datetime.now(timezone.utc).isoformat()}
        articles, state, checked = _rss_cache["articles"], _rss_cache["state"], _rss_cache["checkedAt"]
    tokens = list(dict.fromkeys(re.findall(r"[가-힣a-zA-Z0-9-]{3,}", title.lower())))[:12]
    tokens = [t for t in tokens if not t.isdigit() and t not in {"정품", "공식", "삼성전자", "samsung", "apple"}]
    model_tokens = [t for t in tokens if any(c.isdigit() for c in t) and any(c.isalpha() for c in t)]
    related = []
    for article in articles:
        text = (article["title"] + " " + article["summary"]).lower()
        hits = category_hits(text, keywords) if keywords is not None else [t for t in tokens if t in text]
        if (bool(hits) if keywords is not None else any(t in text for t in model_tokens) or len(hits) >= 2):
            related.append({**article, "matchedTerms": hits, "relevance": "관련 카테고리 키워드 일치 · 시장 배경 정보" if keywords is not None else "상품명 키워드 일치; 동일 옵션/행사 적용 미확인"})
    related = list({a["url"]: a for a in related}.values())
    related.sort(key=lambda a: a["publishedAt"], reverse=True)
    result = {"state": state, "articles": related[:5], "checkedAt": checked}
    if keywords is not None:
        result.update({"searchBasis":"CATEGORY", "category":title})
    result["version"] = hashlib.sha256(json.dumps(result, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
    return result
