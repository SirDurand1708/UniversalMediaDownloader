"""
Script de compilación y empaquetado para generar el ejecutable nativo de OmniVault Desktop.
Utiliza PyInstaller para generar una versión portátil lista para usar en dist/OmniVault/.
"""

import os
import sys
import shutil
import subprocess

if sys.stdout and hasattr(sys.stdout, 'reconfigure'):
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

def build():
    base_dir = os.path.dirname(os.path.abspath(__file__))
    print("=" * 60)
    print("[*] Compilador Oficial de OmniVault Desktop (.EXE)")
    print("=" * 60)

    try:
        from package_release import ensure_portable_binaries
        ensure_portable_binaries(base_dir)
    except Exception:
        pass

    # Verificar PyInstaller
    try:
        import PyInstaller
    except ImportError:
        print("[!] PyInstaller no encontrado. Instalando...")
        subprocess.check_call([sys.executable, "-m", "pip", "install", "pyinstaller"])

    dist_dir = os.path.join(base_dir, "dist")
    build_dir = os.path.join(base_dir, "build")

    ico_file = os.path.join(base_dir, "assets", "icon.ico")
    icon_args = ["--icon", ico_file] if os.path.isfile(ico_file) else []

    assets_dir = os.path.join(base_dir, "assets")

    # Paquetes requeridos y opcionales
    packages_to_collect = ["customtkinter", "PIL", "mutagen", "yt_dlp", "packaging"]
    optional_packages = ["pystray", "curl_cffi", "darkdetect", "speech_recognition"]
    for pkg in optional_packages:
        try:
            __import__(pkg)
            packages_to_collect.append(pkg)
        except ImportError:
            pass

    collect_args = []
    for pkg in packages_to_collect:
        collect_args.extend(["--collect-all", pkg])

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
    ] + collect_args + icon_args + [os.path.join(base_dir, "app.py")]

    print("[*] Ejecutando compilación...")
    ret = subprocess.call(cmd)
    if ret != 0:
        print("[X] Error durante la compilación con PyInstaller.")
        return False

    output_app_dir = os.path.join(dist_dir, "OmniVault")
    internal_dir = os.path.join(output_app_dir, "_internal")
    print(f"[*] Copiando recursos y binarios a {output_app_dir}...")

    # Copiar carpeta bin (FFmpeg / Aria2c) únicamente a la raíz de dist/OmniVault para evitar duplicar peso en _internal
    src_bin = os.path.join(base_dir, "bin")
    if os.path.isdir(src_bin):
        target_bin = os.path.join(output_app_dir, "bin")
        os.makedirs(target_bin, exist_ok=True)
        for item in os.listdir(src_bin):
            s = os.path.join(src_bin, item)
            d = os.path.join(target_bin, item)
            if os.path.isfile(s):
                shutil.copy2(s, d)
        internal_bin = os.path.join(internal_dir, "bin")
        if os.path.isdir(internal_bin):
            shutil.rmtree(internal_bin, ignore_errors=True)
        print("    -> Carpeta bin (FFmpeg / Aria2c) copiada con éxito sin duplicados.")

    # Copiar config.example.json y LICENSE
    for f_name in ["config.example.json", "LICENSE"]:
        src_f = os.path.join(base_dir, f_name)
        if os.path.isfile(src_f):
            shutil.copy2(src_f, os.path.join(output_app_dir, f_name))

    # Copiar carpeta assets (iconos y logos) únicamente a la raíz de dist/OmniVault
    if os.path.isdir(assets_dir):
        target_assets = os.path.join(output_app_dir, "assets")
        if os.path.exists(target_assets):
            shutil.rmtree(target_assets)
        shutil.copytree(assets_dir, target_assets)
        internal_assets = os.path.join(internal_dir, "assets")
        if os.path.isdir(internal_assets):
            shutil.rmtree(internal_assets, ignore_errors=True)
        print("    -> Carpeta assets copiada con iconos y logotipo oficiales sin duplicados.")

    print("\n" + "=" * 60)
    print("[OK] COMPILACION EXITOSA!")
    print(f"El ejecutable esta listo en: {os.path.join(output_app_dir, 'OmniVault.exe')}")
    print("=" * 60)
    return True

if __name__ == "__main__":
    success = build()
    sys.exit(0 if success else 1)
