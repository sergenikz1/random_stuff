#!/usr/bin/env python3
"""Лоу-поли рыцарь в стиле PS1.

Модель собирается из простых примитивов (призмы, конусы, «лейзы», ромбы)
и выгружается в knight.glb, knight.obj + knight.mtl, palette.png
и viewer.html (просмотрщик в духе PS1, открывается в браузере).
Цвета берутся из маленькой палитры 64x64: каждый цвет занимает клетку 8x8,
UV всех вершин треугольника смотрят в центр клетки. Так модель можно
открыть в Blender, Godot, Unity или three.js и перекрасить палитру
в любом редакторе картинок.

Нужен только стандартный Python 3.8+. Запуск:
    python3 build_knight.py            # файлы появятся рядом со скриптом
    python3 build_knight.py out_dir    # или в указанной папке
"""
import json
import math
import os
import struct
import sys
import zlib

# ---------------------------------------------------------------- палитра

PALETTE = {
    "steel": (150, 162, 186),
    "steel_dark": (88, 98, 124),
    "steel_light": (204, 214, 232),
    "gold": (226, 176, 66),
    "gold_dark": (150, 100, 34),
    "red": (200, 30, 26),
    "red_dark": (118, 14, 14),
    "mail": (148, 150, 156),
    "cloth": (52, 60, 102),
    "leather": (108, 64, 30),
    "leather_dark": (64, 38, 18),
    "black": (16, 16, 22),
    "blade": (206, 220, 242),
}
COLOR_NAMES = list(PALETTE)
CELL = 8
GRID = 8  # 8x8 клеток = текстура 64x64


def color_uv(name):
    i = COLOR_NAMES.index(name)
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


# ---------------------------------------------------------------- сам рыцарь

def build_leg(mesh, s, foot, knee, hip, toe_yaw):
    """s = -1 правая нога (−X), +1 левая (+X)."""
    yaw = rot_y(toe_yaw)
    fwd = mat_apply(yaw, (0, 0, 1))
    heel = add(foot, add(mul(fwd, -0.1), (0, 0.075, 0)))
    # сабатон: вытянутый к носку «лейз», пояса стальные и золотые
    lathe(mesh, heel, fwd, [
        (0.00, 0.085, 0.075),
        (0.10, 0.100, 0.080),
        (0.18, 0.095, 0.072),
        (0.26, 0.075, 0.055),
        (0.34, 0.035, 0.030),
        (0.40, 0.0, 0.0),
    ], 6, ["steel", "gold", "steel", "gold", "steel_light"], hint=(0, 1, 0), phase=0.0)
    ankle = add(foot, (0, 0.13, 0))
    # голень
    tube(mesh, ankle, add(ankle, (0, 0.04, 0)), 0.105, 0.10, 7, "gold")
    shin_top = sub(knee, mul(norm(sub(knee, ankle)), 0.06))
    lathe(mesh, add(ankle, (0, 0.04, 0)), sub(shin_top, add(ankle, (0, 0.04, 0))), [
        (0.0, 0.085, 0.09),
        (length(sub(shin_top, add(ankle, (0, 0.04, 0)))) * 0.75, 0.115, 0.12),
        (length(sub(shin_top, add(ankle, (0, 0.04, 0)))), 0.11, 0.115),
    ], 7, ["steel", "steel_dark"])
    # наколенник: золотая полусфера и шип вперёд
    lathe(mesh, add(knee, (0, -0.07, 0)), (0, 1, 0), [
        (0.00, 0.10, 0.10),
        (0.06, 0.135, 0.14),
        (0.12, 0.115, 0.12),
        (0.17, 0.05, 0.05),
    ], 7, ["gold", "gold", "gold_dark"])
    diamond(mesh, add(knee, add(mul(fwd, 0.13), (0, -0.01, 0))), (0.06, 0.08, 0.05), "gold")
    # бедро: ткань и стальная набедренная пластина
    tube(mesh, knee, hip, 0.12, 0.15, 7, "cloth")
    thigh_mid = mul(add(knee, hip), 0.5)
    plate_m = mat_mul(basis_from_y(sub(hip, knee), (0, 0, 1)), rot_x(-6))
    box(mesh, add(thigh_mid, (0, 0, 0.1)), (0.2, 0.26, 0.06), "steel", plate_m)


