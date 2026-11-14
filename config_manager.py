"""
Gestor de configuración y preferencias de usuario para OmniVault Desktop.
Manejo seguro, atómico y con soporte para concurrencia.
"""

import os
import sys
import json
import threading
from typing import Dict, Any

def get_app_dir() -> str:
    if getattr(sys, 'frozen', False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))

def get_data_dir() -> str:
    """
    Devuelve el directorio donde se almacenan los datos mutables de la aplicación.
    - En modo instalado (Inno Setup / Program Files / AppData\\Local\\Programs): %LOCALAPPDATA%\\OmniVault
    - En modo portable o desarrollo: junto al ejecutable/código (get_app_dir())
    """
    try:
        import updater
        if updater.get_installation_type() == "installed":
            local_app_data = os.environ.get("LOCALAPPDATA") or os.path.expanduser(r"~\AppData\Local")
            data_dir = os.path.join(local_app_data, "OmniVault")
            os.makedirs(data_dir, exist_ok=True)
            return data_dir
    except Exception:
        pass
    app_dir = get_app_dir()
    try:
        os.makedirs(app_dir, exist_ok=True)
    except Exception:
        pass
    return app_dir

CONFIG_FILE = os.path.join(get_data_dir(), "config.json")

class ConfigManager:
    def __init__(self, filepath: str = CONFIG_FILE):
        self.filepath = filepath
        self._lock = threading.Lock()
        self.settings: Dict[str, Any] = self._load()

    def _default_settings(self) -> Dict[str, Any]:
        user_home = os.path.expanduser("~")
        
        # Carpetas predeterminadas especializadas solicitadas por el usuario
        music_base = os.path.join(user_home, "Music")
        videos_base = os.path.join(user_home, "Videos")
        downloads_base = os.path.join(user_home, "Downloads")

        default_audio = os.path.join(music_base, "OmniVault Audios") if os.path.exists(music_base) else os.path.join(downloads_base, "OmniVault Audios")
        default_video = os.path.join(videos_base, "OmniVault Videos") if os.path.exists(videos_base) else os.path.join(downloads_base, "OmniVault Videos")
        default_dl = downloads_base if os.path.exists(downloads_base) else user_home

        return {
            "audio_download_dir": default_audio,
            "video_download_dir": default_video,
            "download_dir": default_dl,
            "browser_cookies": "none",
            "default_format": "mp3_320",
            "max_workers": 2,
            "clipboard_watch": True,
            "notifications_enabled": True,
            "subtitles_enabled": False,
            "normalize_audio": False,
            "aria2c_enabled": True,
            "accent_theme": "violet",
            "tray_enabled": True,
            "close_action": "minimize",
            "tray_notified_on_close": False,
            "on_finish_action": "none",
            "max_download_rate": 0,
            "sync_channels_auto": True,
            "sync_channels_interval_min": 30,
            "floating_hud_enabled": False,
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
            return defaults

    def get_audio_dir(self) -> str:
        with self._lock:
            d = self.settings.get("audio_download_dir")
        if not d or not isinstance(d, str):
            d = self._default_settings()["audio_download_dir"]
        try:
            os.makedirs(d, exist_ok=True)
        except Exception:
            pass
        return d

    def get_video_dir(self) -> str:
        with self._lock:
            d = self.settings.get("video_download_dir")
        if not d or not isinstance(d, str):
            d = self._default_settings()["video_download_dir"]
        try:
            os.makedirs(d, exist_ok=True)
        except Exception:
            pass
        return d

    def save(self):
        """Guarda la configuración de forma atómica y segura entre hilos."""
        with self._lock:
            temp_path = f"{self.filepath}.tmp"
            try:
                parent_dir = os.path.dirname(os.path.abspath(self.filepath))
                if parent_dir:
                    os.makedirs(parent_dir, exist_ok=True)
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
