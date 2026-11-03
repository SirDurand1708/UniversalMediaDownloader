"""
Motor de extracción y descarga multimedia usando yt-dlp y FFmpeg.
Compatible con YouTube, TikTok, Instagram, Twitter/X, Twitch, SoundCloud y más de 1000 sitios.
Soporte inteligente: Conexión limpia nativa por defecto + fallback de sesión para +18/privados.
"""

import os
import re
import sys
import shutil
import yt_dlp
from typing import Callable, Optional, Dict, Any, List

def _ensure_ffmpeg() -> Optional[str]:
    """Garantiza que FFmpeg esté en el PATH del proceso, leyéndolo del registro si es necesario."""
    found = shutil.which("ffmpeg")
    if found:
        return os.path.dirname(found)

    if sys.platform == "win32":
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as key:
                user_path, _ = winreg.QueryValueEx(key, "Path")
                for p in user_path.split(";"):
                    if "ffmpeg" in p.lower() and os.path.isdir(p):
                        os.environ["PATH"] += os.pathsep + p
                        return p
        except Exception:
            pass
    return None

FFMPEG_DIR = _ensure_ffmpeg()

def clean_error_text(err: str) -> str:
    """Elimina códigos de escape ANSI y prefijos redundantes de error."""
    clean = re.sub(r'\x1b\[[0-9;]*[a-zA-Z]', '', err)
    clean = re.sub(r'(\[0;[0-9]+m)+', '', clean)
    clean = re.sub(r'ERROR:\s*', '', clean)
    return clean.strip()

def format_duration(seconds: Optional[int]) -> str:
    """Convierte segundos en formato MM:SS o HH:MM:SS."""
    if not seconds:
        return "--:--"
    m, s = divmod(int(seconds), 60)
    h, m = divmod(m, 60)
    if h > 0:
        return f"{h:02d}:{m:02d}:{s:02d}"
    return f"{m:02d}:{s:02d}"

def is_url(text: str) -> bool:
    """Determina si el texto ingresado es una URL o una búsqueda."""
    text = text.strip()
    return bool(re.match(r'^(https?://|www\.)', text, re.IGNORECASE))

