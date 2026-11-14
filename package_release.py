"""
Empaquetador Oficial de Lanzamientos (Release Packager) para OmniVault Desktop.
Genera la versión portátil (.zip), el instalador (.exe) y calcula las sumas SHA-256.
"""

import os
import sys
import shutil
import hashlib
import zipfile
import subprocess

if sys.stdout and hasattr(sys.stdout, 'reconfigure'):
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

DEFAULT_VERSION = "2.5.7"

def get_release_version() -> str:
    """Obtiene la versión oficial de forma dinámica desde argumentos CLI, entorno CI/CD o version.json."""
    # 1. Argumentos CLI: --version X.Y.Z o -v X.Y.Z
    for i, arg in enumerate(sys.argv):
        if arg in ("--version", "-v") and i + 1 < len(sys.argv):
            return sys.argv[i + 1].lstrip("v").strip()

    # 2. Variable de entorno del flujo CI/CD de GitHub Actions
    for env_var in ["GITHUB_REF_NAME", "RELEASE_TAG", "RELEASE_VERSION"]:
        val = os.environ.get(env_var)
        if val:
            cleaned = val.split("/")[-1].lstrip("v").strip()
            if cleaned and cleaned != "main":
                return cleaned

    # 3. Leer directamente desde version.json
    try:
        base_dir = os.path.dirname(os.path.abspath(__file__))
        with open(os.path.join(base_dir, "version.json"), "r", encoding="utf-8") as f:
            import json
            data = json.load(f)
            v = data.get("version")
            if v:
                return v.lstrip("v").strip()
    except Exception:
        pass

    return DEFAULT_VERSION

VERSION = get_release_version()

def ensure_portable_binaries(base_dir: str):
    """
    Localiza y copia los binarios de FFmpeg y Aria2c a la carpeta bin/ del proyecto
    para que se incluyan automáticamente en el paquete portátil (.zip) y en el instalador (.exe).
    """
    bin_dir = os.path.join(base_dir, "bin")
    os.makedirs(bin_dir, exist_ok=True)

    # 1. FFmpeg
    ffmpeg_target = os.path.join(bin_dir, "ffmpeg.exe" if sys.platform == "win32" else "ffmpeg")
    if not os.path.isfile(ffmpeg_target):
        system_ff = shutil.which("ffmpeg")
        if not system_ff:
            cand_paths = [
                os.path.expandvars(r"%LOCALAPPDATA%\Microsoft\WinGet\Links\ffmpeg.exe"),
                os.path.expandvars(r"%ProgramData%\chocolatey\bin\ffmpeg.exe"),
                os.path.expanduser(r"~\scoop\shims\ffmpeg.exe")
            ]
            system_ff = next((p for p in cand_paths if os.path.isfile(p)), None)

        if system_ff and os.path.isfile(system_ff):
            try:
                shutil.copy2(system_ff, ffmpeg_target)
                print(f"[*] [OK] FFmpeg portable integrado en bin/ ({system_ff})")
            except Exception as e:
                print(f"[!] No se pudo copiar FFmpeg: {e}")

    # 2. Aria2c
    aria_target = os.path.join(bin_dir, "aria2c.exe" if sys.platform == "win32" else "aria2c")
    if not os.path.isfile(aria_target):
        system_aria = shutil.which("aria2c")
        if not system_aria:
            cand_paths = [
                os.path.expandvars(r"%LOCALAPPDATA%\Microsoft\WinGet\Links\aria2c.exe"),
                os.path.expandvars(r"%ProgramData%\chocolatey\bin\aria2c.exe"),
                os.path.expanduser(r"~\scoop\shims\aria2c.exe")
            ]
            system_aria = next((p for p in cand_paths if os.path.isfile(p)), None)

        if system_aria and os.path.isfile(system_aria):
            try:
                shutil.copy2(system_aria, aria_target)
                print(f"[*] [OK] Aria2c portable integrado en bin/ ({system_aria})")
            except Exception as e:
                print(f"[!] No se pudo copiar Aria2c: {e}")

def calculate_sha256(filepath: str) -> str:
    """Calcula el hash SHA-256 de un archivo en bloques de 64KB."""
    sha256 = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            sha256.update(chunk)
    return sha256.hexdigest()

