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

CURRENT_VERSION = "2.0.9"
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

    # 3. Fallback heurístico: Si falló vX.Y.Z, intentar con v2.0.0
    if download_url and "-v" in download_url:
        candidate = re.sub(r"-v[\d\.]+-", "-v2.0.0-", download_url)
        if candidate != download_url:
            return candidate

    return download_url

def check_for_updates_async(callback):
    """
    Consulta en segundo plano la existencia de una nueva versión.
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
                remote_version = data.get("version", CURRENT_VERSION)
                has_update = is_newer_version(remote_version, CURRENT_VERSION)
                callback(has_update, data, None)
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
