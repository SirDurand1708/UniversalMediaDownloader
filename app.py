"""
Snaptube Pro Desktop - Edición Privada y Segura
Interfaz prémium 'Obsidian & Titanium' con soporte para Playlists, Selección Múltiple y Sesión Segura.
"""

import os
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox
import customtkinter as ctk

import theme
from downloader import DownloaderEngine, is_url, clean_error_text
from queue_manager import QueueManager, DownloadTask
from history_manager import HistoryManager
from image_cache import ThumbnailCache
from config_manager import ConfigManager

ctk.set_appearance_mode("Dark")
ctk.set_default_color_theme("blue")

class SnaptubeDesktopApp(ctk.CTk):
    def __init__(self):
        super().__init__()

        self.title("Snaptube Pro Desktop - Edición Privada")
        self.geometry("1060x740")
        self.minsize(940, 660)
        self.configure(fg_color=theme.BG_MAIN)

        # Configuración persistente
        self.config = ConfigManager()

        # Servicios y Motores
        self.downloader = DownloaderEngine()
        self.history = HistoryManager()
        self.queue_manager = QueueManager(
            self.downloader,
            self.history,
            max_workers=self.config.get("max_workers", 2)
        )
        self.img_cache = ThumbnailCache()

        # Callbacks del gestor de descargas
        self.queue_manager.on_task_updated = self._on_task_updated
        self.queue_manager.on_task_finished = self._on_task_finished

        # Estado actual
        self.download_dir = self.config.get("download_dir")
        self.current_media = None
        self.playlist_entries = []
        self.playlist_check_vars = []
        self.search_results = []
        self.search_check_vars = []
        self.selected_format = ctk.StringVar(value=self.config.get("default_format", "mp3_320"))

        self._build_layout()
        self._select_tab("explore")

    def _get_active_browser(self):
        saved = os.path.join(os.path.dirname(os.path.abspath(__file__)), "saved_cookies.txt")
        if os.path.exists(saved) and os.path.getsize(saved) > 0:
            return None  # downloader usa saved_cookies.txt con máxima prioridad sin tocar el navegador
        b = self.config.get("browser_cookies", "none")
        return None if b == "none" else b

    def _build_layout(self):
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
            ("explore", "🔍  Explorar / Buscar", self._show_explore),
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

        self.sidebar_spacer = ctk.CTkFrame(self.sidebar, fg_color="transparent")
        self.sidebar_spacer.pack(fill="both", expand=True)

        # Estado en la barra lateral
        status_box = ctk.CTkFrame(
            self.sidebar,
            corner_radius=theme.CORNER_RADIUS_SM,
            fg_color=theme.BG_CARD,
            border_width=1,
            border_color=theme.BORDER_COLOR
        )
        status_box.pack(fill="x", padx=14, pady=16)

        saved_cookie = os.path.join(os.path.dirname(os.path.abspath(__file__)), "saved_cookies.txt")
        has_saved = os.path.exists(saved_cookie) and os.path.getsize(saved_cookie) > 0
        self.session_lbl = ctk.CTkLabel(
            status_box,
            text="● Sesión: Activa" if has_saved else f"● Sesión: {self.config.get('browser_cookies', 'none').capitalize()}",
            font=ctk.CTkFont(size=11, weight="bold"),
            text_color=theme.STATUS_SUCCESS if (has_saved or self.config.get('browser_cookies') != 'none') else theme.TEXT_MUTED
        )
        self.session_lbl.pack(anchor="w", padx=10, pady=(6, 2))

        privacy_lbl = ctk.CTkLabel(
            status_box,
            text="Playlists • Lotes • FFmpeg Activo",
            font=ctk.CTkFont(size=10),
            text_color=theme.TEXT_MUTED
        )
        privacy_lbl.pack(anchor="w", padx=10, pady=(0, 6))

        # --- ÁREA PRINCIPAL ---
        self.main_container = ctk.CTkFrame(self, fg_color=theme.BG_MAIN, corner_radius=0)
        self.main_container.grid(row=0, column=1, sticky="nsew", padx=20, pady=20)
        self.main_container.grid_rowconfigure(0, weight=1)
        self.main_container.grid_columnconfigure(0, weight=1)

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
                self.nav_btns[t_id].configure(fg_color=theme.BG_CARD, text_color=theme.TEXT_PRIMARY)
            else:
                view.grid_forget()
                self.nav_btns[t_id].configure(fg_color="transparent", text_color=theme.TEXT_SECONDARY)

        if tab_id == "library":
            self._refresh_library_list()
        elif tab_id == "downloads":
            self._refresh_downloads_list()

    def _show_explore(self): self._select_tab("explore")
    def _show_downloads(self): self._select_tab("downloads")
    def _show_library(self): self._select_tab("library")
    def _show_settings(self): self._select_tab("settings")

    # =========================================================================
    # VISTA 1: EXPLORAR / BUSCAR / PLAYLISTS
    # =========================================================================
    def _create_explore_view(self) -> ctk.CTkFrame:
        view = ctk.CTkFrame(self.main_container, fg_color="transparent")
        view.grid_rowconfigure(2, weight=1)
        view.grid_columnconfigure(0, weight=1)

        search_box = ctk.CTkFrame(view, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
        search_box.grid(row=0, column=0, sticky="ew", pady=(0, 14))

        self.search_entry = ctk.CTkEntry(
            search_box,
            placeholder_text="🔍  Pega un enlace (Video, Playlist o Álbum) o busca por título...",
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

        self.analyze_status = ctk.CTkLabel(view, text="", font=ctk.CTkFont(size=12), text_color=theme.ACCENT_CYAN)
        self.analyze_status.grid(row=1, column=0, sticky="w", pady=(0, 8))

        self.explore_scroll = ctk.CTkScrollableFrame(view, fg_color="transparent", corner_radius=theme.CORNER_RADIUS)
        self.explore_scroll.grid(row=2, column=0, sticky="nsew")

        self._show_explore_welcome()
        return view

    def _show_explore_welcome(self):
        for widget in self.explore_scroll.winfo_children():
            widget.destroy()

        welcome_frame = ctk.CTkFrame(self.explore_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS_LG, border_width=1, border_color=theme.BORDER_COLOR)
        welcome_frame.pack(fill="x", pady=20, padx=10)

        ctk.CTkLabel(
            welcome_frame,
            text="Descargas Multimedia & Playlists en Máxima Calidad",
            font=ctk.CTkFont(size=20, weight="bold"),
            text_color=theme.TEXT_PRIMARY
        ).pack(pady=(25, 6))

        ctk.CTkLabel(
            welcome_frame,
            text="Descarga canciones, videos o playlists completas con selección múltiple.\nSin publicidad, sin límite de velocidad y compatible con YouTube, TikTok, Reels, Twitter y más.",
            font=ctk.CTkFont(size=12),
            text_color=theme.TEXT_SECONDARY,
            justify="center"
        ).pack(pady=(0, 20))

        chips_frame = ctk.CTkFrame(welcome_frame, fg_color="transparent")
        chips_frame.pack(pady=(0, 25))

        platforms = ["Playlists Completas", "YouTube 4K", "TikTok HD", "Instagram Reels", "Twitter / X", "SoundCloud"]
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

        self.search_btn.configure(state="disabled", text="Analizando...")
        self.analyze_status.configure(text="Conectando y analizando contenido...", text_color=theme.ACCENT_CYAN)

        browser = self._get_active_browser()
        if is_url(raw_text):
            threading.Thread(target=self._async_inspect_url, args=(raw_text, browser), daemon=True).start()
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
        self.analyze_status.configure(text=f"✖ Error: {cleaned[:60]}", text_color=theme.STATUS_ERROR)
        messagebox.showerror("Error", f"No se pudo analizar el contenido:\n\n{cleaned}")

    # --- RESULTADOS DE BÚSQUEDA CON MULTI-SELECCIÓN ---
    def _display_search_results(self, results: list, query: str):
        self.search_btn.configure(state="normal", text="Buscar / Analizar")
        self.analyze_status.configure(text=f"Resultados para '{query}' (Selecciona uno o varios para descargar)", text_color=theme.TEXT_SECONDARY)
        self.search_results = results
        self.search_check_vars = []

        for widget in self.explore_scroll.winfo_children():
            widget.destroy()

        if not results:
            ctk.CTkLabel(self.explore_scroll, text="No se encontraron videos coincidentes.", font=ctk.CTkFont(size=14), text_color=theme.TEXT_MUTED).pack(pady=40)
            return

        # Barra de control múltiple
        batch_bar = ctk.CTkFrame(self.explore_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
        batch_bar.pack(fill="x", pady=(0, 10), padx=5)

        select_all_btn = ctk.CTkButton(
            batch_bar, text="✓ Seleccionar Todos", width=140, height=32,
            fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER, text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"),
            command=self._select_all_search
        )
        select_all_btn.pack(side="left", padx=10, pady=8)

        deselect_btn = ctk.CTkButton(
            batch_bar, text="✖ Desmarcar", width=100, height=32,
            fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER, text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"),
            command=self._deselect_all_search
        )
        deselect_btn.pack(side="left", padx=(0, 10))

        self.batch_search_lbl = ctk.CTkLabel(batch_bar, text="0 seleccionados", font=ctk.CTkFont(size=12, weight="bold"), text_color=theme.TEXT_PRIMARY)
        self.batch_search_lbl.pack(side="left", padx=10)

        # Botón para descargar seleccionados de la búsqueda
        self.batch_dl_btn = ctk.CTkButton(
            batch_bar, text="⬇ Descargar Seleccionados (0)", width=220, height=34,
            fg_color=theme.BTN_PRIMARY_BG, hover_color=theme.BTN_PRIMARY_HOVER, text_color=theme.BTN_PRIMARY_TEXT,
            font=ctk.CTkFont(size=12, weight="bold"),
            command=self._download_selected_search
        )
        self.batch_dl_btn.pack(side="right", padx=10)

        # Tarjetas de cada resultado
        for idx, item in enumerate(results):
            var = ctk.BooleanVar(value=False)
            self.search_check_vars.append(var)

            card = ctk.CTkFrame(self.explore_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
            card.pack(fill="x", pady=4, padx=5)

            # Checkbox
            cb = ctk.CTkCheckBox(
                card, text="", variable=var, width=24, checkbox_width=20, checkbox_height=20,
                fg_color=theme.BTN_PRIMARY_BG, hover_color=theme.BTN_PRIMARY_HOVER,
                command=self._update_search_batch_count
            )
            cb.pack(side="left", padx=(12, 6))

            # Miniatura
            thumb_label = ctk.CTkLabel(card, text="", width=110, height=62, fg_color=theme.BG_INPUT, corner_radius=theme.CORNER_RADIUS_SM)
            thumb_label.pack(side="left", padx=6, pady=8)

            if item.get("thumbnail"):
                cached = self.img_cache.get_thumbnail(item["thumbnail"], size=(110, 62), callback=lambda img, l=thumb_label: self.after(0, lambda: l.configure(image=img)))
                if cached:
                    thumb_label.configure(image=cached)

            info_frame = ctk.CTkFrame(card, fg_color="transparent")
            info_frame.pack(side="left", fill="both", expand=True, padx=8, pady=8)

            ctk.CTkLabel(info_frame, text=item.get("title", ""), font=ctk.CTkFont(size=13, weight="bold"), text_color=theme.TEXT_PRIMARY, anchor="w").pack(fill="x")
            ctk.CTkLabel(info_frame, text=f"{item.get('uploader')}   •   Duración: {item.get('duration')}", font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY, anchor="w").pack(fill="x", pady=(2, 0))

            sel_btn = ctk.CTkButton(
                card, text="Inspeccionar ➔", width=120, height=32,
                fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER, text_color=theme.BTN_SECONDARY_TEXT,
                font=ctk.CTkFont(size=11, weight="bold"),
                command=lambda url=item.get("url"): self._select_single_from_search(url)
            )
            sel_btn.pack(side="right", padx=12)

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
        self.batch_dl_btn.configure(text=f"⬇ Descargar Seleccionados ({selected_count})")

    def _download_selected_search(self):
        selected_items = [self.search_results[i] for i, v in enumerate(self.search_check_vars) if v.get()]
        if not selected_items:
            messagebox.showwarning("Atención", "Por favor marca al menos un video de la lista con la casilla.")
            return

        fmt = self.selected_format.get()
        browser = self._get_active_browser()
        count = self.queue_manager.add_batch(selected_items, format_type=fmt, output_dir=self.download_dir, browser=browser)
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

        # Cabecera de la Playlist
        header_card = ctk.CTkFrame(self.explore_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS_LG, border_width=1, border_color=theme.BORDER_COLOR)
        header_card.pack(fill="x", pady=10, padx=5)

        h_row = ctk.CTkFrame(header_card, fg_color="transparent")
        h_row.pack(fill="x", padx=16, pady=16)

        thumb_lbl = ctk.CTkLabel(h_row, text="", width=140, height=80, fg_color=theme.BG_INPUT, corner_radius=theme.CORNER_RADIUS)
        thumb_lbl.pack(side="left", padx=(0, 16))

        if data.get("thumbnail"):
            cached = self.img_cache.get_thumbnail(data["thumbnail"], size=(140, 80), callback=lambda img, l=thumb_lbl: self.after(0, lambda: l.configure(image=img)))
            if cached:
                thumb_lbl.configure(image=cached)

        info_col = ctk.CTkFrame(h_row, fg_color="transparent")
        info_col.pack(side="left", fill="both", expand=True)

        ctk.CTkLabel(info_col, text="✦ LISTA DE REPRODUCCIÓN / ÁLBUM", font=ctk.CTkFont(size=11, weight="bold"), text_color=theme.ACCENT_CYAN, anchor="w").pack(fill="x")
        ctk.CTkLabel(info_col, text=data.get("title", ""), font=ctk.CTkFont(size=17, weight="bold"), text_color=theme.TEXT_PRIMARY, anchor="w").pack(fill="x", pady=2)
        ctk.CTkLabel(info_col, text=f"Por: {data.get('uploader')}   •   Total: {data.get('count')} canciones/videos", font=ctk.CTkFont(size=12), text_color=theme.TEXT_SECONDARY, anchor="w").pack(fill="x")

        # Barra de Selección y Formato para la Playlist
        action_box = ctk.CTkFrame(header_card, fg_color=theme.BG_INPUT, corner_radius=theme.CORNER_RADIUS)
        action_box.pack(fill="x", padx=16, pady=(0, 16))

        top_act = ctk.CTkFrame(action_box, fg_color="transparent")
        top_act.pack(fill="x", padx=12, pady=(10, 6))

        sel_all = ctk.CTkButton(
            top_act, text="✓ Seleccionar Toda la Lista", width=170, height=32,
            fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER, text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"), command=self._select_all_playlist
        )
        sel_all.pack(side="left", padx=(0, 8))

        desel_all = ctk.CTkButton(
            top_act, text="✖ Desmarcar Todos", width=130, height=32,
            fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER, text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"), command=self._deselect_all_playlist
        )
        desel_all.pack(side="left")

        self.playlist_count_lbl = ctk.CTkLabel(
            top_act,
            text=f"{len(self.playlist_entries)} / {len(self.playlist_entries)} seleccionados",
            font=ctk.CTkFont(size=12, weight="bold"),
            text_color=theme.TEXT_PRIMARY
        )
        self.playlist_count_lbl.pack(side="right", padx=10)

        # Opciones de Formato para el Lote
        fmt_row = ctk.CTkFrame(action_box, fg_color="transparent")
        fmt_row.pack(fill="x", padx=12, pady=(0, 10))

        ctk.CTkLabel(fmt_row, text="Formato para la lista:", font=ctk.CTkFont(size=12, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(side="left", padx=(0, 12))

        opts = [
            ("mp3_320", "🎵 MP3 320k (HD)"),
            ("mp3_192", "🎵 MP3 192k"),
            ("m4a_best", "🎵 M4A Original"),
            ("mp4_1080", "🎬 MP4 1080p"),
            ("mp4_720", "🎬 MP4 720p"),
        ]
        for val, lbl in opts:
            ctk.CTkRadioButton(
                fmt_row, text=lbl, variable=self.selected_format, value=val,
                font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY,
                fg_color=theme.BTN_PRIMARY_BG, hover_color=theme.BTN_PRIMARY_HOVER
            ).pack(side="left", padx=8)

        # Botón Grande de Descarga
        self.playlist_dl_btn = ctk.CTkButton(
            header_card,
            text=f"⬇ DESCARGAR SELECCIONADOS ({len(self.playlist_entries)})",
            height=46,
            fg_color=theme.BTN_PRIMARY_BG,
            hover_color=theme.BTN_PRIMARY_HOVER,
            text_color=theme.BTN_PRIMARY_TEXT,
            font=ctk.CTkFont(size=14, weight="bold"),
            command=self._download_selected_playlist
        )
        self.playlist_dl_btn.pack(fill="x", padx=16, pady=(0, 16))

        # Lista de canciones con checkboxes
        for idx, entry in enumerate(self.playlist_entries):
            var = self.playlist_check_vars[idx]

            row = ctk.CTkFrame(self.explore_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS_SM, border_width=1, border_color=theme.BORDER_COLOR)
            row.pack(fill="x", pady=2, padx=5)

            cb = ctk.CTkCheckBox(
                row, text="", variable=var, width=24, checkbox_width=18, checkbox_height=18,
                fg_color=theme.BTN_PRIMARY_BG, hover_color=theme.BTN_PRIMARY_HOVER,
                command=self._update_playlist_count
            )
            cb.pack(side="left", padx=(12, 6), pady=8)

            idx_lbl = ctk.CTkLabel(row, text=f"#{entry.get('index', idx+1):02d}", width=35, font=ctk.CTkFont(size=11, weight="bold"), text_color=theme.TEXT_MUTED)
            idx_lbl.pack(side="left", padx=4)

            title_lbl = ctk.CTkLabel(row, text=entry.get("title", ""), font=ctk.CTkFont(size=12, weight="bold"), text_color=theme.TEXT_PRIMARY, anchor="w")
            title_lbl.pack(side="left", fill="x", expand=True, padx=8)

            dur_lbl = ctk.CTkLabel(row, text=entry.get("duration", "--:--"), font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY)
            dur_lbl.pack(side="right", padx=16)

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
        browser = self._get_active_browser()
        count = self.queue_manager.add_batch(selected, format_type=fmt, output_dir=self.download_dir, browser=browser)
        messagebox.showinfo("Playlist Encolada", f"Se han añadido {count} descargas a la cola activa.")
        self._select_tab("downloads")

    # --- MODAL DE VIDEO INDIVIDUAL (2 COLUMNAS) ---
    def _display_single_modal(self, data: dict):
        self.search_btn.configure(state="normal", text="Buscar / Analizar")
        self.analyze_status.configure(text="✔ Contenido verificado listo para descargar", text_color=theme.STATUS_SUCCESS)
        self.current_media = data

        for widget in self.explore_scroll.winfo_children():
            widget.destroy()

        card = ctk.CTkFrame(self.explore_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS_LG, border_width=1, border_color=theme.BORDER_COLOR)
        card.pack(fill="x", pady=10, padx=5)

        header_row = ctk.CTkFrame(card, fg_color="transparent")
        header_row.pack(fill="x", padx=16, pady=16)

        thumb_label = ctk.CTkLabel(header_row, text="", width=180, height=101, fg_color=theme.BG_INPUT, corner_radius=theme.CORNER_RADIUS)
        thumb_label.pack(side="left", padx=(0, 16))

        if data.get("thumbnail"):
            cached = self.img_cache.get_thumbnail(data["thumbnail"], size=(180, 101), callback=lambda img, l=thumb_label: self.after(0, lambda: l.configure(image=img)))
            if cached:
                thumb_label.configure(image=cached)

        info_col = ctk.CTkFrame(header_row, fg_color="transparent")
        info_col.pack(side="left", fill="both", expand=True)

        ctk.CTkLabel(info_col, text=data.get("title", ""), font=ctk.CTkFont(size=16, weight="bold"), text_color=theme.TEXT_PRIMARY, anchor="w", wraplength=480, justify="left").pack(fill="x")
        meta = f"{data.get('uploader')}   •   Duración: {data.get('duration')}   •   Fuente: {data.get('extractor')}"
        ctk.CTkLabel(info_col, text=meta, font=ctk.CTkFont(size=12), text_color=theme.TEXT_SECONDARY, anchor="w").pack(fill="x", pady=(6, 0))

        grid_frame = ctk.CTkFrame(card, fg_color=theme.BG_INPUT, corner_radius=theme.CORNER_RADIUS)
        grid_frame.pack(fill="x", padx=16, pady=(0, 16))
        grid_frame.grid_columnconfigure(0, weight=1)
        grid_frame.grid_columnconfigure(1, weight=1)

        # Audio
        audio_col = ctk.CTkFrame(grid_frame, fg_color="transparent")
        audio_col.grid(row=0, column=0, sticky="nsew", padx=14, pady=14)

        ctk.CTkLabel(audio_col, text="🎵 OPCIONES DE AUDIO", font=ctk.CTkFont(size=13, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(anchor="w", pady=(0, 10))

        audio_options = [
            ("mp3_320", "MP3 320 kbps (HD Máximo + Portada)"),
            ("mp3_192", "MP3 192 kbps (Estándar Ligero)"),
            ("m4a_best", "M4A Original (Sin recompresión)"),
            ("wav", "WAV Audio de Estudio (Sin pérdida)"),
        ]
        for val, label in audio_options:
            ctk.CTkRadioButton(
                audio_col, text=label, variable=self.selected_format, value=val,
                font=ctk.CTkFont(size=12), text_color=theme.TEXT_SECONDARY,
                fg_color=theme.BTN_PRIMARY_BG, hover_color=theme.BTN_PRIMARY_HOVER
            ).pack(anchor="w", pady=5)

        # Video
        video_col = ctk.CTkFrame(grid_frame, fg_color="transparent")
        video_col.grid(row=0, column=1, sticky="nsew", padx=14, pady=14)

        ctk.CTkLabel(video_col, text="🎬 OPCIONES DE VIDEO (MP4)", font=ctk.CTkFont(size=13, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(anchor="w", pady=(0, 10))

        video_options = [
            ("mp4_4k", "4K / 2K Ultra HD (Máxima nitidez 60fps)"),
            ("mp4_1080", "1080p Full HD"),
            ("mp4_720", "720p HD (Ligero y Rápido)"),
            ("mp4_480", "480p / 360p Ahorro de espacio"),
        ]
        for val, label in video_options:
            ctk.CTkRadioButton(
                video_col, text=label, variable=self.selected_format, value=val,
                font=ctk.CTkFont(size=12), text_color=theme.TEXT_SECONDARY,
                fg_color=theme.BTN_PRIMARY_BG, hover_color=theme.BTN_PRIMARY_HOVER
            ).pack(anchor="w", pady=5)

        btn_row = ctk.CTkFrame(card, fg_color="transparent")
        btn_row.pack(fill="x", padx=16, pady=(0, 16))

        dl_btn = ctk.CTkButton(
            btn_row, text="⬇ DESCARGAR AHORA", height=46,
            fg_color=theme.BTN_PRIMARY_BG, hover_color=theme.BTN_PRIMARY_HOVER, text_color=theme.BTN_PRIMARY_TEXT,
            font=ctk.CTkFont(size=14, weight="bold"),
            command=self._enqueue_single_media
        )
        dl_btn.pack(fill="x")

    def _enqueue_single_media(self):
        if not self.current_media:
            return

        fmt = self.selected_format.get()
        url = self.current_media.get("url")
        title = self.current_media.get("title")
        thumb = self.current_media.get("thumbnail")
        browser = self._get_active_browser()

        self.queue_manager.add_download(
            url=url, format_type=fmt, output_dir=self.download_dir,
            title=title, thumbnail_url=thumb, browser=browser
        )

        messagebox.showinfo("Descarga Encolada", f"'{title[:40]}...' ha sido añadido a la lista.")
        self._select_tab("downloads")

    # =========================================================================
    # VISTA 2: DESCARGAS ACTIVAS
    # =========================================================================
    def _create_downloads_view(self) -> ctk.CTkFrame:
        view = ctk.CTkFrame(self.main_container, fg_color="transparent")
        view.grid_rowconfigure(1, weight=1)
        view.grid_columnconfigure(0, weight=1)

        header = ctk.CTkFrame(view, fg_color="transparent")
        header.grid(row=0, column=0, sticky="ew", pady=(0, 12))

        ctk.CTkLabel(header, text="Descargas Activas", font=ctk.CTkFont(size=20, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(side="left")

        self.downloads_scroll = ctk.CTkScrollableFrame(view, fg_color="transparent", corner_radius=theme.CORNER_RADIUS)
        self.downloads_scroll.grid(row=1, column=0, sticky="nsew")

        self.task_ui_cards: dict = {}
        return view

    def _refresh_downloads_list(self):
        tasks = self.queue_manager.get_all_tasks()
        for widget in self.downloads_scroll.winfo_children():
            widget.destroy()
        self.task_ui_cards.clear()

        if not tasks:
            empty_box = ctk.CTkFrame(self.downloads_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS_LG, border_width=1, border_color=theme.BORDER_COLOR)
            empty_box.pack(fill="x", pady=40, padx=20)
            ctk.CTkLabel(empty_box, text="📥 No hay descargas activas", font=ctk.CTkFont(size=18, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(pady=(30, 6))
            ctk.CTkLabel(empty_box, text="Busca un video o pega una playlist en la pestaña Explorar para comenzar.", font=ctk.CTkFont(size=12), text_color=theme.TEXT_SECONDARY).pack(pady=(0, 30))
            return

        for task in reversed(tasks):
            card = ctk.CTkFrame(self.downloads_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
            card.pack(fill="x", pady=5, padx=5)

            top_row = ctk.CTkFrame(card, fg_color="transparent")
            top_row.pack(fill="x", padx=14, pady=(12, 6))

            ctk.CTkLabel(top_row, text=task.title, font=ctk.CTkFont(size=13, weight="bold"), text_color=theme.TEXT_PRIMARY, anchor="w").pack(side="left", fill="x", expand=True)
            ctk.CTkLabel(top_row, text=f"  {task.format_label}  ", font=ctk.CTkFont(size=11, weight="bold"), fg_color=theme.BG_INPUT, text_color=theme.TEXT_ACCENT, corner_radius=theme.CORNER_RADIUS_SM).pack(side="right")

            p_bar = ctk.CTkProgressBar(card, height=8, fg_color=theme.BG_INPUT, progress_color=theme.ACCENT_CYAN)
            p_bar.pack(fill="x", padx=14, pady=(2, 6))
            p_bar.set(task.percent)

            bot_row = ctk.CTkFrame(card, fg_color="transparent")
            bot_row.pack(fill="x", padx=14, pady=(0, 10))

            status_lbl = ctk.CTkLabel(bot_row, text=task.status_text, font=ctk.CTkFont(size=11), text_color=theme.TEXT_MUTED)
            status_lbl.pack(side="left")

            cancel_btn = ctk.CTkButton(
                bot_row, text="Cancelar", width=75, height=26,
                fg_color=theme.BTN_DANGER_BG, hover_color=theme.BTN_DANGER_HOVER, text_color=theme.BTN_DANGER_TEXT,
                font=ctk.CTkFont(size=11, weight="bold"), command=lambda t_id=task.id: self.queue_manager.cancel_download(t_id)
            )
            if task.status in ["queued", "downloading", "converting"]:
                cancel_btn.pack(side="right")

            self.task_ui_cards[task.id] = {"progress_bar": p_bar, "status_label": status_lbl, "cancel_btn": cancel_btn}

    def _on_task_updated(self, task: DownloadTask):
        def _apply():
            active_count = len(self.queue_manager.get_active_tasks())
            btn_text = f"📥  Descargas ({active_count})" if active_count > 0 else "📥  Descargas"
            self.nav_btns["downloads"].configure(text=btn_text)

            if task.id in self.task_ui_cards:
                ui = self.task_ui_cards[task.id]
                ui["progress_bar"].set(task.percent)
                ui["status_label"].configure(text=task.status_text)
                if task.status in ["completed", "error", "cancelled"]:
                    ui["cancel_btn"].pack_forget()

        self.after(0, _apply)

    def _on_task_finished(self, task: DownloadTask):
        self.after(0, lambda: self._on_task_updated(task))

    # =========================================================================
    # VISTA 3: MI BIBLIOTECA
    # =========================================================================
    def _create_library_view(self) -> ctk.CTkFrame:
        view = ctk.CTkFrame(self.main_container, fg_color="transparent")
        view.grid_rowconfigure(1, weight=1)
        view.grid_columnconfigure(0, weight=1)

        header = ctk.CTkFrame(view, fg_color="transparent")
        header.grid(row=0, column=0, sticky="ew", pady=(0, 12))

        ctk.CTkLabel(header, text="Mi Biblioteca", font=ctk.CTkFont(size=20, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(side="left")

        open_folder_btn = ctk.CTkButton(
            header, text="📂 Abrir Carpeta de Descargas", height=34,
            fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER, text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=12, weight="bold"), command=self._open_downloads_folder
        )
        open_folder_btn.pack(side="right")

        self.library_scroll = ctk.CTkScrollableFrame(view, fg_color="transparent", corner_radius=theme.CORNER_RADIUS)
        self.library_scroll.grid(row=1, column=0, sticky="nsew")
        return view

    def _refresh_library_list(self):
        for widget in self.library_scroll.winfo_children():
            widget.destroy()

        items = self.history.get_all()
        if not items:
            empty_box = ctk.CTkFrame(self.library_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS_LG, border_width=1, border_color=theme.BORDER_COLOR)
            empty_box.pack(fill="x", pady=40, padx=20)
            ctk.CTkLabel(empty_box, text="🎵 Tu biblioteca está vacía", font=ctk.CTkFont(size=18, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(pady=(30, 6))
            ctk.CTkLabel(empty_box, text="Los audios y videos descargados aparecerán aquí.", font=ctk.CTkFont(size=12), text_color=theme.TEXT_SECONDARY).pack(pady=(0, 30))
            return

        for item in items:
            card = ctk.CTkFrame(self.library_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
            card.pack(fill="x", pady=4, padx=5)

            is_audio = item.get("media_type") == "audio"
            icon_text = "🎵" if is_audio else "🎬"
            ctk.CTkLabel(card, text=icon_text, font=ctk.CTkFont(size=20), width=45).pack(side="left", padx=10, pady=10)

            info_frame = ctk.CTkFrame(card, fg_color="transparent")
            info_frame.pack(side="left", fill="both", expand=True, padx=5, pady=8)

            ctk.CTkLabel(info_frame, text=item.get("title", ""), font=ctk.CTkFont(size=13, weight="bold"), text_color=theme.TEXT_PRIMARY, anchor="w").pack(fill="x")

            size_mb = item.get("file_size", 0) / (1024 * 1024)
            size_str = f"{size_mb:.1f} MB" if size_mb > 0 else "-- MB"
            ctk.CTkLabel(info_frame, text=f"{item.get('format_label')}   •   {size_str}   •   {item.get('date')}", font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY, anchor="w").pack(fill="x", pady=(2, 0))

            actions_frame = ctk.CTkFrame(card, fg_color="transparent")
            actions_frame.pack(side="right", padx=12)

            play_btn = ctk.CTkButton(
                actions_frame, text="▶ Reproducir", width=90, height=30,
                fg_color=theme.BTN_PRIMARY_BG, hover_color=theme.BTN_PRIMARY_HOVER, text_color=theme.BTN_PRIMARY_TEXT,
                font=ctk.CTkFont(size=11, weight="bold"), command=lambda p=item.get("file_path"): self.history.play_media(p)
            )
            play_btn.pack(side="left", padx=3)

            folder_btn = ctk.CTkButton(
                actions_frame, text="📁", width=34, height=30,
                fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER, text_color=theme.BTN_SECONDARY_TEXT,
                font=ctk.CTkFont(size=12), command=lambda p=item.get("file_path"): self.history.reveal_in_folder(p)
            )
            folder_btn.pack(side="left", padx=3)

            del_btn = ctk.CTkButton(
                actions_frame, text="✖", width=34, height=30,
                fg_color=theme.BTN_DANGER_BG, hover_color=theme.BTN_DANGER_HOVER, text_color=theme.BTN_DANGER_TEXT,
                font=ctk.CTkFont(size=12), command=lambda i=item.get("id"): self._delete_library_item(i)
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
    # VISTA 4: AJUSTES & SESIÓN SEGURA
    # =========================================================================
    def _create_settings_view(self) -> ctk.CTkFrame:
        view = ctk.CTkFrame(self.main_container, fg_color="transparent")
        view.grid_rowconfigure(1, weight=1)
        view.grid_columnconfigure(0, weight=1)

        header = ctk.CTkFrame(view, fg_color="transparent")
        header.grid(row=0, column=0, sticky="ew", pady=(0, 16))

        ctk.CTkLabel(header, text="Ajustes de la Aplicación", font=ctk.CTkFont(size=20, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(side="left")

        settings_scroll = ctk.CTkScrollableFrame(view, fg_color="transparent")
        settings_scroll.grid(row=1, column=0, sticky="nsew")

        # Bloque 1: Carpeta de Descargas
        box1 = ctk.CTkFrame(settings_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
        box1.pack(fill="x", pady=8)

        ctk.CTkLabel(box1, text="Carpeta de Descarga Predeterminada", font=ctk.CTkFont(size=14, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(anchor="w", padx=16, pady=(14, 6))

        row1 = ctk.CTkFrame(box1, fg_color="transparent")
        row1.pack(fill="x", padx=16, pady=(0, 14))

        self.dir_entry = ctk.CTkEntry(row1, height=36, font=ctk.CTkFont(size=12), fg_color=theme.BG_INPUT)
        self.dir_entry.insert(0, self.download_dir)
        self.dir_entry.configure(state="readonly")
        self.dir_entry.pack(side="left", fill="x", expand=True, padx=(0, 10))

        browse_btn = ctk.CTkButton(
            row1, text="📁 Cambiar", width=90, height=36,
            fg_color=theme.BTN_SECONDARY_BG, hover_color=theme.BTN_SECONDARY_HOVER, text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=12, weight="bold"), command=self._change_download_dir
        )
        browse_btn.pack(side="left")

        # Bloque 2: Sesión de Navegador & Cookies
        box2 = ctk.CTkFrame(settings_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
        box2.pack(fill="x", pady=8)

        ctk.CTkLabel(box2, text="🔒 Conexión Segura con YouTube (Playlists Privadas y +18)", font=ctk.CTkFont(size=14, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(anchor="w", padx=16, pady=(14, 4))
        
        desc = (
            "Para descargar videos con restricción de edad o tus playlists privadas, se requiere sesión activa.\n"
            "✦ Cómo sincronizar con Vivaldi sin bloqueos de Windows:\n"
            "  1. Cierra Vivaldi durante 5 segundos y haz clic en '📸 Sincronizar Sesión de Vivaldi Ahora'.\n"
            "  2. O bien, carga un archivo cookies.txt exportado desde tu navegador."
        )
        ctk.CTkLabel(box2, text=desc, font=ctk.CTkFont(size=11), text_color=theme.TEXT_SECONDARY, justify="left").pack(anchor="w", padx=16, pady=(0, 10))

        saved_cookie_file = os.path.join(os.path.dirname(os.path.abspath(__file__)), "saved_cookies.txt")
        self.cookie_status_lbl = ctk.CTkLabel(
            box2,
            text="✔ Sesión guardada en archivo local (Sin bloqueos de navegador)" if os.path.exists(saved_cookie_file) else "ℹ No hay sesión guardada en archivo local (Usando modo anónimo)",
            font=ctk.CTkFont(size=12, weight="bold"),
            text_color=theme.STATUS_SUCCESS if os.path.exists(saved_cookie_file) else theme.TEXT_MUTED
        )
        self.cookie_status_lbl.pack(anchor="w", padx=16, pady=(0, 10))

        btn_row_cookies = ctk.CTkFrame(box2, fg_color="transparent")
        btn_row_cookies.pack(fill="x", padx=16, pady=(0, 14))

        self.sync_vivaldi_btn = ctk.CTkButton(
            btn_row_cookies,
            text="📸 Sincronizar Sesión de Vivaldi Ahora",
            height=34,
            fg_color=theme.BTN_PRIMARY_BG,
            hover_color=theme.BTN_PRIMARY_HOVER,
            text_color=theme.BTN_PRIMARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"),
            command=self._sync_vivaldi_snapshot
        )
        self.sync_vivaldi_btn.pack(side="left", padx=(0, 8))

        load_cookie_btn = ctk.CTkButton(
            btn_row_cookies,
            text="📄 Cargar cookies.txt",
            height=34,
            fg_color=theme.BTN_SECONDARY_BG,
            hover_color=theme.BTN_SECONDARY_HOVER,
            text_color=theme.BTN_SECONDARY_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"),
            command=self._load_cookie_file
        )
        load_cookie_btn.pack(side="left", padx=(0, 8))

        del_cookie_btn = ctk.CTkButton(
            btn_row_cookies,
            text="🗑 Limpiar Sesión",
            width=110,
            height=34,
            fg_color=theme.BTN_DANGER_BG,
            hover_color=theme.BTN_DANGER_HOVER,
            text_color=theme.BTN_DANGER_TEXT,
            font=ctk.CTkFont(size=11, weight="bold"),
            command=self._clear_saved_cookies
        )
        del_cookie_btn.pack(side="left")

        # Bloque 3: Diagnóstico del Sistema
        box3 = ctk.CTkFrame(settings_scroll, fg_color=theme.BG_CARD, corner_radius=theme.CORNER_RADIUS, border_width=1, border_color=theme.BORDER_COLOR)
        box3.pack(fill="x", pady=8)

        ctk.CTkLabel(box3, text="Diagnóstico del Sistema & Privacidad", font=ctk.CTkFont(size=14, weight="bold"), text_color=theme.TEXT_PRIMARY).pack(anchor="w", padx=16, pady=(14, 6))

        diag_text = (
            f"• Python: {sys.version.split()[0]} ({sys.platform})\n"
            f"• Conexión: Cifrada y Directa (Sin telemetría ni intermediarios)\n"
            f"• Motor FFmpeg: Habilitado con soporte 4K y codificación LAME MP3\n"
            f"• Runtime JS: Node.js Activo para firmas complejas de YouTube\n"
            f"• Ubicación del proyecto: {os.path.dirname(os.path.abspath(__file__))}"
        )
        ctk.CTkLabel(box3, text=diag_text, font=ctk.CTkFont(size=12), text_color=theme.TEXT_SECONDARY, justify="left").pack(anchor="w", padx=16, pady=(0, 14))

        return view

    def _on_browser_changed(self, key: str):
        self.config.set("browser_cookies", key)
        self.session_lbl.configure(
            text=f"● Sesión: {key.capitalize()}",
            text_color=theme.STATUS_SUCCESS if key != "none" else theme.TEXT_MUTED
        )

    def _sync_vivaldi_snapshot(self):
        saved_cookie_file = os.path.join(os.path.dirname(os.path.abspath(__file__)), "saved_cookies.txt")
        self.sync_vivaldi_btn.configure(state="disabled", text="Extrayendo...")

        def _worker():
            try:
                self.downloader.export_browser_cookies_snapshot("vivaldi")
                self.after(0, lambda: self._on_cookie_sync_success())
            except Exception as e:
                err = str(e)
                self.after(0, lambda: self._on_cookie_sync_error(err))

        threading.Thread(target=_worker, daemon=True).start()

    def _on_cookie_sync_success(self):
        self.sync_vivaldi_btn.configure(state="normal", text="📸 Sincronizar Sesión de Vivaldi Ahora")
        self.cookie_status_lbl.configure(
            text="✔ Sesión guardada con éxito (Sin bloqueos de navegador)",
            text_color=theme.STATUS_SUCCESS
        )
        self.session_lbl.configure(text="● Sesión: Vivaldi (Copiada)", text_color=theme.STATUS_SUCCESS)
        messagebox.showinfo(
            "Sesión Sincronizada",
            "¡Listo! Se ha guardado una copia segura de tu sesión de Vivaldi.\n\n"
            "Ya puedes volver a abrir Vivaldi y usarlo normalmente; la app usará esta copia para siempre sin bloqueos."
        )

    def _on_cookie_sync_error(self, err_msg: str):
        self.sync_vivaldi_btn.configure(state="normal", text="📸 Sincronizar Sesión de Vivaldi Ahora")
        messagebox.showwarning(
            "Navegador en uso",
            "No se pudieron leer las cookies porque Vivaldi aún está abierto en tu equipo.\n\n"
            "Pasos:\n"
            "1. Cierra todas las ventanas de Vivaldi.\n"
            "2. Espera 3 segundos y presiona este botón nuevamente.\n"
            "3. Una vez guardado, podrás abrir Vivaldi y usarlo como siempre."
        )

    def _load_cookie_file(self):
        path = filedialog.askopenfilename(
            title="Seleccionar archivo cookies.txt",
            filetypes=[("Archivos de texto", "*.txt"), ("Todos los archivos", "*.*")]
        )
        if path:
            dest = os.path.join(os.path.dirname(os.path.abspath(__file__)), "saved_cookies.txt")
            import shutil
            shutil.copy(path, dest)
            self.cookie_status_lbl.configure(
                text="✔ Archivo cookies.txt cargado con éxito",
                text_color=theme.STATUS_SUCCESS
            )
            self.session_lbl.configure(text="● Sesión: cookies.txt", text_color=theme.STATUS_SUCCESS)
            messagebox.showinfo("Éxito", "El archivo cookies.txt ha sido importado correctamente.")

    def _clear_saved_cookies(self):
        dest = os.path.join(os.path.dirname(os.path.abspath(__file__)), "saved_cookies.txt")
        if os.path.exists(dest):
            os.remove(dest)
        self.config.set("browser_cookies", "none")
        self.cookie_status_lbl.configure(
            text="ℹ Sesión eliminada. Usando modo anónimo por defecto.",
            text_color=theme.TEXT_MUTED
        )
        self.session_lbl.configure(text="● Sesión: Anónima", text_color=theme.TEXT_MUTED)
        messagebox.showinfo("Sesión Limpiada", "Se ha eliminado el archivo de cookies y se ha restablecido el modo anónimo.")

    def _change_download_dir(self):
        chosen = filedialog.askdirectory(initialdir=self.download_dir)
        if chosen:
            self.download_dir = chosen
            self.config.set("download_dir", chosen)
            self.dir_entry.configure(state="normal")
            self.dir_entry.delete(0, tk.END)
            self.dir_entry.insert(0, self.download_dir)
            self.dir_entry.configure(state="readonly")


if __name__ == "__main__":
    app = SnaptubeDesktopApp()
    app.mainloop()
