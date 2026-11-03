"""Configurable search planning and conservative, explainable snippet-based impact scoring."""

import hashlib
import json
import re
from datetime import datetime
from difflib import SequenceMatcher
from pathlib import Path
from urllib.parse import urlsplit

from .news_relevance import category_hits

POLICY = json.loads((Path(__file__).parent / "news-impact-policy.json").read_text(encoding="utf-8"))
POLICY_HASH = hashlib.sha256(
    json.dumps(POLICY, sort_keys=True, ensure_ascii=False).encode()
).hexdigest()
LIMITS = POLICY["limits"]
if not (
    1 <= LIMITS["maxQueries"] <= 6
    and 1 <= LIMITS["maxWorkers"] <= 3
    and 1 <= LIMITS["maxCandidates"] <= 100
    and 1 <= LIMITS["maxSelected"] <= 5
    and 0 <= LIMITS["maxReference"] <= LIMITS["maxSelected"]
    and 0
    <= POLICY["thresholds"]["related"]
    <= POLICY["thresholds"]["reference"]
    <= POLICY["thresholds"]["evidence"]
    <= 100
):
    raise ValueError("Invalid news impact limits")
if (
    any(not isinstance(v, (int, float)) or v < 0 for v in POLICY["weights"].values())
    or sum(POLICY["weights"].values()) <= 0
):
    raise ValueError("Invalid news impact weights")
for _group in ("upward", "downward"):
    for _signal in POLICY["signals"][_group]:
        re.compile(_signal["pattern"], re.IGNORECASE)


def normalize_product(category, keywords, title, product_id="", maker="", brand="", intro=""):
    profile_id = next(
        (name for name, p in POLICY["profiles"].items() if category in p["categories"]), "default"
    )
    profile = POLICY["profiles"][profile_id]
    text = (title + " " + intro)[:4500]
    hints = [
        name
        for name, aliases in POLICY["brands"].items()
        if category_hits(text + " " + maker + " " + brand, aliases)
    ]
    models = []
    for match in re.findall(
        r"(?<![a-z0-9])[a-z]{1,12}[- ]?\d[a-z0-9-]{0,20}(?![a-z0-9])", title.lower()
    ):
        value = re.sub(r"\s+", " ", match).upper()
        if not re.fullmatch(r"\d*(?:GB|TB|MB|HZ|MAH|MM|CM)", value) and not category_hits(
            value, profile["possibleInputs"]
        ):
            models.append(value)
    components = []
    compact = re.sub(r"\s+", "", text.lower())
    for term in profile["possibleInputs"]:
        if not category_hits(text, [term]):
            continue
        position = compact.find(re.sub(r"\s+", "", term.lower()))
        nearby = compact[max(0, position - 8) : position + len(term) + 20]
        if re.search(r"미지원|제외|아님|지원하지않|사용하지않", nearby):
            continue
        components.append(
            {
                "name": term,
                "aliases": [term],
                "basis": "PRODUCT_TEXT",
                "verified": False,
                "source": "상품명/소개에 명시된 내용",
            }
        )
    # A configured attribute needs an explicit verification flag and a source, not just a model guess.
    for row in POLICY.get("productMappings", {}).get(product_id, {}).get("components", []):
        if (
            row.get("verified") is True
            and isinstance(row.get("source"), str)
            and row["source"].strip()
            and row.get("name")
            and row.get("aliases")
        ):
            components.append(
                {
                    "name": row["name"],
                    "aliases": row["aliases"],
                    "basis": "VERIFIED_MAPPING",
                    "verified": True,
                    "source": row["source"],
                }
            )
    return {
        "productId": product_id,
        "title": title[:500],
        "maker": maker[:1000],
        "brand": brand[:1000],
        "category": category,
        "keywords": list(dict.fromkeys(keywords)),
        "profile": profile_id,
        "modelHints": list(dict.fromkeys(models))[:4],
        "entityHints": hints,
        "components": components,
        "possibleInputs": profile["possibleInputs"],
        "attributeLimit": "카테고리 후보 부품은 실제 탑재 사양이 아닙니다. 모델·기업 힌트는 원문 텍스트에서 추출했습니다.",
    }


def search_plan(context):
    profile = POLICY["profiles"][context["profile"]]
    entity = (
        context["entityHints"][0]
        if context["entityHints"]
        else context["brand"] or context["maker"] or ""
    )
    templates = list(profile["queries"])
    if context["modelHints"]:
        templates[0] = context["modelHints"][0] + " 가격 할인"
    if context["components"] and len(templates) > 2:
        templates[2] = context["components"][0]["name"] + " 가격 공급"
    plan = []
    for template in templates:
        query = re.sub(
            r"\s+", " ", template.format(category=context["category"], entity=entity)
        ).strip()[:160]
        if query and query not in [p["query"] for p in plan]:
            plan.append(
                {
                    "query": query,
                    "basis": "EXPLICIT_PRODUCT_AND_PROFILE"
                    if context["components"]
                    else "CATEGORY_POSSIBLE_FACTOR",
                }
            )
        if len(plan) == LIMITS["maxQueries"]:
            break
    return plan


