# ✦ OmniVault Desktop v2.0 - Universal Media Hub & Creative Suite

<p align="center">
  <img src="assets/icon.png" width="128" height="128" alt="OmniVault Logo">
  <br>
  <b>Bóveda Multimedia Universal • Suite Creativa • Descargador de Alto Rendimiento</b>
</p>

> **Aplicación de escritorio de alto rendimiento para descarga, conversión, recorte y gestión multimedia.**  
> Diseñada en arquitectura modular con estética prémium *Obsidian & Titanium*, soporte multiresolución responsivo, aceleración por hardware GPU, aceleración multihilo con `aria2c`, normalización de audio EBU R128, transcriptor de subtítulos con IA, Mini-HUD flotante Picture-in-Picture y sincronización automática de creadores.

---

## 🎨 Paleta de Diseño: *Obsidian & Titanium*

- **Negro Obsidiana (`#0F1012`):** Base oscura profunda diseñada para eliminar la fatiga visual.
- **Grafito Oscuro (`#1A1D22`):** Paneles y tarjetas estructuradas de alto contraste.
- **Plata Titanio (`#E2E8F0` / `#FFFFFF`):** Botones principales de acción táctil.
- **Color de Acento Personalizable:** Elige entre 5 temas de acento:
  - 🟣 **Cyberpunk Violet** (Predeterminado)
  - 🔵 **Electric Blue**
  - 🟢 **Emerald Matrix**
  - 🟠 **Sunset Orange**
  - 🌸 **Rose Quartz**

---

## 🚀 Guía Completa de Funcionalidades

### 1. 📥 Descargas Individuales y Detección Automática
- Pega cualquier enlace de más de 1000 plataformas compatibles (**YouTube, TikTok, SoundCloud, Instagram, Facebook, Twitter/X, Twitch**, etc.).
- **Smart Auto-Paste:** Al copiar un enlace en tu navegador o cualquier programa, OmniVault lo detecta en el portapapeles y te muestra un banner flotante para analizarlo con 1 clic sin tener que pegarlo manualmente.
- **Separación Inteligente de Carpetas:**
  - Los audios (`MP3`, `WAV`, `M4A`, `FLAC`) se guardan automáticamente en tu carpeta del sistema: `Música\OmniVault Audios`.
  - Los videos (`MP4`, `MKV`) se guardan automáticamente en: `Videos\OmniVault Videos`.
  - Puedes cambiar ambas rutas en cualquier momento desde **⚙ Ajustes**.

---

### 2. ✂️ Recorte de Fragmentos de Audio y Video (*Trim & Clip*)
¿Solo necesitas el estribillo de una canción, el solo de guitarra o una escena específica de un podcast de 3 horas?
1. Pega y analiza el enlace en la pestaña **Explorar / Buscar**.
2. En la tarjeta de vista previa, marca la casilla: **✂️ Recortar fragmento específico**.
3. Ingresa los tiempos:
   - **Desde (Inicio):** Ejemplos: `01:15` o `00:01:15` o `75`.
   - **Hasta (Fin):** Ejemplos: `02:30` o `00:02:30` (o déjalo vacío para descargar desde el inicio indicado hasta el final del video).
4. Elige tu formato preferido y haz clic en **⬇ DESCARGAR AHORA**.
5. OmniVault recortará el fragmento exacto con precisión de milisegundos mediante FFmpeg.

---

### 3. 📊 Estimación de Peso y Tamaño de Archivo
Antes de iniciar cualquier descarga, OmniVault analiza los flujos disponibles y te muestra el **peso aproximado** que ocupará cada calidad en tu disco duro:
- `MP3 320 kbps HD (~8.4 MB)`
- `MP3 192 kbps (~5.0 MB)`
- `MP4 1080p Full HD (~72.5 MB)`
- `MP4 720p HD (~36.0 MB)`
- etc.

---

### 4. 🔄 Conversor Multimedia Local Offline (*Herramienta Offline*)
OmniVault incluye un conversor multimedia completo que opera **100% de manera local en tu PC**, sin conexión a internet y sin subir tus archivos privados a servidores externos:
1. En la barra lateral, haz clic en **🔄 Conversor Local**.
2. Haz clic en **📁 Examinar...** y selecciona cualquier archivo de tu ordenador (`.mp4`, `.mkv`, `.avi`, `.mov`, `.flv`, `.webm`, `.mp3`, `.wav`, `.m4a`, `.aac`, `.flac`, `.ogg`).
3. El programa detectará automáticamente la duración, tamaño y tipo de flujo (audio/video).
4. Elige el formato de salida:
   - **🎵 Formatos de Audio:** `MP3 (320 kbps HD)`, `MP3 (192 kbps)`, `WAV (Estudio)`, `M4A (AAC 256k)` o `FLAC (Lossless)`.  
     *(¡Ideal para extraer el audio de cualquier video en 1 clic!)*
   - **🎬 Formatos de Video:** `MP4 H.264` o **Comprimir Video (Reduce hasta un 50% el tamaño manteniendo gran nitidez)**.
