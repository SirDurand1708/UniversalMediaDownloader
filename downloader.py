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
import zipfile
import tempfile
from typing import Callable, Optional, Dict, Any, List
import yt_dlp
from config_manager import get_data_dir

def get_app_dir() -> str:
    if getattr(sys, 'frozen', False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))

def _ensure_ffmpeg() -> Optional[str]:
    """Garantiza que FFmpeg esté en el PATH del proceso, buscando en PATH, registro y carpeta local."""
    found = shutil.which("ffmpeg")
    if found:
        return os.path.dirname(os.path.abspath(found))

    # Buscar en carpeta local del proyecto o subcarpeta 'bin'
    base_dir = get_app_dir()
    local_candidates = [
        base_dir,
        os.path.join(base_dir, "bin"),
        os.path.join(base_dir, "ffmpeg"),
        os.path.dirname(os.path.abspath(__file__)),
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "bin"),
    ]
    if hasattr(sys, '_MEIPASS'):
        local_candidates.extend([
            sys._MEIPASS,
            os.path.join(sys._MEIPASS, "bin"),
        ])
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

_ARIA2C_CACHED_PATH: Optional[str] = None
_ARIA2C_CHECKED: bool = False

def _get_aria2c_path(force_refresh: bool = False) -> Optional[str]:
    """Busca aria2c en el PATH, carpeta del proyecto, subcarpeta bin o rutas comunes del usuario (con caché ultrarrápida)."""
    global _ARIA2C_CACHED_PATH, _ARIA2C_CHECKED
    if _ARIA2C_CHECKED and not force_refresh:
        return _ARIA2C_CACHED_PATH

    _ARIA2C_CHECKED = True
    found = shutil.which("aria2c")
    if found:
        _ARIA2C_CACHED_PATH = found
        return found

    base_dir = get_app_dir()
    candidates = [
        os.path.join(base_dir, "bin", "aria2c.exe"),
        os.path.join(base_dir, "aria2c.exe"),
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "bin", "aria2c.exe"),
        os.path.expanduser(r"~\scoop\shims\aria2c.exe"),
        r"C:\ProgramData\chocolatey\bin\aria2c.exe",
    ]
    if hasattr(sys, '_MEIPASS'):
        candidates.insert(0, os.path.join(sys._MEIPASS, "bin", "aria2c.exe"))

    docs_software = os.path.expanduser(r"~\Documents\Software")
    if os.path.isdir(docs_software):
        try:
            for root, _, files in os.walk(docs_software):
                if "aria2c.exe" in files:
                    candidates.append(os.path.join(root, "aria2c.exe"))
                    break
        except Exception:
            pass

    for c in candidates:
        if os.path.isfile(c):
            aria_dir = os.path.dirname(os.path.abspath(c))
            if aria_dir not in os.environ.get("PATH", ""):
                os.environ["PATH"] = aria_dir + os.pathsep + os.environ.get("PATH", "")
            _ARIA2C_CACHED_PATH = c
            return c

    _ARIA2C_CACHED_PATH = None
    return None

def is_aria2c_available() -> bool:
    """Comprueba si el acelerador de red multihilo aria2c está disponible."""
    return bool(_get_aria2c_path())