def build_arm(mesh, s, shoulder, elbow, hand):
    tube(mesh, shoulder, elbow, 0.085, 0.075, 6, "steel_dark")
    lathe(mesh, sub(elbow, (0, 0.06, 0)), (0, 1, 0), [
        (0.0, 0.07, 0.07), (0.06, 0.095, 0.095), (0.12, 0.06, 0.06)], 6, "gold")
    cuff = add(hand, mul(norm(sub(elbow, hand)), 0.07))
    tube(mesh, elbow, cuff, 0.07, 0.085, 6, "steel")
    tube(mesh, cuff, add(cuff, mul(norm(sub(hand, cuff)), 0.035)), 0.1, 0.1, 6, "gold")
    box(mesh, hand, (0.11, 0.11, 0.12), "steel",
        basis_from_y(sub(hand, elbow), (0, 1, 0)))


def build_pauldron(mesh, s, pos):
    axis = norm((s * 0.45, 1.0, 0.0))
    lathe(mesh, add(pos, mul(axis, -0.1)), axis, [
        (0.00, 0.19, 0.17),
        (0.04, 0.19, 0.17),
    ], 8, "gold", hint=(0, 0, 1))
    lathe(mesh, add(pos, mul(axis, -0.06)), axis, [
        (0.00, 0.18, 0.16),
        (0.07, 0.175, 0.155),
        (0.13, 0.12, 0.11),
        (0.17, 0.0, 0.0),
    ], 8, ["steel", "steel", "steel_light"], hint=(0, 0, 1))
    # нижняя пластина наплечника
    lathe(mesh, add(pos, add(mul(axis, -0.2), (s * 0.02, 0, 0))), axis, [
        (0.00, 0.15, 0.14),
        (0.08, 0.17, 0.155),
    ], 8, ["steel_dark"], hint=(0, 0, 1))


def build_sword(mesh, grip_center, direction, twist_deg):
    sword = Mesh()
    up = (0, 0, 0)
    # рукоять
    tube(sword, (0, -0.11, 0), (0, 0.11, 0), 0.026, 0.026, 6, "leather_dark")
    # навершие: длинный золотой ромб с шариком
    lathe(sword, (0, -0.15, 0), (0, 1, 0), [(0.0, 0.0, 0.0), (0.03, 0.04, 0.04), (0.05, 0.03, 0.03)], 6, "gold")
    diamond(sword, (0, -0.26, 0), (0.055, 0.11, 0.028), "gold")
    diamond(sword, (0, -0.26, 0), (0.025, 0.05, 0.034), "gold_dark")
    # гарда: перекладина, ромбы на концах и в центре
    box(sword, (0, 0.13, 0), (0.42, 0.035, 0.045), "gold")
    for sx in (-1, 1):
        diamond(sword, (sx * 0.25, 0.13, 0), (0.07, 0.07, 0.026), "gold")
        diamond(sword, (sx * 0.25, 0.13, 0), (0.03, 0.03, 0.034), "gold_dark")
        diamond(sword, (sx * 0.17, 0.08, 0), (0.025, 0.06, 0.02), "gold")
    diamond(sword, (0, 0.16, 0), (0.06, 0.08, 0.035), "gold")
    # клинок: ромб в сечении, к острию сужается
    lathe(sword, (0, 0.15, 0), (0, 1, 0), [
        (0.00, 0.058, 0.018),
        (0.85, 0.048, 0.016),
        (1.03, 0.0, 0.0),
    ], 4, "blade", hint=(0, 0, 1), phase=0.0)
    # дол (тёмная полоса посередине)
    box(sword, (0, 0.55, 0.0), (0.016, 0.7, 0.038), "steel")
    # зубцы на кромках
    for y in (0.32, 0.5, 0.68):
        for sx in (-1, 1):
            diamond(sword, (sx * 0.06, 0.15 + y, 0), (0.03, 0.035, 0.008), "steel_light")
    del up
    m = mat_mul(basis_from_y(direction, (0, 0, 1)), rot_y(twist_deg))
    mesh.merge(sword, m, grip_center)


