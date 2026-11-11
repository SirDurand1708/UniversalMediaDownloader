"""
Punto de entrada estándar para OmniVault Desktop.
Inicia la interfaz gráfica principal con soporte para Cyberpunk Neon y control de instancia única.
"""

import sys
from single_instance import notify_running_instance
def main():
    if notify_running_instance():
        sys.exit(0)
    from app import OmniVaultApp
    try:
        app = OmniVaultApp()
        app.mainloop()
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
