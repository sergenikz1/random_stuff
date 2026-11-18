"""Шаманка: медвежий капюшон, бисерный верх, набедренная повязка, меховые обмотки и костяные клинки."""
import math

from lowpoly import (Mesh, add, basis_from_y, box, bulge_height, diamond, inset_polygon, lathe, length,
                     mul, norm, plate, rot_x, rot_y, sub, tube)

NAME = "Shaman"
FILE = "shaman"
TITLE = "Лоу-поли шаманка"
TITLE_HTML = "Лоу-поли <span>шаманка</span>"
LEDE = ("Шаманка в медвежьем капюшоне с костяными клинками в духе игр PlayStation&nbsp;1: плоские "
        "грани, одна текстура-палитра 64×64, дрожащие вершины и дизеринг в&nbsp;15-битном цвете.")

PALETTE = {
    "skin": (198, 116, 66),
    "skin_shade": (160, 88, 50),
    "hair": (66, 42, 28),
    "fur": (124, 92, 60),
    "fur_dark": (80, 58, 38),
    "cloth": (136, 112, 92),
    "cloth_dark": (98, 78, 62),
    "leather": (146, 92, 50),
    "beads": (208, 86, 40),
    "beads_dark": (138, 44, 24),
    "red": (196, 26, 30),
    "bone": (234, 226, 206),
    "brass": (216, 158, 52),
    "gourd": (200, 140, 72),
    "black": (22, 16, 18),
    "lips": (146, 58, 48),
    "paint": (96, 62, 124),
}

NAMES_RU = {
    "skin": "кожа", "skin_shade": "тень кожи", "hair": "волосы", "fur": "мех", "fur_dark": "тёмный мех",
    "cloth": "повязка", "cloth_dark": "край повязки", "leather": "кожаный ремень", "beads": "бисер",
    "beads_dark": "тёмный бисер", "red": "кисти", "bone": "кость", "brass": "латунь", "gourd": "тыквы",
    "black": "глаза", "lips": "губы", "paint": "краска у глаз",
}


def ring_of_diamonds(mesh, y, rx, rz, count, half, color, start=0.0):
    for i in range(count):
        a = start + 2 * math.pi * i / count
        diamond(mesh, (math.cos(a) * rx, y, math.sin(a) * rz), half, color, rot_y(90 - math.degrees(a)))


def build_leg(mesh, s, foot, knee, hip, toe_yaw):
    fwd = (math.sin(math.radians(toe_yaw)), 0.0, math.cos(math.radians(toe_yaw)))
    heel = add(foot, add(mul(fwd, -0.07), (0, 0.04, 0)))
    # босая стопа
    lathe(mesh, heel, fwd, [
        (0.00, 0.040, 0.040),
        (0.07, 0.050, 0.042),
        (0.15, 0.052, 0.032),
        (0.21, 0.036, 0.020),
        (0.23, 0.0, 0.0),
    ], 6, ["skin", "skin", "skin", "skin_shade"], hint=(0, 1, 0), phase=0.0)
    # меховая обмотка от щиколотки до колена
    base = add(foot, (0, 0.05, 0))
    axis = sub(knee, base)
    h = length(axis) - 0.03
    lathe(mesh, base, axis, [
        (0.00, 0.070, 0.072),
        (0.05, 0.100, 0.104),
        (h * 0.45, 0.118, 0.122),
        (h * 0.80, 0.122, 0.126),
        (h, 0.100, 0.104),
    ], 7, ["fur_dark", "fur", "fur", "fur_dark"])
    up = norm(axis)
    # клочья меха по низу и по бокам
    for i in range(7):
        a = 2 * math.pi * (i + 0.5) / 7
        p = add(base, (math.cos(a) * 0.1, 0.03, math.sin(a) * 0.1))
        diamond(mesh, p, (0.035, 0.05, 0.02), "fur_dark", rot_y(90 - math.degrees(a)))
    for k in range(5):
        a = 2 * math.pi * (k + 0.3) / 5
        p = add(add(base, mul(up, h * 0.55)), (math.cos(a) * 0.115, 0, math.sin(a) * 0.119))
        diamond(mesh, p, (0.04, 0.055, 0.018), "fur", rot_y(90 - math.degrees(a)))
    # оранжевые завязки
    for frac in (0.25, 0.86):
        tube(mesh, add(base, mul(up, h * frac)), add(base, mul(up, h * frac + 0.018)),
             0.124 if frac > 0.5 else 0.121, 0.124 if frac > 0.5 else 0.121, 7, "beads")
    # бедро
    tube(mesh, add(base, mul(up, h - 0.02)), hip, 0.07, 0.1, 7, "skin")


