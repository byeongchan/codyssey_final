"""One bounded generateContent request. No SDK auto retries or configurable remote URL."""
import json
import re
import time
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field


class ExplanationText(BaseModel):
    model_config = ConfigDict(extra="forbid")
    reasons: list[Annotated[str, Field(min_length=1, max_length=200)]] = Field(min_length=1, max_length=3)
    risks: list[Annotated[str, Field(min_length=1, max_length=200)]] = Field(min_length=1, max_length=3)


def validate_text(value: ExplanationText) -> None:
    for text in value.reasons + value.risks:
        if not text.strip() or re.search(r"\d|보장|확실히|할인 예정|재고 있음", text):
            raise ValueError("Invalid explanation wording")


class GeminiExplanationProvider:
    def __init__(self, key, model, factory, transport=None):
        if not key or not re.fullmatch(r"gemini-[A-Za-z0-9._-]{1,100}", model):
            raise RuntimeError("Set GEMINI_API_KEY and a valid GEMINI_MODEL")
        self.key, self.model, self.factory = key, model, factory
        if transport is None:
            import httpx  # Fail at startup if optional Gemini requirements are missing.
        self.transport = transport or self._generate

    def _generate(self, payload):
        import httpx  # Optional dependency, never imported by fixture mode.
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{self.model}:generateContent"
        started = time.monotonic()
        with httpx.Client(timeout=httpx.Timeout(4.0, connect=2.0), trust_env=False, follow_redirects=False) as client:
            with client.stream("POST", url, headers={"x-goog-api-key": self.key}, json=payload) as response:
                if response.status_code != 200:
                    raise ValueError("Explanation service unavailable")
                body = bytearray()
                for chunk in response.iter_bytes():
                    body.extend(chunk)
                    if len(body) > 65536 or time.monotonic() - started > 6:
                        raise ValueError("Explanation response limit")
        return json.loads(body)

    def analyze(self, data):
        facts = {name: getattr(data, name) for name in ("verdict", "reasonCode", "currentPrice", "averagePrice", "observedDays")}
        source_instruction = ("입력은 합성 가격 데이터이며 실제 시세가 아니다. 주의사항에 합성 데이터의 한계를 설명한다. " if data.synthetic else
            "입력은 Reef 다나와 실제 표시가 관측이다. 재고와 판매처 옵션 일치는 검증되지 않았다. "
            "표시가는 확정 구매가가 아니다. 수집 시각이 미제공되거나 오래되었을 수 있다. "
            "평균은 최근 관측 날짜의 표시가 요약이며 시장 전체 평균이 아니다. 구매 판단 보류와 데이터 한계를 설명한다. ")
        payload = {
            "systemInstruction": {"parts": [{"text": (
                source_instruction + "한국어 구매 판단 설명을 작성한다. "
                "verdict와 reasonCode는 서버 확정 결과다. 재판정하거나 뒤집지 않는다. "
                "입력 근거 밖의 재고, 쿠폰, 할인 예정, 미래 가격, 확률을 주장하지 않는다. "
                "금액, 일수, 숫자는 문장에 쓰지 않는다. 보장, 확실히, 할인 예정, 재고 있음 표현도 쓰지 않는다. "
                "이유와 주의사항은 각각 짧은 한두 문장으로 작성한다. 각 문장은 백 자 이내로 한다. "
            )}]},
            "contents": [{"role": "user", "parts": [{"text": json.dumps(facts, ensure_ascii=False)}]}],
            "generationConfig": {"responseMimeType": "application/json", "responseJsonSchema": ExplanationText.model_json_schema(),
                                 "maxOutputTokens": 768},
        }
        response = self.transport(payload)
        candidates = response.get("candidates", [])
        if len(candidates) != 1 or candidates[0].get("finishReason") != "STOP":
            raise ValueError("Incomplete explanation")
        parts = candidates[0].get("content", {}).get("parts", [])
        texts = [part["text"] for part in parts if not part.get("thought") and isinstance(part.get("text"), str)]
        text = "".join(texts)
        if not text or len(text) > 6000:
            raise ValueError("Invalid explanation body")
        result = ExplanationText.model_validate_json(text)
        validate_text(result)
        # Identity and timestamps are trusted request metadata, never model-generated.
        return self.factory(productId=data.productId, ruleVersion=data.ruleVersion, explanationVersion=data.explanationVersion,
                            evidenceSnapshotId=data.evidenceSnapshotId, generatedAt=data.referenceTime,
                            reasons=result.reasons, risks=result.risks, model=self.model, synthetic=data.synthetic)
