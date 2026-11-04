"""
Gestor de historial y biblioteca local para Universal Media Downloader.
Manejo seguro, atómico y protegido contra inyecciones y ejecuciones no autorizadas.
"""

import json
import os
import sys
import subprocess
import threading
from datetime import datetime
from typing import List, Dict, Any, Optional

def get_app_dir() -> str:
    if getattr(sys, 'frozen', False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))

HISTORY_FILE = os.path.join(get_app_dir(), "library.json")

# Lista blanca estricta de extensiones multimedia permitidas para reproducción
ALLOWED_MEDIA_EXTENSIONS = {
    ".mp3", ".mp4", ".m4a", ".wav", ".webm", ".mkv",
    ".flac", ".ogg", ".opus", ".aac", ".avi", ".mov"
}

class HistoryManager:
    def __init__(self, filepath: str = HISTORY_FILE):
        self.filepath = filepath
        self._lock = threading.Lock()
        self.items: List[Dict[str, Any]] = self._load()

    def _load(self) -> List[Dict[str, Any]]:
        if not os.path.exists(self.filepath):
            return []
        try:
            with open(self.filepath, "r", encoding="utf-8") as f:
                data = json.load(f)
                return data if isinstance(data, list) else []
        except Exception:
            return []

    def _save(self):
        """Guarda la biblioteca atómicamente para prevenir corrupción de datos."""
        temp_path = f"{self.filepath}.tmp"
        try:
            with open(temp_path, "w", encoding="utf-8") as f:
                json.dump(self.items, f, indent=2, ensure_ascii=False)
            os.replace(temp_path, self.filepath)
        except Exception as e:
            if os.path.exists(temp_path):
                try:
                    os.remove(temp_path)
                except Exception:
                    pass
            print(f"Error guardando biblioteca: {e}")

    def add_download(
        self,
        title: str,
        file_path: str,
        format_label: str,
        media_type: str,
        url: str = "",
        thumbnail_url: str = ""
    ) -> Dict[str, Any]:
        """Añade una descarga completada a la biblioteca de manera segura entre hilos."""
        file_size = 0
        if file_path and os.path.isfile(file_path):
            try:
                file_size = os.path.getsize(file_path)
            except OSError:
                file_size = 0

        entry = {
            "id": datetime.now().strftime("%Y%m%d%H%M%S%f"),
            "title": str(title or "Sin título"),
            "file_path": str(file_path or ""),
            "format_label": str(format_label or ""),
            "media_type": str(media_type or "audio"),
            "url": str(url or ""),
            "thumbnail_url": str(thumbnail_url or ""),
            "file_size": file_size,
            "date": datetime.now().strftime("%d/%m/%Y %H:%M"),
        }

        with self._lock:
            self.items.insert(0, entry)
            self._save()
        return entry

    def get_all(self) -> List[Dict[str, Any]]:
        """Devuelve todos los elementos, verificando si el archivo aún existe en disco."""
        with self._lock:
            valid_items = []
            for item in self.items:
                path = item.get("file_path", "")
                item["exists_on_disk"] = bool(path and os.path.exists(path))
                valid_items.append(dict(item))
            return valid_items

    def delete_item(self, item_id: str, delete_file: bool = False):
        """Elimina un elemento del historial y opcionalmente el archivo físico."""
        with self._lock:
            target = None
            for item in self.items:
                if item.get("id") == item_id:
                    target = item
                    break
            if target:
                if delete_file:
                    fp = target.get("file_path", "")
                    if fp and os.path.isfile(fp):
                        try:
                            os.remove(fp)
                        except OSError:
                            pass
                self.items.remove(target)
                self._save()

    def clear_all(self):
        """Limpia el historial de la biblioteca."""
        with self._lock:
            self.items = []
            self._save()

    @staticmethod
    def play_media(file_path: str) -> bool:
        """
        Abre el archivo de forma segura con el reproductor multimedia predeterminado.
        Valida que el archivo exista y sea un formato multimedia permitido (evita ejecución arbitraria).
        """
        if not file_path or not os.path.isfile(file_path):
            return False

        ext = os.path.splitext(file_path)[1].lower()
        if ext not in ALLOWED_MEDIA_EXTENSIONS:
            print(f"Bloqueada ejecución de archivo con extensión no autorizada: {ext}")
            return False

        try:
            if sys.platform == "win32":
                os.startfile(file_path)
            elif sys.platform == "darwin":
                subprocess.Popen(["open", file_path])
            else:
                subprocess.Popen(["xdg-open", file_path])
            return True
        except Exception as e:
            print(f"Error reproduciendo archivo: {e}")
            return False

    @staticmethod
    def reveal_in_folder(file_path: str):
        """Abre la carpeta contenedora y selecciona el archivo de forma segura y multiplataforma."""
        if not file_path:
            return

        norm_path = os.path.normpath(file_path)
        folder = os.path.dirname(norm_path) if os.path.isfile(norm_path) else norm_path

        try:
            if sys.platform == "win32":
                if os.path.isfile(norm_path):
                    # Uso seguro sin concatenar strings en shell
                    subprocess.Popen(["explorer.exe", f"/select,{norm_path}"])
                elif os.path.isdir(folder):
                    os.startfile(folder)
            elif sys.platform == "darwin":
                if os.path.isfile(norm_path):
                    subprocess.Popen(["open", "-R", norm_path])
                elif os.path.isdir(folder):
                    subprocess.Popen(["open", folder])
            else:
                if os.path.isdir(folder):
                    subprocess.Popen(["xdg-open", folder])
        except Exception as e:
            print(f"Error revelando archivo en carpeta: {e}")
