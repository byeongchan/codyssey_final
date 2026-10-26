import json
import os
import unittest
from unittest.mock import Mock, patch
from app import price_agent


class PriceAgentTest(unittest.TestCase):
    def test_real_question_and_recent_context_are_forwarded_to_model(self):
        content={"decision":"INSUFFICIENT_DATA","headline":"용도 안내","reasoning":[],"risks":[],"wait_condition":None,"data_limitations":[],"answer":"상품명 기준으로 그래픽카드로 보입니다. 앞서 말씀하신 게임 용도로 확인해 보겠습니다."}
        transport=Mock(return_value={"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":json.dumps(content)}]}}]})
        agent=price_agent.GeminiPriceAgent("test","gemini-test",dict,transport)
        evidence={"assessment":{"decision":"INSUFFICIENT_DATA","wait_condition":None,"risks":[],"data_limitations":[]}}
        history=[{"role":"user","content":"게임용으로 사용할 거예요."}]
        result=agent.explain(evidence,"SPECS","그 용도로 괜찮아?",history)
        submitted=json.loads(transport.call_args.args[0]["contents"][0]["parts"][0]["text"])
        self.assertEqual(submitted["question"],"그 용도로 괜찮아?")
        self.assertEqual(submitted["history"],history)
        self.assertIn("게임",result["answer"])
    def setUp(self):
        settings = patch.object(price_agent, "rss_settings", side_effect=lambda: (os.getenv("RSS_FEEDS_JSON", "[]"), os.getenv("RSS_ALLOWED_HOSTS", "")))
        settings.start()
        self.addCleanup(settings.stop)

    def test_schema_and_server_decision_are_both_enforced(self):
        content = {"decision": "BUY", "headline": "Reasonable current price", "reasoning": ["Below recent median"], "risks": [], "wait_condition": None, "data_limitations": [], "answer": "Purchase is reasonable."}
        transport = Mock(return_value={"candidates": [{"finishReason": "STOP", "content": {"parts": [{"text": json.dumps(content)}]}}]})
        agent = price_agent.GeminiPriceAgent("test", "gemini-test", dict, transport)
        evidence = {"assessment": {"decision": "BUY", "wait_condition": None, "risks": ["Past is not a forecast"], "data_limitations": []}}
        result = agent.explain(evidence, "PRICE")
        self.assertEqual(result["decision"], "BUY")
        self.assertIn("Past is not a forecast", result["risks"])
        config = transport.call_args.args[0]["generationConfig"]
        self.assertEqual(config["responseMimeType"], "application/json")
        self.assertIn("decision", config["responseJsonSchema"]["required"])
        for change in ({"decision": "WAIT"}, {"wait_condition": "Invented sale tomorrow"}, {"extra": "unexpected"}, {"decision": "NEUTRAL"}):
            invalid = {**content, **change}
            transport.return_value["candidates"][0]["content"]["parts"][0]["text"] = json.dumps(invalid)
            with self.assertRaises(ValueError):
                agent.explain(evidence, "PRICE")

    def test_news_off_is_distinct_from_failure_and_articles_require_product_match(self):
        with patch.dict("os.environ", {"RSS_FEEDS_JSON": "[]", "RSS_ALLOWED_HOSTS": ""}), patch.object(price_agent, "_rss_cache", None):
            self.assertEqual(price_agent.news_for("모델 123")['state'], "NOT_CONFIGURED")
        article = {"title": "Galaxy S25 소식", "summary": "제품 출시 기사", "url": "https://example.test/article", "publisher": "test", "publishedAt": "2026-10-26T00:00:00Z"}
        settings = {"RSS_FEEDS_JSON": '[{"name":"test","url":"https://example.test/rss"}]', "RSS_ALLOWED_HOSTS": "example.test"}
        with patch.dict("os.environ", settings), patch.object(price_agent, "_rss_cache", None), patch.object(price_agent, "_fetch_feed", return_value=[article]) as fetch:
            first = price_agent.news_for("Samsung Galaxy S25")
            self.assertEqual(len(first['articles']), 1)
            self.assertEqual(price_agent.news_for("다른 제품 999")['articles'], [])
            self.assertEqual(first['version'], price_agent.news_for("Samsung Galaxy S25")['version'])
            fetch.assert_called_once()
