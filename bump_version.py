#!/usr/bin/env python3
"""
bump_version.py — Script de incremento de versión independiente o conjunto para OmniVault.

Uso:
    python bump_version.py                  # incrementa patch global (ambas plataformas)
    python bump_version.py --android        # incrementa patch SOLO para Android APK
    python bump_version.py --windows        # incrementa patch SOLO para Windows PC
    python bump_version.py --minor          # incrementa minor (2.1.x -> 2.2.0)
    python bump_version.py --major          # incrementa major (2.x.x -> 3.0.0)
    python bump_version.py --set 2.1.5      # establece versión específica
    python bump_version.py --status         # consulta las versiones actuales de PC y Android
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


def update_version_json(new_version: str, version_code: int, platform: str = "all"):
    path = os.path.join(ROOT, "version.json")
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)

    today = datetime.date.today().isoformat()
    data["release_date"] = today

    if platform in ("android", "all"):
        if "android" not in data:
            data["android"] = {}
        data["android"]["version"] = new_version
        data["android"]["version_code"] = version_code
        data["android"]["apk_url"] = (
            f"https://github.com/SirDurand1708/UniversalMediaDownloader/releases/download/"
            f"v{new_version}/OmniVault-v{new_version}-Android.apk"
        )

    if platform in ("windows", "all"):
        if "windows" not in data:
            data["windows"] = {}
        data["windows"]["version"] = new_version
        data["windows"]["version_code"] = version_code
        base = (
            f"https://github.com/SirDurand1708/UniversalMediaDownloader/releases/download/"
            f"v{new_version}"
        )
        data["windows"]["setup_url"] = f"{base}/OmniVault-v{new_version}-Windows-x64-Setup.exe"
        data["windows"]["portable_url"] = f"{base}/OmniVault-v{new_version}-Windows-x64-Portable.zip"

    win_v = data.get("windows", {}).get("version", "0.0.0")
    and_v = data.get("android", {}).get("version", "0.0.0")
    max_v = new_version
    try:
        from updater import is_newer_version
        if is_newer_version(win_v, max_v):
            max_v = win_v
        if is_newer_version(and_v, max_v):
            max_v = and_v
    except Exception:
        pass

    data["version"] = max_v
    data["version_code"] = version_code
    data["title"] = f"OmniVault v{max_v} — Windows PC & Android"

    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
    print(f"  [OK] version.json actualizado -> {new_version} (plataforma: {platform})")


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


def update_installer_iss(new_version: str):
    path = os.path.join(ROOT, "installer.iss")
    if not os.path.isfile(path):
        return
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    content = re.sub(
        r'#define MyAppVersion\s+"[^"]*"',
        f'#define MyAppVersion "{new_version}"',
        content
    )
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"  [OK] installer.iss actualizado -> {new_version}")


def update_package_release(new_version: str):
    path = os.path.join(ROOT, "package_release.py")
    if not os.path.isfile(path):
        return
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    content = re.sub(
        r'DEFAULT_VERSION = "[^"]*"',
        f'DEFAULT_VERSION = "{new_version}"',
        content
    )
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"  [OK] package_release.py actualizado -> {new_version}")


def print_status():
    version_json_path = os.path.join(ROOT, "version.json")
    with open(version_json_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    print("\n" + "=" * 65)
    print("✦ ESTADO DE VERSIONES DE OMNIVAULT (Desktop & Android)")
    print("=" * 65)
    print(f"  Tag de Release Global : v{data.get('version', 'N/A')}")
    print(f"  💻 Windows PC        : v{data.get('windows', {}).get('version', 'N/A')} (código: {data.get('windows', {}).get('version_code', 'N/A')})")
    print(f"  📱 Android APK       : v{data.get('android', {}).get('version', 'N/A')} (código: {data.get('android', {}).get('version_code', 'N/A')})")
    print("=" * 65 + "\n")


def main():
    args = sys.argv[1:]

    if "--status" in args or "-s" in args:
        print_status()
        return

    # Leer versión actual desde version.json
    version_json_path = os.path.join(ROOT, "version.json")
    with open(version_json_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    # Determinar plataforma objetivo
    target_platform = "all"
    if "--android" in args or ("--platform" in args and args[args.index("--platform") + 1].lower() == "android"):
        target_platform = "android"
    elif "--windows" in args or ("--platform" in args and args[args.index("--platform") + 1].lower() == "windows"):
        target_platform = "windows"

    # Seleccionar la versión base correspondiente
    if target_platform == "android":
        current = data.get("android", {}).get("version") or data.get("version", "2.0.0")
    elif target_platform == "windows":
        current = data.get("windows", {}).get("version") or data.get("version", "2.0.0")
    else:
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

    print(f"\n[*] OmniVault Version Bump [Plataforma: {target_platform.upper()}]")
    print(f"    {current}  ->  {new_version}  (code: {version_code})\n")

    update_version_json(new_version, version_code, platform=target_platform)

    if target_platform in ("android", "all"):
        update_build_gradle(new_version, version_code)
        update_manager_kt(new_version, version_code)

    if target_platform in ("windows", "all"):
        update_updater_py(new_version)
        update_installer_iss(new_version)
        update_package_release(new_version)

    print(f"\n[OK] Archivos de {target_platform.upper()} actualizados exitosamente a v{new_version}")
    print(f"\nProximos pasos:")
    print(f"  git add -A")
    print(f"  git commit -m \"chore({target_platform}): bump version to v{new_version}\"")
    print(f"  git tag v{new_version}")
    print(f"  git push origin main --tags")
    print(f"\nEl CI/CD de GitHub Actions detectara y publicara la release automaticamente.\n")


if __name__ == "__main__":
    main()