5. Selecciona la carpeta de destino y presiona **⚡ INICIAR CONVERSIÓN**.
6. Observa la barra de progreso en vivo con porcentaje, velocidad de procesamiento (`4.5x`) y tiempo restante.
7. Al terminar, presiona **📂 Abrir Archivo** para reproducirlo o compartirlo de inmediato.

---

### 5. ⚡ Aceleración de Red (Aria2c) y Hardware GPU
- **Acelerador Multihilo Aria2c:**
  - OmniVault detecta automáticamente `aria2c.exe` (en `bin/`, en `Documents/Software` o en tu `PATH`).
  - Cada descarga pesada se divide en **8 conexiones simultáneas en paralelo** (`-s 8 -x 8 -k 1M -j 4`), multiplicando la velocidad de descarga hasta por 5x.
- **Aceleración por Tarjeta Gráfica (GPU):**
  - Detección automática de hardware:
    - 🔴 **AMD Radeon:** Codificación por hardware **AMD AMF** (`h264_amf`).
    - 🟢 **NVIDIA GeForce:** Codificación por hardware **NVIDIA NVENC** (`h264_nvenc`).
    - 🔵 **Intel Graphics:** Codificación por hardware **Intel QuickSync** (`h264_qsv`).
  - Convierte y comprime videos a velocidades ultra rápidas sin sobrecargar tu procesador (CPU).

---

### 6. 🔕 Minimizar a la Bandeja del Sistema (*System Tray / Reloj de Windows*)
- Al cerrar la ventana o minimizarla, OmniVault puede quedarse discretamente en segundo plano al lado del reloj de Windows.
- Permite que las descargas largas continúen sin interrumpirse y que el detector inteligente de portapapeles siga vigilando mientras juegas o trabajas.
- Haz clic derecho sobre el icono en la bandeja para:
  - **✦ Abrir OmniVault**
  - **⏸ / ▶ Pausar o Reanudar Descargas**
  - **❌ Salir de OmniVault**
- Puedes activar o desactivar este comportamiento en cualquier momento desde **⚙ Ajustes**.

---

### 7. 💤 Acciones Automáticas al Terminar (*Apagado / Suspender Nocturno*)
Ideal para dejar descargando listas de reproducción pesadas o cursos antes de irte a dormir:
1. Ve a **⚙ Ajustes** > **Acción al completar todas las descargas**.
2. Selecciona entre:
   - **Ninguna**
   - **Sonido de campana** (Timbre discreto de aviso)
   - **Suspender equipo**
   - **Apagar equipo (aviso 30s)**
3. Cuando finalice la última descarga de la cola, OmniVault activará la acción elegida.
4. Si elegiste **Apagar equipo**, aparecerá una ventana en pantalla con una cuenta regresiva de 30 segundos y un botón rojo **✖ CANCELAR APAGADO** por si sigues frente al ordenador.

---

### 8. 🎵 Motor Especializado para TikTok (HD Sin Marcas de Agua)
- Descarga directa de videos en resolución nativa HD sin marcas de agua de TikTok.
- Extracción de pistas de audio originales (MP3/M4A).
- Evasión automática de bloqueos de IP y retos anti-bot sin configuración técnica.

---

### 9. 📋 Playlists, Álbumes y Descarga Masiva
- Pega cualquier enlace de playlist o álbum completo de YouTube o SoundCloud.
- Elige qué canciones descargar marcando o desmarcando casillas.
- Botones de acción rápida: `[✓ Todos]`, `[✖ Ninguno]` y descarga en lote con un solo clic.

---

### 10. 🎧 Mini-Reproductor de Audio Integrado (Nativo MCI)
- Reproductor de audio integrado en la barra inferior para escuchar tus canciones **sin abrir programas externos**.
- Basado en la API nativa **Windows MCI (`winmm.dll`)**: 0% consumo de CPU adicional y cero dependencias pesadas.
- Barra de desplazamiento interactiva (*Seekbar*), control de volumen suave y contador de tiempo.