def build_arm(mesh, s, shoulder, elbow, wrist):
    # дельта
    lathe(mesh, add(shoulder, (0, -0.07, 0)), (s * 0.25, 1, 0), [
        (0.00, 0.0, 0.0), (0.03, 0.058, 0.058), (0.08, 0.068, 0.066), (0.13, 0.045, 0.045), (0.15, 0.0, 0.0),
    ], 6, "skin")
    tube(mesh, shoulder, elbow, 0.066, 0.052, 6, "skin")
    tube(mesh, elbow, wrist, 0.056, 0.044, 6, "skin")
    # кожаный наруч с меховой опушкой и бисерной полосой
    d = sub(wrist, elbow)
    a = add(elbow, mul(d, 0.3))
    b = add(elbow, mul(d, 0.95))
    tube(mesh, a, b, 0.066, 0.056, 6, "leather")
    tube(mesh, sub(a, mul(norm(d), 0.03)), add(a, mul(norm(d), 0.02)), 0.076, 0.074, 6, "fur")
    mid = add(elbow, mul(d, 0.62))
    tube(mesh, mid, add(mid, mul(norm(d), 0.022)), 0.064, 0.063, 6, "beads")
    # кулак
    hand = add(wrist, mul(norm(d), 0.05))
    box(mesh, hand, (0.065, 0.09, 0.07), "skin", basis_from_y(d, (0, 0, 1)))
    # костяной клинок, обратный хват: остриё вниз и вперёд
    blade_dir = norm((s * 0.35, -1.0, 0.5))
    root = add(hand, mul(blade_dir, 0.04))
    lathe(mesh, root, blade_dir, [
        (0.00, 0.030, 0.012),
        (0.08, 0.036, 0.012),
        (0.17, 0.020, 0.008),
        (0.22, 0.0, 0.0),
    ], 4, "bone", hint=(1, 0, 0), phase=0.0)
    diamond(mesh, sub(hand, mul(blade_dir, 0.055)), (0.022, 0.03, 0.022), "bone",
            basis_from_y(blade_dir, (1, 0, 0)))


