# Universal Media Downloader & Converter 🎬🎵

Aplicación de escritorio moderna y ligera para descargar y convertir videos y audios de prácticamente cualquier sitio web (YouTube, TikTok, Instagram, Twitter/X, Twitch, SoundCloud, Facebook, etc.) en máxima calidad.

---

## 🚀 Características

- 🎵 **Audio MP3 en Máxima Calidad (320 kbps):** Extrae el audio, aplica metadatos (artista, título) e incrusta la portada original automáticamente.
- 🎵 **Audio M4A:** Descarga la pista de audio directa sin pérdidas por recompresión.
- 🎬 **Video MP4 Máxima Calidad:** Detecta y fusiona la máxima resolución disponible (1080p, 2K, 4K a 60fps con el mejor audio).
- 🎬 **Video MP4 720p:** Versión ligera y rápida para videos cotidianos.
- 🎨 **Interfaz Moderna en Modo Oscuro:** Construida con `CustomTkinter`, intuitiva, botón para pegar enlace con 1 clic y barra de progreso en tiempo real con velocidad y tiempo estimado.
- 📂 **Selector de destino y acceso rápido:** Guarda en tu carpeta de Descargas por defecto o en la que tú elijas, con botón para abrirla directamente al finalizar.

---

## 🛠️ Instalación en otra máquina (Laptop / PC)

Para usar este proyecto en tu laptop o en cualquier otra computadora con Windows:

### 1. Clonar el repositorio
```bash
git clone https://github.com/SirDurand1708/UniversalMediaDownloader.git
cd UniversalMediaDownloader
```

### 2. Instalar FFmpeg
FFmpeg es necesario para unir los canales de video y audio en alta definición y para generar los archivos MP3. En Windows, puedes instalarlo rápidamente desde una terminal de PowerShell:

```powershell
winget install Gyan.FFmpeg.Essentials
```

### 3. Instalar las dependencias de Python
Asegúrate de tener Python 3.10 o superior instalado:

```bash
pip install -r requirements.txt
```

---

## ▶️ Cómo ejecutar la aplicación

Tienes dos formas de iniciar la aplicación:

1. **Doble clic:** Haz doble clic en el archivo `iniciar.bat`.
2. **Desde la terminal:**
   ```bash
   python app.py
   ```

---

## 📁 Estructura del Proyecto

```
UniversalMediaDownloader/
├── app.py              # Interfaz gráfica moderna (CustomTkinter)
├── downloader.py       # Lógica de descarga y conversión (yt-dlp + FFmpeg)
├── iniciar.bat         # Lanzador directo para Windows
├── requirements.txt    # Dependencias del proyecto
├── .gitignore          # Archivos excluidos de Git
└── README.md           # Documentación del proyecto
```
