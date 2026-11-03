import json
import unittest
from datetime import datetime, timedelta, timezone
from email.utils import format_datetime
from unittest.mock import MagicMock, patch

from app import naver_news, news_sources


class NaverNewsTest(unittest.TestCase):
    now = datetime(2026, 10, 26, 6, tzinfo=timezone.utc)

    def item(self, days=1, title="Galaxy S25 release", url="https://publisher.test/news/1"):
        return {
            "title": title,
            "description": "스마트폰 Galaxy S25 판매가 인하와 할인 확대가 발표됐다. 유통업계는 이번 판매 정책과 적용 조건을 자세히 설명했다고 전했다.",
            "originallink": url,
            "link": "https://n.news.naver.com/article/001/1",
            "pubDate": format_datetime(self.now - timedelta(days=days)),
        }

    def setUp(self):
        naver_news._cache.clear()
        naver_news._analysis_cache.clear()

    def test_exact_rolling_thirty_day_window_and_no_fabricated_dates(self):
        missing = self.item()
        missing.pop("pubDate")
        naive = self.item()
        naive["pubDate"] = "Mon, 26 Oct 2026 01:00:00"
        items = [
            self.item(30),
            self.item(31, url="https://publisher.test/old"),
            self.item(-1),
            missing,
            naive,
        ]
        selected = naver_news.normalize_articles(items, self.now)
        self.assertEqual(len(selected), 1)
        self.assertEqual(selected[0]["dateBasis"], "NAVER_PROVIDED_AT")
        self.assertEqual(selected[0]["publisher"], "publisher.test")

    def test_wrong_model_html_duplicates_and_dangerous_links(self):
        wrong = self.item(title="Galaxy S24 release")
        wrong["description"] = "Galaxy S24 product"
        valid = self.item(title="Galaxy <b>S25</b> &amp; updates")
        bad = self.item()
        bad["originallink"] = "javascript:alert(1)"
        bad["link"] = "http://127.0.0.1/private"
        selected = naver_news.select_articles(
            [wrong, valid, valid, bad],
            "스마트폰",
            self.now,
            keywords=["스마트폰"],
            product_title="Samsung Galaxy S25",
        )
        self.assertEqual(len(selected), 1)
        self.assertEqual(selected[0]["title"], "Galaxy S25 & updates")
        self.assertNotIn("<b>", selected[0]["summary"])

    def test_category_search_accepts_different_models_but_excludes_other_categories(self):
        phone = self.item(title="스마트폰 시장 변화와 갤럭시 S24 출시")
        phone["description"] = (
            "스마트폰 판매가 인상이 나타나며 유통 가격도 상승했다. 업계는 수급 상황과 판매 조건을 지속적으로 확인하고 있다고 전했다."
        )
        laptop = self.item(title="노트북 시장 소식", url="https://publisher.test/laptop")
        laptop["description"] = "노트북 신제품 출시"
        found = naver_news.select_articles(
            [phone, laptop], "스마트폰", self.now, keywords=["스마트폰", "아이폰"]
        )
        self.assertEqual(len(found), 1)
        self.assertIn("S24", found[0]["title"])
        self.assertTrue(found[0]["scoreDimensions"]["category"])

    def test_multiple_queries_reuse_retrieval_across_aliases_and_product_context(self):
        settings = {"NAVER_CLIENT_ID": "fake-id", "NAVER_CLIENT_SECRET": "fake-secret"}
        with (
            patch.object(naver_news, "local_settings", return_value=settings),
            patch.object(naver_news, "_fetch", return_value=[]) as fetch,
        ):
            result = naver_news.news_for("노트북", keywords=["노트북", "맥북"])
            self.assertLessEqual(fetch.call_count, naver_news.LIMITS["maxQueries"])
            first_count = fetch.call_count
            self.assertTrue(any("노트북" in call.args[0] for call in fetch.call_args_list))
            self.assertEqual(result["category"], "노트북")
            self.assertEqual(result["searchBasis"], "PRICE_FACTORS")
            reused = naver_news.news_for(
                "노트북", keywords=["노트북", "맥북"], category_basis="PRODUCT_TEXT_CATEGORY"
            )
            self.assertEqual(reused["categoryBasis"], "PRODUCT_TEXT_CATEGORY")
            naver_news.news_for("노트북", keywords=["노트북", "laptop"])
            self.assertEqual(fetch.call_count, first_count)

    def test_same_url_keeps_newest_and_at_most_five_articles(self):
        items = [
            self.item(i, title=f"Galaxy S25 story {i}", url=f"https://publisher.test/{i}")
            for i in range(10)
        ]
        items.append(self.item(20, title="Galaxy S25 old headline", url="https://publisher.test/0"))
        selected = naver_news.select_articles(
            items, "스마트폰", self.now, keywords=["스마트폰"], product_title="Galaxy S25"
        )
        self.assertGreater(len(selected), 0)
        self.assertLessEqual(len(selected), 5)
        self.assertEqual(selected[0]["title"], "Galaxy S25 story 0")

    def test_missing_keys_skip_network(self):
        with (
            patch.object(
                naver_news,
                "local_settings",
                return_value={"NAVER_CLIENT_ID": "", "NAVER_CLIENT_SECRET": ""},
            ),
            patch.object(naver_news, "_fetch") as fetch,
        ):
            self.assertEqual(naver_news.news_for("Galaxy S25")["state"], "NOT_CONFIGURED")
            fetch.assert_not_called()

    def test_results_are_cached_without_credentials_or_http_auto_retry(self):
        settings = {"NAVER_CLIENT_ID": "fake-id", "NAVER_CLIENT_SECRET": "fake-secret"}
        with (
            patch.object(naver_news, "local_settings", return_value=settings),
            patch.object(naver_news, "_fetch", return_value=[]) as fetch,
        ):
            first = naver_news.news_for("Galaxy S25")
            self.assertEqual(first["state"], "READY")
            self.assertEqual(first["version"], naver_news.news_for("Galaxy S25")["version"])
            self.assertEqual(fetch.call_count, naver_news.LIMITS["maxQueries"])
            self.assertNotIn("fake-secret", json.dumps(first))
            self.assertNotIn("fake-id", json.dumps(first))

    def test_auth_and_quota_failures_are_cached_and_not_confused_with_no_results(self):
        settings = {"NAVER_CLIENT_ID": "fake-id", "NAVER_CLIENT_SECRET": "fake-secret"}
        for state in ("AUTH_FAILED", "RATE_LIMITED", "FAILED"):
            naver_news._cache.clear()
            naver_news._analysis_cache.clear()
            with (
                patch.object(naver_news, "local_settings", return_value=settings),
                patch.object(
                    naver_news, "_fetch", side_effect=naver_news.SearchFailure(state)
                ) as fetch,
            ):
                self.assertEqual(naver_news.news_for("Galaxy S25")["state"], state)
                self.assertEqual(naver_news.news_for("Galaxy S25")["state"], state)
                self.assertEqual(
                    fetch.call_count,
                    1
                    if state in {"AUTH_FAILED", "RATE_LIMITED"}
                    else naver_news.LIMITS["maxQueries"],
                )

    def test_explicit_provider_does_not_silently_fallback_to_rss(self):
        with (
            patch.object(news_sources, "local_settings", return_value={"NEWS_PROVIDER": "naver"}),
            patch.object(
                news_sources, "naver_news", return_value={"state": "NOT_CONFIGURED"}
            ) as search,
            patch.object(news_sources, "rss_news") as rss,
        ):
            self.assertEqual(news_sources.news_for("Galaxy S25")["state"], "NOT_CONFIGURED")
            search.assert_called_once()
            rss.assert_not_called()

    def test_partial_failure_is_not_reported_as_complete_absence_and_policy_invalidates_analysis_only(
        self,
    ):
        settings = {"NAVER_CLIENT_ID": "fake-id", "NAVER_CLIENT_SECRET": "fake-secret"}

        def fetch(query, *args):
            if "관세" in query:
                raise naver_news.SearchFailure("RATE_LIMITED", 429, "429")
            return []

        with (
            patch.object(naver_news, "local_settings", return_value=settings),
            patch.object(naver_news, "_fetch", side_effect=fetch) as request,
        ):
            first = naver_news.news_for("노트북", keywords=["노트북"])
            self.assertEqual(first["state"], "PARTIAL")
            calls = request.call_count
            with patch.object(naver_news, "POLICY_HASH", "changed-policy"):
                second = naver_news.news_for("노트북", keywords=["노트북"])
            self.assertNotEqual(first["version"], second["version"])
            self.assertEqual(request.call_count, calls)

    def test_http_contract_uses_fixed_endpoint_headers_and_date_sort(self):
        response = MagicMock()
        response.status_code = 200
        response.iter_bytes.return_value = [b'{"items":[]}']
        response.__enter__.return_value = response
        client = MagicMock()
        client.__enter__.return_value = client
        client.stream.return_value = response
        with patch("httpx.Client", return_value=client) as factory:
            self.assertEqual(naver_news._fetch("Galaxy S25", "fake-id", "fake-secret"), [])
            call = client.stream.call_args
            self.assertEqual(
                call.args, ("GET", "https://naverapihub.apigw.ntruss.com/search/v1/news")
            )
            self.assertEqual(
                call.kwargs["params"],
                {
                    "query": "Galaxy S25",
                    "display": 100,
                    "start": 1,
                    "sort": "date",
                    "format": "json",
                },
            )
            self.assertEqual(
                call.kwargs["headers"],
                {"X-NCP-APIGW-API-KEY-ID": "fake-id", "X-NCP-APIGW-API-KEY": "fake-secret"},
            )
            self.assertFalse(factory.call_args.kwargs["follow_redirects"])
            self.assertFalse(factory.call_args.kwargs["trust_env"])

    def test_gateway_errors_keep_only_safe_code_and_distinguish_forbidden(self):
        for status, payload, state, code in [
            (
                401,
                {"error": {"errorCode": "200", "details": "private-value"}},
                "AUTH_FAILED",
                "200",
            ),
            (
                403,
                {"error": {"errorCode": "403", "message": "private-value"}},
                "REQUEST_REJECTED",
                "403",
            ),
            (
                400,
                {"errorCode": "SE02", "errorMessage": "private-value"},
                "INVALID_REQUEST",
                "SE02",
            ),
            (
                300,
                {"error": {"errorCode": "300", "message": "private-value"}},
                "API_NOT_FOUND",
                "300",
            ),
            (401, {"error": {"errorCode": "private-value"}}, "AUTH_FAILED", "UNKNOWN"),
        ]:
            response = MagicMock()
            response.status_code = status
            response.__enter__.return_value = response
            response.iter_bytes.return_value = [json.dumps(payload).encode()]
            client = MagicMock()
            client.__enter__.return_value = client
            client.stream.return_value = response
            with patch("httpx.Client", return_value=client):
                with self.assertRaises(naver_news.SearchFailure) as caught:
                    naver_news._fetch("Galaxy S25", "fake-id", "fake-secret")
                self.assertEqual(caught.exception.state, state)
                self.assertEqual(caught.exception.http_status, status)
                self.assertEqual(caught.exception.code, code)
                self.assertNotIn("private-value", str(caught.exception.__dict__))
