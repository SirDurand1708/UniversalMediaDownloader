"""
Snaptube Pro Desktop - Edición Privada y Segura
Interfaz prémium 'Obsidian & Titanium' construida con CustomTkinter.
"""

import os
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox
import customtkinter as ctk

import theme
from downloader import DownloaderEngine, is_url
from queue_manager import QueueManager, DownloadTask
from history_manager import HistoryManager
from image_cache import ThumbnailCache

# Configuración base de CustomTkinter
ctk.set_appearance_mode("Dark")
ctk.set_default_color_theme("blue")

class SnaptubeDesktopApp(ctk.CTk):
    def __init__(self):
        super().__init__()

        self.title("Snaptube Pro Desktop - Edición Privada")
        self.geometry("1020x720")
        self.minsize(920, 640)
        self.configure(fg_color=theme.BG_MAIN)

        # Servicios y Motores
        self.downloader = DownloaderEngine()
        self.history = HistoryManager()
        self.queue_manager = QueueManager(self.downloader, self.history, max_workers=2)
        self.img_cache = ThumbnailCache()

        # Callbacks del gestor de descargas
        self.queue_manager.on_task_updated = self._on_task_updated
        self.queue_manager.on_task_finished = self._on_task_finished

        # Carpeta predeterminada: Descargas del usuario
        default_dl = os.path.join(os.path.expanduser("~"), "Downloads")
        self.download_dir = default_dl if os.path.exists(default_dl) else os.getcwd()

        # Estado actual de inspección
        self.current_media = None
        self.selected_format = ctk.StringVar(value="mp3_320")

        self._build_layout()
        self._select_tab("explore")

    def _build_layout(self):
        # Contenedor raíz dividido en Barra Lateral y Área de Contenido
        self.grid_rowconfigure(0, weight=1)
        self.grid_columnconfigure(1, weight=1)

        # --- BARRA LATERAL ---
        self.sidebar = ctk.CTkFrame(
            self,
            width=230,
            corner_radius=0,
            fg_color=theme.BG_SIDEBAR,
            border_width=1,
            border_color=theme.BORDER_COLOR
        )
        self.sidebar.grid(row=0, column=0, sticky="nsew")
        self.sidebar.grid_propagate(False)

        # Logotipo / Branding
        brand_frame = ctk.CTkFrame(self.sidebar, fg_color="transparent")
        brand_frame.pack(fill="x", padx=18, pady=(24, 20))

        logo_title = ctk.CTkLabel(
            brand_frame,
            text="✦ SNAPTUBE",
            font=ctk.CTkFont(family="Segoe UI", size=20, weight="bold"),
            text_color=theme.TEXT_PRIMARY
        )
        logo_title.pack(anchor="w")

        logo_sub = ctk.CTkLabel(
            brand_frame,
            text="PRO DESKTOP • OBSIDIAN",
            font=ctk.CTkFont(family="Segoe UI", size=10, weight="bold"),
            text_color=theme.TEXT_MUTED
        )
        logo_sub.pack(anchor="w")

        # Botones de Navegación Lateral
        self.nav_btns = {}
        nav_items = [
            ("explore", "🔍  Explorar", self._show_explore),
            ("downloads", "📥  Descargas", self._show_downloads),
            ("library", "🎵  Mi Biblioteca", self._show_library),
            ("settings", "⚙  Ajustes", self._show_settings),
        ]

        self.nav_frame = ctk.CTkFrame(self.sidebar, fg_color="transparent")
        self.nav_frame.pack(fill="x", padx=12, pady=10)

        for tab_id, text, cmd in nav_items:
            btn = ctk.CTkButton(
                self.nav_frame,
                text=text,
                anchor="w",
                height=42,
                corner_radius=theme.CORNER_RADIUS,
                font=ctk.CTkFont(family="Segoe UI", size=13, weight="bold"),
                fg_color="transparent",
                text_color=theme.TEXT_SECONDARY,
                hover_color=theme.BG_CARD_HOVER,
                command=cmd
            )
            btn.pack(fill="x", pady=4)
            self.nav_btns[tab_id] = btn

        # Espaciador
        self.sidebar_spacer = ctk.CTkFrame(self.sidebar, fg_color="transparent")
        self.sidebar_spacer.pack(fill="both", expand=True)

        # Estado de FFmpeg en la parte inferior de la barra lateral
        status_box = ctk.CTkFrame(
            self.sidebar,
            corner_radius=theme.CORNER_RADIUS_SM,
            fg_color=theme.BG_CARD,
            border_width=1,
            border_color=theme.BORDER_COLOR
        )
        status_box.pack(fill="x", padx=14, pady=16)

        ffmpeg_status = ctk.CTkLabel(
            status_box,
            text="● Motor FFmpeg Activo",
            font=ctk.CTkFont(size=11, weight="bold"),
            text_color=theme.STATUS_SUCCESS
        )
        ffmpeg_status.pack(anchor="w", padx=10, pady=(6, 2))

        privacy_lbl = ctk.CTkLabel(
            status_box,
            text="100% Privado • Sin Cuentas",
            font=ctk.CTkFont(size=10),
            text_color=theme.TEXT_MUTED
        )
        privacy_lbl.pack(anchor="w", padx=10, pady=(0, 6))

        # --- ÁREA PRINCIPAL ---
        self.main_container = ctk.CTkFrame(self, fg_color=theme.BG_MAIN, corner_radius=0)
        self.main_container.grid(row=0, column=1, sticky="nsew", padx=20, pady=20)
        self.main_container.grid_rowconfigure(0, weight=1)
        self.main_container.grid_columnconfigure(0, weight=1)

        # Contenedores para cada vista
        self.views = {
            "explore": self._create_explore_view(),
            "downloads": self._create_downloads_view(),
            "library": self._create_library_view(),
            "settings": self._create_settings_view(),
        }

    def _select_tab(self, tab_id: str):
        for t_id, view in self.views.items():
            if t_id == tab_id:
                view.grid(row=0, column=0, sticky="nsew")
                self.nav_btns[t_id].configure(
                    fg_color=theme.BG_CARD,
                    text_color=theme.TEXT_PRIMARY
                )
            else:
                view.grid_forget()
                self.nav_btns[t_id].configure(
                    fg_color="transparent",
                    text_color=theme.TEXT_SECONDARY
                )

        if tab_id == "library":
            self._refresh_library_list()
        elif tab_id == "downloads":
            self._refresh_downloads_list()

    def _show_explore(self): self._select_tab("explore")
    def _show_downloads(self): self._select_tab("downloads")
    def _show_library(self): self._select_tab("library")
    def _show_settings(self): self._select_tab("settings")

    # =========================================================================
    # VISTA 1: EXPLORAR / BUSCAR
    # =========================================================================
    def _create_explore_view(self) -> ctk.CTkFrame:
        view = ctk.CTkFrame(self.main_container, fg_color="transparent")
        view.grid_rowconfigure(2, weight=1)
        view.grid_columnconfigure(0, weight=1)

        # Barra Superior de Búsqueda
        search_box = ctk.CTkFrame(view, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
        search_box.grid(row=0, column=0, sticky="ew", pady=(0, 16))

        self.search_entry = ctk.CTkEntry(
            search_box,
            placeholder_text="🔍  Pega un enlace (YouTube, TikTok, Reels, Twitter...) o busca por título...",
            font=ctk.CTkFont(size=13),
            height=44,
            fg_color=theme.BG_INPUT,
            border_width=0,
            text_color=theme.TEXT_PRIMARY
        )
        self.search_entry.pack(side="left", fill="x", expand=True, padx=(10, 8), pady=8)
        self.search_entry.bind("<Return>", lambda e: self._on_search_submit())

        paste_btn = ctk.CTkButton(
            search_box,
            text="📋 Pegar",
            width=80,
            height=38,
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
            width=130,
            height=38,
            fg_color=theme.BTN_PRIMARY_BG,
            hover_color=theme.BTN_PRIMARY_HOVER,
            text_color=theme.BTN_PRIMARY_TEXT,
            font=ctk.CTkFont(size=12, weight="bold"),
            command=self._on_search_submit
        )
        self.search_btn.pack(side="left", padx=(0, 10))

        # Indicador de estado de análisis
        self.analyze_status = ctk.CTkLabel(
            view,
            text="",
            font=ctk.CTkFont(size=12),
            text_color=theme.ACCENT_CYAN
        )
        self.analyze_status.grid(row=1, column=0, sticky="w", pady=(0, 8))

        # Área de Contenido Dinámico (Resultados de Búsqueda o Tarjeta Snaptube)
        self.explore_scroll = ctk.CTkScrollableFrame(
            view,
            fg_color="transparent",
            corner_radius=theme.CORNER_RADIUS
        )
        self.explore_scroll.grid(row=2, column=0, sticky="nsew")

        # Pantalla de bienvenida inicial
        self._show_explore_welcome()

        return view

    def _show_explore_welcome(self):
        for widget in self.explore_scroll.winfo_children():
            widget.destroy()

        welcome_frame = ctk.CTkFrame(self.explore_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS_LG, border_width=1, border_color=theme.BORDER_COLOR)
        welcome_frame.pack(fill="x", pady=20, padx=10)

        ctk.CTkLabel(
            welcome_frame,
            text="Descargas Multimedia en Máxima Calidad",
            font=ctk.CTkFont(size=20, weight="bold"),
            text_color=theme.TEXT_PRIMARY
        ).pack(pady=(25, 6))

        ctk.CTkLabel(
            welcome_frame,
            text="Sin publicidad, sin registro, sin límite de velocidad y sin intermediarios sospechosos.\nCompatible con YouTube, TikTok, Instagram, Twitter/X, Twitch, SoundCloud y más.",
            font=ctk.CTkFont(size=12),
            text_color=theme.TEXT_SECONDARY,
            justify="center"
        ).pack(pady=(0, 20))

        # Chips de sitios compatibles
        chips_frame = ctk.CTkFrame(welcome_frame, fg_color="transparent")
        chips_frame.pack(pady=(0, 25))

        platforms = ["YouTube 4K", "TikTok Sin Marca", "Instagram Reels", "Twitter / X", "Twitch Clips", "SoundCloud HD"]
        for p in platforms:
            chip = ctk.CTkLabel(
                chips_frame,
                text=f"  {p}  ",
                fg_color=theme.BG_INPUT,
                corner_radius=theme.CORNER_RADIUS_SM,
                font=ctk.CTkFont(size=11, weight="bold"),
                text_color=theme.TEXT_ACCENT
            )
            chip.pack(side="left", padx=4)

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

        self.search_btn.configure(state="disabled", text="Procesando...")
        self.analyze_status.configure(text="Conectando y analizando...")

        if is_url(raw_text):
            # Es URL directa -> Inspeccionar y abrir modal Snaptube
            threading.Thread(target=self._async_inspect_url, args=(raw_text,), daemon=True).start()
        else:
            # Es búsqueda por texto -> Buscar en YouTube
            threading.Thread(target=self._async_search, args=(raw_text,), daemon=True).start()

    def _async_inspect_url(self, url: str):
        try:
            data = self.downloader.inspect_url(url)
            self.after(0, lambda: self._display_snaptube_modal(data))
        except Exception as e:
            err = str(e)
            self.after(0, lambda: self._on_analyze_error(err))

    def _async_search(self, query: str):
        try:
            results = self.downloader.search_youtube(query, max_results=6)
            self.after(0, lambda: self._display_search_results(results, query))
        except Exception as e:
            err = str(e)
            self.after(0, lambda: self._on_analyze_error(err))

    def _on_analyze_error(self, err_msg: str):
        self.search_btn.configure(state="normal", text="Buscar / Analizar")
        self.analyze_status.configure(text=f"✖ Error: {err_msg[:60]}", text_color=theme.STATUS_ERROR)
        messagebox.showerror("Error", f"No se pudo analizar el contenido:\n\n{err_msg}")

    def _display_search_results(self, results: list, query: str):
        self.search_btn.configure(state="normal", text="Buscar / Analizar")
        self.analyze_status.configure(text=f"Se encontraron {len(results)} resultados para '{query}'", text_color=theme.TEXT_SECONDARY)

        for widget in self.explore_scroll.winfo_children():
            widget.destroy()

        if not results:
            ctk.CTkLabel(
                self.explore_scroll,
                text="No se encontraron videos coincidentes.",
                font=ctk.CTkFont(size=14),
                text_color=theme.TEXT_MUTED
            ).pack(pady=40)
            return

        for item in results:
            card = ctk.CTkFrame(
                self.explore_scroll,
                fg_color=theme.BG_CARD,
                corner_radius=theme.CORNER_RADIUS,
                border_width=1,
                border_color=theme.BORDER_COLOR
            )
            card.pack(fill="x", pady=6, padx=5)

            # Contenedor para thumbnail
            thumb_label = ctk.CTkLabel(
                card,
                text="",
                width=120,
                height=68,
                fg_color=theme.BG_INPUT,
                corner_radius=theme.CORNER_RADIUS_SM
            )
            thumb_label.pack(side="left", padx=10, pady=10)

            # Carga asíncrona de miniatura
            if item.get("thumbnail"):
                def _set_thumb(img, lbl=thumb_label):
                    lbl.configure(image=img)
                cached = self.img_cache.get_thumbnail(item["thumbnail"], size=(120, 68), callback=lambda img, l=thumb_label: self.after(0, lambda: _set_thumb(img, l)))
                if cached:
                    thumb_label.configure(image=cached)

            # Información de texto
            info_frame = ctk.CTkFrame(card, fg_color="transparent")
            info_frame.pack(side="left", fill="both", expand=True, padx=10, pady=10)

            title_lbl = ctk.CTkLabel(
                info_frame,
                text=item.get("title", ""),
                font=ctk.CTkFont(size=13, weight="bold"),
                text_color=theme.TEXT_PRIMARY,
                anchor="w"
            )
            title_lbl.pack(fill="x")

            meta_text = f"Canal: {item.get('uploader')}   •   Duración: {item.get('duration')}"
            ctk.CTkLabel(
                info_frame,
                text=meta_text,
                font=ctk.CTkFont(size=11),
                text_color=theme.TEXT_SECONDARY,
                anchor="w"
            ).pack(fill="x", pady=(3, 0))

            # Botón seleccionar
            sel_btn = ctk.CTkButton(
                card,
                text="Seleccionar ➔",
                width=110,
                height=34,
                fg_color=theme.BTN_SECONDARY_BG,
                hover_color=theme.BTN_SECONDARY_HOVER,
                text_color=theme.BTN_SECONDARY_TEXT,
                font=ctk.CTkFont(size=12, weight="bold"),
                command=lambda url=item.get("url"): self._select_result(url)
            )
            sel_btn.pack(side="right", padx=14)

    def _select_result(self, url: str):
        self.search_entry.delete(0, tk.END)
        self.search_entry.insert(0, url)
        self._on_search_submit()

    # --- MODAL ESTILO SNAPTUBE (SELECTOR DE RESOLUCIÓN EN TITANIO) ---
    def _display_snaptube_modal(self, data: dict):
        self.search_btn.configure(state="normal", text="Buscar / Analizar")
        self.analyze_status.configure(text="✔ Contenido verificado listo para descargar", text_color=theme.STATUS_SUCCESS)
        self.current_media = data

        for widget in self.explore_scroll.winfo_children():
            widget.destroy()

        # Tarjeta de Video Principal
        card = ctk.CTkFrame(
            self.explore_scroll,
            fg_color=theme.BG_CARD,
            corner_radius=theme.CORNER_RADIUS_LG,
            border_width=1,
            border_color=theme.BORDER_COLOR
        )
        card.pack(fill="x", pady=10, padx=5)

        header_row = ctk.CTkFrame(card, fg_color="transparent")
        header_row.pack(fill="x", padx=16, pady=16)

        # Miniatura Grande
        thumb_label = ctk.CTkLabel(
            header_row,
            text="",
            width=180,
            height=101,
            fg_color=theme.BG_INPUT,
            corner_radius=theme.CORNER_RADIUS
        )
        thumb_label.pack(side="left", padx=(0, 16))

        if data.get("thumbnail"):
            cached = self.img_cache.get_thumbnail(
                data["thumbnail"],
                size=(180, 101),
                callback=lambda img, l=thumb_label: self.after(0, lambda: l.configure(image=img))
            )
            if cached:
                thumb_label.configure(image=cached)

        # Metadatos del contenido
        info_col = ctk.CTkFrame(header_row, fg_color="transparent")
        info_col.pack(side="left", fill="both", expand=True)

        ctk.CTkLabel(
            info_col,
            text=data.get("title", ""),
            font=ctk.CTkFont(size=16, weight="bold"),
            text_color=theme.TEXT_PRIMARY,
            anchor="w",
            wraplength=480,
            justify="left"
        ).pack(fill="x")

        meta = f"{data.get('uploader')}   •   Duración: {data.get('duration')}   •   Fuente: {data.get('extractor')}"
        ctk.CTkLabel(
            info_col,
            text=meta,
            font=ctk.CTkFont(size=12),
            text_color=theme.TEXT_SECONDARY,
            anchor="w"
        ).pack(fill="x", pady=(6, 0))

        # --- SELECCIÓN DE RESOLUCIÓN (2 COLUMNAS ESTILO SNAPTUBE) ---
        grid_frame = ctk.CTkFrame(card, fg_color=theme.BG_INPUT, corner_radius=theme.CORNER_RADIUS)
        grid_frame.pack(fill="x", padx=16, pady=(0, 16))
        grid_frame.grid_columnconfigure(0, weight=1)
        grid_frame.grid_columnconfigure(1, weight=1)

        # Columna 1: MÚSICA & AUDIO
        audio_col = ctk.CTkFrame(grid_frame, fg_color="transparent")
        audio_col.grid(row=0, column=0, sticky="nsew", padx=14, pady=14)

        ctk.CTkLabel(
            audio_col,
            text="🎵 OPCIONES DE AUDIO",
            font=ctk.CTkFont(size=13, weight="bold"),
            text_color=theme.TEXT_PRIMARY
        ).pack(anchor="w", pady=(0, 10))

        audio_options = [
            ("mp3_320", "MP3 320 kbps (HD Máximo + Portada)"),
            ("mp3_192", "MP3 192 kbps (Estándar Ligero)"),
            ("m4a_best", "M4A Original (Sin recompresión)"),
            ("wav", "WAV Audio de Estudio (Sin pérdida)"),
        ]

        for val, label in audio_options:
            rb = ctk.CTkRadioButton(
                audio_col,
                text=label,
                variable=self.selected_format,
                value=val,
                font=ctk.CTkFont(size=12),
                text_color=theme.TEXT_SECONDARY,
                fg_color=theme.BTN_PRIMARY_BG,
                hover_color=theme.BTN_PRIMARY_HOVER
            )
            rb.pack(anchor="w", pady=5)

        # Columna 2: VIDEO MP4
        video_col = ctk.CTkFrame(grid_frame, fg_color="transparent")
        video_col.grid(row=0, column=1, sticky="nsew", padx=14, pady=14)

        ctk.CTkLabel(
            video_col,
            text="🎬 OPCIONES DE VIDEO (MP4)",
            font=ctk.CTkFont(size=13, weight="bold"),
            text_color=theme.TEXT_PRIMARY
        ).pack(anchor="w", pady=(0, 10))

        video_options = [
            ("mp4_4k", "4K / 2K Ultra HD (Máxima nitidez 60fps)"),
            ("mp4_1080", "1080p Full HD"),
            ("mp4_720", "720p HD (Ligero y Rápido)"),
            ("mp4_480", "480p / 360p Ahorro de espacio"),
        ]

        for val, label in video_options:
            rb = ctk.CTkRadioButton(
                video_col,
                text=label,
                variable=self.selected_format,
                value=val,
                font=ctk.CTkFont(size=12),
                text_color=theme.TEXT_SECONDARY,
                fg_color=theme.BTN_PRIMARY_BG,
                hover_color=theme.BTN_PRIMARY_HOVER
            )
            rb.pack(anchor="w", pady=5)

        # Botón Grande Plateado Titanio: DESCARGAR AHORA
        btn_row = ctk.CTkFrame(card, fg_color="transparent")
        btn_row.pack(fill="x", padx=16, pady=(0, 16))

        dl_btn = ctk.CTkButton(
            btn_row,
            text="⬇ DESCARGAR AHORA",
            height=46,
            fg_color=theme.BTN_PRIMARY_BG,
            hover_color=theme.BTN_PRIMARY_HOVER,
            text_color=theme.BTN_PRIMARY_TEXT,
            font=ctk.CTkFont(size=14, weight="bold"),
            command=self._enqueue_current_media
        )
        dl_btn.pack(fill="x")

    def _enqueue_current_media(self):
        if not self.current_media:
            return

        fmt = self.selected_format.get()
        url = self.current_media.get("url")
        title = self.current_media.get("title")
        thumb = self.current_media.get("thumbnail")

        # Encolar descarga
        task = self.queue_manager.add_download(
            url=url,
            format_type=fmt,
            output_dir=self.download_dir,
            title=title,
            thumbnail_url=thumb
        )

        messagebox.showinfo("Descarga Encolada", f"'{title[:40]}...' ha sido añadido a la lista de descargas.")
        self._select_tab("downloads")

    # =========================================================================
    # VISTA 2: DESCARGAS ACTIVAS
    # =========================================================================
    def _create_downloads_view(self) -> ctk.CTkFrame:
        view = ctk.CTkFrame(self.main_container, fg_color="transparent")
        view.grid_rowconfigure(1, weight=1)
        view.grid_columnconfigure(0, weight=1)

        # Cabecera
        header = ctk.CTkFrame(view, fg_color="transparent")
        header.grid(row=0, column=0, sticky="ew", pady=(0, 12))

        ctk.CTkLabel(
            header,
            text="Descargas Activas",
            font=ctk.CTkFont(size=20, weight="bold"),
            text_color=theme.TEXT_PRIMARY
        ).pack(side="left")

        self.downloads_scroll = ctk.CTkScrollableFrame(
            view,
            fg_color="transparent",
            corner_radius=theme.CORNER_RADIUS
        )
        self.downloads_scroll.grid(row=1, column=0, sticky="nsew")

        self.task_ui_cards: dict = {}
        return view

    def _refresh_downloads_list(self):
        tasks = self.queue_manager.get_all_tasks()
        # Limpiar
        for widget in self.downloads_scroll.winfo_children():
            widget.destroy()
        self.task_ui_cards.clear()

        if not tasks:
            empty_box = ctk.CTkFrame(self.downloads_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS_LG, border_width=1, border_color=theme.BORDER_COLOR)
            empty_box.pack(fill="x", pady=40, padx=20)

            ctk.CTkLabel(
                empty_box,
                text="📥 No hay descargas activas",
                font=ctk.CTkFont(size=18, weight="bold"),
                text_color=theme.TEXT_PRIMARY
            ).pack(pady=(30, 6))

            ctk.CTkLabel(
                empty_box,
                text="Busca un video o pega un enlace en la pestaña Explorar para comenzar.",
                font=ctk.CTkFont(size=12),
                text_color=theme.TEXT_SECONDARY
            ).pack(pady=(0, 30))
            return

        for task in reversed(tasks):
            card = ctk.CTkFrame(
                self.downloads_scroll,
                fg_color=theme.BG_CARD,
                corner_radius=theme.CORNER_RADIUS,
                border_width=1,
                border_color=theme.BORDER_COLOR
            )
            card.pack(fill="x", pady=6, padx=5)

            # Fila superior: Título y Formato
            top_row = ctk.CTkFrame(card, fg_color="transparent")
            top_row.pack(fill="x", padx=14, pady=(12, 6))

            title_lbl = ctk.CTkLabel(
                top_row,
                text=task.title,
                font=ctk.CTkFont(size=13, weight="bold"),
                text_color=theme.TEXT_PRIMARY,
                anchor="w"
            )
            title_lbl.pack(side="left", fill="x", expand=True)

            fmt_badge = ctk.CTkLabel(
                top_row,
                text=f"  {task.format_label}  ",
                font=ctk.CTkFont(size=11, weight="bold"),
                fg_color=theme.BG_INPUT,
                text_color=theme.TEXT_ACCENT,
                corner_radius=theme.CORNER_RADIUS_SM
            )
            fmt_badge.pack(side="right")

            # Barra de progreso
            p_bar = ctk.CTkProgressBar(card, height=8, fg_color=theme.BG_INPUT, progress_color=theme.ACCENT_CYAN)
            p_bar.pack(fill="x", padx=14, pady=(2, 6))
            p_bar.set(task.percent)

            # Fila inferior: Estado y Botón Cancelar
            bot_row = ctk.CTkFrame(card, fg_color="transparent")
            bot_row.pack(fill="x", padx=14, pady=(0, 10))

            status_lbl = ctk.CTkLabel(
                bot_row,
                text=task.status_text,
                font=ctk.CTkFont(size=11),
                text_color=theme.TEXT_MUTED
            )
            status_lbl.pack(side="left")

            cancel_btn = ctk.CTkButton(
                bot_row,
                text="Cancelar",
                width=75,
                height=26,
                fg_color=theme.BTN_DANGER_BG,
                hover_color=theme.BTN_DANGER_HOVER,
                text_color=theme.BTN_DANGER_TEXT,
                font=ctk.CTkFont(size=11, weight="bold"),
                command=lambda t_id=task.id: self._cancel_task(t_id)
            )
            if task.status in ["queued", "downloading", "converting"]:
                cancel_btn.pack(side="right")

            self.task_ui_cards[task.id] = {
                "progress_bar": p_bar,
                "status_label": status_lbl,
                "cancel_btn": cancel_btn
            }

    def _cancel_task(self, task_id: str):
        self.queue_manager.cancel_download(task_id)

    def _on_task_updated(self, task: DownloadTask):
        def _apply():
            # Actualizar insignia de descargas activas en la barra lateral
            active_count = len(self.queue_manager.get_active_tasks())
            btn_text = f"📥  Descargas ({active_count})" if active_count > 0 else "📥  Descargas"
            self.nav_btns["downloads"].configure(text=btn_text)

            # Si la tarjeta está visible, actualizarla
            if task.id in self.task_ui_cards:
                ui = self.task_ui_cards[task.id]
                ui["progress_bar"].set(task.percent)
                ui["status_label"].configure(text=task.status_text)
                if task.status in ["completed", "error", "cancelled"]:
                    ui["cancel_btn"].pack_forget()

        self.after(0, _apply)

    def _on_task_finished(self, task: DownloadTask):
        def _apply():
            self._on_task_updated(task)
        self.after(0, _apply)

    # =========================================================================
    # VISTA 3: MI BIBLIOTECA
    # =========================================================================
    def _create_library_view(self) -> ctk.CTkFrame:
        view = ctk.CTkFrame(self.main_container, fg_color="transparent")
        view.grid_rowconfigure(1, weight=1)
        view.grid_columnconfigure(0, weight=1)

        header = ctk.CTkFrame(view, fg_color="transparent")
        header.grid(row=0, column=0, sticky="ew", pady=(0, 12))

        ctk.CTkLabel(
            header,
            text="Mi Biblioteca",
            font=ctk.CTkFont(size=20, weight="bold"),
            text_color=theme.TEXT_PRIMARY
        ).pack(side="left")

        open_folder_btn = ctk.CTkButton(
            header,
            text="📂 Abrir Carpeta de Descargas",
            height=34,
            fg_color=theme.BTN_SECONDARY_BG,
            hover_color=theme.BTN_SECONDARY_HOVER,
            text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=12, weight="bold"),
            command=self._open_downloads_folder
        )
        open_folder_btn.pack(side="right")

        self.library_scroll = ctk.CTkScrollableFrame(
            view,
            fg_color="transparent",
            corner_radius=theme.CORNER_RADIUS
        )
        self.library_scroll.grid(row=1, column=0, sticky="nsew")

        return view

    def _refresh_library_list(self):
        for widget in self.library_scroll.winfo_children():
            widget.destroy()

        items = self.history.get_all()
        if not items:
            empty_box = ctk.CTkFrame(self.library_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS_LG, border_width=1, border_color=theme.BORDER_COLOR)
            empty_box.pack(fill="x", pady=40, padx=20)

            ctk.CTkLabel(
                empty_box,
                text="🎵 Tu biblioteca está vacía",
                font=ctk.CTkFont(size=18, weight="bold"),
                text_color=theme.TEXT_PRIMARY
            ).pack(pady=(30, 6))

            ctk.CTkLabel(
                empty_box,
                text="Los audios y videos que descargues aparecerán aquí organizados.",
                font=ctk.CTkFont(size=12),
                text_color=theme.TEXT_SECONDARY
            ).pack(pady=(0, 30))
            return

        for item in items:
            card = ctk.CTkFrame(
                self.library_scroll,
                fg_color=theme.BG_CARD,
                corner_radius=theme.CORNER_RADIUS,
                border_width=1,
                border_color=theme.BORDER_COLOR
            )
            card.pack(fill="x", pady=5, padx=5)

            # Icono según formato
            is_audio = item.get("media_type") == "audio"
            icon_text = "🎵" if is_audio else "🎬"

            icon_lbl = ctk.CTkLabel(
                card,
                text=icon_text,
                font=ctk.CTkFont(size=20),
                width=45
            )
            icon_lbl.pack(side="left", padx=10, pady=10)

            # Detalles
            info_frame = ctk.CTkFrame(card, fg_color="transparent")
            info_frame.pack(side="left", fill="both", expand=True, padx=5, pady=8)

            title_lbl = ctk.CTkLabel(
                info_frame,
                text=item.get("title", ""),
                font=ctk.CTkFont(size=13, weight="bold"),
                text_color=theme.TEXT_PRIMARY,
                anchor="w"
            )
            title_lbl.pack(fill="x")

            # Formatear tamaño
            size_mb = item.get("file_size", 0) / (1024 * 1024)
            size_str = f"{size_mb:.1f} MB" if size_mb > 0 else "-- MB"

            meta_lbl = ctk.CTkLabel(
                info_frame,
                text=f"{item.get('format_label')}   •   {size_str}   •   {item.get('date')}",
                font=ctk.CTkFont(size=11),
                text_color=theme.TEXT_SECONDARY,
                anchor="w"
            )
            meta_lbl.pack(fill="x", pady=(2, 0))

            # Acciones
            actions_frame = ctk.CTkFrame(card, fg_color="transparent")
            actions_frame.pack(side="right", padx=12)

            play_btn = ctk.CTkButton(
                actions_frame,
                text="▶ Reproducir",
                width=90,
                height=30,
                fg_color=theme.BTN_PRIMARY_BG,
                hover_color=theme.BTN_PRIMARY_HOVER,
                text_color=theme.BTN_PRIMARY_TEXT,
                font=ctk.CTkFont(size=11, weight="bold"),
                command=lambda p=item.get("file_path"): self.history.play_media(p)
            )
            play_btn.pack(side="left", padx=3)

            folder_btn = ctk.CTkButton(
                actions_frame,
                text="📁",
                width=34,
                height=30,
                fg_color=theme.BTN_SECONDARY_BG,
                hover_color=theme.BTN_SECONDARY_HOVER,
                text_color=theme.BTN_SECONDARY_TEXT,
                font=ctk.CTkFont(size=12),
                command=lambda p=item.get("file_path"): self.history.reveal_in_folder(p)
            )
            folder_btn.pack(side="left", padx=3)

            del_btn = ctk.CTkButton(
                actions_frame,
                text="✖",
                width=34,
                height=30,
                fg_color=theme.BTN_DANGER_BG,
                hover_color=theme.BTN_DANGER_HOVER,
                text_color=theme.BTN_DANGER_TEXT,
                font=ctk.CTkFont(size=12),
                command=lambda i=item.get("id"): self._delete_library_item(i)
            )
            del_btn.pack(side="left", padx=3)

    def _delete_library_item(self, item_id: str):
        self.history.delete_item(item_id)
        self._refresh_library_list()

    def _open_downloads_folder(self):
        if os.path.exists(self.download_dir):
            if sys.platform == "win32":
                os.startfile(self.download_dir)

    # =========================================================================
    # VISTA 4: AJUSTES
    # =========================================================================
    def _create_settings_view(self) -> ctk.CTkFrame:
        view = ctk.CTkFrame(self.main_container, fg_color="transparent")
        view.grid_rowconfigure(1, weight=1)
        view.grid_columnconfigure(0, weight=1)

        header = ctk.CTkFrame(view, fg_color="transparent")
        header.grid(row=0, column=0, sticky="ew", pady=(0, 16))

        ctk.CTkLabel(
            header,
            text="Ajustes de la Aplicación",
            font=ctk.CTkFont(size=20, weight="bold"),
            text_color=theme.TEXT_PRIMARY
        ).pack(side="left")

        settings_scroll = ctk.CTkScrollableFrame(view, fg_color="transparent")
        settings_scroll.grid(row=1, column=0, sticky="nsew")

        # Bloque 1: Directorio de descarga
        box1 = ctk.CTkFrame(settings_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
        box1.pack(fill="x", pady=8)

        ctk.CTkLabel(
            box1,
            text="Carpeta de Descarga Predeterminada",
            font=ctk.CTkFont(size=14, weight="bold"),
            text_color=theme.TEXT_PRIMARY
        ).pack(anchor="w", padx=16, pady=(14, 6))

        row1 = ctk.CTkFrame(box1, fg_color="transparent")
        row1.pack(fill="x", padx=16, pady=(0, 14))

        self.dir_entry = ctk.CTkEntry(row1, height=36, font=ctk.CTkFont(size=12), fg_color=theme.BG_INPUT)
        self.dir_entry.insert(0, self.download_dir)
        self.dir_entry.configure(state="readonly")
        self.dir_entry.pack(side="left", fill="x", expand=True, padx=(0, 10))

        browse_btn = ctk.CTkButton(
            row1,
            text="📁 Cambiar",
            width=90,
            height=36,
            fg_color=theme.BTN_SECONDARY_BG,
            hover_color=theme.BTN_SECONDARY_HOVER,
            text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=12, weight="bold"),
            command=self._change_download_dir
        )
        browse_btn.pack(side="left")

        # Bloque 2: Diagnóstico del Sistema
        box2 = ctk.CTkFrame(settings_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
        box2.pack(fill="x", pady=8)

        ctk.CTkLabel(
            box2,
            text="Diagnóstico del Sistema & Privacidad",
            font=ctk.CTkFont(size=14, weight="bold"),
            text_color=theme.TEXT_PRIMARY
        ).pack(anchor="w", padx=16, pady=(14, 6))

        diag_text = (
            f"• Python: {sys.version.split()[0]} ({sys.platform})\n"
            f"• Conexión: Cifrada y Directa (Sin telemetría ni cookies ajenas)\n"
            f"• Motor FFmpeg: Habilitado con soporte 4K y codificación LAME MP3\n"
            f"• Ubicación del proyecto: {os.path.dirname(os.path.abspath(__file__))}"
        )
        ctk.CTkLabel(
            box2,
            text=diag_text,
            font=ctk.CTkFont(size=12),
            text_color=theme.TEXT_SECONDARY,
            justify="left"
        ).pack(anchor="w", padx=16, pady=(0, 14))

        return view

    def _change_download_dir(self):
        chosen = filedialog.askdirectory(initialdir=self.download_dir)
        if chosen:
            self.download_dir = chosen
            self.dir_entry.configure(state="normal")
            self.dir_entry.delete(0, tk.END)
            self.dir_entry.insert(0, self.download_dir)
            self.dir_entry.configure(state="readonly")


if __name__ == "__main__":
    app = SnaptubeDesktopApp()
    app.mainloop()