def build_head(mesh):
    hc = (0.0, 1.57, 0.01)
    lathe(mesh, hc, (0, 1, 0), [
        (0.000, 0.0, 0.0),
        (0.015, 0.045, 0.050),
        (0.050, 0.070, 0.080),
        (0.110, 0.083, 0.092),
        (0.170, 0.082, 0.090),
        (0.210, 0.066, 0.076),
        (0.240, 0.030, 0.040),
        (0.250, 0.0, 0.0),
    ], 8, "skin")
    fz = 0.01 + 0.0917 * math.cos(math.pi / 8)
    for sx in (-1, 1):
        box(mesh, (sx * 0.029, 1.70, fz - 0.001), (0.022, 0.011, 0.012), "black")
        box(mesh, (sx * 0.031, 1.712, fz - 0.003), (0.03, 0.012, 0.012), "paint")
        box(mesh, (sx * 0.032, 1.726, fz - 0.004), (0.032, 0.008, 0.012), "hair")
    diamond(mesh, (0.0, 1.678, fz + 0.004), (0.012, 0.024, 0.014), "skin_shade")
    box(mesh, (0.0, 1.646, fz - 0.009), (0.032, 0.009, 0.012), "lips")
    # серьги
    for sx in (-1, 1):
        tube(mesh, (sx * 0.084, 1.655, 0.015), (sx * 0.088, 1.6, 0.02), 0.005, 0.005, 3, "brass")
        diamond(mesh, (sx * 0.088, 1.575, 0.02), (0.014, 0.03, 0.014), "brass")
    # волосы: шапка, длинная прядь по спине, боковые пряди
    lathe(mesh, (0.0, 1.6, -0.02), (0, 1, 0), [
        (0.00, 0.096, 0.086),
        (0.08, 0.101, 0.100),
        (0.15, 0.100, 0.100),
        (0.20, 0.086, 0.090),
        (0.235, 0.05, 0.06),
        (0.25, 0.0, 0.0),
    ], 8, "hair")
    lathe(mesh, (0.0, 1.70, -0.06), norm((0.0, -1.0, -0.25)), [
        (0.00, 0.10, 0.06),
        (0.20, 0.14, 0.06),
        (0.40, 0.15, 0.05),
        (0.50, 0.12, 0.03),
        (0.56, 0.0, 0.0),
    ], 6, "hair")
    for sx in (-1, 1):
        pts = [(sx * 0.085, 1.72, 0.0), (sx * 0.12, 1.55, -0.01), (sx * 0.15, 1.40, -0.035)]
        tube(mesh, pts[0], pts[1], 0.04, 0.032, 5, "hair")
        lathe(mesh, pts[1], sub(pts[2], pts[1]), [(0.0, 0.032, 0.03), (length(sub(pts[2], pts[1])), 0.0, 0.0)],
              5, "hair")


def build_headdress(mesh):
    # меховая шапка из медвежьей шкуры
    lathe(mesh, (0.0, 1.725, -0.02), (0, 1, 0), [
        (0.00, 0.106, 0.110),
        (0.06, 0.112, 0.116),
        (0.12, 0.092, 0.100),
        (0.17, 0.052, 0.060),
        (0.19, 0.0, 0.0),
    ], 8, ["fur_dark", "fur", "fur", "fur"])
    # бисерная налобная повязка
    lathe(mesh, (0.0, 1.722, 0.0), (0, 1, 0), [(0.0, 0.101, 0.106), (0.026, 0.101, 0.106)], 8, "beads")
    diamond(mesh, (0.0, 1.735, 0.108), (0.02, 0.022, 0.012), "bone")
    # морда медведя над лбом
    lathe(mesh, (0.0, 1.84, 0.04), norm((0.0, -0.35, 1.0)), [
        (0.00, 0.055, 0.046),
        (0.06, 0.050, 0.042),
        (0.10, 0.034, 0.030),
        (0.115, 0.0, 0.0),
    ], 6, ["fur_dark", "fur_dark", "fur"], hint=(0, 1, 0))
    diamond(mesh, (0.0, 1.808, 0.152), (0.016, 0.012, 0.012), "black")
    for sx in (-1, 1):
        box(mesh, (sx * 0.032, 1.852, 0.098), (0.016, 0.01, 0.02), "black", rot_x(-20))
        lathe(mesh, (sx * 0.075, 1.86, -0.03), norm((sx * 0.4, 1.0, 0.0)), [
            (0.0, 0.032, 0.02), (0.035, 0.028, 0.018), (0.06, 0.0, 0.0)], 5, "fur_dark", hint=(0, 0, 1))
    # клыки по краю шкуры
    for sx in (-1, 1):
        diamond(mesh, (sx * 0.022, 1.79, 0.13), (0.007, 0.016, 0.007), "bone")


