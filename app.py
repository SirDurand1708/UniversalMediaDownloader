"""
OmniVault Desktop - Universal Media Hub & Playlist Archiver
Interfaz prémium 'Obsidian & Titanium' completamente responsiva.
Soporte universal para YouTube, TikTok (sin marcas de agua), Reels, Playlists y Sesión Segura.
"""

import os
import sys
import threading
import subprocess
import tkinter as tk
from tkinter import filedialog, messagebox
import customtkinter as ctk

import theme
import themes
from tray import OmniVaultTray
from converter import LocalConverter, get_media_info
from transcriber import MediaTranscriber
from subscriptions_manager import SubscriptionsManager
from mini_hud import MiniHUD
from downloader import (
    DownloaderEngine, is_url, normalize_url, clean_error_text,
    is_ffmpeg_available, FFMPEG_DIR, is_browser_running,
    is_aria2c_available, detect_hardware_acceleration, format_duration,
    parse_timestamp_str, estimate_sizes_for_video
)
from queue_manager import QueueManager, DownloadTask
from history_manager import HistoryManager
from image_cache import ThumbnailCache
from config_manager import ConfigManager
from audio_player import AudioPlayer
from notifications import send_notification

import traceback
import time

def get_app_dir() -> str:
    """Devuelve la ruta raíz de la aplicación, compatible con Python directo y PyInstaller."""
    if getattr(sys, 'frozen', False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))

def _global_exception_handler(exc_type, exc_value, exc_traceback):
    """Captura errores no manejados, escribe en crash_log.txt y muestra una alerta."""
    if issubclass(exc_type, KeyboardInterrupt):
        sys.__excepthook__(exc_type, exc_value, exc_traceback)
        return
    err_text = "".join(traceback.format_exception(exc_type, exc_value, exc_traceback))
    try:
        log_path = os.path.join(get_app_dir(), "crash_log.txt")
        with open(log_path, "a", encoding="utf-8") as f:
            f.write(f"\n--- ERROR {time.strftime('%Y-%m-%d %H:%M:%S')} ---\n")
            f.write(err_text)
    except Exception:
        pass
    try:
        import tkinter.messagebox as mb
        mb.showerror("Error Inesperado en OmniVault", f"Ocurrió un error al ejecutar la aplicación:\n\n{err_text[:400]}")
    except Exception:
        pass

sys.excepthook = _global_exception_handler

ctk.set_appearance_mode("Dark")
ctk.set_default_color_theme("blue")

