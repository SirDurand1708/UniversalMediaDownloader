"""
Universal Media Downloader & Converter
Interfaz gráfica moderna construida con CustomTkinter y yt-dlp.
"""

import os
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox
import customtkinter as ctk

from downloader import Downloader

# Configuración visual moderna
ctk.set_appearance_mode("Dark")
ctk.set_default_color_theme("blue")

class MediaDownloaderApp(ctk.CTk):
    def __init__(self):
        super().__init__()

        self.title("Universal Media Downloader & Converter")
        self.geometry("740x630")
        self.minsize(680, 580)

        # Gestor de descargas
        self.downloader = Downloader()
        self.download_thread = None

        # Carpeta predeterminada: Descargas del usuario
        default_downloads = os.path.join(os.path.expanduser("~"), "Downloads")
        self.output_dir = default_downloads if os.path.exists(default_downloads) else os.getcwd()

        self._build_ui()

    def _build_ui(self):
        # Frame Principal con espaciado
        main_frame = ctk.CTkFrame(self, corner_radius=12)
        main_frame.pack(fill="both", expand=True, padx=20, pady=20)

        # Encabezado
        title_label = ctk.CTkLabel(
            main_frame,
            text="Universal Media Downloader",
            font=ctk.CTkFont(size=24, weight="bold")
        )
        title_label.pack(pady=(18, 4))

        subtitle_label = ctk.CTkLabel(
            main_frame,
            text="Descarga y convierte videos o audios en máxima calidad (YouTube, TikTok, Instagram, Twitter y más)",
            font=ctk.CTkFont(size=12),
            text_color="gray"
        )
        subtitle_label.pack(pady=(0, 18))

        # --- SECCIÓN 1: URL ---
        url_frame = ctk.CTkFrame(main_frame, fg_color="transparent")
        url_frame.pack(fill="x", padx=25, pady=(0, 15))

        url_label = ctk.CTkLabel(
            url_frame,
            text="Enlace (URL):",
            font=ctk.CTkFont(size=14, weight="bold")
        )
        url_label.pack(anchor="w", pady=(0, 6))

        input_row = ctk.CTkFrame(url_frame, fg_color="transparent")
        input_row.pack(fill="x")

        self.url_entry = ctk.CTkEntry(
            input_row,
            placeholder_text="Pega aquí el enlace de YouTube, TikTok, Instagram, Twitter/X...",
            height=38,
            font=ctk.CTkFont(size=13)
        )
        self.url_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))

        self.paste_btn = ctk.CTkButton(
            input_row,
            text="📋 Pegar",
            width=80,
            height=38,
            command=self._paste_clipboard
        )
        self.paste_btn.pack(side="left", padx=(0, 6))

        self.clear_btn = ctk.CTkButton(
            input_row,
            text="✖",
            width=40,
            height=38,
            fg_color="#444444",
            hover_color="#555555",
            command=self._clear_url
        )
        self.clear_btn.pack(side="left")

        # --- SECCIÓN 2: FORMATO Y CALIDAD ---
        format_frame = ctk.CTkFrame(main_frame, corner_radius=10)
        format_frame.pack(fill="x", padx=25, pady=(0, 15))

        format_label = ctk.CTkLabel(
            format_frame,
            text="Formato de Conversión:",
            font=ctk.CTkFont(size=14, weight="bold")
        )
        format_label.pack(anchor="w", padx=15, pady=(12, 8))

        self.format_var = ctk.StringVar(value="mp3_320")

        radio_grid = ctk.CTkFrame(format_frame, fg_color="transparent")
        radio_grid.pack(fill="x", padx=15, pady=(0, 14))

        # Opciones de audio y video
        self.opt_mp3 = ctk.CTkRadioButton(
            radio_grid,
            text="🎵 Audio MP3 (320 kbps HD + Carátula)",
            variable=self.format_var,
            value="mp3_320",
            font=ctk.CTkFont(size=13)
        )
        self.opt_mp3.grid(row=0, column=0, sticky="w", padx=10, pady=6)

        self.opt_m4a = ctk.CTkRadioButton(
            radio_grid,
            text="🎵 Audio M4A (Original sin recompresión)",
            variable=self.format_var,
            value="m4a_best",
            font=ctk.CTkFont(size=13)
        )
        self.opt_m4a.grid(row=0, column=1, sticky="w", padx=10, pady=6)

        self.opt_mp4_max = ctk.CTkRadioButton(
            radio_grid,
            text="🎬 Video MP4 (Máxima calidad: 4K / 1080p 60fps)",
            variable=self.format_var,
            value="mp4_max",
            font=ctk.CTkFont(size=13)
        )
        self.opt_mp4_max.grid(row=1, column=0, sticky="w", padx=10, pady=6)

        self.opt_mp4_720 = ctk.CTkRadioButton(
            radio_grid,
            text="🎬 Video MP4 (720p - Ligero y Rápido)",
            variable=self.format_var,
            value="mp4_720",
            font=ctk.CTkFont(size=13)
        )
        self.opt_mp4_720.grid(row=1, column=1, sticky="w", padx=10, pady=6)

        # --- SECCIÓN 3: CARPETA DE DESTINO ---
        dest_frame = ctk.CTkFrame(main_frame, fg_color="transparent")
        dest_frame.pack(fill="x", padx=25, pady=(0, 15))

        dest_header = ctk.CTkFrame(dest_frame, fg_color="transparent")
        dest_header.pack(fill="x")

        dest_label = ctk.CTkLabel(
            dest_header,
            text="Guardar en:",
            font=ctk.CTkFont(size=14, weight="bold")
        )
        dest_label.pack(side="left")

        dest_row = ctk.CTkFrame(dest_frame, fg_color="transparent")
        dest_row.pack(fill="x", pady=(6, 0))

        self.dest_entry = ctk.CTkEntry(
            dest_row,
            height=34,
            font=ctk.CTkFont(size=12)
        )
        self.dest_entry.insert(0, self.output_dir)
        self.dest_entry.configure(state="readonly")
        self.dest_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))

        self.browse_btn = ctk.CTkButton(
            dest_row,
            text="📁 Cambiar",
            width=100,
            height=34,
            command=self._choose_directory
        )
        self.browse_btn.pack(side="left")

        # --- SECCIÓN 4: PROGRESO Y ESTADO ---
        progress_box = ctk.CTkFrame(main_frame, corner_radius=10)
        progress_box.pack(fill="x", padx=25, pady=(0, 15))

        self.status_label = ctk.CTkLabel(
            progress_box,
            text="Listo para descargar",
            font=ctk.CTkFont(size=13, weight="bold"),
            text_color="#3B8ED0"
        )
        self.status_label.pack(anchor="w", padx=15, pady=(10, 4))

        self.progress_bar = ctk.CTkProgressBar(progress_box, height=12)
        self.progress_bar.pack(fill="x", padx=15, pady=(2, 6))
        self.progress_bar.set(0.0)

        self.detail_label = ctk.CTkLabel(
            progress_box,
            text="",
            font=ctk.CTkFont(size=11),
            text_color="gray"
        )
        self.detail_label.pack(anchor="w", padx=15, pady=(0, 8))

        # --- SECCIÓN 5: BOTONES DE ACCIÓN ---
        action_row = ctk.CTkFrame(main_frame, fg_color="transparent")
        action_row.pack(fill="x", padx=25, pady=(5, 10))

        self.download_btn = ctk.CTkButton(
            action_row,
            text="⬇ DESCARGAR AHORA",
            height=44,
            font=ctk.CTkFont(size=15, weight="bold"),
            fg_color="#1f6aa5",
            hover_color="#144870",
            command=self._start_download
        )
        self.download_btn.pack(side="left", fill="x", expand=True, padx=(0, 10))

        self.open_folder_btn = ctk.CTkButton(
            action_row,
            text="📂 Abrir Carpeta",
            height=44,
            width=130,
            fg_color="#333333",
            hover_color="#444444",
            command=self._open_output_folder
        )
        self.open_folder_btn.pack(side="left")

    # --- FUNCIONES DE INTERFAZ ---

    def _paste_clipboard(self):
        try:
            clipboard_text = self.clipboard_get()
            self.url_entry.delete(0, tk.END)
            self.url_entry.insert(0, clipboard_text.strip())
        except Exception:
            pass

    def _clear_url(self):
        self.url_entry.delete(0, tk.END)
        self.status_label.configure(text="Listo para descargar", text_color="#3B8ED0")
        self.detail_label.configure(text="")
        self.progress_bar.set(0.0)

    def _choose_directory(self):
        chosen = filedialog.askdirectory(initialdir=self.output_dir)
        if chosen:
            self.output_dir = chosen
            self.dest_entry.configure(state="normal")
            self.dest_entry.delete(0, tk.END)
            self.dest_entry.insert(0, self.output_dir)
            self.dest_entry.configure(state="readonly")

    def _open_output_folder(self):
        if os.path.exists(self.output_dir):
            if sys.platform == "win32":
                os.startfile(self.output_dir)
            elif sys.platform == "darwin":
                import subprocess
                subprocess.Popen(["open", self.output_dir])
            else:
                import subprocess
                subprocess.Popen(["xdg-open", self.output_dir])

    def _set_ui_state(self, downloading: bool):
        if downloading:
            self.download_btn.configure(state="disabled", text="⏳ Descargando...")
            self.paste_btn.configure(state="disabled")
            self.clear_btn.configure(state="disabled")
            self.browse_btn.configure(state="disabled")
        else:
            self.download_btn.configure(state="normal", text="⬇ DESCARGAR AHORA")
            self.paste_btn.configure(state="normal")
            self.clear_btn.configure(state="normal")
            self.browse_btn.configure(state="normal")

    def _update_progress(self, percent: float, speed: str, eta: str, msg: str):
        def _apply():
            self.progress_bar.set(percent)
            self.status_label.configure(text=f"Descargando... ({int(percent * 100)}%)", text_color="#3B8ED0")
            details = []
            if speed and speed != 'N/A':
                details.append(f"Velocidad: {speed}")
            if eta and eta != 'N/A':
                details.append(f"Tiempo restante: {eta}")
            self.detail_label.configure(text=" | ".join(details))

        self.after(0, _apply)

    def _update_status(self, text: str):
        self.after(0, lambda: self.status_label.configure(text=text, text_color="#E5A93B"))

    def _start_download(self):
        url = self.url_entry.get().strip()
        if not url:
            messagebox.showwarning("Atención", "Por favor ingresa o pega una URL válida.")
            return

        fmt = self.format_var.get()
        self._set_ui_state(True)
        self.progress_bar.set(0.0)
        self.status_label.configure(text="Iniciando descarga...", text_color="#3B8ED0")
        self.detail_label.configure(text="Conectando con el servidor multimedia...")

        def _worker():
            try:
                self.downloader.download(
                    url=url,
                    format_type=fmt,
                    output_dir=self.output_dir,
                    progress_callback=self._update_progress,
                    status_callback=self._update_status
                )
                self.after(0, self._on_success)
            except Exception as e:
                err_msg = str(e)
                self.after(0, lambda: self._on_error(err_msg))

        self.download_thread = threading.Thread(target=_worker, daemon=True)
        self.download_thread.start()

    def _on_success(self):
        self._set_ui_state(False)
        self.progress_bar.set(1.0)
        self.status_label.configure(text="✔ ¡Descarga completada con éxito!", text_color="#2CC985")
        self.detail_label.configure(text=f"Archivo guardado en: {self.output_dir}")

    def _on_error(self, err_msg: str):
        self._set_ui_state(False)
        self.status_label.configure(text="✖ Ocurrió un error al descargar", text_color="#E74C3C")
        self.detail_label.configure(text=err_msg[:120] + ("..." if len(err_msg) > 120 else ""))
        messagebox.showerror("Error en la descarga", f"No se pudo completar la descarga:\n\n{err_msg}")


if __name__ == "__main__":
    app = MediaDownloaderApp()
    app.mainloop()