---

### 11. 🔒 Sesión Segura y Cookies (Para cualquier navegador)
Por defecto, **no necesitas iniciar sesión para descargar el 95% del contenido**. Solo se requiere sesión para:
- Videos con restricción de edad (+18).
- Playlists privadas personales (como "Videos que me gustan").
- Pistas protegidas por el filtro anti-bot de YouTube.

#### ⭐ Método 1: Archivo `cookies.txt` (Recomendado y 100% Universal)
Funciona en **Chrome, Edge, Firefox, Vivaldi, Brave, Opera**:
1. Instala la extensión gratuita:
   - Para navegadores Chromium: [Get cookies.txt LOCALLY (Chrome Web Store)](https://chromewebstore.google.com/detail/get-cookiestxt-locally/cclelndahbckbenkjhflpdbgdldlbecc).
   - Para Firefox: [cookies.txt (Firefox Add-ons)](https://addons.mozilla.org/es/firefox/addon/cookie-txt/).
2. Abre [YouTube.com](https://www.youtube.com) con tu cuenta abierta.
3. Haz clic en la extensión y presiona **Export** (descargará un archivo `.txt`).
4. En OmniVault, ve a **⚙ Ajustes** > **📄 Cargar archivo cookies.txt**.
5. Presiona **🧪 Probar Estado de Sesión** para comprobar la autenticación.

#### ⚡ Método 2: Extracción Directa desde Navegador (Experimental)
Compatible con **Vivaldi, Firefox, Brave y Opera**:
1. Cierra completamente todas las ventanas de tu navegador y espera 3 segundos (para liberar el bloqueo de archivo en Windows).
2. En OmniVault, ve a **⚙ Ajustes**, selecciona tu navegador y presiona **📸 Sincronizar**.
3. ¡Listo! Vuelve a abrir tu navegador normalmente.

---

### 12. 🔊 Normalización Inteligente de Volumen (EBU R128 / Spotify Standard)
¿Cansado de que una canción suene demasiado baja y la siguiente te aturda los oídos?
- OmniVault integra el filtro profesional de masterización de volumen **EBU R128 (`loudnorm=I=-16:TP=-1.5:LRA=11`)**, el mismo estándar adoptado por **Spotify, Apple Music y YouTube**.
- **En descargas:** Activa la casilla en **⚙ Ajustes** > *Normalización inteligente de volumen*. Todos los audios y videos descargados tendrán un nivel sonoro homogéneo y balanceado.
- **En el conversor local:** Marca la casilla *🔊 Aplicar normalización inteligente de volumen EBU R128* al convertir cualquier canción o video de tu PC.

---

### 13. 🤖 Transcriptor con IA & Generador de Subtítulos (.SRT / .TXT)
Genera subtítulos estándar y transcripciones completas de cualquier video, podcast o canción local:
1. Dirígete a la pestaña **🔄 Conversor Local**.
2. Selecciona cualquier archivo de video o audio en el Paso 1.
3. En la tarjeta **🤖 Generador de Subtítulos y Transcripción con IA**, selecciona el idioma del audio (**Español, Inglés, Portugués, Francés, Alemán o Italiano**).
4. Haz clic en **📝 GENERAR SUBTÍTULOS (.SRT Y .TXT)**.
5. OmniVault extraerá el flujo de voz en 16kHz mono, fragmentará el audio y reconocerá los diálogos generando:
   - Archivo `.srt` con marcas de tiempo sincronizadas con precisión de milisegundos para reproductores como VLC o edición de video.
   - Archivo `.txt` plano con el texto completo organizado en párrafos legibles.
6. Al finalizar, haz clic en **📄 Abrir .SRT** para revisar tus subtítulos al instante.

---

### 14. 📌 Mini-Widget Flotante (Floating Mini-HUD / Picture-in-Picture)
Mantén el control total de OmniVault sin tener que cambiar de ventana mientras juegas, estudias o trabajas:
- **Siempre Visible al Frente:** Un widget compacto y sin marco flotante (`Always-On-Top`) que puedes arrastrar a cualquier esquina de tu pantalla.
- **Información en Tiempo Real:** Visualiza la velocidad de descarga actual (`⚡ 4.8 MB/s`), porcentaje de progreso y cuántos elementos restan en cola.
- **Control de Cola:** Botón rápido para **pausar (⏸)** y **reanudar (▶)** todas las descargas con 1 clic.
- **Descargas Ultrarrápidas Directas:** Pega cualquier enlace directamente en la barra del HUD y presiona **⬇ Bajar** para añadirlo a la cola sin abrir la app completa.
- **Control Musical:** Muestra el título de la canción en reproducción y su estado.
- **Cómo abrirlo:**
  - Presiona el botón **📌 Mini-Widget HUD** en la barra lateral de OmniVault.
  - O haz clic derecho en el icono de la bandeja del sistema (al lado del reloj) y selecciona **📌 Mini-Widget Flotante (HUD)**.

---

### 15. 📡 Monitoreo Automático de Canales y Creadores (Auto-Sync)
¿Sigues a canales de YouTube o creadores de TikTok y quieres tener sus nuevos videos o canciones en cuanto se publiquen?
1. Ve a la nueva pestaña **📡 Canales / Sync** en la barra lateral.
2. Pega el enlace del canal (por ejemplo: `https://www.youtube.com/@Canal` o tu perfil favorito).
3. Selecciona el formato deseado para las descargas automáticas (`MP3 320k HD`, `MP4 1080p Full HD`, etc.).
4. Presiona **➕ Suscribirse**.
5. OmniVault monitoreará periódicamente en segundo plano si el canal publica nuevos contenidos.
6. En cuanto detecte un nuevo lanzamiento, lo descargará automáticamente a tu carpeta de Música o Videos y te avisará con una notificación nativa de Windows.
7. Puedes activar o desactivar la auto-descarga por canal en cualquier momento o presionar **🔄 Comprobar Novedades Ahora** para forzar un escaneo inmediato.

---

### 16. ⌨ Atajos de Teclado Rápidos
- **`Ctrl + V`:** Pega y analiza automáticamente cualquier enlace del portapapeles.
- **`Ctrl + F`:** Abre la biblioteca y enfoca el buscador.
- **`Barra Espaciadora`:** Pausa o reanuda la música del mini-reproductor.
- **`Escape`:** Oculta el mini-reproductor.

---

## ⚙️ Instalación y Requisitos

### 1. Requisitos Previos
- **Windows 10 u 11** (64 bits).
- **Python 3.10 o superior** (o utiliza el ejecutable compilado).

### 2. Instalación de dependencias
Abre la consola en la carpeta del proyecto y ejecuta:
```bash
py -m pip install -r requirements.txt
```

### 3. FFmpeg y Aria2c
- **FFmpeg:** Ya está detectado automáticamente si lo instalaste con winget (`winget install Gyan.FFmpeg`) o colocándolo en la carpeta `bin/`.
- **Aria2c:** Ya está integrado en la subcarpeta `bin/aria2c.exe`.

---

## ▶️ Cómo ejecutar la aplicación

### Modo Ventana Limpia (Sin Terminal / Ventana Negra)
Haz doble clic en:
```text
iniciar.bat
```
*(Utiliza el lanzador `pyw` nativo de Windows: la ventana se abre directamente y la consola se cierra en milisegundos).*

### Modo Depuración (Con Consola)
Si quieres ver registros técnicos detallados en la terminal:
```text
iniciar_consola.bat
```

---

## 📦 Creación del Instalador Oficial de Windows (.exe / Setup)

### Paso 1: Compilar a Ejecutable Autónomo (.exe)
Haz doble clic en:
```text
compilar.bat
```
O ejecuta en consola:
```bash
py build_exe.py
```
Este proceso empaqueta automáticamente todo el código, librerías, CustomTkinter, FFmpeg y Aria2c dentro de la carpeta:
```text
dist\OmniVault\OmniVault.exe
```

### Paso 2: Generar el Instalador Setup (Inno Setup)
Si deseas un instalador clásico de Windows con asistente de instalación, accesos directos en el Escritorio y Menú Inicio:
1. Descarga e instala [Inno Setup (Gratuito)](https://jrsoftware.org/isdl.php).
2. Haz clic derecho en [`installer.iss`](file:///c:/Users/georg/OneDrive/Documentos/GitHub/UniversalMediaDownloader/installer.iss) y selecciona **Compile** (o presiona `F9` dentro de Inno Setup).
3. Obtendrás el instalador final en:
   ```text
   dist\Installer\OmniVault_Setup_v2.0.exe
   ```

---

## 🛡️ Seguridad y Privacidad
- **Cero telemetría y sin publicidad.**
- **Código abierto y transparente.**
- **Tus contraseñas nunca se solicitan ni se almacenan.**
- **Los archivos locales de configuración y cookies están estrictamente aislados y excluidos de Git.**
