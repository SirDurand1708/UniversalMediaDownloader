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
import threading
import subprocess

try:
    from packaging import version
except ImportError:
    version = None

CURRENT_VERSION = "2.2.0"
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

def find_release_asset_url(version_tag: str, is_installer: bool, direct_url: str = "") -> tuple[bool, str]:
    """
    Comprueba si existe un binario real (.exe o .zip) para Windows en el release de GitHub.
    Retorna (found: bool, url: str).
    """
    # 1. Probar URL directa por HEAD
    if direct_url:
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

def download_and_install_update(download_url: str, is_installer: bool, progress_callback=None, finished_callback=None, error_callback=None, remote_version: str = None):
    """
    Descarga el nuevo paquete (.exe o .zip) con seguimiento de progreso
    y ejecuta la actualización automáticamente.
    """
    def _download():
        try:
            # Resolución dinámica a prueba de 404
            target_url = resolve_download_url(download_url, is_installer, remote_version)

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

            if finished_callback:
                finished_callback(dest_file)

            # Ejecutar actualización
            if dest_file.lower().endswith(".exe"):
                # Ejecutar instalador Inno Setup con elevación y cierre de instancias previas
                subprocess.Popen([dest_file, "/CLOSEAPPLICATIONS"], shell=True)
                sys.exit(0)
            else:
                # Versión portátil: abrir carpeta con el zip descargado
                if sys.platform == "win32":
                    os.startfile(target_dir)

        except Exception as e:
            if error_callback:
                error_callback(str(e))

    thread = threading.Thread(target=_download, daemon=True)
    thread.start()
