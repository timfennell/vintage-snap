"""Vintage Snap's launcher icon: a look through the finder.

The adaptive icon's foreground at 108 dp, drawn at 16 px per dp and scaled down. The key
content sits inside the 66 dp circle every launcher mask keeps.
"""
import math, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

DP = 16
N = 108 * DP
C = N / 2
rng = np.random.default_rng(5)

def dp(v): return v * DP

# ---- the dark eyepiece surround, a little warm, darker toward the edges
yy, xx = np.mgrid[0:N, 0:N].astype(np.float32)
d = np.hypot(xx - C, yy - C) / (N / 2)
base = np.dstack([np.full((N, N), 0.045), np.full((N, N), 0.040), np.full((N, N), 0.036)]).astype(np.float32)
base *= (1.0 - 0.45 * np.clip(d - 0.3, 0, 1))[..., None]

# ---- the ground glass: portrait 2:3, rounded corners
gw, gh = dp(33), dp(49.5)
GY = C - dp(4.5)                                   # the glass sits a little high: the readout goes under it
gx0, gy0 = C - gw / 2, GY - gh / 2
glass_mask = Image.new("L", (N, N), 0)
ImageDraw.Draw(glass_mask).rounded_rectangle([gx0, gy0, gx0 + gw, gy0 + gh], radius=dp(3.2), fill=255)
gm = np.asarray(glass_mask, np.float32) / 255

