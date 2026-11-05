# ✦ Guía Oficial de Lanzamientos y Publicación en GitHub (Releases)

Esta guía explica cómo publicar nuevas versiones de **OmniVault Desktop** siguiendo los más altos estándares de desarrollo de código abierto.

---

## 🌟 Método 1: Automatización Total con GitHub Actions (Recomendado)

El repositorio incluye un flujo de integración y entrega continua (CI/CD) en `.github/workflows/release.yml`.

### Pasos para publicar una nueva versión con 1 comando:
1. Haz commit de todos tus cambios:
   ```bash
   git add .
   git commit -m "feat: release version 2.0.0"
   git push origin main
   ```
2. Crea una etiqueta de versión (*Tag*) y súbela a GitHub:
   ```bash
   git tag v2.0.0
   git push origin v2.0.0
   ```
3. **¡Listo!** En ese instante, un servidor en la nube de GitHub:
   * Descarga el código.
   * Ejecuta la suite de pruebas unitarias (`run_tests.py`).
   * Compila el ejecutable con PyInstaller.
   * Compila el instalador oficial con Inno Setup.
   * Comprime la versión portátil en `.zip`.
   * Calcula las sumas criptográficas SHA-256.
   * **Crea y publica automáticamente la versión en la sección Releases de tu repositorio con todos los archivos descargables listos para tus usuarios.**

---

## 🖱️ Método 2: Lanzamiento Manual desde la Web de GitHub

Si prefieres lanzar una versión sin usar comandos de consola:
1. Entra a tu repositorio en GitHub desde el navegador.
2. Ve a la pestaña **Actions** en la parte superior.
3. En la barra lateral izquierda, selecciona **✦ OmniVault Official Release CI/CD**.
4. Haz clic en el botón desplegable **Run workflow**.
5. Escribe la versión (por ejemplo `v2.0.0`) y presiona el botón verde **Run workflow**.

---

## 💻 Método 3: Compilar y Empaquetar Localmente en tu PC

Si deseas generar los archivos en tu propia computadora para probarlos o distribuirlos manualmente:

1. Haz doble clic en:
   ```text
   empaquetar.bat
   ```
   *(O ejecuta en la terminal: `py package_release.py`)*.
2. El script realizará todo el proceso y colocará los archivos finales en la carpeta:
   ```text
   dist\Releases\
   ├── OmniVault-v2.0.0-Windows-x64-Setup.exe       (Instalador)
   ├── OmniVault-v2.0.0-Windows-x64-Portable.zip    (Versión portátil)
   └── SHA256SUMS.txt                               (Sumas de verificación)
   ```
3. Para publicarlos a mano:
   * Ve a `https://github.com/tu-usuario/UniversalMediaDownloader/releases/new`.
   * Escribe la versión (ej. `v2.0.0`).
   * Arrastra los 3 archivos de `dist\Releases\` a la zona de subida.
   * Haz clic en **Publish release**.

---

## 🛡️ Nomenclatura Estándar de Archivos

| Archivo | Función |
| :--- | :--- |
| `OmniVault-vX.Y.Z-Windows-x64-Setup.exe` | Instalador oficial de Windows con asistente guiado, accesos directos y desinstalador. |
| `OmniVault-vX.Y.Z-Windows-x64-Portable.zip` | Versión sin instalación para llevar en memorias USB o ejecutar sin permisos de administrador. |
| `SHA256SUMS.txt` | Firmas criptográficas para que los usuarios verifiquen que los archivos no fueron alterados ni contienen virus. |
