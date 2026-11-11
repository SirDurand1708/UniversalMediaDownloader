#!/usr/bin/env python3
"""
bump_version.py — Script de incremento automático de versión para OmniVault.

Uso:
    python bump_version.py           # incrementa el patch (2.0.3 -> 2.0.4)
    python bump_version.py --minor   # incrementa el minor (2.0.x -> 2.1.0)
    python bump_version.py --major   # incrementa el major (2.x.x -> 3.0.0)
    python bump_version.py --set 2.1.5  # establece una versión específica

Actualiza automáticamente:
    - version.json
    - android/app/build.gradle (versionCode y versionName)
    - android/app/src/main/java/.../utils/UpdateManager.kt
    - updater.py (escritorio)
"""

import json
import re
import sys
import os
import datetime

# Soporte UTF-8 en consolas Windows (evita UnicodeEncodeError con cp1252)
if sys.platform == "win32":
    try:
        if hasattr(sys.stdout, "reconfigure"):
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

ROOT = os.path.dirname(os.path.abspath(__file__))


def parse_version(v: str) -> tuple[int, int, int]:
    v = v.lstrip("v").strip()
    parts = v.split(".")
    return (int(parts[0]), int(parts[1]), int(parts[2]))


def version_to_code(major: int, minor: int, patch: int) -> int:
    """Convierte versión semver a versionCode entero (ej. 2.0.4 -> 204)."""
    return major * 100 + minor * 10 + patch


def bump(current: str, mode: str) -> str:
    major, minor, patch = parse_version(current)
    if mode == "major":
        return f"{major + 1}.0.0"
    elif mode == "minor":
        return f"{major}.{minor + 1}.0"
    else:
        return f"{major}.{minor}.{patch + 1}"


def update_version_json(new_version: str, version_code: int):
    path = os.path.join(ROOT, "version.json")
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)

    today = datetime.date.today().isoformat()
    data["version"] = new_version
    data["version_code"] = version_code
    data["release_date"] = today
    data["title"] = f"OmniVault v{new_version} — Ultra Performance Boost & Smooth Micro-Interactions"
    data["changelog"] = (
        "⚡ Arranque ultrarrápido (3x más rápido): Carga diferida de pestañas (lazy loading) e inicialización instantánea de MiniHUD\n"
        "🚀 Descargas a máxima velocidad: Fragmentos concurrentes en yt-dlp, aceleración Aria2c a 16 hilos y buffers de 256KB/64KB\n"
        "🎯 Suavizado de progreso (LERP): Barras de descarga fluidas y continuas sin saltos en PC y Android\n"
        "✨ Micro-interacciones y animaciones: Despliegue animado de banners, respuesta táctil bounce-click y realce de pestaña activa\n"
        "🛡️ Optimización de CPU: Throttling a ~13 FPS en callbacks de progreso para cero tirones en la interfaz"
    )

    for platform in ["android", "windows"]:
        if platform in data:
            data[platform]["version"] = new_version
            data[platform]["version_code"] = version_code

    if "android" in data:
        data["android"]["apk_url"] = (
            f"https://github.com/SirDurand1708/UniversalMediaDownloader/releases/download/"
            f"v{new_version}/OmniVault-v{new_version}-Android.apk"
        )

    if "windows" in data:
        base = (
            f"https://github.com/SirDurand1708/UniversalMediaDownloader/releases/download/"
            f"v{new_version}"
        )
        data["windows"]["setup_url"] = f"{base}/OmniVault-v{new_version}-Windows-x64-Setup.exe"
        data["windows"]["portable_url"] = f"{base}/OmniVault-v{new_version}-Windows-x64-Portable.zip"

    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
    print(f"  [OK] version.json actualizado -> {new_version}")


def update_build_gradle(new_version: str, version_code: int):
    path = os.path.join(ROOT, "android", "app", "build.gradle")
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    content = re.sub(r'versionCode\s+\d+', f'versionCode {version_code}', content)
    content = re.sub(r'versionName\s+"[^"]*"', f'versionName "{new_version}"', content)

    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"  [OK] android/app/build.gradle actualizado -> {new_version} ({version_code})")


def update_manager_kt(new_version: str, version_code: int):
    path = os.path.join(
        ROOT,
        "android", "app", "src", "main", "java",
        "com", "omnivault", "media", "utils", "UpdateManager.kt"
    )
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    content = re.sub(
        r'const val CURRENT_VERSION_NAME = "[^"]*"',
        f'const val CURRENT_VERSION_NAME = "{new_version}"',
        content
    )
    content = re.sub(
        r'const val CURRENT_VERSION_CODE = \d+',
        f'const val CURRENT_VERSION_CODE = {version_code}',
        content
    )

    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"  [OK] UpdateManager.kt actualizado -> {new_version} ({version_code})")


def update_updater_py(new_version: str):
    path = os.path.join(ROOT, "updater.py")
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    content = re.sub(
        r'CURRENT_VERSION = "[^"]*"',
        f'CURRENT_VERSION = "{new_version}"',
        content
    )

    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"  [OK] updater.py actualizado -> {new_version}")


def main():
    args = sys.argv[1:]

    # Leer versión actual desde version.json
    version_json_path = os.path.join(ROOT, "version.json")
    with open(version_json_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    current = data.get("version", "2.0.0")

    # Determinar nueva versión
    if "--set" in args:
        idx = args.index("--set")
        new_version = args[idx + 1].lstrip("v")
    elif "--major" in args:
        new_version = bump(current, "major")
    elif "--minor" in args:
        new_version = bump(current, "minor")
    else:
        new_version = bump(current, "patch")

    major, minor, patch = parse_version(new_version)
    version_code = version_to_code(major, minor, patch)

    print(f"\n[*] OmniVault Version Bump")
    print(f"    {current}  ->  {new_version}  (code: {version_code})\n")

    update_version_json(new_version, version_code)
    update_build_gradle(new_version, version_code)
    update_manager_kt(new_version, version_code)
    update_updater_py(new_version)

    print(f"\n[OK] Todos los archivos actualizados exitosamente a v{new_version}")
    print(f"\nProximos pasos:")
    print(f"  git add -A")
    print(f"  git commit -m \"chore: bump version to v{new_version}\"")
    print(f"  git tag v{new_version}")
    print(f"  git push origin main --tags")
    print(f"\nEl CI/CD de GitHub Actions compilara y publicara la release automaticamente.\n")


if __name__ == "__main__":
    main()
