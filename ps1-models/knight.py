"""Рыцарь с мечом и щитом: рогатый шлем, красная котта с солнцем, щит с шипами."""
import math

from lowpoly import (Mesh, basis_from_y, box, chain, diamond, inset_polygon, bulge_height, lathe,
                     length, mat_apply, mat_mul, mul, norm, plate, rot_x, rot_y, star, sub, add, tube)

NAME = "Knight"
FILE = "knight"
TITLE = "Лоу-поли рыцарь"
TITLE_HTML = "Лоу-поли <span>рыцарь</span>"
LEDE = ("Рыцарь с двуручным мечом и щитом в духе игр PlayStation&nbsp;1: плоские грани, одна "
        "текстура-палитра 64×64, дрожащие вершины и дизеринг в&nbsp;15-битном цвете.")

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

NAMES_RU = {
    "steel": "сталь", "steel_dark": "тёмная сталь", "steel_light": "светлая сталь",
    "gold": "золото", "gold_dark": "тёмное золото", "red": "красный", "red_dark": "тёмно-красный",
    "mail": "кольчуга", "cloth": "сукно", "leather": "кожа", "leather_dark": "тёмная кожа",
    "black": "прорезь", "blade": "клинок",
}


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


def build():
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
