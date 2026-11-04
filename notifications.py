"""
Módulo de notificaciones nativas de Windows y avisos sonoros para OmniVault Desktop.
"""

import sys
import threading
import subprocess

def send_notification(title: str, message: str):
    """
    Emite una notificación nativa en Windows y un sonido sutil de finalización.
    Se ejecuta en un hilo secundario para no retrasar la interfaz principal.
    """
    def _worker():
        # 1. Sonido sutil nativo del sistema
        if sys.platform == "win32":
            try:
                import winsound
                winsound.MessageBeep(winsound.MB_ICONASTERISK)
            except Exception:
                pass

        # 2. Notificación Toast nativa en Windows 10/11
        if sys.platform == "win32":
            try:
                # Saneamiento de comillas
                safe_title = title.replace('"', '`"').replace("'", "''")
                safe_msg = message.replace('"', '`"').replace("'", "''")

                ps_script = f"""
                [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
                $template = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02)
                $textNodes = $template.GetElementsByTagName('text')
                $textNodes.Item(0).AppendChild($template.CreateTextNode('{safe_title}')) | Out-Null
                $textNodes.Item(1).AppendChild($template.CreateTextNode('{safe_msg}')) | Out-Null
                $toast = [Windows.UI.Notifications.ToastNotification]::new($template)
                [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('OmniVault Desktop').Show($toast)
                """

                flags = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
                subprocess.run(
                    ["powershell", "-NoProfile", "-NonInteractive", "-Command", ps_script],
                    creationflags=flags,
                    timeout=4,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL
                )
            except Exception:
                pass

    threading.Thread(target=_worker, daemon=True).start()