def build_shield(mesh, center, yaw, pitch):
    sh = Mesh()
    outline = [
        (-0.24, 0.50), (0.00, 0.54), (0.24, 0.50), (0.30, 0.43),
        (0.31, 0.05), (0.28, -0.30), (0.20, -0.46), (0.07, -0.53),
        (-0.07, -0.53), (-0.20, -0.46), (-0.28, -0.30), (-0.31, 0.05), (-0.30, 0.43),
    ]
    thick, bulge = 0.035, 0.08
    z_outer = lambda p: thick  # noqa: E731
    plate(sh, outline, z_outer, thick + bulge, -0.01, "steel", "steel_dark", "steel_dark")

    def layer(poly, color, eps):
        zf = lambda p: bulge_height(outline, p, thick, bulge) + eps  # noqa: E731
        plate(sh, poly, zf, thick + bulge + eps, thick, color, "gold_dark", "gold_dark")

    gold_poly = inset_polygon(outline, 0.065)
    red_poly = inset_polygon(outline, 0.085)
    layer(gold_poly, "gold", 0.008)
    layer(red_poly, "red", 0.016)
    # заклёпки по стальной кромке
    for p in [(-0.27, 0.40), (0.27, 0.40), (-0.28, 0.0), (0.28, 0.0), (-0.24, -0.34), (0.24, -0.34),
              (0.0, 0.50), (0.0, -0.49)]:
        z = bulge_height(outline, p, thick, bulge) + 0.005
        diamond(sh, (p[0], p[1], z), (0.016, 0.016, 0.012), "gold")
    # шипы
    for u, v in [(0.12, 0.12), (0.18, 0.02), (0.12, -0.08), (0.06, 0.02)]:
        z = bulge_height(outline, (u, v), thick, bulge) + 0.014
        lathe(sh, (u, v, z), (0, 0, 1), [(0.0, 0.034, 0.034), (0.02, 0.03, 0.03), (0.11, 0.0, 0.0)],
              6, ["gold_dark", "gold"], hint=(0, 1, 0))
    # ручка на обороте
    box(sh, (0.0, 0.0, -0.05), (0.06, 0.32, 0.05), "leather")
    m = mat_mul(rot_y(yaw), rot_x(pitch))
    mesh.merge(sh, m, center)


def build_helmet(mesh):
    lathe(mesh, (0, 1.565, 0), (0, 1, 0), [
        (0.00, 0.17, 0.17),
        (0.03, 0.17, 0.17),
    ], 8, "gold")
    lathe(mesh, (0, 1.595, 0), (0, 1, 0), [
        (0.00, 0.16, 0.16),
        (0.20, 0.16, 0.16),
        (0.30, 0.14, 0.14),
        (0.35, 0.075, 0.075),
        (0.37, 0.0, 0.0),
    ], 8, ["steel", "steel", "steel_light", "steel_light"])
    apo = 0.16 * math.cos(math.pi / 8)  # расстояние до центра грани

    def on_face(k, y, size, color, lift):
        a = math.radians(45 * k)
        r = apo + lift
        box(mesh, (math.sin(a) * r, y, math.cos(a) * r), size, color, rot_y(45 * k))

    # Т-образная прорезь в золотой рамке
    for k in (-1, 0, 1):
        on_face(k, 1.78, (0.125, 0.07, 0.02), "gold", 0.004)
        on_face(k, 1.78, (0.105 if k == 0 else 0.09, 0.034, 0.02), "black", 0.012)
    on_face(0, 1.675, (0.075, 0.15, 0.02), "gold", 0.004)
    on_face(0, 1.675, (0.036, 0.13, 0.02), "black", 0.012)
    # красная полоса и ромб надо лбом, красная пластина на подбородке
    on_face(0, 1.86, (0.04, 0.1, 0.02), "red", 0.002)
    diamond(mesh, (0, 1.875, apo + 0.01), (0.045, 0.055, 0.02), "red")
    on_face(0, 1.6, (0.06, 0.05, 0.02), "gold", 0.004)
    # дыхательные отверстия
    for k in (-1, 1):
        for i in range(3):
            on_face(k, 1.69 - i * 0.035, (0.07, 0.012, 0.02), "black", 0.004)
    # рога
    for s in (-1, 1):
        pts = [(s * 0.11, 1.84, 0.0), (s * 0.21, 1.88, 0.0), (s * 0.27, 1.99, 0.0),
               (s * 0.27, 2.12, 0.0), (s * 0.23, 2.22, 0.0)]
        chain(mesh, pts, [0.05, 0.042, 0.032, 0.02, 0.0], 5, "gold")
        lathe(mesh, (s * 0.135, 1.84, 0.0), (s, 0.0, 0.0), [(0.0, 0.065, 0.065), (0.03, 0.065, 0.065)],
              6, "gold_dark", hint=(0, 1, 0))


