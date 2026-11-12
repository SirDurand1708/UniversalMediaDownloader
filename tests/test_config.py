"""
Pruebas unitarias para ConfigManager (Gestión de configuración).
"""

import os
import tempfile
import unittest
from config_manager import ConfigManager

class TestConfigManager(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.config_path = os.path.join(self.temp_dir, "test_config.json")
        self.cfg = ConfigManager(filepath=self.config_path)

    def tearDown(self):
        if os.path.exists(self.config_path):
            try: os.remove(self.config_path)
            except Exception: pass
        try: os.rmdir(self.temp_dir)
        except Exception: pass

    def test_default_values(self):
        self.assertEqual(self.cfg.get("browser_cookies"), "none")
        self.assertEqual(self.cfg.get("default_format"), "mp3_320")
        self.assertEqual(self.cfg.get("accent_theme"), "violet")
        self.assertTrue(self.cfg.get("clipboard_watch"))
        self.assertEqual(self.cfg.get("max_download_rate"), 0)

    def test_max_download_rate_setting(self):
        self.cfg.set("max_download_rate", 5120)
        self.assertEqual(self.cfg.get("max_download_rate"), 5120)

    def test_set_and_save(self):
        self.cfg.set("accent_theme", "emerald")
        self.assertEqual(self.cfg.get("accent_theme"), "emerald")

        # Reload from disk
        cfg2 = ConfigManager(filepath=self.config_path)
        self.assertEqual(cfg2.get("accent_theme"), "emerald")

    def test_directory_creation(self):
        audio_dir = self.cfg.get_audio_dir()
        self.assertTrue(isinstance(audio_dir, str) and len(audio_dir) > 0)
        self.assertTrue(os.path.isdir(audio_dir))

if __name__ == "__main__":
    unittest.main()
