"""
Pruebas unitarias para HistoryManager (Gestión de biblioteca e historial).
"""

import os
import tempfile
import unittest
from history_manager import HistoryManager, ALLOWED_MEDIA_EXTENSIONS

class TestHistoryManager(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.history_path = os.path.join(self.temp_dir, "test_library.json")
        self.hist = HistoryManager(filepath=self.history_path)

    def tearDown(self):
        if os.path.exists(self.history_path):
            try: os.remove(self.history_path)
            except Exception: pass
        try: os.rmdir(self.temp_dir)
        except Exception: pass

    def test_add_and_retrieve(self):
        fake_file = os.path.join(self.temp_dir, "song.mp3")
        with open(fake_file, "w") as f:
            f.write("audio content")

        entry = self.hist.add_download(
            title="Mi Canción",
            file_path=fake_file,
            format_label="MP3 320k",
            media_type="audio",
            url="https://youtube.com/watch?v=123"
        )
        self.assertEqual(entry["title"], "Mi Canción")
        self.assertEqual(entry["format_label"], "MP3 320k")

        all_items = self.hist.get_all()
        self.assertEqual(len(all_items), 1)
        self.assertTrue(all_items[0]["exists_on_disk"])

    def test_extension_whitelist(self):
        self.assertIn(".mp3", ALLOWED_MEDIA_EXTENSIONS)
        self.assertIn(".mp4", ALLOWED_MEDIA_EXTENSIONS)
        self.assertIn(".wav", ALLOWED_MEDIA_EXTENSIONS)
        self.assertNotIn(".exe", ALLOWED_MEDIA_EXTENSIONS)
        self.assertNotIn(".bat", ALLOWED_MEDIA_EXTENSIONS)

if __name__ == "__main__":
    unittest.main()
