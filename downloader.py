"""
Motor de extracción y descarga multimedia usando yt-dlp, curl_cffi y FFmpeg.
Compatible con YouTube, TikTok, Instagram, Twitter/X, Twitch, SoundCloud y más de 1000 sitios.
Incluye motor especializado de alta velocidad para TikTok (resolución de bloqueos y descarga sin marca de agua).
"""

import os
import re
import sys
import glob
import time
import json
import shutil
import threading
import subprocess
import urllib.parse
import urllib.request
from typing import Callable, Optional, Dict, Any, List
import yt_dlp

def _ensure_ffmpeg() -> Optional[str]:
    """Garantiza que FFmpeg esté en el PATH del proceso, buscando en PATH, registro y carpeta local."""
    found = shutil.which("ffmpeg")
    if found:
        return os.path.dirname(os.path.abspath(found))

    # Buscar en carpeta local del proyecto o subcarpeta 'bin'
    base_dir = os.path.dirname(os.path.abspath(__file__))
    local_candidates = [
        base_dir,
        os.path.join(base_dir, "bin"),
        os.path.join(base_dir, "ffmpeg"),
    ]
    for cand in local_candidates:
        cand_exe = os.path.join(cand, "ffmpeg.exe" if sys.platform == "win32" else "ffmpeg")
        if os.path.isfile(cand_exe):
            os.environ["PATH"] = cand + os.pathsep + os.environ.get("PATH", "")
            return cand

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

def is_ffmpeg_available() -> bool:
    """Comprueba si FFmpeg está disponible y funcional."""
    return bool(FFMPEG_DIR or shutil.which("ffmpeg"))

def sanitize_filename(name: str) -> str:
    """Sanea un nombre de archivo eliminando caracteres ilegales en Windows/Linux/macOS."""
    clean = re.sub(r'[\x5c/:\x2a\x3f\x22\x3c\x3e\x7c]', '_', name).strip()
    return clean[:120] if clean else "Media_File"

def clean_error_text(err: str) -> str:
    """Elimina códigos de escape ANSI y prefijos redundantes de error."""
    if not err:
        return "Error desconocido"
    clean = re.sub(r'\x1b\[[0-9;]*[a-zA-Z]', '', err)
    clean = re.sub(r'(\[0;[0-9]+m)+', '', clean)
    clean = re.sub(r'ERROR:\s*', '', clean)
    return clean.strip()

def format_duration(seconds: Optional[int]) -> str:
    """Convierte segundos en formato MM:SS o HH:MM:SS."""
    if not seconds or not isinstance(seconds, (int, float)):
        return "--:--"
    m, s = divmod(int(seconds), 60)
    h, m = divmod(m, 60)
    if h > 0:
        return f"{h:02d}:{m:02d}:{s:02d}"
    return f"{m:02d}:{s:02d}"

def is_url(text: str) -> bool:
    """Determina si el texto ingresado es una URL válida HTTP/HTTPS o un término de búsqueda."""
    text = text.strip()
    if not text:
        return False
    if text.lower().startswith("www."):
        text = "https://" + text
    try:
        parsed = urllib.parse.urlparse(text)
        return parsed.scheme.lower() in ("http", "https") and bool(parsed.netloc)
    except Exception:
        return False

def normalize_url(text: str) -> str:
    """Normaliza un enlace agregando https:// si comienza con www."""
    text = text.strip()
    if text.lower().startswith("www."):
        return "https://" + text
    return text

def is_tiktok_url(url: str) -> bool:
    """Comprueba si una URL corresponde a TikTok."""
    u = url.lower()
    return any(d in u for d in ("tiktok.com", "douyin.com", "vm.tiktok.com", "vt.tiktok.com"))

def _convert_with_ffmpeg(input_file: str, output_file: str, codec: str = "mp3", bitrate: str = "320k") -> bool:
    """Convierte audio o video usando FFmpeg directamente."""
    ff = shutil.which("ffmpeg") or (os.path.join(FFMPEG_DIR, "ffmpeg.exe") if FFMPEG_DIR else None)
    if not ff:
        return False
    cmd = [ff, "-y", "-i", input_file]
    if codec == "mp3":
        cmd.extend(["-vn", "-acodec", "libmp3lame", "-b:a", bitrate, output_file])
    elif codec == "wav":
        cmd.extend(["-vn", "-acodec", "pcm_s16le", output_file])
    elif codec == "m4a":
        cmd.extend(["-vn", "-acodec", "aac", "-b:a", "192k", output_file])
    else:
        cmd.extend(["-vn", output_file])

    try:
        flags = getattr(subprocess, 'CREATE_NO_WINDOW', 0) if sys.platform == "win32" else 0
        res = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, creationflags=flags)
        return res.returncode == 0 and os.path.exists(output_file)
    except Exception:
        return False

