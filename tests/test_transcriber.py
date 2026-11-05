"""
Pruebas unitarias para el módulo de transcripción y formato de marcas de tiempo SRT.
"""

import unittest
from transcriber import format_srt_timestamp

class TestTranscriber(unittest.TestCase):
    def test_format_srt_timestamp(self):
        self.assertEqual(format_srt_timestamp(0.0), "00:00:00,000")
        self.assertEqual(format_srt_timestamp(1.5), "00:00:01,500")
        self.assertEqual(format_srt_timestamp(65.123), "00:01:05,123")
        self.assertEqual(format_srt_timestamp(3600.0), "01:00:00,000")
        self.assertEqual(format_srt_timestamp(3665.456), "01:01:05,456")

if __name__ == "__main__":
    unittest.main()
