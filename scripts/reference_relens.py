"""CPU reference of the spike's GPU pipeline (NumPy), for checking the look
on the Mac and as a golden image to compare the phone's output against.
Mirrors app/src/main/assets/shaders/*.comp pass by pass:

  linearize -> depth (Depth Anything V2 Small, ONNX Runtime CPU) -> jbu -> coc
  -> mips -> tiles + dilate -> gather -> develop (film_look)

    python reference_relens.py photo.jpg out_dir [--width 1440] [--lens 85] [--stock "Backlot 800T"]
"""
import argparse, json, math, os, sys
import numpy as np
from PIL import Image
import onnxruntime as ort

HERE = os.path.dirname(os.path.abspath(__file__))

LENSES = {  # name, focal, max aperture, blades, rotation, cat's eye, soap, vignette, lateral CA, obstruction
    "50": ("Ohi Kor 50mm f/1.4", 50, 1.4, 7, 0.1, 0.45, 0.15, 0.40, 0.0005, 0),
    "85": ("Ohi Kor 85mm f/1.4", 85, 1.4, 9, 0.0, 0.5, 0.1, 0.4, 0.0005, 0),
    "58": ("Krasnogorsk Solnce 58mm f/2", 58, 2.0, 0, 0.0, 0.9, 0.2, 0.45, 0.0009, 0),
    "500": ("Ohi Reflex-Kor 500mm f/8", 500, 8.0, 0, 0.0, 0.1, 0.2, 0.3, 0.0001, 0.45),
}

# The stocks the spike shows, packed as FilmStock.packed() (rows of 4).
def pack(ct=((1, 0, 0), (0, 1, 0), (0, 0, 1)), bal=(1, 1, 1), bw=False, bwmix=(0.30, 0.59, 0.11), sat=1,
         contrast=1, lift=0.02, shoulder=1, grain=0.3, iso=100, size=1.2, toe=1, hal=0,
         tint=(1.0, 0.25, 0.05), ir=False):
    return np.array([[*ct[0], 0], [*ct[1], 0], [*ct[2], 0], [*bal, 1 if bw else 0], [*bwmix, sat],
                     [contrast, lift, shoulder, grain * math.sqrt(iso / 100)], [hal, *tint],
                     [iso, size, toe, 1 if ir else 0]], dtype=np.float32)

STOCKS = {
    "Rochester Portrait 400": pack(((1.0, 0.02, -0.02), (0.0, 0.98, 0.02), (-0.01, 0.04, 0.97)), (1.03, 1.0, 0.96),
                                   sat=0.9, contrast=0.85, toe=0.95, shoulder=0.6, lift=0.035, grain=0.14, size=1.1, iso=400),
    "Backlot 800T": pack(((1.0, 0.03, -0.03), (0.0, 1.0, 0.0), (-0.02, 0.02, 1.0)), (0.80, 1.0, 1.40),
                         sat=1.0, contrast=0.95, toe=1.05, shoulder=0.7, lift=0.03, grain=0.17, size=1.3, iso=800,
                         hal=0.9, tint=(1.0, 0.18, 0.04)),
    "Rochester Tri-Pan 400": pack(bw=True, bwmix=(0.32, 0.56, 0.12), contrast=1.2, toe=1.1, shoulder=0.8,
                                  lift=0.025, grain=0.30, size=1.4, iso=400),
}


def to_lin(c):
    c = np.maximum(c, 0)
    return np.where(c >= 0.04045, ((c + 0.055) / 1.055) ** 2.4, c / 12.92)


def luma(c):
    return c[..., 0] * 0.2126 + c[..., 1] * 0.7152 + c[..., 2] * 0.0722


def bilinear(img, x, y):
    """img HxWxC, pixel-centre coordinates x, y (float arrays, any shape); clamp to edge."""
    h, w = img.shape[:2]
    x = np.clip(x - 0.5, 0, w - 1); y = np.clip(y - 0.5, 0, h - 1)
    x0 = np.floor(x).astype(np.int32); y0 = np.floor(y).astype(np.int32)
    x1 = np.minimum(x0 + 1, w - 1); y1 = np.minimum(y0 + 1, h - 1)
    fx = (x - x0)[..., None]; fy = (y - y0)[..., None]
    a = img[y0, x0]; b = img[y0, x1]; c = img[y1, x0]; d = img[y1, x1]
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy


