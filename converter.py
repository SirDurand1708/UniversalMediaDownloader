"""
Módulo de Conversor Multimedia Local Offline para OmniVault Desktop.
Permite convertir, comprimir y extraer audio de archivos locales en la PC usando FFmpeg y aceleración por GPU.
"""

import os
import re
import sys
import time
import shutil
import subprocess
import threading
from typing import Callable, Optional, Dict, Any, List

from downloader import FFMPEG_DIR, detect_hardware_acceleration, is_ffmpeg_available

def _get_ffmpeg_bin() -> Optional[str]:
    ff = shutil.which("ffmpeg")
    if ff:
        return ff
    if FFMPEG_DIR:
        cand = os.path.join(FFMPEG_DIR, "ffmpeg.exe" if sys.platform == "win32" else "ffmpeg")
        if os.path.isfile(cand):
            return cand
    return None

def _get_ffprobe_bin() -> Optional[str]:
    ff = shutil.which("ffprobe")
    if ff:
        return ff
    if FFMPEG_DIR:
        cand = os.path.join(FFMPEG_DIR, "ffprobe.exe" if sys.platform == "win32" else "ffprobe")
        if os.path.isfile(cand):
            return cand
    return None

def get_media_info(file_path: str) -> Dict[str, Any]:
    """Obtiene información básica del archivo multimedia local (duración, tamaño, formato)."""
    if not os.path.isfile(file_path):
        return {"error": "Archivo no encontrado"}

    size_bytes = os.path.getsize(file_path)
    size_mb = size_bytes / (1024 * 1024)

    info = {
        "file_name": os.path.basename(file_path),
        "file_path": file_path,
        "size_mb": round(size_mb, 2),
        "size_str": f"{size_mb:.2f} MB" if size_mb < 1024 else f"{size_mb/1024:.2f} GB",
        "duration_sec": 0.0,
        "duration_str": "--:--",
        "has_video": False,
        "has_audio": False,
    }

    ffprobe = _get_ffprobe_bin()
    if not ffprobe:
        # Intentar obtener duración usando ffmpeg -i
        ffmpeg = _get_ffmpeg_bin()
        if not ffmpeg:
            return info
        try:
            flags = getattr(subprocess, 'CREATE_NO_WINDOW', 0) if sys.platform == "win32" else 0
            p = subprocess.run([ffmpeg, "-i", file_path], capture_output=True, text=True, errors="replace", creationflags=flags)
            err = p.stderr
            dur_match = re.search(r"Duration:\s*(\d+):(\d+):(\d+\.?\d*)", err)
            if dur_match:
                h, m, s = float(dur_match.group(1)), float(dur_match.group(2)), float(dur_match.group(3))
                total_s = h * 3600 + m * 60 + s
                info["duration_sec"] = total_s
                info["duration_str"] = f"{int(h):02d}:{int(m):02d}:{int(s):02d}" if h > 0 else f"{int(m):02d}:{int(s):02d}"
            if "Video:" in err:
                info["has_video"] = True
            if "Audio:" in err:
                info["has_audio"] = True
        except Exception:
            pass
        return info

    try:
        flags = getattr(subprocess, 'CREATE_NO_WINDOW', 0) if sys.platform == "win32" else 0
        cmd = [
            ffprobe,
            "-v", "error",
            "-show_entries", "format=duration:stream=codec_type",
            "-of", "default=noprint_wrappers=1:nokey=1",
            file_path
        ]
        p = subprocess.run(cmd, capture_output=True, text=True, errors="replace", creationflags=flags, timeout=5)
        lines = p.stdout.strip().splitlines()
        for line in lines:
            line = line.strip()
            if line == "video":
                info["has_video"] = True
            elif line == "audio":
                info["has_audio"] = True
            else:
                try:
                    dur = float(line)
                    info["duration_sec"] = dur
                    m = int(dur // 60)
                    s = int(dur % 60)
                    h = m // 60
                    m = m % 60
                    info["duration_str"] = f"{h:02d}:{m:02d}:{s:02d}" if h > 0 else f"{m:02d}:{s:02d}"
                except ValueError:
                    pass
    except Exception:
        pass

    return info


class LocalConverter:
    """Motor de conversión offline de alta velocidad con FFmpeg y GPU."""

    def __init__(self):
        self._proc: Optional[subprocess.Popen] = None
        self._cancelled = False
        self._lock = threading.Lock()

    def cancel(self):
        """Cancela el proceso de conversión en curso."""
        with self._lock:
            self._cancelled = True
            if self._proc and self._proc.poll() is None:
                try:
                    self._proc.terminate()
                except Exception:
                    pass

    def is_cancelled(self) -> bool:
        with self._lock:
            return self._cancelled

    def convert(
        self,
        input_path: str,
        output_format: str,
        output_dir: str,
        progress_callback: Optional[Callable[[float, str, str], None]] = None,
        status_callback: Optional[Callable[[str], None]] = None,
        normalize_audio: bool = False,
    ) -> Dict[str, Any]:
        """
        Ejecuta la conversión de un archivo local.
        output_format: 'mp3_320', 'mp3_192', 'wav', 'm4a', 'flac', 'mp4', 'compress_mp4'
        """
        with self._lock:
            self._cancelled = False

        if not is_ffmpeg_available():
            raise RuntimeError("FFmpeg no está disponible para realizar la conversión.")

        ffmpeg = _get_ffmpeg_bin()
        if not ffmpeg:
            raise RuntimeError("No se encontró el ejecutable de FFmpeg.")

        if not os.path.isfile(input_path):
            raise FileNotFoundError(f"El archivo fuente no existe: {input_path}")

        os.makedirs(output_dir, exist_ok=True)
        base_name = os.path.splitext(os.path.basename(input_path))[0]
        media_info = get_media_info(input_path)
        total_duration = media_info.get("duration_sec", 0.0)

        # Validación preventiva: no convertir audio puro a video
        if not media_info.get("has_video") and output_format in ["mp4", "compress_mp4"]:
            raise ValueError("El archivo fuente solo contiene audio. Selecciona un formato de audio (MP3, WAV, M4A o FLAC) para convertirlo.")

        # Construir argumentos de salida
        extra_args: List[str] = []
        ext = ".mp3"
        gpu = detect_hardware_acceleration()

        if output_format == "mp3_320":
            ext = ".mp3"
            extra_args = ["-vn", "-c:a", "libmp3lame", "-b:a", "320k", "-id3v2_version", "3"]
        elif output_format == "mp3_192":
            ext = ".mp3"
            extra_args = ["-vn", "-c:a", "libmp3lame", "-b:a", "192k", "-id3v2_version", "3"]
        elif output_format == "wav":
            ext = ".wav"
            extra_args = ["-vn", "-c:a", "pcm_s16le"]
        elif output_format == "m4a":
            ext = ".m4a"
            extra_args = ["-vn", "-c:a", "aac", "-b:a", "256k"]
        elif output_format == "flac":
            ext = ".flac"
            extra_args = ["-vn", "-c:a", "flac"]
        elif output_format == "mp4":
            ext = ".mp4"
            if gpu == "AMD AMF":
                extra_args = ["-c:v", "h264_amf", "-c:a", "aac", "-b:a", "192k"]
            elif gpu == "NVIDIA NVENC":
                extra_args = ["-c:v", "h264_nvenc", "-c:a", "aac", "-b:a", "192k"]
            elif gpu == "Intel QuickSync":
                extra_args = ["-c:v", "h264_qsv", "-c:a", "aac", "-b:a", "192k"]
            else:
                extra_args = ["-c:v", "libx264", "-preset", "fast", "-crf", "22", "-c:a", "aac", "-b:a", "192k"]
        elif output_format == "compress_mp4":
            ext = ".mp4"
            base_name += "_comprimido"
            if gpu == "AMD AMF":
                extra_args = ["-c:v", "h264_amf", "-b:v", "1800k", "-c:a", "aac", "-b:a", "128k"]
            elif gpu == "NVIDIA NVENC":
                extra_args = ["-c:v", "h264_nvenc", "-cq", "28", "-c:a", "aac", "-b:a", "128k"]
            else:
                extra_args = ["-c:v", "libx264", "-preset", "medium", "-crf", "27", "-c:a", "aac", "-b:a", "128k"]
        else:
            raise ValueError(f"Formato desconocido: {output_format}")

        if normalize_audio:
            extra_args = ["-filter:a", "loudnorm=I=-16:TP=-1.5:LRA=11"] + extra_args

        output_file = os.path.join(output_dir, f"{base_name}{ext}")

        # Evitar sobreescribir el mismo archivo si origen == destino
        if os.path.abspath(input_path).lower() == os.path.abspath(output_file).lower():
            output_file = os.path.join(output_dir, f"{base_name}_convertido{ext}")

        cmd = [
            ffmpeg,
            "-y",
            "-i", input_path,
        ] + extra_args + [output_file]

        if status_callback:
            status_callback("Iniciando conversión con FFmpeg...")

        flags = getattr(subprocess, 'CREATE_NO_WINDOW', 0) if sys.platform == "win32" else 0
        start_time = time.time()

        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            errors="replace",
            creationflags=flags
        )
        with self._lock:
            self._proc = proc

        # Leer progreso de stderr en tiempo real
        time_pattern = re.compile(r"time=(\d+):(\d+):(\d+\.?\d*)")
        speed_pattern = re.compile(r"speed=\s*([\d\.]+x)")

        stderr_output = []
        try:
            while True:
                if self.is_cancelled():
                    proc.terminate()
                    raise Exception("Conversión cancelada por el usuario.")

                line = proc.stderr.readline()
                if not line and proc.poll() is not None:
                    break
                if not line:
                    continue

                stderr_output.append(line)
                time_m = time_pattern.search(line)
                speed_m = speed_pattern.search(line)

                if time_m and total_duration > 0:
                    h, m, s = float(time_m.group(1)), float(time_m.group(2)), float(time_m.group(3))
                    curr_sec = h * 3600 + m * 60 + s
                    pct = min(curr_sec / total_duration, 0.99)

                    speed_str = speed_m.group(1) if speed_m else "--"
                    speed_val = 1.0
                    if speed_m:
                        try:
                            speed_val = max(float(speed_m.group(1).replace('x', '').strip()), 0.1)
                        except ValueError:
                            speed_val = 1.0

                    rem_sec = int((total_duration - curr_sec) / speed_val)
                    eta_str = f"{rem_sec}s" if rem_sec >= 0 else "--"

                    if progress_callback:
                        progress_callback(pct, speed_str, eta_str)
        finally:
            with self._lock:
                self._proc = None

        if self.is_cancelled():
            if os.path.isfile(output_file):
                try: os.remove(output_file)
                except Exception: pass
            raise Exception("Conversión cancelada por el usuario.")

        rc = proc.wait()
        if rc != 0 or not os.path.isfile(output_file):
            # Fallback inteligente: si la GPU falló, reintentar con CPU libx264
            if gpu and output_format in ["mp4", "compress_mp4"]:
                if status_callback:
                    status_callback("GPU no disponible para este códec. Reintentando con CPU (libx264)...")
                cpu_args = ["-c:v", "libx264", "-preset", "fast", "-crf", "23", "-c:a", "aac", "-b:a", "192k"]
                cmd_cpu = [ffmpeg, "-y", "-i", input_path] + cpu_args + [output_file]
                res_cpu = subprocess.run(cmd_cpu, capture_output=True, text=True, errors="replace", creationflags=flags)
                if res_cpu.returncode == 0 and os.path.isfile(output_file):
                    gpu = "CPU (Fallback de GPU)"
                else:
                    err_summary = "".join(stderr_output[-15:])
                    raise RuntimeError(f"Error durante la conversión FFmpeg (código {rc}):\n{err_summary}")
            else:
                err_summary = "".join(stderr_output[-15:])
                raise RuntimeError(f"Error durante la conversión FFmpeg (código {rc}):\n{err_summary}")

        if progress_callback:
            progress_callback(1.0, "100%", "0s")
        if status_callback:
            status_callback("¡Conversión completada con éxito!")

        out_info = get_media_info(output_file)
        return {
            "file_path": output_file,
            "file_name": os.path.basename(output_file),
            "size_str": out_info.get("size_str", ""),
            "duration_str": out_info.get("duration_str", ""),
            "gpu_used": gpu or "CPU Estándar",
        }
