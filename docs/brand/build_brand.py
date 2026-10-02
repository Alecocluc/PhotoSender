"""Generate every Pherry brand asset from one mark geometry.

The mark is a "P" cut from film: the stem is a strip with five sprocket perforations, the bowl
holds a rectangular frame window. Run from the repo root:  python docs/brand/build_brand.py
Requires fontTools + uharfbuzz and the Archivo variable font (desktop/renderer/fonts).
"""
import re
from pathlib import Path

import uharfbuzz as hb
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

ROOT = Path(__file__).resolve().parents[2]
FONT = ROOT / "desktop" / "renderer" / "fonts" / "Archivo.ttf"

YELLOW = "#FFC629"  # envelope yellow
INK = "#151412"     # print ink
PAPER = "#EDEBE6"   # darkroom text

# Mark geometry in a 108-unit box (Android adaptive icon space). Bounding box x30..86, y21..87.
P_OUTER = "M30 21h33a23 23 0 0 1 0 46H53v20H30Z"
P_WINDOW = "M57 32h7a2 2 0 0 1 2 2v20a2 2 0 0 1-2 2h-7a2 2 0 0 1-2-2V34a2 2 0 0 1 2-2Z"
PERF_Y = (27, 39, 51, 63, 75)


def perf(y):
    return (f"M36.5 {y}h10a1.6 1.6 0 0 1 1.6 1.6v3.4a1.6 1.6 0 0 1-1.6 1.6h-10"
            f"a1.6 1.6 0 0 1-1.6-1.6v-3.4a1.6 1.6 0 0 1 1.6-1.6Z")


MARK_PATH = " ".join([P_OUTER, P_WINDOW, *(perf(y) for y in PERF_Y)])
BBOX_CX, BBOX_CY = 58.0, 54.0  # centre of the mark's bounding box


def centred(scale, box=108.0):
    """Translate/scale that centres the mark in a `box`-unit square at `scale`."""
    c = box / 2
    tx = c - BBOX_CX * scale
    ty = c - BBOX_CY * scale
    return scale, tx, ty


def svg_mark_group(scale, fill, box=108.0):
    s, tx, ty = centred(scale, box)
    return (f'<path fill="{fill}" fill-rule="evenodd" transform="translate({tx:.3f} {ty:.3f}) '
            f'scale({s:.4f})" d="{MARK_PATH}"/>')


def tile_svg(size=512, radius_ratio=0.2222, scale=0.86):
    r = 108 * radius_ratio
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 108 108" '
            f'role="img" aria-label="Pherry">\n'
            f'  <rect width="108" height="108" rx="{r:.2f}" fill="{YELLOW}"/>\n'
            f'  {svg_mark_group(scale, INK)}\n</svg>\n')


def mono_svg():
    # Tight box around the mark, painted with currentColor so it inherits text colour.
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="29 20 58 68" role="img" aria-label="Pherry">\n'
            f'  <path fill="currentColor" fill-rule="evenodd" d="{MARK_PATH}"/>\n</svg>\n')


def wordmark_paths(text="Pherry", wght=800, wdth=82, size=100.0):
    """Shape `text` with HarfBuzz at the given variation and return (svg path d, width, ascent)."""
    blob = hb.Blob.from_file_path(str(FONT))
    face = hb.Face(blob)
    font = hb.Font(face)
    font.set_variations({"wght": wght, "wdth": wdth})
    upem = face.upem
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(font, buf, {"kern": True, "liga": True})
    scale = size / upem
    pen = SVGPathPen(None)
    x = 0
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        # Flip y (font units are y-up) and place each glyph at its pen position.
        tpen = TransformPen(pen, (scale, 0, 0, -scale, (x + pos.x_offset) * scale, -pos.y_offset * scale))
        font.draw_glyph_with_pen(info.codepoint, tpen)
        x += pos.x_advance
    extents = font.get_font_extents("ltr")
    d = re.sub(r"-?\d+\.\d+", lambda m: f"{float(m.group()):.2f}".rstrip("0").rstrip("."), pen.getCommands())
    return d, x * scale, extents.ascender * scale, -extents.descender * scale


def wordmark_svg(color="currentColor"):
    d, w, asc, desc = wordmark_paths()
    h = asc + desc
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 {-asc:.2f} {w:.2f} {h:.2f}" role="img" '
            f'aria-label="Pherry">\n  <path fill="{color}" d="{d}"/>\n</svg>\n')


