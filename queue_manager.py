"""
Gestor de cola de descargas asíncronas para OmniVault Desktop.
Manejo concurrente, seguro entre hilos, con soporte para reintentos, cancelación y trabajadores dinámicos.
"""

import threading
import queue
import time
import uuid
from typing import Dict, Any, List, Callable, Optional
from downloader import DownloaderEngine, clean_error_text
from history_manager import HistoryManager
from notifications import send_notification

class DownloadTask:
    def __init__(
        self,
        task_id: str,
        url: str,
        format_type: str,
        output_dir: str,
        title: str = "",
        thumbnail_url: str = "",
        browser: Optional[str] = None,
        download_subtitles: bool = False,
        use_aria2c: bool = True,
        clip_start: Optional[str] = None,
        clip_end: Optional[str] = None,
        normalize_audio: bool = False,
    ):
        self.id = task_id
        self.url = url
        self.format_type = format_type
        self.output_dir = output_dir
        self.title = title or "Analizando..."
        self.thumbnail_url = thumbnail_url
        self.browser = browser
        self.download_subtitles = download_subtitles
        self.use_aria2c = use_aria2c
        self.clip_start = clip_start
        self.clip_end = clip_end
        self.normalize_audio = normalize_audio
        self.format_label = format_type
        self.media_type = "audio" if format_type in ["mp3_320", "mp3_192", "m4a_best", "wav"] else "video"

        # Estado
        self.status = "queued"  # queued, downloading, converting, completed, error, cancelled
        self.percent = 0.0
        self.speed = "--"
        self.eta = "--"
        self.status_text = "En cola de espera..."
        self.file_path = ""
        self.error_msg = ""

