"""
Pruebas unitarias para funciones auxiliares de DownloaderEngine.
"""

import unittest
from downloader import (
    is_url, normalize_url, format_duration, parse_timestamp_str, clean_error_text
)

class TestDownloaderHelpers(unittest.TestCase):
    def test_is_url(self):
        self.assertTrue(is_url("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        self.assertTrue(is_url("http://soundcloud.com/artist/track"))
        self.assertTrue(is_url("https://tiktok.com/@user/video/123"))
        self.assertFalse(is_url("hola mundo"))
        self.assertFalse(is_url("C:\\Archivos\\cancion.mp3"))
        self.assertFalse(is_url(""))

    def test_normalize_url(self):
        # normalize_url añade https:// si comienza con www.
        self.assertEqual(normalize_url("www.youtube.com/watch?v=123"), "https://www.youtube.com/watch?v=123")
        self.assertEqual(normalize_url("https://youtu.be/123"), "https://youtu.be/123")

    def test_format_duration(self):
        self.assertEqual(format_duration(None), "--:--")
        self.assertEqual(format_duration(0), "--:--")
        self.assertEqual(format_duration(65), "01:05")
        self.assertEqual(format_duration(3665), "01:01:05")

    def test_parse_timestamp_str(self):
        self.assertEqual(parse_timestamp_str("01:15"), 75.0)
        self.assertEqual(parse_timestamp_str("00:01:15"), 75.0)
        self.assertEqual(parse_timestamp_str("75"), 75.0)
        self.assertEqual(parse_timestamp_str("01:00:00"), 3600.0)
        self.assertIsNone(parse_timestamp_str(""))
        self.assertIsNone(parse_timestamp_str("invalido"))

    def test_clean_error_text(self):
        dirty = "\x1b[31mError fatal:\x1b[0m Video no disponible"
        cleaned = clean_error_text(dirty)
        self.assertNotIn("\x1b", cleaned)
        self.assertIn("Video no disponible", cleaned)

if __name__ == "__main__":
    unittest.main()