def install_aria2(
    status_callback: Optional[Callable[[str], None]] = None
) -> tuple[bool, str]:
    """
    Descarga, extrae y configura automáticamente aria2c.exe en la carpeta bin/ del proyecto.
    Utiliza curl.exe nativo de Windows (o urllib como respaldo) y extrae aria2c.exe
    sin requerir permisos de administrador.
    """
    base_dir = get_app_dir()
    bin_dir = os.path.join(base_dir, "bin")
    os.makedirs(bin_dir, exist_ok=True)
    target_exe = os.path.join(bin_dir, "aria2c.exe")

    url = "https://github.com/aria2/aria2/releases/download/release-1.37.0/aria2-1.37.0-win-64bit-build1.zip"
    temp_zip = os.path.join(tempfile.gettempdir(), f"omnivault_aria2_{int(time.time())}.zip")

    flags = getattr(subprocess, 'CREATE_NO_WINDOW', 0) if sys.platform == "win32" else 0

    try:
        if status_callback:
            status_callback("Conectando con el servidor de Aria2c...")

        # 1. Intentar con curl.exe nativo de Windows
        curl_bin = shutil.which("curl") or r"C:\Windows\System32\curl.exe"
        downloaded = False

        if os.path.isfile(curl_bin):
            if status_callback:
                status_callback("Descargando paquete oficial de Aria2c (64-bit)...")
            cmd = [curl_bin, "-L", "-A", "Mozilla/5.0", "--connect-timeout", "10", "--retry", "3", url, "-o", temp_zip]
            res = subprocess.run(cmd, creationflags=flags, timeout=120)
            if res.returncode == 0 and os.path.isfile(temp_zip) and os.path.getsize(temp_zip) > 500000:
                downloaded = True

        # 2. Fallback con urllib si curl no pudo completarlo
        if not downloaded:
            if status_callback:
                status_callback("Descargando mediante protocolo HTTP alternativo...")
            headers = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=45) as resp:
                with open(temp_zip, "wb") as f_out:
                    shutil.copyfileobj(resp, f_out)
            if os.path.isfile(temp_zip) and os.path.getsize(temp_zip) > 500000:
                downloaded = True

        if not downloaded:
            return False, "No se pudo descargar el paquete de Aria2c. Comprueba tu conexión a internet."

        if status_callback:
            status_callback("Extrayendo aria2c.exe en la carpeta bin/...")

        with zipfile.ZipFile(temp_zip, "r") as zf:
            found_name = None
            for name in zf.namelist():
                if name.lower().endswith("aria2c.exe"):
                    found_name = name
                    break
            if not found_name:
                return False, "El archivo descargado no contiene el binario aria2c.exe esperado."

            with zf.open(found_name) as src, open(target_exe, "wb") as dst:
                shutil.copyfileobj(src, dst)

        if not os.path.isfile(target_exe):
            return False, "No se pudo escribir aria2c.exe en la carpeta bin/."

        # Probar ejecución
        test_run = subprocess.run([target_exe, "--version"], capture_output=True, text=True, creationflags=flags)
        if test_run.returncode != 0:
            return False, "Aria2c se extrajo pero no pudo ser ejecutado en este sistema."

        # Actualizar variable PATH y caché en el proceso actual
        if bin_dir not in os.environ.get("PATH", ""):
            os.environ["PATH"] = bin_dir + os.pathsep + os.environ.get("PATH", "")
        _get_aria2c_path(force_refresh=True)

        first_line = test_run.stdout.splitlines()[0] if test_run.stdout else "Versión 1.37.0"
        return True, f"Aria2c instalado y activo ({first_line})."

    except Exception as e:
        return False, f"Error durante la instalación de Aria2c: {str(e)}"
    finally:
        if os.path.isfile(temp_zip):
            try:
                os.remove(temp_zip)
            except Exception:
                pass

_CURL_CFFI_AVAILABLE: Optional[bool] = None

def is_curl_cffi_available() -> bool:
    """Comprueba si curl_cffi está presente y disponible para impersonación TLS (con caché en memoria)."""
    global _CURL_CFFI_AVAILABLE
    if _CURL_CFFI_AVAILABLE is not None:
        return _CURL_CFFI_AVAILABLE
    try:
        import curl_cffi
        from yt_dlp.networking.impersonate import ImpersonateTarget
        _CURL_CFFI_AVAILABLE = True
    except Exception:
        _CURL_CFFI_AVAILABLE = False
    return _CURL_CFFI_AVAILABLE


_DETECTED_GPU_ACCEL: Optional[str] = None
_GPU_ACCEL_CHECKED: bool = False

def detect_hardware_acceleration() -> Optional[str]:
    """Detecta si el sistema cuenta con GPU para codificación rápida en FFmpeg (NVIDIA, Intel o AMD)."""
    global _DETECTED_GPU_ACCEL, _GPU_ACCEL_CHECKED
    if _GPU_ACCEL_CHECKED:
        return _DETECTED_GPU_ACCEL

    _GPU_ACCEL_CHECKED = True
    ff = shutil.which("ffmpeg") or (os.path.join(FFMPEG_DIR, "ffmpeg.exe") if FFMPEG_DIR else None)
    if not ff:
        return None

    try:
        flags = getattr(subprocess, 'CREATE_NO_WINDOW', 0) if sys.platform == "win32" else 0
        res = subprocess.run([ff, "-hide_banner", "-encoders"], capture_output=True, text=True, creationflags=flags, timeout=3)
        out = res.stdout.lower()
        if "h264_nvenc" in out:
            _DETECTED_GPU_ACCEL = "NVIDIA NVENC"
        elif "h264_amf" in out:
            _DETECTED_GPU_ACCEL = "AMD AMF"
        elif "h264_qsv" in out:
            _DETECTED_GPU_ACCEL = "Intel QuickSync"
    except Exception:
        pass
    return _DETECTED_GPU_ACCEL