def build_top(mesh):
    """Бисерный верх: чашки, кайма, нагрудное ожерелье и лямки. Сам пояс верха — пояс торса в build()."""
    for sx in (-1, 1):
        lathe(mesh, (sx * 0.078, 1.335, 0.07), norm((sx * 0.3, -0.1, 1.0)), [
            (0.00, 0.082, 0.076),
            (0.045, 0.078, 0.072),
            (0.075, 0.048, 0.045),
            (0.09, 0.0, 0.0),
        ], 7, ["beads_dark", "beads", "beads"], hint=(0, 1, 0))
    for y, r in ((1.262, (0.194, 0.127)), (1.418, (0.212, 0.126))):
        lathe(mesh, (0.0, y, 0.0), (0, 1, 0), [(0.0, r[0], r[1]), (0.026, r[0], r[1])], 10, "beads_dark", phase=0.0)
    # ожерелье на груди
    outline = [(-0.1, 0.05), (-0.06, 0.075), (0.0, 0.08), (0.06, 0.075), (0.1, 0.05), (0.09, -0.01),
               (0.05, -0.05), (0.0, -0.065), (-0.05, -0.05), (-0.09, -0.01)]
    bib = Mesh()
    plate(bib, outline, lambda p: 0.01, 0.03, -0.01, "beads_dark", "beads_dark", "beads_dark")
    inner = inset_polygon(outline, 0.022)
    plate(bib, inner, lambda p: bulge_height(outline, p, 0.01, 0.02) + 0.006, 0.036, 0.01,
          "beads", "beads_dark", "beads_dark")
    diamond(bib, (0.0, -0.01, 0.05), (0.026, 0.032, 0.014), "bone")
    diamond(bib, (0.0, -0.01, 0.056), (0.01, 0.014, 0.012), "brass")
    mesh.merge(bib, rot_x(-10), (0.0, 1.455, 0.085))
    # ошейник и лямки
    lathe(mesh, (0.0, 1.49, 0.005), (0, 1, 0), [(0.0, 0.068, 0.066), (0.03, 0.062, 0.06)], 8, "beads_dark")
    for sx in (-1, 1):
        tube(mesh, (sx * 0.09, 1.47, 0.07), (sx * 0.12, 1.49, -0.06), 0.014, 0.014, 4, "beads_dark")
        tube(mesh, (sx * 0.12, 1.49, -0.06), (sx * 0.15, 1.29, -0.11), 0.014, 0.014, 4, "beads_dark")


