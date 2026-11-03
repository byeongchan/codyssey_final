import unittest
from datetime import datetime, timedelta, timezone
from app import news_impact as impact


class NewsImpactTest(unittest.TestCase):
    now = datetime(2026, 10, 26, 6, tzinfo=timezone.utc)

    def context(self, category="그래픽카드", title="NVIDIA RTX 5070 그래픽카드", intro=""):
        return impact.normalize_product(category, [category], title, "123", "", "", intro)

    def article(self, title, summary, age=1, url="https://www.yna.co.kr/view/test"):
        return {
            "title": title,
            "summary": summary,
            "url": url,
            "originalUrl": url,
            "naverUrl": "https://n.news.naver.com/article/001/test",
            "publisher": "www.yna.co.kr",
            "publishedAt": (self.now - timedelta(days=age)).isoformat(),
            "dateBasis": "NAVER_PROVIDED_AT",
        }

    def test_gpu_queries_expand_without_fabricating_gddr7_specification(self):
        context = self.context()
        queries = [p["query"] for p in impact.search_plan(context)]
        self.assertEqual(len(queries), impact.LIMITS["maxQueries"])
        self.assertTrue(any("생산 공급" in q for q in queries))
        self.assertTrue(any("관세" in q for q in queries))
        self.assertFalse(context["components"])
        self.assertFalse(any("GDDR7" in q for q in queries))
        explicit = self.context(intro="메모리: GDDR7")
        self.assertTrue(any("gddr7" in q["query"].lower() for q in impact.search_plan(explicit)))
        self.assertTrue(all(c["basis"] == "PRODUCT_TEXT" for c in explicit["components"]))

    def test_food_phone_laptop_appliance_do_not_reuse_gpu_queries(self):
        for category, title in [
            ("스마트폰", "삼성 스마트폰"),
            ("노트북", "삼성 노트북"),
            ("냉장고", "LG 냉장고"),
            ("커피", "원두 커피"),
            ("쌀", "쌀"),
        ]:
            queries = " ".join(
                row["query"] for row in impact.search_plan(self.context(category, title))
            )
            self.assertNotIn("GPU", queries)
            self.assertNotIn("그래픽", queries)
        coffee = self.context("커피", "원두 커피")
        self.assertTrue(any("수확" in q["query"] for q in impact.search_plan(coffee)))

    def test_indirect_manufacturer_production_story_has_path_without_product_name(self):
        article = self.article(
            "엔비디아 GPU 공급 부족",
            "엔비디아 GPU 생산 차질로 공급 감소가 이어지고 있다. 유통업계는 해당 제품군의 수급 상황과 재고 확보 일정을 계속 점검하고 있다고 전했다.",
        )
        result = impact.evaluate(article, self.context(), self.now)
        self.assertEqual(result["priceImpact"], "UPWARD_PRESSURE")
        self.assertTrue(result["impactPath"])
        self.assertTrue(result["reportedFacts"])
        self.assertEqual(result["assessmentStage"], "EVIDENCE")
        self.assertFalse(result["scoreDimensions"]["direct"])

    def test_unknown_component_is_reference_and_not_a_verified_product_attribute(self):
        article = self.article(
            "GDDR7 가격 상승",
            "GDDR7 가격 상승이 이어지며 부품 시장의 공급 상황에 관심이 모였다. 업계는 계약 조건과 향후 생산 계획을 확인 중이라고 밝혔다.",
        )
        result = impact.evaluate(article, self.context(), self.now)
        self.assertEqual(result["priceImpact"], "UPWARD_PRESSURE")
        self.assertEqual(result["assessmentStage"], "REFERENCE")
        self.assertTrue(any("탑재" in text for text in result["uncertainties"]))

    def test_earnings_and_unconnected_launch_do_not_become_price_evidence(self):
        for title, summary in [
            (
                "엔비디아 실적 발표",
                "엔비디아 영업이익과 실적이 발표되었으며 주가와 목표가격 상승에 대한 증권가의 의견이 이어졌다.",
            ),
            (
                "새 그래픽카드 출시",
                "그래픽카드 신제품이 공개되어 성능과 디자인이 소개되었다. 공급과 판매 가격 변화는 이 기사 설명에 포함되지 않았다.",
            ),
        ]:
            result = impact.evaluate(self.article(title, summary), self.context(), self.now)
            self.assertNotIn(result["assessmentStage"], {"EVIDENCE", "REFERENCE"})
            self.assertEqual(result["priceImpact"], "IRRELEVANT")

    def test_tariff_scope_is_uncertain_reference_not_applied_fact(self):
        result = impact.evaluate(
            self.article(
                "반도체 수출 규제 검토",
                "반도체 수출 규제에 대한 검토와 정책 논의가 이어지고 있다. 구체적인 대상 국가와 적용 범위는 아직 결정되지 않았다고 전했다.",
            ),
            self.context(),
            self.now,
        )
        self.assertEqual(result["priceImpact"], "UNCERTAIN")
        self.assertEqual(result["assessmentStage"], "REFERENCE")

    def test_mixed_signals_and_resolved_shortage(self):
        mixed = impact.evaluate(
            self.article(
                "GPU 수급 변화",
                "GPU 공급 부족이 이어지는 한편 그래픽카드 재고 증가도 관측됐다. 유통 경로에 따라 재고와 공급 상황이 다르게 나타난다고 전했다.",
            ),
            self.context(),
            self.now,
        )
        self.assertEqual(mixed["priceImpact"], "MIXED")
        resolved = impact.evaluate(
            self.article(
                "GPU 공급 회복",
                "GPU 공급 부족이 앞서 나타났다. GPU 공급 차질 해소와 생산 정상화가 확인됐다는 업계의 설명이 이어졌다.",
            ),
            self.context(),
            self.now,
        )
        self.assertEqual(resolved["priceImpact"], "DOWNWARD_PRESSURE")
        self.assertFalse(any("공급 감소 요인" in path for path in resolved["impactPath"]))

    def test_older_unknown_source_and_denial_are_not_strong_evidence(self):
        for article in [
            self.article(
                "GPU 생산 차질",
                "GPU 생산 차질과 공급 부족이 이어지고 있다. 유통사들은 향후 공급량과 재고 확보 계획을 계속 점검하고 있다고 밝혔다.",
                age=20,
            ),
            self.article(
                "GPU 생산 차질",
                "GPU 생산 차질과 공급 부족이 이어지고 있다. 유통사들은 향후 공급량과 재고 확보 계획을 계속 점검하고 있다고 밝혔다.",
                url="https://unknown.test/a",
            ),
        ]:
            self.assertNotEqual(
                impact.evaluate(article, self.context(), self.now)["assessmentStage"], "EVIDENCE"
            )
        denied = impact.evaluate(
            self.article(
                "GPU 공급 부족 보도 부인",
                "GPU 공급 부족은 사실이 아니라고 회사가 부인했다. 유통 현황과 납품 계획을 설명하면서 기존 생산 일정에는 변동이 없다고 전했다.",
            ),
            self.context(),
            self.now,
        )
        self.assertNotEqual(denied["priceImpact"], "UPWARD_PRESSURE")

    def test_duplicates_are_one_event_and_opposing_evidence_is_retained(self):
        up = self.article(
            "엔비디아 GPU 생산 차질 소식",
            "엔비디아 GPU 생산 차질과 공급 감소가 이어지고 있다. 유통업계는 공급량과 입고 시점을 계속 확인하고 있다고 밝혔다.",
        )
        duplicate = {
            **up,
            "url": "https://other.test/story",
            "originalUrl": "https://other.test/story",
        }
        down = self.article(
            "그래픽카드 재고 증가",
            "그래픽카드 재고 증가로 가격 경쟁 확대가 이어지고 있다. 유통업계는 재고 처리를 위한 판매 정책을 시행하고 있다고 밝혔다.",
            url="https://www.yna.co.kr/down",
        )
        selected, stats = impact.rank_articles([up, duplicate, down], self.context(), self.now)
        self.assertEqual(stats["uniqueCandidates"], 2)
        self.assertTrue(any(a["duplicateCount"] == 1 for a in selected))
        self.assertEqual(
            {a["priceImpact"] for a in selected}, {"UPWARD_PRESSURE", "DOWNWARD_PRESSURE"}
        )

    def test_third_party_model_discount_is_not_selected_without_competition_path(self):
        article = self.article(
            "다른 그래픽카드 모델 할인 행사",
            "AMD RX 9060 그래픽카드 판매가 인하와 할인 행사가 발표됐다. 판매처는 해당 모델의 구매 조건과 행사 기간을 안내하고 있다고 밝혔다.",
        )
        result = impact.evaluate(article, self.context(), self.now)
        self.assertNotIn(result["assessmentStage"], {"EVIDENCE", "REFERENCE"})