def is_browser_running(browser_name: str) -> bool:
    """Comprueba de forma rápida si el proceso del navegador está en ejecución en Windows."""
    browser_procs = {
        'vivaldi': ['vivaldi.exe'],
        'chrome': ['chrome.exe'],
        'edge': ['msedge.exe'],
        'brave': ['brave.exe'],
        'firefox': ['firefox.exe'],
        'opera': ['opera.exe'],
    }
    targets = browser_procs.get(browser_name.lower(), [f"{browser_name.lower()}.exe"])
    if sys.platform == "win32":
        try:
            flags = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
            for exe in targets:
                res = subprocess.run(
                    ["tasklist", "/FI", f"IMAGENAME eq {exe}", "/NH"],
                    capture_output=True,
                    text=True,
                    creationflags=flags
                )
                if exe.lower() in res.stdout.lower():
                    return True
        except Exception:
            pass
    return False

def sanitize_filename(name: str) -> str:
    """Sanea un nombre de archivo eliminando caracteres ilegales en Windows/Linux/macOS."""
    clean = re.sub(r'[\x5c/:\x2a\x3f\x22\x3c\x3e\x7c]', '_', name).strip()
    return clean[:120] if clean else "Media_File"

def clean_error_text(err: str) -> str:
    """Sanea y traduce mensajes técnicos de error a explicaciones claras y amigables."""
    if not err:
        return "Error desconocido"
    clean = re.sub(r'\x1b\[[0-9;]*[a-zA-Z]', '', err)
    clean = re.sub(r'(\[0;[0-9]+m)+', '', clean)
    clean = re.sub(r'ERROR:\s*', '', clean)
    lower = clean.lower()

    if any(k in lower for k in ["sign in to confirm you’re not a bot", "sign in to confirm you're not a bot", "not a bot", "confirm you're not a bot"]):
        return (
            "YouTube requiere verificación anti-bot para este video.\n"
            "Sincroniza tus cookies desde Ajustes (cerrando el navegador unos segundos) o importa un archivo cookies.txt."
        )
    if "could not copy chrome cookie database" in lower or ("permission denied" in lower and "cookies" in lower):
        return (
            "El archivo de sesión del navegador está bloqueado porque el navegador está abierto.\n"
            "Cierra tu navegador durante 3 segundos y presiona 'Sincronizar' nuevamente."
        )
    if "failed to decrypt with dpapi" in lower:
        return (
            "Este navegador tiene sus cookies protegidas por el sistema (App-Bound Encryption).\n"
            "Para sincronizarlo, exporta tus cookies con la extensión 'Get cookies.txt LOCALLY' y cárgalas en Ajustes."
        )
    if "private video" in lower or "este video es privado" in lower:
        return "Este video es privado o no está disponible públicamente."
    if "members-only" in lower or "solo para miembros" in lower:
        return "Este contenido es exclusivo para miembros con suscripción al canal."
    if "confirm your age" in lower or "age restricted" in lower or "requiere confirmación de edad" in lower:
        return "Este video tiene restricción de edad (+18). Requiere una sesión de YouTube iniciada."
    if "ffmpeg no está" in lower or "ffmpeg is not" in lower:
        return "FFmpeg no está disponible en el sistema para la conversión de formatos."
    if "no space left on device" in lower or "espacio insuficiente" in lower:
        return "No hay suficiente espacio disponible en el disco para completar la descarga."

    first_line = clean.split("\n")[0].strip()
    return first_line if first_line else clean.strip()

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

def normalize_audio_file(file_path: str) -> bool:
    """
    Aplica normalización inteligente EBU R128 (-16 LUFS estándar de streaming y Spotify)
    mediante el filtro de audio loudnorm de FFmpeg.
    """
    ff = shutil.which("ffmpeg") or (os.path.join(FFMPEG_DIR, "ffmpeg.exe") if FFMPEG_DIR else None)
    if not ff or not os.path.isfile(file_path):
        return False

    base, ext = os.path.splitext(file_path)
    tmp_out = f"{base}_norm{ext}"
    if os.path.exists(tmp_out):
        try: os.remove(tmp_out)
        except Exception: pass

    cmd = [ff, "-y", "-i", file_path, "-filter:a", "loudnorm=I=-16:TP=-1.5:LRA=11"]
    ext_l = ext.lower()
    if ext_l == ".mp3":
        cmd.extend(["-vn", "-c:a", "libmp3lame", "-b:a", "320k", tmp_out])
    elif ext_l == ".wav":
        cmd.extend(["-vn", "-c:a", "pcm_s16le", tmp_out])
    elif ext_l == ".m4a":
        cmd.extend(["-vn", "-c:a", "aac", "-b:a", "256k", tmp_out])
    elif ext_l in [".mp4", ".mkv", ".webm"]:
        cmd.extend(["-c:v", "copy", "-c:a", "aac", "-b:a", "192k", tmp_out])
    else:
        cmd.extend(["-vn", tmp_out])

    flags = getattr(subprocess, 'CREATE_NO_WINDOW', 0) if sys.platform == "win32" else 0
    try:
        res = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, creationflags=flags)
        if res.returncode == 0 and os.path.isfile(tmp_out) and os.path.getsize(tmp_out) > 1024:
            os.replace(tmp_out, file_path)
            return True
        elif os.path.exists(tmp_out):
            try: os.remove(tmp_out)
            except Exception: pass
    except Exception:
        if os.path.exists(tmp_out):
            try: os.remove(tmp_out)
            except Exception: pass
    return False

