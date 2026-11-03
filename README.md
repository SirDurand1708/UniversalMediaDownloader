# Universal Media Downloader (Snaptube Pro Desktop) ✦ Edición Privada y Segura

> **Aplicación para computadoras de escritorio y laptops con diseño prémium *Obsidian & Titanium*.**  
> Sin anuncios, sin rastreadores, sin riesgo de fuga de credenciales y con soporte nativo de **Playlists y Selección Múltiple**.

---

## 🎨 Paleta de Diseño: *Obsidian & Titanium*

- **Negro Obsidiana (`#0F1012`):** Base oscura profunda para menor fatiga visual.
- **Grafito Oscuro (`#1A1D22`):** Tarjetas y paneles tridimensionales estructurados.
- **Plata Titanio (`#E2E8F0` / `#FFFFFF`):** Botones de acción principales de alto contraste.
- **Plata Hielo / Cian (`#38BDF8`):** Barras de progreso e indicadores de actividad.

---

## 🚀 Funcionalidades Principales

### 1. 📋 Soporte Completo de Playlists & Álbumes
- **Detección inteligente:** Pega cualquier enlace de playlist o álbum completo de YouTube o SoundCloud.
- **Panel con casillas de verificación:** Selecciona canciones o videos específicos de la lista.
- **Controles rápidos:** Botones `[✓ Seleccionar Toda la Lista]` y `[✖ Desmarcar Todos]`.
- **Descarga masiva en 1 clic:** Elige el formato y descarga todo el lote directamente a la cola activa.

### 2. 🔍 Búsqueda Directa con Selección Múltiple
- Escribe el nombre de un artista o canción para buscar directamente en YouTube.
- Marca varios resultados a la vez con casillas para descargarlos juntos.
- Botón para inspeccionar individualmente y elegir resoluciones avanzadas (1080p, 4K, 60fps).

### 3. 🔒 Sesión Segura de Navegador (Opcional & 100% Privada)
- Configurable en la pestaña **Ajustes**.
- Permite descargar **videos con restricción de edad (+18)** o tus **playlists privadas** (como "Videos que me gustan") sincronizando la sesión de tu navegador local (Chrome, Edge, Vivaldi, Firefox, Brave, Opera).
- **Cero contraseñas:** La aplicación jamás solicita correos ni contraseñas.
- **Archivos protegidos:** Las cookies y configuraciones locales están excluidas automáticamente de Git mediante `.gitignore`.

### 4. 📥 Gestor de Descargas Concurrentes
- Descargas en segundo plano con barra de progreso, velocidad en tiempo real y tiempo restante.
- Cola multihilo que procesa varias descargas sin congelar la interfaz.
- Cierre limpio y cancelación segura de archivos parciales (`.part`).

### 5. 🎵 Mi Biblioteca Local
- Historial completo de tus archivos descargados guardado de manera atómica.
- Botón **▶ Reproducir** directo con el reproductor multimedia predeterminado (protegido contra ejecución de ejecutables no deseados).
- Botón **📁 Ubicación** para abrir y resaltar el archivo en el Explorador de archivos (compatible con Windows, macOS y Linux).

---

## ⚙️ Requisitos e Instalación

### 1. Instalar Dependencias de Python
Abre la consola en la carpeta del proyecto y ejecuta:
```bash
py -m pip install -r requirements.txt
```
*(O `python -m pip install -r requirements.txt` si tu comando predeterminado es `python`)*.

### 2. Instalar FFmpeg (Recomendado)
FFmpeg es necesario para convertir audios a MP3/WAV y combinar video 1080p/4K con audio:
- **Opción A (Recomendada en Windows):** Abre PowerShell y ejecuta:
  ```powershell
  winget install Gyan.FFmpeg
  ```
- **Opción B (Portátil):** Descarga `ffmpeg.exe` y colócalo directamente en la carpeta de este programa.

---

## ▶️ Cómo ejecutar la aplicación

Haz doble clic en:
```
iniciar.bat
```
O desde la consola:
```bash
py app.py
```
*(o `python app.py`)*
