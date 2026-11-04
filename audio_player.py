"""
Módulo de reproducción de audio nativo y de alto rendimiento para OmniVault Desktop.
Utiliza Windows MCI (winmm.dll) para reproducción precisa de MP3, WAV y M4A sin dependencias externas pesadas.
"""

import os
import sys
import ctypes
import threading
from typing import Optional

class AudioPlayer:
    def __init__(self):
        self._lock = threading.Lock()
        self._alias = "omnivault_audio_device"
        self._loaded_path: Optional[str] = None
        self._duration_ms: int = 0
        self._is_paused: bool = False
        self._volume: float = 0.8  # 0.0 a 1.0

        self._mci = None
        if sys.platform == "win32":
            try:
                self._mci = ctypes.windll.winmm.mciSendStringW
            except Exception:
                self._mci = None

    def _exec(self, cmd: str) -> tuple[int, str]:
        """Ejecuta un comando MCI de forma segura y devuelve (código_error, buffer_respuesta)."""
        if not self._mci:
            return -1, ""
        buf = ctypes.create_unicode_buffer(256)
        res = self._mci(cmd, buf, 256, 0)
        return res, buf.value

    def load(self, file_path: str) -> bool:
        """Carga una pista de audio (MP3, WAV, M4A) para su reproducción."""
        if not file_path or not os.path.isfile(file_path):
            return False

        with self._lock:
            self.stop()
            self._exec(f'close {self._alias}')

            # En Windows MCI las rutas con barras diagonales son más seguras
            clean_path = os.path.abspath(file_path).replace("\\", "/")
            res, _ = self._exec(f'open "{clean_path}" type mpegvideo alias {self._alias}')
            if res != 0:
                # Reintentar sin especificar 'type mpegvideo' para WAV u otros
                res, _ = self._exec(f'open "{clean_path}" alias {self._alias}')
                if res != 0:
                    return False

            self._exec(f'set {self._alias} time format milliseconds')
            _, len_str = self._exec(f'status {self._alias} length')
            self._duration_ms = int(len_str) if len_str.isdigit() else 0
            self._loaded_path = file_path
            self._is_paused = False

            # Aplicar volumen predeterminado
            self.set_volume(self._volume)
            return True

    def play(self) -> bool:
        """Inicia o reanuda la reproducción desde el principio o la posición actual."""
        with self._lock:
            if not self._loaded_path:
                return False
            res, _ = self._exec(f'play {self._alias}')
            self._is_paused = False
            return res == 0

    def pause(self) -> bool:
        """Pausa la reproducción."""
        with self._lock:
            if not self._loaded_path:
                return False
            res, _ = self._exec(f'pause {self._alias}')
            self._is_paused = True
            return res == 0

    def resume(self) -> bool:
        """Reanuda la reproducción pausada."""
        with self._lock:
            if not self._loaded_path:
                return False
            res, _ = self._exec(f'resume {self._alias}')
            self._is_paused = False
            return res == 0

    def stop(self):
        """Detiene la reproducción y regresa la pista al inicio."""
        with self._lock:
            if self._loaded_path:
                self._exec(f'stop {self._alias}')
                self._exec(f'seek {self._alias} to start')
                self._is_paused = False

    def seek(self, position_ms: int):
        """Avanza o retrocede la reproducción a una posición específica en milisegundos."""
        with self._lock:
            if not self._loaded_path:
                return
            target = max(0, min(position_ms, self._duration_ms))
            was_playing = self.is_playing()
            self._exec(f'seek {self._alias} to {int(target)}')
            if was_playing:
                self._exec(f'play {self._alias}')

    def set_volume(self, level: float):
        """Ajusta el volumen de 0.0 (silencio) a 1.0 (máximo)."""
        with self._lock:
            self._volume = max(0.0, min(1.0, float(level)))
            mci_vol = int(self._volume * 1000)
            self._exec(f'setaudio {self._alias} volume to {mci_vol}')

    def get_volume(self) -> float:
        return self._volume

    def get_position(self) -> int:
        """Devuelve la posición actual de reproducción en milisegundos."""
        with self._lock:
            if not self._loaded_path:
                return 0
            _, pos_str = self._exec(f'status {self._alias} position')
            return int(pos_str) if pos_str.isdigit() else 0

    def get_duration(self) -> int:
        """Devuelve la duración total de la pista en milisegundos."""
        return self._duration_ms

    def is_playing(self) -> bool:
        """Comprueba si el reproductor está actualmente emitiendo sonido."""
        with self._lock:
            if not self._loaded_path or self._is_paused:
                return False
            _, mode = self._exec(f'status {self._alias} mode')
            return mode.lower() == "playing"

    def is_paused(self) -> bool:
        return self._is_paused

    def get_loaded_path(self) -> Optional[str]:
        return self._loaded_path

    def close(self):
        """Cierra el dispositivo MCI y libera recursos de Windows."""
        with self._lock:
            self._exec(f'close {self._alias}')
            self._loaded_path = None
            self._duration_ms = 0
            self._is_paused = False