class OmniVaultApp(ctk.CTk):
    def __init__(self):
        super().__init__()

        self.title("OmniVault Desktop - Universal Media Hub")
        self.geometry("1060x720")
        # Tamaño mínimo adaptable a pantallas de laptops pequeñas o con escalado DPI (125%/150%)
        self.minsize(760, 500)
        self.configure(fg_color=theme.BG_MAIN)

        # Icono oficial de la ventana
        base_dir = get_app_dir()
        ico_file = os.path.join(base_dir, "assets", "icon.ico")
        if not os.path.isfile(ico_file) and hasattr(sys, '_MEIPASS'):
            cand = os.path.join(sys._MEIPASS, "assets", "icon.ico")
            if os.path.isfile(cand):
                ico_file = cand
        if os.path.isfile(ico_file):
            try:
                self.iconbitmap(ico_file)
            except Exception:
                pass

        # Configuración persistente y tema de color de acento
        self.config = ConfigManager()
        self.theme_accent = themes.get_theme(self.config.get("accent_theme", "violet"))

        # Servicios y Motores
        self.downloader = DownloaderEngine()
        self.history = HistoryManager()
        self.queue_manager = QueueManager(
            self.downloader,
            self.history,
            max_workers=self.config.get("max_workers", 2)
        )
        self.img_cache = ThumbnailCache(max_workers=4)

        # Conversor multimedia offline y transcriptor de subtítulos IA
        self.converter = LocalConverter()
        self._converter_running = False
        self._converted_file_path = ""
        self.transcriber = MediaTranscriber()
        self._transcription_running = False
        self._transcribed_srt_path = ""

        # Gestor de monitoreo de canales y creadores (Auto-Sync)
        self.subscriptions_manager = SubscriptionsManager()

        # Callbacks del gestor de descargas
        self.queue_manager.on_task_updated = self._on_task_updated
        self.queue_manager.on_task_finished = self._on_task_finished
        self.queue_manager.on_queue_completed = self._on_queue_all_completed

        # Reproductor de audio integrado nativo
        self.player = AudioPlayer()
        self._is_player_visible = False
        self._is_seeking = False

        # Mini-Widget Flotante siempre visible (Mini-HUD / Picture-in-Picture)
        self.mini_hud = MiniHUD(
            self,
            self.queue_manager,
            self.player,
            self.config,
            on_restore_callback=self._restore_from_tray
        )

        # Bandeja del sistema (System Tray)
        self.tray = OmniVaultTray(
            on_open=self._tray_restore,
            on_toggle_queue=self._tray_toggle_queue,
            on_toggle_hud=self._tray_toggle_hud,
            on_quit=lambda: self._on_close(force=True),
            theme_color=self.theme_accent["primary"]
        )
        if self.config.get("tray_enabled", True) and self.tray.is_available():
            self.tray.start()

        # Iniciar sincronización automática de canales si está habilitada
        if self.config.get("sync_channels_auto", True):
            self.subscriptions_manager.start_background_sync(
                interval_seconds=int(self.config.get("sync_channels_interval_min", 30)) * 60,
                queue_manager=self.queue_manager,
                get_target_dir_func=self._get_target_dir
            )

        # Protocolo de cierre limpio de ventana
        self.protocol("WM_DELETE_WINDOW", self._on_close)

        # Estado actual
        self.current_media = None
        self.playlist_entries = []
        self.playlist_check_vars = []
        self.search_results = []
        self.search_check_vars = []
        self.selected_format = ctk.StringVar(value=self.config.get("default_format", "mp3_320"))

        # Detección inteligente de portapapeles
        self._last_detected_clipboard = ""
        self._clipboard_banner_visible = False

        self._build_layout()
        self._select_tab("explore")

        # Configuración de atajos de teclado rápidos
        self._setup_keyboard_shortcuts()

        # Ciclo de actualización de timeline del reproductor
        self.after(500, self._update_player_loop)

        # Iniciar monitoreo del portapapeles si está activado
        self.after(1500, self._check_clipboard_loop)

    def _on_close(self, force: bool = False):
        """Maneja el cierre ordenado de la aplicación o la minimiza a la bandeja del sistema."""
        if not force and self.config.get("tray_enabled", True) and self.tray.is_available():
            self.withdraw()
            return

        active_tasks = self.queue_manager.get_active_tasks()
        if active_tasks:
            confirm = messagebox.askyesno(
                "Descargas en curso",
                f"Hay {len(active_tasks)} descargas en progreso.\n¿Estás seguro de que deseas salir y cancelar las descargas activas?"
            )
            if not confirm:
                return

        self.subscriptions_manager.stop_background_sync()
        self.mini_hud.hide_hud()
        self.tray.stop()
        self.player.close()
        self.queue_manager.stop()
        self.img_cache.shutdown(wait=False)
        self.destroy()

    def _tray_restore(self):
        self.after(0, self._restore_from_tray)

    def _restore_from_tray(self):
        self.deiconify()
        self.lift()
        self.focus_force()

    def _tray_toggle_hud(self):
        self.after(0, self.mini_hud.toggle_hud)

    def _tray_toggle_queue(self):
        is_paused = self.queue_manager.toggle_pause_queue()
        status_text = "Cola de descargas: Pausada" if is_paused else "Cola de descargas: Reanudada"
        try:
            send_notification("✦ OmniVault", status_text)
        except Exception:
            pass

    def _get_active_browser(self):
        saved = os.path.join(get_app_dir(), "saved_cookies.txt")
        if os.path.exists(saved) and os.path.getsize(saved) > 0:
            return None
        b = self.config.get("browser_cookies", "none")
        return None if b == "none" else b

    def _get_target_dir(self, format_type: str) -> str:
        """Enruta descargas de audio a la carpeta de música y videos a la carpeta de videos."""
        if format_type in ["mp3_320", "mp3_192", "m4a_best", "wav"]:
            return self.config.get_audio_dir()
        return self.config.get_video_dir()

    def _build_layout(self):
        self.grid_rowconfigure(0, weight=1)
        self.grid_rowconfigure(1, weight=0)                  # Fila para el reproductor inferior
        self.grid_columnconfigure(0, weight=0, minsize=210)  # Barra lateral
        self.grid_columnconfigure(1, weight=1)               # Contenedor dinámico principal

        # --- BARRA LATERAL ---
        self.sidebar = ctk.CTkFrame(
            self,
            width=210,
            corner_radius=0,
            fg_color=theme.BG_SIDEBAR,
            border_width=1,
            border_color=theme.BORDER_COLOR
        )
        self.sidebar.grid(row=0, column=0, sticky="nsew")
        self.sidebar.grid_propagate(False)

        brand_frame = ctk.CTkFrame(self.sidebar, fg_color="transparent")
        brand_frame.pack(fill="x", padx=14, pady=(18, 14))

        brand_row = ctk.CTkFrame(brand_frame, fg_color="transparent")
        brand_row.pack(fill="x")

        base_dir = get_app_dir()
        icon_path = os.path.join(base_dir, "assets", "icon.png")
        if not os.path.isfile(icon_path) and hasattr(sys, '_MEIPASS'):
            cand = os.path.join(sys._MEIPASS, "assets", "icon.png")
            if os.path.isfile(cand):
                icon_path = cand
        if os.path.isfile(icon_path):
            try:
                from PIL import Image
                pil_icon = Image.open(icon_path)
                self.logo_img = ctk.CTkImage(light_image=pil_icon, dark_image=pil_icon, size=(38, 38))
                logo_lbl = ctk.CTkLabel(brand_row, text="", image=self.logo_img)
                logo_lbl.pack(side="left", padx=(0, 10))
            except Exception:
                pass

        titles_col = ctk.CTkFrame(brand_row, fg_color="transparent")
        titles_col.pack(side="left", fill="both", expand=True)

        logo_title = ctk.CTkLabel(
            titles_col,
            text="OMNIVAULT",
            font=ctk.CTkFont(family="Segoe UI", size=18, weight="bold"),
            text_color=theme.TEXT_PRIMARY
        )
        logo_title.pack(anchor="w")

        logo_sub = ctk.CTkLabel(
            titles_col,
            text="UNIVERSAL MEDIA HUB",
            font=ctk.CTkFont(family="Segoe UI", size=8, weight="bold"),
            text_color=self.theme_accent["text_accent"]
        )
        logo_sub.pack(anchor="w")

        # Botones de Navegación Lateral
        self.nav_btns = {}
        nav_items = [
            ("explore", "🔍  Explorar / Buscar", self._show_explore),
            ("downloads", "📥  Descargas", self._show_downloads),
            ("library", "🎵  Mi Biblioteca", self._show_library),
            ("converter", "🔄  Conversor Local", self._show_converter),
            ("subscriptions", "📡  Canales / Sync", self._show_subscriptions),
            ("settings", "⚙  Ajustes", self._show_settings),
        ]

        self.nav_frame = ctk.CTkFrame(self.sidebar, fg_color="transparent")
        self.nav_frame.pack(fill="x", padx=10, pady=6)

        for tab_id, text, cmd in nav_items:
            btn = ctk.CTkButton(
                self.nav_frame,
                text=text,
                anchor="w",
                height=38,
                corner_radius=theme.CORNER_RADIUS,
                font=ctk.CTkFont(family="Segoe UI", size=12, weight="bold"),
                fg_color="transparent",
                text_color=theme.TEXT_SECONDARY,
                hover_color=theme.BG_CARD_HOVER,
                command=cmd
            )
            btn.pack(fill="x", pady=2)
            self.nav_btns[tab_id] = btn

        # Botón para activar el Mini-Widget HUD flotante
        self.hud_btn = ctk.CTkButton(
            self.nav_frame,
            text="📌  Mini-Widget HUD",
            anchor="w",
            height=34,
            corner_radius=theme.CORNER_RADIUS,
            font=ctk.CTkFont(family="Segoe UI", size=11, weight="bold"),
            fg_color=theme.BG_INPUT,
            text_color=self.theme_accent["text_accent"],
            hover_color=theme.BG_CARD_HOVER,
            command=self.mini_hud.toggle_hud
        )
        self.hud_btn.pack(fill="x", pady=(8, 2))

        self.sidebar_spacer = ctk.CTkFrame(self.sidebar, fg_color="transparent")
        self.sidebar_spacer.pack(fill="both", expand=True)

        # Tarjeta de Estado en la barra lateral
        status_box = ctk.CTkFrame(
            self.sidebar,
            corner_radius=theme.CORNER_RADIUS_SM,
            fg_color=theme.BG_CARD,
            border_width=1,
            border_color=theme.BORDER_COLOR
        )
        status_box.pack(fill="x", padx=12, pady=14)

        saved_cookie = os.path.join(get_app_dir(), "saved_cookies.txt")
        has_saved = os.path.exists(saved_cookie) and os.path.getsize(saved_cookie) > 0
        self.session_lbl = ctk.CTkLabel(
            status_box,
            text="● Sesión: Activa" if has_saved else f"● Sesión: {self.config.get('browser_cookies', 'none').capitalize()}",
            font=ctk.CTkFont(size=11, weight="bold"),
            text_color=theme.STATUS_SUCCESS if (has_saved or self.config.get('browser_cookies') != 'none') else theme.TEXT_MUTED
        )
        self.session_lbl.pack(anchor="w", padx=10, pady=(6, 2))

        ff_ok = is_ffmpeg_available()
        ffmpeg_text = "● FFmpeg: Activo" if ff_ok else "⚠ FFmpeg: No detectado"
        ffmpeg_color = theme.TEXT_MUTED if ff_ok else theme.STATUS_WARNING

        privacy_lbl = ctk.CTkLabel(
            status_box,
            text=ffmpeg_text,
            font=ctk.CTkFont(size=10, weight="bold" if not ff_ok else "normal"),
            text_color=ffmpeg_color
        )
        privacy_lbl.pack(anchor="w", padx=10, pady=(0, 6))

        # --- ÁREA PRINCIPAL AUTORRESPONSIVA ---
        self.main_container = ctk.CTkFrame(self, fg_color=theme.BG_MAIN, corner_radius=0)
        self.main_container.grid(row=0, column=1, sticky="nsew", padx=(14, 16), pady=16)
        self.main_container.grid_rowconfigure(0, weight=1)
        self.main_container.grid_columnconfigure(0, weight=1)

        self.views = {
            "explore": self._create_explore_view(),
            "downloads": self._create_downloads_view(),
            "library": self._create_library_view(),
            "converter": self._create_converter_view(),
            "subscriptions": self._create_subscriptions_view(),
            "settings": self._create_settings_view(),
        }

        # Mini-Reproductor integrado en el pie de página
        self.player_frame = self._create_player_bar()

    def _select_tab(self, tab_id: str):
        for t_id, view in self.views.items():
            if t_id == tab_id:
                view.grid(row=0, column=0, sticky="nsew")
                self.nav_btns[t_id].configure(fg_color=theme.BG_CARD, text_color=theme.TEXT_PRIMARY)
            else:
                view.grid_forget()
                self.nav_btns[t_id].configure(fg_color="transparent", text_color=theme.TEXT_SECONDARY)

        if tab_id == "library":
            self._refresh_library_list()
        elif tab_id == "downloads":
            self._refresh_downloads_list()
        elif tab_id == "subscriptions":
            self._refresh_subscriptions_list()

    def _show_explore(self): self._select_tab("explore")
    def _show_downloads(self): self._select_tab("downloads")
    def _show_library(self): self._select_tab("library")
    def _show_converter(self): self._select_tab("converter")
    def _show_subscriptions(self): self._select_tab("subscriptions")
    def _show_settings(self): self._select_tab("settings")

    # =========================================================================
    # VISTA 1: EXPLORAR / BUSCAR / PLAYLISTS
    # =========================================================================
    def _create_explore_view(self) -> ctk.CTkFrame:
        view = ctk.CTkFrame(self.main_container, fg_color="transparent")
        view.grid_rowconfigure(3, weight=1)
        view.grid_columnconfigure(0, weight=1)

        # Banner de Detección Automática de Portapapeles (Smart Auto-Paste)
        self.clipboard_banner = ctk.CTkFrame(
            view,
            fg_color=theme.BG_CARD,
            corner_radius=theme.CORNER_RADIUS_SM,
            border_width=1,
            border_color=theme.ACCENT_CYAN
        )
        self.clipboard_lbl = ctk.CTkLabel(
            self.clipboard_banner,
            text="",
            font=ctk.CTkFont(size=11, weight="bold"),
            text_color=theme.ACCENT_CYAN,
            anchor="w"
        )
        self.clipboard_lbl.pack(side="left", padx=10, pady=6, fill="x", expand=True)

        self.clipboard_paste_btn = ctk.CTkButton(
            self.clipboard_banner,
            text="⚡ Analizar enlace copiado",
            width=160,
            height=28,
            fg_color=theme.BTN_PRIMARY_BG,
            hover_color=theme.BTN_PRIMARY_HOVER,
            text_color=theme.BTN_PRIMARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"),
            command=self._use_detected_clipboard
        )
        self.clipboard_paste_btn.pack(side="left", padx=(0, 6))

        ctk.CTkButton(
            self.clipboard_banner,
            text="✖",
            width=28,
            height=28,
            fg_color=theme.BTN_SECONDARY_BG,
            hover_color=theme.BTN_SECONDARY_HOVER,
            text_color=theme.BTN_SECONDARY_TEXT,
            command=self._dismiss_clipboard_banner
        ).pack(side="left", padx=(0, 8))

        # Barra de Búsqueda adaptable
        search_box = ctk.CTkFrame(view, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
        search_box.grid(row=1, column=0, sticky="ew", pady=(0, 10))

        self.search_entry = ctk.CTkEntry(
            search_box,
            placeholder_text="🔍  Pega un enlace (YouTube, TikTok, Reels, Twitter...) o busca por título...",
            font=ctk.CTkFont(size=13),
            height=42,
            fg_color=theme.BG_INPUT,
            border_width=0,
            text_color=theme.TEXT_PRIMARY
        )
        self.search_entry.pack(side="left", fill="x", expand=True, padx=(10, 6), pady=6)
        self.search_entry.bind("<Return>", lambda e: self._on_search_submit())

        paste_btn = ctk.CTkButton(
            search_box,
            text="📋 Pegar",
            width=70,
            height=36,
            fg_color=theme.BTN_SECONDARY_BG,
            hover_color=theme.BTN_SECONDARY_HOVER,
            text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=12, weight="bold"),
            command=self._paste_to_search
        )
        paste_btn.pack(side="left", padx=(0, 6))

        self.search_btn = ctk.CTkButton(
            search_box,
            text="Buscar / Analizar",
            width=125,
            height=36,
            fg_color=theme.BTN_PRIMARY_BG,
            hover_color=theme.BTN_PRIMARY_HOVER,
            text_color=theme.BTN_PRIMARY_TEXT,
            font=ctk.CTkFont(size=12, weight="bold"),
            command=self._on_search_submit
        )
        self.search_btn.pack(side="left", padx=(0, 8))

        self.analyze_status = ctk.CTkLabel(view, text="", font=ctk.CTkFont(size=12), text_color=theme.ACCENT_CYAN, anchor="w")
        self.analyze_status.grid(row=2, column=0, sticky="ew", pady=(0, 6))

        self.explore_scroll = ctk.CTkScrollableFrame(view, fg_color="transparent", corner_radius=theme.CORNER_RADIUS)
        self.explore_scroll.grid(row=3, column=0, sticky="nsew")
        self.explore_scroll.grid_columnconfigure(0, weight=1)

        self._show_explore_welcome()
        return view

    def _check_clipboard_loop(self):
        """Monitorea el portapapeles y notifica amigablemente si el usuario copia un enlace multimedia."""
        try:
            if self.config.get("clipboard_watch", True):
                clip = self.clipboard_get().strip()
                if is_url(clip) and clip != self._last_detected_clipboard and clip != self.search_entry.get().strip():
                    self._last_detected_clipboard = clip
                    short_display = clip if len(clip) <= 45 else clip[:42] + "..."
                    self.clipboard_lbl.configure(text=f"📋 Enlace detectado: {short_display}")
                    self.clipboard_banner.grid(row=0, column=0, sticky="ew", pady=(0, 8))
                    self._clipboard_banner_visible = True
        except Exception:
            pass
        finally:
            self.after(1800, self._check_clipboard_loop)

    def _use_detected_clipboard(self):
        if self._last_detected_clipboard:
            self.search_entry.delete(0, tk.END)
            self.search_entry.insert(0, self._last_detected_clipboard)
            self._dismiss_clipboard_banner()
            self._on_search_submit()

    def _dismiss_clipboard_banner(self):
        self.clipboard_banner.grid_forget()
        self._clipboard_banner_visible = False

    def _show_explore_welcome(self):
        for widget in self.explore_scroll.winfo_children():
            widget.destroy()

        welcome_frame = ctk.CTkFrame(self.explore_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS_LG, border_width=1, border_color=theme.BORDER_COLOR)
        welcome_frame.pack(fill="x", pady=16, padx=6)

        ctk.CTkLabel(
            welcome_frame,
            text="Descargas Multimedia & Playlists en Máxima Calidad",
            font=ctk.CTkFont(size=19, weight="bold"),
            text_color=theme.TEXT_PRIMARY
        ).pack(pady=(22, 6))

        ctk.CTkLabel(
            welcome_frame,
            text="Descarga canciones, videos individuales o playlists completas con selección múltiple.\nSoporte directo de TikTok sin marcas de agua, YouTube 4K, Instagram Reels, Twitter/X y SoundCloud.",
            font=ctk.CTkFont(size=12),
            text_color=theme.TEXT_SECONDARY,
            justify="center"
        ).pack(pady=(0, 18), padx=16)

        chips_box = ctk.CTkFrame(welcome_frame, fg_color="transparent")
        chips_box.pack(pady=(0, 20), padx=10)

        row1 = ["Playlists Completas", "YouTube 4K", "TikTok HD (Sin Marca)"]
        row2 = ["Instagram Reels", "Twitter / X", "SoundCloud HD"]

        f1 = ctk.CTkFrame(chips_box, fg_color="transparent")
        f1.pack(pady=2)
        for p in row1:
            ctk.CTkLabel(
                f1, text=f"  {p}  ", fg_color=theme.BG_INPUT,
                corner_radius=theme.CORNER_RADIUS_SM, font=ctk.CTkFont(size=11, weight="bold"),
                text_color=theme.TEXT_ACCENT
            ).pack(side="left", padx=4)

        f2 = ctk.CTkFrame(chips_box, fg_color="transparent")
        f2.pack(pady=4)
        for p in row2:
            ctk.CTkLabel(
                f2, text=f"  {p}  ", fg_color=theme.BG_INPUT,
                corner_radius=theme.CORNER_RADIUS_SM, font=ctk.CTkFont(size=11, weight="bold"),
                text_color=theme.TEXT_ACCENT
            ).pack(side="left", padx=4)

    def _paste_to_search(self):
        try:
            text = self.clipboard_get().strip()
            self.search_entry.delete(0, tk.END)
            self.search_entry.insert(0, text)
            self._on_search_submit()
        except Exception:
            pass

    def _on_search_submit(self):
        raw_text = self.search_entry.get().strip()
        if not raw_text:
            return

        self._dismiss_clipboard_banner()
        self.search_btn.configure(state="disabled", text="Analizando...")
        self.analyze_status.configure(text="Conectando y analizando contenido...", text_color=theme.ACCENT_CYAN)

        browser = self._get_active_browser()
        if is_url(raw_text):
            normalized = normalize_url(raw_text)
            threading.Thread(target=self._async_inspect_url, args=(normalized, browser), daemon=True).start()
        else:
            threading.Thread(target=self._async_search, args=(raw_text, browser), daemon=True).start()

    def _async_inspect_url(self, url: str, browser: str):
        try:
            data = self.downloader.inspect_url(url, browser=browser)
            if data.get("is_playlist"):
                self.after(0, lambda: self._display_playlist_selector(data))
            else:
                self.after(0, lambda: self._display_single_modal(data))
        except Exception as e:
            err = str(e)
            self.after(0, lambda: self._on_analyze_error(err))

    def _async_search(self, query: str, browser: str):
        try:
            results = self.downloader.search_youtube(query, max_results=8, browser=browser)
            self.after(0, lambda: self._display_search_results(results, query))
        except Exception as e:
            err = str(e)
            self.after(0, lambda: self._on_analyze_error(err))

    def _on_analyze_error(self, err_msg: str):
        self.search_btn.configure(state="normal", text="Buscar / Analizar")
        cleaned = clean_error_text(err_msg)
        first_line = cleaned.splitlines()[0] if cleaned else "Error desconocido"
        self.analyze_status.configure(text=f"✖ Error: {first_line[:65]}", text_color=theme.STATUS_ERROR)

        lower = err_msg.lower()
        if any(k in lower for k in ["bot", "sign in to confirm", "accede para confirmar"]):
            go_to_settings = messagebox.askyesno(
                "Verificación de YouTube Requerida",
                "YouTube ha solicitado verificación anti-bot para este video.\n\n"
                "Para poder descargarlo, necesitas sincronizar o cargar tus cookies de YouTube en Ajustes.\n\n"
                "¿Deseas abrir la pestaña de Ajustes ahora para sincronizar tus cookies?"
            )
            if go_to_settings:
                self._show_settings()
        else:
            messagebox.showerror("Error de Contenido", f"No se pudo procesar el enlace:\n\n{cleaned}")

    # --- RESULTADOS DE BÚSQUEDA ---
    def _display_search_results(self, results: list, query: str):
        self.search_btn.configure(state="normal", text="Buscar / Analizar")
        self.analyze_status.configure(text=f"Resultados para '{query}' (Marca casillas para descarga en lote)", text_color=theme.TEXT_SECONDARY)
        self.search_results = results
        self.search_check_vars = []

        for widget in self.explore_scroll.winfo_children():
            widget.destroy()

        if not results:
            ctk.CTkLabel(self.explore_scroll, text="No se encontraron contenidos coincidentes.", font=ctk.CTkFont(size=14), text_color=theme.TEXT_MUTED).pack(pady=40)
            return

        batch_bar = ctk.CTkFrame(self.explore_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
        batch_bar.pack(fill="x", pady=(0, 10), padx=4)

        b_left = ctk.CTkFrame(batch_bar, fg_color="transparent")
        b_left.pack(side="left", fill="x", expand=True, padx=8, pady=6)

        ctk.CTkButton(
            b_left, text="✓ Todos", width=75, height=30,
            fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER, text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"), command=self._select_all_search
        ).pack(side="left", padx=(0, 6))

        ctk.CTkButton(
            b_left, text="✖ Ninguno", width=80, height=30,
            fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER, text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"), command=self._deselect_all_search
        ).pack(side="left", padx=(0, 8))

        self.batch_search_lbl = ctk.CTkLabel(b_left, text="0 seleccionados", font=ctk.CTkFont(size=11, weight="bold"), text_color=theme.TEXT_PRIMARY)
        self.batch_search_lbl.pack(side="left", padx=4)

        self.batch_dl_btn = ctk.CTkButton(
            batch_bar, text="⬇ Descargar (0)", width=150, height=32,
            fg_color=theme.BTN_PRIMARY_BG, hover_color=theme.BTN_PRIMARY_HOVER, text_color=theme.BTN_PRIMARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"),
            command=self._download_selected_search
        )
        self.batch_dl_btn.pack(side="right", padx=10, pady=6)

        for idx, item in enumerate(results):
            var = ctk.BooleanVar(value=False)
            self.search_check_vars.append(var)

            card = ctk.CTkFrame(self.explore_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
            card.pack(fill="x", pady=4, padx=4)

            cb = ctk.CTkCheckBox(
                card, text="", variable=var, width=22, checkbox_width=18, checkbox_height=18,
                fg_color=theme.BTN_PRIMARY_BG, hover_color=theme.BTN_PRIMARY_HOVER,
                command=self._update_search_batch_count
            )
            cb.pack(side="left", padx=(10, 4))

            thumb_label = ctk.CTkLabel(card, text="", width=100, height=56, fg_color=theme.BG_INPUT, corner_radius=theme.CORNER_RADIUS_SM)
            thumb_label.pack(side="left", padx=4, pady=6)

            if item.get("thumbnail"):
                cached = self.img_cache.get_thumbnail(
                    item["thumbnail"],
                    size=(100, 56),
                    callback=lambda img, l=thumb_label: self.after(0, lambda: l.configure(image=img) if l.winfo_exists() else None)
                )
                if cached:
                    thumb_label.configure(image=cached)

            info_frame = ctk.CTkFrame(card, fg_color="transparent")
            info_frame.pack(side="left", fill="both", expand=True, padx=8, pady=6)

            title_txt = item.get("title", "")
            ctk.CTkLabel(info_frame, text=title_txt, font=ctk.CTkFont(size=12, weight="bold"), text_color=theme.TEXT_PRIMARY, anchor="w").pack(fill="x")
            ctk.CTkLabel(info_frame, text=f"{item.get('uploader')}  •  Duración: {item.get('duration')}", font=ctk.CTkFont(size=10), text_color=theme.TEXT_SECONDARY, anchor="w").pack(fill="x", pady=(2, 0))

            sel_btn = ctk.CTkButton(
                card, text="Abrir ➔", width=95, height=30,
                fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER, text_color=theme.BTN_SECONDARY_TEXT,
                font=ctk.CTkFont(size=11, weight="bold"),
                command=lambda url=item.get("url"): self._select_single_from_search(url)
            )
            sel_btn.pack(side="right", padx=10)

    def _select_single_from_search(self, url: str):
        self.search_entry.delete(0, tk.END)
        self.search_entry.insert(0, url)
        self._on_search_submit()

    def _select_all_search(self):
        for var in self.search_check_vars:
            var.set(True)
        self._update_search_batch_count()

    def _deselect_all_search(self):
        for var in self.search_check_vars:
            var.set(False)
        self._update_search_batch_count()

    def _update_search_batch_count(self):
        selected_count = sum(1 for v in self.search_check_vars if v.get())
        self.batch_search_lbl.configure(text=f"{selected_count} seleccionados")
        self.batch_dl_btn.configure(text=f"⬇ Descargar ({selected_count})")

    def _download_selected_search(self):
        selected_items = [self.search_results[i] for i, v in enumerate(self.search_check_vars) if v.get()]
        if not selected_items:
            messagebox.showwarning("Atención", "Por favor marca al menos un video de la lista con la casilla.")
            return

        fmt = self.selected_format.get()
        target_dir = self._get_target_dir(fmt)
        browser = self._get_active_browser()
        subtitles = bool(self.config.get("subtitles_enabled", False))
        aria2c = bool(self.config.get("aria2c_enabled", True))
        normalize = bool(self.config.get("normalize_audio", False))
        count = self.queue_manager.add_batch(
            selected_items, format_type=fmt, output_dir=target_dir,
            browser=browser, download_subtitles=subtitles, use_aria2c=aria2c,
            normalize_audio=normalize
        )
        messagebox.showinfo("Descarga en Lote", f"Se han añadido {count} elementos a la cola de descargas.")
        self._select_tab("downloads")

    # --- PANTALLA DE PLAYLISTS / ÁLBUMES COMPLETOS ---
    def _display_playlist_selector(self, data: dict):
        self.search_btn.configure(state="normal", text="Buscar / Analizar")
        self.analyze_status.configure(text=f"✔ Playlist verificada: {data.get('count')} pistas encontradas", text_color=theme.STATUS_SUCCESS)

        self.playlist_entries = data.get("entries", [])
        self.playlist_check_vars = [ctk.BooleanVar(value=True) for _ in self.playlist_entries]

        for widget in self.explore_scroll.winfo_children():
            widget.destroy()

        header_card = ctk.CTkFrame(self.explore_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS_LG, border_width=1, border_color=theme.BORDER_COLOR)
        header_card.pack(fill="x", pady=8, padx=4)

        h_row = ctk.CTkFrame(header_card, fg_color="transparent")
        h_row.pack(fill="x", padx=14, pady=14)

        thumb_lbl = ctk.CTkLabel(h_row, text="", width=120, height=68, fg_color=theme.BG_INPUT, corner_radius=theme.CORNER_RADIUS)
        thumb_lbl.pack(side="left", padx=(0, 14))

        if data.get("thumbnail"):
            cached = self.img_cache.get_thumbnail(
                data["thumbnail"],
                size=(120, 68),
                callback=lambda img, l=thumb_lbl: self.after(0, lambda: l.configure(image=img) if l.winfo_exists() else None)
            )
            if cached:
                thumb_lbl.configure(image=cached)

        info_col = ctk.CTkFrame(h_row, fg_color="transparent")
        info_col.pack(side="left", fill="both", expand=True)

        ctk.CTkLabel(info_col, text="✦ LISTA DE REPRODUCCIÓN / ÁLBUM", font=ctk.CTkFont(size=10, weight="bold"), text_color=theme.ACCENT_CYAN, anchor="w").pack(fill="x")
        ctk.CTkLabel(info_col, text=data.get("title", ""), font=ctk.CTkFont(size=15, weight="bold"), text_color=theme.TEXT_PRIMARY, anchor="w").pack(fill="x", pady=2)
        ctk.CTkLabel(info_col, text=f"Por: {data.get('uploader')}  •  Total: {data.get('count')} elementos", font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY, anchor="w").pack(fill="x")

        action_box = ctk.CTkFrame(header_card, fg_color=theme.BG_INPUT, corner_radius=theme.CORNER_RADIUS)
        action_box.pack(fill="x", padx=14, pady=(0, 14))

        top_act = ctk.CTkFrame(action_box, fg_color="transparent")
        top_act.pack(fill="x", padx=10, pady=(8, 4))

        ctk.CTkButton(
            top_act, text="✓ Seleccionar Toda", width=140, height=30,
            fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER, text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"), command=self._select_all_playlist
        ).pack(side="left", padx=(0, 6))

        ctk.CTkButton(
            top_act, text="✖ Desmarcar", width=100, height=30,
            fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER, text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"), command=self._deselect_all_playlist
        ).pack(side="left")

        self.playlist_count_lbl = ctk.CTkLabel(
            top_act,
            text=f"{len(self.playlist_entries)} / {len(self.playlist_entries)} seleccionados",
            font=ctk.CTkFont(size=11, weight="bold"),
            text_color=theme.TEXT_PRIMARY
        )
        self.playlist_count_lbl.pack(side="right", padx=6)

        fmt_frame = ctk.CTkFrame(action_box, fg_color="transparent")
        fmt_frame.pack(fill="x", padx=10, pady=(0, 8))

        row_audio = ctk.CTkFrame(fmt_frame, fg_color="transparent")
        row_audio.pack(fill="x", pady=2)
        ctk.CTkLabel(row_audio, text="Audio:", font=ctk.CTkFont(size=11, weight="bold"), width=50, anchor="w").pack(side="left")
        for val, lbl in [("mp3_320", "MP3 320k HD"), ("mp3_192", "MP3 192k"), ("m4a_best", "M4A Original")]:
            ctk.CTkRadioButton(
                row_audio, text=lbl, variable=self.selected_format, value=val,
                font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY,
                fg_color=theme.BTN_PRIMARY_BG, hover_color=theme.BTN_PRIMARY_HOVER
            ).pack(side="left", padx=8)

        row_video = ctk.CTkFrame(fmt_frame, fg_color="transparent")
        row_video.pack(fill="x", pady=2)
        ctk.CTkLabel(row_video, text="Video:", font=ctk.CTkFont(size=11, weight="bold"), width=50, anchor="w").pack(side="left")
        for val, lbl in [("mp4_1080", "MP4 1080p Full HD"), ("mp4_720", "MP4 720p HD")]:
            ctk.CTkRadioButton(
                row_video, text=lbl, variable=self.selected_format, value=val,
                font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY,
                fg_color=theme.BTN_PRIMARY_BG, hover_color=theme.BTN_PRIMARY_HOVER
            ).pack(side="left", padx=8)

        self.playlist_dl_btn = ctk.CTkButton(
            header_card,
            text=f"⬇ DESCARGAR SELECCIONADOS ({len(self.playlist_entries)})",
            height=42,
            fg_color=theme.BTN_PRIMARY_BG,
            hover_color=theme.BTN_PRIMARY_HOVER,
            text_color=theme.BTN_PRIMARY_TEXT,
            font=ctk.CTkFont(size=13, weight="bold"),
            command=self._download_selected_playlist
        )
        self.playlist_dl_btn.pack(fill="x", padx=14, pady=(0, 14))

        for idx, entry in enumerate(self.playlist_entries):
            var = self.playlist_check_vars[idx]

            row = ctk.CTkFrame(self.explore_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS_SM, border_width=1, border_color=theme.BORDER_COLOR)
            row.pack(fill="x", pady=2, padx=4)

            cb = ctk.CTkCheckBox(
                row, text="", variable=var, width=22, checkbox_width=17, checkbox_height=17,
                fg_color=theme.BTN_PRIMARY_BG, hover_color=theme.BTN_PRIMARY_HOVER,
                command=self._update_playlist_count
            )
            cb.pack(side="left", padx=(10, 4), pady=6)

            ctk.CTkLabel(row, text=f"#{entry.get('index', idx+1):02d}", width=30, font=ctk.CTkFont(size=10, weight="bold"), text_color=theme.TEXT_MUTED).pack(side="left")
            ctk.CTkLabel(row, text=entry.get("title", ""), font=ctk.CTkFont(size=12, weight="bold"), text_color=theme.TEXT_PRIMARY, anchor="w").pack(side="left", fill="x", expand=True, padx=6)
            ctk.CTkLabel(row, text=entry.get("duration", "--:--"), font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY).pack(side="right", padx=12)

    def _select_all_playlist(self):
        for var in self.playlist_check_vars:
            var.set(True)
        self._update_playlist_count()

    def _deselect_all_playlist(self):
        for var in self.playlist_check_vars:
            var.set(False)
        self._update_playlist_count()

    def _update_playlist_count(self):
        count = sum(1 for v in self.playlist_check_vars if v.get())
        total = len(self.playlist_entries)
        self.playlist_count_lbl.configure(text=f"{count} / {total} seleccionados")
        self.playlist_dl_btn.configure(text=f"⬇ DESCARGAR SELECCIONADOS ({count})")

    def _download_selected_playlist(self):
        selected = [self.playlist_entries[i] for i, v in enumerate(self.playlist_check_vars) if v.get()]
        if not selected:
            messagebox.showwarning("Atención", "Debes seleccionar al menos una pista de la lista.")
            return

        fmt = self.selected_format.get()
        target_dir = self._get_target_dir(fmt)
        browser = self._get_active_browser()
        subtitles = bool(self.config.get("subtitles_enabled", False))
        aria2c = bool(self.config.get("aria2c_enabled", True))
        normalize = bool(self.config.get("normalize_audio", False))
        count = self.queue_manager.add_batch(
            selected, format_type=fmt, output_dir=target_dir,
            browser=browser, download_subtitles=subtitles, use_aria2c=aria2c,
            normalize_audio=normalize
        )
        messagebox.showinfo("Playlist Encolada", f"Se han añadido {count} descargas a la cola activa.")
        self._select_tab("downloads")

    # --- MODAL DE VIDEO INDIVIDUAL ---
    def _display_single_modal(self, data: dict):
        self.search_btn.configure(state="normal", text="Buscar / Analizar")
        self.analyze_status.configure(text="✔ Contenido verificado listo para descargar", text_color=theme.STATUS_SUCCESS)
        self.current_media = data

        for widget in self.explore_scroll.winfo_children():
            widget.destroy()

        card = ctk.CTkFrame(self.explore_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS_LG, border_width=1, border_color=theme.BORDER_COLOR)
        card.pack(fill="x", pady=8, padx=4)

        header_row = ctk.CTkFrame(card, fg_color="transparent")
        header_row.pack(fill="x", padx=14, pady=14)

        thumb_label = ctk.CTkLabel(header_row, text="", width=150, height=84, fg_color=theme.BG_INPUT, corner_radius=theme.CORNER_RADIUS)
        thumb_label.pack(side="left", padx=(0, 14))

        if data.get("thumbnail"):
            cached = self.img_cache.get_thumbnail(
                data["thumbnail"],
                size=(150, 84),
                callback=lambda img, l=thumb_label: self.after(0, lambda: l.configure(image=img) if l.winfo_exists() else None)
            )
            if cached:
                thumb_label.configure(image=cached)

        info_col = ctk.CTkFrame(header_row, fg_color="transparent")
        info_col.pack(side="left", fill="both", expand=True)

        ctk.CTkLabel(info_col, text=data.get("title", ""), font=ctk.CTkFont(size=15, weight="bold"), text_color=theme.TEXT_PRIMARY, anchor="w", justify="left").pack(fill="x")
        meta = f"{data.get('uploader')}   •   Duración: {data.get('duration')}   •   Fuente: {data.get('extractor')}"
        ctk.CTkLabel(info_col, text=meta, font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY, anchor="w").pack(fill="x", pady=(4, 0))

        grid_frame = ctk.CTkFrame(card, fg_color=theme.BG_INPUT, corner_radius=theme.CORNER_RADIUS)
        grid_frame.pack(fill="x", padx=14, pady=(0, 14))
        grid_frame.grid_columnconfigure(0, weight=1)
        grid_frame.grid_columnconfigure(1, weight=1)

        # Audio
        audio_col = ctk.CTkFrame(grid_frame, fg_color="transparent")
        audio_col.grid(row=0, column=0, sticky="nsew", padx=12, pady=12)

        ctk.CTkLabel(audio_col, text="🎵 OPCIONES DE AUDIO", font=ctk.CTkFont(size=12, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(anchor="w", pady=(0, 8))

        sizes = data.get("estimated_sizes", {})
        s_320 = f"  ({sizes.get('mp3_320')})" if sizes.get('mp3_320') else ""
        s_192 = f"  ({sizes.get('mp3_192')})" if sizes.get('mp3_192') else ""
        s_m4a = f"  ({sizes.get('m4a_best')})" if sizes.get('m4a_best') else ""
        s_wav = f"  ({sizes.get('wav')})" if sizes.get('wav') else ""

        audio_options = [
            ("mp3_320", f"MP3 320 kbps (HD Máximo + Portada){s_320}"),
            ("mp3_192", f"MP3 192 kbps (Ligero){s_192}"),
            ("m4a_best", f"M4A Original{s_m4a}"),
            ("wav", f"WAV Estudio (Sin pérdida){s_wav}"),
        ]
        for val, label in audio_options:
            ctk.CTkRadioButton(
                audio_col, text=label, variable=self.selected_format, value=val,
                font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY,
                fg_color=theme.BTN_PRIMARY_BG, hover_color=theme.BTN_PRIMARY_HOVER
            ).pack(anchor="w", pady=4)

        # Video
        video_col = ctk.CTkFrame(grid_frame, fg_color="transparent")
        video_col.grid(row=0, column=1, sticky="nsew", padx=12, pady=12)

        ctk.CTkLabel(video_col, text="🎬 OPCIONES DE VIDEO (MP4)", font=ctk.CTkFont(size=12, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(anchor="w", pady=(0, 8))

        s_4k = f"  ({sizes.get('mp4_4k')})" if sizes.get('mp4_4k') else ""
        s_1080 = f"  ({sizes.get('mp4_1080')})" if sizes.get('mp4_1080') else ""
        s_720 = f"  ({sizes.get('mp4_720')})" if sizes.get('mp4_720') else ""
        s_480 = f"  ({sizes.get('mp4_480')})" if sizes.get('mp4_480') else ""

        video_options = [
            ("mp4_4k", f"4K / 2K Ultra HD (Máxima nitidez){s_4k}"),
            ("mp4_1080", f"1080p Full HD{s_1080}"),
            ("mp4_720", f"720p HD (Ligero y Rápido){s_720}"),
            ("mp4_480", f"480p Ahorro de espacio{s_480}"),
        ]
        for val, label in video_options:
            ctk.CTkRadioButton(
                video_col, text=label, variable=self.selected_format, value=val,
                font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY,
                fg_color=theme.BTN_PRIMARY_BG, hover_color=theme.BTN_PRIMARY_HOVER
            ).pack(anchor="w", pady=4)

        # --- SECCIÓN DE RECORTE DE TIEMPO (TRIM / CLIP) ---
        clip_box = ctk.CTkFrame(card, fg_color=theme.BG_INPUT, corner_radius=theme.CORNER_RADIUS)
        clip_box.pack(fill="x", padx=14, pady=(0, 12))

        self.clip_enabled_var = ctk.BooleanVar(value=False)
        clip_cb = ctk.CTkCheckBox(
            clip_box,
            text="✂️ Recortar fragmento específico (Descargar solo una parte)",
            variable=self.clip_enabled_var,
            font=ctk.CTkFont(size=11, weight="bold"),
            fg_color=self.theme_accent["primary"],
            hover_color=self.theme_accent["hover"],
            command=self._on_clip_toggle
        )
        clip_cb.pack(anchor="w", padx=12, pady=(10, 4))

        self.clip_inputs_frame = ctk.CTkFrame(clip_box, fg_color="transparent")

        ctk.CTkLabel(self.clip_inputs_frame, text="Desde (Inicio):", font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY).pack(side="left", padx=(0, 6))
        self.clip_start_entry = ctk.CTkEntry(self.clip_inputs_frame, width=90, height=28, placeholder_text="00:00:00", font=ctk.CTkFont(size=11))
        self.clip_start_entry.pack(side="left", padx=(0, 14))

        ctk.CTkLabel(self.clip_inputs_frame, text="Hasta (Fin):", font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY).pack(side="left", padx=(0, 6))
        self.clip_end_entry = ctk.CTkEntry(self.clip_inputs_frame, width=90, height=28, placeholder_text="00:00:00", font=ctk.CTkFont(size=11))
        self.clip_end_entry.pack(side="left", padx=(0, 10))

        ctk.CTkLabel(
            self.clip_inputs_frame,
            text="(Formato: MM:SS o HH:MM:SS. Deja 'Hasta' vacío para ir hasta el final)",
            font=ctk.CTkFont(size=10),
            text_color=theme.TEXT_MUTED
        ).pack(side="left")

        btn_row = ctk.CTkFrame(card, fg_color="transparent")
        btn_row.pack(fill="x", padx=14, pady=(0, 14))

        dl_btn = ctk.CTkButton(
            btn_row, text="⬇ DESCARGAR AHORA", height=42,
            fg_color=theme.BTN_PRIMARY_BG, hover_color=theme.BTN_PRIMARY_HOVER, text_color=theme.BTN_PRIMARY_TEXT,
            font=ctk.CTkFont(size=13, weight="bold"),
            command=self._enqueue_single_media
        )
        dl_btn.pack(fill="x")

    def _on_clip_toggle(self):
        if self.clip_enabled_var.get():
            self.clip_inputs_frame.pack(fill="x", padx=12, pady=(4, 10))
        else:
            self.clip_inputs_frame.pack_forget()

    def _enqueue_single_media(self):
        if not self.current_media:
            return

        fmt = self.selected_format.get()
        target_dir = self._get_target_dir(fmt)
        url = self.current_media.get("url")
        title = self.current_media.get("title")
        thumb = self.current_media.get("thumbnail")
        browser = self._get_active_browser()

        subtitles = bool(self.config.get("subtitles_enabled", False))
        aria2c = bool(self.config.get("aria2c_enabled", True))
        normalize = bool(self.config.get("normalize_audio", False))

        clip_start = None
        clip_end = None
        if hasattr(self, "clip_enabled_var") and self.clip_enabled_var.get():
            clip_start = self.clip_start_entry.get().strip() or None
            clip_end = self.clip_end_entry.get().strip() or None

        self.queue_manager.add_download(
            url=url, format_type=fmt, output_dir=target_dir,
            title=title, thumbnail_url=thumb, browser=browser,
            download_subtitles=subtitles, use_aria2c=aria2c,
            clip_start=clip_start, clip_end=clip_end,
            normalize_audio=normalize
        )

        messagebox.showinfo("Descarga Encolada", f"'{title[:35]}...' añadido a la cola.")
        self._select_tab("downloads")

    # =========================================================================
    # VISTA 2: DESCARGAS ACTIVAS CON BOTÓN DE REINTENTO
    # =========================================================================
    def _create_downloads_view(self) -> ctk.CTkFrame:
        view = ctk.CTkFrame(self.main_container, fg_color="transparent")
        view.grid_rowconfigure(1, weight=1)
        view.grid_columnconfigure(0, weight=1)

        header = ctk.CTkFrame(view, fg_color="transparent")
        header.grid(row=0, column=0, sticky="ew", pady=(0, 10))

        ctk.CTkLabel(header, text="Descargas Activas", font=ctk.CTkFont(size=19, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(side="left")

        self.downloads_scroll = ctk.CTkScrollableFrame(view, fg_color="transparent", corner_radius=theme.CORNER_RADIUS)
        self.downloads_scroll.grid(row=1, column=0, sticky="nsew")
        self.downloads_scroll.grid_columnconfigure(0, weight=1)

        self.task_ui_cards: dict = {}
        return view

    def _refresh_downloads_list(self):
        tasks = self.queue_manager.get_all_tasks()
        for widget in self.downloads_scroll.winfo_children():
            widget.destroy()
        self.task_ui_cards.clear()

        if not tasks:
            empty_box = ctk.CTkFrame(self.downloads_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS_LG, border_width=1, border_color=theme.BORDER_COLOR)
            empty_box.pack(fill="x", pady=30, padx=14)
            ctk.CTkLabel(empty_box, text="📥 No hay descargas activas", font=ctk.CTkFont(size=17, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(pady=(24, 4))
            ctk.CTkLabel(empty_box, text="Busca un video o pega un enlace en la pestaña Explorar para comenzar.", font=ctk.CTkFont(size=12), text_color=theme.TEXT_SECONDARY).pack(pady=(0, 24))
            return

        for task in reversed(tasks):
            card = ctk.CTkFrame(self.downloads_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
            card.pack(fill="x", pady=4, padx=4)

            top_row = ctk.CTkFrame(card, fg_color="transparent")
            top_row.pack(fill="x", padx=12, pady=(10, 4))

            ctk.CTkLabel(top_row, text=task.title, font=ctk.CTkFont(size=12, weight="bold"), text_color=theme.TEXT_PRIMARY, anchor="w").pack(side="left", fill="x", expand=True)
            ctk.CTkLabel(top_row, text=f"  {task.format_label}  ", font=ctk.CTkFont(size=10, weight="bold"), fg_color=theme.BG_INPUT, text_color=theme.TEXT_ACCENT, corner_radius=theme.CORNER_RADIUS_SM).pack(side="right")

            p_bar = ctk.CTkProgressBar(card, height=8, fg_color=theme.BG_INPUT, progress_color=theme.ACCENT_CYAN)
            p_bar.pack(fill="x", padx=12, pady=(2, 6))
            p_bar.set(task.percent)

            bot_row = ctk.CTkFrame(card, fg_color="transparent")
            bot_row.pack(fill="x", padx=12, pady=(0, 8))

            status_lbl = ctk.CTkLabel(bot_row, text=task.status_text, font=ctk.CTkFont(size=11), text_color=theme.TEXT_MUTED)
            status_lbl.pack(side="left")

            retry_btn = ctk.CTkButton(
                bot_row, text="🔄 Reintentar", width=85, height=24,
                fg_color=theme.BTN_PRIMARY_BG, hover_color=theme.BTN_PRIMARY_HOVER, text_color=theme.BTN_PRIMARY_TEXT,
                font=ctk.CTkFont(size=10, weight="bold"), command=lambda t_id=task.id: self.queue_manager.retry_download(t_id)
            )
            if task.status in ["error", "cancelled"]:
                retry_btn.pack(side="right", padx=(4, 0))

            cancel_btn = ctk.CTkButton(
                bot_row, text="Cancelar", width=70, height=24,
                fg_color=theme.BTN_DANGER_BG, hover_color=theme.BTN_DANGER_HOVER, text_color=theme.BTN_DANGER_TEXT,
                font=ctk.CTkFont(size=10, weight="bold"), command=lambda t_id=task.id: self.queue_manager.cancel_download(t_id)
            )
            if task.status in ["queued", "downloading", "converting"]:
                cancel_btn.pack(side="right")

            self.task_ui_cards[task.id] = {
                "progress_bar": p_bar,
                "status_label": status_lbl,
                "cancel_btn": cancel_btn,
                "retry_btn": retry_btn
            }

    def _on_task_updated(self, task: DownloadTask):
        def _apply():
            active_count = len(self.queue_manager.get_active_tasks())
            btn_text = f"📥  Descargas ({active_count})" if active_count > 0 else "📥  Descargas"
            self.nav_btns["downloads"].configure(text=btn_text)

            if task.id in self.task_ui_cards:
                ui = self.task_ui_cards[task.id]
                ui["progress_bar"].set(task.percent)
                ui["status_label"].configure(text=task.status_text)
                if task.status in ["completed"]:
                    ui["cancel_btn"].pack_forget()
                    ui["retry_btn"].pack_forget()
                elif task.status in ["error", "cancelled"]:
                    ui["cancel_btn"].pack_forget()
                    ui["retry_btn"].pack(side="right", padx=(4, 0))
                else:
                    ui["retry_btn"].pack_forget()
                    ui["cancel_btn"].pack(side="right")

        self.after(0, _apply)

    def _on_task_finished(self, task: DownloadTask):
        self.after(0, lambda: self._on_task_updated(task))

    # =========================================================================
    # VISTA 3: MI BIBLIOTECA CON BÚSQUEDA Y FILTRADO EN VIVO
    # =========================================================================
    def _create_library_view(self) -> ctk.CTkFrame:
        view = ctk.CTkFrame(self.main_container, fg_color="transparent")
        view.grid_rowconfigure(2, weight=1)
        view.grid_columnconfigure(0, weight=1)

        header = ctk.CTkFrame(view, fg_color="transparent")
        header.grid(row=0, column=0, sticky="ew", pady=(0, 8))

        ctk.CTkLabel(header, text="Mi Biblioteca", font=ctk.CTkFont(size=19, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(side="left")

        # Botones para abrir carpetas especializadas
        btn_box = ctk.CTkFrame(header, fg_color="transparent")
        btn_box.pack(side="right")

        ctk.CTkButton(
            btn_box, text="🎵 Carpeta Audios", width=125, height=30,
            fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER, text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"), command=self._open_audios_folder
        ).pack(side="left", padx=3)

        ctk.CTkButton(
            btn_box, text="🎬 Carpeta Videos", width=125, height=30,
            fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER, text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"), command=self._open_videos_folder
        ).pack(side="left", padx=3)

        # Barra de Filtrado en Tiempo Real
        filter_box = ctk.CTkFrame(view, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS_SM, border_width=1, border_color=theme.BORDER_COLOR)
        filter_box.grid(row=1, column=0, sticky="ew", pady=(0, 10))

        self.library_search_entry = ctk.CTkEntry(
            filter_box,
            placeholder_text="🔍  Filtrar biblioteca por nombre, artista o formato...",
            font=ctk.CTkFont(size=12),
            height=34,
            fg_color=theme.BG_INPUT,
            border_width=0,
            text_color=theme.TEXT_PRIMARY
        )
        self.library_search_entry.pack(side="left", fill="x", expand=True, padx=8, pady=4)
        self.library_search_entry.bind("<KeyRelease>", lambda e: self._refresh_library_list())

        self.library_scroll = ctk.CTkScrollableFrame(view, fg_color="transparent", corner_radius=theme.CORNER_RADIUS)
        self.library_scroll.grid(row=2, column=0, sticky="nsew")
        self.library_scroll.grid_columnconfigure(0, weight=1)
        return view

    def _refresh_library_list(self):
        for widget in self.library_scroll.winfo_children():
            widget.destroy()

        filter_txt = self.library_search_entry.get().strip().lower() if hasattr(self, 'library_search_entry') else ""
        items = self.history.get_all()

        if filter_txt:
            items = [
                i for i in items
                if filter_txt in i.get("title", "").lower() or filter_txt in i.get("format_label", "").lower()
            ]

        if not items:
            empty_box = ctk.CTkFrame(self.library_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS_LG, border_width=1, border_color=theme.BORDER_COLOR)
            empty_box.pack(fill="x", pady=30, padx=14)
            msg_title = "🔍 Sin resultados coincidentes" if filter_txt else "🎵 Tu biblioteca está vacía"
            msg_sub = f"No se encontró ningún archivo con '{filter_txt}'" if filter_txt else "Los audios y videos descargados aparecerán aquí para reproducir al instante."
            ctk.CTkLabel(empty_box, text=msg_title, font=ctk.CTkFont(size=17, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(pady=(24, 4))
            ctk.CTkLabel(empty_box, text=msg_sub, font=ctk.CTkFont(size=12), text_color=theme.TEXT_SECONDARY).pack(pady=(0, 24))
            return

        for item in items:
            card = ctk.CTkFrame(self.library_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
            card.pack(fill="x", pady=3, padx=4)

            is_audio = item.get("media_type") == "audio"
            icon_text = "🎵" if is_audio else "🎬"
            ctk.CTkLabel(card, text=icon_text, font=ctk.CTkFont(size=18), width=38).pack(side="left", padx=8, pady=8)

            info_frame = ctk.CTkFrame(card, fg_color="transparent")
            info_frame.pack(side="left", fill="both", expand=True, padx=4, pady=6)

            ctk.CTkLabel(info_frame, text=item.get("title", ""), font=ctk.CTkFont(size=12, weight="bold"), text_color=theme.TEXT_PRIMARY, anchor="w").pack(fill="x")

            size_mb = item.get("file_size", 0) / (1024 * 1024)
            size_str = f"{size_mb:.1f} MB" if size_mb > 0 else "-- MB"
            ctk.CTkLabel(info_frame, text=f"{item.get('format_label')}  •  {size_str}  •  {item.get('date')}", font=ctk.CTkFont(size=10), text_color=theme.TEXT_SECONDARY, anchor="w").pack(fill="x", pady=(2, 0))

            actions_frame = ctk.CTkFrame(card, fg_color="transparent")
            actions_frame.pack(side="right", padx=8)

            ctk.CTkButton(
                actions_frame, text="▶ Reproducir", width=85, height=28,
                fg_color=theme.BTN_PRIMARY_BG, hover_color=theme.BTN_PRIMARY_HOVER, text_color=theme.BTN_PRIMARY_TEXT,
                font=ctk.CTkFont(size=10, weight="bold"), command=lambda it=item: self._on_play_library_item(it)
            ).pack(side="left", padx=2)

            ctk.CTkButton(
                actions_frame, text="📁", width=30, height=28,
                fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER, text_color=theme.BTN_SECONDARY_TEXT,
                font=ctk.CTkFont(size=11), command=lambda p=item.get("file_path"): self.history.reveal_in_folder(p)
            ).pack(side="left", padx=2)

            ctk.CTkButton(
                actions_frame, text="✖", width=30, height=28,
                fg_color=theme.BTN_DANGER_BG, hover_color=theme.BTN_DANGER_HOVER, text_color=theme.BTN_DANGER_TEXT,
                font=ctk.CTkFont(size=11), command=lambda i=item.get("id"): self._delete_library_item(i)
            ).pack(side="left", padx=2)

    def _delete_library_item(self, item_id: str):
        self.history.delete_item(item_id)
        self._refresh_library_list()

    def _open_folder_safe(self, folder_path: str):
        if not folder_path:
            return
        os.makedirs(folder_path, exist_ok=True)
        try:
            if sys.platform == "win32":
                os.startfile(folder_path)
            elif sys.platform == "darwin":
                subprocess.Popen(["open", folder_path])
            else:
                subprocess.Popen(["xdg-open", folder_path])
        except Exception as e:
            print(f"Error abriendo carpeta: {e}")

    def _open_audios_folder(self):
        self._open_folder_safe(self.config.get_audio_dir())

    def _open_videos_folder(self):
        self._open_folder_safe(self.config.get_video_dir())

    # =========================================================================
    # VISTA 4: CONVERSOR MULTIMEDIA LOCAL OFFLINE (FFmpeg + GPU)
    # =========================================================================
    def _create_converter_view(self) -> ctk.CTkFrame:
        view = ctk.CTkFrame(self.main_container, fg_color="transparent")
        view.grid_rowconfigure(1, weight=1)
        view.grid_columnconfigure(0, weight=1)

        header = ctk.CTkFrame(view, fg_color="transparent")
        header.grid(row=0, column=0, sticky="ew", pady=(0, 12))

        ctk.CTkLabel(
            header, text="Conversor Multimedia Local Offline",
            font=ctk.CTkFont(size=19, weight="bold"), text_color=theme.TEXT_PRIMARY
        ).pack(side="left")

        gpu = detect_hardware_acceleration()
        gpu_badge = f"⚡ GPU: {gpu}" if gpu else "⚡ CPU x264"
        ctk.CTkLabel(
            header, text=gpu_badge,
            font=ctk.CTkFont(size=11, weight="bold"),
            fg_color=theme.BG_CARD, text_color=theme.STATUS_SUCCESS if gpu else theme.TEXT_MUTED,
            corner_radius=theme.CORNER_RADIUS_SM, padx=10, pady=4
        ).pack(side="right")

        scroll = ctk.CTkScrollableFrame(view, fg_color="transparent")
        scroll.grid(row=1, column=0, sticky="nsew")
        scroll.grid_columnconfigure(0, weight=1)

        # Tarjeta 1: Archivo Fuente
        box1 = ctk.CTkFrame(scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
        box1.pack(fill="x", pady=6)

        ctk.CTkLabel(box1, text="1. Archivo Fuente (Video o Audio en tu PC)", font=ctk.CTkFont(size=13, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(anchor="w", padx=14, pady=(12, 4))
        ctk.CTkLabel(box1, text="Selecciona cualquier archivo local para convertirlo, extraerle audio o comprimirlo.", font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY).pack(anchor="w", padx=14, pady=(0, 8))

        row_file = ctk.CTkFrame(box1, fg_color="transparent")
        row_file.pack(fill="x", padx=14, pady=(0, 8))

        self.conv_file_entry = ctk.CTkEntry(row_file, height=34, font=ctk.CTkFont(size=11), fg_color=theme.BG_INPUT, placeholder_text="Ningún archivo seleccionado...")
        self.conv_file_entry.configure(state="readonly")
        self.conv_file_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))

        ctk.CTkButton(
            row_file, text="📁 Examinar...", width=110, height=34,
            fg_color=theme.BTN_PRIMARY_BG, hover_color=theme.BTN_PRIMARY_HOVER, text_color=theme.BTN_PRIMARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"), command=self._select_converter_input_file
        ).pack(side="left")

        self.conv_info_lbl = ctk.CTkLabel(box1, text="Información: --", font=ctk.CTkFont(size=11), text_color=theme.TEXT_MUTED)
        self.conv_info_lbl.pack(anchor="w", padx=14, pady=(0, 12))

        # Tarjeta 2: Formato de Salida
        box2 = ctk.CTkFrame(scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
        box2.pack(fill="x", pady=6)

        ctk.CTkLabel(box2, text="2. Formato de Salida Objetivo", font=ctk.CTkFont(size=13, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(anchor="w", padx=14, pady=(12, 6))

        self.conv_format_var = ctk.StringVar(value="mp3_320")

        f_grid = ctk.CTkFrame(box2, fg_color="transparent")
        f_grid.pack(fill="x", padx=14, pady=(0, 12))
        f_grid.grid_columnconfigure(0, weight=1)
        f_grid.grid_columnconfigure(1, weight=1)

        # Audio
        col_a = ctk.CTkFrame(f_grid, fg_color=theme.BG_INPUT, corner_radius=theme.CORNER_RADIUS_SM)
        col_a.grid(row=0, column=0, sticky="nsew", padx=(0, 6), pady=4)
        ctk.CTkLabel(col_a, text="🎵 Formatos de Audio", font=ctk.CTkFont(size=11, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(anchor="w", padx=10, pady=(8, 4))
        for val, lbl in [
            ("mp3_320", "MP3 320 kbps (HD Máximo)"),
            ("mp3_192", "MP3 192 kbps (Estándar)"),
            ("wav", "WAV (Sin pérdida / Estudio)"),
            ("m4a", "M4A (AAC 256k)"),
            ("flac", "FLAC (Lossless)"),
        ]:
            ctk.CTkRadioButton(col_a, text=lbl, variable=self.conv_format_var, value=val, font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY).pack(anchor="w", padx=10, pady=3)

        # Video
        col_v = ctk.CTkFrame(f_grid, fg_color=theme.BG_INPUT, corner_radius=theme.CORNER_RADIUS_SM)
        col_v.grid(row=0, column=1, sticky="nsew", padx=(6, 0), pady=4)
        ctk.CTkLabel(col_v, text="🎬 Formatos de Video", font=ctk.CTkFont(size=11, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(anchor="w", padx=10, pady=(8, 4))
        for val, lbl in [
            ("mp4", f"MP4 H.264 ({gpu or 'CPU'})"),
            ("compress_mp4", "Comprimir Video (Reduce ~50% peso)"),
        ]:
            ctk.CTkRadioButton(col_v, text=lbl, variable=self.conv_format_var, value=val, font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY).pack(anchor="w", padx=10, pady=4)

        self.conv_norm_var = ctk.BooleanVar(value=bool(self.config.get("normalize_audio", False)))
        ctk.CTkCheckBox(
            box2,
            text="🔊 Aplicar normalización inteligente de volumen EBU R128 (-16 LUFS estándar Spotify)",
            variable=self.conv_norm_var,
            font=ctk.CTkFont(size=11, weight="bold"),
            fg_color=self.theme_accent["primary"],
            hover_color=self.theme_accent["hover"]
        ).pack(anchor="w", padx=14, pady=(2, 10))

        # Tarjeta 3: Carpeta de Destino
        box3 = ctk.CTkFrame(scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
        box3.pack(fill="x", pady=6)

        ctk.CTkLabel(box3, text="3. Carpeta de Destino", font=ctk.CTkFont(size=13, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(anchor="w", padx=14, pady=(12, 4))

        row_dst = ctk.CTkFrame(box3, fg_color="transparent")
        row_dst.pack(fill="x", padx=14, pady=(0, 12))

        self.conv_dst_entry = ctk.CTkEntry(row_dst, height=32, font=ctk.CTkFont(size=11), fg_color=theme.BG_INPUT)
        self.conv_dst_entry.insert(0, self.config.get_audio_dir())
        self.conv_dst_entry.configure(state="readonly")
        self.conv_dst_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))

        ctk.CTkButton(
            row_dst, text="📁 Cambiar", width=80, height=32,
            fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER, text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"), command=self._change_converter_dst_dir
        ).pack(side="left")

        # Acciones de Conversión y Progreso
        action_box = ctk.CTkFrame(scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
        action_box.pack(fill="x", pady=6)

        self.conv_progress = ctk.CTkProgressBar(action_box, height=10, corner_radius=theme.CORNER_RADIUS_SM)
        self.conv_progress.set(0.0)
        self.conv_progress.pack(fill="x", padx=14, pady=(14, 6))

        self.conv_status_lbl = ctk.CTkLabel(action_box, text="Listo para convertir", font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY)
        self.conv_status_lbl.pack(anchor="w", padx=14, pady=(0, 10))

        btn_bar = ctk.CTkFrame(action_box, fg_color="transparent")
        btn_bar.pack(fill="x", padx=14, pady=(0, 14))

        self.conv_start_btn = ctk.CTkButton(
            btn_bar, text="⚡ INICIAR CONVERSIÓN", height=38,
            fg_color=self.theme_accent["primary"], hover_color=self.theme_accent["hover"],
            font=ctk.CTkFont(size=12, weight="bold"), command=self._start_conversion
        )
        self.conv_start_btn.pack(side="left", fill="x", expand=True, padx=(0, 6))

        self.conv_cancel_btn = ctk.CTkButton(
            btn_bar, text="✖ Cancelar", width=90, height=38,
            fg_color=theme.BTN_DANGER_BG, hover_color=theme.BTN_DANGER_HOVER, text_color=theme.BTN_DANGER_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"), command=self._cancel_conversion, state="disabled"
        )
        self.conv_cancel_btn.pack(side="left", padx=(0, 6))

        self.conv_open_btn = ctk.CTkButton(
            btn_bar, text="📂 Abrir Archivo", width=120, height=38,
            fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER, text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"), command=self._open_converted_file, state="disabled"
        )
        self.conv_open_btn.pack(side="left")

        # Tarjeta 4: Transcripción con IA y Subtítulos (.SRT / .TXT)
        ai_box = ctk.CTkFrame(scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
        ai_box.pack(fill="x", pady=8)

        ctk.CTkLabel(ai_box, text="🤖 Generador de Subtítulos y Transcripción con IA", font=ctk.CTkFont(size=13, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(anchor="w", padx=14, pady=(12, 4))
        ctk.CTkLabel(ai_box, text="Extrae la voz de cualquier archivo y crea subtítulos sincronizados (.srt) y texto plano (.txt).", font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY).pack(anchor="w", padx=14, pady=(0, 10))

        row_lang = ctk.CTkFrame(ai_box, fg_color="transparent")
        row_lang.pack(fill="x", padx=14, pady=(0, 8))

        ctk.CTkLabel(row_lang, text="Idioma del Audio:", font=ctk.CTkFont(size=11, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(side="left", padx=(0, 8))
        self.trans_lang_var = ctk.StringVar(value="Español (es-ES)")
        ctk.CTkOptionMenu(
            row_lang,
            values=["Español (es-ES)", "English (en-US)", "Português (pt-BR)", "Français (fr-FR)", "Deutsch (de-DE)", "Italiano (it-IT)"],
            variable=self.trans_lang_var,
            width=170,
            height=30
        ).pack(side="left")

        self.trans_progress = ctk.CTkProgressBar(ai_box, height=8, corner_radius=theme.CORNER_RADIUS_SM)
        self.trans_progress.set(0.0)
        self.trans_progress.pack(fill="x", padx=14, pady=(8, 4))

        self.trans_status_lbl = ctk.CTkLabel(ai_box, text="Listo para transcribir", font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY)
        self.trans_status_lbl.pack(anchor="w", padx=14, pady=(0, 8))

        trans_btn_row = ctk.CTkFrame(ai_box, fg_color="transparent")
        trans_btn_row.pack(fill="x", padx=14, pady=(0, 14))

        self.trans_btn = ctk.CTkButton(
            trans_btn_row, text="📝 GENERAR SUBTÍTULOS (.SRT Y .TXT)", height=36,
            fg_color=theme.BTN_PRIMARY_BG, hover_color=theme.BTN_PRIMARY_HOVER, text_color=theme.BTN_PRIMARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"), command=self._start_transcription
        )
        self.trans_btn.pack(side="left", fill="x", expand=True, padx=(0, 6))

        self.trans_open_btn = ctk.CTkButton(
            trans_btn_row, text="📄 Abrir .SRT", width=110, height=36,
            fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER, text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"), command=self._open_transcribed_srt, state="disabled"
        )
        self.trans_open_btn.pack(side="left")

        return view

    def _select_converter_input_file(self):
        filetypes = [
            ("Archivos Multimedia", "*.mp4 *.mkv *.avi *.mov *.flv *.webm *.mp3 *.wav *.m4a *.aac *.flac *.ogg"),
            ("Todos los archivos", "*.*")
        ]
        path = filedialog.askopenfilename(title="Seleccionar archivo multimedia para convertir", filetypes=filetypes)
        if path:
            self.conv_file_entry.configure(state="normal")
            self.conv_file_entry.delete(0, tk.END)
            self.conv_file_entry.insert(0, path)
            self.conv_file_entry.configure(state="readonly")

            info = get_media_info(path)
            dur = info.get("duration_str", "--")
            sz = info.get("size_str", "--")
            types = []
            if info.get("has_video"): types.append("Video")
            if info.get("has_audio"): types.append("Audio")
            t_str = " + ".join(types) if types else "Archivo multimedia"
            self.conv_info_lbl.configure(text=f"Información: {t_str}  •  Duración: {dur}  •  Tamaño: {sz}")

            fmt = self.conv_format_var.get()
            default_dst = self.config.get_audio_dir() if fmt in ["mp3_320", "mp3_192", "wav", "m4a", "flac"] else self.config.get_video_dir()
            self.conv_dst_entry.configure(state="normal")
            self.conv_dst_entry.delete(0, tk.END)
            self.conv_dst_entry.insert(0, default_dst)
            self.conv_dst_entry.configure(state="readonly")

    def _change_converter_dst_dir(self):
        chosen = filedialog.askdirectory(initialdir=self.conv_dst_entry.get())
        if chosen:
            self.conv_dst_entry.configure(state="normal")
            self.conv_dst_entry.delete(0, tk.END)
            self.conv_dst_entry.insert(0, chosen)
            self.conv_dst_entry.configure(state="readonly")

    def _start_conversion(self):
        in_file = self.conv_file_entry.get().strip()
        if not in_file or not os.path.isfile(in_file):
            messagebox.showwarning("Atención", "Por favor selecciona un archivo multimedia válido antes de iniciar.")
            return

        out_fmt = self.conv_format_var.get()
        out_dir = self.conv_dst_entry.get().strip() or os.path.dirname(in_file)

        self._converter_running = True
        self.conv_start_btn.configure(state="disabled", text="Convirtiendo...")
        self.conv_cancel_btn.configure(state="normal")
        self.conv_open_btn.configure(state="disabled")
        self.conv_progress.set(0.0)
        self.conv_status_lbl.configure(text="Iniciando FFmpeg...", text_color=theme.ACCENT_CYAN)

        def run():
            def prog(pct, spd, eta):
                self.after(0, lambda: self._update_converter_progress(pct, spd, eta))

            def stat(txt):
                self.after(0, lambda: self.conv_status_lbl.configure(text=txt))

            try:
                res = self.converter.convert(
                    input_path=in_file,
                    output_format=out_fmt,
                    output_dir=out_dir,
                    progress_callback=prog,
                    status_callback=stat,
                    normalize_audio=self.conv_norm_var.get()
                )
                self.after(0, lambda: self._on_converter_success(res))
            except Exception as e:
                err = str(e)
                self.after(0, lambda: self._on_converter_error(err))

        threading.Thread(target=run, daemon=True).start()

    def _update_converter_progress(self, pct: float, spd: str, eta: str):
        if self._converter_running:
            self.conv_progress.set(pct)
            self.conv_status_lbl.configure(text=f"Convirtiendo: {int(pct * 100)}%  •  Velocidad: {spd}  •  Restante: {eta}")

    def _on_converter_success(self, res: dict):
        self._converter_running = False
        self.conv_start_btn.configure(state="normal", text="⚡ INICIAR CONVERSIÓN")
        self.conv_cancel_btn.configure(state="disabled")
        self.conv_open_btn.configure(state="normal")
        self.conv_progress.set(1.0)
        self._converted_file_path = res.get("file_path", "")
        msg = f"✔ ¡Completado! Guardado en: {res.get('file_name')} ({res.get('size_str')}) usando {res.get('gpu_used')}"
        self.conv_status_lbl.configure(text=msg, text_color=theme.STATUS_SUCCESS)
        send_notification("✦ OmniVault Conversor", f"¡Conversión completada!\n{res.get('file_name')}")

    def _on_converter_error(self, err_msg: str):
        self._converter_running = False
        self.conv_start_btn.configure(state="normal", text="⚡ INICIAR CONVERSIÓN")
        self.conv_cancel_btn.configure(state="disabled")
        first_l = err_msg.splitlines()[0]
        self.conv_status_lbl.configure(text=f"✖ {first_l[:75]}", text_color=theme.STATUS_ERROR)
        if "cancelada" not in err_msg.lower():
            messagebox.showerror("Error de Conversión", f"No se pudo completar la conversión:\n\n{err_msg}")

    def _cancel_conversion(self):
        if self._converter_running:
            self.converter.cancel()
            self.conv_status_lbl.configure(text="Cancelando...", text_color=theme.STATUS_WARNING)

    def _open_converted_file(self):
        if self._converted_file_path and os.path.isfile(self._converted_file_path):
            try:
                os.startfile(self._converted_file_path)
            except Exception:
                self._open_folder_safe(os.path.dirname(self._converted_file_path))

    # --- TRANSCRIPCIÓN INTELIGENTE Y SUBTÍTULOS IA ---
    def _start_transcription(self):
        in_file = self.conv_file_entry.get().strip()
        if not in_file or not os.path.isfile(in_file):
            messagebox.showwarning("Atención", "Por favor selecciona un archivo multimedia válido en el paso 1.")
            return

        out_dir = self.conv_dst_entry.get().strip() or os.path.dirname(in_file)
        lang_choice = self.trans_lang_var.get()
        # Mapear opción legible a código de idioma
        lang_code = "es-ES"
        if "en-US" in lang_choice: lang_code = "en-US"
        elif "pt-BR" in lang_choice: lang_code = "pt-BR"
        elif "fr-FR" in lang_choice: lang_code = "fr-FR"
        elif "de-DE" in lang_choice: lang_code = "de-DE"
        elif "it-IT" in lang_choice: lang_code = "it-IT"

        self._transcription_running = True
        self.trans_btn.configure(state="disabled", text="Transcribiendo audio...")
        self.trans_open_btn.configure(state="disabled")
        self.trans_progress.set(0.0)
        self.trans_status_lbl.configure(text="Iniciando motor de reconocimiento...", text_color=theme.ACCENT_CYAN)

        def run():
            def prog(pct, msg):
                self.after(0, lambda: self._update_transcription_progress(pct, msg))

            def stat(txt):
                self.after(0, lambda: self.trans_status_lbl.configure(text=txt))

            try:
                res = self.transcriber.transcribe(
                    media_path=in_file,
                    output_dir=out_dir,
                    language=lang_code,
                    progress_callback=prog,
                    status_callback=stat
                )
                self.after(0, lambda: self._on_transcription_success(res))
            except Exception as e:
                err = str(e)
                self.after(0, lambda: self._on_transcription_error(err))

        threading.Thread(target=run, daemon=True).start()

    def _update_transcription_progress(self, pct: float, msg: str):
        if self._transcription_running:
            self.trans_progress.set(pct)
            self.trans_status_lbl.configure(text=f"Procesando: {int(pct * 100)}%  •  {msg}")

    def _on_transcription_success(self, res: dict):
        self._transcription_running = False
        self.trans_btn.configure(state="normal", text="📝 GENERAR SUBTÍTULOS (.SRT Y .TXT)")
        self.trans_open_btn.configure(state="normal")
        self.trans_progress.set(1.0)
        self._transcribed_srt_path = res.get("srt_path", "")
        count = res.get("segments_count", 0)
        fname = os.path.basename(self._transcribed_srt_path)
        self.trans_status_lbl.configure(
            text=f"✔ ¡Subtítulos listos! Generados {count} bloques de diálogo en: {fname}",
            text_color=theme.STATUS_SUCCESS
        )
        send_notification("✦ OmniVault Transcriptor", f"¡Subtítulos generados con éxito!\n{fname}")
        messagebox.showinfo("Transcripción Completada", f"Se han creado los subtítulos y la transcripción:\n\n• {res.get('srt_path')}\n• {res.get('txt_path')}")

    def _on_transcription_error(self, err_msg: str):
        self._transcription_running = False
        self.trans_btn.configure(state="normal", text="📝 GENERAR SUBTÍTULOS (.SRT Y .TXT)")
        first_l = err_msg.splitlines()[0]
        self.trans_status_lbl.configure(text=f"✖ {first_l[:75]}", text_color=theme.STATUS_ERROR)
        messagebox.showerror("Error en Transcripción", f"No se pudo completar la transcripción:\n\n{err_msg}")

    def _open_transcribed_srt(self):
        if self._transcribed_srt_path and os.path.isfile(self._transcribed_srt_path):
            try:
                os.startfile(self._transcribed_srt_path)
            except Exception:
                self._open_folder_safe(os.path.dirname(self._transcribed_srt_path))

    # =========================================================================
    # VISTA 5: CANALES Y MONITOREO AUTOMÁTICO (AUTO-SYNC)
    # =========================================================================
    def _create_subscriptions_view(self) -> ctk.CTkFrame:
        view = ctk.CTkFrame(self.main_container, fg_color="transparent")
        view.grid_rowconfigure(2, weight=1)
        view.grid_columnconfigure(0, weight=1)

        header = ctk.CTkFrame(view, fg_color="transparent")
        header.grid(row=0, column=0, sticky="ew", pady=(0, 10))

        ctk.CTkLabel(
            header, text="Monitoreo de Canales y Creadores (Auto-Sync)",
            font=ctk.CTkFont(size=19, weight="bold"), text_color=theme.TEXT_PRIMARY
        ).pack(side="left")

        self.btn_sync_all = ctk.CTkButton(
            header, text="🔄 Comprobar Novedades Ahora",
            height=32, fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER,
            text_color=theme.BTN_SECONDARY_TEXT, font=ctk.CTkFont(size=11, weight="bold"),
            command=self._sync_all_subs_now
        )
        self.btn_sync_all.pack(side="right")

        # Tarjeta para añadir nuevo canal
        add_card = ctk.CTkFrame(view, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
        add_card.grid(row=1, column=0, sticky="ew", pady=(0, 10))

        ctk.CTkLabel(
            add_card, text="Añadir Nuevo Canal / Perfil para Monitoreo Automático",
            font=ctk.CTkFont(size=12, weight="bold"), text_color=theme.TEXT_PRIMARY
        ).pack(anchor="w", padx=14, pady=(10, 4))

        input_row = ctk.CTkFrame(add_card, fg_color="transparent")
        input_row.pack(fill="x", padx=14, pady=(0, 12))

        self.sub_url_entry = ctk.CTkEntry(
            input_row, placeholder_text="Enlace del Canal de YouTube o Perfil de TikTok (ej. https://www.youtube.com/@Canal)",
            height=34, font=ctk.CTkFont(size=11), fg_color=theme.BG_INPUT
        )
        self.sub_url_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))

        self.sub_fmt_var = ctk.StringVar(value="mp3_320")
        self.sub_fmt_menu = ctk.CTkOptionMenu(
            input_row,
            values=["mp3_320", "mp3_192", "mp4_1080", "mp4_720"],
            variable=self.sub_fmt_var,
            width=115,
            height=34
        )
        self.sub_fmt_menu.pack(side="left", padx=(0, 8))

        ctk.CTkButton(
            input_row, text="➕ Suscribirse", width=120, height=34,
            fg_color=self.theme_accent["primary"], hover_color=self.theme_accent["hover"],
            font=ctk.CTkFont(size=11, weight="bold"), command=self._add_new_subscription
        ).pack(side="left")

        # Lista de suscripciones
        self.subs_scroll = ctk.CTkScrollableFrame(view, fg_color="transparent", corner_radius=theme.CORNER_RADIUS)
        self.subs_scroll.grid(row=2, column=0, sticky="nsew")
        self.subs_scroll.grid_columnconfigure(0, weight=1)

        return view

    def _refresh_subscriptions_list(self):
        for w in self.subs_scroll.winfo_children():
            w.destroy()

        subs = self.subscriptions_manager.get_subscriptions()
        if not subs:
            hint_box = ctk.CTkFrame(self.subs_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
            hint_box.pack(fill="x", pady=20, padx=8)
            ctk.CTkLabel(hint_box, text="📡 No tienes canales suscritos para monitoreo todavía.", font=ctk.CTkFont(size=14, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(pady=(20, 6))
            ctk.CTkLabel(
                hint_box,
                text="Pega la URL de tu canal favorito de YouTube o perfil de TikTok arriba y presiona 'Suscribirse'.\n"
                     "OmniVault revisará automáticamente en segundo plano si el creador sube música o videos nuevos y los descargará al instante.",
                font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY, justify="center"
            ).pack(pady=(0, 20), padx=20)
            return

        for s in subs:
            sub_id = s.get("id")
            name = s.get("name") or s.get("url")
            url = s.get("url")
            fmt = s.get("format_type", "mp3_320")
            last_checked = s.get("last_checked_str", "Nunca")
            count = s.get("downloaded_count", 0)
            auto_dl = bool(s.get("auto_download", True))

            card = ctk.CTkFrame(self.subs_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
            card.pack(fill="x", pady=4, padx=4)

            row = ctk.CTkFrame(card, fg_color="transparent")
            row.pack(fill="x", padx=12, pady=10)

            icon_lbl = ctk.CTkLabel(row, text="📡", font=ctk.CTkFont(size=18), width=32)
            icon_lbl.pack(side="left", padx=(0, 10))

            info_box = ctk.CTkFrame(row, fg_color="transparent")
            info_box.pack(side="left", fill="both", expand=True)

            ctk.CTkLabel(info_box, text=name, font=ctk.CTkFont(size=13, weight="bold"), text_color=theme.TEXT_PRIMARY, anchor="w").pack(fill="x")
            sub_meta = f"Formato: {fmt.upper()}  •  Último escaneo: {last_checked}  •  Descargados: {count}"
            ctk.CTkLabel(info_box, text=sub_meta, font=ctk.CTkFont(size=10), text_color=theme.TEXT_SECONDARY, anchor="w").pack(fill="x", pady=(2, 0))

            ctrl_box = ctk.CTkFrame(row, fg_color="transparent")
            ctrl_box.pack(side="right")

            auto_var = ctk.BooleanVar(value=auto_dl)
            ctk.CTkSwitch(
                ctrl_box, text="Auto-Bajar", variable=auto_var,
                font=ctk.CTkFont(size=11), command=lambda sid=sub_id, v=auto_var: self._toggle_sub_auto(sid, v.get())
            ).pack(side="left", padx=(0, 10))

            ctk.CTkButton(
                ctrl_box, text="🔍 Escanear", width=80, height=28,
                fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER, text_color=theme.BTN_SECONDARY_TEXT,
                font=ctk.CTkFont(size=11, weight="bold"), command=lambda s_item=s: self._sync_single_sub(s_item)
            ).pack(side="left", padx=(0, 6))

            ctk.CTkButton(
                ctrl_box, text="🗑", width=34, height=28,
                fg_color=theme.BTN_DANGER_BG, hover_color=theme.BTN_DANGER_HOVER, text_color=theme.BTN_DANGER_TEXT,
                font=ctk.CTkFont(size=12, weight="bold"), command=lambda sid=sub_id: self._remove_sub(sid)
            ).pack(side="left")

    def _add_new_subscription(self):
        url = self.sub_url_entry.get().strip()
        if not url or not is_url(url):
            messagebox.showwarning("Atención", "Por favor ingresa un enlace de canal o perfil válido.")
            return

        fmt = self.sub_fmt_var.get()
        try:
            self.subscriptions_manager.add_subscription(url=url, format_type=fmt)
            self.sub_url_entry.delete(0, tk.END)
            self._refresh_subscriptions_list()
            messagebox.showinfo("Canal Suscrito", "¡Canal añadido con éxito al monitoreo automático!")
        except Exception as e:
            messagebox.showerror("Error", str(e))

    def _sync_all_subs_now(self):
        self.btn_sync_all.configure(state="disabled", text="Comprobando...")

        def run():
            try:
                new_c = self.subscriptions_manager.check_all(
                    queue_manager=self.queue_manager,
                    get_target_dir_func=self._get_target_dir
                )
                self.after(0, lambda: self._on_sync_finished(new_c))
            except Exception as e:
                self.after(0, lambda: messagebox.showerror("Error en Sincronización", str(e)))
                self.after(0, lambda: self.btn_sync_all.configure(state="normal", text="🔄 Comprobar Novedades Ahora"))

        threading.Thread(target=run, daemon=True).start()

    def _sync_single_sub(self, sub: dict):
        def run():
            try:
                items = self.subscriptions_manager.check_channel(
                    sub,
                    queue_manager=self.queue_manager,
                    get_target_dir_func=self._get_target_dir
                )
                self.after(0, lambda: self._refresh_subscriptions_list())
                if items:
                    self.after(0, lambda: messagebox.showinfo("Novedades", f"Se encontraron {len(items)} nuevos contenidos y se agregaron a la cola."))
                else:
                    self.after(0, lambda: messagebox.showinfo("Sin Novedades", "El canal está al día, no hay publicaciones nuevas no descargadas."))
            except Exception as e:
                self.after(0, lambda: messagebox.showerror("Error", str(e)))

        threading.Thread(target=run, daemon=True).start()

    def _on_sync_finished(self, new_count: int):
        self.btn_sync_all.configure(state="normal", text="🔄 Comprobar Novedades Ahora")
        self._refresh_subscriptions_list()
        if new_count > 0:
            messagebox.showinfo("Sincronización Completa", f"¡Se encontraron y encolaron {new_count} nuevos archivos!")
        else:
            messagebox.showinfo("Sincronización Completa", "Todos los canales están al día. No hay nuevos lanzamientos.")

    def _toggle_sub_auto(self, sub_id: str, val: bool):
        self.subscriptions_manager.update_subscription(sub_id, auto_download=val)

    def _remove_sub(self, sub_id: str):
        confirm = messagebox.askyesno("Eliminar Monitoreo", "¿Estás seguro de que deseas dejar de monitorear este canal?")
        if confirm:
            self.subscriptions_manager.remove_subscription(sub_id)
            self._refresh_subscriptions_list()

    # =========================================================================
    # VISTA 5: AJUSTES (DIRECTORIOS, RENDIMIENTO Y SESIÓN)
    # =========================================================================
    def _create_settings_view(self) -> ctk.CTkFrame:
        view = ctk.CTkFrame(self.main_container, fg_color="transparent")
        view.grid_rowconfigure(1, weight=1)
        view.grid_columnconfigure(0, weight=1)

        header = ctk.CTkFrame(view, fg_color="transparent")
        header.grid(row=0, column=0, sticky="ew", pady=(0, 12))

        ctk.CTkLabel(header, text="Ajustes de la Aplicación", font=ctk.CTkFont(size=19, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(side="left")

        settings_scroll = ctk.CTkScrollableFrame(view, fg_color="transparent")
        settings_scroll.grid(row=1, column=0, sticky="nsew")
        settings_scroll.grid_columnconfigure(0, weight=1)

        # Bloque 1: Carpetas de Descargas Separadas
        box1 = ctk.CTkFrame(settings_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
        box1.pack(fill="x", pady=6)

        ctk.CTkLabel(box1, text="Ubicaciones de Descarga Predeterminadas", font=ctk.CTkFont(size=13, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(anchor="w", padx=14, pady=(12, 4))
        ctk.CTkLabel(box1, text="Las canciones y audios se guardan en Música, y los videos en la carpeta de Videos.", font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY).pack(anchor="w", padx=14, pady=(0, 10))

        # Fila Audios
        ctk.CTkLabel(box1, text="🎵 Carpeta de Audios / Canciones:", font=ctk.CTkFont(size=11, weight="bold"), text_color=theme.TEXT_SECONDARY).pack(anchor="w", padx=14, pady=(2, 2))
        row_audio = ctk.CTkFrame(box1, fg_color="transparent")
        row_audio.pack(fill="x", padx=14, pady=(0, 8))

        self.audio_dir_entry = ctk.CTkEntry(row_audio, height=32, font=ctk.CTkFont(size=11), fg_color=theme.BG_INPUT)
        self.audio_dir_entry.insert(0, self.config.get_audio_dir())
        self.audio_dir_entry.configure(state="readonly")
        self.audio_dir_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))

        ctk.CTkButton(
            row_audio, text="📁 Cambiar", width=80, height=32,
            fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER, text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"), command=self._change_audio_dir
        ).pack(side="left")

        # Fila Videos
        ctk.CTkLabel(box1, text="🎬 Carpeta de Videos:", font=ctk.CTkFont(size=11, weight="bold"), text_color=theme.TEXT_SECONDARY).pack(anchor="w", padx=14, pady=(2, 2))
        row_video = ctk.CTkFrame(box1, fg_color="transparent")
        row_video.pack(fill="x", padx=14, pady=(0, 12))

        self.video_dir_entry = ctk.CTkEntry(row_video, height=32, font=ctk.CTkFont(size=11), fg_color=theme.BG_INPUT)
        self.video_dir_entry.insert(0, self.config.get_video_dir())
        self.video_dir_entry.configure(state="readonly")
        self.video_dir_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))

        ctk.CTkButton(
            row_video, text="📁 Cambiar", width=80, height=32,
            fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER, text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"), command=self._change_video_dir
        ).pack(side="left")

        # Bloque 2: Rendimiento y Portapapeles
        box2 = ctk.CTkFrame(settings_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
        box2.pack(fill="x", pady=6)

        ctk.CTkLabel(box2, text="⚡ Rendimiento & Experiencia", font=ctk.CTkFont(size=13, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(anchor="w", padx=14, pady=(12, 4))

        # Descargas simultáneas
        row_workers = ctk.CTkFrame(box2, fg_color="transparent")
        row_workers.pack(fill="x", padx=14, pady=(6, 8))

        ctk.CTkLabel(row_workers, text="Descargas simultáneas en paralelo:", font=ctk.CTkFont(size=11, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(side="left")

        current_w = str(self.config.get("max_workers", 2))
        self.workers_seg = ctk.CTkSegmentedButton(
            row_workers, values=["1", "2", "3", "4"],
            height=28, command=self._on_workers_changed
        )
        self.workers_seg.set(current_w)
        self.workers_seg.pack(side="right")

        # Switch Portapapeles
        self.clip_switch_var = ctk.BooleanVar(value=bool(self.config.get("clipboard_watch", True)))
        clip_sw = ctk.CTkSwitch(
            box2, text="Detectar automáticamente enlaces del portapapeles (Smart Auto-Paste)",
            variable=self.clip_switch_var, font=ctk.CTkFont(size=11),
            command=self._on_clipboard_watch_toggle
        )
        clip_sw.pack(anchor="w", padx=14, pady=(2, 6))

        # Switch Aria2c
        aria_ok = is_aria2c_available()
        aria_status = "Activo (8 hilos)" if aria_ok else "No detectado (Opcional)"
        self.aria2c_switch_var = ctk.BooleanVar(value=bool(self.config.get("aria2c_enabled", True)))
        aria_sw = ctk.CTkSwitch(
            box2, text=f"Acelerador de red multihilo Aria2c — {aria_status}",
            variable=self.aria2c_switch_var, font=ctk.CTkFont(size=11),
            command=self._on_aria2c_toggle
        )
        aria_sw.pack(anchor="w", padx=14, pady=(2, 6))

        # Switch Notificaciones
        self.notify_switch_var = ctk.BooleanVar(value=bool(self.config.get("notifications_enabled", True)))
        notify_sw = ctk.CTkSwitch(
            box2, text="Notificaciones nativas y sonido sutil al terminar descargas",
            variable=self.notify_switch_var, font=ctk.CTkFont(size=11),
            command=self._on_notify_toggle
        )
        notify_sw.pack(anchor="w", padx=14, pady=(2, 6))

        # Switch Subtítulos
        self.subtitles_switch_var = ctk.BooleanVar(value=bool(self.config.get("subtitles_enabled", False)))
        subs_sw = ctk.CTkSwitch(
            box2, text="Descargar subtítulos automáticamente (.srt e incrustados en video)",
            variable=self.subtitles_switch_var, font=ctk.CTkFont(size=11),
            command=self._on_subtitles_toggle
        )
        subs_sw.pack(anchor="w", padx=14, pady=(2, 6))

        # Switch Normalización de Volumen EBU R128
        self.norm_switch_var = ctk.BooleanVar(value=bool(self.config.get("normalize_audio", False)))
        norm_sw = ctk.CTkSwitch(
            box2, text="Normalización inteligente de volumen EBU R128 (-16 LUFS estándar Spotify)",
            variable=self.norm_switch_var, font=ctk.CTkFont(size=11),
            command=self._on_normalize_toggle
        )
        norm_sw.pack(anchor="w", padx=14, pady=(2, 6))

        # Switch Monitoreo Automático de Canales
        self.sync_switch_var = ctk.BooleanVar(value=bool(self.config.get("sync_channels_auto", True)))
        sync_sw = ctk.CTkSwitch(
            box2, text="Monitoreo y auto-descarga de Canales / Creadores suscritos (Auto-Sync)",
            variable=self.sync_switch_var, font=ctk.CTkFont(size=11),
            command=self._on_sync_toggle
        )
        sync_sw.pack(anchor="w", padx=14, pady=(2, 6))

        # Switch System Tray
        self.tray_switch_var = ctk.BooleanVar(value=bool(self.config.get("tray_enabled", True)))
        tray_sw = ctk.CTkSwitch(
            box2, text="Minimizar a la bandeja del sistema (System Tray) al cerrar la ventana",
            variable=self.tray_switch_var, font=ctk.CTkFont(size=11),
            command=self._on_tray_toggle
        )
        tray_sw.pack(anchor="w", padx=14, pady=(2, 8))

        # Acción al terminar descargas
        row_action = ctk.CTkFrame(box2, fg_color="transparent")
        row_action.pack(fill="x", padx=14, pady=(4, 12))

        ctk.CTkLabel(row_action, text="Acción al completar todas las descargas:", font=ctk.CTkFont(size=11, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(side="left")

        self.finish_action_var = ctk.StringVar(value=self._get_finish_action_label(self.config.get("on_finish_action", "none")))
        self.finish_menu = ctk.CTkOptionMenu(
            row_action,
            values=["Ninguna", "Sonido de campana", "Suspender equipo", "Apagar equipo (aviso 30s)"],
            variable=self.finish_action_var,
            height=28,
            command=self._on_finish_action_changed
        )
        self.finish_menu.pack(side="right")

        # Bloque: Personalización de Tema y Acento
        box_theme = ctk.CTkFrame(settings_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
        box_theme.pack(fill="x", pady=6)

        ctk.CTkLabel(box_theme, text="🎨 Personalización & Color de Acento", font=ctk.CTkFont(size=13, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(anchor="w", padx=14, pady=(12, 4))
        ctk.CTkLabel(box_theme, text="Elige el estilo visual y color de acento principal de la aplicación:", font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY).pack(anchor="w", padx=14, pady=(0, 8))

        theme_row = ctk.CTkFrame(box_theme, fg_color="transparent")
        theme_row.pack(fill="x", padx=14, pady=(0, 14))

        self.theme_seg = ctk.CTkSegmentedButton(
            theme_row,
            values=["🟣 Violet", "🔵 Blue", "🟢 Emerald", "🟠 Orange", "🌸 Rose"],
            command=self._on_theme_changed,
            height=32
        )
        self.theme_seg.set(self._get_current_theme_label())
        self.theme_seg.pack(fill="x")

        # Bloque 3: Sesión de Navegador & Cookies
        box3 = ctk.CTkFrame(settings_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
        box3.pack(fill="x", pady=6)

        ctk.CTkLabel(box3, text="🔒 Conexión Segura con YouTube (Playlists Privadas y Anti-Bot)", font=ctk.CTkFont(size=13, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(anchor="w", padx=14, pady=(12, 4))

        desc = (
            "El 95% de las descargas operan sin cookies en modo anónimo.\n"
            "Solo se requiere sesión para videos con restricción de edad (+18), playlists privadas o comprobación anti-bot."
        )
        ctk.CTkLabel(box3, text=desc, font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY, justify="left").pack(anchor="w", padx=14, pady=(0, 6))

        saved_cookie_file = os.path.join(get_app_dir(), "saved_cookies.txt")
        self.cookie_status_lbl = ctk.CTkLabel(
            box3,
            text="✔ Sesión guardada en archivo local (Sin bloqueos de navegador)" if os.path.exists(saved_cookie_file) else "ℹ Modo anónimo activo (Sin credenciales guardadas)",
            font=ctk.CTkFont(size=11, weight="bold"),
            text_color=theme.STATUS_SUCCESS if os.path.exists(saved_cookie_file) else theme.TEXT_MUTED
        )
        self.cookie_status_lbl.pack(anchor="w", padx=14, pady=(0, 10))

        # --- OPCIÓN 1: MÉTODO UNIVERSAL RECOMENDADO ---
        method1_box = ctk.CTkFrame(box3, fg_color=theme.BG_MAIN, corner_radius=theme.CORNER_RADIUS_SM)
        method1_box.pack(fill="x", padx=14, pady=(0, 8))

        ctk.CTkLabel(
            method1_box,
            text="⭐ Método Recomendado (Universal para Chrome, Edge, Firefox, Vivaldi, Brave, Opera):",
            font=ctk.CTkFont(size=11, weight="bold"),
            text_color=theme.TEXT_PRIMARY
        ).pack(anchor="w", padx=10, pady=(8, 2))

        ctk.CTkLabel(
            method1_box,
            text="Exporta tus cookies de YouTube con la extensión gratuita 'Get cookies.txt LOCALLY' y cárgalas aquí.\n"
                 "Cero bloqueos de Windows y 100% inmune al nuevo cifrado App-Bound de Google Chrome y Microsoft Edge.",
            font=ctk.CTkFont(size=10),
            text_color=theme.TEXT_SECONDARY,
            justify="left"
        ).pack(anchor="w", padx=10, pady=(0, 6))

        m1_btn_row = ctk.CTkFrame(method1_box, fg_color="transparent")
        m1_btn_row.pack(fill="x", padx=10, pady=(0, 8))

        load_cookie_btn = ctk.CTkButton(
            m1_btn_row,
            text="📄 Cargar archivo cookies.txt",
            height=32,
            fg_color=theme.BTN_PRIMARY_BG,
            hover_color=theme.BTN_PRIMARY_HOVER,
            text_color=theme.BTN_PRIMARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"),
            command=self._load_cookie_file
        )
        load_cookie_btn.pack(side="left", padx=(0, 6))

        # --- OPCIÓN 2: EXTRACCIÓN DIRECTA PREVENTIVA (EXPERIMENTAL) ---
        method2_box = ctk.CTkFrame(box3, fg_color=theme.BG_MAIN, corner_radius=theme.CORNER_RADIUS_SM)
        method2_box.pack(fill="x", padx=14, pady=(0, 10))

        ctk.CTkLabel(
            method2_box,
            text="⚡ Método Directo (Experimental - Requiere cerrar el navegador):",
            font=ctk.CTkFont(size=11, weight="bold"),
            text_color=theme.TEXT_PRIMARY
        ).pack(anchor="w", padx=10, pady=(8, 2))

        ctk.CTkLabel(
            method2_box,
            text="Extrae las cookies directamente del navegador (Vivaldi, Firefox, Brave, Opera).\n"
                 "Importante: Debes cerrar tu navegador 3 segundos antes de hacer clic para evitar el bloqueo de archivos de Windows.",
            font=ctk.CTkFont(size=10),
            text_color=theme.TEXT_SECONDARY,
            justify="left"
        ).pack(anchor="w", padx=10, pady=(0, 6))

        m2_btn_row = ctk.CTkFrame(method2_box, fg_color="transparent")
        m2_btn_row.pack(fill="x", padx=10, pady=(0, 8))

        ctk.CTkLabel(m2_btn_row, text="Navegador:", font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY).pack(side="left", padx=(0, 6))

        self.browser_var = ctk.StringVar(value="Vivaldi")
        self.browser_menu = ctk.CTkOptionMenu(
            m2_btn_row,
            values=["Vivaldi", "Firefox", "Brave", "Opera", "Chrome", "Edge"],
            variable=self.browser_var,
            width=100,
            height=30,
            command=self._on_browser_selection_changed
        )
        self.browser_menu.pack(side="left", padx=(0, 8))

        self.sync_browser_btn = ctk.CTkButton(
            m2_btn_row,
            text="📸 Sincronizar Vivaldi",
            height=30,
            fg_color=theme.BTN_SECONDARY_BG,
            hover_color=theme.BTN_SECONDARY_HOVER,
            text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"),
            command=self._sync_selected_browser
        )
        self.sync_browser_btn.pack(side="left", padx=(0, 8))

        # --- BARRA DE ACCIONES DE SESIÓN ---
        actions_row = ctk.CTkFrame(box3, fg_color="transparent")
        actions_row.pack(fill="x", padx=14, pady=(0, 12))

        test_cookie_btn = ctk.CTkButton(
            actions_row,
            text="🧪 Probar Estado de Sesión",
            height=30,
            fg_color=theme.BTN_SECONDARY_BG,
            hover_color=theme.BTN_SECONDARY_HOVER,
            text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"),
            command=self._test_saved_session
        )
        test_cookie_btn.pack(side="left", padx=(0, 6))

        del_cookie_btn = ctk.CTkButton(
            actions_row,
            text="🗑 Limpiar Sesión Guardada",
            height=30,
            fg_color=theme.BTN_DANGER_BG,
            hover_color=theme.BTN_DANGER_HOVER,
            text_color=theme.BTN_DANGER_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"),
            command=self._clear_saved_cookies
        )
        del_cookie_btn.pack(side="left")

        # Bloque 4: Diagnóstico del Sistema
        box4 = ctk.CTkFrame(settings_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
        box4.pack(fill="x", pady=6)

        ctk.CTkLabel(box4, text="Diagnóstico del Sistema & Privacidad", font=ctk.CTkFont(size=13, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(anchor="w", padx=14, pady=(12, 4))

        ff_found = is_ffmpeg_available()
        ff_detail = f"Habilitado ({FFMPEG_DIR or 'PATH'})" if ff_found else "No detectado (Instala FFmpeg o coloca ffmpeg.exe en la carpeta)"

        gpu_accel = detect_hardware_acceleration() or "Procesamiento por CPU estándar"
        aria_ok = is_aria2c_available()
        aria_detail = "Habilitado (8 conexiones simultáneas por archivo)" if aria_ok else "No detectado (Opcional)"

        diag_text = (
            f"• Aplicación: OmniVault Desktop (Obsidian & Titanium Edition)\n"
            f"• Python: {sys.version.split()[0]} ({sys.platform})\n"
            f"• Reproductor de Audio: MCI Nativo winmm.dll (Bajo consumo de memoria)\n"
            f"• Aceleración GPU FFmpeg: {gpu_accel}\n"
            f"• Acelerador Aria2c: {aria_detail}\n"
            f"• Motor TikTok: Directo HD sin marca de agua activo con soporte ID3\n"
            f"• Impersonación TLS: Habilitada con curl_cffi\n"
            f"• Motor FFmpeg: {ff_detail}\n"
            f"• Conexión: Cifrada y directa con validación SSL activa"
        )
        ctk.CTkLabel(box4, text=diag_text, font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY, justify="left").pack(anchor="w", padx=14, pady=(0, 12))

        return view

    def _on_workers_changed(self, value: str):
        val_int = int(value)
        self.config.set("max_workers", val_int)
        self.queue_manager.set_max_workers(val_int)

    def _on_clipboard_watch_toggle(self):
        val = self.clip_switch_var.get()
        self.config.set("clipboard_watch", val)
        if not val:
            self._dismiss_clipboard_banner()

    def _on_aria2c_toggle(self):
        val = self.aria2c_switch_var.get()
        self.config.set("aria2c_enabled", val)

    def _on_notify_toggle(self):
        val = self.notify_switch_var.get()
        self.config.set("notifications_enabled", val)

    def _on_subtitles_toggle(self):
        val = self.subtitles_switch_var.get()
        self.config.set("subtitles_enabled", val)

    def _change_audio_dir(self):
        chosen = filedialog.askdirectory(initialdir=self.config.get_audio_dir())
        if chosen:
            self.config.set("audio_download_dir", chosen)
            self.audio_dir_entry.configure(state="normal")
            self.audio_dir_entry.delete(0, tk.END)
            self.audio_dir_entry.insert(0, chosen)
            self.audio_dir_entry.configure(state="readonly")

    def _change_video_dir(self):
        chosen = filedialog.askdirectory(initialdir=self.config.get_video_dir())
        if chosen:
            self.config.set("video_download_dir", chosen)
            self.video_dir_entry.configure(state="normal")
            self.video_dir_entry.delete(0, tk.END)
            self.video_dir_entry.insert(0, chosen)
            self.video_dir_entry.configure(state="readonly")

    def _on_browser_selection_changed(self, choice: str):
        self.sync_browser_btn.configure(text=f"📸 Sincronizar {choice}")

    def _sync_selected_browser(self):
        browser = self.browser_var.get().lower()

        # 1. Comprobación de cifrado App-Bound en Windows (Chrome / Edge 127+)
        if browser in ["chrome", "edge"]:
            messagebox.showwarning(
                "Cifrado Protegido (App-Bound Encryption)",
                f"Google Chrome y Microsoft Edge cuentan con cifrado exclusivo de Windows (App-Bound Encryption).\n\n"
                f"Por seguridad de Windows, no permiten la extracción directa de contraseñas ni cookies desde programas externos.\n\n"
                f"✦ Solución recomendada y universal:\n"
                f"1. Instala la extensión gratuita 'Get cookies.txt LOCALLY' en tu navegador.\n"
                f"2. Exporta el archivo cookies.txt de YouTube.\n"
                f"3. Haz clic en '📄 Cargar archivo cookies.txt' en esta ventana."
            )
            return

        # 2. Comprobación preventiva de proceso en ejecución (evita error de bloqueo de archivo)
        if is_browser_running(browser):
            messagebox.showwarning(
                f"{browser.capitalize()} en ejecución",
                f"El navegador {browser.capitalize()} está actualmente abierto.\n\n"
                f"Windows no permite leer los archivos de sesión mientras el navegador está en ejecución debido al bloqueo de archivos compartidos.\n\n"
                f"✦ Pasos para sincronizar:\n"
                f"1. Cierra todas las ventanas de {browser.capitalize()}.\n"
                f"2. Espera 3 segundos.\n"
                f"3. Vuelve a hacer clic en '📸 Sincronizar {browser.capitalize()}'.\n"
                f"4. Una vez guardado, podrás abrir {browser.capitalize()} y usarlo normalmente."
            )
            return

        self.sync_browser_btn.configure(state="disabled", text="Extrayendo...")

        def _worker():
            try:
                self.downloader.export_browser_cookies_snapshot(browser)
                self.after(0, lambda: self._on_cookie_sync_success(browser))
            except Exception as e:
                err = str(e)
                self.after(0, lambda: self._on_cookie_sync_error(err, browser))

        threading.Thread(target=_worker, daemon=True).start()

    def _on_cookie_sync_success(self, browser: str):
        self.sync_browser_btn.configure(state="normal", text=f"📸 Sincronizar {browser.capitalize()}")
        self.cookie_status_lbl.configure(
            text=f"✔ Sesión de {browser.capitalize()} guardada con éxito (Sin bloqueos)",
            text_color=theme.STATUS_SUCCESS
        )
        self.session_lbl.configure(text=f"● Sesión: {browser.capitalize()}", text_color=theme.STATUS_SUCCESS)
        messagebox.showinfo(
            "Sesión Sincronizada",
            f"¡Listo! Se ha guardado una copia segura de tu sesión de {browser.capitalize()}.\n\n"
            f"Ya puedes volver a abrir {browser.capitalize()} normalmente; OmniVault utilizará esta sesión sin bloqueos."
        )

    def _on_cookie_sync_error(self, err_msg: str, browser: str):
        self.sync_browser_btn.configure(state="normal", text=f"📸 Sincronizar {browser.capitalize()}")
        messagebox.showwarning("Aviso de Sincronización", err_msg)

    def _test_saved_session(self):
        def _worker():
            result = self.downloader.test_youtube_auth()
            self.after(0, lambda: self._display_test_result(result))

        threading.Thread(target=_worker, daemon=True).start()

    def _display_test_result(self, res: dict):
        if res.get("ok"):
            self.cookie_status_lbl.configure(text="✔ Sesión de YouTube activa y verificada", text_color=theme.STATUS_SUCCESS)
            messagebox.showinfo("Estado de Sesión", f"¡Conexión Exitosa!\n\n{res.get('message')}")
        else:
            self.cookie_status_lbl.configure(text="⚠ Sesión no verificada o expirada", text_color=theme.STATUS_WARNING)
            messagebox.showwarning("Estado de Sesión", f"Resultado del Diagnóstico:\n\n{res.get('message')}")

    def _load_cookie_file(self):
        path = filedialog.askopenfilename(
            title="Seleccionar archivo cookies.txt",
            filetypes=[("Archivos de texto", "*.txt"), ("Todos los archivos", "*.*")]
        )
        if path:
            dest = os.path.join(get_app_dir(), "saved_cookies.txt")
            import shutil
            shutil.copy(path, dest)
            try:
                os.chmod(dest, 0o600)
            except Exception:
                pass
            self.cookie_status_lbl.configure(
                text="✔ Archivo cookies.txt cargado con éxito",
                text_color=theme.STATUS_SUCCESS
            )
            self.session_lbl.configure(text="● Sesión: cookies.txt", text_color=theme.STATUS_SUCCESS)
            messagebox.showinfo("Éxito", "El archivo cookies.txt ha sido importado correctamente.")

    def _clear_saved_cookies(self):
        dest = os.path.join(get_app_dir(), "saved_cookies.txt")
        if os.path.exists(dest):
            try:
                os.remove(dest)
            except OSError as e:
                messagebox.showerror("Error", f"No se pudo eliminar el archivo de sesión: {e}")
                return

        self.config.set("browser_cookies", "none")
        self.cookie_status_lbl.configure(
            text="ℹ Sesión eliminada. Usando modo anónimo por defecto.",
            text_color=theme.TEXT_MUTED
        )
        self.session_lbl.configure(text="● Sesión: Anónima", text_color=theme.TEXT_MUTED)
        messagebox.showinfo("Sesión Limpiada", "Se ha eliminado el archivo de cookies y se ha restablecido el modo anónimo.")

    def _get_current_theme_label(self) -> str:
        cur = self.config.get("accent_theme", "violet")
        mapping = {
            "violet": "🟣 Violet",
            "blue": "🔵 Blue",
            "emerald": "🟢 Emerald",
            "orange": "🟠 Orange",
            "rose": "🌸 Rose",
        }
        return mapping.get(cur, "🟣 Violet")

    def _on_theme_changed(self, choice: str):
        theme_id = "violet"
        for k in themes.get_available_themes():
            t = themes.get_theme(k)
            if t["name"].lower() in choice.lower() or t["id"].lower() in choice.lower() or t["emoji"] in choice:
                theme_id = k
                break
        self.config.set("accent_theme", theme_id)
        self.theme_accent = themes.get_theme(theme_id)
        if hasattr(self, "tray") and self.tray.is_available():
            self.tray.update_icon(self.theme_accent["primary"])
        messagebox.showinfo(
            "Tema de Acento Seleccionado",
            f"Se ha activado el estilo: {self.theme_accent['name']}.\nLos acentos y botones se han actualizado con éxito."
        )

    def _on_tray_toggle(self):
        val = self.tray_switch_var.get()
        self.config.set("tray_enabled", val)
        if val and not self.tray._is_running:
            self.tray.start()
        elif not val and self.tray._is_running:
            self.tray.stop()

    def _on_normalize_toggle(self):
        val = self.norm_switch_var.get()
        self.config.set("normalize_audio", val)

    def _on_sync_toggle(self):
        val = self.sync_switch_var.get()
        self.config.set("sync_channels_auto", val)
        if val:
            self.subscriptions_manager.start_background_sync(
                interval_seconds=int(self.config.get("sync_channels_interval_min", 30)) * 60,
                queue_manager=self.queue_manager,
                get_target_dir_func=self._get_target_dir
            )
        else:
            self.subscriptions_manager.stop_background_sync()

    def _get_finish_action_label(self, val: str) -> str:
        mapping = {
            "none": "Ninguna",
            "sound": "Sonido de campana",
            "sleep": "Suspender equipo",
            "shutdown": "Apagar equipo (aviso 30s)",
        }
        return mapping.get(val, "Ninguna")

    def _on_finish_action_changed(self, choice: str):
        mapping = {
            "Ninguna": "none",
            "Sonido de campana": "sound",
            "Suspender equipo": "sleep",
            "Apagar equipo (aviso 30s)": "shutdown",
        }
        self.config.set("on_finish_action", mapping.get(choice, "none"))

    def _on_queue_all_completed(self):
        action = self.config.get("on_finish_action", "none")
        if action == "sound":
            try:
                import winsound
                winsound.MessageBeep(winsound.MB_ICONASTERISK)
            except Exception:
                pass
        elif action == "sleep":
            try:
                flags = getattr(subprocess, 'CREATE_NO_WINDOW', 0) if sys.platform == "win32" else 0
                subprocess.run(["rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0"], creationflags=flags)
            except Exception:
                pass
        elif action == "shutdown":
            self.after(0, self._show_shutdown_countdown_dialog)

    def _show_shutdown_countdown_dialog(self):
        try:
            flags = getattr(subprocess, 'CREATE_NO_WINDOW', 0) if sys.platform == "win32" else 0
            subprocess.run(["shutdown", "/s", "/t", "30", "/c", "OmniVault: Descargas finalizadas. Apagando equipo..."], creationflags=flags)
        except Exception:
            pass

        win = ctk.CTkToplevel(self)
        win.title("Apagado Automático")
        win.geometry("420x210")
        win.attributes("-topmost", True)
        win.configure(fg_color=theme.BG_CARD)

        ctk.CTkLabel(win, text="💤 Descargas Completadas", font=ctk.CTkFont(size=16, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(pady=(16, 4))
        cd_lbl = ctk.CTkLabel(win, text="El equipo se apagará en 30 segundos...", font=ctk.CTkFont(size=13), text_color=theme.STATUS_WARNING)
        cd_lbl.pack(pady=6)

        seconds_left = [30]
        def count():
            seconds_left[0] -= 1
            if seconds_left[0] > 0 and win.winfo_exists():
                cd_lbl.configure(text=f"El equipo se apagará en {seconds_left[0]} segundos...")
                win.after(1000, count)

        win.after(1000, count)

        def cancel_shutdown():
            try:
                flags = getattr(subprocess, 'CREATE_NO_WINDOW', 0) if sys.platform == "win32" else 0
                subprocess.run(["shutdown", "/a"], creationflags=flags)
            except Exception:
                pass
            win.destroy()
            messagebox.showinfo("Cancelado", "El apagado automático del sistema ha sido cancelado.")

        ctk.CTkButton(
            win, text="✖ CANCELAR APAGADO", height=38,
            fg_color=theme.BTN_DANGER_BG, hover_color=theme.BTN_DANGER_HOVER, text_color=theme.BTN_DANGER_TEXT,
            font=ctk.CTkFont(size=12, weight="bold"), command=cancel_shutdown
        ).pack(pady=14, padx=20, fill="x")

        win.protocol("WM_DELETE_WINDOW", cancel_shutdown)

    # =========================================================================
    # REPRODUCTOR DE AUDIO INTEGRADO (MINI-PLAYER NATIVO MCI)
    # =========================================================================
    def _create_player_bar(self) -> ctk.CTkFrame:
        bar = ctk.CTkFrame(
            self,
            height=66,
            corner_radius=0,
            fg_color=theme.BG_SIDEBAR,
            border_width=1,
            border_color=theme.BORDER_COLOR
        )
        bar.grid_propagate(False)

        bar.grid_columnconfigure(0, weight=1, minsize=240)
        bar.grid_columnconfigure(1, weight=2, minsize=320)
        bar.grid_columnconfigure(2, weight=1, minsize=200)
        bar.grid_rowconfigure(0, weight=1)

        # Columna 1: Info pista
        info_col = ctk.CTkFrame(bar, fg_color="transparent")
        info_col.grid(row=0, column=0, sticky="w", padx=16)

        self.player_icon_lbl = ctk.CTkLabel(info_col, text="🎵", font=ctk.CTkFont(size=22), width=36)
        self.player_icon_lbl.pack(side="left", padx=(0, 10))

        text_col = ctk.CTkFrame(info_col, fg_color="transparent")
        text_col.pack(side="left", fill="both", expand=True)

        self.player_title_lbl = ctk.CTkLabel(
            text_col, text="Ninguna pista", font=ctk.CTkFont(family="Segoe UI", size=12, weight="bold"),
            text_color=theme.TEXT_PRIMARY, anchor="w"
        )
        self.player_title_lbl.pack(fill="x")

        self.player_artist_lbl = ctk.CTkLabel(
            text_col, text="OmniVault Player", font=ctk.CTkFont(family="Segoe UI", size=10),
            text_color=theme.TEXT_MUTED, anchor="w"
        )
        self.player_artist_lbl.pack(fill="x")

        # Columna 2: Controles & Timeline
        center_col = ctk.CTkFrame(bar, fg_color="transparent")
        center_col.grid(row=0, column=1, sticky="nsew", pady=4)

        ctrl_row = ctk.CTkFrame(center_col, fg_color="transparent")
        ctrl_row.pack()

        self.player_play_btn = ctk.CTkButton(
            ctrl_row, text="⏸", width=36, height=28,
            fg_color=theme.BTN_PRIMARY_BG, hover_color=theme.BTN_PRIMARY_HOVER,
            text_color=theme.BTN_PRIMARY_TEXT, font=ctk.CTkFont(size=14, weight="bold"),
            command=self._toggle_player_playback
        )
        self.player_play_btn.pack(side="left", padx=4)

        self.player_stop_btn = ctk.CTkButton(
            ctrl_row, text="⏹", width=30, height=28,
            fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER,
            text_color=theme.BTN_SECONDARY_TEXT, font=ctk.CTkFont(size=11),
            command=self._stop_player_playback
        )
        self.player_stop_btn.pack(side="left", padx=4)

        seek_row = ctk.CTkFrame(center_col, fg_color="transparent")
        seek_row.pack(fill="x", padx=12, pady=(2, 0))

        self.player_time_cur_lbl = ctk.CTkLabel(seek_row, text="00:00", font=ctk.CTkFont(size=10), text_color=theme.TEXT_MUTED, width=35)
        self.player_time_cur_lbl.pack(side="left")

        self.player_slider = ctk.CTkSlider(
            seek_row, from_=0, to=1000, height=14,
            button_length=10, button_color=theme.ACCENT_CYAN,
            progress_color=theme.ACCENT_CYAN, fg_color=theme.BG_CARD_HOVER,
            command=self._on_player_slider_change
        )
        self.player_slider.set(0)
        self.player_slider.pack(side="left", fill="x", expand=True, padx=8)

        self.player_time_tot_lbl = ctk.CTkLabel(seek_row, text="00:00", font=ctk.CTkFont(size=10), text_color=theme.TEXT_MUTED, width=35)
        self.player_time_tot_lbl.pack(side="right")

        # Columna 3: Volumen y Botón Cerrar
        right_col = ctk.CTkFrame(bar, fg_color="transparent")
        right_col.grid(row=0, column=2, sticky="e", padx=16)

        ctk.CTkLabel(right_col, text="🔊", font=ctk.CTkFont(size=12)).pack(side="left", padx=(0, 4))

        self.player_vol_slider = ctk.CTkSlider(
            right_col, from_=0.0, to=1.0, width=90, height=14,
            button_length=10, button_color=theme.TEXT_PRIMARY,
            command=self._on_volume_slider_change
        )
        self.player_vol_slider.set(0.8)
        self.player_vol_slider.pack(side="left", padx=(0, 10))

        close_btn = ctk.CTkButton(
            right_col, text="✕", width=26, height=26,
            fg_color="transparent", hover_color=theme.BTN_DANGER_BG,
            text_color=theme.TEXT_SECONDARY, font=ctk.CTkFont(size=12),
            command=self._hide_player
        )
        close_btn.pack(side="left")

        return bar

    def _play_in_app_audio(self, item: dict):
        file_path = item.get("file_path")
        if not file_path or not os.path.isfile(file_path):
            messagebox.showerror("Archivo no encontrado", "El archivo de audio no se encuentra en el disco.")
            return

        title = item.get("title", os.path.basename(file_path))
        label = item.get("format_label", "Audio")

        if self.player.load(file_path):
            self.player.play()
            self.player_title_lbl.configure(text=title[:38] + ("..." if len(title) > 38 else ""))
            self.player_artist_lbl.configure(text=label)
            self.player_play_btn.configure(text="⏸")
            self.player_slider.set(0)

            dur_s = int(self.player.get_duration() / 1000)
            self.player_time_tot_lbl.configure(text=format_duration(dur_s))
            self.player_time_cur_lbl.configure(text="00:00")

            self._is_player_visible = True
            self.player_frame.grid(row=1, column=0, columnspan=2, sticky="ew")
        else:
            self.history.play_media(file_path)

    def _on_play_library_item(self, item: dict):
        fp = (item.get("file_path") or "").lower()
        is_audio = item.get("media_type") == "audio" or any(fp.endswith(ext) for ext in [".mp3", ".wav", ".m4a"])
        if is_audio:
            self._play_in_app_audio(item)
        else:
            self.history.play_media(item.get("file_path"))

    def _toggle_player_playback(self):
        if self.player.is_playing():
            self.player.pause()
            self.player_play_btn.configure(text="▶")
        else:
            self.player.resume()
            self.player_play_btn.configure(text="⏸")

    def _stop_player_playback(self):
        self.player.stop()
        self.player_play_btn.configure(text="▶")
        self.player_slider.set(0)
        self.player_time_cur_lbl.configure(text="00:00")

    def _hide_player(self):
        self.player.stop()
        self._is_player_visible = False
        self.player_frame.grid_forget()

    def _on_player_slider_change(self, val):
        self._is_seeking = True
        dur_ms = self.player.get_duration()
        if dur_ms > 0:
            target_ms = int((val / 1000.0) * dur_ms)
            self.player.seek(target_ms)
            cur_s = int(target_ms / 1000)
            self.player_time_cur_lbl.configure(text=format_duration(cur_s))
        self._is_seeking = False

    def _on_volume_slider_change(self, val):
        self.player.set_volume(float(val))

    def _update_player_loop(self):
        if self._is_player_visible and self.player.is_playing() and not self._is_seeking:
            pos_ms = self.player.get_position()
            dur_ms = self.player.get_duration()
            if dur_ms > 0:
                fraction = pos_ms / dur_ms
                self.player_slider.set(fraction * 1000)
                cur_s = int(pos_ms / 1000)
                tot_s = int(dur_ms / 1000)
                self.player_time_cur_lbl.configure(text=format_duration(cur_s))
                self.player_time_tot_lbl.configure(text=format_duration(tot_s))

            if pos_ms >= dur_ms and dur_ms > 0:
                self.player_play_btn.configure(text="▶")

        self.after(300, self._update_player_loop)

    # =========================================================================
    # ATAJOS DE TECLADO RÁPIDOS
    # =========================================================================
    def _setup_keyboard_shortcuts(self):
        self.bind("<Control-v>", self._on_shortcut_paste)
        self.bind("<Control-V>", self._on_shortcut_paste)
        self.bind("<Control-f>", self._on_shortcut_search)
        self.bind("<Control-F>", self._on_shortcut_search)
        self.bind("<space>", self._on_shortcut_space)
        self.bind("<Escape>", self._on_shortcut_escape)

    def _on_shortcut_paste(self, event=None):
        focused = self.focus_get()
        if isinstance(focused, (tk.Entry, tk.Text)) or "entry" in str(focused).lower():
            return
        try:
            clip = self.clipboard_get().strip()
            if is_url(clip):
                self._select_tab("explore")
                self.url_entry.delete(0, tk.END)
                self.url_entry.insert(0, clip)
                self._on_search_clicked()
        except Exception:
            pass

    def _on_shortcut_search(self, event=None):
        self._select_tab("library")
        if hasattr(self, "lib_search_entry"):
            self.lib_search_entry.focus_set()

    def _on_shortcut_space(self, event=None):
        focused = self.focus_get()
        if isinstance(focused, (tk.Entry, tk.Text)) or "entry" in str(focused).lower():
            return
        if self._is_player_visible:
            self._toggle_player_playback()

    def _on_shortcut_escape(self, event=None):
        if self._is_player_visible:
            self._hide_player()


if __name__ == "__main__":
    app = OmniVaultApp()
    app.mainloop()
