"""
Mini-Widget Flotante siempre visible (Floating Mini-HUD / Picture-in-Picture) para OmniVault Desktop.
Permite monitorear la velocidad de descarga en tiempo real, pausar/reanudar la cola,
pegar URLs para descargas ultrarrápidas y controlar la música mientras usas otras aplicaciones o juegos.
"""

import os
import sys
import tkinter as tk
import customtkinter as ctk
from typing import Optional, Callable

import theme
from downloader import is_url, normalize_url


class MiniHUD(ctk.CTkToplevel):
    def __init__(
        self,
        master,
        queue_manager,
        audio_player,
        config_manager,
        on_restore_callback: Optional[Callable[[], None]] = None
    ):
        super().__init__(master)
        self.queue_manager = queue_manager
        self.audio_player = audio_player
        self.config = config_manager
        self.on_restore_callback = on_restore_callback

        self._drag_start_x = 0
        self._drag_start_y = 0
        self._is_visible = False

        # Configuración de ventana sin marco y siempre visible al frente
        self.overrideredirect(True)
        self.attributes("-topmost", True)
        self.configure(fg_color=theme.BG_SIDEBAR)

        # Dimensiones y posición inicial (esquina inferior derecha de la pantalla)
        screen_w = self.winfo_screenwidth()
        screen_h = self.winfo_screenheight()
        width = 440
        height = 135
        pos_x = max(20, screen_w - width - 40)
        pos_y = max(20, screen_h - height - 80)
        self.geometry(f"{width}x{height}+{pos_x}+{pos_y}")

        self._build_ui()
        self.withdraw()  # Inicia oculto hasta que se invoque

        # Bucle de refresco en tiempo real
        self.after(500, self._refresh_loop)

    def _build_ui(self):
        # Marco principal contenedor con borde elegante
        self.outer_frame = ctk.CTkFrame(
            self,
            fg_color=theme.BG_CARD,
            corner_radius=theme.CORNER_RADIUS,
            border_width=1,
            border_color=theme.BORDER_COLOR
        )
        self.outer_frame.pack(fill="both", expand=True, padx=2, pady=2)

        # 1. BARRA SUPERIOR (Arrastrable con el mouse)
        header = ctk.CTkFrame(self.outer_frame, fg_color="transparent", height=32)
        header.pack(fill="x", padx=10, pady=(6, 2))

        # Drag handlers
        header.bind("<ButtonPress-1>", self._start_drag)
        header.bind("<B1-Motion>", self._on_drag)

        # Título y logotipo
        title_box = ctk.CTkFrame(header, fg_color="transparent")
        title_box.pack(side="left")
        title_box.bind("<ButtonPress-1>", self._start_drag)
        title_box.bind("<B1-Motion>", self._on_drag)

        dot = ctk.CTkLabel(title_box, text="⚡", font=ctk.CTkFont(size=13))
        dot.pack(side="left", padx=(0, 4))
        dot.bind("<ButtonPress-1>", self._start_drag)
        dot.bind("<B1-Motion>", self._on_drag)

        self.title_lbl = ctk.CTkLabel(
            title_box,
            text="OmniVault HUD",
            font=ctk.CTkFont(family="Segoe UI", size=12, weight="bold"),
            text_color=theme.TEXT_PRIMARY
        )
        self.title_lbl.pack(side="left")
        self.title_lbl.bind("<ButtonPress-1>", self._start_drag)
        self.title_lbl.bind("<B1-Motion>", self._on_drag)

        # Velocidad / Estado en vivo
        self.speed_badge = ctk.CTkLabel(
            header,
            text="✦ En reposo",
            font=ctk.CTkFont(size=11, weight="bold"),
            text_color=theme.TEXT_MUTED
        )
        self.speed_badge.pack(side="left", padx=10)
        self.speed_badge.bind("<ButtonPress-1>", self._start_drag)
        self.speed_badge.bind("<B1-Motion>", self._on_drag)

        # Controles ventana (Restaurar y Cerrar)
        ctrls = ctk.CTkFrame(header, fg_color="transparent")
        ctrls.pack(side="right")

        self.btn_pause = ctk.CTkButton(
            ctrls, text="⏸", width=26, height=24,
            fg_color="transparent", hover_color=theme.BG_CARD_HOVER,
            text_color=theme.TEXT_PRIMARY, font=ctk.CTkFont(size=11, weight="bold"),
            command=self._toggle_queue
        )
        self.btn_pause.pack(side="left", padx=2)

        btn_restore = ctk.CTkButton(
            ctrls, text="🗖", width=26, height=24,
            fg_color="transparent", hover_color=theme.BG_CARD_HOVER,
            text_color=theme.TEXT_PRIMARY, font=ctk.CTkFont(size=12, weight="bold"),
            command=self.restore_main_window
        )
        btn_restore.pack(side="left", padx=2)

        btn_close = ctk.CTkButton(
            ctrls, text="✕", width=26, height=24,
            fg_color="transparent", hover_color=theme.BTN_DANGER_BG,
            text_color=theme.TEXT_SECONDARY, font=ctk.CTkFont(size=11, weight="bold"),
            command=self.hide_hud
        )
        btn_close.pack(side="left", padx=2)

        # 2. FILA DE ESTADO / REPRODUCTOR / PROGRESO
        status_row = ctk.CTkFrame(self.outer_frame, fg_color="transparent")
        status_row.pack(fill="x", padx=10, pady=(2, 4))

        self.progress_bar = ctk.CTkProgressBar(status_row, height=6, corner_radius=3)
        self.progress_bar.set(0.0)
        self.progress_bar.pack(fill="x", pady=(2, 4))

        self.substatus_lbl = ctk.CTkLabel(
            status_row,
            text="Sin descargas activas",
            font=ctk.CTkFont(size=10),
            text_color=theme.TEXT_SECONDARY,
            anchor="w"
        )
        self.substatus_lbl.pack(fill="x")

        # 3. FILA DE DESCARGA RÁPIDA (Direct Quick-Download Bar)
        quick_row = ctk.CTkFrame(self.outer_frame, fg_color="transparent")
        quick_row.pack(fill="x", padx=10, pady=(2, 8))

        self.quick_entry = ctk.CTkEntry(
            quick_row,
            height=28,
            font=ctk.CTkFont(size=11),
            placeholder_text="Pegar enlace para descarga rápida...",
            fg_color=theme.BG_INPUT
        )
        self.quick_entry.pack(side="left", fill="x", expand=True, padx=(0, 6))
        self.quick_entry.bind("<Return>", lambda e: self._on_quick_download())

        self.btn_download = ctk.CTkButton(
            quick_row,
            text="⬇ Bajar",
            width=65,
            height=28,
            font=ctk.CTkFont(size=11, weight="bold"),
            fg_color=theme.ACCENT_CYAN,
            hover_color=theme.ACCENT_CYAN_HOVER,
            text_color="#0a0d14",
            command=self._on_quick_download
        )
        self.btn_download.pack(side="left")

    def _start_drag(self, event):
        self._drag_start_x = event.x
        self._drag_start_y = event.y

    def _on_drag(self, event):
        x = self.winfo_x() - self._drag_start_x + event.x
        y = self.winfo_y() - self._drag_start_y + event.y
        self.geometry(f"+{x}+{y}")

    def show_hud(self):
        self._is_visible = True
        self.deiconify()
        self.lift()
        self.attributes("-topmost", True)

    def hide_hud(self):
        self._is_visible = False
        self.withdraw()

    def toggle_hud(self):
        if self._is_visible:
            self.hide_hud()
        else:
            self.show_hud()

    def restore_main_window(self):
        if self.on_restore_callback:
            self.on_restore_callback()

    def _toggle_queue(self):
        is_paused = self.queue_manager.toggle_pause_queue()
        self.btn_pause.configure(text="▶" if is_paused else "⏸")

    def _on_quick_download(self):
        url = self.quick_entry.get().strip()
        if not url or not is_url(url):
            self.substatus_lbl.configure(text="⚠ Enlace inválido en portapapeles", text_color=theme.STATUS_WARNING)
            return

        fmt = self.config.get("default_format", "mp3_320")
        if fmt in ["mp3_320", "mp3_192", "m4a_best", "wav"]:
            out_dir = self.config.get_audio_dir()
        else:
            out_dir = self.config.get_video_dir()

        try:
            self.queue_manager.add_download(
                url=url,
                format_type=fmt,
                output_dir=out_dir,
                use_aria2c=bool(self.config.get("aria2c_enabled", True)),
                download_subtitles=bool(self.config.get("subtitles_enabled", False))
            )
            self.quick_entry.delete(0, tk.END)
            self.substatus_lbl.configure(text="✔ Descarga encolada con éxito", text_color=theme.STATUS_SUCCESS)
        except Exception as e:
            self.substatus_lbl.configure(text=f"✖ Error: {str(e)[:45]}", text_color=theme.STATUS_ERROR)

    def _refresh_loop(self):
        if not self._is_visible:
            self.after(800, self._refresh_loop)
            return

        try:
            active_tasks = self.queue_manager.get_active_tasks()
            all_tasks = self.queue_manager.get_all_tasks()
            queued = [t for t in all_tasks if t.status == "queued"]
            is_paused = self.queue_manager.is_paused()

            self.btn_pause.configure(text="▶" if is_paused else "⏸")

            if active_tasks:
                first = active_tasks[0]
                speed = first.speed if first.speed and first.speed != "--" else "Descargando..."
                pct = first.percent
                self.speed_badge.configure(text=f"⚡ {speed}", text_color=theme.ACCENT_CYAN)
                self.progress_bar.set(pct)

                title_clean = first.title[:38] + ("..." if len(first.title) > 38 else "")
                pending_count = len(active_tasks) + len(queued)
                self.substatus_lbl.configure(
                    text=f"[{pending_count} activos] {title_clean}",
                    text_color=theme.TEXT_PRIMARY
                )
            elif queued:
                self.speed_badge.configure(text=f"⏳ {len(queued)} en cola", text_color=theme.STATUS_WARNING)
                self.progress_bar.set(0.0)
                self.substatus_lbl.configure(text="En espera de asignación de worker...", text_color=theme.TEXT_MUTED)
            elif self.audio_player and self.audio_player.is_playing():
                track = self.audio_player.get_current_track_title() or "Reproduciendo audio"
                self.speed_badge.configure(text="🎵 Música Activa", text_color=theme.STATUS_SUCCESS)
                self.substatus_lbl.configure(text=track[:40], text_color=theme.TEXT_SECONDARY)
                self.progress_bar.set(0.0)
            else:
                self.speed_badge.configure(text="✦ En reposo", text_color=theme.TEXT_MUTED)
                self.progress_bar.set(0.0)
                if not self.quick_entry.get():
                    self.substatus_lbl.configure(text="Listo para descargar cualquier enlace", text_color=theme.TEXT_MUTED)

        except Exception as e:
            pass

        self.after(500, self._refresh_loop)