def lockup_svg(text_color):
    d, w, asc, desc = wordmark_paths(size=100)
    # Tile height equals the cap+descender band; wordmark sits to its right.
    tile = 112.0
    gap = 26.0
    cap = 70.0  # Archivo cap height at 100 units, approximately
    baseline = tile / 2 + cap / 2
    total_w = tile + gap + w
    mark = svg_mark_group(0.86, INK)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {total_w:.2f} {tile:.2f}" role="img" '
            f'aria-label="Pherry">\n'
            f'  <g transform="scale({tile / 108:.4f})"><rect width="108" height="108" rx="24" fill="{YELLOW}"/>{mark}</g>\n'
            f'  <path fill="{text_color}" transform="translate({tile + gap:.2f} {baseline:.2f})" d="{d}"/>\n'
            f'</svg>\n')


# ── Android vector drawables ─────────────────────────────────────────────────

def android_group(scale, fill, box=108.0, extra=""):
    s, tx, ty = centred(scale, box)
    return (f'    <group android:translateX="{tx:.3f}" android:translateY="{ty:.3f}" '
            f'android:scaleX="{s:.4f}" android:scaleY="{s:.4f}">\n'
            f'        <path\n            android:fillType="evenOdd"\n'
            f'            android:fillColor="{fill}"\n            android:pathData="{MARK_PATH}" />\n'
            f'    </group>\n{extra}')


def vector(width_dp, height_dp, body, comment, vp=108):
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- {comment} Generated by docs/brand/build_brand.py; edit the script, not this file. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{width_dp}dp"\n    android:height="{height_dp}dp"\n'
            f'    android:viewportWidth="{vp}"\n    android:viewportHeight="{vp}">\n'
            f'{body}</vector>\n')


def main():
    brand = ROOT / "docs" / "brand"
    res = ROOT / "app" / "src" / "main" / "res" / "drawable"
    renderer = ROOT / "desktop" / "renderer"

    (brand / "pherry-mark.svg").write_text(tile_svg(), encoding="utf-8")
    (brand / "pherry-logo.svg").write_text(tile_svg(), encoding="utf-8")
    (brand / "pherry-mark-mono.svg").write_text(mono_svg(), encoding="utf-8")
    (brand / "pherry-wordmark.svg").write_text(wordmark_svg(), encoding="utf-8")
    (brand / "pherry-lockup.svg").write_text(lockup_svg(INK), encoding="utf-8")
    (brand / "pherry-lockup-dark.svg").write_text(lockup_svg(PAPER), encoding="utf-8")
    (renderer / "pherry-icon.svg").write_text(tile_svg(256), encoding="utf-8")

    # Adaptive launcher icon: mark inside the 66dp safe circle (scale 0.72), yellow ground.
    (res / "ic_launcher_foreground.xml").write_text(
        vector(108, 108, android_group(0.72, "#FF151412"), "Launcher foreground: the Pherry film-P."),
        encoding="utf-8")
    (res / "ic_launcher_monochrome.xml").write_text(
        vector(108, 108, android_group(0.72, "#FFFFFFFF"), "Themed-icon mask of the Pherry film-P."),
        encoding="utf-8")
    (res / "ic_launcher_background.xml").write_text(
        vector(108, 108, '    <path android:fillColor="#FFFFC629" android:pathData="M0,0h108v108h-108z" />\n',
               "Launcher background: envelope yellow."),
        encoding="utf-8")
    # In-app tile (rounded square) used by PherryMark.
    tile_body = ('    <path android:fillColor="#FFFFC629" '
                 'android:pathData="M24,0h60a24,24 0,0 1,24 24v60a24,24 0,0 1,-24 24h-60a24,24 0,0 1,-24 -24v-60a24,24 0,0 1,24 -24z" />\n'
                 + android_group(0.86, "#FF151412"))
    (res / "ic_pherry_logo.xml").write_text(
        vector(108, 108, tile_body, "In-app Pherry tile (yellow, rounded)."), encoding="utf-8")
    # Status-bar icon: white silhouette filling most of a 24dp box.
    (res / "ic_stat_pherry.xml").write_text(
        vector(24, 24, android_group(1.42, "#FFFFFFFF"), "Notification small icon."), encoding="utf-8")
    print("brand assets written")


if __name__ == "__main__":
    main()