def _fetch_tiktok_api(url: str) -> Optional[Dict[str, Any]]:
    """Extrae metadatos y enlaces directos HD sin marca de agua de TikTok vía API directa."""
    api_url = 'https://www.tikwm.com/api/'
    data = urllib.parse.urlencode({'url': url, 'hd': 1}).encode('utf-8')
    req = urllib.request.Request(api_url, data=data, headers={
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
        'Accept': 'application/json',
    })
    try:
        with urllib.request.urlopen(req, timeout=12) as resp:
            res = json.loads(resp.read().decode('utf-8'))
        if res.get('code') == 0 and 'data' in res:
            d = res['data']
            video_url = d.get('hdplay') or d.get('play')
            audio_url = d.get('music')
            title = (d.get('title') or f"TikTok {d.get('id', '')}").strip()
            author = d.get('author', {}).get('nickname') or d.get('author', {}).get('unique_id') or 'TikTok Creator'
            thumb = d.get('cover') or d.get('origin_cover') or ''
            duration = int(d.get('duration', 0))
            return {
                'id': str(d.get('id', '')),
                'title': title,
                'uploader': author,
                'duration': duration,
                'thumbnail': thumb,
                'video_url': video_url,
                'audio_url': audio_url,
            }
    except Exception:
        pass
    return None


