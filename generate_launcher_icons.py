"""
Generates standard (non-adaptive) PNG launcher icons for every density
bucket Android expects. Traditional PNG icons (rather than adaptive-icon XML
layers) were chosen deliberately here: they render identically on every
Android version without needing a preview/render step to confirm the layers
composite correctly, which matters since this can't be visually verified in
this environment before shipping.
"""
from PIL import Image, ImageDraw

FOREST = (6, 16, 11, 255)
SOURCE = "app/src/main/res/drawable-nodpi/forest_shield.png"

# (folder, launcher_px_size)
DENSITIES = [
    ("mipmap-mdpi", 48),
    ("mipmap-hdpi", 72),
    ("mipmap-xhdpi", 96),
    ("mipmap-xxhdpi", 144),
    ("mipmap-xxxhdpi", 192),
]


def draw_icon(size, rounded: bool):
    scale = 4
    canvas = size * scale
    img = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    if rounded:
        draw.ellipse([0, 0, canvas - 1, canvas - 1], fill=FOREST)
    else:
        radius = canvas * 0.22
        draw.rounded_rectangle([0, 0, canvas - 1, canvas - 1], radius=radius, fill=FOREST)

    mark = Image.open(SOURCE).convert("RGBA")
    mark.thumbnail((int(canvas * 0.70), int(canvas * 0.70)), Image.LANCZOS)
    x = (canvas - mark.width) // 2
    y = (canvas - mark.height) // 2
    img.alpha_composite(mark, (x, y))

    return img.resize((size, size), Image.LANCZOS)


import os

base_dir = "app/src/main/res"
for folder, size in DENSITIES:
    out_dir = os.path.join(base_dir, folder)
    os.makedirs(out_dir, exist_ok=True)
    draw_icon(size, rounded=False).save(os.path.join(out_dir, "ic_launcher.png"))
    draw_icon(size, rounded=True).save(os.path.join(out_dir, "ic_launcher_round.png"))
    print(f"{folder}: {size}x{size} -> ic_launcher.png, ic_launcher_round.png")
