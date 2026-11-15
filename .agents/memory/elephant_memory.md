# Memoria del Proyecto — UniversalMediaDownloader (OmniVault / Aura Player)

## 1. Arquitectura y Puntos Clave
- **Multiplataforma:** El repositorio reúne la app Desktop en Python (`CustomTkinter` + `yt-dlp` + `FFmpeg` + `aria2c`), la capa web y la app Android nativa en `android/` (`Kotlin` + `Jetpack Compose` + `Media3 ExoPlayer`).
- **Pantallas principales Android (`MainActivity.kt`):** 4 pestañas en barra inferior (`Screen.Downloads`, `Screen.Home`, `Screen.Videos`, `Screen.Settings`) con transición `fadeIn`/`fadeOut` de 150 ms entre pestañas principales (y slide horizontal solo para pantallas secundarias).
- **Gestión de Descargas y Videos:**
  - `OmniVaultDownloaderScreen.kt` observa `downloadedSongs` desde `DownloadViewModel` (con `clearCompletedHistory()` para limpiar historial en BD sin borrar archivos).
  - `VideosViewModel.kt` combina `DownloadDatabase` y `LocalMediaScanner` con deduplicación por ruta y por `(título normalizado + tamaño)`, además de filtros `onlyDownloaded` y `hideShortClips` (`>= 5s`).

## 2. Decisiones Vigentes
- **Sincronización de Versiones:** Cada release requiere actualizar la versión (`versionName` / `versionCode`) de forma consistente en:
  1. `android/app/build.gradle.kts` (`versionCode`, `versionName`)
  2. `android/app/src/main/java/com/omnivault/media/utils/UpdateManager.kt` (`CURRENT_VERSION_NAME`, `CURRENT_VERSION_CODE`)
  3. `version.json` (`version`, `version_code`, URLs de `android` y `windows`)
- **Idioma y UX:** Toda la UI de la app Android y respuestas al usuario van en español claro, evitando textos truncados (`maxLines = 2` donde sea necesario) y ocultando chevrons (`showChevron = false`) en filas informativas no clickeables.
- **Análisis de Enlaces (Android):** Fast-path nativo en Kotlin (`MediaExtractor.analyze`) para YouTube, TikTok, Instagram, Facebook y X con timeout estricto de 4s; `yt-dlp` lazy-initialized solo para sitios genéricos o al pulsar Descargar; prohibido repetir `extractMetadata` dentro de `executeDownload` (reutilizar metadata cacheada/enviada).

## 3. Trampas Conocidas (Gotchas)
- **Sin Android SDK local en Windows:** Esta máquina no tiene `ANDROID_HOME` instalado, así que Gradle/Kotlin se compila en **GitHub Actions**. Antes de hacer tag/push de cambios en `.kt`, revisar manualmente todos los nombres de variables, imports y tipos en el `git diff` (ej. en `v2.5.18` falló `:app:compileReleaseKotlin` por usar `completedSongs` en vez de `downloadedSongs` en `OmniVaultDownloaderScreen.kt`).
- **Logs de GitHub Actions:** Si un workflow falla, los logs completos requieren sesión iniciada en GitHub; revisar primero el diff local o compartir el extracto del log.

## 4. Checklist de Verificación y Release
1. Ejecutar pruebas de escritorio: `py run_tests.py` (debe pasar `24` tests en verde).
2. Verificar manualmente cualquier cambio en `android/app/src/` (referencias, imports, `@OptIn`).
3. Actualizar `version.json`, `build.gradle.kts` y `UpdateManager.kt` si es un release.
4. Hacer commit semántico, crear tag `vX.Y.Z`, y hacer push de `main` y del tag.
5. Confirmar que los 3 jobs de `OmniVault Official Multiplatform Release CI/CD` (`Build Windows`, `Build Android APK`, `Publish Official Release`) terminen en `success`.
