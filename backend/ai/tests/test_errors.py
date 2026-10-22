import os
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

with patch.dict(os.environ, {
    "APP_ENV": "development", "AI_PROVIDER": "fixture", "INTERNAL_API_KEY": "test-internal-key",
}):
    from app.main import app, INTERNAL_KEY


class ErrorContractTest(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(app, raise_server_exceptions=False)
        self.headers = {"X-Internal-Key": INTERNAL_KEY}
        self.body = {
            "productId": "p", "observedDays": 30, "currentPrice": 10000,
            "averagePrice": 11000, "referenceTime": "2026-10-22T03:00:00Z",
            "verdict": "BUY", "reasonCode": "BELOW_AVERAGE", "ruleVersion": "price-rules-v1",
            "explanationVersion": "explanation-v1", "evidenceSnapshotId": "fixture:test-evidence",
        }

    def tearDown(self):
        self.client.close()

    def assert_error(self, response, status, code):
        self.assertEqual(response.status_code, status)
        error = response.json()["error"]
        self.assertEqual(error["code"], code)
        self.assertEqual(response.headers["X-Request-Id"], error["requestId"])
        self.assertEqual(error["retryable"], status == 429 or status >= 500)

    def test_missing_key_uses_common_403(self):
        self.assert_error(self.client.post("/internal/analyze", json=self.body), 403, "HTTP_403")

    def test_validation_error_does_not_echo_invalid_input(self):
        body = {**self.body, "productId": {"private": "sensitive-input"}}
        response = self.client.post("/internal/analyze", json=body, headers=self.headers)
        self.assert_error(response, 422, "VALIDATION_ERROR")
        self.assertNotIn("sensitive-input", response.text)

    def test_unknown_route_and_wrong_method_use_common_errors(self):
        self.assert_error(self.client.get("/missing"), 404, "HTTP_404")
        response = self.client.get("/internal/analyze")
        self.assert_error(response, 405, "HTTP_405")
        self.assertIn("POST", response.headers["Allow"])

    def test_unexpected_error_is_safe_and_retryable(self):
        with patch("app.main.provider.analyze", side_effect=RuntimeError("private-upstream-key")):
            response = self.client.post("/internal/analyze", json=self.body, headers=self.headers)
        self.assert_error(response, 500, "HTTP_500")
        self.assertNotIn("private-upstream-key", response.text)

    def test_openapi_documents_security_and_error_envelopes(self):
        spec = self.client.get("/openapi.json").json()
        operation = spec["paths"]["/internal/analyze"]["post"]
        self.assertEqual(operation["security"], [{"InternalKey": []}])
        for status in ["403", "422", "500"]:
            self.assertEqual(operation["responses"][status]["content"]["application/json"]["schema"]["$ref"],
                             "#/components/schemas/ErrorEnvelope")

    def test_explanation_echoes_evidence_but_cannot_return_verdict_or_prices(self):
        # Deliberately inconsistent prices: this service must describe, never recalculate.
        response = self.client.post("/internal/analyze", json={**self.body, "currentPrice": 20000}, headers=self.headers)
        self.assertEqual(response.status_code, 200)
        result = response.json()
        self.assertEqual(result["evidenceSnapshotId"], self.body["evidenceSnapshotId"])
        self.assertEqual(result["ruleVersion"], "price-rules-v1")
        self.assertNotIn("verdict", result)
        self.assertNotIn("currentPrice", result)
        self.assertIn("낮은 구간", result["reasons"][0])

    def test_unknown_versions_extra_fields_and_naive_time_are_rejected(self):
        for fields in [{"ruleVersion": "unknown"}, {"extra": "field"}, {"referenceTime": "2026-10-22T03:00:00"}]:
            with self.subTest(fields=fields):
                self.assert_error(self.client.post("/internal/analyze", json={**self.body, **fields}, headers=self.headers), 422, "VALIDATION_ERROR")


if __name__ == "__main__":
    unittest.main()
