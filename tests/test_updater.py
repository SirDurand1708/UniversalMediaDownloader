"""
Pruebas unitarias para el módulo de actualización (updater.py)
y el empaquetador de versiones (package_release.py).
"""

import unittest
from updater import (
    is_newer_version,
    resolve_download_url,
    get_installation_type,
    evaluate_update_availability
)
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
        failed_url = "https://github.com/SirDurand1708/UniversalMediaDownloader/releases/download/v2.0.9/OmniVault-v2.0.9-Windows-x64-Setup.exe"
        resolved = resolve_download_url(failed_url, is_installer=True, remote_version="2.0.9")
        self.assertTrue(resolved.endswith(".exe"))

    def test_dynamic_package_version(self):
        ver = get_release_version()
        self.assertTrue(len(ver) > 0)
        self.assertFalse(ver.startswith("v"))

    def test_evaluate_update_android_only_does_not_alert_pc(self):
        # Simula release nuevo v2.1.5 lanzado SOLO para Android (APK)
        mock_data = {
            "version": "2.1.5",
            "android": {
                "version": "2.1.5",
                "version_code": 215,
                "apk_url": "https://example.com/OmniVault-v2.1.5-Android.apk"
            },
            "windows": {
                "version": "2.1.0",
                "version_code": 210,
                "setup_url": "https://example.com/OmniVault-v2.1.0-Windows-x64-Setup.exe",
                "portable_url": "https://example.com/OmniVault-v2.1.0-Windows-x64-Portable.zip"
            }
        }
        # La PC con v2.1.0 NO debe recibir alerta
        has_update, _ = evaluate_update_availability(mock_data, current_version="2.1.0")
        self.assertFalse(has_update)

    def test_evaluate_update_windows_only_alerts_pc(self):
        # Simula release v2.2.0 lanzado para Windows
        mock_data = {
            "version": "2.2.0",
            "android": {
                "version": "2.1.0",
                "version_code": 210,
                "apk_url": "https://example.com/OmniVault-v2.1.0-Android.apk"
            },
            "windows": {
                "version": "2.2.0",
                "version_code": 220,
                "setup_url": "https://example.com/OmniVault-v2.2.0-Windows-x64-Setup.exe",
                "portable_url": "https://example.com/OmniVault-v2.2.0-Windows-x64-Portable.zip"
            }
        }
        has_update, eff = evaluate_update_availability(mock_data, current_version="2.1.0")
        self.assertTrue(has_update)
        self.assertEqual(eff["version"], "2.2.0")

    def test_evaluate_update_no_windows_binaries_ignored(self):
        # Release con versión superior en Windows pero sin URLs de binarios
        mock_data = {
            "version": "2.3.0",
            "windows": {
                "version": "2.3.0",
                "setup_url": "",
                "portable_url": ""
            }
        }
        has_update, _ = evaluate_update_availability(mock_data, current_version="2.1.0")
        self.assertFalse(has_update)

    def test_evaluate_update_installed_vs_portable_target(self):
        mock_data = {
            "version": "2.2.0",
            "windows": {
                "version": "2.2.0",
                "setup_url": "https://example.com/setup.exe",
                "portable_url": "https://example.com/portable.zip"
            }
        }
        # Versión instalada debe apuntar a setup.exe
        _, eff_inst = evaluate_update_availability(mock_data, current_version="2.1.0", install_type="installed")
        self.assertEqual(eff_inst["target_url"], "https://example.com/setup.exe")

        # Versión portable debe apuntar a portable.zip
        _, eff_port = evaluate_update_availability(mock_data, current_version="2.1.0", install_type="portable")
        self.assertEqual(eff_port["target_url"], "https://example.com/portable.zip")


if __name__ == "__main__":
    unittest.main()
