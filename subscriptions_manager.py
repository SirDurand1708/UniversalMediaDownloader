"""
Gestor de Monitoreo Automático de Creadores y Canales (Channel Auto-Sync) para OmniVault Desktop.
Permite suscribirse a canales de YouTube, perfiles de TikTok o listas de reproducción,
detectando periódicamente nuevos videos/audios y descargándolos automáticamente.
"""

import os
import json
import time
import threading
from typing import Dict, Any, List, Optional, Callable
import yt_dlp

from notifications import send_notification

SUBSCRIPTIONS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "subscriptions.json")


class SubscriptionsManager:
    def __init__(self, filepath: str = SUBSCRIPTIONS_FILE):
        self.filepath = filepath
        self._lock = threading.Lock()
        self.subscriptions: List[Dict[str, Any]] = self._load()
        self._timer_thread: Optional[threading.Thread] = None
        self._running = False
        self.on_subscriptions_changed: Optional[Callable[[], None]] = None

    def _load(self) -> List[Dict[str, Any]]:
        if not os.path.exists(self.filepath):
            return []
        try:
            with open(self.filepath, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, list):
                    return data
                return []
        except Exception:
            return []

    def _save(self):
        temp_path = f"{self.filepath}.tmp"
        try:
            with open(temp_path, "w", encoding="utf-8") as f:
                json.dump(self.subscriptions, f, indent=2, ensure_ascii=False)
            os.replace(temp_path, self.filepath)
        except Exception as e:
            if os.path.exists(temp_path):
                try: os.remove(temp_path)
                except Exception: pass
            print(f"Error guardando suscripciones: {e}")

    def get_subscriptions(self) -> List[Dict[str, Any]]:
        with self._lock:
            return list(self.subscriptions)

    def add_subscription(
        self,
        url: str,
        name: str = "",
        format_type: str = "mp3_320",
        auto_download: bool = True
    ) -> Dict[str, Any]:
        """Añade un nuevo canal o perfil para monitoreo constante."""
        url = url.strip()
        with self._lock:
            for s in self.subscriptions:
                if s.get("url", "").lower() == url.lower():
                    raise ValueError("Este canal o enlace ya está registrado en tus suscripciones.")

            sub_id = f"sub_{int(time.time())}_{len(self.subscriptions)}"
            record = {
                "id": sub_id,
                "url": url,
                "name": name or url,
                "format_type": format_type,
                "auto_download": auto_download,
                "created_at": time.time(),
                "last_checked": 0,
                "last_checked_str": "Nunca",
                "downloaded_count": 0,
                "seen_ids": []
            }
            self.subscriptions.append(record)
            self._save()

        if self.on_subscriptions_changed:
            try: self.on_subscriptions_changed()
            except Exception: pass
        return record

    def remove_subscription(self, sub_id: str) -> bool:
        with self._lock:
            orig_len = len(self.subscriptions)
            self.subscriptions = [s for s in self.subscriptions if s.get("id") != sub_id]
            if len(self.subscriptions) != orig_len:
                self._save()
                if self.on_subscriptions_changed:
                    try: self.on_subscriptions_changed()
                    except Exception: pass
                return True
        return False

    def update_subscription(self, sub_id: str, **kwargs) -> bool:
        with self._lock:
            for s in self.subscriptions:
                if s.get("id") == sub_id:
                    s.update(kwargs)
                    self._save()
                    if self.on_subscriptions_changed:
                        try: self.on_subscriptions_changed()
                        except Exception: pass
                    return True
        return False

    def check_channel(
        self,
        sub: Dict[str, Any],
        queue_manager=None,
        get_target_dir_func=None
    ) -> List[Dict[str, Any]]:
        """
        Consulta las últimas 5 publicaciones del canal.
        Si hay elementos nuevos no vistos y auto_download está activo, los encola automáticamente.
        """
        url = sub.get("url")
        if not url:
            return []

        ydl_opts = {
            'extract_flat': True,
            'skip_download': True,
            'playlistend': 5,
            'quiet': True,
            'no_warnings': True,
        }

        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(url, download=False)
        except Exception as e:
            print(f"Error comprobando canal {url}: {e}")
            return []

        if not info:
            return []

        channel_title = info.get("uploader") or info.get("channel") or info.get("title") or sub.get("name")
        entries = info.get("entries", [])
        if not entries and "id" in info:
            entries = [info]

        seen_ids = set(sub.get("seen_ids", []))
        new_items = []

        is_first_sync = len(seen_ids) == 0

        for e in entries:
            if not e:
                continue
            v_id = str(e.get("id", ""))
            if not v_id:
                continue

            item_url = e.get("url")
            if not item_url or not str(item_url).startswith("http"):
                item_url = f"https://www.youtube.com/watch?v={v_id}"

            item_title = e.get("title", f"Video {v_id}")

            if v_id not in seen_ids:
                seen_ids.add(v_id)
                # En la primera sincronización, marcamos como vistos sin descargar en masa para evitar saturación
                if not is_first_sync:
                    new_items.append({
                        "id": v_id,
                        "title": item_title,
                        "url": item_url,
                        "uploader": channel_title
                    })

        # Actualizar datos de la suscripción
        now_str = time.strftime("%Y-%m-%d %H:%M")
        with self._lock:
            for s in self.subscriptions:
                if s.get("id") == sub.get("id"):
                    s["seen_ids"] = list(seen_ids)
                    s["last_checked"] = time.time()
                    s["last_checked_str"] = now_str
                    if channel_title and s.get("name") == s.get("url"):
                        s["name"] = channel_title
                    if new_items:
                        s["downloaded_count"] = s.get("downloaded_count", 0) + len(new_items)
                    self._save()
                    break

        # Si hay elementos nuevos y auto_download está habilitado, encolar en el gestor de descargas
        if new_items and sub.get("auto_download", True) and queue_manager:
            fmt = sub.get("format_type", "mp3_320")
            target_dir = get_target_dir_func(fmt) if get_target_dir_func else None

            for it in new_items:
                try:
                    queue_manager.add_download(
                        url=it["url"],
                        format_type=fmt,
                        output_dir=target_dir or os.path.expanduser("~/Downloads"),
                        title=it["title"]
                    )
                except Exception as ex:
                    print(f"Error encolando nuevo video de canal: {ex}")

            try:
                first_title = new_items[0]["title"][:40]
                send_notification(
                    "✦ OmniVault Auto-Sync",
                    f"¡Nuevo video de {channel_title}!\n{first_title} (Descargando...)"
                )
            except Exception:
                pass

        if self.on_subscriptions_changed:
            try: self.on_subscriptions_changed()
            except Exception: pass

        return new_items

    def check_all(self, queue_manager=None, get_target_dir_func=None) -> int:
        """Comprueba todas las suscripciones registradas y devuelve la cantidad total de nuevos elementos."""
        subs = self.get_subscriptions()
        total_new = 0
        for s in subs:
            try:
                new_items = self.check_channel(s, queue_manager=queue_manager, get_target_dir_func=get_target_dir_func)
                total_new += len(new_items)
            except Exception as e:
                print(f"Error en sync de {s.get('name')}: {e}")
        return total_new

    def start_background_sync(
        self,
        interval_seconds: int = 1800,
        queue_manager=None,
        get_target_dir_func=None
    ):
        """Inicia el hilo de sincronización en segundo plano."""
        if self._running:
            return
        self._running = True

        def loop():
            # Espera inicial de 15 segundos tras iniciar la app antes del primer escaneo
            time.sleep(15)
            while self._running:
                try:
                    self.check_all(queue_manager=queue_manager, get_target_dir_func=get_target_dir_func)
                except Exception as e:
                    print(f"Error en bucle de auto-sync de canales: {e}")

                # Esperar intervalo verificando _running periódicamente
                elapsed = 0
                while self._running and elapsed < interval_seconds:
                    time.sleep(2)
                    elapsed += 2

        self._timer_thread = threading.Thread(target=loop, daemon=True, name="OmniVault-ChannelSync")
        self._timer_thread.start()

    def stop_background_sync(self):
        self._running = False
