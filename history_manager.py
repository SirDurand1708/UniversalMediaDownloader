"""
Gestor de historial y biblioteca local para Snaptube Desktop.
Guarda y administra las descargas completadas en un archivo JSON local.
"""

import json
import os
import sys
import subprocess
from datetime import datetime
from typing import List, Dict, Any, Optional

HISTORY_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "library.json")

class HistoryManager:
    def __init__(self, filepath: str = HISTORY_FILE):
        self.filepath = filepath
        self.items: List[Dict[str, Any]] = self._load()

    def _load(self) -> List[Dict[str, Any]]:
        if not os.path.exists(self.filepath):
            return []
        try:
            with open(self.filepath, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return []

    def _save(self):
        try:
            with open(self.filepath, "w", encoding="utf-8") as f:
                json.dump(self.items, f, indent=2, ensure_ascii=False)
        except Exception as e:
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
        """Añade una descarga completada a la biblioteca."""
        file_size = 0
        if os.path.exists(file_path):
            file_size = os.path.getsize(file_path)

        entry = {
            "id": datetime.now().strftime("%Y%m%d%H%M%S%f"),
            "title": title,
            "file_path": file_path,
            "format_label": format_label,
            "media_type": media_type,
            "url": url,
            "thumbnail_url": thumbnail_url,
            "file_size": file_size,
            "date": datetime.now().strftime("%d/%m/%Y %H:%M"),
        }
        # Insertar al inicio (más reciente primero)
        self.items.insert(0, entry)
        self._save()
        return entry

    def get_all(self) -> List[Dict[str, Any]]:
        """Devuelve todos los elementos, verificando si el archivo aún existe en disco."""
        valid_items = []
        for item in self.items:
            # Marcamos si el archivo existe
            item["exists_on_disk"] = os.path.exists(item.get("file_path", ""))
            valid_items.append(item)
        return valid_items

    def delete_item(self, item_id: str, delete_file: bool = False):
        """Elimina un elemento del historial y opcionalmente el archivo físico."""
        target = None
        for item in self.items:
            if item.get("id") == item_id:
                target = item
                break
        if target:
            if delete_file and os.path.exists(target.get("file_path", "")):
                try:
                    os.remove(target["file_path"])
                except Exception:
                    pass
            self.items.remove(target)
            self._save()

    def clear_all(self):
        """Limpia el historial de la biblioteca."""
        self.items = []
        self._save()

    @staticmethod
    def play_media(file_path: str):
        """Abre el archivo con el reproductor multimedia predeterminado del sistema."""
        if not os.path.exists(file_path):
            return False
        if sys.platform == "win32":
            os.startfile(file_path)
        elif sys.platform == "darwin":
            subprocess.Popen(["open", file_path])
        else:
            subprocess.Popen(["xdg-open", file_path])
        return True

    @staticmethod
    def reveal_in_folder(file_path: str):
        """Abre la carpeta que contiene el archivo y lo resalta en el explorador de Windows."""
        if sys.platform == "win32":
            if os.path.exists(file_path):
                subprocess.Popen(f'explorer /select,"{os.path.normpath(file_path)}"')
            else:
                folder = os.path.dirname(file_path)
                if os.path.exists(folder):
                    os.startfile(folder)
        else:
            folder = os.path.dirname(file_path)
            if os.path.exists(folder):
                subprocess.Popen(["xdg-open", folder])
