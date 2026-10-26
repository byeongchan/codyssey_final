"""Select a news source explicitly. No silent RSS fallback for missing Naver credentials."""
from .rss_settings import local_settings
from .naver_news import news_for as naver_news
from .price_agent import news_for as rss_news
from .news_impact import normalize_product, rank_articles, POLICY_HASH, LIMITS
from datetime import datetime, timedelta, timezone
import hashlib
import json


def news_for(category, keywords=None, category_basis="STORED_CATEGORY", product_title="", product_id="", maker="", brand="", intro=""):
    provider = local_settings({"NEWS_PROVIDER": "naver"})["NEWS_PROVIDER"].strip().lower()
    if provider == "naver":
        return naver_news(category, keywords=keywords, category_basis=category_basis, product_title=product_title,
                          product_id=product_id, maker=maker, brand=brand, intro=intro)
    if provider == "rss":
        result = rss_news(category, keywords=keywords)
        context = normalize_product(category,keywords or [category],product_title,product_id,maker,brand,intro)
        context["categoryBasis"]=category_basis
        now = datetime.now(timezone.utc)
        candidates = [a for a in result["articles"] if datetime.fromisoformat(a["publishedAt"]) >= now-timedelta(days=LIMITS["windowDays"])
                      and len(a.get("summary","")) >= LIMITS["minDescriptionChars"]]
        selected,stats = rank_articles(candidates,context,now)
        result.update({"articles":selected,"evaluation":stats,"searchBasis":"PRICE_FACTORS_RSS","provider":"rss","policyHash":POLICY_HASH})
        result["version"] = hashlib.sha256(json.dumps(result,sort_keys=True,ensure_ascii=False).encode()).hexdigest()
        return result
    raise ValueError("Unsupported news provider")
