"""
Módulo de Integración con la Bandeja del Sistema (System Tray) para OmniVault Desktop.
Permite minimizar la aplicación al área de notificación de Windows al lado del reloj.
"""

import sys
import threading
from typing import Callable, Optional
from PIL import Image, ImageDraw

try:
    import pystray
    PYSTRAY_AVAILABLE = True
except ImportError:
    pystray = None
    PYSTRAY_AVAILABLE = False


def create_default_tray_image(color: str = "#8B5CF6") -> Image.Image:
    """Crea una imagen estilizada para el icono de la bandeja del sistema en memoria."""
    size = (64, 64)
    image = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    # Fondo redondeado / hexágono
    draw.rounded_rectangle([4, 4, 60, 60], radius=14, fill=color)

    # Dibujar diseño estilizado de "V" / Bóveda en blanco
    points = [(18, 20), (32, 46), (46, 20), (38, 20), (32, 33), (26, 20)]
    draw.polygon(points, fill="white")

    # Detalle de arco superior
    draw.ellipse([26, 12, 38, 24], outline="white", width=3)

    return image


class OmniVaultTray:
    def __init__(
        self,
        on_open: Optional[Callable[[], None]] = None,
        on_toggle_queue: Optional[Callable[[], None]] = None,
        on_quit: Optional[Callable[[], None]] = None,
        theme_color: str = "#8B5CF6",
    ):
        self.on_open = on_open
        self.on_toggle_queue = on_toggle_queue
        self.on_quit = on_quit
        self.theme_color = theme_color
        self._icon: Optional[pystray.Icon] = None
        self._thread: Optional[threading.Thread] = None
        self._is_running = False

    def is_available(self) -> bool:
        """Indica si el soporte de bandeja está disponible en el entorno."""
        return PYSTRAY_AVAILABLE

    def start(self):
        """Inicia el icono en la bandeja del sistema en un hilo secundario."""
        if not PYSTRAY_AVAILABLE or self._is_running:
            return

        def _setup_and_run():
            image = create_default_tray_image(self.theme_color)
            menu = pystray.Menu(
                pystray.MenuItem("✦ Abrir OmniVault", self._handle_open, default=True),
                pystray.MenuItem("⏸ / ▶ Pausar / Reanudar Cola", self._handle_toggle_queue),
                pystray.Menu.SEPARATOR,
                pystray.MenuItem("❌ Salir de OmniVault", self._handle_quit)
            )
            self._icon = pystray.Icon("OmniVault", image, "OmniVault Desktop", menu)
            self._is_running = True
            try:
                self._icon.run()
            except Exception:
                pass
            finally:
                self._is_running = False

        self._thread = threading.Thread(target=_setup_and_run, daemon=True)
        self._thread.start()

    def update_icon(self, color: str):
        """Actualiza el color del icono en la bandeja según el tema activo."""
        self.theme_color = color
        if self._icon and self._is_running:
            try:
                self._icon.icon = create_default_tray_image(color)
            except Exception:
                pass

    def stop(self):
        """Detiene y elimina el icono de la bandeja del sistema."""
        if self._icon and self._is_running:
            try:
                self._icon.stop()
            except Exception:
                pass
            self._is_running = False

    def _handle_open(self, icon=None, item=None):
        if self.on_open:
            self.on_open()

    def _handle_toggle_queue(self, icon=None, item=None):
        if self.on_toggle_queue:
            self.on_toggle_queue()

    def _handle_quit(self, icon=None, item=None):
        self.stop()
        if self.on_quit:
            self.on_quit()
        else:
            sys.exit(0)
