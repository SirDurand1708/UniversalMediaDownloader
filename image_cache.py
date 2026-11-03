"""
Gestor de descarga y caché de miniaturas en memoria para CustomTkinter.
"""

import io
import urllib.request
import threading
from typing import Dict, Optional, Callable
from PIL import Image
import customtkinter as ctk

class ThumbnailCache:
    def __init__(self):
        self._cache: Dict[str, ctk.CTkImage] = {}
        self._pending: Dict[str, bool] = {}

    def get_thumbnail(
        self,
        url: Optional[str],
        size: tuple = (160, 90),
        callback: Optional[Callable[[ctk.CTkImage], None]] = None
    ) -> Optional[ctk.CTkImage]:
        if not url:
            return None

        key = f"{url}_{size[0]}x{size[1]}"
        if key in self._cache:
            return self._cache[key]

        if key in self._pending:
            return None

        self._pending[key] = True

        def _fetch():
            try:
                headers = {'User-Agent': 'Mozilla/5.0'}
                req = urllib.request.Request(url, headers=headers)
                with urllib.request.urlopen(req, timeout=5) as response:
                    raw_data = response.read()
                pil_img = Image.open(io.BytesIO(raw_data)).convert("RGB")
                pil_img = pil_img.resize(size, Image.Resampling.LANCZOS)
                ctk_img = ctk.CTkImage(light_image=pil_img, dark_image=pil_img, size=size)
                self._cache[key] = ctk_img
                if callback:
                    callback(ctk_img)
            except Exception:
                pass
            finally:
                self._pending.pop(key, None)

        threading.Thread(target=_fetch, daemon=True).start()
        return None
