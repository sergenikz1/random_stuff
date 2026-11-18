"""Общие примитивы и экспорт для лоу-поли моделей в стиле PS1.

Модели собираются из призм, конусов, «лейзов» (тел вращения), ромбов и пластин.
Цвет каждого треугольника — имя из палитры модели. При экспорте палитра
превращается в текстуру 64x64 (клетка 8x8 на цвет), а UV треугольника смотрят
в центр своей клетки.
"""
import html
import json
import math
import os
import struct
import zlib

CELL = 8
GRID = 8  # 8x8 клеток = текстура 64x64


def color_uv(names, name):
    i = names.index(name)
    cx, cy = i % GRID, i // GRID
    return ((cx + 0.5) / GRID, (cy + 0.5) / GRID)


# ---------------------------------------------------------------- векторы

def add(a, b):
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def mul(a, s):
    return (a[0] * s, a[1] * s, a[2] * s)


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def length(a):
    return math.sqrt(dot(a, a))


def norm(a):
    l = length(a)
    return (a[0] / l, a[1] / l, a[2] / l) if l > 1e-12 else (0.0, 0.0, 0.0)


def mat_apply(m, p):
    return (
        m[0][0] * p[0] + m[0][1] * p[1] + m[0][2] * p[2],
        m[1][0] * p[0] + m[1][1] * p[1] + m[1][2] * p[2],
        m[2][0] * p[0] + m[2][1] * p[1] + m[2][2] * p[2],
    )


