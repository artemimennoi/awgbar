#!/usr/bin/env python3
"""Writes the layers of AppIcon.icon: a silver shield and, above it, a gear with a V.

Everything is built from the right half and mirrored, so the icon is symmetric by construction.
The menu bar glyph (Sources/AWGBar/StatusGlyph.swift) uses the same shield and a bolder gear without the V; keep them in step.
Design space is 1024 points with the icon body 824 points wide; the layers are scaled to fill
Icon Composer's 1024-point canvas. Run from anywhere: python3 Resources/make-icon.py
"""
import math
import os

CANVAS = 1024
FILL = 1024 / 824  # design space (824-point body) to Icon Composer canvas
CX = 512
GEAR = (512, 500)  # the shield's visual middle sits above its geometric one
DARK = "#1a1c21"


def t(x, y):
    return (CX + (x - CX) * FILL, CX + (y - CX) * FILL)


def fmt(p):
    return f"{p[0]:.2f},{p[1]:.2f}"


def shield_path():
    """Right half from the top center down to the bottom tip, then the same segments mirrored."""
    right = [
        ("M", [(512, 226)]),
        ("C", [(596, 270), (676, 290), (744, 292)]),
        ("C", [(758, 292), (768, 302), (768, 316)]),
        ("L", [(768, 470)]),
        ("C", [(768, 636), (654, 742), (512, 800)]),
    ]
    mirror = lambda p: (2 * CX - p[0], p[1])
    d = [cmd + " " + " ".join(fmt(t(*p)) for p in pts) for cmd, pts in right]
    for i in range(len(right) - 1, 0, -1):
        cmd, pts = right[i]
        start = right[i - 1][1][-1]
        back = [mirror(pts[1]), mirror(pts[0]), mirror(start)] if cmd == "C" else [mirror(start)]
        d.append(cmd + " " + " ".join(fmt(t(*p)) for p in back))
    return " ".join(d) + " Z"


def gear_path(r_tip, r_root, hole, teeth=8, tip_frac=0.42, root_frac=0.62):
    """One tooth points straight up, so with an even count the gear is symmetric; the hole is even-odd."""
    cx, cy = t(*GEAR)
    r_tip, r_root, hole = r_tip * FILL, r_root * FILL, hole * FILL
    step = 2 * math.pi / teeth
    at, ar = step * tip_frac / 2, step * root_frac / 2
    pt = lambda r, a: (cx + r * math.cos(a), cy + r * math.sin(a))
    d = []
    for i in range(teeth):
        a = -math.pi / 2 + i * step
        d.append(("M " if i == 0 else "L ") + fmt(pt(r_root, a - ar)))
        d.append("L " + fmt(pt(r_tip, a - at)))
        d.append(f"A {r_tip:.2f} {r_tip:.2f} 0 0 1 " + fmt(pt(r_tip, a + at)))
        d.append("L " + fmt(pt(r_root, a + ar)))
        d.append(f"A {r_root:.2f} {r_root:.2f} 0 0 1 " + fmt(pt(r_root, a + step - ar)))
    d.append("Z")
    d.append(f"M {cx - hole:.2f},{cy:.2f} A {hole:.2f} {hole:.2f} 0 1 0 {cx + hole:.2f},{cy:.2f} "
             f"A {hole:.2f} {hole:.2f} 0 1 0 {cx - hole:.2f},{cy:.2f} Z")
    return " ".join(d)


def v_path(width, height, drop):
    cx, cy = GEAR[0], GEAR[1] + drop
    pts = [(cx - width / 2, cy - height / 2), (cx, cy + height / 2), (cx + width / 2, cy - height / 2)]
    return "M " + " L ".join(fmt(t(*p)) for p in pts)


def svg(body, defs=""):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{CANVAS}" height="{CANVAS}" '
            f'viewBox="0 0 {CANVAS} {CANVAS}"><defs>{defs}</defs>{body}</svg>\n')


SILVER = ('<linearGradient id="silver" x1="0" y1="0" x2="0" y2="1">'
          '<stop offset="0" stop-color="#ffffff"/><stop offset="1" stop-color="#b9bec8"/></linearGradient>')

LAYERS = {
    "shield.svg": svg(f'<path d="{shield_path()}" fill="url(#silver)"/>', SILVER),
    "gear.svg": svg(
        f'<path d="{gear_path(150, 118, 78)}" fill="{DARK}" fill-rule="evenodd"/>'
        f'<path d="{v_path(84, 74, 4)}" fill="none" stroke="{DARK}" stroke-width="{30 * FILL:.2f}" '
        'stroke-linecap="round" stroke-linejoin="round"/>'),
}

if __name__ == "__main__":
    assets = os.path.join(os.path.dirname(os.path.abspath(__file__)), "AppIcon.icon", "Assets")
    os.makedirs(assets, exist_ok=True)
    for name, content in LAYERS.items():
        with open(os.path.join(assets, name), "w") as f:
            f.write(content)