def make_portable_zip(source_dir: str, target_zip: str):
    """Crea el archivo .zip portátil excluyendo cachés temporales."""
    print(f"[*] Comprimiendo versión portátil: {target_zip}...")
    with zipfile.ZipFile(target_zip, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for root, dirs, files in os.walk(source_dir):
            dirs[:] = [d for d in dirs if d != "__pycache__"]
            for file in files:
                if file.endswith((".pyc", ".tmp")):
                    continue
                full_path = os.path.join(root, file)
                rel_path = os.path.join("OmniVault", os.path.relpath(full_path, source_dir))
                zf.write(full_path, rel_path)
    size_mb = os.path.getsize(target_zip) / (1024 * 1024)
    print(f"    -> [OK] Versión portátil generada ({size_mb:.2f} MB)")

def compile_inno_setup(iss_file: str, version_str: str = None) -> bool:
    """Compila el instalador con Inno Setup Compiler si está instalado."""
    ver = version_str or VERSION
    print(f"[*] Buscando compilador Inno Setup (ISCC.exe) para versión v{ver}...")
    iscc_candidates = [
        shutil.which("iscc"),
        os.path.expandvars(r"%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe"),
        os.path.expandvars(r"%LOCALAPPDATA%\Programs\Inno Setup 7\ISCC.exe"),
        r"C:\Program Files (x86)\Inno Setup 6\ISCC.exe",
        r"C:\Program Files\Inno Setup 6\ISCC.exe",
        r"C:\Program Files (x86)\Inno Setup 5\ISCC.exe",
        r"C:\Program Files\Inno Setup 5\ISCC.exe",
    ]
    iscc_bin = next((c for c in iscc_candidates if c and os.path.isfile(c)), None)
    if not iscc_bin:
        print("[!] Inno Setup no está instalado en este equipo.")
        print("    Descárgalo gratis desde: https://jrsoftware.org/isdl.php para generar el instalador .exe")
        return False

    print(f"[*] Compilando instalador oficial con {iscc_bin} (v{ver})...")
    cmd = [
        iscc_bin,
        f"/DMyAppVersion={ver}",
        f"/DOutputBaseFilename=OmniVault-v{ver}-Windows-x64-Setup",
        iss_file
    ]
    res = subprocess.call(cmd)
    return res == 0

def generate_checksums(releases_dir: str) -> str:
    """Calcula SHA-256 para todos los artefactos de la carpeta Releases."""
    checksums_file = os.path.join(releases_dir, "SHA256SUMS.txt")
    lines = []
    print("\n[*] Sumas de Verificación (SHA-256):")
    for f_name in sorted(os.listdir(releases_dir)):
        if f_name == "SHA256SUMS.txt":
            continue
        full_p = os.path.join(releases_dir, f_name)
        if os.path.isfile(full_p):
            h = calculate_sha256(full_p)
            lines.append(f"{h}  {f_name}")
            print(f"    {h}  {f_name}")

    with open(checksums_file, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"\n    -> Guardado en: {checksums_file}")
    return checksums_file

def main():
    base_dir = os.path.dirname(os.path.abspath(__file__))
    dist_dir = os.path.join(base_dir, "dist")
    app_dir = os.path.join(dist_dir, "OmniVault")
    releases_dir = os.path.join(dist_dir, "Releases")
    os.makedirs(releases_dir, exist_ok=True)

    print("=" * 70)
    print(f"✦ OMNIVAULT DESKTOP v{VERSION} - EMPAQUETADOR OFICIAL DE LANZAMIENTOS")
    print("=" * 70)

    # 0. Asegurar binarios portables en bin/
    ensure_portable_binaries(base_dir)

    # 1. Compilar con PyInstaller si dist/OmniVault no existe o si se solicita
    if not os.path.isfile(os.path.join(app_dir, "OmniVault.exe")):
        print("[*] Binario de OmniVault no encontrado. Compilando primero...")
        build_script = os.path.join(base_dir, "build_exe.py")
        ret = subprocess.call([sys.executable, build_script])
        if ret != 0:
            print("[X] Falló la compilación con PyInstaller.")
            return 1

    # 2. Generar ZIP Portátil
    portable_zip = os.path.join(releases_dir, f"OmniVault-v{VERSION}-Windows-x64-Portable.zip")
    make_portable_zip(app_dir, portable_zip)

    # 3. Compilar Instalador con Inno Setup
    iss_file = os.path.join(base_dir, "installer.iss")
    if os.path.isfile(iss_file):
        compile_inno_setup(iss_file, VERSION)

    # 4. Generar Sumas de Verificación SHA-256
    generate_checksums(releases_dir)

    print("\n" + "=" * 70)
    print("✦ ¡PAQUETES DE DISTRIBUCIÓN GENERADOS CON ÉXITO! ✦")
    print(f"  Carpeta de destino: {releases_dir}")
    print("=" * 70)
    return 0

if __name__ == "__main__":
    sys.exit(main())
