"""
Punto de entrada estándar para OmniVault Desktop.
Inicia la interfaz gráfica principal con soporte completo para Obsidian & Titanium.
"""

import sys
from app import OmniVaultApp

def main():
    try:
        app = OmniVaultApp()
        app.mainloop()
    except KeyboardInterrupt:
        sys.exit(0)

if __name__ == "__main__":
    main()
