"""
Script de compilación y empaquetado para generar el ejecutable nativo de OmniVault Desktop.
Utiliza PyInstaller para generar una versión portátil lista para usar en dist/OmniVault/.
"""

import os
import sys
import shutil
import subprocess

def build():
    base_dir = os.path.dirname(os.path.abspath(__file__))
    print("=" * 60)
    print("✦ Compilador Oficial de OmniVault Desktop (.EXE)")
    print("=" * 60)

    # Verificar PyInstaller
    try:
        import PyInstaller
    except ImportError:
        print("[!] PyInstaller no encontrado. Instalando...")
        subprocess.check_call([sys.executable, "-m", "pip", "install", "pyinstaller"])

    dist_dir = os.path.join(base_dir, "dist")
    build_dir = os.path.join(base_dir, "build")

    print("[*] Preparando argumentos de PyInstaller...")
    cmd = [
        sys.executable,
        "-m",
        "PyInstaller",
        "--noconfirm",
        "--onedir",
        "--windowed",
        "--name", "OmniVault",
        "--clean",
        "--collect-all", "customtkinter",
        "--hidden-import", "pystray",
        "--hidden-import", "PIL",
        "--hidden-import", "mutagen",
        "--hidden-import", "yt_dlp",
        "--hidden-import", "curl_cffi",
        "--hidden-import", "darkdetect",
        os.path.join(base_dir, "app.py"),
    ]

    print("[*] Ejecutando compilación...")
    ret = subprocess.call(cmd)
    if ret != 0:
        print("[X] Error durante la compilación con PyInstaller.")
        return False

    output_app_dir = os.path.join(dist_dir, "OmniVault")
    print(f"[*] Copiando recursos y binarios a {output_app_dir}...")

    # Copiar carpeta bin (FFmpeg / Aria2c) si existe
    src_bin = os.path.join(base_dir, "bin")
    dst_bin = os.path.join(output_app_dir, "bin")
    if os.path.isdir(src_bin):
        os.makedirs(dst_bin, exist_ok=True)
        for item in os.listdir(src_bin):
            s = os.path.join(src_bin, item)
            d = os.path.join(dst_bin, item)
            if os.path.isfile(s):
                shutil.copy2(s, d)
                print(f"    -> Binario copiado: {item}")

    # Copiar config.example.json
    src_cfg = os.path.join(base_dir, "config.example.json")
    if os.path.isfile(src_cfg):
        shutil.copy2(src_cfg, os.path.join(output_app_dir, "config.example.json"))

    print("\n" + "=" * 60)
    print("¡COMPILACIÓN EXITOSA!")
    print(f"El ejecutable está listo en: {os.path.join(output_app_dir, 'OmniVault.exe')}")
    print("=" * 60)
    return True

if __name__ == "__main__":
    success = build()
    sys.exit(0 if success else 1)
