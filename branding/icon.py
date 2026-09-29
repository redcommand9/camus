"""Camus Reader icon ("Raised books"): one geometry source for every icon file.

Run `python3 branding/icon.py` from the project root after changing the shapes; it rewrites the
Android vector/adaptive icon, the master SVGs, and (with rsvg-convert + ImageMagick) the PNGs.
Coordinates are on Android's 108x108 adaptive-icon grid; only the centre 72x72 is ever visible.
"""
import math, pathlib, shutil, subprocess

BG, MAIN, ACCENT = "#0D0D0F", "#F2EEE8", "#DEC6AC"
ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "composeApp/src/androidMain/res"

def n(v): return f"{v:.2f}".rstrip("0").rstrip(".")

def rounded_rect(x, y, w, h, r):
    return (f"M{n(x + r)},{n(y)} h{n(w - 2 * r)} a{n(r)},{n(r)} 0 0 1 {n(r)},{n(r)} v{n(h - 2 * r)} "
            f"a{n(r)},{n(r)} 0 0 1 {n(-r)},{n(r)} h{n(-(w - 2 * r))} a{n(r)},{n(r)} 0 0 1 {n(-r)},{n(-r)} "
            f"v{n(-(h - 2 * r))} a{n(r)},{n(r)} 0 0 1 {n(r)},{n(-r)} z")

def ray(cx, cy, r0, r1, deg, half_w):
    a = math.radians(deg); nx, ny = -math.sin(a), math.cos(a)
    bx, by = cx + r0 * math.cos(a), cy + r0 * math.sin(a)
    tx, ty = cx + r1 * math.cos(a), cy + r1 * math.sin(a)
    return f"M{n(bx + nx * half_w)},{n(by + ny * half_w)} L{n(tx)},{n(ty)} L{n(bx - nx * half_w)},{n(by - ny * half_w)} z"

# (color, path data, rotation or None as (degrees, pivot x, pivot y)). Gaps between the books are real
# geometry, not background-coloured strokes, so the monochrome (themed-icon) layer stays readable.
SHAPES = [(ACCENT, ray(55, 31, 4.5, 11, d, 1.4), None) for d in (-150, -120, -90, -60, -30)] + [
    (MAIN, "M30,112 L47,112 L61,64 L50.5,61.5 z", None),                          # forearm
    (MAIN, rounded_rect(49, 54.5, 12.5, 11.5, 3.2), (12, 55.25, 60.25)),          # fist
    (ACCENT, rounded_rect(34.9, 46.9, 40.2, 5.7, 1), None),                        # bottom book
    (ACCENT, rounded_rect(37.9, 39.4, 34.2, 5.7, 1), (-2.5, 55, 42.25)),           # middle book
    (ACCENT, rounded_rect(36.9, 31.3, 35.2, 5.7, 1), (2.5, 54.5, 34.15)),          # top book
]

def vector(mono=False):
    out = ['<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="108dp" android:height="108dp"',
           '    android:viewportWidth="108" android:viewportHeight="108">']
    for color, d, rot in SHAPES:
        fill = "#FFFFFFFF" if mono else color
        path = f'<path android:fillColor="{fill}" android:pathData="{d}" />'
        if rot:
            out.append(f'    <group android:rotation="{n(rot[0])}" android:pivotX="{n(rot[1])}" android:pivotY="{n(rot[2])}">')
            out.append(f"        {path}")
            out.append("    </group>")
        else:
            out.append(f"    {path}")
    out.append("</vector>")
    return "\n".join(out) + "\n"

def svg(background=True, crop=True):
    box = "18 18 72 72" if crop else "0 0 108 108"
    body = [f'<rect x="0" y="0" width="108" height="108" fill="{BG}"/>'] if background else []
    for color, d, rot in SHAPES:
        t = f' transform="rotate({n(rot[0])} {n(rot[1])} {n(rot[2])})"' if rot else ""
        body.append(f'<path fill="{color}" d="{d}"{t}/>')
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{box}">\n  ' + "\n  ".join(body) + "\n</svg>\n"

def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True); path.write_text(text)

ADAPTIVE = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""

write(RES / "drawable/ic_launcher_foreground.xml", vector())
write(RES / "drawable/ic_launcher_monochrome.xml", vector(mono=True))
write(RES / "values/ic_launcher_background.xml",
      f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">{BG}</color>\n</resources>\n')
write(RES / "mipmap-anydpi-v26/ic_launcher.xml", ADAPTIVE)
write(RES / "mipmap-anydpi-v26/ic_launcher_round.xml", ADAPTIVE)
write(ROOT / "branding/camus-reader-icon.svg", svg())

if shutil.which("rsvg-convert") and shutil.which("magick"):
    tmp = ROOT / "branding/.render.png"
    def render(size, out, shape):
        # Legacy (Android 7.x) icons: the visible 72x72 crop inside a 2/48 margin, as launchers expect.
        inner = round(size * 44 / 48)
        subprocess.run(["rsvg-convert", "-w", str(inner * 4), "-h", str(inner * 4), "-o", str(tmp), str(ROOT / "branding/camus-reader-icon.svg")], check=True)
        r = inner * 2 if shape == "round" else round(inner * 0.23) * 4
        s = inner * 4
        subprocess.run(["magick", str(tmp), "-alpha", "set", "(", "-size", f"{s}x{s}", "xc:none", "-fill", "white",
                        "-draw", f"roundrectangle 0,0 {s - 1},{s - 1} {r},{r}", ")", "-compose", "DstIn", "-composite", "-compose", "over",
                        "-resize", f"{inner}x{inner}", "-background", "none", "-gravity", "center",
                        "-extent", f"{size}x{size}", str(out)], check=True)
    for dpi, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        (RES / f"mipmap-{dpi}").mkdir(parents=True, exist_ok=True)
        render(size, RES / f"mipmap-{dpi}/ic_launcher.png", "square")
        render(size, RES / f"mipmap-{dpi}/ic_launcher_round.png", "round")
    # Linux: packaged app icon and the running window's icon.
    desktop = ROOT / "composeApp/src/desktopMain/resources/camus-reader.png"
    desktop.parent.mkdir(parents=True, exist_ok=True)
    render(512, desktop, "square")
    tmp.unlink()
else:
    print("rsvg-convert/magick not found: PNGs not regenerated")