class DownloaderEngine:
    def __init__(self):
        self._cancel_flags: Dict[str, bool] = {}
        self._lock = threading.Lock()

    def cancel_task(self, task_id: str):
        with self._lock:
            self._cancel_flags[task_id] = True

    def is_cancelled(self, task_id: str) -> bool:
        with self._lock:
            return self._cancel_flags.get(task_id, False)

    def export_browser_cookies_snapshot(self, browser: str = "vivaldi") -> str:
        """
        Extrae las cookies del navegador y las guarda en un archivo local 'saved_cookies.txt'.
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

        try:
            os.chmod(dest_path, 0o600)
        except Exception:
            pass

        return dest_path

    def _base_opts(self, browser: Optional[str] = None, cookiefile: Optional[str] = None, use_cookies: bool = False) -> Dict[str, Any]:
        opts: Dict[str, Any] = {
            'quiet': True,
            'no_warnings': True,
            'nocheckcertificate': False,  # Verificación SSL activa por defecto
            'windowsfilenames': True,
            'js_runtimes': {'node': {}},
        }

        # Soporte de impersonate si curl_cffi está presente
        try:
            from yt_dlp.networking.impersonate import ImpersonateTarget
            opts['impersonate'] = ImpersonateTarget.from_str('chrome')
        except Exception:
            pass

        # Localización de FFmpeg
        ff = FFMPEG_DIR or shutil.which("ffmpeg")
        if ff:
            opts['ffmpeg_location'] = ff if os.path.isdir(ff) else os.path.dirname(ff)

        if use_cookies:
            saved_cookie = os.path.join(os.path.dirname(os.path.abspath(__file__)), "saved_cookies.txt")
            if cookiefile and os.path.exists(cookiefile):
                opts['cookiefile'] = cookiefile
            elif os.path.exists(saved_cookie) and os.path.getsize(saved_cookie) > 0:
                opts['cookiefile'] = saved_cookie
            elif browser and browser.lower() != 'none':
                opts['cookiesfrombrowser'] = (browser.lower(),)

        return opts

    def search_youtube(self, query: str, max_results: int = 8, browser: Optional[str] = None) -> List[Dict[str, Any]]:
        """Busca videos en YouTube directamente mediante conexión limpia nativa."""
        query = query.strip()
        if not query:
            return []

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
        Para TikTok: utiliza el motor directo HD sin restricciones.
        Para otras plataformas: utiliza yt-dlp con fallback seguro.
        """
        url = normalize_url(url)

        # Interceptación prioritaria para TikTok (evita bloqueos de IP y anti-bot)
        if is_tiktok_url(url):
            tt_data = _fetch_tiktok_api(url)
            if tt_data:
                return {
                    'is_playlist': False,
                    'id': tt_data['id'],
                    'title': tt_data['title'],
                    'uploader': tt_data['uploader'],
                    'duration': format_duration(tt_data['duration']),
                    'raw_duration': tt_data['duration'],
                    'thumbnail': tt_data['thumbnail'],
                    'url': url,
                    'available_heights': [1080, 720],
                    'extractor': 'TikTok (HD Directo)',
                    '_tiktok_data': tt_data,
                }

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
            # Si TikTok falló en yt-dlp, reintentar con el motor directo
            if is_tiktok_url(url):
                tt_data = _fetch_tiktok_api(url)
                if tt_data:
                    return {
                        'is_playlist': False,
                        'id': tt_data['id'],
                        'title': tt_data['title'],
                        'uploader': tt_data['uploader'],
                        'duration': format_duration(tt_data['duration']),
                        'raw_duration': tt_data['duration'],
                        'thumbnail': tt_data['thumbnail'],
                        'url': url,
                        'available_heights': [1080, 720],
                        'extractor': 'TikTok (HD Directo)',
                        '_tiktok_data': tt_data,
                    }

            if any(k in err for k in ['sign in', 'age', 'confirm your age', 'private', 'login']):
                ydl_opts_cookies = self._base_opts(browser=browser, cookiefile=cookiefile, use_cookies=True)
                ydl_opts_cookies.update({'extract_flat': True, 'skip_download': True})
                try:
                    with yt_dlp.YoutubeDL(ydl_opts_cookies) as ydl:
                        res = ydl.extract_info(url, download=False)
                except Exception as e2:
                    raise Exception(clean_error_text(str(e2)))
            else:
                raise Exception(clean_error_text(str(e)))

        if not res and is_tiktok_url(url):
            tt_data = _fetch_tiktok_api(url)
            if tt_data:
                return {
                    'is_playlist': False,
                    'id': tt_data['id'],
                    'title': tt_data['title'],
                    'uploader': tt_data['uploader'],
                    'duration': format_duration(tt_data['duration']),
                    'raw_duration': tt_data['duration'],
                    'thumbnail': tt_data['thumbnail'],
                    'url': url,
                    'available_heights': [1080, 720],
                    'extractor': 'TikTok (HD Directo)',
                    '_tiktok_data': tt_data,
                }

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

    def _download_tiktok_stream(
        self,
        task_id: str,
        tt_data: Dict[str, Any],
        format_type: str,
        output_dir: str,
        progress_callback: Optional[Callable[[float, str, str, str], None]] = None,
        status_callback: Optional[Callable[[str], None]] = None,
    ) -> Dict[str, Any]:
        """Descarga directa de video o audio de TikTok sin marca de agua y en alta definición."""
        is_audio = format_type in ['mp3_320', 'mp3_192', 'wav', 'm4a_best']
        stream_url = tt_data.get('audio_url') if (is_audio and tt_data.get('audio_url')) else tt_data.get('video_url')

        if not stream_url:
            raise Exception("No se pudo obtener el enlace de transmisión directo de TikTok.")

        safe_title = sanitize_filename(tt_data.get('title', f"TikTok_{tt_data.get('id', 'video')}"))
        temp_file = os.path.join(output_dir, f".temp_tt_{task_id}.bin")

        req = urllib.request.Request(stream_url, headers={
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
            'Referer': 'https://www.tiktok.com/',
        })

        start_time = time.time()
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                total_bytes = int(resp.headers.get('content-length', 0))
                downloaded_bytes = 0

                with open(temp_file, 'wb') as out_f:
                    while True:
                        if self.is_cancelled(task_id):
                            raise Exception("Descarga cancelada por el usuario.")

                        chunk = resp.read(65536)
                        if not chunk:
                            break
                        out_f.write(chunk)
                        downloaded_bytes += len(chunk)

                        elapsed = max(time.time() - start_time, 0.001)
                        speed_bps = downloaded_bytes / elapsed
                        speed_str = f"{speed_bps / (1024 * 1024):.1f} MB/s"

                        percent = (downloaded_bytes / total_bytes) if total_bytes > 0 else 0.5
                        if total_bytes > 0:
                            rem_bytes = max(total_bytes - downloaded_bytes, 0)
                            rem_sec = int(rem_bytes / max(speed_bps, 1))
                            eta_str = f"{rem_sec}s"
                        else:
                            eta_str = "--"

                        if progress_callback:
                            progress_callback(percent, speed_str, eta_str, f"{int(percent * 100)}%")

            if progress_callback:
                progress_callback(1.0, "", "", "100%")

            # Conversión / Guardado Final
            if is_audio:
                media_type = "audio"
                if format_type == 'mp3_320':
                    format_label = "MP3 (320 kbps HD)"
                    final_path = os.path.join(output_dir, f"{safe_title}.mp3")
                    if status_callback: status_callback("Convirtiendo a MP3...")
                    if not _convert_with_ffmpeg(temp_file, final_path, codec="mp3", bitrate="320k"):
                        shutil.move(temp_file, final_path)
                elif format_type == 'mp3_192':
                    format_label = "MP3 (192 kbps)"
                    final_path = os.path.join(output_dir, f"{safe_title}.mp3")
                    if status_callback: status_callback("Convirtiendo a MP3...")
                    if not _convert_with_ffmpeg(temp_file, final_path, codec="mp3", bitrate="192k"):
                        shutil.move(temp_file, final_path)
                elif format_type == 'wav':
                    format_label = "WAV Sin Compresión"
                    final_path = os.path.join(output_dir, f"{safe_title}.wav")
                    if not _convert_with_ffmpeg(temp_file, final_path, codec="wav"):
                        shutil.move(temp_file, final_path)
                else:
                    format_label = "M4A Original"
                    final_path = os.path.join(output_dir, f"{safe_title}.m4a")
                    shutil.move(temp_file, final_path)
            else:
                media_type = "video"
                format_label = "MP4 HD (Sin Marca)"
                final_path = os.path.join(output_dir, f"{safe_title}.mp4")
                if os.path.exists(final_path):
                    try: os.remove(final_path)
                    except Exception: pass
                shutil.move(temp_file, final_path)

            if os.path.exists(temp_file):
                try: os.remove(temp_file)
                except Exception: pass

            return {
                'title': tt_data.get('title', safe_title),
                'file_path': final_path,
                'format_label': format_label,
                'media_type': media_type,
                'thumbnail_url': tt_data.get('thumbnail', ''),
            }

        finally:
            if os.path.exists(temp_file):
                try: os.remove(temp_file)
                except Exception: pass

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
        url = normalize_url(url)
        with self._lock:
            self._cancel_flags[task_id] = False

        os.makedirs(output_dir, exist_ok=True)

        # Manejo especializado de TikTok
        if is_tiktok_url(url):
            tt_data = _fetch_tiktok_api(url)
            if tt_data:
                return self._download_tiktok_stream(
                    task_id=task_id,
                    tt_data=tt_data,
                    format_type=format_type,
                    output_dir=output_dir,
                    progress_callback=progress_callback,
                    status_callback=status_callback
                )

        # Validación preventiva de FFmpeg
        requires_ffmpeg = format_type in ['mp3_320', 'mp3_192', 'wav', 'mp4_4k', 'mp4_1080', 'mp4_720', 'mp4_480']
        if requires_ffmpeg and not is_ffmpeg_available():
            raise RuntimeError(
                "FFmpeg no está detectado en el sistema.\n"
                "Para convertir audio a MP3/WAV o unir audio y video en alta definición, se requiere FFmpeg.\n"
                "Coloca ffmpeg.exe en la carpeta de la app o instálalo en tu sistema."
            )

        def hook(d):
            if self.is_cancelled(task_id):
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
                    status_callback("Procesando archivo con FFmpeg...")

        out_template = os.path.join(output_dir, '%(title)s.%(ext)s')

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

        ydl_opts_clean = self._base_opts(use_cookies=False)
        ydl_opts_clean.update(format_opts)

        info = None
        try:
            try:
                with yt_dlp.YoutubeDL(ydl_opts_clean) as ydl:
                    info = ydl.extract_info(url, download=True)
            except Exception as e:
                err = str(e).lower()
                if "cancelada por el usuario" in err or self.is_cancelled(task_id):
                    raise Exception("Descarga cancelada por el usuario.")

                if any(k in err for k in ['sign in', 'age', 'confirm your age', 'private', 'login']):
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
                raise Exception("No se pudo completar la descarga del archivo multimedia.")

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

            if not os.path.exists(final_path):
                raise FileNotFoundError(f"El archivo descargado no se pudo encontrar en el disco: {os.path.basename(final_path)}")

            title = info.get('title', os.path.basename(final_path))
            thumbnail = info.get('thumbnail', '')

            return {
                'title': title,
                'file_path': final_path,
                'format_label': format_label,
                'media_type': media_type,
                'thumbnail_url': thumbnail,
            }

        finally:
            with self._lock:
                self._cancel_flags.pop(task_id, None)

            if self.is_cancelled(task_id):
                try:
                    for part in glob.glob(os.path.join(output_dir, "*.part")):
                        if os.path.isfile(part): os.remove(part)
                    for ytdl in glob.glob(os.path.join(output_dir, "*.ytdl")):
                        if os.path.isfile(ytdl): os.remove(ytdl)
                except Exception:
                    pass
