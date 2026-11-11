"""
Herramienta de Diagnóstico del Sistema y Verificación de Requisitos para OmniVault Desktop.
Verifica que el sistema cuente con todas las dependencias, herramientas nativas (FFmpeg, Aria2c)
y aceleración por hardware necesarias para un rendimiento óptimo.
"""

import os
import sys
import shutil
import subprocess
import importlib

# Manejo robusto de codificación en consolas de Windows (evita errores con cp1252)
if sys.stdout and hasattr(sys.stdout, 'reconfigure'):
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass
if sys.stderr and hasattr(sys.stderr, 'reconfigure'):
    try:
        sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

def check_mark(ok: bool) -> str:
    return "[OK]" if ok else "[FALTA]"

def run_diagnostics():
    print("=" * 70)
    print("[*] OMNIVAULT DESKTOP - DIAGNOSTICO INTEGRAL DEL SISTEMA")
    print("=" * 70)

    # 1. Python y Sistema Operativo
    py_ver = f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}"
    py_ok = sys.version_info >= (3, 10)
    print(f"\n1. Entorno de Ejecución:")
    print(f"   {check_mark(py_ok)} Python {py_ver} (Requerido: >= 3.10)")
    print(f"   [INFO] Plataforma: {sys.platform} ({os.name})")

    # 2. Dependencias de Python
    required_packages = [
        ("yt_dlp", "yt-dlp (Motor de extracción de medios)"),
        ("customtkinter", "CustomTkinter (Interfaz gráfica moderna)"),
        ("darkdetect", "darkdetect (Detección de tema oscuro de Windows)"),
        ("mutagen", "mutagen (Inyección de metadatos ID3/M4A)"),
        ("PIL", "Pillow (Procesamiento de imágenes y carátulas)"),
        ("pystray", "pystray (Área de notificación / System Tray)"),
        ("curl_cffi", "curl_cffi (Evasión TLS contra bloqueos antibot)"),
        ("speech_recognition", "SpeechRecognition (Subtítulos IA y transcripción)"),
        ("packaging", "packaging (Comparador semántico de versiones y actualizaciones)"),
        ("PyInstaller", "PyInstaller (Compilador a ejecutable autónomo)"),
    ]

    print("\n2. Módulos y Librerías de Python:")
    missing_packages = []
    for pkg_name, desc in required_packages:
        try:
            importlib.import_module(pkg_name)
            print(f"   [OK] {desc}")
        except ImportError:
            print(f"   [FALTA] {desc} -> pip install {pkg_name.lower()}")
            missing_packages.append(pkg_name.lower())

    # 3. Herramientas Multimedia Binarias (FFmpeg y Aria2c)
    print("\n3. Binarios Externos de Alto Rendimiento:")
    
    # Importar detectores locales si están disponibles
    try:
        from downloader import is_ffmpeg_available, is_aria2c_available, FFMPEG_DIR, _get_aria2c_path, detect_hardware_acceleration
        ff_ok = is_ffmpeg_available()
        aria_ok = is_aria2c_available()
        gpu_accel = detect_hardware_acceleration()
        aria_path = _get_aria2c_path()
    except Exception:
        ff_path = shutil.which("ffmpeg")
        ff_ok = bool(ff_path)
        FFMPEG_DIR = os.path.dirname(ff_path) if ff_path else None
        aria_path = shutil.which("aria2c")
        aria_ok = bool(aria_path)
        gpu_accel = None

    if ff_ok:
        print(f"   [OK] FFmpeg: Instalado y detectado ({FFMPEG_DIR or 'PATH'})")
    else:
        print("   [ADVERTENCIA] FFmpeg: No detectado en el sistema.")
        print("                 Recomendación: Ejecuta en terminal: winget install Gyan.FFmpeg")

    if aria_ok:
        print(f"   [OK] Aria2c: Acelerador de red multihilo activo ({aria_path})")
    else:
        print("   [OPCIONAL] Aria2c: No detectado (el sistema usará el descargador nativo).")
        print("              Para habilitar descargas 5x paralelas, coloca aria2c.exe en bin/ o Documents/Software")

    if gpu_accel:
        print(f"   [OK] Aceleración por GPU: Detectada ({gpu_accel.upper()})")
    else:
        print("   [INFO] Aceleración por GPU: No detectada o FFmpeg usará codificación por CPU.")

    # 4. Recursos Gráficos
    print("\n4. Recursos de Identidad Visual (assets/):")
    base_dir = os.path.dirname(os.path.abspath(__file__))
    assets_dir = os.path.join(base_dir, "assets")
    assets_to_check = [
        ("icon.ico", "Icono de aplicación (.ico)"),
        ("icon.png", "Icono de alta definición (.png)"),
        ("logo.png", "Banner / Logotipo (.png)"),
        ("omnivault_artwork.jpg", "Arte oficial / Carátula de suite"),
    ]
    for asset_file, desc in assets_to_check:
        full_p = os.path.join(assets_dir, asset_file)
        if os.path.isfile(full_p):
            size_kb = os.path.getsize(full_p) / 1024
            print(f"   [OK] {desc} ({size_kb:.1f} KB)")
        else:
            print(f"   [FALTA] {desc} ({asset_file})")

    # 5. Directorios de Usuario Predeterminados
    print("\n5. Rutas de Guardado del Sistema:")
    try:
        from config_manager import ConfigManager
        cfg = ConfigManager()
        audio_dir = cfg.get_audio_dir()
        video_dir = cfg.get_video_dir()
        print(f"   [OK] Carpeta de Audios: {audio_dir}")
        print(f"   [OK] Carpeta de Videos: {video_dir}")
    except Exception as e:
        print(f"   [INFO] Error al obtener directorios: {e}")

    # 6. Resumen y Conclusión
    print("\n" + "=" * 70)
    if missing_packages:
        print(f"[!] FALTAN {len(missing_packages)} DEPENDENCIAS. Ejecuta:")
        print("    py -m pip install -r requirements.txt")
    else:
        print("[OK] EL ENTORNO ESTA 100% LISTO PARA EJECUTAR OMNIVAULT DESKTOP!")
        print("     Puedes iniciar la aplicación ejecutando: iniciar.bat  o  py main.py")
    print("=" * 70)

if __name__ == "__main__":
    run_diagnostics()
