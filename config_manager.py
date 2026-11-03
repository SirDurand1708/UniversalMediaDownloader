"""
Gestor de configuración y preferencias de usuario para Universal Media Downloader.
Manejo seguro, atómico y con soporte para concurrencia.
"""

import os
import json
import threading
from typing import Dict, Any

CONFIG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")

class ConfigManager:
    def __init__(self, filepath: str = CONFIG_FILE):
        self.filepath = filepath
        self._lock = threading.Lock()
        self.settings: Dict[str, Any] = self._load()

    def _default_settings(self) -> Dict[str, Any]:
        default_dl = os.path.join(os.path.expanduser("~"), "Downloads")
        if not os.path.exists(default_dl):
            try:
                os.makedirs(default_dl, exist_ok=True)
            except Exception:
                default_dl = os.getcwd()

        return {
            "download_dir": default_dl,
            "browser_cookies": "none",  # 'none', 'chrome', 'edge', 'firefox', 'brave', 'opera', 'vivaldi'
            "default_format": "mp3_320",
            "max_workers": 2,
        }

    def _load(self) -> Dict[str, Any]:
        defaults = self._default_settings()
        if not os.path.exists(self.filepath):
            return defaults
        try:
            with open(self.filepath, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, dict):
                    defaults.update(data)
                return defaults
        except Exception:
            # Si el archivo está corrupto, mantenemos defaults
            return defaults

    def save(self):
        """Guarda la configuración de forma atómica y segura entre hilos."""
        with self._lock:
            temp_path = f"{self.filepath}.tmp"
            try:
                with open(temp_path, "w", encoding="utf-8") as f:
                    json.dump(self.settings, f, indent=2, ensure_ascii=False)
                os.replace(temp_path, self.filepath)
            except Exception as e:
                if os.path.exists(temp_path):
                    try:
                        os.remove(temp_path)
                    except Exception:
                        pass
                print(f"Error guardando configuración: {e}")

    def get(self, key: str, default: Any = None) -> Any:
        with self._lock:
            return self.settings.get(key, default)

    def set(self, key: str, value: Any):
        with self._lock:
            self.settings[key] = value
        self.save()
