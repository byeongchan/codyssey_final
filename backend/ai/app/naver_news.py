"""Bounded NAVER API HUB news retrieval; article links are never fetched by this service."""
import copy
import hashlib
import html
import ipaddress
import json
import logging
import re
import threading
import time
from collections import OrderedDict
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from urllib.parse import urlsplit, urlunsplit

from .rss_settings import local_settings
from concurrent.futures import ThreadPoolExecutor
from .news_impact import POLICY, POLICY_HASH, LIMITS, normalize_product, search_plan, rank_articles

ENDPOINT = "https://naverapihub.apigw.ntruss.com/search/v1/news"
WINDOW_DAYS = LIMITS["windowDays"]
_lock = threading.Lock()
_cache = OrderedDict()
_analysis_cache = OrderedDict()
_flights = {}
_slots = threading.BoundedSemaphore(LIMITS["maxWorkers"])
_logger = logging.getLogger(__name__)


class SearchFailure(Exception):
    def __init__(self, state, http_status=None, code=None):
        self.state = state
        self.http_status = http_status
        self.code = code
        super().__init__(state)


def _clean(value, limit):
    if not isinstance(value, str):
        return ""
    # Decode before removing markup, including entity-encoded HTML.
    return re.sub(r"\s+", " ", re.sub(r"<[^>]*>", " ", html.unescape(value))).strip()[:limit]


def _link(value):
    if not isinstance(value, str) or len(value) > 2048:
        return None
    try:
        target = urlsplit(value)
        host = target.hostname
        if target.scheme not in {"http", "https"} or not host or target.username or target.password or target.port not in (None, 80, 443):
            return None
        if any(ord(c) < 33 for c in value) or "." not in host or host.endswith((".local", ".localhost", ".internal")):
            return None
        try:
            if not ipaddress.ip_address(host).is_global:
                return None
        except ValueError:
            pass
        return urlunsplit((target.scheme, target.netloc, target.path, target.query, ""))
    except ValueError:
        return None


def normalize_articles(items, now):
    articles = []
    for item in items[:100]:
        if not isinstance(item, dict):
            continue
        try:
            provided = parsedate_to_datetime(item.get("pubDate", ""))
            if provided.tzinfo is None:
                continue
            provided = provided.astimezone(timezone.utc)
        except (TypeError, ValueError, OverflowError, AttributeError):
            continue
        if provided < now - timedelta(days=WINDOW_DAYS) or provided > now:
            continue
        title, summary = _clean(item.get("title"), 250), _clean(item.get("description"), 800)
        original, naver = _link(item.get("originallink")), _link(item.get("link"))
        link = original or naver
        if title and link and len(summary) >= LIMITS["minDescriptionChars"]:
            articles.append({"title":title, "summary":summary, "url":link, "originalUrl":original, "naverUrl":naver,
                             "publisher":urlsplit(link).hostname, "publishedAt":provided.isoformat(),
                             "dateBasis":"NAVER_PROVIDED_AT", "provider":"naver"})
    return articles


def select_articles(items, category, now, keywords=None, product_title=""):
    context = normalize_product(category, keywords or [category], product_title)
    return rank_articles(normalize_articles(items, now), context, now)[0]


def _fetch(query, client_id, client_secret):
    import httpx
    started = time.monotonic()
    with httpx.Client(timeout=httpx.Timeout(3.0, connect=2.0), trust_env=False, follow_redirects=False) as client:
        with client.stream("GET", ENDPOINT, params={"query": query, "display": 100, "start": 1, "sort": "date", "format": "json"},
                           headers={"X-NCP-APIGW-API-KEY-ID": client_id, "X-NCP-APIGW-API-KEY": client_secret}) as response:
            body = bytearray()
            for chunk in response.iter_bytes():
                body.extend(chunk)
                limit = 524288 if response.status_code == 200 else 8192
                if len(body) > limit or time.monotonic() - started > 5:
                    raise SearchFailure("FAILED")
            if response.status_code != 200:
                code = "UNKNOWN"
                try:
                    error = json.loads(body)
                    if isinstance(error, dict):
                        wrapped = error.get("error")
                        raw = wrapped.get("errorCode") if isinstance(wrapped, dict) else error.get("errorCode")
                        if isinstance(raw, (str, int)) and re.fullmatch(r"(?:[0-9]{3}|SE[0-9]{2})", str(raw)):
                            code = str(raw)
                except (ValueError, TypeError):
                    pass
                state = {300: "API_NOT_FOUND", 400: "INVALID_REQUEST", 401: "AUTH_FAILED", 403: "REQUEST_REJECTED",
                         404: "API_NOT_FOUND", 429: "RATE_LIMITED"}.get(response.status_code, "FAILED")
                if response.status_code == 403 and code == "200":
                    state = "AUTH_FAILED"
                raise SearchFailure(state, response.status_code, code)
    payload = json.loads(body)
    if not isinstance(payload, dict) or not isinstance(payload.get("items"), list):
        raise SearchFailure("FAILED")
    return payload["items"]


