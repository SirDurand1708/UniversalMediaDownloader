"""
Gestor de configuración y preferencias de usuario para Snaptube Desktop.
"""

import os
import json
from typing import Dict, Any, Optional

CONFIG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")

class ConfigManager:
    def __init__(self, filepath: str = CONFIG_FILE):
        self.filepath = filepath
        self.settings: Dict[str, Any] = self._load()

    def _default_settings(self) -> Dict[str, Any]:
        default_dl = os.path.join(os.path.expanduser("~"), "Downloads")
        return {
            "download_dir": default_dl if os.path.exists(default_dl) else os.getcwd(),
            "browser_cookies": "none",  # 'none', 'chrome', 'edge', 'firefox', 'brave', 'opera'
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
                defaults.update(data)
                return defaults
        except Exception:
            return defaults

    def save(self):
        try:
            with open(self.filepath, "w", encoding="utf-8") as f:
                json.dump(self.settings, f, indent=2, ensure_ascii=False)
        except Exception as e:
            print(f"Error guardando configuración: {e}")

    def get(self, key: str, default: Any = None) -> Any:
        return self.settings.get(key, default)

    def set(self, key: str, value: Any):
        self.settings[key] = value
        self.save()