class DownloaderEngine:
    def __init__(self):
        self._cancel_flags: Dict[str, bool] = {}

    def cancel_task(self, task_id: str):
        self._cancel_flags[task_id] = True

    def export_browser_cookies_snapshot(self, browser: str = "vivaldi") -> str:
        """
        Extrae las cookies del navegador y las guarda en un archivo local 'saved_cookies.txt'.
        Requiere que el navegador esté cerrado brevemente para evitar el bloqueo de archivo de Windows.
        """
        dest_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "saved_cookies.txt")
        ydl_opts = {
            'quiet': True,
            'no_warnings': True,
            'cookiesfrombrowser': (browser.lower(),),
        }
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            jar = ydl.cookiejar
            jar.save(dest_path, ignore_discard=True, ignore_expires=True)
        return dest_path

    def _base_opts(self, browser: Optional[str] = None, cookiefile: Optional[str] = None, use_cookies: bool = False) -> Dict[str, Any]:
        opts: Dict[str, Any] = {
            'quiet': True,
            'no_warnings': True,
            'nocheckcertificate': True,
            'windowsfilenames': True,
            'js_runtimes': {'node': {}},
        }
        
        # Localización de FFmpeg
        ff = FFMPEG_DIR or shutil.which("ffmpeg")
        if ff:
            opts['ffmpeg_location'] = ff if os.path.isdir(ff) else os.path.dirname(ff)

        # Solo adjuntar cookies cuando se solicite explícitamente (para contenido +18 o privado)
        if use_cookies:
            saved_cookie = os.path.join(os.path.dirname(os.path.abspath(__file__)), "saved_cookies.txt")
            if cookiefile and os.path.exists(cookiefile):
                opts['cookiefile'] = cookiefile
            elif os.path.exists(saved_cookie) and os.path.getsize(saved_cookie) > 0:
                opts['cookiefile'] = saved_cookie
            elif browser and browser.lower() != 'none':
                opts['cookiesfrombrowser'] = (browser.lower(),)

        return opts

    def search_youtube(self, query: str, max_results: int = 6, browser: Optional[str] = None) -> List[Dict[str, Any]]:
        """Busca videos en YouTube directamente mediante conexión limpia nativa."""
        search_query = f"ytsearch{max_results}:{query}"
        ydl_opts = self._base_opts(use_cookies=False)
        ydl_opts.update({
            'extract_flat': True,
            'skip_download': True,
        })
        results = []
        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                data = ydl.extract_info(search_query, download=False)
        except Exception as e:
            raise Exception(clean_error_text(str(e)))

        entries = data.get('entries', []) if data else []
        for e in entries:
            if not e:
                continue
            v_id = e.get('id', '')
            url = e.get('url') or f"https://www.youtube.com/watch?v={v_id}"
            
            thumb = None
            thumbs = e.get('thumbnails', [])
            if thumbs:
                thumb = thumbs[-1].get('url')
            elif v_id:
                thumb = f"https://i.ytimg.com/vi/{v_id}/hqdefault.jpg"

            results.append({
                'id': v_id,
                'title': e.get('title', 'Sin título'),
                'uploader': e.get('uploader') or e.get('channel') or 'Desconocido',
                'duration': format_duration(e.get('duration')),
                'raw_duration': e.get('duration', 0),
                'url': url,
                'thumbnail': thumb,
            })
        return results

    def inspect_url(self, url: str, browser: Optional[str] = None, cookiefile: Optional[str] = None) -> Dict[str, Any]:
        """
        Inspecciona una URL universalmente.
        Estrategia inteligente:
        1. Utiliza conexión nativa limpia (evita bloqueos de PO-Token y LatinAutor-UMPG de discográficas).
        2. Si el video es privado o con restricción de edad (+18), utiliza la sesión de cookies.
        """
        ydl_opts = self._base_opts(use_cookies=False)
        ydl_opts.update({
            'extract_flat': True,
            'skip_download': True,
        })

        res = None
        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                res = ydl.extract_info(url, download=False)
        except Exception as e:
            err = str(e).lower()
            # Si requiere inicio de sesión para verificar edad o es video privado, activar cookies
            if 'sign in' in err or 'age' in err or 'confirm your age' in err or 'private' in err:
                ydl_opts_cookies = self._base_opts(browser=browser, cookiefile=cookiefile, use_cookies=True)
                ydl_opts_cookies.update({'extract_flat': True, 'skip_download': True})
                try:
                    with yt_dlp.YoutubeDL(ydl_opts_cookies) as ydl:
                        res = ydl.extract_info(url, download=False)
                except Exception as e2:
                    raise Exception(clean_error_text(str(e2)))
            else:
                raise Exception(clean_error_text(str(e)))

        if not res:
            # Reintento con cookies por si acaso
            ydl_opts_cookies = self._base_opts(browser=browser, cookiefile=cookiefile, use_cookies=True)
            ydl_opts_cookies.update({'extract_flat': True, 'skip_download': True})
            try:
                with yt_dlp.YoutubeDL(ydl_opts_cookies) as ydl:
                    res = ydl.extract_info(url, download=False)
            except Exception:
                pass

        if not res:
            raise Exception("No se pudo obtener la información del contenido multimedia.")

        is_playlist = (
            res.get('_type') == 'playlist' or 
            ('entries' in res and len(res.get('entries', [])) > 1)
        )

        # --- CASO 1: ES UNA PLAYLIST O ÁLBUM ---
        if is_playlist:
            raw_entries = res.get('entries', [])
            entries = []
            for idx, e in enumerate(raw_entries, 1):
                if not e:
                    continue
                v_id = e.get('id', '')
                item_url = e.get('url') or f"https://www.youtube.com/watch?v={v_id}"
                
                thumb = None
                thumbs = e.get('thumbnails', [])
                if thumbs:
                    thumb = thumbs[-1].get('url')
                elif v_id:
                    thumb = f"https://i.ytimg.com/vi/{v_id}/hqdefault.jpg"

                entries.append({
                    'index': idx,
                    'id': v_id,
                    'title': e.get('title', f'Pista {idx}'),
                    'uploader': e.get('uploader') or e.get('channel') or 'Desconocido',
                    'duration': format_duration(e.get('duration')),
                    'raw_duration': e.get('duration', 0),
                    'url': item_url,
                    'thumbnail': thumb,
                })

            thumb = None
            thumbs = res.get('thumbnails', [])
            if thumbs:
                thumb = thumbs[-1].get('url')
            elif entries and entries[0].get('thumbnail'):
                thumb = entries[0]['thumbnail']

            return {
                'is_playlist': True,
                'title': res.get('title', 'Lista de Reproducción'),
                'uploader': res.get('uploader') or res.get('channel') or 'Varios Artistas',
                'count': len(entries),
                'entries': entries,
                'thumbnail': thumb,
                'url': url,
                'extractor': res.get('extractor_key', 'Playlist'),
            }

        # --- CASO 2: ES UN VIDEO INDIVIDUAL ---
        formats = res.get('formats', [])
        heights = set()
        for f in formats:
            h = f.get('height')
            if h and isinstance(h, int) and h > 0:
                heights.add(h)

        v_id = res.get('id', '')
        thumb = res.get('thumbnail')
        if not thumb and v_id:
            thumb = f"https://i.ytimg.com/vi/{v_id}/hqdefault.jpg"

        return {
            'is_playlist': False,
            'id': v_id,
            'title': res.get('title', 'Sin título'),
            'uploader': res.get('uploader') or res.get('channel') or 'Desconocido',
            'duration': format_duration(res.get('duration')),
            'raw_duration': res.get('duration', 0),
            'thumbnail': thumb,
            'url': url,
            'available_heights': sorted(list(heights), reverse=True),
            'extractor': res.get('extractor_key', 'Universal'),
        }

    def download(
        self,
        task_id: str,
        url: str,
        format_type: str,
        output_dir: str,
        progress_callback: Optional[Callable[[float, str, str, str], None]] = None,
        status_callback: Optional[Callable[[str], None]] = None,
        browser: Optional[str] = None,
        cookiefile: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Descarga y convierte el archivo multimedia según el formato solicitado."""
        self._cancel_flags[task_id] = False
        os.makedirs(output_dir, exist_ok=True)

        def hook(d):
            if self._cancel_flags.get(task_id, False):
                raise Exception("Descarga cancelada por el usuario.")

            status = d.get('status')
            if status == 'downloading':
                total_bytes = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
                downloaded_bytes = d.get('downloaded_bytes', 0)
                
                percent = 0.0
                if total_bytes > 0:
                    percent = downloaded_bytes / total_bytes
                else:
                    raw_percent = d.get('_percent_str', '0%')
                    clean = re.sub(r'\x1b\[[0-9;]*m', '', raw_percent).replace('%', '').strip()
                    try:
                        percent = float(clean) / 100.0
                    except ValueError:
                        percent = 0.0

                speed = re.sub(r'\x1b\[[0-9;]*m', '', d.get('_speed_str', 'N/A')).strip()
                eta = re.sub(r'\x1b\[[0-9;]*m', '', d.get('_eta_str', 'N/A')).strip()
                
                msg = f"{int(percent * 100)}%"
                if progress_callback:
                    progress_callback(percent, speed, eta, msg)

            elif status == 'finished':
                if progress_callback:
                    progress_callback(1.0, "", "", "100%")
                if status_callback:
                    status_callback("Procesando con FFmpeg...")

        out_template = os.path.join(output_dir, '%(title)s.%(ext)s')
        
        # Opciones comunes de formato
        format_opts: Dict[str, Any] = {
            'outtmpl': out_template,
            'progress_hooks': [hook],
        }

        format_label = "Desconocido"
        media_type = "video"

        # --- OPCIONES DE AUDIO ---
        if format_type == 'mp3_320':
            format_label = "MP3 (320 kbps HD)"
            media_type = "audio"
            format_opts.update({
                'format': 'bestaudio/best',
                'writethumbnail': True,
                'postprocessors': [
                    {'key': 'FFmpegExtractAudio', 'preferredcodec': 'mp3', 'preferredquality': '320'},
                    {'key': 'FFmpegMetadata', 'add_metadata': True},
                    {'key': 'EmbedThumbnail', 'already_have_thumbnail': False},
                ],
            })
        elif format_type == 'mp3_192':
            format_label = "MP3 (192 kbps)"
            media_type = "audio"
            format_opts.update({
                'format': 'bestaudio/best',
                'writethumbnail': True,
                'postprocessors': [
                    {'key': 'FFmpegExtractAudio', 'preferredcodec': 'mp3', 'preferredquality': '192'},
                    {'key': 'FFmpegMetadata', 'add_metadata': True},
                    {'key': 'EmbedThumbnail', 'already_have_thumbnail': False},
                ],
            })
        elif format_type == 'm4a_best':
            format_label = "M4A Original"
            media_type = "audio"
            format_opts.update({
                'format': 'bestaudio[ext=m4a]/bestaudio/best',
                'postprocessors': [{'key': 'FFmpegMetadata', 'add_metadata': True}],
            })
        elif format_type == 'wav':
            format_label = "WAV Sin Compresión"
            media_type = "audio"
            format_opts.update({
                'format': 'bestaudio/best',
                'postprocessors': [
                    {'key': 'FFmpegExtractAudio', 'preferredcodec': 'wav'},
                    {'key': 'FFmpegMetadata', 'add_metadata': True},
                ],
            })

        # --- OPCIONES DE VIDEO ---
        elif format_type == 'mp4_4k':
            format_label = "MP4 4K / 2K Ultra HD"
            media_type = "video"
            format_opts.update({
                'format': 'bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best',
                'merge_output_format': 'mp4',
                'postprocessors': [{'key': 'FFmpegMetadata', 'add_metadata': True}],
            })
        elif format_type == 'mp4_1080':
            format_label = "MP4 1080p Full HD"
            media_type = "video"
            format_opts.update({
                'format': 'bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=1080]+bestaudio/best[height<=1080]/best',
                'merge_output_format': 'mp4',
                'postprocessors': [{'key': 'FFmpegMetadata', 'add_metadata': True}],
            })
        elif format_type == 'mp4_720':
            format_label = "MP4 720p HD"
            media_type = "video"
            format_opts.update({
                'format': 'bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=720]+bestaudio/best[height<=720]/best',
                'merge_output_format': 'mp4',
                'postprocessors': [{'key': 'FFmpegMetadata', 'add_metadata': True}],
            })
        elif format_type == 'mp4_480':
            format_label = "MP4 480p Ligero"
            media_type = "video"
            format_opts.update({
                'format': 'bestvideo[height<=480][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=480]+bestaudio/best[height<=480]/best',
                'merge_output_format': 'mp4',
                'postprocessors': [{'key': 'FFmpegMetadata', 'add_metadata': True}],
            })
        else:
            raise ValueError(f"Formato desconocido: {format_type}")

        # Intentar primero con conexión limpia nativa
        ydl_opts_clean = self._base_opts(use_cookies=False)
        ydl_opts_clean.update(format_opts)

        info = None
        try:
            with yt_dlp.YoutubeDL(ydl_opts_clean) as ydl:
                info = ydl.extract_info(url, download=True)
        except Exception as e:
            err = str(e).lower()
            # Si requiere inicio de sesión, intentar con cookies
            if 'sign in' in err or 'age' in err or 'confirm your age' in err or 'private' in err:
                ydl_opts_cookies = self._base_opts(browser=browser, cookiefile=cookiefile, use_cookies=True)
                ydl_opts_cookies.update(format_opts)
                try:
                    with yt_dlp.YoutubeDL(ydl_opts_cookies) as ydl:
                        info = ydl.extract_info(url, download=True)
                except Exception as e2:
                    raise Exception(clean_error_text(str(e2)))
            else:
                raise Exception(clean_error_text(str(e)))

        if not info:
            raise Exception("No se pudo completar la descarga del archivo.")

        with yt_dlp.YoutubeDL(ydl_opts_clean) as ydl:
            raw_filename = ydl.prepare_filename(info)
        
        if format_type.startswith('mp3'):
            final_path = os.path.splitext(raw_filename)[0] + '.mp3'
        elif format_type == 'wav':
            final_path = os.path.splitext(raw_filename)[0] + '.wav'
        elif format_type == 'm4a_best':
            final_path = os.path.splitext(raw_filename)[0] + '.m4a'
        else:
            final_path = os.path.splitext(raw_filename)[0] + '.mp4'

        if not os.path.exists(final_path):
            base = os.path.splitext(raw_filename)[0]
            for ext in ['.mp3', '.mp4', '.m4a', '.wav', '.webm', '.mkv']:
                candidate = base + ext
                if os.path.exists(candidate):
                    final_path = candidate
                    break

        title = info.get('title', os.path.basename(final_path))
        thumbnail = info.get('thumbnail', '')

        return {
            'title': title,
            'file_path': final_path,
            'format_label': format_label,
            'media_type': media_type,
            'thumbnail_url': thumbnail,
        }
