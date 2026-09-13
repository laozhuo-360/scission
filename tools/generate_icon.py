# -*- coding: utf-8 -*-
"""断流 Scission launcher icon: deep-blue night gradient + starfield + moon +
clean signal glyph with a break. Matches the main-screen PillSwitch palette.

用法：
    python tools/generate_icon.py
需要 Pillow：pip install pillow
会在 app/src/main/res/mipmap-*/ 下生成 ic_launcher.png 与 ic_launcher_fg.png。
"""
from PIL import Image, ImageDraw
import os, random

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

NIGHT_TOP = (27, 42, 94)      # #1B2A5E
NIGHT_MID = (21, 34, 80)      # #152250
NIGHT_BOT = (10, 18, 48)      # #0A1230
ACCENT = (47, 142, 252)       # #2F8EFC
WHITE = (255, 255, 255)
MOON = (246, 231, 180)        # #F6E7B4


def make_icon(size):
    s = size * 8
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))

    # 对角渐变底
    grad = Image.new("RGBA", (s, s))
    gd = ImageDraw.Draw(grad)
    for y in range(s):
        t = y / s
        if t < 0.5:
            k = t / 0.5
            col = tuple(int(NIGHT_TOP[i] + (NIGHT_MID[i] - NIGHT_TOP[i]) * k) for i in range(3))
        else:
            k = (t - 0.5) / 0.5
            col = tuple(int(NIGHT_MID[i] + (NIGHT_BOT[i] - NIGHT_MID[i]) * k) for i in range(3))
        gd.line([(0, y), (s, y)], fill=col + (255,))

    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, s - 1, s - 1], radius=s // 4, fill=255)
    img.paste(grad, (0, 0), mask)

    d = ImageDraw.Draw(img)

    # 星场
    random.seed(7)
    for _ in range(26):
        x = random.randint(int(s * 0.08), int(s * 0.92))
        y = random.randint(int(s * 0.06), int(s * 0.60))
        r = random.choice([0.008, 0.010, 0.013, 0.016]) * s
        a = random.randint(120, 235)
        d.ellipse([x - r, y - r, x + r, y + r], fill=WHITE + (a,))

    # 月牙
    mx, my, mr = s * 0.735, s * 0.225, s * 0.100
    d.ellipse([mx - mr, my - mr, mx + mr, my + mr], fill=MOON + (255,))
    cr = mr * 0.90
    d.ellipse([mx - mr * 0.42 - cr, my - mr * 0.30 - cr,
               mx - mr * 0.42 + cr, my - mr * 0.30 + cr], fill=NIGHT_TOP + (255,))

    # 信号弧（品牌蓝）
    cx, cy = s * 0.5, s * 0.655
    lw = max(3, int(s * 0.058))

    def arc(radius):
        d.arc([cx - radius, cy - radius, cx + radius, cy + radius],
              start=133, end=407, fill=ACCENT + (255,), width=lw)

    arc(s * 0.150)
    arc(s * 0.290)
    r_dot = s * 0.048
    d.ellipse([cx - r_dot, cy - s * 0.048 - r_dot,
               cx + r_dot, cy - s * 0.048 + r_dot], fill=ACCENT + (255,))

    # 断裂缺口
    gap_y = cy + s * 0.285
    d.ellipse([cx - s * 0.112, gap_y - s * 0.072, cx + s * 0.112, gap_y + s * 0.072],
              fill=NIGHT_BOT + (255,))

    return img.resize((size, size), Image.LANCZOS)


def make_foreground(size):
    """自适应图标前景：透明底 + 白色图形，留安全边距"""
    s = size * 8
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    cx, cy = s * 0.5, s * 0.60
    lw = max(3, int(s * 0.042))

    def arc(radius):
        d.arc([cx - radius, cy - radius, cx + radius, cy + radius],
              start=133, end=407, fill=WHITE + (255,), width=lw)

    arc(s * 0.115)
    arc(s * 0.222)
    r_dot = s * 0.037
    d.ellipse([cx - r_dot, cy - s * 0.038 - r_dot,
               cx + r_dot, cy - s * 0.038 + r_dot], fill=WHITE + (255,))
    gap_y = cy + s * 0.219
    d.ellipse([cx - s * 0.086, gap_y - s * 0.056, cx + s * 0.086, gap_y + s * 0.056],
              fill=(0, 0, 0, 0))

    return img.resize((size, size), Image.LANCZOS)


for dpi, size in SIZES.items():
    out = os.path.join(RES, "mipmap-" + dpi)
    os.makedirs(out, exist_ok=True)
    make_icon(size).save(os.path.join(out, "ic_launcher.png"))
    make_foreground(size).save(os.path.join(out, "ic_launcher_fg.png"))
    print("saved", dpi, size)

print("ALL DONE")