def build_knight():
    mesh = Mesh()

    # ноги: правая (−X) отставлена, левая (+X) чуть впереди
    build_leg(mesh, -1, (-0.27, 0.0, -0.05), (-0.24, 0.58, 0.02), (-0.13, 0.93, 0.0), toe_yaw=-18)
    build_leg(mesh, 1, (0.3, 0.0, 0.12), (0.25, 0.58, 0.12), (0.13, 0.93, 0.0), toe_yaw=20)

    # кольчужная юбка, красный подол, пояс
    lathe(mesh, (0, 0.76, 0), (0, 1, 0), [
        (0.00, 0.345, 0.28),
        (0.10, 0.325, 0.26),
        (0.22, 0.30, 0.235),
    ], 10, ["mail", "mail"], phase=0.0)
    for i in range(10):  # нижний зубчатый край кольчуги
        a = 2 * math.pi * (i + 0.5) / 10
        diamond(mesh, (math.cos(a) * 0.335, 0.76, math.sin(a) * 0.272), (0.04, 0.04, 0.03), "steel_dark",
                rot_y(-math.degrees(a)))
    lathe(mesh, (0, 0.94, 0), (0, 1, 0), [
        (0.00, 0.315, 0.245),
        (0.16, 0.29, 0.225),
    ], 10, "red", phase=0.0)
    lathe(mesh, (0, 1.09, 0), (0, 1, 0), [
        (0.00, 0.30, 0.235),
        (0.06, 0.30, 0.235),
    ], 10, "leather", phase=0.0)
    belt_front = 0.235 * math.cos(math.pi / 10)
    box(mesh, (0, 1.12, belt_front + 0.02), (0.1, 0.075, 0.04), "gold")
    box(mesh, (0, 1.12, belt_front + 0.03), (0.05, 0.035, 0.04), "leather_dark")
    for x in (-0.18, -0.1, 0.1, 0.18):  # заклёпки ремня
        box(mesh, (x, 1.12, belt_front - 0.02 + abs(x) * -0.25), (0.025, 0.025, 0.04), "gold")
    # сумка на правом бедре
    box(mesh, (-0.31, 1.0, 0.08), (0.11, 0.15, 0.13), "leather", rot_y(-25))
    box(mesh, (-0.315, 1.05, 0.083), (0.12, 0.06, 0.14), "leather_dark", rot_y(-25))

    # торс в красной котте
    lathe(mesh, (0, 1.15, 0), (0, 1, 0), [
        (0.00, 0.29, 0.22),
        (0.18, 0.315, 0.235),
        (0.34, 0.325, 0.225),
        (0.42, 0.24, 0.18),
    ], 10, ["red", "red", "red_dark"], phase=0.0)
    chest_z = 0.235 * math.cos(math.pi / 10) + 0.002
    # солнце на груди
    sun = Mesh()
    plate(sun, star(8, 0.075, 0.042, rot=math.pi / 8), lambda p: 0.012, 0.022, 0.0, "gold", "gold_dark")
    lathe(sun, (0, 0, 0.012), (0, 0, 1), [(0.0, 0.03, 0.03), (0.018, 0.022, 0.022)], 8, "red_dark",
          hint=(0, 1, 0))
    mesh.merge(sun, rot_x(-4), (0, 1.35, chest_z))
    # золотой ворот-горжет
    lathe(mesh, (0, 1.53, 0), (0, 1, 0), [
        (0.00, 0.24, 0.19),
        (0.05, 0.2, 0.17),
        (0.08, 0.16, 0.15),
    ], 8, ["gold", "gold_dark"])

    build_helmet(mesh)

    # наплечники
    build_pauldron(mesh, -1, (-0.33, 1.53, 0.0))
    build_pauldron(mesh, 1, (0.33, 1.53, 0.0))

    # правая рука с мечом
    r_hand = (-0.62, 1.27, 0.33)
    build_arm(mesh, -1, (-0.37, 1.45, 0.0), (-0.57, 1.2, 0.08), r_hand)
    build_sword(mesh, r_hand, norm((-0.22, 1.0, -0.12)), twist_deg=-8)

    # левая рука и щит
    build_arm(mesh, 1, (0.37, 1.45, 0.0), (0.53, 1.18, 0.02), (0.5, 1.2, 0.25))
    build_shield(mesh, (0.56, 1.1, 0.4), yaw=28, pitch=-6)

    return mesh


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