def depth_anything(img_srgb, model):
    h, w = img_srgb.shape[:2]
    sess = ort.InferenceSession(model, providers=["CPUExecutionProvider"])
    inp = sess.get_inputs()[0]
    ih, iw = inp.shape[2], inp.shape[3]
    if not isinstance(ih, int):
        ih = 392 if w > h else 518; iw = 518 if w > h else 392
    small = np.asarray(Image.fromarray((img_srgb * 255).astype(np.uint8)).resize((iw, ih), Image.BICUBIC)) / 255.0
    x = (small - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]
    out = sess.run(None, {inp.name: x.transpose(2, 0, 1)[None].astype(np.float32)})[0].reshape(ih, iw)
    lo, hi = np.percentile(out, 1), np.percentile(out, 99)
    return np.clip((out - lo) / max(hi - lo, 1e-6), 0, 1).astype(np.float32), small.astype(np.float32)


def jbu(disp_lo, guide_lo, lin, sigma_r=0.06):
    h, w = lin.shape[:2]; lh, lw = disp_lo.shape
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    fx = (xx + 0.5) * lw / w - 0.5; fy = (yy + 0.5) * lh / h - 0.5
    bx = np.floor(fx).astype(np.int32); by = np.floor(fy).astype(np.int32)
    g = np.sqrt(luma(lin))
    gl_all = np.sqrt(luma(to_lin(guide_lo)))
    acc = np.zeros((h, w), np.float32); ws = np.zeros((h, w), np.float32)
    for dy in range(-1, 3):
        for dx in range(-1, 3):
            tx = np.clip(bx + dx, 0, lw - 1); ty = np.clip(by + dy, 0, lh - 1)
            d2 = (tx - fx) ** 2 + (ty - fy) ** 2
            wsp = np.exp(-0.5 * d2)
            wr = np.exp(-(g - gl_all[ty, tx]) ** 2 / (2 * sigma_r ** 2))
            wt = wsp * wr + 1e-5 * wsp
            acc += disp_lo[ty, tx] * wt; ws += wt
    return acc / ws


