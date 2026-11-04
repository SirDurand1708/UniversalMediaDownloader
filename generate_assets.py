"""
Generador de Identidad Visual y Logotipo para OmniVault Desktop.
Crea iconos nítidos en múltiples resoluciones (ICO de 16px a 256px y PNG de 512px)
con estética Obsidian & Titanium, detalles facetados en violeta neón y plata titanio.
"""

import os
import math
from PIL import Image, ImageDraw, ImageFont, ImageFilter

def create_omnivault_icon(size: int = 512) -> Image.Image:
    """Crea el icono oficial de OmniVault con diseño geométrico y alta definición."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    margin = size * 0.08
    cx, cy = size / 2, size / 2
    r = (size / 2) - margin

    # 1. Resplandor exterior (Glow)
    glow_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow_img)
    glow_draw.ellipse(
        [cx - r * 0.9, cy - r * 0.9, cx + r * 0.9, cy + r * 0.9],
        fill=(139, 92, 246, 50)
    )
    glow_img = glow_img.filter(ImageFilter.GaussianBlur(radius=size * 0.08))
    img.paste(glow_img, (0, 0), glow_img)

    # 2. Base Hexagonal / Bóveda (Obsidian Dark Vault)
    # 6 vértices de un hexágono vertical redondeado
    points = []
    for i in range(6):
        angle = math.radians(60 * i - 30)
        px = cx + r * math.cos(angle)
        py = cy + r * math.sin(angle)
        points.append((px, py))

    # Sombra de elevación
    shadow = [(p[0], p[1] + size * 0.03) for p in points]
    draw.polygon(shadow, fill=(10, 11, 14, 180))

    # Cuerpo principal Obsidiana
    draw.polygon(points, fill=(22, 25, 30, 255))

    # Borde Titanio / Neon Violet
    draw.line(points + [points[0]], fill=(139, 92, 246, 220), width=max(2, int(size * 0.025)))

    # 3. Facetas Interiores (Iluminación 3D)
    top_p = points[0]
    bot_p = points[3]
    # Bisel superior izquierdo
    draw.polygon([points[5], points[0], (cx, cy)], fill=(32, 36, 44, 255))
    # Bisel superior derecho
    draw.polygon([points[0], points[1], (cx, cy)], fill=(26, 30, 36, 255))
    # Bisel inferior
    draw.polygon([points[2], points[3], (cx, cy)], fill=(16, 18, 22, 255))
    draw.polygon([points[3], points[4], (cx, cy)], fill=(18, 20, 25, 255))

    # 4. Glifo Central: La "V" de Vault + Prisma Multimedia
    # Dibujar la 'V' estilizada con gradiente violeta neón a cian
    v_top_y = cy - r * 0.45
    v_bot_y = cy + r * 0.48
    v_left_x = cx - r * 0.52
    v_right_x = cx + r * 0.52
    v_thick = r * 0.22

    # Ala izquierda
    p_left = [
        (v_left_x, v_top_y),
        (v_left_x + v_thick, v_top_y),
        (cx, v_bot_y),
        (cx - v_thick * 0.7, v_bot_y)
    ]
    draw.polygon(p_left, fill=(139, 92, 246, 255))  # Violeta neón

    # Ala derecha (con toque de degradado / cian)
    p_right = [
        (v_right_x, v_top_y),
        (v_right_x - v_thick, v_top_y),
        (cx, v_bot_y),
        (cx + v_thick * 0.7, v_bot_y)
    ]
    draw.polygon(p_right, fill=(167, 139, 250, 255))  # Violeta claro brillante

    # Vértice de unión inferior
    draw.polygon([
        (cx - v_thick * 0.5, v_bot_y - v_thick * 0.4),
        (cx + v_thick * 0.5, v_bot_y - v_thick * 0.4),
        (cx, v_bot_y + v_thick * 0.2)
    ], fill=(56, 189, 248, 255))  # Cian brillante

    # 5. Diamante / Triángulo de Reproducción Superior (Play Core)
    core_y = cy - r * 0.15
    core_size = r * 0.24
    core_points = [
        (cx - core_size * 0.7, core_y - core_size * 0.8),
        (cx + core_size * 0.9, core_y),
        (cx - core_size * 0.7, core_y + core_size * 0.8)
    ]
    draw.polygon(core_points, fill=(248, 250, 252, 255))  # Plata pura

    # Resaltado especular en el play core
    draw.polygon([
        core_points[0],
        core_points[1],
        (cx - core_size * 0.2, core_y)
    ], fill=(255, 255, 255, 255))

    return img

def create_banner_logo(icon_img: Image.Image) -> Image.Image:
    """Crea el banner horizontal para la barra lateral y bienvenida."""
    w, h = 420, 110
    banner = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(banner)

    # Pegar icono escalado
    ic_size = 78
    ic_thumb = icon_img.resize((ic_size, ic_size), Image.Resampling.LANCZOS)
    banner.paste(ic_thumb, (12, 16), ic_thumb)

    # Texto OMNIVAULT
    # Dibujar tipografía vectorial si no hay fuente externa
    tx = 104
    ty = 24
    draw.text((tx, ty), "OMNIVAULT", fill=(248, 250, 252), font_size=32)
    draw.text((tx, ty + 40), "MEDIA HUB • OBSIDIAN SUITE", fill=(139, 92, 246), font_size=12)

    return banner

def main():
    base_dir = os.path.dirname(os.path.abspath(__file__))
    assets_dir = os.path.join(base_dir, "assets")
    os.makedirs(assets_dir, exist_ok=True)

    print("[*] Generando icono maestro de 512x512...")
    icon_512 = create_omnivault_icon(512)

    png_path = os.path.join(assets_dir, "icon.png")
    icon_512.save(png_path, "PNG")
    print(f"    -> Guardado: {png_path}")

    ico_path = os.path.join(assets_dir, "icon.ico")
    icon_sizes = [(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)]
    icon_512.save(ico_path, format="ICO", sizes=icon_sizes)
    print(f"    -> Guardado: {ico_path}")

    banner_path = os.path.join(assets_dir, "logo.png")
    banner = create_banner_logo(icon_512)
    banner.save(banner_path, "PNG")
    print(f"    -> Guardado: {banner_path}")

    print("[OK] Todos los recursos graficos fueron generados exitosamente!")

if __name__ == "__main__":
    main()