def _retrieval(query, client_id, secret):
    key = hashlib.sha256(json.dumps([ENDPOINT,query,client_id,secret]).encode()).hexdigest()
    with _lock:
        cached = _cache.get(key)
        if cached is not None and cached[0] > time.monotonic():
            _cache.move_to_end(key)
            return cached[1]
        event = _flights.get(key)
        owner = event is None
        if owner:
            event = threading.Event(); _flights[key] = event
    if not owner:
        if not event.wait(7):
            return {"query":query,"state":"FAILED","items":[],"checkedAt":datetime.now(timezone.utc).isoformat()}
        with _lock:
            cached = _cache.get(key)
            if cached is not None and cached[0] > time.monotonic():
                return cached[1]
        return {"query":query,"state":"FAILED","items":[],"checkedAt":datetime.now(timezone.utc).isoformat()}
    result = {"query":query,"state":"READY","items":[],"checkedAt":datetime.now(timezone.utc).isoformat()}
    try:
        try:
            if not _slots.acquire(timeout=2):
                raise SearchFailure("FAILED")
            try:
                result["items"] = _fetch(query,client_id,secret)
            finally:
                _slots.release()
        except SearchFailure as exc:
            result["state"] = exc.state
            if exc.http_status is not None:
                result["diagnostics"] = {"httpStatus":exc.http_status,"errorCode":exc.code}
                _logger.warning("Naver news request failed status=%s code=%s state=%s",exc.http_status,exc.code,exc.state)
        except Exception:
            result["state"] = "FAILED"
        ttl = LIMITS["successTtlSeconds"] if result["state"] == "READY" else LIMITS["failureTtlSeconds"]
        with _lock:
            _cache[key] = (time.monotonic()+ttl,result)
            _cache.move_to_end(key)
            while len(_cache)>LIMITS["queryCacheEntries"]:
                _cache.popitem(last=False)
        return result
    finally:
        with _lock:
            _flights.pop(key,None); event.set()


def news_for(category, keywords=None, category_basis="STORED_CATEGORY", product_title="", product_id="", maker="", brand="", intro=""):
    context = normalize_product(category,keywords or [category],product_title,product_id,maker,brand,intro)
    context["categoryBasis"] = category_basis
    plan = search_plan(context)
    settings = local_settings({"NAVER_CLIENT_ID":"","NAVER_CLIENT_SECRET":""})
    client_id,secret = settings["NAVER_CLIENT_ID"].strip(),settings["NAVER_CLIENT_SECRET"].strip()
    now = datetime.now(timezone.utc)
    retrievals = []
    if client_id and secret:
        first=_retrieval(plan[0]["query"],client_id,secret)
        retrievals=[first]
        if first["state"] not in {"AUTH_FAILED","RATE_LIMITED","API_NOT_FOUND","REQUEST_REJECTED","NOT_CONFIGURED"}:
            with ThreadPoolExecutor(max_workers=LIMITS["maxWorkers"]) as pool:
                futures = [pool.submit(_retrieval,topic["query"],client_id,secret) for topic in plan[1:]]
                retrievals.extend(future.result() for future in futures)
    successes = [r for r in retrievals if r["state"] == "READY"]
    failures = [r for r in retrievals if r["state"] != "READY"]
    state = "NOT_CONFIGURED" if not retrievals else "PARTIAL" if successes and failures else "READY" if successes else failures[0]["state"]
    candidates = [{**article,"searchQueries":[r["query"]]} for r in successes for article in normalize_articles(r["items"],now)]
    queries = [{k:v for k,v in r.items() if k != "items"} for r in retrievals]
    analysis_key = hashlib.sha256(json.dumps([POLICY_HASH,context,queries,candidates,category_basis],sort_keys=True,ensure_ascii=False).encode()).hexdigest()
    with _lock:
        hit = _analysis_cache.get(analysis_key)
        evidence_aged = hit is not None and any(a["assessmentStage"] == "EVIDENCE" and
            (now-datetime.fromisoformat(a["publishedAt"])).total_seconds() > LIMITS["evidenceAgeDays"]*86400 for a in hit[1]["articles"])
        if hit is not None and hit[0] > time.monotonic() and not evidence_aged:
            _analysis_cache.move_to_end(analysis_key)
            return copy.deepcopy(hit[1])
    selected,statistics = rank_articles(candidates,context,now)
    result = {"provider":"naver","apiVariant":"NAVER_API_HUB","windowDays":WINDOW_DAYS,"dateBasis":"NAVER_PROVIDED_AT",
              "state":state,"articles":selected,"category":category,"categoryBasis":category_basis,"searchBasis":"PRICE_FACTORS",
              "policyVersion":POLICY["version"],"policyHash":POLICY_HASH,"checkedAt":max((r["checkedAt"] for r in retrievals),default=now.isoformat()),
              "searchTopics":queries,"evaluation":statistics,"productContext":context,
              "retrievalScope":"상품군별 제한된 검색 주제·쿼리별 최신 100건·최근 30일 검색 요약 평가; 기사 전문 및 실제 가격 전가 검증 아님"}
    if failures and "diagnostics" in failures[0]:
        result["diagnostics"] = failures[0]["diagnostics"]
    result["version"] = hashlib.sha256(json.dumps(result,sort_keys=True,ensure_ascii=False).encode()).hexdigest()
    # Failure analysis must not hide corrected credentials for the normal success TTL.
    ttl = LIMITS["analysisTtlSeconds"] if state == "READY" else LIMITS["failureTtlSeconds"]
    with _lock:
        _analysis_cache[analysis_key] = (time.monotonic()+ttl,copy.deepcopy(result))
        _analysis_cache.move_to_end(analysis_key)
        while len(_analysis_cache)>LIMITS["analysisCacheEntries"]:
            _analysis_cache.popitem(last=False)
    return result