def embed_id3_metadata(mp3_path: str, title: str, artist: str = "", album: str = "", thumbnail_url: str = ""):
    """Incrusta título, artista y carátula del álbum en archivos MP3 usando mutagen."""
    if not mp3_path or not os.path.isfile(mp3_path) or not mp3_path.lower().endswith(".mp3"):
        return
    try:
        from mutagen.id3 import ID3, APIC, TIT2, TPE1, TALB, ID3NoHeaderError
        try:
            audio = ID3(mp3_path)
        except ID3NoHeaderError:
            audio = ID3()

        if title:
            audio["TIT2"] = TIT2(encoding=3, text=title)
        if artist:
            audio["TPE1"] = TPE1(encoding=3, text=artist)
        if album:
            audio["TALB"] = TALB(encoding=3, text=album)

        if thumbnail_url and is_url(thumbnail_url):
            try:
                headers = {'User-Agent': 'Mozilla/5.0'}
                req = urllib.request.Request(thumbnail_url, headers=headers)
                with urllib.request.urlopen(req, timeout=6) as resp:
                    img_data = resp.read(5 * 1024 * 1024)
                if img_data:
                    audio["APIC"] = APIC(
                        encoding=3,
                        mime="image/jpeg",
                        type=3,  # Front cover
                        desc="Cover",
                        data=img_data
                    )
            except Exception:
                pass

        audio.save(mp3_path)
    except Exception:
        pass

def parse_timestamp_str(s: Optional[str]) -> Optional[float]:
    """Convierte cadenas como '01:23:45', '02:30', '75', '1m30s' a segundos flotantes."""
    if not s:
        return None
    s = s.strip()
    if not s:
        return None
    if ":" in s:
        parts = s.split(":")
        try:
            if len(parts) == 2:
                return int(parts[0]) * 60 + float(parts[1])
            elif len(parts) == 3:
                return int(parts[0]) * 3600 + int(parts[1]) * 60 + float(parts[2])
        except ValueError:
            return None
    m = re.match(r'(?:(\d+)m)?\s*(?:(\d+)s)?', s)
    if m and (m.group(1) or m.group(2)):
        mins = int(m.group(1) or 0)
        secs = int(m.group(2) or 0)
        return float(mins * 60 + secs)
    try:
        return float(s)
    except ValueError:
        return None

def _trim_file_with_ffmpeg(input_file: str, start_sec: Optional[float], end_sec: Optional[float]) -> Optional[str]:
    """Recorta un archivo multimedia con precisión usando FFmpeg."""
    ff = shutil.which("ffmpeg") or (os.path.join(FFMPEG_DIR, "ffmpeg.exe") if FFMPEG_DIR else None)
    if not ff or not os.path.isfile(input_file):
        return None

    # Validar rangos
    if start_sec is not None and start_sec < 0:
        start_sec = 0.0
    if end_sec is not None and start_sec is not None and end_sec <= start_sec:
        end_sec = None

    base, ext = os.path.splitext(input_file)
    output_file = f"{base}_clip{ext}"
    if os.path.exists(output_file):
        try: os.remove(output_file)
        except Exception: pass

    cmd = [ff, "-y"]
    if start_sec is not None and start_sec > 0:
        cmd.extend(["-ss", str(start_sec)])
    if end_sec is not None and end_sec > 0:
        if start_sec is not None and start_sec > 0:
            cmd.extend(["-to", str(end_sec)])
        else:
            cmd.extend(["-t", str(end_sec)])
    cmd.extend(["-i", input_file])

    flags = getattr(subprocess, 'CREATE_NO_WINDOW', 0) if sys.platform == "win32" else 0

    # Para videos, intentar -c copy (ultrarrápido e instantáneo sin pérdida)
    if ext.lower() in [".mp4", ".mkv", ".webm"]:
        cmd_copy = cmd + ["-c", "copy", output_file]
        try:
            res = subprocess.run(cmd_copy, capture_output=True, creationflags=flags)
            if res.returncode == 0 and os.path.isfile(output_file) and os.path.getsize(output_file) > 1024:
                return output_file
        except Exception:
            pass

    # Si -c copy falla o es audio, recodificar de forma óptima
    if ext.lower() == ".mp3":
        cmd_rec = cmd + ["-vn", "-c:a", "libmp3lame", "-b:a", "320k", output_file]
    elif ext.lower() == ".wav":
        cmd_rec = cmd + ["-vn", "-c:a", "pcm_s16le", output_file]
    elif ext.lower() == ".m4a":
        cmd_rec = cmd + ["-vn", "-c:a", "aac", "-b:a", "256k", output_file]
    else:
        cmd_rec = cmd + ["-c:v", "libx264", "-preset", "fast", "-c:a", "aac", output_file]

    try:
        res = subprocess.run(cmd_rec, capture_output=True, creationflags=flags)
        if res.returncode == 0 and os.path.isfile(output_file):
            return output_file
    except Exception:
        pass
    return None

