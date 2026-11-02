"""
Módulo de descarga y extracción multimedia usando yt-dlp y FFmpeg.
Soporta YouTube, Instagram, TikTok, Twitter/X, Twitch, Facebook, SoundCloud y más de 1000 sitios.
"""

import os
import re
import sys
import shutil
import yt_dlp
from typing import Callable, Optional, Dict, Any

def _ensure_ffmpeg():
    """Garantiza que FFmpeg esté en el PATH del proceso, leyéndolo del registro si es necesario."""
    if shutil.which("ffmpeg"):
        return
    if sys.platform == "win32":
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as key:
                user_path, _ = winreg.QueryValueEx(key, "Path")
                for p in user_path.split(";"):
                    if "ffmpeg" in p.lower() and os.path.isdir(p):
                        os.environ["PATH"] += os.pathsep + p
                        return
        except Exception:
            pass

_ensure_ffmpeg()

class Downloader:
    def __init__(self):
        self._cancel_requested = False

    def cancel(self):
        self._cancel_requested = True

    def get_info(self, url: str) -> Dict[str, Any]:
        """Extrae metadatos básicos de la URL (título, duración, autor, miniatura)."""
        ydl_opts = {
            'quiet': True,
            'no_warnings': True,
            'skip_download': True,
        }
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)
            return {
                'title': info.get('title', 'Desconocido'),
                'uploader': info.get('uploader', info.get('channel', 'Desconocido')),
                'duration': info.get('duration', 0),
                'thumbnail': info.get('thumbnail', None),
                'description': info.get('description', '')[:200] if info.get('description') else '',
            }

    def download(
        self,
        url: str,
        format_type: str,
        output_dir: str,
        progress_callback: Optional[Callable[[float, str, str, str], None]] = None,
        status_callback: Optional[Callable[[str], None]] = None,
    ) -> str:
        """
        Descarga el contenido multimedia en la ruta especificada.

        format_type puede ser:
          - 'mp3_320': Audio MP3 320kbps + metadatos + carátula
          - 'm4a_best': Audio M4A original sin recompresión
          - 'mp4_max': Video MP4 en la máxima resolución (4K, 1080p, etc.)
          - 'mp4_720': Video MP4 en 720p (ligero)
        """
        self._cancel_requested = False
        os.makedirs(output_dir, exist_ok=True)

        downloaded_file = ""

        def hook(d):
            if self._cancel_requested:
                raise Exception("Descarga cancelada por el usuario.")

            status = d.get('status')
            if status == 'downloading':
                # Calcular porcentaje
                total_bytes = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
                downloaded_bytes = d.get('downloaded_bytes', 0)
                
                percent = 0.0
                if total_bytes > 0:
                    percent = downloaded_bytes / total_bytes
                else:
                    # Parsear string de porcentaje
                    raw_percent = d.get('_percent_str', '0%')
                    clean_percent = re.sub(r'\x1b\[[0-9;]*m', '', raw_percent).replace('%', '').strip()
                    try:
                        percent = float(clean_percent) / 100.0
                    except ValueError:
                        percent = 0.0

                speed = re.sub(r'\x1b\[[0-9;]*m', '', d.get('_speed_str', 'N/A')).strip()
                eta = re.sub(r'\x1b\[[0-9;]*m', '', d.get('_eta_str', 'N/A')).strip()
                
                msg = f"Descargando: {percent * 100:.1f}% | Vel: {speed} | Restante: {eta}"
                if progress_callback:
                    progress_callback(percent, speed, eta, msg)

            elif status == 'finished':
                if progress_callback:
                    progress_callback(1.0, "", "", "Procesando y convirtiendo con FFmpeg...")
                if status_callback:
                    status_callback("Finalizando conversión multimedia...")

        # Opciones base
        out_template = os.path.join(output_dir, '%(title)s.%(ext)s')
        ydl_opts: Dict[str, Any] = {
            'outtmpl': out_template,
            'progress_hooks': [hook],
            'quiet': True,
            'no_warnings': True,
            'nocheckcertificate': True,
            # Asegurar que no sobrescriba archivos con el mismo nombre
            'windowsfilenames': True,
        }

        # Configuración según el formato seleccionado
        if format_type == 'mp3_320':
            ydl_opts.update({
                'format': 'bestaudio/best',
                'writethumbnail': True,
                'postprocessors': [
                    {
                        'key': 'FFmpegExtractAudio',
                        'preferredcodec': 'mp3',
                        'preferredquality': '320',
                    },
                    {
                        'key': 'FFmpegMetadata',
                        'add_metadata': True,
                    },
                    {
                        'key': 'EmbedThumbnail',
                        'already_have_thumbnail': False,
                    }
                ],
            })
        elif format_type == 'm4a_best':
            ydl_opts.update({
                'format': 'bestaudio[ext=m4a]/bestaudio/best',
                'postprocessors': [
                    {
                        'key': 'FFmpegMetadata',
                        'add_metadata': True,
                    }
                ],
            })
        elif format_type == 'mp4_max':
            ydl_opts.update({
                'format': 'bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best',
                'merge_output_format': 'mp4',
                'postprocessors': [
                    {
                        'key': 'FFmpegMetadata',
                        'add_metadata': True,
                    }
                ],
            })
        elif format_type == 'mp4_720':
            ydl_opts.update({
                'format': 'bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=720]+bestaudio/best[height<=720]/best',
                'merge_output_format': 'mp4',
                'postprocessors': [
                    {
                        'key': 'FFmpegMetadata',
                        'add_metadata': True,
                    }
                ],
            })
        else:
            raise ValueError(f"Formato no reconocido: {format_type}")

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ydl.download([url])

        return "Descarga completada con éxito."