# frosted warm glass: a hot spot in the middle falling off to the corners, grain, Fresnel rings
gd = np.hypot((xx - C) / (gw / 2), (yy - GY) / (gh / 2))
lum = 0.93 - 0.20 * np.clip(gd, 0, 1.5) ** 1.6
# a landscape on the screen, well out of focus: warm sky to a peach horizon (a hazy sun in it),
# blue-green hills, a warm green and ochre field in front
v = (yy - gy0) / gh                                        # 0 at the glass's top, 1 at its bottom
u = (xx - gx0) / gw
hz = 0.50 + 0.035 * np.sin(u * 5.1 + 0.7) + 0.02 * np.sin(u * 11.3 + 2.0)   # the hills' line
fh = 0.66 + 0.02 * np.sin(u * 3.3 + 1.1)                                    # where the field begins
sky_t = np.clip(v / 0.5, 0, 1)
sky = np.dstack([0.62 + 0.36 * sky_t, 0.70 + 0.20 * sky_t, 0.80 - 0.10 * sky_t])
sun = np.exp(-(((u - 0.66) / 0.16) ** 2 + ((v - 0.40) / 0.10) ** 2))
sky = sky + sun[..., None] * np.array([0.30, 0.22, 0.10])
hills = np.dstack([np.full((N, N), 0.40), np.full((N, N), 0.50), np.full((N, N), 0.50)])
field_t = np.clip((v - fh) / 0.34, 0, 1)
field = np.dstack([0.52 + 0.20 * field_t, 0.56 + 0.06 * field_t, 0.30 - 0.04 * field_t])
land = np.where((v < hz)[..., None], sky, np.where((v < fh)[..., None], hills, field)).astype(np.float32)
land8 = Image.fromarray((np.clip(land, 0, 1) * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(dp(2.6)))
land = np.asarray(land8, np.float32) / 255
# seen on the warm ground glass: tinted, a little flat, brighter in the middle
col = (land * 0.80 + 0.14) * np.array([1.0, 0.95, 0.86]) * (lum / 0.93)[..., None]
grain = rng.normal(0, 0.035, (N, N)).astype(np.float32)
grain = np.asarray(Image.fromarray(((grain + 0.5) * 255).clip(0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(DP * 0.12)), np.float32) / 255 - 0.5
col += grain[..., None] * 0.5
rings = 0.012 * (np.cos(np.hypot(xx - C, yy - GY) / dp(1.6) * 2 * math.pi) > 0.92)
col -= rings[..., None]

# ---- the split prism and microprism collar at the centre
r_collar, r_spot = dp(5.6), dp(3.2)
rc = np.hypot(xx - C, yy - GY)
collar = (rc < r_collar) & (rc >= r_spot)
# microprism shimmer: a fine checker of light and dark
chk = ((np.floor((xx - C) / dp(0.5)) + np.floor((yy - GY) / dp(0.5))) % 2) * 2 - 1
col[collar] -= 0.05
col[collar] += (chk[collar] * 0.035)[:, None]
spot = rc < r_spot
col[spot] *= 1.03
# the split image: a dark vertical edge in the scene, broken and shifted at the horizontal divide
edge_top = spot & (yy < GY) & (np.abs(xx - (C - dp(0.9))) < dp(0.55))
edge_bot = spot & (yy >= GY) & (np.abs(xx - (C + dp(0.9))) < dp(0.55))
col[edge_top | edge_bot] = [0.18, 0.15, 0.12]
divide = spot & (np.abs(yy - GY) < dp(0.12))
col[divide] -= 0.12
ring_line = np.abs(rc - r_spot) < dp(0.18)
col[ring_line] -= 0.10
ring_out = np.abs(rc - r_collar) < dp(0.16)
col[ring_out] -= 0.07

# ---- the corner frame marks
cm = Image.new("L", (N, N), 0); cd = ImageDraw.Draw(cm)
m, L, w = dp(3.2), dp(4.2), int(dp(0.35))
for sx, sy in ((0, 0), (1, 0), (0, 1), (1, 1)):
    x = gx0 + m if sx == 0 else gx0 + gw - m
    y = gy0 + m if sy == 0 else gy0 + gh - m
    dx = L if sx == 0 else -L; dy = L if sy == 0 else -L
    cd.line([x, y, x + dx, y], fill=255, width=w); cd.line([x, y, x, y + dy], fill=255, width=w)
cmk = np.asarray(cm.filter(ImageFilter.GaussianBlur(DP * 0.15)), np.float32) / 255
col -= cmk[..., None] * 0.35

# ---- the meter strip: frosted, coming in from the glass's left edge, red caps, dashes, needle
sx0, sw = gx0 - dp(2.0), dp(7.0)
sh = gh * 0.62; sy0 = GY - sh / 2
strip = Image.new("L", (N, N), 0)
ImageDraw.Draw(strip).rounded_rectangle([sx0, sy0, sx0 + sw, sy0 + sh], radius=dp(0.8), fill=255)
st = np.asarray(strip, np.float32) / 255 * gm
paper = np.array([0.93, 0.90, 0.83])
col = col * (1 - st[..., None] * 0.55) + paper * st[..., None] * 0.55
caps = Image.new("L", (N, N), 0); cpd = ImageDraw.Draw(caps)
cap = dp(3.0)
cpd.rounded_rectangle([sx0, sy0, sx0 + sw, sy0 + cap], radius=dp(0.8), fill=255)
cpd.rounded_rectangle([sx0, sy0 + sh - cap, sx0 + sw, sy0 + sh], radius=dp(0.8), fill=255)
cp = np.asarray(caps, np.float32) / 255 * gm
red = np.array([0.85, 0.24, 0.14])
col = col * (1 - cp[..., None]) + red * cp[..., None]
# the scale's marks: short dashes (figures don't read at icon size), the slow ones red
dash = Image.new("RGBA", (N, N), (0, 0, 0, 0)); dd = ImageDraw.Draw(dash)
n = 9
for i in range(n):
    y = sy0 + cap + (i + 0.5) * (sh - 2 * cap) / n
    c = (210, 60, 35, 255) if i >= 5 else (46, 42, 38, 255)
    dd.rounded_rectangle([sx0 + sw - dp(3.6), y - dp(0.35), sx0 + sw - dp(0.9), y + dp(0.35)], radius=dp(0.3), fill=c)
da = np.asarray(dash, np.float32) / 255
col = col * (1 - da[..., 3:4] * gm[..., None]) + da[..., :3] * (da[..., 3:4] * gm[..., None])
# the needle, in from outside the frame
nd = Image.new("L", (N, N), 0)
ny = sy0 + cap + 3.3 * (sh - 2 * cap) / n
ImageDraw.Draw(nd).line([gx0 - dp(14), GY + dp(3), gx0 + dp(1.6), ny], fill=255, width=int(dp(0.45)))
ndm = np.asarray(nd, np.float32) / 255 * gm
col = col * (1 - ndm[..., None]) + np.array([0.78, 0.09, 0.06]) * ndm[..., None]
# soft shadow where the strip goes under the frame's edge
edge_sh = np.clip(1 - (xx - gx0) / dp(1.6), 0, 1) * gm * st
col *= (1 - 0.45 * edge_sh)[..., None]

# ---- composite the glass into the surround, a slight softness (seen through the eyepiece)
glass = np.clip(col, 0, 1)
gimg = Image.fromarray((glass * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(DP * 0.10))
glass = np.asarray(gimg, np.float32) / 255
out = base * (1 - gm[..., None]) + glass * gm[..., None]

# the glass's light blooming past its edge into the dark surround (the finder's glow)
gimg8 = Image.fromarray((gm * 255).astype(np.uint8))
tight = np.asarray(gimg8.filter(ImageFilter.GaussianBlur(dp(1.6))), np.float32) / 255 * (1 - gm)
wide = np.asarray(gimg8.filter(ImageFilter.GaussianBlur(dp(7.0))), np.float32) / 255 * (1 - gm)
out += tight[..., None] * np.array([0.95, 0.85, 0.68]) * 0.38      # a bright halo hugging the glass
out += wide[..., None] * np.array([0.60, 0.48, 0.32]) * 0.30       # and a warm spill into the eyepiece

# the HUD index: green reflected light at the strip's inner edge, glowing, with a ghost
hud = Image.new("L", (N, N), 0); hd = ImageDraw.Draw(hud)
hy = sy0 + cap + 4.5 * (sh - 2 * cap) / n
hx = sx0 + sw + dp(0.4)
tri = [(hx, hy - dp(1.5)), (hx + dp(3.0), hy), (hx, hy + dp(1.5))]
hd.polygon(tri, fill=255)
h = np.asarray(hud, np.float32) / 255
hb = np.asarray(hud.filter(ImageFilter.GaussianBlur(dp(1.4))), np.float32) / 255
ghost = Image.new("L", (N, N), 0)
ImageDraw.Draw(ghost).polygon([(x + dp(0.9), y - dp(0.6)) for x, y in tri], fill=255)
gh_ = np.asarray(ghost.filter(ImageFilter.GaussianBlur(DP * 0.3)), np.float32) / 255
green = np.array([0.10, 0.80, 0.32])
a = np.clip(h * 0.9 + gh_ * 0.18, 0, 1)[..., None]
out = out * (1 - a) + green * a                       # the reflected green, holding its colour on the bright glass
out += green * (hb * 0.22)[..., None] * (1 - h)[..., None]   # and its bloom

# ---- the red seven-segment readout under the finder: "125", unlit segments faintly there
SEG = {"0": "abcdef", "1": "bc", "2": "abged", "3": "abgcd", "4": "fgbc", "5": "afgcd", "6": "afgcde",
       "7": "abc", "8": "abcdefg", "9": "abcdfg"}
def seg_polys(x, y, w, h, t, slant):
    """The seven segments of a digit whose box is x, y, w, h (stroke t), slanted like an LED's."""
    def P(px, py): return (px + slant * (y + h - py), py)
    m = y + h / 2
    hz = lambda yy: [P(x + t, yy - t / 2), P(x + w - t, yy - t / 2), P(x + w - t / 2, yy), P(x + w - t, yy + t / 2), P(x + t, yy + t / 2), P(x + t / 2, yy)]
    vt = lambda xx, y0, y1: [P(xx, y0 + t / 2), P(xx + t / 2, y0 + t), P(xx + t / 2, y1 - t), P(xx, y1 - t / 2), P(xx - t / 2, y1 - t), P(xx - t / 2, y0 + t)]
    return {"a": hz(y + t / 2), "g": hz(m), "d": hz(y + h - t / 2),
            "f": vt(x + t / 2, y + t / 2, m), "b": vt(x + w - t / 2, y + t / 2, m),
            "e": vt(x + t / 2, m, y + h - t / 2), "c": vt(x + w - t / 2, m, y + h - t / 2)}
lit = Image.new("L", (N, N), 0); unl = Image.new("L", (N, N), 0)
ld, ud = ImageDraw.Draw(lit), ImageDraw.Draw(unl)
dw, dh, gap = dp(5.0), dp(8.0), dp(1.6)
text = "125"
x = C - (len(text) * dw + (len(text) - 1) * gap) / 2
ytop = GY + gh / 2 + dp(3.2)
for ch in text:
    polys = seg_polys(x, ytop, dw, dh, dp(1.05), 0.12)
    for k, poly in polys.items():
        (ld if k in SEG[ch] else ud).polygon(poly, fill=255)
    x += dw + gap
L = np.asarray(lit.filter(ImageFilter.GaussianBlur(DP * 0.12)), np.float32) / 255
U = np.asarray(unl.filter(ImageFilter.GaussianBlur(DP * 0.12)), np.float32) / 255
LG = np.asarray(lit.filter(ImageFilter.GaussianBlur(dp(1.5))), np.float32) / 255
out = out * (1 - U[..., None] * 0.6) + np.array([0.16, 0.025, 0.02]) * (U[..., None] * 0.6)
out = out * (1 - L[..., None]) + np.array([1.0, 0.20, 0.10]) * L[..., None]
out += LG[..., None] * np.array([0.9, 0.12, 0.05]) * 0.55          # the LEDs' red glow

out = np.clip(out, 0, 1)
icon = Image.fromarray((out * 255).astype(np.uint8))

res = sys.argv[1]
for name, px in (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)):
    icon.resize((px, px), Image.LANCZOS).save(f"{res}/mipmap-{name}/ic_launcher_foreground.png")

# previews: full, and under the circle and squircle masks launchers use (72 dp of the 108 shown)
prev = icon.resize((432, 432), Image.LANCZOS)
crop = prev.crop((72, 72, 360, 360))
mask_c = Image.new("L", (288, 288), 0); ImageDraw.Draw(mask_c).ellipse([0, 0, 287, 287], fill=255)
mask_s = Image.new("L", (288, 288), 0); ImageDraw.Draw(mask_s).rounded_rectangle([0, 0, 287, 287], radius=90, fill=255)
sheet = Image.new("RGB", (288 * 3 + 40, 288), (240, 240, 240))
sheet.paste(crop, (0, 0))
sheet.paste(crop, (288 + 20, 0), mask_c)
sheet.paste(crop, (2 * 288 + 40, 0), mask_s)
sheet.save(sys.argv[2])