def format_bytes_estimate(b: float) -> str:
    """Convierte bytes a representación legible de tamaño estimado."""
    if b <= 0:
        return "--"
    mb = b / (1024 * 1024)
    if mb < 1024:
        return f"~{mb:.1f} MB"
    return f"~{mb / 1024:.2f} GB"

def estimate_sizes_for_video(duration: float, formats: list) -> Dict[str, str]:
    """Calcula estimaciones del tamaño aproximado del archivo para cada formato disponible."""
    if not duration or duration <= 0:
        return {}
    res = {
        'mp3_320': format_bytes_estimate(duration * 320 * 1000 / 8),
        'mp3_192': format_bytes_estimate(duration * 192 * 1000 / 8),
        'm4a_best': format_bytes_estimate(duration * 256 * 1000 / 8),
        'wav': format_bytes_estimate(duration * 1411 * 1000 / 8),
    }
    targets = {
        'mp4_4k': (2160, 22_000_000),
        'mp4_1080': (1080, 4_800_000),
        'mp4_720': (720, 2_400_000),
        'mp4_480': (480, 1_100_000),
    }
    audio_extra = duration * 192 * 1000 / 8
    for key, (h_target, default_bps) in targets.items():
        found_size = 0
        if formats:
            for f in formats:
                if f.get('height') == h_target:
                    sz = f.get('filesize') or f.get('filesize_approx')
                    if sz:
                        found_size = sz + audio_extra
                        break
        if not found_size:
            found_size = (duration * default_bps / 8) + audio_extra
        res[key] = format_bytes_estimate(found_size)
    return res

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
    def __init__(self, config=None):
        self._cancel_flags: Dict[str, bool] = {}
        self._lock = threading.Lock()
        self.config = config

    def cancel_task(self, task_id: str):
        with self._lock:
            self._cancel_flags[task_id] = True

    def is_cancelled(self, task_id: str) -> bool:
        with self._lock:
            return self._cancel_flags.get(task_id, False)

    def export_browser_cookies_snapshot(self, browser: str = "vivaldi") -> str:
        """
        Extrae las cookies del navegador especificado y las guarda en un archivo local 'saved_cookies.txt'.
        """
        dest_path = os.path.join(get_data_dir(), "saved_cookies.txt")
        ydl_opts = {
            'quiet': True,
            'no_warnings': True,
            'cookiesfrombrowser': (browser.lower(),),
        }
        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                jar = ydl.cookiejar
                jar.save(dest_path, ignore_discard=True, ignore_expires=True)
        except Exception as e:
            err = str(e).lower()
            if "could not copy chrome cookie database" in err or "permission denied" in err:
                raise RuntimeError(
                    f"El navegador {browser.capitalize()} está actualmente abierto.\n\n"
                    f"Windows no permite leer las cookies mientras el navegador está en ejecución.\n"
                    f"Por favor cierra {browser.capitalize()} durante 3 segundos y vuelve a presionar 'Sincronizar'."
                )
            elif "failed to decrypt with dpapi" in err:
                raise RuntimeError(
                    f"{browser.capitalize()} utiliza el cifrado protegido de Windows (App-Bound Encryption).\n\n"
                    f"Para sincronizarlo, exporta tus cookies con la extensión 'Get cookies.txt LOCALLY' "
                    f"en tu navegador y cárgalas con el botón '📄 Cargar cookies.txt'."
                )
            else:
                raise RuntimeError(f"Error al sincronizar cookies de {browser}: {clean_error_text(str(e))}")

        try:
            os.chmod(dest_path, 0o600)
        except Exception:
            pass

        return dest_path

    def test_youtube_auth(self) -> Dict[str, Any]:
        """Comprueba si las cookies guardadas permiten acceso autenticado a YouTube."""
        saved_cookie = os.path.join(get_data_dir(), "saved_cookies.txt")
        if not os.path.exists(saved_cookie) or os.path.getsize(saved_cookie) == 0:
            return {
                'ok': False,
                'status': 'anonymous',
                'message': 'No hay archivo de cookies guardado (modo anónimo activo).'
            }

        try:
            import http.cookiejar
            jar = http.cookiejar.MozillaCookieJar(saved_cookie)
            jar.load(ignore_discard=True, ignore_expires=True)
            yt_cookies = [c for c in jar if 'youtube.com' in c.domain]
            if not yt_cookies:
                return {
                    'ok': False,
                    'status': 'empty',
                    'message': 'El archivo saved_cookies.txt no contiene cookies de youtube.com.'
                }

            auth_names = ['LOGIN_INFO', 'SAPISID', '__Secure-3PAPISID', 'SID', '__Secure-3PSID']
            found_auth = [c.name for c in yt_cookies if c.name in auth_names]

            node_path = shutil.which("node") or r"C:\Program Files\nodejs\node.exe"
            js_run = {'node': {'path': node_path}} if (node_path and os.path.isfile(node_path)) else {'node': {}}
            opts = {
                'cookiefile': saved_cookie,
                'quiet': True,
                'no_warnings': True,
                'skip_download': True,
                'js_runtimes': js_run,
            }
            with yt_dlp.YoutubeDL(opts) as ydl:
                ydl.extract_info("https://www.youtube.com/feed/history", download=False)

            return {
                'ok': True,
                'status': 'authenticated',
                'message': f'Sesión de YouTube válida y activa ({len(yt_cookies)} cookies, tokens: {", ".join(set(found_auth))}).'
            }
        except Exception as e:
            err = str(e).lower()
            if "sign in" in err or "login" in err:
                return {
                    'ok': False,
                    'status': 'expired',
                    'message': 'Las cookies guardadas han expirado. Es necesario volver a sincronizarlas desde el navegador.'
                }
            return {
                'ok': False,
                'status': 'error',
                'message': f'No se pudo verificar la sesión: {clean_error_text(str(e))}'
            }

    def _base_opts(self, browser: Optional[str] = None, cookiefile: Optional[str] = None, use_cookies: bool = False, use_aria2c: bool = False) -> Dict[str, Any]:
        node_path = shutil.which("node") or r"C:\Program Files\nodejs\node.exe"
        js_conf = {'node': {'path': node_path}} if (node_path and os.path.isfile(node_path)) else {'node': {}}

        opts: Dict[str, Any] = {
            'quiet': True,
            'no_warnings': True,
            'nocheckcertificate': False,  # Verificación SSL activa por defecto
            'windowsfilenames': True,
            'js_runtimes': js_conf,
            'concurrent_fragment_downloads': 5,
            'buffersize': 1048576,
            'http_chunk_size': 10485760,
            'retries': 5,
            'fragment_retries': 5,
            'socket_timeout': 15,
        }

        # Límite de velocidad de descarga configurable (Bandwidth Throttling)
        rate_limit_kb = 0
        if hasattr(self, 'config') and self.config:
            try:
                rate_limit_kb = int(self.config.get("max_download_rate", 0) or 0)
            except Exception:
                rate_limit_kb = 0

        if rate_limit_kb > 0:
            opts['ratelimit'] = rate_limit_kb * 1024  # Bytes por segundo

        # Aceleración de red con aria2c si está disponible
        if use_aria2c and is_aria2c_available():
            aria_path = _get_aria2c_path()
            opts['external_downloader'] = aria_path or 'aria2c'
            aria_args = [
                '-s', '16',
                '-x', '16',
                '-k', '1M',
                '-j', '4',
                '--min-split-size=1M',
                '--file-allocation=none',
                '--max-connection-per-server=16',
                '--summary-interval=0'
            ]
            if rate_limit_kb > 0:
                aria_args.append(f'--max-download-limit={rate_limit_kb}K')
            opts['external_downloader_args'] = {'aria2c': aria_args}

        # Soporte de impersonate solo si curl_cffi está realmente instalado y operativo
        if is_curl_cffi_available():
            try:
                from yt_dlp.networking.impersonate import ImpersonateTarget
                opts['impersonate'] = ImpersonateTarget.from_str('chrome')
            except Exception:
                opts.pop('impersonate', None)

        # Localización de FFmpeg
        ff = FFMPEG_DIR or shutil.which("ffmpeg")
        if ff:
            opts['ffmpeg_location'] = ff if os.path.isdir(ff) else os.path.dirname(ff)

        if use_cookies:
            saved_cookie = os.path.join(get_data_dir(), "saved_cookies.txt")
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
            if 'impersonate target' in str(e).lower() and 'impersonate' in ydl_opts:
                ydl_opts.pop('impersonate', None)
                try:
                    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                        data = ydl.extract_info(search_query, download=False)
                except Exception as e2:
                    raise Exception(clean_error_text(str(e2)))
            else:
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
                tt_dur = tt_data.get('duration', 0)
                return {
                    'is_playlist': False,
                    'id': tt_data['id'],
                    'title': tt_data['title'],
                    'uploader': tt_data['uploader'],
                    'duration': format_duration(tt_dur),
                    'raw_duration': tt_dur,
                    'thumbnail': tt_data['thumbnail'],
                    'url': url,
                    'available_heights': [1080, 720],
                    'extractor': 'TikTok (HD Directo)',
                    '_tiktok_data': tt_data,
                    'estimated_sizes': estimate_sizes_for_video(tt_dur, []),
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
            if 'impersonate target' in err and 'impersonate' in ydl_opts:
                ydl_opts.pop('impersonate', None)
                try:
                    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                        res = ydl.extract_info(url, download=False)
                except Exception as retry_e:
                    e = retry_e
                    err = str(e).lower()
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

            if any(k in err for k in ['sign in', 'age', 'confirm your age', 'private', 'login', 'bot', 'confirm you']):
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
                tt_dur = tt_data.get('duration', 0)
                return {
                    'is_playlist': False,
                    'id': tt_data['id'],
                    'title': tt_data['title'],
                    'uploader': tt_data['uploader'],
                    'duration': format_duration(tt_dur),
                    'raw_duration': tt_dur,
                    'thumbnail': tt_data['thumbnail'],
                    'url': url,
                    'available_heights': [1080, 720],
                    'extractor': 'TikTok (HD Directo)',
                    '_tiktok_data': tt_data,
                    'estimated_sizes': estimate_sizes_for_video(tt_dur, []),
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

        raw_dur = res.get('duration', 0)
        sizes = estimate_sizes_for_video(raw_dur, formats)

        return {
            'is_playlist': False,
            'id': v_id,
            'title': res.get('title', 'Sin título'),
            'uploader': res.get('uploader') or res.get('channel') or 'Desconocido',
            'duration': format_duration(raw_dur),
            'raw_duration': raw_dur,
            'thumbnail': thumb,
            'url': url,
            'available_heights': sorted(list(heights), reverse=True),
            'extractor': res.get('extractor_key', 'Universal'),
            'estimated_sizes': sizes,
        }

    def _download_tiktok_stream(
        self,
        task_id: str,
        tt_data: Dict[str, Any],
        format_type: str,
        output_dir: str,
        progress_callback: Optional[Callable[[float, str, str, str], None]] = None,
        status_callback: Optional[Callable[[str], None]] = None,
        clip_start: Optional[str] = None,
        clip_end: Optional[str] = None,
        normalize_audio: bool = False,
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
        last_cb_time = 0.0
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                total_bytes = int(resp.headers.get('content-length', 0))
                downloaded_bytes = 0

                with open(temp_file, 'wb') as out_f:
                    while True:
                        if self.is_cancelled(task_id):
                            raise Exception("Descarga cancelada por el usuario.")

                        chunk = resp.read(262144)  # Buffer de 256KB para máxima tasa de transferencia
                        if not chunk:
                            break
                        out_f.write(chunk)
                        downloaded_bytes += len(chunk)

                        now = time.monotonic()
                        if progress_callback and (now - last_cb_time >= 0.08 or (total_bytes > 0 and downloaded_bytes >= total_bytes)):
                            last_cb_time = now
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

            # Recorte de tiempo si fue solicitado
            start_sec = parse_timestamp_str(clip_start)
            end_sec = parse_timestamp_str(clip_end)
            if (start_sec is not None and start_sec > 0) or (end_sec is not None and end_sec > 0):
                if status_callback: status_callback("Recortando fragmento con FFmpeg...")
                trimmed = _trim_file_with_ffmpeg(final_path, start_sec, end_sec)
                if trimmed and os.path.isfile(trimmed):
                    try: os.remove(final_path)
                    except Exception: pass
                    final_path = trimmed

            if normalize_audio:
                if status_callback: status_callback("Normalizando audio EBU R128...")
                normalize_audio_file(final_path)

            if final_path.lower().endswith(".mp3"):
                embed_id3_metadata(
                    final_path,
                    title=tt_data.get('title', safe_title),
                    artist=tt_data.get('uploader', ''),
                    album="OmniVault TikTok",
                    thumbnail_url=tt_data.get('thumbnail', '')
                )

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
        download_subtitles: bool = False,
        use_aria2c: bool = True,
        clip_start: Optional[str] = None,
        clip_end: Optional[str] = None,
        normalize_audio: bool = False,
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
                    status_callback=status_callback,
                    clip_start=clip_start,
                    clip_end=clip_end,
                    normalize_audio=normalize_audio,
                )

        # Validación preventiva de FFmpeg
        requires_ffmpeg = format_type in ['mp3_320', 'mp3_192', 'wav', 'mp4_4k', 'mp4_1080', 'mp4_720', 'mp4_480']
        if requires_ffmpeg and not is_ffmpeg_available():
            raise RuntimeError(
                "FFmpeg no está detectado en el sistema.\n"
                "Para convertir audio a MP3/WAV o unir audio y video en alta definición, se requiere FFmpeg.\n"
                "Coloca ffmpeg.exe en la carpeta de la app o instálalo en tu sistema."
            )

        last_hook_time = 0.0

        def hook(d):
            nonlocal last_hook_time
            if self.is_cancelled(task_id):
                raise Exception("Descarga cancelada por el usuario.")

            status = d.get('status')
            if status == 'downloading':
                now = time.monotonic()
                total_bytes = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
                downloaded_bytes = d.get('downloaded_bytes', 0)
                is_done = total_bytes > 0 and downloaded_bytes >= total_bytes

                if not is_done and (now - last_hook_time < 0.075):
                    return
                last_hook_time = now

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

        if download_subtitles:
            format_opts.update({
                'writesubtitles': True,
                'writeautomaticsub': True,
                'subtitleslangs': ['es', 'en'],
                'subtitlesformat': 'srt',
            })
            if media_type == 'video':
                format_opts.setdefault('postprocessors', []).append({
                    'key': 'FFmpegEmbedSubtitle',
                    'already_have_subtitle': False
                })

        ydl_opts_clean = self._base_opts(use_cookies=False, use_aria2c=use_aria2c)
        ydl_opts_clean.update(format_opts)

        info = None
        try:
            try:
                with yt_dlp.YoutubeDL(ydl_opts_clean) as ydl:
                    info = ydl.extract_info(url, download=True)
            except Exception as e:
                err = str(e).lower()
                if 'impersonate target' in err and 'impersonate' in ydl_opts_clean:
                    ydl_opts_clean.pop('impersonate', None)
                    try:
                        with yt_dlp.YoutubeDL(ydl_opts_clean) as ydl:
                            info = ydl.extract_info(url, download=True)
                    except Exception as retry_e:
                        e = retry_e
                        err = str(e).lower()
                err = str(e).lower()
                if "cancelada por el usuario" in err or self.is_cancelled(task_id):
                    raise Exception("Descarga cancelada por el usuario.")

                if any(k in err for k in ['sign in', 'age', 'confirm your age', 'private', 'login', 'bot', 'confirm you']):
                    ydl_opts_cookies = self._base_opts(browser=browser, cookiefile=cookiefile, use_cookies=True, use_aria2c=use_aria2c)
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

            # Recorte de tiempo si fue solicitado
            start_sec = parse_timestamp_str(clip_start)
            end_sec = parse_timestamp_str(clip_end)
            if (start_sec is not None and start_sec > 0) or (end_sec is not None and end_sec > 0):
                if status_callback:
                    status_callback("Recortando fragmento con FFmpeg...")
                trimmed = _trim_file_with_ffmpeg(final_path, start_sec, end_sec)
                if trimmed and os.path.isfile(trimmed):
                    try: os.remove(final_path)
                    except Exception: pass
                    final_path = trimmed

            if normalize_audio:
                if status_callback:
                    status_callback("Normalizando audio EBU R128...")
                normalize_audio_file(final_path)

            if final_path.lower().endswith(".mp3"):
                embed_id3_metadata(
                    final_path,
                    title=title,
                    artist=info.get('uploader') or info.get('channel') or '',
                    album="OmniVault",
                    thumbnail_url=thumbnail
                )

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
