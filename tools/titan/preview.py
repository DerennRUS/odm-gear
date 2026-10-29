"""Software preview of exported titan data: skinning + textured rasterization (validates the export format)."""
import json, struct, sys, math
import numpy as np
from PIL import Image

D = sys.argv[1]          # dir with titan_model.json / titan_anims.bin
TEX = sys.argv[2]        # dir with textures
OUT = sys.argv[3]
combos = json.loads(sys.argv[4])   # list of dicts: body, head, hair, anim, frame, yaw, haircolor, eye, gray

model = json.load(open(D + '/titan_model.json'))
blob = open(D + '/titan_anims.bin', 'rb').read()
nb = len(model['bones'])
offsets = {}
off = 0
for a in model['anims']:
    offsets[a['name']] = (off, a['frames'])
    off += a['frames'] * nb * 12
mats_all = np.frombuffer(blob, dtype='>f4').astype(np.float32)
meshes = {m['name']: m for m in model['meshes']}
HAIRTEX = {'armin': 'HairArmin', 'boy1': 'HairBoy1', 'boy2': 'HairBoy2', 'boy3': 'HairBoy3', 'eren': 'HairBoy1', 'girl2': 'HairGirl2',
           'jean': 'HairJean', 'levi': 'HairLevi', 'marco': 'HairMarco', 'mikasa': 'HairMikasa', 'mike': 'HairMike', 'petra': 'HairPetra'}
texcache = {}


def tex(name):
    if name not in texcache:
        im = Image.open(f'{TEX}/aottg_{name}.png').convert('RGBA')
        texcache[name] = np.asarray(im).astype(np.float32) / 255.0
    return texcache[name]


def skin(mesh, anim, frame):
    o, n = offsets[anim]
    frame = min(frame, n - 1)
    M = mats_all[o + frame * nb * 12: o + (frame + 1) * nb * 12].reshape(nb, 3, 4)
    P = np.array(mesh['pos'], dtype=np.float32)
    out = np.zeros_like(P)
    Ph = np.concatenate([P, np.ones((len(P), 1), np.float32)], 1)
    for i, ws in enumerate(mesh['weights']):
        acc = np.zeros(3, np.float32)
        for b, w in ws:
            acc += w * (M[b] @ Ph[i])
        out[i] = acc
    return out


def render(c, W=360, H=480):
    img = np.zeros((H, W, 3), np.float32) + np.array([0.75, 0.82, 0.9], np.float32)
    zb = np.full((H, W), -1e9, np.float32)
    parts = []
    btex = 'TitanBody2Tex' if c['body'] == 4 else 'TitanBody1Tex'
    g = c.get('gray', 1.0)
    parts.append((meshes[f"body_{c['body']}"], btex, (g, g, g), None))
    parts.append((meshes[f"head_{c['head']}"], btex, (g, g, g), None))
    e = c.get('eye', 0)
    parts.append((meshes[f"eyes_{c['head']}"], 'TitanEyesTex', (1, 1, 1), (0.25 * (e // 8), -0.125 * (e % 8))))
    if c.get('hair'):
        hc = [v / 255 for v in c.get('haircolor', [90, 56, 37])]
        parts.append((meshes[f"hair_{c['hair']}_0@head_{c['head']}"], HAIRTEX[c['hair']] + 'Tex', hc, None))
    yaw = math.radians(c.get('yaw', 0))
    Ry = np.array([[math.cos(yaw), 0, math.sin(yaw)], [0, 1, 0], [-math.sin(yaw), 0, math.cos(yaw)]], np.float32)
    allpts = []
    skinned = []
    for m, t, col, uvoff in parts:
        p = skin(m, c.get('anim', 'idle'), c.get('frame', 0)) @ Ry.T
        skinned.append(p)
        allpts.append(p)
    allp = np.concatenate(allpts)
    mn, mx = allp.min(0), allp.max(0)
    scale = 0.9 * min(W / (mx[0] - mn[0] + 1e-6), H / (mx[1] - mn[1] + 1e-6))
    cx = (mn[0] + mx[0]) / 2
    light = np.array([0.4, 0.6, 0.7]); light /= np.linalg.norm(light)
    for (m, t, col, uvoff), p in zip(parts, skinned):
        T = tex(t); th, tw = T.shape[:2]
        tris = m['tris']
        for k in range(0, len(tris), 3):
            idx = [tris[k + j][0] for j in range(3)]
            uv = np.array([[tris[k + j][1], tris[k + j][2]] for j in range(3)], np.float32)
            if uvoff:
                uv = uv + np.array(uvoff, np.float32)
            v = p[idx]
            nrm = np.cross(v[1] - v[0], v[2] - v[0]); ln = np.linalg.norm(nrm)
            if ln < 1e-9:
                continue
            nrm /= ln
            shade = 0.45 + 0.55 * abs(float(nrm @ light))
            sx = (v[:, 0] - cx) * scale + W / 2
            sy = H - ((v[:, 1] - mn[1]) * scale + H * 0.05)
            x0, x1 = int(max(0, np.floor(sx.min()))), int(min(W - 1, np.ceil(sx.max())))
            y0, y1 = int(max(0, np.floor(sy.min()))), int(min(H - 1, np.ceil(sy.max())))
            if x1 < x0 or y1 < y0:
                continue
            xs, ys = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
            d = (sy[1] - sy[2]) * (sx[0] - sx[2]) + (sx[2] - sx[1]) * (sy[0] - sy[2])
            if abs(d) < 1e-9:
                continue
            a = ((sy[1] - sy[2]) * (xs - sx[2]) + (sx[2] - sx[1]) * (ys - sy[2])) / d
            b = ((sy[2] - sy[0]) * (xs - sx[2]) + (sx[0] - sx[2]) * (ys - sy[2])) / d
            cc = 1 - a - b
            inside = (a >= 0) & (b >= 0) & (cc >= 0)
            if not inside.any():
                continue
            z = a * v[0, 2] + b * v[1, 2] + cc * v[2, 2]
            u = (a * uv[0, 0] + b * uv[1, 0] + cc * uv[2, 0]) % 1.0
            vv = (a * uv[0, 1] + b * uv[1, 1] + cc * uv[2, 1]) % 1.0
            tx = np.clip((u * tw).astype(int), 0, tw - 1); ty = np.clip(((1 - vv) * th).astype(int), 0, th - 1)
            texel = T[ty, tx]
            sub_z = zb[y0:y1 + 1, x0:x1 + 1]
            mask = inside & (z > sub_z) & (texel[..., 3] > 0.5)
            rgb = texel[..., :3] * np.array(col, np.float32) * shade
            region = img[y0:y1 + 1, x0:x1 + 1]
            region[mask] = rgb[mask]
            sub_z[mask] = z[mask]
    return (np.clip(img, 0, 1) * 255).astype(np.uint8)


tiles = [render(c) for c in combos]
cols = min(4, len(tiles))
rows = (len(tiles) + cols - 1) // cols
H, W = tiles[0].shape[:2]
sheet = np.zeros((rows * H, cols * W, 3), np.uint8) + 255
for i, t in enumerate(tiles):
    r, cl = divmod(i, cols)
    sheet[r * H:(r + 1) * H, cl * W:(cl + 1) * W] = t
Image.fromarray(sheet).save(OUT)
print('saved', OUT)
