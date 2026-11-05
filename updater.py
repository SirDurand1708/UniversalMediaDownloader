"""
Módulo de Actualización Automática Oficial (In-App Auto-Updater) para OmniVault Desktop.
Verifica nuevas versiones oficiales en GitHub y permite la actualización con 1 solo clic
tanto para la versión instalada como para la versión portátil.
"""

import os
import sys
import json
import urllib.request
import threading
import subprocess
from packaging import version

CURRENT_VERSION = "2.0.1"
VERSION_INFO_URL = "https://raw.githubusercontent.com/SirDurand1708/UniversalMediaDownloader/main/version.json"

def is_newer_version(remote_str: str, local_str: str) -> bool:
    """Compara si la versión remota es estrictamente superior a la versión local."""
    try:
        r = remote_str.lstrip("v").strip()
        l = local_str.lstrip("v").strip()
        return version.parse(r) > version.parse(l)
    except Exception:
        # Fallback semver simple
        try:
            r_parts = [int(p) for p in remote_str.lstrip("v").split(".") if p.isdigit()]
            l_parts = [int(p) for p in local_str.lstrip("v").split(".") if p.isdigit()]
            return r_parts > l_parts
        except Exception:
            return False

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

def download_and_install_update(download_url: str, is_installer: bool, progress_callback=None, finished_callback=None, error_callback=None):
    """
    Descarga el nuevo paquete (.exe o .zip) con seguimiento de progreso
    y ejecuta la actualización automáticamente.
    """
    def _download():
        try:
            target_dir = os.path.join(os.environ.get("TEMP", os.path.expanduser("~")), "OmniVault_Update")
            os.makedirs(target_dir, exist_ok=True)
            filename = os.path.basename(download_url.split("?")[0]) or ("OmniVault_Setup.exe" if is_installer else "OmniVault_Portable.zip")
            dest_file = os.path.join(target_dir, filename)

            req = urllib.request.Request(download_url, headers={"User-Agent": "Mozilla/5.0 OmniVault"})
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
            if is_installer and dest_file.endswith(".exe"):
                # Ejecutar instalador Inno Setup con elevación
                subprocess.Popen([dest_file, "/SILENT", "/CLOSEAPPLICATIONS"], shell=True)
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
