import json
import unittest
from unittest.mock import Mock
from app import product_identity


class ProductIdentityTest(unittest.TestCase):
    def setUp(self):
        product_identity._cache.clear()

    def test_product_text_is_classified_without_paid_call(self):
        transport = Mock()
        result = product_identity.identify(
            "상품 모델 X123", intro="무선 노트북", transport=transport
        )
        self.assertEqual(result["category"], "노트북")
        self.assertEqual(result["basis"], "PRODUCT_TEXT_CATEGORY")
        transport.assert_not_called()

    def test_unknown_model_can_use_cached_ai_category_without_saving_specs(self):
        content = {
            "category": "헤드폰",
            "description": "모델명을 바탕으로 헤드폰으로 추정합니다.",
            "confidence": "HIGH",
        }
        transport = Mock(
            return_value={
                "candidates": [
                    {"finishReason": "STOP", "content": {"parts": [{"text": json.dumps(content)}]}}
                ]
            }
        )
        first = product_identity.identify(
            "WH-1000XM5", key="fake-key", model="gemini-test", transport=transport
        )
        second = product_identity.identify(
            "WH-1000XM5", key="fake-key", model="gemini-test", transport=transport
        )
        self.assertEqual(first, second)
        self.assertEqual(first["basis"], "AI_INFERRED")
        self.assertEqual(first["category"], "헤드폰")
        transport.assert_called_once()
        self.assertNotIn("specifications", first)

    def test_failed_classification_does_not_invent_category(self):
        transport = Mock(side_effect=ValueError("unavailable"))
        result = product_identity.identify(
            "알 수 없는 모델", key="fake-key", model="gemini-test", transport=transport
        )
        self.assertEqual(result["confidence"], "UNKNOWN")
        self.assertEqual(result["category"], "")