def _title_key(title):
    return re.sub(r"[^a-z0-9가-힣]", "", title.lower())


def model_match(text, models):
    for model in models:
        parts = re.findall(r"[a-z]+|[0-9]+", model.lower())
        if parts and re.search(
            r"(?<![a-z0-9])" + r"[-\s]*".join(map(re.escape, parts)) + r"(?![a-z0-9])", text, re.I
        ):
            return True
    return False


def deduplicate(articles):
    representatives = []
    for article in sorted(articles, key=lambda a: a["publishedAt"], reverse=True):
        key = _title_key(article["title"])
        old = next(
            (
                a
                for a in representatives
                if a["url"] == article["url"]
                or a.get("originalUrl")
                and a.get("originalUrl") == article.get("originalUrl")
                or _title_key(a["title"]) == key
                or SequenceMatcher(None, _title_key(a["title"]), key).ratio()
                >= LIMITS["eventSimilarity"]
            ),
            None,
        )
        if old is not None:
            old["duplicateCount"] += 1
            old["searchQueries"] = list(
                dict.fromkeys(old.get("searchQueries", []) + article.get("searchQueries", []))
            )
            continue
        row = dict(article)
        row["eventId"] = hashlib.sha256(key.encode()).hexdigest()[:20]
        row["duplicateCount"] = 0
        representatives.append(row)
    return representatives


