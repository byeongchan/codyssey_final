import json
import os
import unittest
from unittest.mock import Mock, patch

from fastapi.testclient import TestClient
from pydantic import ValidationError

with patch.dict(os.environ, {"APP_ENV": "development", "AI_PROVIDER": "fixture", "INTERNAL_API_KEY": "test-internal-key"}):
    from app import main
from app.review_summary import GeminiReviewProvider, ReviewText


class ReviewSummaryTest(unittest.TestCase):
    def envelope(self, ids=None):
        return {"candidates": [{"finishReason": "STOP", "content": {"parts": [{"text": json.dumps({
            "summary": "입력 표본에서 착용감에 대한 긍정 의견이 확인됩니다.",
            "positives": [{"theme": "편안한 착용감", "reviewIds": ids or [1]}], "negatives": [],
        }, ensure_ascii=False)}]}}]}

    def test_summary_uses_only_input_and_checks_evidence_ids(self):
        transport = Mock(return_value=self.envelope())
        provider = GeminiReviewProvider("test", "gemini-test", dict, transport)
        result = provider.summarize(["착용감이 편안해요."])
        self.assertEqual(result.positives[0].reviewIds, [1])
        payload = transport.call_args.args[0]
        self.assertEqual(json.loads(payload["contents"][0]["parts"][0]["text"])[0]["id"], 1)
        self.assertNotIn("sampleCount", result.model_dump())
        for ids in ([2], [1, 1]):
            transport.return_value = self.envelope(ids)
            with self.assertRaises(ValueError):
                provider.summarize(["착용감이 편안해요."])

    def test_input_is_bounded_and_duplicates_do_not_inflate_sample_count(self):
        value = main.ReviewInput(productId="123", reviews=[" 좋아요 ", "좋아요"], model="gemini-test")
        self.assertEqual(value.reviews, ["좋아요"])
        for reviews in ([" "], ["x" * 2001], ["x"] * 21, [str(i) + "x" * 1998 for i in range(9)]):
            with self.assertRaises(ValidationError):
                main.ReviewInput(productId="123", reviews=reviews, model="gemini-test")

    def test_endpoint_requires_internal_auth_and_never_returns_fixture_review_analysis(self):
        client = TestClient(main.app)
        body = {"productId": "123", "reviews": ["착용감이 편안해요."], "model": "gemini-test"}
        self.assertEqual(client.post("/internal/reviews/summarize", json=body).status_code, 403)
        with patch.object(main, "AI_PROVIDER", "fixture"):
            self.assertEqual(client.post("/internal/reviews/summarize", json=body, headers={"X-Internal-Key": main.INTERNAL_KEY}).status_code, 503)

    def test_endpoint_owns_identity_and_sample_count(self):
        client = TestClient(main.app)
        output = ReviewText(summary="착용감을 긍정적으로 언급했습니다.", positives=[], negatives=[])
        with patch.object(main, "AI_PROVIDER", "gemini"), patch.object(main, "GEMINI_MODEL", "gemini-test"), patch.object(main, "GeminiReviewProvider") as provider:
            provider.return_value.summarize.return_value = output
            response = client.post("/internal/reviews/summarize", json={"productId": "123", "reviews": ["좋아요", "좋아요"], "model": "gemini-test"}, headers={"X-Internal-Key": main.INTERNAL_KEY})
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()["source"], "user-provided")
            self.assertEqual(response.json()["sampleCount"], 1)
            provider.return_value.summarize.assert_called_once_with(["좋아요"])
