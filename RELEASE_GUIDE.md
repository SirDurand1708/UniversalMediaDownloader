# ✦ Guía Oficial de Lanzamientos y Publicación en GitHub (Releases)

Esta guía explica cómo publicar nuevas versiones de **OmniVault** (tanto para **Windows PC** como para **teléfonos Android**) siguiendo los más altos estándares de desarrollo de código abierto.

---

## 🌟 Método 1: Automatización Total con GitHub Actions (Recomendado)

El repositorio incluye un flujo de integración y entrega continua (CI/CD) multiplataforma en `.github/workflows/release.yml`.

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
3. **¡Listo!** En ese instante, los servidores en la nube de GitHub:
   * **Windows Runner:**
     * Ejecuta la suite de pruebas unitarias (`run_tests.py`).
     * Compila el ejecutable con PyInstaller.
     * Compila el instalador oficial con Inno Setup (`OmniVault-v2.0.0-Windows-x64-Setup.exe`).
     * Comprime la versión portátil en `.zip` (`OmniVault-v2.0.0-Windows-x64-Portable.zip`).
   * **Android / Linux Runner:**
     * Configura el entorno Java 17 y Android SDK.
     * Compila la aplicación móvil con Gradle (`OmniVault-v2.0.0-Android.apk`).
   * **Publicador Global:**
     * Calcula las sumas criptográficas SHA-256 de todos los binarios.
     * **Crea y publica automáticamente la versión oficial en GitHub Releases con todos los archivos descargables listos para tus usuarios.**

---

## 🖱️ Método 2: Lanzamiento Manual desde la Web de GitHub

Si prefieres lanzar una versión sin usar comandos de consola:
1. Entra a tu repositorio en GitHub desde el navegador.
2. Ve a la pestaña **Actions** en la parte superior.
3. En la barra lateral izquierda, selecciona **✦ OmniVault Official Multiplatform Release CI/CD**.
4. Haz clic en el botón desplegable **Run workflow**.
5. Escribe la versión (por ejemplo `v2.0.0`) y presiona el botón verde **Run workflow**.

---

## 💻 Método 3: Compilar y Empaquetar Localmente en tu PC

### Para Windows PC:
1. Haz doble clic en:
   ```text
   empaquetar.bat
   ```
   *(O ejecuta en la terminal: `py package_release.py`)*.
2. El script realizará todo el proceso y colocará los archivos finales en la carpeta:
   ```text
   dist\Releases\
   ├── OmniVault-v2.0.0-Windows-x64-Setup.exe       (Instalador PC)
   ├── OmniVault-v2.0.0-Windows-x64-Portable.zip    (Versión portátil PC)
   └── SHA256SUMS.txt                               (Sumas de verificación)
   ```

### Para Android:
1. Abre la carpeta `android/` en **Android Studio**.
2. Conecta tu teléfono Android o usa el emulador.
3. En el menú superior, ve a **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. O desde la terminal en la carpeta `android`:
   ```bash
   ./gradlew assembleDebug
   ```
   El archivo `.apk` se generará en `android/app/build/outputs/apk/debug/app-debug.apk`.

---

## 🛡️ Nomenclatura Estándar de Archivos de Distribución

| Archivo | Plataforma | Función |
| :--- | :--- | :--- |
| `OmniVault-v2.0.0-Android.apk` | 📱 Android (8.0 a 14+) | Aplicación móvil nativa con auto-captura de portapapeles y soporte para menú 'Compartir' estilo Snaptube. |
| `OmniVault-v2.0.0-Windows-x64-Setup.exe` | 💻 Windows (10/11) | Instalador oficial para PC con asistente guiado, accesos directos y desinstalador en Windows. |
| `OmniVault-v2.0.0-Windows-x64-Portable.zip` | 💻 Windows (10/11) | Versión sin instalación para llevar en memorias USB o ejecutar sin permisos de administrador en cualquier PC. |
| `SHA256SUMS.txt` | 🛡️ Multiplataforma | Firmas criptográficas para que los usuarios verifiquen que los archivos no fueron alterados ni contienen malware. |