class QueueManager:
    def __init__(self, downloader: DownloaderEngine, history: HistoryManager, max_workers: int = 2):
        self.downloader = downloader
        self.history = history
        self.max_workers = max(1, min(max_workers, 6))

        self.tasks: Dict[str, DownloadTask] = {}
        self.task_queue: queue.Queue = queue.Queue()
        self.active_workers: List[threading.Thread] = []
        self._running = True
        self._is_paused = False
        self._pause_event = threading.Event()
        self._pause_event.set()
        self._lock = threading.Lock()

        self.on_task_updated: Optional[Callable[[DownloadTask], None]] = None
        self.on_task_finished: Optional[Callable[[DownloadTask], None]] = None
        self.on_queue_completed: Optional[Callable[[], None]] = None

        for idx in range(self.max_workers):
            t = threading.Thread(target=self._worker_loop, daemon=True, name=f"DLWorker-{idx+1}")
            t.start()
            self.active_workers.append(t)

    def set_max_workers(self, count: int):
        """Ajusta dinámicamente el número de hilos de descarga."""
        with self._lock:
            target = max(1, min(int(count), 6))
            self.max_workers = target
            alive = [w for w in self.active_workers if w.is_alive()]
            current_alive = len(alive)
            if target > current_alive and self._running:
                for idx in range(current_alive, target):
                    t = threading.Thread(target=self._worker_loop, daemon=True, name=f"DLWorker-{idx+1}")
                    t.start()
                    self.active_workers.append(t)

    def add_download(
        self,
        url: str,
        format_type: str,
        output_dir: str,
        title: str = "",
        thumbnail_url: str = "",
        browser: Optional[str] = None,
        download_subtitles: bool = False,
        use_aria2c: bool = True,
        clip_start: Optional[str] = None,
        clip_end: Optional[str] = None,
        normalize_audio: bool = False,
    ) -> DownloadTask:
        task_id = f"task_{int(time.time())}_{uuid.uuid4().hex[:6]}"
        task = DownloadTask(
            task_id,
            url,
            format_type,
            output_dir,
            title,
            thumbnail_url,
            browser=browser,
            download_subtitles=download_subtitles,
            use_aria2c=use_aria2c,
            clip_start=clip_start,
            clip_end=clip_end,
            normalize_audio=normalize_audio,
        )

        with self._lock:
            self.tasks[task_id] = task

        self.task_queue.put(task)
        self._notify_update(task)
        return task

    def add_batch(
        self,
        items: List[Dict[str, Any]],
        format_type: str,
        output_dir: str,
        browser: Optional[str] = None,
        download_subtitles: bool = False,
        use_aria2c: bool = True,
        clip_start: Optional[str] = None,
        clip_end: Optional[str] = None,
        normalize_audio: bool = False,
    ) -> int:
        """Encola una lista de elementos (playlist o selección múltiple)."""
        count = 0
        for item in items:
            url = item.get("url")
            if not url:
                continue
            title = item.get("title", "")
            thumb = item.get("thumbnail", "")
            self.add_download(
                url=url,
                format_type=format_type,
                output_dir=output_dir,
                title=title,
                thumbnail_url=thumb,
                browser=browser,
                download_subtitles=download_subtitles,
                use_aria2c=use_aria2c,
                clip_start=clip_start,
                clip_end=clip_end,
                normalize_audio=normalize_audio,
            )
            count += 1
        return count

    def retry_download(self, task_id: str) -> bool:
        """Reinicia una descarga que haya fallado o sido cancelada."""
        with self._lock:
            task = self.tasks.get(task_id)
            if not task:
                return False
            task.status = "queued"
            task.percent = 0.0
            task.speed = "--"
            task.eta = "--"
            task.status_text = "En cola de espera..."
            task.error_msg = ""

        self.task_queue.put(task)
        self._notify_update(task)
        return True

    def cancel_download(self, task_id: str):
        with self._lock:
            task = self.tasks.get(task_id)
        if task:
            task.status = "cancelled"
            task.status_text = "Descarga cancelada"
            self.downloader.cancel_task(task_id)
            self._notify_update(task)

    def get_active_tasks(self) -> List[DownloadTask]:
        with self._lock:
            return [t for t in self.tasks.values() if t.status in ["queued", "downloading", "converting"]]

    def get_all_tasks(self) -> List[DownloadTask]:
        with self._lock:
            return list(self.tasks.values())

    def stop(self):
        """Detiene la ejecución de los workers de forma limpia."""
        self._running = False
        self._pause_event.set()
        for task in self.get_active_tasks():
            self.cancel_download(task.id)

    def is_paused(self) -> bool:
        """Indica si la cola de descargas está pausada."""
        return self._is_paused

    def pause_queue(self):
        """Pausa la cola: los workers no tomarán nuevas tareas hasta que se reanude."""
        self._is_paused = True
        self._pause_event.clear()

    def resume_queue(self):
        """Reanuda la cola de descargas."""
        self._is_paused = False
        self._pause_event.set()

    def toggle_pause_queue(self) -> bool:
        """Alterna el estado de pausa de la cola y devuelve True si quedó pausada."""
        if self._is_paused:
            self.resume_queue()
        else:
            self.pause_queue()
        return self._is_paused

    def _notify_update(self, task: DownloadTask):
        if self.on_task_updated:
            try:
                self.on_task_updated(task)
            except Exception:
                pass

    def _notify_finished(self, task: DownloadTask):
        if self.on_task_finished:
            try:
                self.on_task_finished(task)
            except Exception:
                pass

    def _notify_queue_completed(self):
        if self.on_queue_completed:
            try:
                self.on_queue_completed()
            except Exception:
                pass

    def _worker_loop(self):
        while self._running:
            # Si la cola está pausada, esperar hasta que sea reanudada
            if not self._pause_event.wait(timeout=0.5):
                continue

            try:
                task: DownloadTask = self.task_queue.get(timeout=0.5)
            except queue.Empty:
                continue

            if not self._running:
                self.task_queue.task_done()
                break

            if task.status == "cancelled":
                self.task_queue.task_done()
                continue

            task.status = "downloading"
            task.status_text = "Iniciando descarga..."
            self._notify_update(task)

            def progress_cb(percent, speed, eta, msg):
                if task.status != "cancelled" and self._running:
                    task.percent = percent
                    task.speed = speed
                    task.eta = eta
                    task.status_text = f"{int(percent * 100)}% - {speed} (Restante: {eta})"
                    self._notify_update(task)

            def status_cb(text):
                if task.status != "cancelled" and self._running:
                    task.status = "converting"
                    task.status_text = text
                    self._notify_update(task)

            try:
                result = self.downloader.download(
                    task_id=task.id,
                    url=task.url,
                    format_type=task.format_type,
                    output_dir=task.output_dir,
                    progress_callback=progress_cb,
                    status_callback=status_cb,
                    browser=task.browser,
                    download_subtitles=task.download_subtitles,
                    use_aria2c=task.use_aria2c,
                    clip_start=task.clip_start,
                    clip_end=task.clip_end,
                    normalize_audio=task.normalize_audio,
                )

                if task.status != "cancelled":
                    task.status = "completed"
                    task.percent = 1.0
                    task.status_text = "¡Descarga completada!"
                    task.file_path = result.get('file_path', '')
                    task.title = result.get('title', task.title)
                    task.format_label = result.get('format_label', task.format_label)
                    task.media_type = result.get('media_type', task.media_type)
                    task.thumbnail_url = result.get('thumbnail_url', task.thumbnail_url)

                    self.history.add_download(
                        title=task.title,
                        file_path=task.file_path,
                        format_label=task.format_label,
                        media_type=task.media_type,
                        url=task.url,
                        thumbnail_url=task.thumbnail_url
                    )

                    # Notificación nativa y sonido al usuario
                    try:
                        clean_title = task.title[:45] + ("..." if len(task.title) > 45 else "")
                        send_notification("✦ OmniVault Desktop", f"¡Descarga completada!\n{clean_title}")
                    except Exception:
                        pass

                    self._notify_update(task)
                    self._notify_finished(task)

            except Exception as e:
                if task.status != "cancelled":
                    task.status = "error"
                    clean_msg = clean_error_text(str(e))
                    task.error_msg = clean_msg
                    first_l = clean_msg.splitlines()[0]
                    task.status_text = f"Error: {first_l[:70]}"
                    self._notify_update(task)

            finally:
                self.task_queue.task_done()
                # Si ya no quedan tareas pendientes ni activas, notificar finalización de cola
                if self.task_queue.empty() and not self.get_active_tasks():
                    self._notify_queue_completed()
