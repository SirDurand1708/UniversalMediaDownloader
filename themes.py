"""
Módulo de Temas y Paletas de Color para OmniVault Desktop.
Permite personalizar la apariencia de la interfaz con distintos colores de acento.
"""

from typing import Dict, Any

THEMES: Dict[str, Dict[str, Any]] = {
    "violet": {
        "id": "violet",
        "name": "Cyberpunk Violet",
        "primary": "#8B5CF6",
        "hover": "#7C3AED",
        "badge": "#3B0764",
        "gradient": ("#8B5CF6", "#6D28D9"),
        "text_accent": "#C4B5FD",
        "emoji": "🟣"
    },
    "blue": {
        "id": "blue",
        "name": "Electric Blue",
        "primary": "#3B82F6",
        "hover": "#2563EB",
        "badge": "#1E3A8A",
        "gradient": ("#3B82F6", "#1D4ED8"),
        "text_accent": "#93C5FD",
        "emoji": "🔵"
    },
    "emerald": {
        "id": "emerald",
        "name": "Emerald Matrix",
        "primary": "#10B981",
        "hover": "#059669",
        "badge": "#064E3B",
        "gradient": ("#10B981", "#047857"),
        "text_accent": "#6EE7B7",
        "emoji": "🟢"
    },
    "orange": {
        "id": "orange",
        "name": "Sunset Orange",
        "primary": "#F97316",
        "hover": "#EA580C",
        "badge": "#7C2D12",
        "gradient": ("#F97316", "#C2410C"),
        "text_accent": "#FDBA74",
        "emoji": "🟠"
    },
    "rose": {
        "id": "rose",
        "name": "Rose Quartz",
        "primary": "#EC4899",
        "hover": "#DB2777",
        "badge": "#831843",
        "gradient": ("#EC4899", "#BE185D"),
        "text_accent": "#F9A8D4",
        "emoji": "🌸"
    }
}

DEFAULT_THEME = "violet"

def get_theme(theme_name: str) -> Dict[str, Any]:
    """Retorna la configuración de color para el tema indicado o el predeterminado."""
    return THEMES.get(theme_name.lower(), THEMES[DEFAULT_THEME])

def get_available_themes() -> list[str]:
    """Retorna la lista de identificadores de temas disponibles."""
    return list(THEMES.keys())