def build_hips(mesh):
    # набедренная повязка
    lathe(mesh, (0.0, 0.58, 0.0), (0, 1, 0), [
        (0.00, 0.255, 0.195),
        (0.20, 0.225, 0.172),
        (0.42, 0.188, 0.140),
    ], 10, ["cloth", "cloth"], phase=0.0, cap_start=False, cap_end=False)
    ring_of_diamonds(mesh, 0.585, 0.25, 0.19, 10, (0.05, 0.055, 0.02), "cloth_dark", start=math.pi / 10)
    flap = Mesh()
    plate(flap, [(-0.10, 0.17), (0.09, 0.17), (0.12, -0.15), (0.06, -0.24), (-0.07, -0.22), (-0.11, -0.14)],
          lambda p: 0.012, 0.018, 0.0, "cloth_dark", "cloth_dark", "cloth_dark")
    mesh.merge(flap, rot_x(-6), (0.03, 0.77, 0.17))
    # пояс с бусинами и латунной бляхой
    lathe(mesh, (0.0, 0.955, 0.0), (0, 1, 0), [(0.0, 0.198, 0.150), (0.055, 0.196, 0.148)], 10, "leather",
          phase=0.0)
    for i in range(10):
        a = 2 * math.pi * (i + 0.5) / 10 + math.pi / 10
        if abs(math.cos(a)) < 0.25 and math.sin(a) > 0:
            continue
        box(mesh, (math.cos(a) * 0.196, 0.982, math.sin(a) * 0.148), (0.022, 0.022, 0.022), "beads",
            rot_y(90 - math.degrees(a)))
    front = 0.148 * math.cos(math.pi / 10)
    lathe(mesh, (0.0, 0.982, front), (0, 0, 1), [(0.0, 0.048, 0.048), (0.014, 0.048, 0.048), (0.024, 0.032, 0.032)],
          8, "brass", hint=(0, 1, 0))
    diamond(mesh, (0.0, 0.982, front + 0.028), (0.014, 0.014, 0.01), "beads_dark")
    # тыквы-фляги на левом бедре
    for pos, scale in (((0.205, 0.80, 0.11), 1.0), ((0.235, 0.82, -0.06), 0.85)):
        gx, gy, gz = pos
        tube(mesh, (gx * 0.92, 0.96, gz * 0.9), (gx, gy + 0.17 * scale, gz), 0.006, 0.006, 3, "leather")
        lathe(mesh, pos, (0, 1, 0), [
            (0.00 * scale, 0.0, 0.0),
            (0.02 * scale, 0.045 * scale, 0.045 * scale),
            (0.07 * scale, 0.062 * scale, 0.062 * scale),
            (0.12 * scale, 0.044 * scale, 0.044 * scale),
            (0.15 * scale, 0.026 * scale, 0.026 * scale),
            (0.17 * scale, 0.024 * scale, 0.024 * scale),
        ], 6, ["gourd", "gourd", "gourd", "gourd", "leather"])
    # красные кисти под повязкой
    for k in range(5):
        a = math.radians(90 + (k - 2) * 24)
        x, z = math.cos(a) * 0.24, math.sin(a) * 0.19 + 0.012
        top = (x, 0.6, z)
        bottom = (x * 1.04, 0.5, z + 0.012)
        tube(mesh, top, bottom, 0.005, 0.005, 3, "fur_dark")
        diamond(mesh, (bottom[0], 0.47, bottom[2]), (0.022, 0.048, 0.018), "red", rot_y(90 - math.degrees(a)))
        diamond(mesh, (bottom[0], 0.415, bottom[2]), (0.012, 0.02, 0.012), "beads_dark")


def build():
    mesh = Mesh()
    build_leg(mesh, -1, (-0.13, 0.0, 0.0), (-0.115, 0.5, 0.03), (-0.1, 0.93, 0.0), toe_yaw=-12)
    build_leg(mesh, 1, (0.13, 0.0, 0.03), (0.115, 0.5, 0.04), (0.1, 0.93, 0.0), toe_yaw=12)
    # таз под повязкой и торс
    lathe(mesh, (0.0, 0.84, 0.0), (0, 1, 0), [(0.0, 0.16, 0.115), (0.15, 0.175, 0.125)], 10, "skin", phase=0.0)
    lathe(mesh, (0.0, 0.99, 0.0), (0, 1, 0), [
        (0.00, 0.172, 0.122),
        (0.12, 0.164, 0.116),
        (0.24, 0.180, 0.120),
        (0.28, 0.187, 0.122),
        (0.36, 0.200, 0.125),
        (0.43, 0.210, 0.121),
        (0.50, 0.150, 0.100),
    ], 10, ["skin", "skin", "skin", "beads", "beads", "skin"], phase=0.0)
    # пресс
    for row, y in enumerate((1.05, 1.1, 1.15)):
        for sx in (-1, 1):
            box(mesh, (sx * 0.03, y, 0.104 - row * 0.002), (0.05, 0.042, 0.03), "skin")
    tube(mesh, (0.0, 1.46, 0.0), (0.0, 1.6, 0.005), 0.05, 0.047, 6, "skin")
    build_top(mesh)
    build_hips(mesh)
    build_head(mesh)
    build_headdress(mesh)
    build_arm(mesh, -1, (-0.225, 1.39, 0.0), (-0.28, 1.12, -0.02), (-0.31, 0.88, 0.05))
    build_arm(mesh, 1, (0.225, 1.39, 0.0), (0.285, 1.12, -0.02), (0.325, 0.88, 0.04))
    return mesh