def evaluate(article, context, now):
    profile = POLICY["profiles"][context["profile"]]
    text = article["title"] + ". " + article["summary"]
    category_terms = context["keywords"] + profile["sectorTerms"]
    explicit_inputs = [alias for row in context["components"] for alias in row["aliases"]]
    direct = model_match(text, context["modelHints"])
    manufacturer = bool(
        category_hits(
            text, context["entityHints"] + [v for v in (context["brand"], context["maker"]) if v]
        )
    )
    # Entity hints use aliases: an NVIDIA production story can spell the company in Korean.
    manufacturer = manufacturer or any(
        category_hits(text, POLICY["brands"].get(hint, [hint])) for hint in context["entityHints"]
    )
    category = bool(category_hits(text, category_terms))
    supply = bool(category_hits(text, explicit_inputs + context["possibleInputs"]))
    policy_match = bool(
        re.search(POLICY["signals"]["policy"], text, re.I)
        and category_hits(text, profile["policyTerms"])
    )
    scoped = (
        direct
        or category
        or supply
        or (manufacturer and context["profile"] != "default")
        or policy_match
    )
    related = direct or category or supply or policy_match or manufacturer
    excerpts, paths, signs, signal_ids = [], [], set(), []
    speculative, denied, resolved = False, False, False
    sentences = [s.strip() for s in re.split(r"(?<=[.!?。])\s+|[\n;]", text) if s.strip()]
    for sentence in sentences:
        if re.search(r"주가|목표가격|목표주가|주식|시가총액", sentence) and not re.search(
            r"공급|생산|재고|판매가|출고가|원가", sentence
        ):
            continue
        sentence_scope = bool(
            category_hits(
                sentence,
                category_terms
                + explicit_inputs
                + context["possibleInputs"]
                + profile["policyTerms"],
            )
        )
        sentence_scope = sentence_scope or (direct and model_match(sentence, context["modelHints"]))
        sentence_scope = sentence_scope or (
            manufacturer
            and context["profile"] != "default"
            and any(
                category_hits(sentence, POLICY["brands"].get(hint, [hint]))
                for hint in context["entityHints"]
            )
        )
        if not scoped or not sentence_scope:
            continue
        negated = bool(re.search(POLICY["signals"]["negation"], sentence))
        forecast = bool(re.search(POLICY["signals"]["speculative"], sentence))
        hits = [
            (group, sig)
            for group in ("upward", "downward")
            for sig in POLICY["signals"][group]
            if re.search(sig["pattern"], sentence, re.I)
        ]
        relief = any(sig["id"] == "resolved" for _, sig in hits) and not negated
        # A stock/earnings change alone never matches a consumer-price path.
        if hits:
            excerpts.append(sentence[:300])
            speculative |= forecast
            denied |= negated
            resolved |= relief
            for group, sig in hits:
                if relief and group == "upward":
                    continue
                paths.append(sig["path"])
                signal_ids.append(sig["id"])
                if not negated:
                    signs.add(group)
        elif policy_match and re.search(POLICY["signals"]["policy"], sentence):
            excerpts.append(sentence[:300])
            paths.append(
                "기사의 정책 변화 → 적용 대상인 부품·유통 비용 또는 공급 제약 변화 가능성 → 상품 가격 영향 가능성; 적용 범위는 추가 확인 필요"
            )
            signal_ids.append("policy_scope_unknown")
            speculative = True
    if resolved:
        if not any(s in signal_ids for s in ("cost_increase", "demand_increase")):
            signs.discard("upward")
        supply_path = next(
            s["path"] for s in POLICY["signals"]["upward"] if s["id"] == "supply_shortage"
        )
        paths = [p for p in paths if p != supply_path]
        excerpts = excerpts[-2:]
    has_path = bool(paths)
    direction = (
        "MIXED"
        if len(signs) == 2
        else "UPWARD_PRESSURE"
        if "upward" in signs
        else "DOWNWARD_PRESSURE"
        if "downward" in signs
        else "UNCERTAIN"
    )
    if denied:
        direction = "UNCERTAIN"
    host = (urlsplit(article["url"]).hostname or "").lower()
    configured_source = any(
        host == domain or host.endswith("." + domain) for domain in POLICY["sourceDomains"]
    )
    age = max(0, (now - datetime.fromisoformat(article["publishedAt"])).total_seconds() / 86400)
    enough = len(article["summary"]) >= LIMITS["minDescriptionChars"]
    dimensions = {
        "direct": float(direct),
        "manufacturer": float(manufacturer),
        "category": float(category),
        "supplyChain": float(supply),
        "policy": float(policy_match),
        "pricePath": float(has_path and not denied),
        "freshness": max(0, 1 - age / LIMITS["windowDays"]),
        "source": 1.0 if configured_source else 0.25,
        "sufficiency": min(1, len(article["summary"]) / LIMITS["evidenceDescriptionChars"]),
    }
    score = round(
        100
        * sum(POLICY["weights"][name] * value for name, value in dimensions.items())
        / sum(POLICY["weights"].values()),
        1,
    )
    if not related or not enough:
        direction = "IRRELEVANT"
    stage = "IRRELEVANT"
    if related and enough and score >= POLICY["thresholds"]["related"]:
        stage = "RELATED_ONLY"
    if stage != "IRRELEVANT" and has_path and score >= POLICY["thresholds"]["reference"]:
        stage = "REFERENCE"
    article_brands = {
        name for name, aliases in POLICY["brands"].items() if category_hits(text, aliases)
    }
    other_models = re.findall(r"(?<![a-z0-9])[a-z]{1,12}[- ]?\d[a-z0-9-]{0,20}", text.lower())
    narrow_other_deal = (
        "price_cut" in signal_ids
        and not direct
        and (
            bool(context["modelHints"] and other_models)
            or bool(
                article_brands
                and context["entityHints"]
                and not article_brands.intersection(context["entityHints"])
            )
        )
    )
    if narrow_other_deal and not re.search(r"가격\s*경쟁|경쟁사.{0,12}(?:대응|인하)", text):
        stage = "RELATED_ONLY"
        direction = "IRRELEVANT"
    uncertain_dependency = (
        (supply or manufacturer)
        and not category_hits(text, explicit_inputs)
        and not direct
        and not category
    )
    if (
        stage == "REFERENCE"
        and score >= POLICY["thresholds"]["evidence"]
        and configured_source
        and len(article["summary"]) >= LIMITS["evidenceDescriptionChars"]
        and age <= LIMITS["evidenceAgeDays"]
        and direction not in {"UNCERTAIN", "IRRELEVANT"}
        and not speculative
        and not denied
        and not uncertain_dependency
        and not policy_match
        and context.get("categoryBasis") != "AI_INFERRED"
    ):
        stage = "EVIDENCE"
    if stage in {"IRRELEVANT", "RELATED_ONLY"}:
        direction = "IRRELEVANT" if stage == "IRRELEVANT" or not has_path else direction
    reasons = []
    for flag, label in (
        (direct, "상품 모델 힌트 일치"),
        (manufacturer, "제조사·기업 문맥 일치"),
        (category, "동일 상품군 문맥"),
        (supply, "부품·공급망 후보 문맥"),
        (policy_match, "해당 산업의 정책 문맥"),
    ):
        if flag:
            reasons.append(label)
    if has_path:
        reasons.append("제공된 제목·설명에서 영향 경로 후보 추출")
    limitations = [
        "네이버 제목·검색 요약 기준이며 기사 전문과 사실의 정확성을 검증하지 않았습니다.",
        "가격 영향은 추론이며 실제 판매가·할인 적용·변동 확률을 뜻하지 않습니다.",
    ]
    if not configured_source:
        limitations.append("출처 도메인의 신뢰도는 별도로 검증되지 않았습니다.")
    if uncertain_dependency:
        limitations.append(
            "카테고리상 가능한 부품 연결이며 선택 상품의 실제 탑재 여부는 미확인입니다."
        )
    if policy_match:
        limitations.append("정책 대상 국가·시행 범위와 국내 판매가 전가 여부는 미확인입니다.")
    if speculative:
        limitations.append("기사 설명에 전망·검토 등 불확실한 표현이 포함되어 있습니다.")
    if denied:
        limitations.append("부인·부정 표현이 있어 단정적인 가격 방향 판단에서 제외했습니다.")
    if age > LIMITS["evidenceAgeDays"]:
        limitations.append("오래된 요인이 현재까지 지속되는지 확인해야 합니다.")
    if resolved:
        limitations.append(
            "공급 문제 해소 문맥을 반영했으며 과거 부족을 현재 상승 근거로 재사용하지 않습니다."
        )
    if context.get("categoryBasis") == "AI_INFERRED":
        limitations.append("상품군은 AI 추정 분류이며 실제 모델·사양을 검증한 것은 아닙니다.")
    return {
        **article,
        "relevanceScore": score,
        "scoreDimensions": dimensions,
        "assessmentStage": stage,
        "priceImpact": direction,
        "relevanceReasons": reasons,
        "relevance": " · ".join(reasons),
        "reportedFacts": list(dict.fromkeys(excerpts))[:2],
        "impactPath": list(dict.fromkeys(paths))[:2],
        "statementBasis": "REPORTED_SNIPPET_CLAIM",
        "sourceAssessment": "CONFIGURED_DOMAIN_NOT_FACT_VERIFIED"
        if configured_source
        else "UNVERIFIED_DOMAIN",
        "uncertainties": limitations,
        "signals": list(dict.fromkeys(signal_ids)),
    }


