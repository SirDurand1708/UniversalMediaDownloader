"""
Módulo de Instancia Única (Single-Instance Application Lock & IPC) para OmniVault Desktop.
Garantiza que solo una instancia de OmniVault se ejecute a la vez. Si el usuario intenta
abrir la aplicación mientras ya está corriendo en segundo plano o minimizada en la bandeja,
la nueva instancia le envía una señal para restaurar su ventana al frente y se cierra de inmediato.
"""

import socket
import threading
import atexit
from typing import Callable, Optional

SINGLE_INSTANCE_PORT = 48729


def notify_running_instance(port: int = SINGLE_INSTANCE_PORT) -> bool:
    """
    Comprueba si ya existe una instancia de OmniVault ejecutándose en este equipo.
    Si existe, le envía el comando para restaurar su ventana y retorna True.
    Si no existe o no responde, retorna False.
    """
    try:
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.settimeout(0.25)
        client.connect(("127.0.0.1", port))
        client.sendall(b"RESTORE\n")
        try:
            client.recv(32)
        except Exception:
            pass
        client.close()
        return True
    except (ConnectionRefusedError, TimeoutError, OSError):
        return False


def start_single_instance_server(
    on_restore: Callable[[], None],
    port: int = SINGLE_INSTANCE_PORT
) -> Optional[socket.socket]:
    """
    Inicia el servidor local de escucha de instancia única.
    Cuando otra instancia intenta abrirse, recibe la notificación y ejecuta on_restore.
    """
    try:
        server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server_sock.bind(("127.0.0.1", port))
        server_sock.listen(5)
    except OSError:
        # En caso de que el puerto no pueda ser enlazado
        return None

    def _listen_loop():
        while True:
            try:
                conn, _ = server_sock.accept()
            except Exception:
                break
            try:
                conn.settimeout(1.5)
                data = conn.recv(1024)
                if b"RESTORE" in data:
                    try:
                        on_restore()
                    except Exception:
                        pass
                    try:
                        conn.sendall(b"OK\n")
                    except Exception:
                        pass
            except Exception:
                pass
            finally:
                try:
                    conn.close()
                except Exception:
                    pass

    t = threading.Thread(target=_listen_loop, daemon=True, name="SingleInstanceListener")
    t.start()

    def _cleanup():
        stop_single_instance_server(server_sock)

    atexit.register(_cleanup)
    return server_sock


def stop_single_instance_server(server_sock: Optional[socket.socket]):
    """Detiene y cierra el socket del servidor de instancia única."""
    if server_sock:
        try:
            server_sock.close()
        except Exception:
            pass
