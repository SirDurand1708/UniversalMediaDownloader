"""
Módulo de Transcripción y Generación Inteligente de Subtítulos (.SRT) para OmniVault Desktop.
Extrae el audio mediante FFmpeg y transcribe con marcas de tiempo precisas
utilizando reconocimiento de voz y modelos de IA en español, inglés o multilingüe.
"""

import os
import sys
import wave
import shutil
import tempfile
import subprocess
import threading
from typing import Callable, Optional, Dict, Any, List

from downloader import FFMPEG_DIR, is_ffmpeg_available

try:
    import speech_recognition as sr
    SR_AVAILABLE = True
except ImportError:
    sr = None
    SR_AVAILABLE = False


def format_srt_timestamp(seconds: float) -> str:
    """Convierte segundos a formato estándar SubRip: 00:01:23,450."""
    seconds = max(0.0, float(seconds))
    h = int(seconds // 3600)
    m = int((seconds % 3600) // 60)
    s = int(seconds % 60)
    ms = int(round((seconds - int(seconds)) * 1000))
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


class MediaTranscriber:
    def __init__(self):
        self._cancelled = False
        self._lock = threading.Lock()

    def cancel(self):
        with self._lock:
            self._cancelled = True

    def is_cancelled(self) -> bool:
        with self._lock:
            return self._cancelled

    def transcribe(
        self,
        media_path: str,
        output_dir: Optional[str] = None,
        language: str = "es-ES",
        segment_seconds: int = 15,
        progress_callback: Optional[Callable[[float, str], None]] = None,
        status_callback: Optional[Callable[[str], None]] = None,
    ) -> Dict[str, Any]:
        """
        Transcribe cualquier archivo de audio o video y genera archivos .srt y .txt con marcas de tiempo.
        """
        with self._lock:
            self._cancelled = False

        if not SR_AVAILABLE:
            raise RuntimeError("La librería speech_recognition no está disponible.")

        if not is_ffmpeg_available():
            raise RuntimeError("FFmpeg es necesario para procesar el audio.")

        if not os.path.isfile(media_path):
            raise FileNotFoundError(f"Archivo no encontrado: {media_path}")

        out_dir = output_dir or os.path.dirname(media_path)
        os.makedirs(out_dir, exist_ok=True)
        base_name = os.path.splitext(os.path.basename(media_path))[0]
        srt_path = os.path.join(out_dir, f"{base_name}.srt")
        txt_path = os.path.join(out_dir, f"{base_name}.txt")

        ff = shutil.which("ffmpeg") or (os.path.join(FFMPEG_DIR, "ffmpeg.exe") if FFMPEG_DIR else None)
        flags = getattr(subprocess, 'CREATE_NO_WINDOW', 0) if sys.platform == "win32" else 0

        # 1. Extraer audio limpio a 16kHz mono WAV en carpeta temporal
        temp_dir = tempfile.mkdtemp(prefix="omnivault_trans_")
        temp_wav = os.path.join(temp_dir, "speech_mono16.wav")

        try:
            if status_callback:
                status_callback("Extrayendo flujo de audio a 16kHz...")

            cmd = [
                ff, "-y",
                "-i", media_path,
                "-vn",
                "-ac", "1",
                "-ar", "16000",
                temp_wav
            ]
            res = subprocess.run(cmd, capture_output=True, creationflags=flags)
            if res.returncode != 0 or not os.path.isfile(temp_wav):
                raise RuntimeError("No se pudo extraer el audio del archivo para transcribirlo.")

            # Obtener duración del WAV
            with wave.open(temp_wav, "rb") as wf:
                framerate = wf.getframerate()
                total_frames = wf.getnframes()
                total_duration = total_frames / float(framerate) if framerate > 0 else 0.0

            if total_duration <= 0.1:
                raise ValueError("El archivo de audio está vacío o no contiene duración válida.")

            recognizer = sr.Recognizer()
            recognizer.energy_threshold = 280
            recognizer.dynamic_energy_threshold = True

            srt_entries: List[str] = []
            txt_lines: List[str] = []
            current_time = 0.0
            idx = 1

            if status_callback:
                status_callback("Analizando y reconociendo voz con IA...")

            while current_time < total_duration:
                if self.is_cancelled():
                    raise Exception("Transcripción cancelada por el usuario.")

                chunk_end = min(current_time + segment_seconds, total_duration)
                seg_duration = chunk_end - current_time

                # Cortar segmento en WAV
                chunk_file = os.path.join(temp_dir, f"chunk_{idx}.wav")
                cmd_cut = [
                    ff, "-y",
                    "-ss", str(current_time),
                    "-t", str(seg_duration),
                    "-i", temp_wav,
                    "-c", "copy",
                    chunk_file
                ]
                subprocess.run(cmd_cut, capture_output=True, creationflags=flags)

                text = ""
                if os.path.isfile(chunk_file) and os.path.getsize(chunk_file) > 1024:
                    try:
                        with sr.AudioFile(chunk_file) as source:
                            audio_data = recognizer.record(source)
                            # Intentar reconocimiento
                            try:
                                text = recognizer.recognize_google(audio_data, language=language)
                            except sr.UnknownValueError:
                                text = ""
                            except Exception:
                                try:
                                    text = recognizer.recognize_whisper(audio_data, language=language.split('-')[0])
                                except Exception:
                                    text = ""
                    except Exception:
                        pass
                    try: os.remove(chunk_file)
                    except Exception: pass

                text = text.strip()
                if text:
                    # Formatear entrada SRT
                    t_start = format_srt_timestamp(current_time)
                    t_end = format_srt_timestamp(chunk_end)
                    srt_entry = f"{idx}\n{t_start} --> {t_end}\n{text}\n"
                    srt_entries.append(srt_entry)
                    txt_lines.append(f"[{format_duration(int(current_time))}] {text}")

                idx += 1
                current_time = chunk_end

                pct = min(current_time / total_duration, 0.99)
                if progress_callback:
                    progress_callback(pct, f"Procesado: {int(current_time)}s / {int(total_duration)}s")

            # Escribir archivos finales
            with open(srt_path, "w", encoding="utf-8") as f_srt:
                f_srt.write("\n".join(srt_entries) if srt_entries else "1\n00:00:00,000 --> 00:00:05,000\n[Sin diálogo detectado]\n")

            with open(txt_path, "w", encoding="utf-8") as f_txt:
                f_txt.write("\n".join(txt_lines) if txt_lines else "[Sin diálogo detectado]")

            if progress_callback:
                progress_callback(1.0, "100%")
            if status_callback:
                status_callback("¡Transcripción y subtítulos completados!")

            preview = "\n".join(txt_lines[:5]) if txt_lines else "(No se detectó voz clara)"
            return {
                "srt_path": srt_path,
                "txt_path": txt_path,
                "lines_count": len(txt_lines),
                "preview": preview,
                "duration_sec": total_duration,
            }

        finally:
            if os.path.isdir(temp_dir):
                shutil.rmtree(temp_dir, ignore_errors=True)