def mips(work, levels):
    out = [work]
    for _ in range(1, levels):
        a = out[-1]
        h, w = a.shape[:2]
        h2, w2 = max(1, h // 2), max(1, w // 2)
        a = a[:h2 * 2, :w2 * 2]
        out.append(0.25 * (a[0::2, 0::2] + a[1::2, 0::2] + a[0::2, 1::2] + a[1::2, 1::2]))
    return out


def sample_lod(chain, x, y, lvl):
    """Trilinear: chain of mips, pixel coords at level 0, per-pixel level."""
    l0 = np.floor(lvl).astype(np.int32); t = (lvl - l0)[..., None]
    out = np.zeros(x.shape + (chain[0].shape[2],), np.float32)
    for l in range(len(chain)):
        for li, wgt in ((l, None),):
            m0 = l0 == l
            m1 = (l0 + 1 == l)
            if not (m0.any() or m1.any()):
                continue
            s = 2.0 ** l
            v = bilinear(chain[l], x / s, y / s)
            out += np.where(m0[..., None], v * (1 - t), 0) + np.where(m1[..., None], v * t, 0)
    return out


def gather_pattern(n, blades, rot):
    rings = max(1, round(math.sqrt(n / math.pi)))
    radii = [(i + 0.5) / rings for i in range(rings)]
    total = sum(radii)
    counts = [max(1, round(n * r / total)) for r in radii]
    counts[-1] = max(1, counts[-1] + n - sum(counts))
    pts = []
    for i, r in enumerate(radii):
        a0 = i * 2.39996323
        for j in range(counts[i]):
            a = a0 + 2 * math.pi * j / counts[i]
            x, y = r * math.cos(a), r * math.sin(a)
            if blades >= 3:
                seg = 2 * math.pi / blades
                th = math.atan2(y, x)
                aa = ((th - rot) % seg + seg) % seg - seg / 2
                k = math.cos(math.pi / blades) / math.cos(aa)
                x *= k; y *= k
            pts.append((x, y))
    return np.array(pts[:n], np.float32)


def shape_norm(lx, ly, qx, qy, blades, rot, cats, open_):
    r = np.sqrt(lx * lx + ly * ly)
    n = r
    if blades >= 3:
        seg = 2 * math.pi / blades
        th = np.arctan2(ly, lx) - rot
        a = np.mod(th, seg) - seg / 2
        n = r * np.cos(a) / math.cos(math.pi / blades)
    return np.maximum(n, np.sqrt((lx * open_ + cats * qx) ** 2 + (ly * open_ + cats * qy) ** 2))


def value_noise(gx, gy, seed):
    def hash_(x, y, s):
        h = (x.astype(np.uint32) * np.uint32(0x8da6b343)) ^ (y.astype(np.uint32) * np.uint32(0xd8163841)) ^ np.uint32((s * 0xcb1ab31f) & 0xffffffff)
        h ^= h >> np.uint32(13); h *= np.uint32(0x5bd1e995); h ^= h >> np.uint32(15)
        return (h & np.uint32(0xffffff)).astype(np.float32) / 16777215.0
    ix = np.floor(gx); iy = np.floor(gy); tx = gx - ix; ty = gy - iy
    tx = tx * tx * (3 - 2 * tx); ty = ty * ty * (3 - 2 * ty)
    ax = (ix + 65536).astype(np.int64); ay = (iy + 65536).astype(np.int64)
    v00 = hash_(ax, ay, seed); v10 = hash_(ax + 1, ay, seed); v01 = hash_(ax, ay + 1, seed); v11 = hash_(ax + 1, ay + 1, seed)
    return (v00 * (1 - tx) + v10 * tx) * (1 - ty) + (v01 * (1 - tx) + v11 * tx) * ty


def film_look(lin, halo, F, seed=1234):
    h, w = lin.shape[:2]
    lin = lin + F[6, 0] * F[6, 1:4] * halo
    c = np.stack([lin @ F[0, :3], lin @ F[1, :3], lin @ F[2, :3]], -1) * F[3, :3]
    bw = F[3, 3] > 0.5
    if bw:
        c = np.repeat((c @ F[4, :3])[..., None], 3, -1)
    l = luma(c)[..., None]
    c = np.maximum(l + (c - l) * F[4, 3], 0)
    x = np.log2(np.maximum(c, 1e-6) / 0.18)
    k = F[5, 0] * 0.34
    xs = np.where(x > 0, x * F[5, 2], x * F[7, 2])
    y = 0.5 + 0.5 * np.tanh(k * xs)
    y = F[5, 1] + (1 - F[5, 1]) * y
    size = F[7, 1] * h / 1080.0
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    gx = (xx + 0.5) / max(size, 0.5); gy = (yy + 0.5) / max(size, 0.5)
    amp = F[5, 3] * 0.16
    mono = value_noise(gx, gy, seed) + value_noise(gx * 2.1 + 17, gy * 2.1 + 17, seed + 1) * 0.5 - 0.75
    if bw:
        n = np.repeat(mono[..., None], 3, -1)
    else:
        chroma = np.stack([value_noise(gx + o, gy + o, seed + s) for o, s in ((31, 2), (57, 3), (83, 4))], -1) - 0.5
        n = mono[..., None] + 0.35 * chroma
    y = y + amp * n * 4 * y * (1 - y)
    return np.clip(y, 0, 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("photo"); ap.add_argument("out")
    ap.add_argument("--width", type=int, default=1440)
    ap.add_argument("--lens", default="85")
    ap.add_argument("--stock", default="Rochester Portrait 400")
    ap.add_argument("--samples", type=int, default=96)
    ap.add_argument("--highlight", type=float, default=2.0, help="JPEG highlight expansion (linearize.comp uHighlight)")
    ap.add_argument("--focus-m", type=float, default=2.0)
    ap.add_argument("--near-m", type=float, default=0.8)
    ap.add_argument("--far-m", type=float, default=50.0)
    ap.add_argument("--focus-at", default="0.5,0.5", help="focus point, fractions of width,height")
    ap.add_argument("--model", default="/Volumes/Mike 1/relens_models/da2s_fp32.onnx")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    name, focal, fmax, blades, rot, cats, soap, vig, lca, obs = LENSES[a.lens]
    N = fmax

    img = Image.open(a.photo).convert("RGB")
    h = round(img.height * a.width / img.width)
    srgb = np.asarray(img.resize((a.width, h), Image.LANCZOS)).astype(np.float32) / 255.0
    w = a.width
    # linearize (+ the highlight expansion)
    lin = to_lin(srgb)
    m = lin.max(-1, keepdims=True)
    t = np.clip((m - 0.9) / 0.1, 0, 1); t = t * t * (3 - 2 * t)
    lin = lin * (1 + a.highlight * t ** 2)

    disp_lo, guide = depth_anything(srgb, a.model)
    Image.fromarray((disp_lo * 255).astype(np.uint8)).save(os.path.join(a.out, "ref_depth_lo.png"))
    disp = jbu(disp_lo, guide, lin)
    Image.fromarray((np.clip(disp, 0, 1) * 255).astype(np.uint8)).save(os.path.join(a.out, "ref_depth_jbu.png"))

    fxp, fyp = (float(v) for v in a.focus_at.split(","))
    lh, lw = disp_lo.shape
    cy, cx = int(fyp * lh), int(fxp * lw)
    focus_disp = float(np.median(disp_lo[max(0, cy - lh // 10):cy + lh // 10, max(0, cx - lw // 10):cx + lw // 10]))
    d_far, d_near = 1 / a.far_m, 1 / a.near_m
    f = focal / 1000; s = max(a.focus_m, f * 1.01)
    coc_per_d = (f / N) * f / (1 - f / s) / 0.036 * w * 0.5
    D = d_far + (d_near - d_far) * disp
    Df = d_far + (d_near - d_far) * focus_disp
    max_coc = h * 0.08
    coc = np.clip(coc_per_d * (Df - D), -max_coc, max_coc).astype(np.float32)
    work = np.concatenate([lin, coc[..., None]], -1).astype(np.float32)
    levels = min(7, int(math.floor(math.log2(max(w, h)))) + 1)
    chain = mips(work, levels)

    # tiles + separable dilate
    th_, tw_ = (h + 15) // 16, (w + 15) // 16
    pad = np.zeros((th_ * 16, tw_ * 16), np.float32)
    pad[:h, :w] = np.abs(coc)
    tiles = pad[::2, ::2].reshape(th_, 8, tw_, 8).max(axis=(1, 3))
    radius = int(math.ceil(max_coc / 16)) + 1
    for axis in (1, 0):
        src = tiles.copy(); out = src.copy()
        for k in range(1, radius + 1):
            gap = (k - 1) * 16
            for sgn in (1, -1):
                sh = np.roll(src, sgn * k, axis=axis)
                # clamp-to-edge rather than wrap
                if axis == 1:
                    if sgn > 0: sh[:, :k] = src[:, :1]
                    else: sh[:, -k:] = src[:, -1:]
                else:
                    if sgn > 0: sh[:k] = src[:1]
                    else: sh[-k:] = src[-1:]
                out = np.where(sh >= gap, np.maximum(out, sh), out)
        tiles = out

    # gather
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    pcx, pcy = xx + 0.5, yy + 0.5
    tfx = pcx / 16 - 0.5; tfy = pcy / 16 - 0.5
    t0x = np.floor(tfx).astype(np.int32); t0y = np.floor(tfy).astype(np.int32)
    frx = tfx - t0x; fry = tfy - t0y
    T = lambda x_, y_: tiles[np.clip(y_, 0, th_ - 1), np.clip(x_, 0, tw_ - 1)]
    reach = (T(t0x, t0y) * (1 - frx) + T(t0x + 1, t0y) * frx) * (1 - fry) + (T(t0x, t0y + 1) * (1 - frx) + T(t0x + 1, t0y + 1) * frx) * fry
    R = np.maximum(np.abs(coc), reach)
    area = math.pi * R * R / a.samples
    lvl = np.clip(0.5 * np.log2(np.maximum(area, 1e-6)), 0, levels - 1)
    rotp = np.mod(52.9829189 * np.mod(pcx * 0.06711056 + pcy * 0.00583715, 1.0), 1.0) * 2 * math.pi
    cr, sr = np.cos(rotp), np.sin(rotp)
    r0 = np.maximum(np.abs(coc), 0.5)
    w0 = 1 / (math.pi * r0 * r0)
    acc = lin * w0[..., None]; wsum = w0.copy()
    pat = gather_pattern(a.samples, blades, rot)
    axis_x, axis_y = w / 2, h / 2; half_diag = math.hypot(w / 2, h / 2)
    open_ = fmax / N
    soap_norm = 1 / (1 + soap * 0.25)
    for j, (ux, uy) in enumerate(pat):
        vx = (cr * ux - sr * uy) * R; vy = (sr * ux + cr * uy) * R
        psx, psy = pcx + vx, pcy + vy
        c = sample_lod(chain, psx, psy, lvl)
        rs = np.abs(c[..., 3])
        tt = np.clip(c[..., 3] - coc, 0, 1); tt = tt * tt * (3 - 2 * tt)
        rs = rs + (np.minimum(rs, np.abs(coc)) - rs) * tt
        rs = np.maximum(rs, 0.5)
        lx, ly = -vx / rs, -vy / rs
        qx, qy = (psx - axis_x) / half_diag, (psy - axis_y) / half_diag
        cov = np.clip((1 - shape_norm(lx, ly, qx, qy, blades, rot, cats, open_)) * rs + 0.5, 0, 1)
        lr = np.sqrt(lx * lx + ly * ly)
        cov *= np.clip((lr - obs) * rs + 0.5, 0, 1)
        rim = (1 + soap * np.minimum(lr, 1) ** 6) * soap_norm
        wt = cov * rim * area / (math.pi * rs * rs)
        acc += c[..., :3] * wt[..., None]; wsum += wt
    focused = np.abs(R) < 0.75
    blur = np.where(focused[..., None], lin, acc / wsum[..., None])
    if os.environ.get("RELENS_DEBUG"):
        pc = lambda v: np.percentile(v, [50, 90, 99, 99.9]).round(3).tolist()
        print("lin", pc(lin[..., 1]), "blur", pc(blur[..., 1]), "srgb", pc(srgb[..., 1]),
              "chain max", [float(c[..., 1].max()) for c in chain])

    # develop
    F = STOCKS[a.stock]
    q2 = ((pcx - axis_x) ** 2 + (pcy - axis_y) ** 2) / half_diag ** 2
    v = 1 - vig * open_ ** 1.5 * q2
    out = np.empty_like(blur)
    out[..., 0] = bilinear(blur[..., :1], axis_x + (pcx - axis_x) * (1 - lca), axis_y + (pcy - axis_y) * (1 - lca))[..., 0]
    out[..., 1] = blur[..., 1]
    out[..., 2] = bilinear(blur[..., 2:3], axis_x + (pcx - axis_x) * (1 + lca), axis_y + (pcy - axis_y) * (1 + lca))[..., 0]
    out *= v[..., None]
    halo = np.zeros_like(out)
    if F[6, 0] > 0:
        rad = h * 0.012
        for k in range(32):
            r_ = math.sqrt((k + 0.5) / 32); th = k * 2.39996323
            halo += np.maximum(bilinear(blur, pcx + r_ * math.cos(th) * rad, pcy + r_ * math.sin(th) * rad) - 0.9, 0)
        halo /= 32
    print_ = film_look(out, halo, F)
    tag = f"{a.lens}mm_{a.stock.replace(' ', '_')}"
    Image.fromarray((print_ * 255 + 0.5).astype(np.uint8)).save(os.path.join(a.out, f"ref_{tag}.jpg"), quality=95)
    Image.fromarray((np.clip(np.abs(coc) / max_coc, 0, 1) * 255).astype(np.uint8)).save(os.path.join(a.out, "ref_coc.png"))
    print(json.dumps({"size": [w, h], "focus_disparity": focus_disp, "coc_px_per_dioptre": coc_per_d,
                      "max_coc": float(np.abs(coc).max()), "lens": name, "stock": a.stock}))


if __name__ == "__main__":
    main()
