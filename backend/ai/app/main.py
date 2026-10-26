"""Gemini explanation service for server-owned decisions; explicit fixture mode for tests."""
import os
import logging
import time
import secrets
from typing import Annotated, Literal, Protocol
from uuid import UUID, uuid4

from fastapi import Depends, FastAPI, HTTPException, Request, Security
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.security import APIKeyHeader
from starlette.exceptions import HTTPException as StarletteHTTPException
from pydantic import BaseModel, Field, ConfigDict, AwareDatetime, model_validator
from .review_summary import GeminiReviewProvider, ReviewText
from .price_agent import GeminiPriceAgent
from .news_sources import news_for
from .product_identity import identify

AI_PROVIDER = os.getenv("AI_PROVIDER", "gemini")
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "")
_agent_logger = logging.getLogger("uvicorn.error")
if os.getenv("APP_ENV", "development") != "development" or AI_PROVIDER not in {"fixture", "gemini"}:
    raise RuntimeError("Only development fixture/gemini explanation modes are implemented")
INTERNAL_KEY = os.getenv("INTERNAL_API_KEY", "")
if not INTERNAL_KEY:
    raise RuntimeError("Set INTERNAL_API_KEY before starting the AI service")

app = FastAPI(title="PRIZM development explanation", version="0.8.0")
if AI_PROVIDER == "gemini":
    import re
    _agent_logger.info("AI configured provider=gemini model=%s", GEMINI_MODEL if re.fullmatch(r"gemini-[A-Za-z0-9._-]{1,100}", GEMINI_MODEL) else "INVALID_OR_MISSING")


class ErrorDetail(BaseModel):
    code: str
    message: str
    requestId: str
    retryable: bool


class ErrorEnvelope(BaseModel):
    error: ErrorDetail


class HealthResponse(BaseModel):
    status: Literal["UP"]
    provider: Literal["fixture", "gemini"]
    synthetic: bool


@app.middleware("http")
async def request_id(request: Request, call_next):
    try:
        value = str(UUID(request.headers.get("X-Request-Id", "")))
    except ValueError:
        value = str(uuid4())
    request.state.request_id = value
    response = await call_next(request)
    response.headers["X-Request-Id"] = value
    return response


def error_response(request: Request, status: int, message: str, code: str | None = None):
    identifier = getattr(request.state, "request_id", str(uuid4()))
    return JSONResponse(status_code=status, headers={"X-Request-Id": identifier, "Cache-Control": "no-store"}, content={
        "error": {"code": code or f"HTTP_{status}", "message": message,
                  "requestId": identifier, "retryable": status == 429 or status >= 500},
    })


@app.exception_handler(RequestValidationError)
async def validation_error(request: Request, exc: RequestValidationError):
    # 잘못된 입력 본문이나 헤더에 포함될 수 있는 비밀값을 응답에 반사하지 않는다.
    return error_response(request, 422, "분석 입력 형식을 확인하세요", "VALIDATION_ERROR")


@app.exception_handler(StarletteHTTPException)
async def http_error(request: Request, exc: StarletteHTTPException):
    messages = {403: "내부 인증 실패", 404: "요청한 API가 없습니다", 405: "지원하지 않는 HTTP 메서드입니다"}
    response = error_response(request, exc.status_code, messages.get(exc.status_code, "요청을 처리하지 못했습니다"))
    if exc.headers:
        response.headers.update(exc.headers)
    return response


@app.exception_handler(Exception)
async def unexpected_error(request: Request, exc: Exception):
    return error_response(request, 500, "AI 서비스 처리 중 오류가 발생했습니다")


class AnalysisInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    productId: str = Field(min_length=1, max_length=100)
    observedDays: int = Field(ge=0, le=30, strict=True)
    currentPrice: int | None = Field(default=None, gt=0, le=2_000_000_000_000, strict=True)
    averagePrice: float | None = Field(default=None, ge=0, allow_inf_nan=False)
    referenceTime: AwareDatetime
    verdict: Literal["BUY", "WAIT", "NEUTRAL", "INSUFFICIENT_DATA"]
    reasonCode: Literal["BELOW_AVERAGE", "ABOVE_AVERAGE", "NEAR_AVERAGE", "STALE_EVIDENCE", "NO_CURRENT_PRICE", "SHORT_HISTORY", "NO_AVERAGE", "UNVERIFIED_STOCK_OR_OPTION"]
    ruleVersion: Literal["price-rules-v1"]
    explanationVersion: Literal["explanation-v1"]
    evidenceSnapshotId: str = Field(min_length=1, max_length=200)
    explanationProvider: Literal["fixture", "gemini"] = "fixture"
    explanationModel: str = "fixture-no-gemini-call"
    synthetic: bool = True


class Explanation(BaseModel):
    model_config = ConfigDict(extra="forbid")
    productId: str
    ruleVersion: Literal["price-rules-v1"]
    explanationVersion: Literal["explanation-v1"]
    reasons: list[str] = Field(min_length=1, max_length=5)
    risks: list[str] = Field(min_length=1, max_length=5)
    generatedAt: AwareDatetime
    model: str = Field(default="fixture-no-gemini-call", min_length=1, max_length=120)
    evidenceSnapshotId: str
    synthetic: bool = True


class ExplanationProvider(Protocol):
    def analyze(self, data: AnalysisInput) -> Explanation: ...


class FixtureAnalysisProvider:
    def analyze(self, data: AnalysisInput) -> Explanation:
        # Only describe the supplied verdict. Do not calculate or override a verdict here.
        reasons = [{
            "BUY": "서버가 현재 확정 비교가를 관측 평균보다 낮은 구간으로 분류했습니다.",
            "WAIT": "서버가 현재 확정 비교가를 관측 평균보다 높은 구간으로 분류했습니다.",
            "NEUTRAL": "서버가 현재 확정 비교가를 관측 평균과 비슷한 구간으로 분류했습니다.",
            "INSUFFICIENT_DATA": "서버가 가격 근거 부족으로 판단을 보류했습니다.",
        }[data.verdict]]
        return Explanation(
            productId=data.productId, ruleVersion=data.ruleVersion, explanationVersion=data.explanationVersion, reasons=reasons,
            risks=["AI 예시 응답입니다. 실제 Gemini 분석이나 미래 가격 예측이 아닙니다."],
            generatedAt=data.referenceTime, evidenceSnapshotId=data.evidenceSnapshotId,
        )


if AI_PROVIDER == "gemini":
    from .gemini import GeminiExplanationProvider
    provider: ExplanationProvider = GeminiExplanationProvider(os.getenv("GEMINI_API_KEY", ""), GEMINI_MODEL, Explanation)
else:
    provider: ExplanationProvider = FixtureAnalysisProvider()


internal_key_header = APIKeyHeader(name="X-Internal-Key", auto_error=False, scheme_name="InternalKey")


def authorize(x_internal_key: str | None = Security(internal_key_header)) -> None:
    if not secrets.compare_digest((x_internal_key or "").encode(), INTERNAL_KEY.encode()):
        raise HTTPException(status_code=403, detail="Internal authentication failed")


@app.get("/health", response_model=HealthResponse)
def health():
    return {"status": "UP", "provider": AI_PROVIDER, "synthetic": AI_PROVIDER == "fixture"}