def palette_png(path):
    size = CELL * GRID
    rows = []
    for y in range(size):
        row = []
        for x in range(size):
            i = (y // CELL) * GRID + (x // CELL)
            row.extend(PALETTE[COLOR_NAMES[i]] if i < len(COLOR_NAMES) else (255, 0, 255))
        rows.append(row)
    return write_png(path, size, size, rows)


def write_glb(path, mesh, png_bytes):
    pos, nrm, uv = bytearray(), bytearray(), bytearray()
    mn = [1e9] * 3
    mx = [-1e9] * 3
    for a, b, c, col in mesh.tris:
        n = face_normal(a, b, c)
        u = color_uv(col)
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
        "asset": {"version": "2.0", "generator": "random_stuff/ps1-knight build_knight.py"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"name": "Knight", "mesh": 0}],
        "meshes": [{"name": "Knight", "primitives": [{
            "attributes": {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2}, "material": 0}]}],
        "materials": [{
            "name": "KnightPalette",
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


def write_obj(obj_path, mtl_name, mesh):
    lines = ["# PS1-style low poly knight, generated by build_knight.py",
             f"mtllib {mtl_name}", "o Knight"]
    for name in COLOR_NAMES:
        u, v = color_uv(name)
        lines.append(f"vt {u:.5f} {1 - v:.5f}")  # в OBJ V смотрит вверх
    lines.append("usemtl KnightPalette")
    lines.append("s off")
    vi = 1
    vn = 1
    vlines, flines, nlines = [], [], []
    for a, b, c, col in mesh.tris:
        n = face_normal(a, b, c)
        nlines.append(f"vn {n[0]:.4f} {n[1]:.4f} {n[2]:.4f}")
        t = COLOR_NAMES.index(col) + 1
        for p in (a, b, c):
            vlines.append(f"v {p[0]:.4f} {p[1]:.4f} {p[2]:.4f}")
        flines.append(f"f {vi}/{t}/{vn} {vi + 1}/{t}/{vn} {vi + 2}/{t}/{vn}")
        vi += 3
        vn += 1
    with open(obj_path, "w") as f:
        f.write("\n".join(lines[:3] + vlines + lines[3:3 + len(COLOR_NAMES)] + nlines
                          + lines[3 + len(COLOR_NAMES):] + flines) + "\n")


def write_mtl(path):
    with open(path, "w") as f:
        f.write("newmtl KnightPalette\nKa 0 0 0\nKd 1 1 1\nKs 0 0 0\nillum 1\nmap_Kd palette.png\n")


def mesh_json(mesh):
    """Компактные данные для вьюера: координаты в мм и индексы цветов."""
    pos, col = [], []
    for a, b, c, name in mesh.tris:
        for p in (a, b, c):
            pos.extend(int(round(x * 1000)) for x in p)
        col.append(COLOR_NAMES.index(name))
    return {"palette": [PALETTE[n] for n in COLOR_NAMES], "names": COLOR_NAMES, "pos": pos, "col": col}


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
    os.makedirs(out, exist_ok=True)
    mesh = build_knight()
    png = palette_png(os.path.join(out, "palette.png"))
    write_glb(os.path.join(out, "knight.glb"), mesh, png)
    write_obj(os.path.join(out, "knight.obj"), "knight.mtl", mesh)
    write_mtl(os.path.join(out, "knight.mtl"))
    here = os.path.dirname(os.path.abspath(__file__))
    with open(os.path.join(here, "viewer_template.html"), encoding="utf-8") as f:
        page = f.read()
    with open(os.path.join(out, "viewer.html"), "w", encoding="utf-8") as f:
        f.write(page.replace("__MESH_JSON__", json.dumps(mesh_json(mesh), separators=(",", ":"))))
    print(f"треугольников: {len(mesh.tris)}, вершин: {len(mesh.tris) * 3}")
    ys = [p[1] for t in mesh.tris for p in t[:3]]
    print(f"высота: {min(ys):.2f} .. {max(ys):.2f}")


if __name__ == "__main__":
    main()
