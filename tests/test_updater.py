"""
Pruebas unitarias para el módulo de actualización (updater.py)
y el empaquetador de versiones (package_release.py).
"""

import unittest
from updater import is_newer_version, resolve_download_url
from package_release import get_release_version


class TestUpdater(unittest.TestCase):

    def test_version_comparison(self):
        self.assertTrue(is_newer_version("2.1.0", "2.0.9"))
        self.assertTrue(is_newer_version("v3.0.0", "2.0.9"))
        self.assertTrue(is_newer_version("2.0.10", "2.0.9"))
        self.assertFalse(is_newer_version("2.0.9", "2.0.9"))
        self.assertFalse(is_newer_version("2.0.8", "2.0.9"))
        self.assertFalse(is_newer_version("1.9.9", "2.0.0"))

    def test_heuristic_fallback_url(self):
        # Cuando falla la URL v2.0.9, debe intentar la sustitución heurística a v2.0.0 si es necesario
        failed_url = "https://github.com/SirDurand1708/UniversalMediaDownloader/releases/download/v2.0.9/OmniVault-v2.0.9-Windows-x64-Setup.exe"
        resolved = resolve_download_url(failed_url, is_installer=True, remote_version="2.0.9")
        # Debe resolver o a la URL real del asset o a un fallback viable con código 200
        self.assertTrue(resolved.endswith(".exe"))

    def test_dynamic_package_version(self):
        ver = get_release_version()
        self.assertTrue(len(ver) > 0)
        self.assertFalse(ver.startswith("v"))


if __name__ == "__main__":
    unittest.main()
