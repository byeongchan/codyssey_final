import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from app.rss_settings import rss_settings


class RssSettingsTest(unittest.TestCase):
    def test_local_rss_overrides_stale_shell_without_exporting_other_values(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text('RSS_FEEDS_JSON=\'[{"name":"test","url":"https://www.yna.co.kr/rss/news.xml"}]\'\nRSS_ALLOWED_HOSTS=www.yna.co.kr,yna.co.kr\nUNRELATED_TEST_KEY=ignored\n', encoding="utf-8")
            with patch("app.rss_settings.RSS_ENV_FILE", path), patch.dict(os.environ, {"RSS_FEEDS_JSON": "[]", "RSS_ALLOWED_HOSTS": ""}):
                feeds, hosts = rss_settings()
                self.assertIn("news.xml", feeds)
                self.assertIn("www.yna.co.kr", hosts)
                self.assertNotIn("UNRELATED_TEST_KEY", os.environ)

    def test_explicit_local_empty_list_disables_collection(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text("RSS_FEEDS_JSON=[]\n", encoding="utf-8")
            with patch("app.rss_settings.RSS_ENV_FILE", path):
                self.assertEqual(rss_settings()[0], "[]")

    def test_absent_configuration_uses_selected_yonhap_feed(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch("app.rss_settings.RSS_ENV_FILE", Path(directory) / "missing"), patch.dict(os.environ, {}, clear=True):
                feeds, hosts = rss_settings()
                self.assertIn("https://www.yna.co.kr/rss/news.xml", feeds)
                self.assertIn("www.yna.co.kr", hosts)
