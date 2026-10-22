"""Gemini explanation service for server-owned decisions; explicit fixture mode for tests."""
import os
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

AI_PROVIDER = os.getenv("AI_PROVIDER", "gemini")
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "")
if os.getenv("APP_ENV", "development") != "development" or AI_PROVIDER not in {"fixture", "gemini"}:
    raise RuntimeError("Only development fixture/gemini explanation modes are implemented")
INTERNAL_KEY = os.getenv("INTERNAL_API_KEY", "")
if not INTERNAL_KEY:
    raise RuntimeError("Set INTERNAL_API_KEY before starting the AI service")

app = FastAPI(title="PRIZM development explanation", version="0.4.0")


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
