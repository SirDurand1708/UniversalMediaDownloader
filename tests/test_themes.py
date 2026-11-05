"""
Pruebas unitarias para el sistema de temas y paletas visuales.
"""

import unittest
from themes import get_theme, get_available_themes, THEMES, DEFAULT_THEME

class TestThemes(unittest.TestCase):
    def test_available_themes(self):
        themes = get_available_themes()
        self.assertIn("violet", themes)
        self.assertIn("blue", themes)
        self.assertIn("emerald", themes)
        self.assertIn("orange", themes)
        self.assertIn("rose", themes)

    def test_get_theme_valid(self):
        t = get_theme("violet")
        self.assertEqual(t["id"], "violet")
        self.assertIn("primary", t)
        self.assertIn("hover", t)
        self.assertIn("badge", t)
        self.assertIn("gradient", t)

    def test_get_theme_fallback(self):
        t = get_theme("inexistente_color")
        self.assertEqual(t["id"], DEFAULT_THEME)

if __name__ == "__main__":
    unittest.main()