@app.post("/internal/analyze", response_model=Explanation, dependencies=[Depends(authorize)], responses={
    403: {"model": ErrorEnvelope, "description": "내부 인증 실패"},
    422: {"model": ErrorEnvelope, "description": "입력 검증 실패"},
    500: {"model": ErrorEnvelope, "description": "AI 서비스 오류"},
    503: {"model": ErrorEnvelope, "description": "공급자 설정 불일치 또는 설명 서비스 사용 불가"},
})
def analyze(data: AnalysisInput):
    if not data.synthetic and (AI_PROVIDER != "gemini" or data.verdict != "INSUFFICIENT_DATA" or data.reasonCode != "UNVERIFIED_STOCK_OR_OPTION"):
        raise HTTPException(status_code=422, detail="Live evidence must preserve the server hold decision")
    expected = GEMINI_MODEL if AI_PROVIDER == "gemini" else "fixture-no-gemini-call"
    if data.explanationProvider != AI_PROVIDER or data.explanationModel != expected:
        raise HTTPException(status_code=503, detail="Explanation configuration mismatch")
    if AI_PROVIDER == "gemini":
        try:
            return provider.analyze(data)
        except Exception:
            raise HTTPException(status_code=503, detail="Explanation service unavailable") from None
    return provider.analyze(data)


class ReviewInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    productId: str = Field(pattern=r"^[0-9]{1,30}$")
    reviews: list[Annotated[str, Field(min_length=1, max_length=2000)]] = Field(min_length=1, max_length=20)
    model: str = Field(pattern=r"^gemini-[A-Za-z0-9._-]{1,100}$")

    @model_validator(mode="after")
    def bounded_reviews(self):
        self.reviews = list(dict.fromkeys(text.strip() for text in self.reviews))
        if any(not text for text in self.reviews) or sum(map(len, self.reviews)) > 16000:
            raise ValueError("Invalid review text size")
        return self


class ReviewSummary(ReviewText):
    productId: str
    model: str
    sampleCount: int
    source: Literal["user-provided"] = "user-provided"
    summaryVersion: Literal["review-summary-v1"] = "review-summary-v1"


@app.post("/internal/reviews/summarize", response_model=ReviewSummary, dependencies=[Depends(authorize)], responses={
    403: {"model": ErrorEnvelope, "description": "내부 인증 실패"},
    422: {"model": ErrorEnvelope, "description": "리뷰 입력 제한/형식 오류"},
    500: {"model": ErrorEnvelope, "description": "AI 서비스 오류"},
    503: {"model": ErrorEnvelope, "description": "Gemini 리뷰 요약 사용 불가"},
})
def summarize_reviews(data: ReviewInput):
    if AI_PROVIDER != "gemini" or data.model != GEMINI_MODEL:
        raise HTTPException(status_code=503, detail="Review summary configuration mismatch")
    try:
        review_provider = GeminiReviewProvider(os.getenv("GEMINI_API_KEY", ""), GEMINI_MODEL, dict)
        result = review_provider.summarize(data.reviews)
        return ReviewSummary(**result.model_dump(), productId=data.productId, model=GEMINI_MODEL, sampleCount=len(data.reviews))
    except Exception:
        raise HTTPException(status_code=503, detail="Review summary unavailable") from None


class NewsInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    category: str = Field(default="", max_length=80)
    keywords: list[Annotated[str, Field(min_length=1, max_length=80)]] = Field(default_factory=list, max_length=12)
    categoryBasis: Literal["STORED_CATEGORY", "PRODUCT_TEXT_CATEGORY", "DISCOVERY_CATEGORY", "AUTO"]
    productTitle: str = Field(min_length=1, max_length=500)
    productId: str = Field(pattern=r"^[0-9]{1,30}$")
    maker: str = Field(default="", max_length=1000)
    brand: str = Field(default="", max_length=1000)
    intro: str = Field(default="", max_length=4000)


