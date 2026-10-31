import json
import unittest
from unittest.mock import Mock
from app.used_agent import GeminiUsedAgent


class UsedAgentTest(unittest.TestCase):
    def agent(self, content):
        transport = Mock(return_value={"candidates": [{"finishReason": "STOP", "content": {"parts": [{"text": json.dumps(content)}]}}]})
        return GeminiUsedAgent("test", "gemini-test", dict, transport), transport

    def test_quotes_must_exist_in_the_saved_description(self):
        agent, _ = self.agent({"answer": "판매자에게 상태를 확인해 주세요.", "sellerNotes": [{"quote": "배터리 정상"}]})
        with self.assertRaises(ValueError):
            agent.explain({"product": {"description": "구성품 미포함"}}, "상태가 어때?", [])

    def test_only_supplied_evidence_is_sent_without_tools(self):
        agent, transport = self.agent({"answer": "판매자는 구성품 미포함으로 설명합니다.", "sellerNotes": [{"quote": "구성품 미포함"}]})
        evidence = {"product": {"description": "구성품 미포함"}, "assessment": {"decision": "INSUFFICIENT_DATA"}}
        result = agent.explain(evidence, "판매 설명을 알려줘", [])
        payload = transport.call_args.args[0]
        self.assertNotIn("tools", payload)
        self.assertEqual(evidence, json.loads(payload["contents"][0]["parts"][0]["text"])["evidence"])
        self.assertEqual("구성품 미포함", result["sellerNotes"][0]["quote"])

    def test_invented_price_is_rejected(self):
        agent, _ = self.agent({"answer": "가격은 123456원입니다.", "sellerNotes": []})
        with self.assertRaises(ValueError):
            agent.explain({"product": {"price": 5000}}, "가격 알려줘", [])

    def test_detail_uses_api_product_even_when_rule_based_fields_are_empty(self):
        agent, transport = self.agent({"answer": "등록가격은 500000원이며 판매자는 충전기가 없다고 설명합니다.", "sellerNotes": [{"quote": "충전기는 없습니다."}]})
        evidence = {"mode": "detail", "product": {"title": "아이폰15", "price": 500000,
            "description": "충전기는 없습니다.", "categoryName": "디지털기기", "deliveryOptions": "직거래"},
            "detailApi": {"availableFields": ["title", "price", "description", "categoryName", "deliveryOptions"]},
            "assessment": {"detailReport": {"selectedFacts": {"fields": {"model": {"value": ""}}}}}}
        result = agent.explain(evidence, "상품 정보를 정리해 줘", [])
        payload = transport.call_args.args[0]
        self.assertEqual(evidence, json.loads(payload["contents"][0]["parts"][0]["text"])["evidence"])
        self.assertIn("여섯 칸을 채우는 답변을 작성하지 않는다", payload["systemInstruction"]["parts"][0]["text"])
        self.assertIn("500000", result["answer"])

    def test_detail_receives_search_price_and_region_summary_separately_from_matched_prices(self):
        agent, transport = self.agent({"answer": "검색 참고 평균은 50000원이며 지역 미제공 매물은 2개입니다.", "sellerNotes": []})
        evidence = {"mode": "detail", "assessment": {
            "detailReport": {"comparison": {"count": 0}},
            "searchAnalysis": {"average": 50000, "regionSummary": {"total": 5, "unknown": 2}}}}
        agent.explain(evidence, "검색 가격대와 지역을 알려줘", [])
        payload = transport.call_args.args[0]
        sent = json.loads(payload["contents"][0]["parts"][0]["text"])["evidence"]
        self.assertEqual(50000, sent["assessment"]["searchAnalysis"]["average"])
        self.assertEqual(0, sent["assessment"]["detailReport"]["comparison"]["count"])
        self.assertIn("동일 조건 비교", payload["systemInstruction"]["parts"][0]["text"])

    def test_detail_uses_the_attached_policy_and_server_report_without_search_fallback_rules(self):
        agent, transport = self.agent({"answer": "선택 매물의 표시 정보와 확인할 조건을 정리했습니다.", "sellerNotes": []})
        evidence = {"mode": "detail", "product": {"description": "상태 미확인"}, "assessment": {"detailReport": {"finalJudgment": "추가 확인 필요"}}}
        agent.explain(evidence, "확인할 조건은?", [])
        payload = transport.call_args.args[0]
        instruction = payload["systemInstruction"]["parts"][0]["text"]
        self.assertIn("01. 역할과 기본 원칙", instruction)
        self.assertIn("중요한 잔여 항목만 기본 0~2개", instruction)
        self.assertIn("현재 사이트에서는 용량·색상·구성품으로 이 탐색 묶음을 세분화하지 않는다", instruction)
        self.assertIn("CATEGORY", instruction)
        self.assertIn("detailReport", instruction)
        self.assertNotIn("mode=search이면", instruction)
        self.assertNotIn("tools", payload)