def rank_articles(articles, context, now):
    unique = deduplicate(articles)
    queries = list(dict.fromkeys(q for a in unique for q in a.get("searchQueries", [])))
    candidates = []
    if queries:
        buckets = [[a for a in unique if q in a.get("searchQueries", [])] for q in queries]
        for index in range(max(map(len, buckets), default=0)):
            for bucket in buckets:
                if index < len(bucket) and bucket[index] not in candidates:
                    candidates.append(bucket[index])
                if len(candidates) >= LIMITS["maxCandidates"]:
                    break
            if len(candidates) >= LIMITS["maxCandidates"]:
                break
    else:
        candidates = unique[: LIMITS["maxCandidates"]]
    evaluated = [evaluate(a, context, now) for a in candidates]
    eligible = [a for a in evaluated if a["assessmentStage"] in {"EVIDENCE", "REFERENCE"}]
    eligible.sort(
        key=lambda a: (a["assessmentStage"] == "EVIDENCE", a["relevanceScore"], a["publishedAt"]),
        reverse=True,
    )
    selected = []
    first = next(
        (a for a in eligible if a["assessmentStage"] == "EVIDENCE" or LIMITS["maxReference"] > 0),
        None,
    )
    if first is not None:
        selected.append(first)
    # Preserve opposing eligible signals instead of showing only one direction.
    for direction in ("UPWARD_PRESSURE", "DOWNWARD_PRESSURE", "MIXED", "UNCERTAIN"):
        candidate = next((a for a in eligible if a["priceImpact"] == direction), None)
        if (
            candidate is not None
            and candidate not in selected
            and len(selected) < LIMITS["maxSelected"]
        ):
            if (
                candidate["assessmentStage"] != "REFERENCE"
                or sum(a["assessmentStage"] == "REFERENCE" for a in selected)
                < LIMITS["maxReference"]
            ):
                selected.append(candidate)
    for candidate in eligible:
        if len(selected) >= LIMITS["maxSelected"]:
            break
        if candidate in selected:
            continue
        if (
            candidate["assessmentStage"] == "REFERENCE"
            and sum(a["assessmentStage"] == "REFERENCE" for a in selected) >= LIMITS["maxReference"]
        ):
            continue
        selected.append(candidate)
    return selected, {
        "uniqueCandidates": len(unique),
        "evaluated": len(evaluated),
        "related": sum(a["assessmentStage"] != "IRRELEVANT" for a in evaluated),
        "possibleImpact": len(eligible),
        "evidence": sum(a["assessmentStage"] == "EVIDENCE" for a in selected),
        "reference": sum(a["assessmentStage"] == "REFERENCE" for a in selected),
    }