@app.post("/internal/price-agent/news", dependencies=[Depends(authorize)])
def agent_news(data: NewsInput):
    try:
        identity=None
        category,keywords,basis=data.category,data.keywords,data.categoryBasis
        if not category:
            identity=identify(data.productTitle,data.intro,data.brand,data.maker,os.getenv("GEMINI_API_KEY","") if AI_PROVIDER=="gemini" else "",GEMINI_MODEL)
            if not identity["category"]:
                return {"state":"IDENTIFICATION_UNCERTAIN","articles":[],"identity":identity,"version":"identity-unresolved-v1"}
            category=identity["category"]; keywords=[category]; basis=identity["basis"]
        result=news_for(category, keywords, basis, data.productTitle, data.productId, data.maker, data.brand, data.intro)
        if identity:
            import hashlib,json
            result["identity"]=identity
            if identity["basis"]=="AI_INFERRED":
                for article in result["articles"]:
                    article["assessmentStage"]="REFERENCE"
                    article["uncertainties"].append("상품군은 AI 추정 분류이며 실제 모델·사양을 검증한 것은 아닙니다.")
                if "evaluation" in result:
                    result["evaluation"]["evidence"]=0;result["evaluation"]["reference"]=len(result["articles"])
            result["version"]=hashlib.sha256(json.dumps(result,sort_keys=True,ensure_ascii=False).encode()).hexdigest()
        return result
    except Exception:
        raise HTTPException(status_code=503, detail="News unavailable") from None


class AgentInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    productId: str = Field(pattern=r"^[0-9]{1,30}$")
    intent: Literal["NEWS", "SELLERS", "SPECS", "TIMING", "PRICE", "OVERVIEW", "CHAT"]
    evidenceVersion: str = Field(pattern=r"^[a-f0-9]{64}$")
    model: str = Field(pattern=r"^gemini-[A-Za-z0-9._-]{1,100}$")
    evidence: dict
    question: str = Field(min_length=1, max_length=1000)
    history: list[dict] = Field(default_factory=list, max_length=6)

    @model_validator(mode="after")
    def bounded_evidence(self):
        import json
        if len(json.dumps(self.evidence, ensure_ascii=False)) > 60000 or self.evidence.get("assessment", {}).get("decision") not in {"BUY", "WAIT", "INSUFFICIENT_DATA"}:
            raise ValueError("Invalid server assessment")
        if any(set(row)!={"role","content"} or row.get("role") not in {"user","assistant"} or not isinstance(row.get("content"),str) or not 1<=len(row["content"])<=1000 for row in self.history):
            raise ValueError("Invalid chat history")
        return self


@app.post("/internal/price-agent/analyze", dependencies=[Depends(authorize)])
def agent_analyze(data: AgentInput):
    started = time.monotonic()
    if AI_PROVIDER != "gemini" or data.model != GEMINI_MODEL:
        _agent_logger.warning("Price agent configuration mismatch requested_model=%s configured_model=%s", data.model, GEMINI_MODEL if AI_PROVIDER == "gemini" else "PROVIDER_NOT_GEMINI")
        raise HTTPException(status_code=503, detail="Agent configuration mismatch")
    try:
        result = GeminiPriceAgent(os.getenv("GEMINI_API_KEY", ""), GEMINI_MODEL, dict).explain(data.evidence, data.intent, data.question, data.history)
        return {**result, "productId": data.productId, "evidenceVersion": data.evidenceVersion, "model": GEMINI_MODEL}
    except Exception as exc:
        # Never log exception messages, request bodies, provider response bodies or keys.
        code = {
            "Incomplete agent response": "INCOMPLETE_OUTPUT",
            "Agent changed server decision": "DECISION_MISMATCH",
            "Blank agent text": "EMPTY_OUTPUT",
            "Agent response limit": "OUTPUT_LIMIT",
            "Explanation service unavailable": "GEMINI_HTTP_ERROR",
        }.get(str(exc), {
            "ReadTimeout": "GEMINI_TIMEOUT", "ConnectTimeout": "GEMINI_TIMEOUT",
            "ConnectError": "GEMINI_CONNECTION", "ValidationError": "RESPONSE_SCHEMA",
        }.get(type(exc).__name__, "ANALYSIS_FAILED"))
        _agent_logger.warning("Price agent failed model=%s error_code=%s error_type=%s elapsed_seconds=%.1f", GEMINI_MODEL, code, type(exc).__name__, time.monotonic() - started)
        raise HTTPException(status_code=503, detail="Agent unavailable") from None
