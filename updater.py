"""
Módulo de Actualización Automática Oficial (In-App Auto-Updater) para OmniVault Desktop.
Verifica nuevas versiones oficiales en GitHub y permite la actualización con 1 solo clic
tanto para la versión instalada como para la versión portátil.
"""

import os
import sys
import json
import re
import urllib.request
import urllib.error
import urllib.parse
import threading
import subprocess

try:
    from packaging import version
except ImportError:
    version = None

def _load_local_version(fallback: str = "2.5.55") -> str:
    """Lee la versión local desde version.json empaquetado junto al ejecutable o en la raíz del proyecto."""
    candidates = []
    if getattr(sys, 'frozen', False):
        candidates.append(os.path.join(os.path.dirname(os.path.abspath(sys.executable)), "version.json"))
    candidates.append(os.path.join(os.path.dirname(os.path.abspath(__file__)), "version.json"))
    for path in candidates:
        if os.path.isfile(path):
            try:
                with open(path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                win_ver = (data.get("windows") or {}).get("version") or data.get("version")
                if isinstance(win_ver, str) and win_ver.strip():
                    return win_ver.lstrip("v").strip()
            except Exception:
                pass
    return fallback

CURRENT_VERSION = _load_local_version()
VERSION_INFO_URL = "https://raw.githubusercontent.com/SirDurand1708/UniversalMediaDownloader/main/version.json"

def is_newer_version(remote_str: str, local_str: str) -> bool:
    """Compara si la versión remota es estrictamente superior a la versión local."""
    try:
        r = remote_str.lstrip("v").strip()
        l = local_str.lstrip("v").strip()
        if version is not None:
            return version.parse(r) > version.parse(l)
    except Exception:
        pass
    # Fallback semver simple
    try:
        r_parts = [int(p) for p in remote_str.lstrip("v").split(".") if p.isdigit()]
        l_parts = [int(p) for p in local_str.lstrip("v").split(".") if p.isdigit()]
        return r_parts > l_parts
    except Exception:
        return False

def get_installation_type() -> str:
    """
    Determina si la instancia actual es 'installed' (instalador Inno Setup con unins000.exe)
    o 'portable' (zip descomprimido o ejecutable autónomo sin instalador registrado).
    """
    is_frozen = getattr(sys, 'frozen', False)
    if not is_frozen:
        return "development"
    exe_dir = os.path.dirname(os.path.abspath(sys.executable))
    has_uninstaller = os.path.isfile(os.path.join(exe_dir, "unins000.exe"))
    exe_lower = sys.executable.lower()
    if (has_uninstaller 
        or "program files" in exe_lower 
        or r"programs\omnivault" in exe_lower 
        or r"appdata\local\programs" in exe_lower):
        return "installed"
    return "portable"

def evaluate_update_availability(data: dict, current_version: str = CURRENT_VERSION, install_type: str = None) -> tuple[bool, dict]:
    """
    Evalúa estrictamente si los datos de version.json contienen una actualización REAL
    y específica para Windows PC, evitando alertas falsas cuando la actualización
    fue publicada exclusivamente para Android (APK).

    Retorna:
        (has_update: bool, effective_data: dict)
    """
    if not isinstance(data, dict):
        return False, {}

    if install_type is None:
        install_type = get_installation_type()

    # 1. Obtener la sección específica de Windows
    windows_info = data.get("windows")
    if isinstance(windows_info, dict) and windows_info.get("version"):
        remote_win_version = str(windows_info.get("version", "")).strip()
        win_changelog = windows_info.get("changelog")
        win_title = windows_info.get("title")
    else:
        # Si no existe nodo específico 'windows', se toma la versión raíz
        remote_win_version = str(data.get("version", "")).strip()
        win_changelog = None
        win_title = None

    if not remote_win_version:
        return False, {}

    # 2. Comprobación estricta de versión para Windows
    if not is_newer_version(remote_win_version, current_version):
        return False, {}

    # 3. Comprobar que existan URLs de descarga de paquetes para Windows
    setup_url = ""
    portable_url = ""
    if isinstance(windows_info, dict):
        setup_url = str(windows_info.get("setup_url", "") or "").strip()
        portable_url = str(windows_info.get("portable_url", "") or "").strip()

    # Si hay sección windows pero ambas URLs están vacías, este release no contiene binarios para PC
    if isinstance(windows_info, dict) and not setup_url and not portable_url:
        return False, {}

    is_installed = (install_type == "installed")
    target_url = setup_url if (is_installed or not portable_url) else (portable_url or setup_url)

    effective_data = dict(data)
    effective_data["version"] = remote_win_version
    if win_changelog:
        effective_data["changelog"] = win_changelog
    if win_title:
        effective_data["title"] = win_title
    effective_data["target_url"] = target_url
    effective_data["is_installed_target"] = is_installed
    effective_data["setup_url"] = setup_url
    effective_data["portable_url"] = portable_url

    return True, effective_data

_TRUSTED_RELEASE_HOSTS = {
    "github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
    "github-releases.githubusercontent.com",
}

def _is_trusted_release_url(url: str) -> bool:
    try:
        parsed = urllib.parse.urlparse((url or "").strip())
        return parsed.scheme == "https" and (parsed.hostname or "").lower() in _TRUSTED_RELEASE_HOSTS
    except Exception:
        return False

def find_release_asset_url(version_tag: str, is_installer: bool, direct_url: str = "") -> tuple[bool, str]:
    """
    Comprueba si existe un binario real (.exe o .zip) para Windows en el release de GitHub.
    Retorna (found: bool, url: str).
    """
    # 1. Probar URL directa por HEAD solo si pertenece a GitHub HTTPS
    if direct_url and _is_trusted_release_url(direct_url):
        try:
            req = urllib.request.Request(direct_url, headers={"User-Agent": "Mozilla/5.0 OmniVault"}, method="HEAD")
            with urllib.request.urlopen(req, timeout=8) as resp:
                if resp.status == 200:
                    return True, direct_url
        except Exception:
            pass

    # 2. Consultar API de GitHub Releases para la versión solicitada
    clean_tag = version_tag.lstrip("v").strip()
    api_url = f"https://api.github.com/repos/SirDurand1708/UniversalMediaDownloader/releases/tags/v{clean_tag}"
    try:
        req = urllib.request.Request(api_url, headers={
            "User-Agent": "Mozilla/5.0 OmniVault",
            "Accept": "application/vnd.github.v3+json"
        })
        with urllib.request.urlopen(req, timeout=8) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            assets = data.get("assets", [])
            for asset in assets:
                name = asset.get("name", "").lower()
                b_url = asset.get("browser_download_url", "")
                if is_installer and name.endswith(".exe") and ("setup" in name or "installer" in name):
                    return True, b_url
                elif not is_installer and name.endswith(".zip") and "portable" in name:
                    return True, b_url
            for asset in assets:
                name = asset.get("name", "").lower()
                b_url = asset.get("browser_download_url", "")
                if is_installer and name.endswith(".exe"):
                    return True, b_url
                elif not is_installer and name.endswith(".zip"):
                    return True, b_url
    except Exception:
        pass

    return False, ""

def resolve_download_url(download_url: str, is_installer: bool, remote_version: str = None) -> str:
    """
    Verifica la accesibilidad de download_url. Si falla con HTTP 404 o nombre desalineado,
    consulta la API de GitHub Releases para obtener la URL real del asset (.exe o .zip).
    """
    # 1. Probar la URL directa inicial
    try:
        req = urllib.request.Request(download_url, headers={"User-Agent": "Mozilla/5.0 OmniVault"}, method="HEAD")
        with urllib.request.urlopen(req, timeout=10) as resp:
            if resp.status == 200:
                return download_url
    except Exception:
        pass

    # 2. Consultar la API oficial de GitHub Releases
    tags_to_check = []
    if remote_version:
        v = remote_version.lstrip("v").strip()
        tags_to_check.append(f"https://api.github.com/repos/SirDurand1708/UniversalMediaDownloader/releases/tags/v{v}")
    tags_to_check.append("https://api.github.com/repos/SirDurand1708/UniversalMediaDownloader/releases/latest")

    for api_url in tags_to_check:
        try:
            req = urllib.request.Request(api_url, headers={
                "User-Agent": "Mozilla/5.0 OmniVault",
                "Accept": "application/vnd.github.v3+json"
            })
            with urllib.request.urlopen(req, timeout=10) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                assets = data.get("assets", [])
                for asset in assets:
                    name = asset.get("name", "").lower()
                    b_url = asset.get("browser_download_url", "")
                    if is_installer and name.endswith(".exe") and ("setup" in name or "installer" in name):
                        return b_url
                    elif not is_installer and name.endswith(".zip") and "portable" in name:
                        return b_url

                # Fallback dentro de la release: cualquier .exe o .zip
                for asset in assets:
                    name = asset.get("name", "").lower()
                    b_url = asset.get("browser_download_url", "")
                    if is_installer and name.endswith(".exe"):
                        return b_url
                    elif not is_installer and name.endswith(".zip"):
                        return b_url
        except Exception:
            continue

    # 3. Fallback heurístico si contiene versión
    if download_url and "-v" in download_url:
        candidate = re.sub(r"-v[\d\.]+-", "-v2.0.0-", download_url)
        if candidate != download_url:
            return candidate

    return download_url

def check_for_updates_async(callback, verify_asset_exists: bool = True):
    """
    Consulta en segundo plano la existencia de una nueva versión específica para Windows.
    callback(has_update: bool, update_data: dict, error: str)
    """
    def _worker():
        try:
            req = urllib.request.Request(
                VERSION_INFO_URL,
                headers={"User-Agent": "OmniVault-Desktop-Updater"}
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
                data = json.loads(resp.read().decode("utf-8"))

            has_update, effective_data = evaluate_update_availability(data)

            if has_update and verify_asset_exists:
                target_url = effective_data.get("target_url", "")
                is_inst = effective_data.get("is_installed_target", True)
                win_ver = effective_data.get("version", CURRENT_VERSION)
                found, resolved = find_release_asset_url(win_ver, is_installer=is_inst, direct_url=target_url)
                if found and resolved:
                    effective_data["resolved_url"] = resolved
                else:
                    # Si no existe un archivo binario para Windows en el release de GitHub,
                    # se descarta la alerta para no mostrar actualización falsa.
                    has_update = False

            callback(has_update, effective_data, None)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                callback(False, {}, "El repositorio en GitHub es Privado 🔒. Para activar las actualizaciones públicas, cámbialo a Público en Settings de GitHub.")
            else:
                callback(False, {}, f"Servidor respondió con código HTTP {e.code}")
        except Exception as e:
            callback(False, {}, str(e))

    thread = threading.Thread(target=_worker, daemon=True)
    thread.start()


def get_desktop_diagnostics(data_dir: str, ffmpeg_ok: bool, aria_ok: bool) -> str:
    """Genera un reporte técnico resumido para diagnóstico y soporte."""
    import platform
    ytdlp_ver = "unknown"
    try:
        import yt_dlp
        ytdlp_ver = getattr(yt_dlp.version, "__version__", "unknown")
    except Exception:
        pass
    lines = [
        f"App: OmniVault Desktop v{CURRENT_VERSION}",
        f"Modo: {get_installation_type()}",
        f"SO: {platform.system()} {platform.release()} ({platform.machine()})",
        f"Python: {sys.version.split()[0]}",
        f"yt-dlp: {ytdlp_ver}",
        f"DataDir: {data_dir}",
        f"FFmpeg: {'OK' if ffmpeg_ok else 'NO'}",
        f"Aria2c: {'OK' if aria_ok else 'NO'}",
    ]
    crash_log = os.path.join(data_dir, "crash_log.txt")
    if os.path.isfile(crash_log):
        try:
            with open(crash_log, "r", encoding="utf-8", errors="ignore") as f:
                tail = f.readlines()[-12:]
            if tail:
                lines.append("--- Último crash_log.txt ---")
                lines.extend(line.rstrip("\r\n") for line in tail)
        except Exception:
            pass
    return "\n".join(lines)

def verify_file_sha256(filepath: str, expected_hex: str) -> bool:
    """Compara el SHA-256 real de filepath contra expected_hex (insensible a mayúsculas)."""
    import hashlib
    sha256 = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            sha256.update(chunk)
    return sha256.hexdigest().lower() == expected_hex.strip().lower()


def verify_release_checksum(dest_file: str, target_url: str) -> tuple[bool, str]:
    """
    Descarga SHA256SUMS.txt del mismo directorio de release y valida dest_file.
    - Omite solo si SHA256SUMS.txt devuelve HTTP 404 (releases antiguos).
    - Falla ante cualquier error de red/HTTP != 404, entrada faltante o hash distinto.
    """
    from urllib.parse import urlsplit, urlunsplit
    parts = urlsplit(target_url)
    base_path = parts.path.rsplit("/", 1)[0] + "/SHA256SUMS.txt"
    sums_url = urlunsplit((parts.scheme, parts.netloc, base_path, "", ""))
    try:
        req = urllib.request.Request(sums_url, headers={"User-Agent": "OmniVault-Desktop-Updater"})
        with urllib.request.urlopen(req, timeout=15) as resp:
            content = resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return True, "SHA256SUMS.txt no disponible en este release"
        return False, f"No se pudo descargar SHA256SUMS.txt (HTTP {e.code})"
    except Exception as e:
        return False, f"Error verificando integridad SHA-256: {e}"

    target_name = os.path.basename(dest_file)
    expected_hex = None
    for line in content.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        tokens = stripped.split(None, 1)
        if len(tokens) != 2:
            continue
        hex_val, fname = tokens[0].strip(), tokens[1].strip().lstrip("*")
        if fname == target_name or fname.lower() == target_name.lower():
            expected_hex = hex_val
            break

    if not expected_hex:
        return False, f"'{target_name}' no figura en SHA256SUMS.txt"

    if not verify_file_sha256(dest_file, expected_hex):
        return False, f"Hash SHA-256 inválido para '{target_name}' (archivo corrupto o alterado)"

    return True, "Integridad SHA-256 verificada"


def download_and_install_update(
    download_url: str,
    is_installer: bool,
    progress_callback=None,
    finished_callback=None,
    error_callback=None,
    remote_version: str = None,
    on_install_started=None,
):
    """
    Descarga el nuevo paquete (.exe o .zip) con seguimiento de progreso,
    verifica su SHA-256 y ejecuta la actualización automáticamente.
    """
    def _download():
        dest_file = None
        try:
            # Resolución dinámica a prueba de 404 + validación de origen HTTPS confiable
            target_url = resolve_download_url(download_url, is_installer, remote_version)
            if not _is_trusted_release_url(target_url):
                raise ValueError(f"URL de actualización no confiable: {target_url}")

            target_dir = os.path.join(os.environ.get("TEMP", os.path.expanduser("~")), "OmniVault_Update")
            os.makedirs(target_dir, exist_ok=True)
            filename = os.path.basename(target_url.split("?")[0]) or ("OmniVault_Setup.exe" if is_installer else "OmniVault_Portable.zip")
            dest_file = os.path.join(target_dir, filename)

            req = urllib.request.Request(target_url, headers={"User-Agent": "Mozilla/5.0 OmniVault"})
            with urllib.request.urlopen(req, timeout=30) as resp:
                total_bytes = int(resp.headers.get("Content-Length", 0))
                downloaded = 0
                chunk_size = 65536

                with open(dest_file, "wb") as f:
                    while True:
                        chunk = resp.read(chunk_size)
                        if not chunk:
                            break
                        f.write(chunk)
                        downloaded += len(chunk)
                        if total_bytes > 0 and progress_callback:
                            percent = int((downloaded / total_bytes) * 100)
                            progress_callback(percent, downloaded, total_bytes)

            ok_hash, hash_msg = verify_release_checksum(dest_file, target_url)
            if not ok_hash:
                try:
                    if os.path.exists(dest_file):
                        os.remove(dest_file)
                except Exception:
                    pass
                raise ValueError(hash_msg)

            if finished_callback:
                finished_callback(dest_file)

            # Ejecutar actualización
            if dest_file.lower().endswith(".exe"):
                subprocess.Popen([dest_file, "/CLOSEAPPLICATIONS"])
                if on_install_started:
                    on_install_started()
            else:
                if sys.platform == "win32":
                    os.startfile(target_dir)

        except Exception as e:
            if error_callback:
                error_callback(str(e))

    thread = threading.Thread(target=_download, daemon=True)
    thread.start()
