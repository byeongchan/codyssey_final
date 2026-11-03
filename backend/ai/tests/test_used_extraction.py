import json
import unittest
from app.used_extraction import GeminiUsedExtraction


class UsedExtractionTests(unittest.TestCase):
    def extract(self, quote):
        empty = {"value": "", "quote": "", "source": "title", "certainty": "UNKNOWN"}
        item = {
            name: dict(empty)
            for name in ("model", "options", "condition", "contents", "unit", "saleType")
        }
        item.update(
            id="a" * 64,
            model={
                "value": "아이폰 15",
                "quote": quote,
                "source": "title",
                "certainty": "EXPLICIT",
            },
        )
        response = {
            "candidates": [
                {
                    "finishReason": "STOP",
                    "content": {"parts": [{"text": json.dumps({"items": [item]})}]},
                }
            ]
        }
        agent = GeminiUsedExtraction("test-key", "gemini-test", dict, transport=lambda _: response)
        return agent.extract(
            [{"id": "a" * 64, "title": "아이폰 15 256GB 판매", "description": "생활기스 있어요"}]
        )

    def test_title_model_without_label(self):
        self.assertEqual("아이폰 15", self.extract("아이폰 15 256GB")["items"][0]["model"]["value"])

    def test_fabricated_quote_rejected(self):
        with self.assertRaises(ValueError):
            self.extract("아이폰 16 새상품")
