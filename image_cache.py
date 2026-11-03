"""
Gestor de descarga y caché de miniaturas en memoria para CustomTkinter.
Implementa validación estricta de protocolos (SSRF), límites de memoria (DoS),
pool de hilos controlado y desalojo de caché (LRU).
"""

import io
import urllib.request
import urllib.parse
import threading
from concurrent.futures import ThreadPoolExecutor
from collections import OrderedDict
from typing import Dict, Optional, Callable, Tuple
from PIL import Image
import customtkinter as ctk

MAX_IMAGE_BYTES = 5 * 1024 * 1024  # Máximo 5 MB por imagen
MAX_CACHE_ENTRIES = 200            # Límite de elementos en memoria
ALLOWED_SCHEMES = {"http", "https"}

class ThumbnailCache:
    def __init__(self, max_workers: int = 4):
        self._cache: OrderedDict[str, ctk.CTkImage] = OrderedDict()
        self._pending: Dict[str, bool] = {}
        self._lock = threading.Lock()
        self._executor = ThreadPoolExecutor(max_workers=max_workers, thread_name_prefix="ThumbFetcher")

    def get_thumbnail(
        self,
        url: Optional[str],
        size: Tuple[int, int] = (160, 90),
        callback: Optional[Callable[[ctk.CTkImage], None]] = None
    ) -> Optional[ctk.CTkImage]:
        if not url or not isinstance(url, str):
            return None

        # Validación estricta de esquema para mitigar SSRF y acceso a archivos locales (file://)
        try:
            parsed = urllib.parse.urlparse(url)
            if parsed.scheme.lower() not in ALLOWED_SCHEMES:
                return None
        except Exception:
            return None

        key = f"{url}_{size[0]}x{size[1]}"

        with self._lock:
            if key in self._cache:
                self._cache.move_to_end(key)
                return self._cache[key]

            if key in self._pending:
                return None

            self._pending[key] = True

        def _fetch():
            ctk_img = None
            try:
                headers = {
                    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
                }
                req = urllib.request.Request(url, headers=headers)
                with urllib.request.urlopen(req, timeout=8) as response:
                    raw_data = response.read(MAX_IMAGE_BYTES + 1)
                    if len(raw_data) > MAX_IMAGE_BYTES:
                        # Excede el tamaño seguro para una miniatura
                        return

                with Image.open(io.BytesIO(raw_data)) as pil_img:
                    pil_img = pil_img.convert("RGB")
                    pil_img = pil_img.resize(size, Image.Resampling.LANCZOS)
                    ctk_img = ctk.CTkImage(light_image=pil_img, dark_image=pil_img, size=size)

                with self._lock:
                    self._cache[key] = ctk_img
                    if len(self._cache) > MAX_CACHE_ENTRIES:
                        self._cache.popitem(last=False)  # Expulsar el más antiguo

                if callback and ctk_img:
                    callback(ctk_img)

            except Exception:
                pass
            finally:
                with self._lock:
                    self._pending.pop(key, None)

        self._executor.submit(_fetch)
        return None

    def shutdown(self, wait: bool = False):
        """Cierra el pool de hilos de forma ordenada."""
        try:
            self._executor.shutdown(wait=wait, cancel_futures=True)
        except Exception:
            pass
