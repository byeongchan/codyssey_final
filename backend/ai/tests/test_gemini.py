import json
import os
import unittest
from unittest.mock import patch, Mock, MagicMock

from fastapi.testclient import TestClient

with patch.dict(os.environ, {"APP_ENV": "development", "AI_PROVIDER": "fixture", "INTERNAL_API_KEY": "test-internal-key"}):
    from app.main import AnalysisInput, Explanation, app, INTERNAL_KEY
from app.gemini import GeminiExplanationProvider


class GeminiProviderTest(unittest.TestCase):
    def setUp(self):
        self.data = AnalysisInput(productId="p", observedDays=30, currentPrice=9800, averagePrice=10000,
            referenceTime="2026-10-22T03:00:00Z", verdict="BUY", reasonCode="BELOW_AVERAGE",
            ruleVersion="price-rules-v1", explanationVersion="explanation-v1", evidenceSnapshotId="fixture:evidence",
            explanationProvider="gemini", explanationModel="gemini-test-model")

    def envelope(self, text=None, finish="STOP"):
        return {"candidates": [{"finishReason": finish, "content": {"parts": [{"text": json.dumps(text or {
            "reasons": ["관측 평균보다 낮은 구간입니다."], "risks": ["합성 가격 데이터에 대한 설명입니다."]}, ensure_ascii=False)}]}}]}

    def provider(self, response):
        transport = Mock(return_value=response)
        return GeminiExplanationProvider("test-key", "gemini-test-model", Explanation, transport), transport

    def test_structured_text_has_server_owned_identity_and_no_personal_prompt(self):
        provider, transport = self.provider(self.envelope())
        result = provider.analyze(self.data)
        self.assertEqual(result.model, "gemini-test-model")
        self.assertEqual(result.evidenceSnapshotId, self.data.evidenceSnapshotId)
        self.assertNotIn("verdict", result.model_dump())
        payload = transport.call_args.args[0]
        self.assertEqual(payload["generationConfig"]["responseMimeType"], "application/json")
        self.assertNotIn("test-key", json.dumps(payload))
        self.assertNotIn("fixture:evidence", json.dumps(payload))
        self.assertNotIn("productId", payload["contents"][0]["parts"][0]["text"])

    def test_truncation_blocking_override_numeric_and_promises_are_rejected(self):
        cases = [self.envelope(finish="MAX_TOKENS"), self.envelope(finish="SAFETY"),
            self.envelope({"verdict": "WAIT", "reasons": ["설명"], "risks": ["주의"]}),
            self.envelope({"reasons": ["내일 100원입니다."], "risks": ["주의"]}),
            self.envelope({"reasons": ["최저가 보장"], "risks": ["주의"]}), {"candidates": []}]
        for case in cases:
            with self.subTest(case=case):
                provider, transport = self.provider(case)
                with self.assertRaises(ValueError):
                    provider.analyze(self.data)
                transport.assert_called_once()

    def test_missing_key_or_url_like_model_is_rejected_without_request(self):
        for key, model in [("", "gemini-test"), ("key", "https://example.test/model")]:
            with self.assertRaises(RuntimeError):
                GeminiExplanationProvider(key, model, Explanation, Mock())

    def test_real_observations_preserve_source_and_hold_context(self):
        data = self.data.model_copy(update={"synthetic": False, "verdict": "INSUFFICIENT_DATA", "reasonCode": "UNVERIFIED_STOCK_OR_OPTION"})
        provider, transport = self.provider(self.envelope({"reasons": ["옵션 확인이 필요합니다."], "risks": ["재고가 확인되지 않았습니다."]}))
        self.assertFalse(provider.analyze(data).synthetic)
        instruction = transport.call_args.args[0]["systemInstruction"]["parts"][0]["text"]
        self.assertIn("실제 표시가 관측", instruction)
        self.assertNotIn("합성 가격 데이터", instruction)

    def test_http_transport_uses_fixed_https_header_and_never_retries_errors(self):
        response = Mock(status_code=429)
        client = MagicMock()
        client.__enter__.return_value = client
        client.stream.return_value.__enter__.return_value = response
        with patch("httpx.Client", return_value=client) as factory:
            provider = GeminiExplanationProvider("test-key", "gemini-test-model", Explanation)
            with self.assertRaises(ValueError):
                provider.analyze(self.data)
            client.stream.assert_called_once()
            args, kwargs = client.stream.call_args
            self.assertEqual(args[1], "https://generativelanguage.googleapis.com/v1beta/models/gemini-test-model:generateContent")
            self.assertEqual(kwargs["headers"], {"x-goog-api-key": "test-key"})
            self.assertFalse(factory.call_args.kwargs["trust_env"])
            self.assertFalse(factory.call_args.kwargs["follow_redirects"])

    def test_provider_errors_and_configuration_mismatch_are_sanitized(self):
        provider = Mock()
        provider.analyze.side_effect = RuntimeError("private-api-key")
        with patch("app.main.AI_PROVIDER", "gemini"), patch("app.main.GEMINI_MODEL", "gemini-test-model"), patch("app.main.provider", provider):
            with TestClient(app, raise_server_exceptions=False) as client:
                body = self.data.model_dump(mode="json")
                response = client.post("/internal/analyze", json=body, headers={"X-Internal-Key": INTERNAL_KEY})
                self.assertEqual(response.status_code, 503)
                self.assertNotIn("private-api-key", response.text)
                provider.analyze.assert_called_once()
                provider.reset_mock()
                response = client.post("/internal/analyze", json={**body, "explanationProvider": "fixture"}, headers={"X-Internal-Key": INTERNAL_KEY})
                self.assertEqual(response.status_code, 503)
                provider.analyze.assert_not_called()


if __name__ == "__main__":
    unittest.main()