def mat_mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def rot_x(deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return [[1, 0, 0], [0, c, -s], [0, s, c]]


def rot_y(deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return [[c, 0, s], [0, 1, 0], [-s, 0, c]]


def rot_z(deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return [[c, -s, 0], [s, c, 0], [0, 0, 1]]


def basis_from_y(direction, hint=(0.0, 0.0, 1.0)):
    """Матрица, переводящая локальную ось Y в direction (Z локально — ближе к hint)."""
    y = norm(direction)
    x = cross(y, hint)
    if length(x) < 1e-6:
        x = cross(y, (1.0, 0.0, 0.0))
    x = norm(x)
    z = cross(x, y)
    return [[x[0], y[0], z[0]], [x[1], y[1], z[1]], [x[2], y[2], z[2]]]


# ---------------------------------------------------------------- меш

class Mesh:
    """Треугольники с цветом. Ориентацию граней задаёт опорная точка «внутри»."""

    def __init__(self):
        self.tris = []  # (p0, p1, p2, color)

    def tri(self, a, b, c, color, inside):
        n = cross(sub(b, a), sub(c, a))
        if length(n) < 1e-10:
            return
        center = mul(add(add(a, b), c), 1.0 / 3.0)
        if dot(n, sub(center, inside)) < 0:
            b, c = c, b
        self.tris.append((a, b, c, color))

    def quad(self, a, b, c, d, color, inside):
        self.tri(a, b, c, color, inside)
        self.tri(a, c, d, color, inside)

    def merge(self, other, m=None, t=(0.0, 0.0, 0.0)):
        m = m or [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
        for a, b, c, col in other.tris:
            self.tris.append((add(mat_apply(m, a), t), add(mat_apply(m, b), t), add(mat_apply(m, c), t), col))


def lathe(mesh, base, axis, profile, n, colors, hint=(0.0, 0.0, 1.0), phase=0.5,
          cap_start=True, cap_end=True):
    """Тело вращения вдоль axis.

    profile: [(h, rx, rz), ...] — высота вдоль оси и полуоси эллиптического сечения.
    colors: один цвет или список цветов на каждый пояс (len(profile)-1).
    """
    m = basis_from_y(axis, hint)
    ex = (m[0][0], m[1][0], m[2][0])
    ey = (m[0][1], m[1][1], m[2][1])
    ez = (m[0][2], m[1][2], m[2][2])
    if isinstance(colors, str):
        colors = [colors] * (len(profile) - 1)
    rings = []
    for h, rx, rz in profile:
        c = add(base, mul(ey, h))
        if rx <= 1e-9 and rz <= 1e-9:
            rings.append([c] * n)
            continue
        ring = []
        for k in range(n):
            a = 2 * math.pi * (k + phase) / n
            ring.append(add(c, add(mul(ex, math.cos(a) * rx), mul(ez, math.sin(a) * rz))))
        rings.append(ring)
    for i in range(len(profile) - 1):
        inside = add(base, mul(ey, (profile[i][0] + profile[i + 1][0]) / 2))
        r0, r1 = rings[i], rings[i + 1]
        for k in range(n):
            k2 = (k + 1) % n
            mesh.tri(r0[k], r0[k2], r1[k2], colors[i], inside)
            mesh.tri(r0[k], r1[k2], r1[k], colors[i], inside)
    h0, h1 = profile[0][0], profile[-1][0]
    mid = add(base, mul(ey, (h0 + h1) / 2))
    for idx, enabled, col in ((0, cap_start, colors[0]), (-1, cap_end, colors[-1])):
        if not enabled or (profile[idx][1] <= 1e-9 and profile[idx][2] <= 1e-9):
            continue
        ring = rings[idx]
        ctr = add(base, mul(ey, profile[idx][0]))
        inside = add(ctr, mul(sub(mid, ctr), 0.5)) if length(sub(mid, ctr)) > 1e-9 else mid
        for k in range(n):
            mesh.tri(ctr, ring[k], ring[(k + 1) % n], col, inside)


def tube(mesh, p0, p1, r0, r1, n, color, hint=(0.0, 0.0, 1.0), phase=0.5, caps=True):
    d = sub(p1, p0)
    h = length(d)
    r0 = r0 if isinstance(r0, tuple) else (r0, r0)
    r1 = r1 if isinstance(r1, tuple) else (r1, r1)
    lathe(mesh, p0, d, [(0.0, r0[0], r0[1]), (h, r1[0], r1[1])], n, color, hint, phase, caps, caps)


def box(mesh, center, size, color, m=None):
    hx, hy, hz = size[0] / 2, size[1] / 2, size[2] / 2
    m = m or [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
    pts = [add(center, mat_apply(m, (sx * hx, sy * hy, sz * hz)))
           for sx in (-1, 1) for sy in (-1, 1) for sz in (-1, 1)]
    faces = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]
    for a, b, c, d in faces:
        mesh.quad(pts[a], pts[b], pts[c], pts[d], color, center)


def diamond(mesh, center, half, color, m=None):
    """Октаэдр (ромб) с полуосями half=(x, y, z)."""
    m = m or [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
    hx, hy, hz = half
    px = add(center, mat_apply(m, (hx, 0, 0)))
    nx = add(center, mat_apply(m, (-hx, 0, 0)))
    py = add(center, mat_apply(m, (0, hy, 0)))
    ny = add(center, mat_apply(m, (0, -hy, 0)))
    pz = add(center, mat_apply(m, (0, 0, hz)))
    nz = add(center, mat_apply(m, (0, 0, -hz)))
    for a in (px, nx):
        for b in (py, ny):
            for c in (pz, nz):
                mesh.tri(a, b, c, color, center)


def chain(mesh, points, radii, n, color, hint=(0.0, 0.0, 1.0)):
    """Изогнутая трубка (рога): цепочка конусов по точкам."""
    for i in range(len(points) - 1):
        tube(mesh, points[i], points[i + 1], radii[i], max(radii[i + 1], 0.0), n, color, hint)


# ---------------------------------------------------------------- плоские щиты и эмблемы

def inset_polygon(poly, d):
    """Сдвиг выпуклого многоугольника внутрь на d."""
    n = len(poly)
    area = sum(poly[i][0] * poly[(i + 1) % n][1] - poly[(i + 1) % n][0] * poly[i][1] for i in range(n))
    sign = 1 if area > 0 else -1
    normals = []
    for i in range(n):
        a, b = poly[i], poly[(i + 1) % n]
        ex, ey = b[0] - a[0], b[1] - a[1]
        l = math.hypot(ex, ey)
        normals.append((-ey / l * sign, ex / l * sign))  # внутрь
    out = []
    for i in range(n):
        n1, n2 = normals[i - 1], normals[i]
        k = 1 + n1[0] * n2[0] + n1[1] * n2[1]
        out.append((poly[i][0] + d * (n1[0] + n2[0]) / k, poly[i][1] + d * (n1[1] + n2[1]) / k))
    return out


def boundary_distance(poly, direction):
    """Расстояние от (0,0) до края выпуклого многоугольника по направлению."""
    dx, dy = direction
    best = None
    n = len(poly)
    for i in range(n):
        ax, ay = poly[i]
        bx, by = poly[(i + 1) % n]
        ex, ey = bx - ax, by - ay
        den = dx * ey - dy * ex
        if abs(den) < 1e-12:
            continue
        t = (ax * ey - ay * ex) / den
        s = (ax * dy - ay * dx) / den
        if t > 0 and -1e-9 <= s <= 1 + 1e-9:
            best = t if best is None else min(best, t)
    return best


def bulge_height(poly, p, thick, bulge):
    """Высота лицевой поверхности «веерной» пластины (центр выпуклый) в точке p."""
    r = math.hypot(p[0], p[1])
    if r < 1e-9:
        return thick + bulge
    big = boundary_distance(poly, (p[0] / r, p[1] / r))
    return thick + bulge * (1 - r / big)


def plate(mesh, poly, z_of, z_center, back_z, color, side_color=None, back_color=None):
    """Пластина: лицевая сторона веером к центру, плоская задняя и бортики."""
    side_color = side_color or color
    back_color = back_color or side_color
    n = len(poly)
    front = [(x, y, z_of((x, y))) for x, y in poly]
    back = [(x, y, back_z) for x, y in poly]
    fc = (0.0, 0.0, z_center)
    inside = (0.0, 0.0, (z_center + back_z) / 2 - 1.0)
    for i in range(n):
        j = (i + 1) % n
        mesh.tri(fc, front[i], front[j], color, inside)
    inside_back = (0.0, 0.0, back_z + 1.0)
    bc = (0.0, 0.0, back_z)
    for i in range(n):
        j = (i + 1) % n
        mesh.tri(bc, back[i], back[j], back_color, inside_back)
        mid = ((poly[i][0] + poly[j][0]) * 0.3, (poly[i][1] + poly[j][1]) * 0.3, (front[i][2] + back_z) / 2)
        mesh.quad(back[i], back[j], front[j], front[i], side_color, mid)


def star(points, r_out, r_in, rot=0.0):
    out = []
    for k in range(points * 2):
        r = r_out if k % 2 == 0 else r_in
        a = math.pi / points * k + rot
        out.append((math.cos(a) * r, math.sin(a) * r))
    return out


# ---------------------------------------------------------------- экспорт

def face_normal(a, b, c):
    return norm(cross(sub(b, a), sub(c, a)))


def write_png(path, width, height, rgb_rows):
    raw = b"".join(b"\x00" + bytes(row) for row in rgb_rows)

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)
    return png


def palette_png(path, palette):
    names = list(palette)
    size = CELL * GRID
    rows = []
    for y in range(size):
        row = []
        for x in range(size):
            i = (y // CELL) * GRID + (x // CELL)
            row.extend(palette[names[i]] if i < len(names) else (255, 0, 255))
        rows.append(row)
    return write_png(path, size, size, rows)


def write_glb(path, mesh, png_bytes, palette, name):
    names = list(palette)
    pos, nrm, uv = bytearray(), bytearray(), bytearray()
    mn = [1e9] * 3
    mx = [-1e9] * 3
    for a, b, c, col in mesh.tris:
        n = face_normal(a, b, c)
        u = color_uv(names, col)
        for p in (a, b, c):
            pos += struct.pack("<3f", *p)
            nrm += struct.pack("<3f", *n)
            uv += struct.pack("<2f", *u)
            for i in range(3):
                mn[i] = min(mn[i], p[i])
                mx[i] = max(mx[i], p[i])
    count = len(mesh.tris) * 3

    def pad4(b, fill=b"\x00"):
        return b + fill * ((4 - len(b) % 4) % 4)

    img = pad4(png_bytes)
    blobs = [bytes(pos), bytes(nrm), bytes(uv), img]
    offsets, off = [], 0
    for blob in blobs:
        offsets.append(off)
        off += len(blob)
    bin_chunk = b"".join(blobs)
    gltf = {
        "asset": {"version": "2.0", "generator": "random_stuff/ps1-models"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"name": name, "mesh": 0}],
        "meshes": [{"name": name, "primitives": [{
            "attributes": {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2}, "material": 0}]}],
        "materials": [{
            "name": name + "Palette",
            "pbrMetallicRoughness": {"baseColorTexture": {"index": 0}, "metallicFactor": 0.0,
                                     "roughnessFactor": 1.0},
        }],
        "textures": [{"sampler": 0, "source": 0}],
        "samplers": [{"magFilter": 9728, "minFilter": 9728, "wrapS": 33071, "wrapT": 33071}],
        "images": [{"name": "palette", "mimeType": "image/png", "bufferView": 3}],
        "buffers": [{"byteLength": len(bin_chunk)}],
        "bufferViews": [
            {"buffer": 0, "byteOffset": offsets[0], "byteLength": len(blobs[0]), "target": 34962},
            {"buffer": 0, "byteOffset": offsets[1], "byteLength": len(blobs[1]), "target": 34962},
            {"buffer": 0, "byteOffset": offsets[2], "byteLength": len(blobs[2]), "target": 34962},
            {"buffer": 0, "byteOffset": offsets[3], "byteLength": len(png_bytes)},
        ],
        "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": count, "type": "VEC3", "min": mn, "max": mx},
            {"bufferView": 1, "componentType": 5126, "count": count, "type": "VEC3"},
            {"bufferView": 2, "componentType": 5126, "count": count, "type": "VEC2"},
        ],
    }
    js = pad4(json.dumps(gltf, separators=(",", ":")).encode(), b" ")
    total = 12 + 8 + len(js) + 8 + len(bin_chunk)
    with open(path, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, total))
        f.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
        f.write(struct.pack("<II", len(bin_chunk), 0x004E4942) + bin_chunk)


def write_obj(obj_path, mtl_name, mesh, palette, name):
    names = list(palette)
    lines = [f"# PS1-style low poly {name}, generated by ps1-models/build.py",
             f"mtllib {mtl_name}", f"o {name}"]
    for cname in names:
        u, v = color_uv(names, cname)
        lines.append(f"vt {u:.5f} {1 - v:.5f}")  # в OBJ V смотрит вверх
    lines.append(f"usemtl {name}Palette")
    lines.append("s off")
    vi = 1
    vn = 1
    vlines, flines, nlines = [], [], []
    for a, b, c, col in mesh.tris:
        n = face_normal(a, b, c)
        nlines.append(f"vn {n[0]:.4f} {n[1]:.4f} {n[2]:.4f}")
        t = names.index(col) + 1
        for p in (a, b, c):
            vlines.append(f"v {p[0]:.4f} {p[1]:.4f} {p[2]:.4f}")
        flines.append(f"f {vi}/{t}/{vn} {vi + 1}/{t}/{vn} {vi + 2}/{t}/{vn}")
        vi += 3
        vn += 1
    with open(obj_path, "w") as f:
        f.write("\n".join(lines[:3] + vlines + lines[3:3 + len(names)] + nlines
                          + lines[3 + len(names):] + flines) + "\n")


def write_mtl(path, name):
    with open(path, "w") as f:
        f.write(f"newmtl {name}Palette\nKa 0 0 0\nKd 1 1 1\nKs 0 0 0\nillum 1\nmap_Kd palette.png\n")


def mesh_json(mesh, palette, names_ru):
    names = list(palette)
    """Компактные данные для вьюера: координаты в мм и индексы цветов."""
    pos, col = [], []
    for a, b, c, name in mesh.tris:
        for p in (a, b, c):
            pos.extend(int(round(x * 1000)) for x in p)
        col.append(names.index(name))
    return {"palette": [palette[n] for n in names], "names": names,
            "names_ru": [names_ru.get(n, n) for n in names], "pos": pos, "col": col}


def export_model(model, out_dir, template_path):
    """Собирает модель и пишет glb, obj/mtl, палитру и страницу-просмотрщик."""
    os.makedirs(out_dir, exist_ok=True)
    mesh = model.build()
    pal = model.PALETTE
    name = model.NAME
    png = palette_png(os.path.join(out_dir, "palette.png"), pal)
    write_glb(os.path.join(out_dir, f"{model.FILE}.glb"), mesh, png, pal, name)
    write_obj(os.path.join(out_dir, f"{model.FILE}.obj"), f"{model.FILE}.mtl", mesh, pal, name)
    write_mtl(os.path.join(out_dir, f"{model.FILE}.mtl"), name)
    with open(template_path, encoding="utf-8") as f:
        page = f.read()
    meta = {"file": model.FILE, "folder": os.path.basename(os.path.normpath(out_dir))}
    page = (page.replace("__TITLE__", html.escape(model.TITLE))
                .replace("__TITLE_HTML__", model.TITLE_HTML)
                .replace("__LEDE__", model.LEDE)
                .replace("__FILE__", meta["file"])
                .replace("__FOLDER__", meta["folder"])
                .replace("__MESH_JSON__", json.dumps(mesh_json(mesh, pal, model.NAMES_RU), ensure_ascii=False,
                                                     separators=(",", ":"))))
    with open(os.path.join(out_dir, "viewer.html"), "w", encoding="utf-8") as f:
        f.write(page)
    ys = [p[1] for t in mesh.tris for p in t[:3]]
    return len(mesh.tris), min(ys), max(ys)
