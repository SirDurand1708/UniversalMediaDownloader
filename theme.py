"""
Paleta de colores y estilos: 'Cyberpunk Neon Vault'
Estética de vanguardia: carbón profundo (OLED), contrastes extremos,
acentos en esmeralda neón (#10B981) y cian eléctrico (#00E5FF).
"""

# Fondos principales (OLED Dark & Cyberpunk)
BG_MAIN = "#070A0F"          # Negro Carbón Profundo (OLED)
BG_SIDEBAR = "#0D111A"       # Pizarra oscura para barra lateral
BG_CARD = "#121824"          # Grafito azulado para tarjetas y contenedores
BG_CARD_HOVER = "#1A2233"    # Interactividad luminosa al pasar el mouse
BG_INPUT = "#0A0E17"         # Fondo para campos de texto y búsqueda
BORDER_COLOR = "#1F293D"     # Borde cian sutil
BORDER_ACTIVE = "#10B981"    # Borde activo Esmeralda Neón

# Botones y acentos Cyberpunk
BTN_PRIMARY_BG = "#10B981"   # Verde Esmeralda Neón
BTN_PRIMARY_HOVER = "#059669"# Esmeralda brillante
BTN_PRIMARY_TEXT = "#051610" # Texto oscuro de alto contraste sobre esmeralda

BTN_SECONDARY_BG = "#1A2333"
BTN_SECONDARY_HOVER = "#243047"
BTN_SECONDARY_TEXT = "#E2E8F0"

BTN_DANGER_BG = "#3E1E22"
BTN_DANGER_HOVER = "#55282D"
BTN_DANGER_TEXT = "#F87171"

# Textos
TEXT_PRIMARY = "#F8FAFC"     # Blanco Platino puro
TEXT_SECONDARY = "#94A3B8"   # Gris Humo / Slate
TEXT_MUTED = "#64748B"       # Gris atenuado
TEXT_ACCENT = "#34D399"      # Verde Esmeralda Neón suave

# Indicadores de estado
ACCENT_CYAN = "#00E5FF"      # Cian Eléctrico puro
ACCENT_CYAN_HOVER = "#00B4D8"# Cian profundo
STATUS_SUCCESS = "#10B981"   # Verde Esmeralda Neón
STATUS_WARNING = "#F59E0B"   # Ámbar neón
STATUS_ERROR = "#EF4444"     # Carmesí neón

# Radios y fuentes
CORNER_RADIUS_SM = 6
CORNER_RADIUS = 10
CORNER_RADIUS_LG = 14

# Re-exportación de temas dinámicos para una interfaz de estilos unificada
try:
    from themes import THEMES, DEFAULT_THEME, get_theme, get_available_themes
except ImportError:
    pass
