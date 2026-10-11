"""Draws twenty houses that each have a shape of their own: an A-frame, a roundhouse, a house on
stilts, a keep, a pagoda and so on. No two share a plan or a roof.

Run: python3 tools/gen_unique_houses.py <blueprints dir> [id to print]

Every design is drawn block by block into a grid; the palette letters are handed out afterwards.
The front of a house is its high-z side, as everywhere else: doors face north, beds lie with
their heads to the back and a chest two steps past their feet.
"""
import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from gen_houses import dump

KEYS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789#$%&*+-/:;<=>?@^_~!|"
LATE = ("_door[", "_bed[", "lantern", "_carpet", "potted_", "chest[", "ladder", "decorated_pot", "poppy", "dandelion",
        "azalea", "fern", "cornflower", "allium", "tulip", "torch", "flower_pot", "cactus", "dead_bush", "lectern", "bell[")
AIR = ""

def m(name): return "minecraft:" + name
def stairs(w, facing, half="bottom"): return m(f"{w}_stairs[facing={facing},half={half}]")
def slab(w, t="bottom"): return m(f"{w}_slab[type={t}]")
def log(w, axis="y"): return m(f"{w}[axis={axis}]")
def lantern(hanging=True): return m(f"lantern[hanging={'true' if hanging else 'false'}]")

class G:
    def __init__(s, W, D, H):
        s.W, s.D, s.H, s.c = W, D, H, {}
        s.keep = set()                                       # (y, z) rows and (x, y, z) cells that stay bare floor
    def put(s, x, y, z, b):
        assert 0 <= x < s.W and 0 <= z < s.D and 0 <= y < s.H, (x, y, z, s.W, s.D, s.H)
        s.c[(x, y, z)] = b
    def get(s, x, y, z): return s.c.get((x, y, z))
    def air(s, x, y, z):
        if (x, y, z) not in s.c:
            s.put(x, y, z, AIR)
    def box(s, x0, x1, y0, y1, z0, z1, b):
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    s.put(x, y, z, b)
    def clear(s, x0, x1, y0, y1, z0, z1): s.box(x0, x1, y0, y1, z0, z1, AIR)

    # walls of a room; the inside is cleared
    def body(s, x0, x1, z0, z1, y0, y1, wall, post=None, sill=None, beam=None, glass="glass_pane", win=None):
        win = y0 + 1 if win is None else win
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                for z in range(z0, z1 + 1):
                    ex, ez = x in (x0, x1), z in (z0, z1)
                    if not (ex or ez):
                        s.air(x, y, z)
                        continue
                    i, n = (x - x0, x1 - x0) if ez else (z - z0, z1 - z0)
                    if ex and ez: b = post or wall
                    elif y == y0 and sill: b = sill
                    elif y == y1 and beam: b = log(beam, "x" if ez else "z")
                    elif y == win and i % 2 == 0 and 0 < i < n: b = m(glass)
                    else: b = wall
                    s.put(x, y, z, b)

    def door(s, x, y, z, wood="spruce"):
        s.put(x, y, z, m(f"{wood}_door[half=lower,facing=north,hinge=left]"))
        s.put(x, y + 1, z, m(f"{wood}_door[half=upper,facing=north,hinge=left]"))
        s.keep.add((x, y, z - 1))

    def beds(s, xs, y, z, color, chest_z=None):
        for x in xs:
            s.put(x, y, z, m(f"{color}_bed[facing=north,part=head]"))
            s.put(x, y, z + 1, m(f"{color}_bed[facing=north,part=foot]"))
            s.put(x, y, z + 2, AIR)
            s.keep.add((y, z + 2))
            if chest_z is not False:
                s.put(x, y, (z + 3) if chest_z is None else chest_z, m("chest[facing=south]"))

    # a roof with its ridge running front to back
    def roof_z(s, x0, x1, z0, z1, y, wood, gable=None, gz=(), cap=None):
        k = 0
        while x0 + k <= x1 - k:
            lo, hi = x0 + k, x1 - k
            for z in range(z0, z1 + 1):
                if lo == hi:
                    s.put(lo, y + k, z, slab(cap or wood))
                    continue
                s.put(lo, y + k, z, stairs(wood, "east")); s.put(hi, y + k, z, stairs(wood, "west"))
                for x in range(lo + 1, hi):
                    if z in gz and gable: s.put(x, y + k, z, gable)
                    else: s.air(x, y + k, z)
            k += 1
        return y + k - 1

    # a roof with its ridge running side to side
    def roof_x(s, x0, x1, z0, z1, y, wood, gable=None, gx=(), cap=None):
        k = 0
        while z0 + k <= z1 - k:
            lo, hi = z0 + k, z1 - k
            for x in range(x0, x1 + 1):
                if lo == hi:
                    s.put(x, y + k, lo, slab(cap or wood))
                    continue
                s.put(x, y + k, lo, stairs(wood, "south")); s.put(x, y + k, hi, stairs(wood, "north"))
                for z in range(lo + 1, hi):
                    if x in gx and gable: s.put(x, y + k, z, gable)
                    else: s.air(x, y + k, z)
            k += 1
        return y + k - 1

    # a roof sloping up from all four sides
    def hip(s, x0, x1, z0, z1, y, wood, cap=None, layers=99):
        k = 0
        while x1 - x0 - 2 * k >= 1 and z1 - z0 - 2 * k >= 1 and k < layers:
            a, b, c, d = x0 + k, x1 - k, z0 + k, z1 - k
            for z in range(c + 1, d):
                s.put(a, y + k, z, stairs(wood, "east")); s.put(b, y + k, z, stairs(wood, "west"))
            for x in range(a, b + 1):
                s.put(x, y + k, c, stairs(wood, "south")); s.put(x, y + k, d, stairs(wood, "north"))
            for x in range(a + 1, b):
                for z in range(c + 1, d):
                    s.air(x, y + k, z)
            k += 1
        if k < layers:
            for x in range(x0 + k, x1 - k + 1):
                for z in range(z0 + k, z1 - k + 1):
                    s.put(x, y + k, z, slab(cap or wood))
        return y + k

    def result(s, id, name, beds, area, stage, extra_wood=0, extra_stone=0):
        used = []
        for b in s.c.values():
            if b != AIR and b not in used:
                used.append(b)
        assert len(used) <= len(KEYS), (id, len(used))
        key = {b: KEYS[i] for i, b in enumerate(used)}
        key[AIR] = "."
        H = max(y for (_, y, _) in s.c) + 1
        layers = [["".join(key[s.c[(x, y, z)]] if (x, y, z) in s.c else " " for x in range(s.W)) for z in range(s.D)] for y in range(H)]
        late = "".join(k for b, k in key.items() if any(w in b for w in LATE))
        return {"id": id, "name": name, "category": "housing", "beds": beds, "min_stage": stage,
                "cost": {"wood": (30 * beds + 3 * area + extra_wood) // 10 * 10, "stone": (area + 30 + extra_stone) // 10 * 10},
                "late": late, "palette": {k: b for b, k in key.items() if b != AIR}, "layers": layers, "_grid": s}

def furnish(g, spots, y=1):
    """Puts things down on free floor: (x, z, block[, block above])."""
    for spot in spots:
        g.put(spot[0], y, spot[1], m(spot[2]))
        if len(spot) > 3:
            g.put(spot[0], y + 1, spot[1], m(spot[3]))

def rug(g, x0, x1, z0, z1, color, y=1):
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if g.get(x, y, z) in (None, AIR) and (y, z) not in g.keep and (x, y, z) not in g.keep:
                g.put(x, y, z, m(color + "_carpet"))

def path(g, x, z): g.put(x, 0, z, m("dirt_path"))

# ---------------------------------------------------------------------------------------------

def a_frame():
    """A roof that comes down to the ground on both sides, with a deck in front."""
    g = G(9, 10, 12)
    wood, trim = "spruce", "dark_oak"
    g.box(1, 7, 0, 0, 1, 7, m("spruce_planks")); g.box(1, 7, 0, 0, 8, 8, m("stone_bricks"))
    for z in range(0, 9):
        for k in range(4):
            edge = z in (0, 8)
            g.put(k, 1 + 2 * k, z, m(f"{trim if edge else wood}_planks")); g.put(8 - k, 1 + 2 * k, z, m(f"{trim if edge else wood}_planks"))
            g.put(k, 2 + 2 * k, z, stairs(trim if edge else wood, "east")); g.put(8 - k, 2 + 2 * k, z, stairs(trim if edge else wood, "west"))
        g.put(4, 9, z, slab(trim))
    for z in range(1, 8):
        for k in range(4):
            for y in (1 + 2 * k, 2 + 2 * k):
                for x in range(k + 1, 8 - k):
                    if z in (1, 7): g.put(x, y, z, m("birch_planks") if k < 3 else m("spruce_planks"))
                    else: g.air(x, y, z)
    for z in (1, 7):
        for x in (3, 5): g.put(x, 4, z, m("glass_pane")); g.put(x, 5, z, m("glass_pane"))
        g.put(4, 6, z, m("glass_pane"))
        g.put(1, 1, z, log("spruce_log")); g.put(7, 1, z, log("spruce_log")); g.put(1, 2, z, log("spruce_log")); g.put(7, 2, z, log("spruce_log"))
    g.door(4, 1, 7); g.put(3, 2, 7, m("glass_pane")); g.put(5, 2, 7, m("glass_pane"))
    g.beds([2, 6], 1, 2, "green")
    furnish(g, [(4, 2, "bookshelf", "bookshelf"), (6, 6, "barrel[facing=up]", "potted_fern"), (2, 6, "crafting_table")])
    rug(g, 3, 5, 4, 5, "green")
    g.put(4, 8, 4, lantern())
    for x in (2, 6): g.put(x, 1, 8, m("spruce_fence")); g.put(x, 2, 8, lantern(False))
    path(g, 4, 9)
    return g.result("a_frame_cabin", "A-Frame Cabin", 2, 49, 1)

def roundhouse():
    """Eight-sided, under a cone of a roof."""
    g = G(11, 11, 10)
    def inside(x, z, r): return max(abs(x - 5), abs(z - 5)) <= r and abs(x - 5) + abs(z - 5) <= 2 * r - 1
    def rim(x, z, r): return inside(x, z, r) and not all(inside(x + a, z + b, r) for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1)))
    for x in range(11):
        for z in range(11):
            if inside(x, z, 4):
                g.put(x, 0, z, m("packed_mud") if rim(x, z, 4) else m("spruce_planks"))
                for y in (1, 2, 3):
                    if not rim(x, z, 4): g.air(x, y, z)
                    elif y == 1: g.put(x, y, z, m("cobblestone"))
                    elif abs(x - 5) == 4 or abs(z - 5) == 4: g.put(x, y, z, m("mud_bricks") if (x + z) % 2 or y == 3 else m("glass_pane"))
                    else: g.put(x, y, z, log("stripped_oak_log"))
    for k in range(5):
        r = 5 - k
        for x in range(11):
            for z in range(11):
                if rim(x, z, r):
                    dx, dz = x - 5, z - 5
                    facing = ("east" if dx < 0 else "west") if abs(dx) >= abs(dz) else ("south" if dz < 0 else "north")
                    g.put(x, 3 + k, z, stairs("spruce" if k % 2 == 0 else "dark_oak", facing))
                elif inside(x, z, r) and k > 0: g.air(x, 3 + k, z)
    g.put(5, 8, 5, m("dark_oak_fence")); g.put(5, 9, 5, lantern(False)); g.put(5, 7, 5, lantern())
    g.door(5, 1, 9, "oak")
    g.beds([4, 6], 1, 2, "orange")
    furnish(g, [(2, 5, "barrel[facing=up]", "potted_dandelion"), (8, 5, "loom[facing=west]"), (5, 5, "decorated_pot[facing=south]")])
    rug(g, 4, 6, 6, 7, "orange")
    path(g, 5, 10)
    return g.result("roundhouse", "Roundhouse", 2, 60, 1)

def stilt_house():
    """Raised on posts, with a stair up to a railed deck."""
    g = G(9, 10, 14)
    for x in (1, 4, 7):
        for z in (1, 4, 7):
            g.box(x, x, 0, 2, z, z, log("stripped_spruce_log"))
    g.box(1, 7, 3, 3, 1, 7, m("spruce_planks"))
    g.body(1, 7, 1, 6, 4, 6, m("birch_planks"), post=log("spruce_log"), beam="spruce_log", win=5)
    g.door(4, 4, 6)
    g.put(4, 1, 9, stairs("spruce", "north")); g.put(4, 2, 8, stairs("spruce", "north")); g.put(4, 3, 7, stairs("spruce", "north"))
    for x in range(1, 8):
        if x != 4: g.put(x, 4, 7, m("spruce_fence"))
    for x in (1, 7): g.box(x, x, 5, 7, 7, 7, m("spruce_fence"))
    g.roof_x(0, 8, 0, 8, 7, "dark_oak", gable=m("birch_planks"), gx=(1, 7), cap="spruce")
    g.put(1, 8, 4, m("glass_pane")); g.put(7, 8, 4, m("glass_pane"))
    g.beds([2, 6], 4, 2, "cyan")
    furnish(g, [(4, 2, "barrel[facing=up]", "potted_blue_orchid")], y=4)
    rug(g, 3, 5, 3, 4, "light_blue", y=4)
    g.put(4, 10, 4, lantern())
    g.put(4, 2, 4, lantern())                                # under the house
    g.put(2, 1, 4, m("hay_block")); g.put(2, 1, 5, m("barrel[facing=up]")); g.put(6, 1, 2, m("composter"))
    return g.result("stilt_house", "Stilt House", 2, 49, 1, extra_wood=40)

def turf_house():
    """Low stone walls under a roof of moss, with flowers growing on it."""
    g = G(9, 8, 8)
    g.box(1, 7, 0, 0, 1, 6, m("spruce_planks"))
    g.body(1, 7, 1, 6, 1, 2, m("mossy_cobblestone"), post=m("cobblestone"), win=2)
    for x in range(1, 8):
        g.put(x, 1, 6, m("dark_oak_planks")); g.put(x, 2, 6, m("dark_oak_planks") if x % 2 else m("glass_pane"))
    g.door(4, 1, 6, "dark_oak")
    g.box(0, 8, 3, 3, 0, 7, m("moss_block")); g.clear(2, 6, 3, 3, 2, 5)
    g.box(1, 7, 4, 4, 1, 6, m("moss_block"))
    for x in range(0, 9): g.put(x, 3, 7, log("dark_oak_log", "x"))
    for x, z, f in ((2, 2, "poppy"), (5, 3, "dandelion"), (3, 5, "cornflower"), (6, 5, "poppy"), (0, 3, "fern"), (8, 4, "fern")):
        g.put(x, 5 if 1 <= x <= 7 else 4, z, m(f))
    g.box(6, 6, 1, 5, 1, 1, m("cobblestone")); g.put(6, 6, 1, m("cobblestone_wall"))
    g.beds([2, 3], 1, 2, "lime", chest_z=5)
    furnish(g, [(6, 2, "smoker[facing=south]"), (6, 4, "barrel[facing=up]")])
    g.put(4, 3, 4, lantern())
    path(g, 4, 7)
    return g.result("turf_house", "Turf House", 2, 42, 1, extra_stone=30)

def l_farmhouse():
    """Two wings at a right angle, with a fenced kitchen garden in the corner between them."""
    g = G(13, 12, 11)
    g.box(1, 7, 0, 0, 1, 10, m("oak_planks")); g.box(7, 11, 0, 0, 1, 6, m("oak_planks"))
    g.body(7, 11, 1, 6, 1, 3, m("white_terracotta"), post=log("oak_log"), sill=m("cobblestone"), beam="oak_log", win=2)
    g.roof_x(8, 12, 0, 7, 4, "spruce", gable=m("white_terracotta"), gx=(11,))
    g.body(1, 7, 1, 10, 1, 3, m("white_terracotta"), post=log("oak_log"), sill=m("cobblestone"), beam="oak_log", win=2)
    g.roof_z(0, 8, 0, 11, 4, "spruce", gable=m("white_terracotta"), gz=(1, 10), cap="dark_oak")
    g.put(4, 5, 10, m("glass_pane")); g.put(11, 5, 3, m("glass_pane")); g.put(11, 5, 4, m("glass_pane"))
    g.clear(7, 7, 1, 2, 3, 4)
    g.door(4, 1, 10, "oak")
    g.beds([2, 3, 5, 6], 1, 2, "white"); g.beds([9, 10], 1, 2, "white")
    furnish(g, [(2, 9, "crafting_table"), (2, 8, "barrel[facing=up]", "potted_oxeye_daisy"), (6, 9, "smoker[facing=west]"),
                (6, 7, "oak_fence", "brown_carpet")])
    rug(g, 3, 5, 6, 8, "red")
    g.put(4, 7, 5, lantern()); g.put(9, 6, 3, lantern())
    for x in range(8, 13):
        g.put(x, 1, 11, m("oak_fence"))
    for z in range(7, 11): g.put(12, 1, z, m("oak_fence"))
    for x in range(9, 12):
        for z in range(8, 10): g.put(x, 0, z, m("coarse_dirt"))
    g.put(9, 1, 8, m("hay_block")); g.put(11, 1, 9, m("composter")); g.put(10, 1, 8, m("poppy")); g.put(9, 1, 9, m("dandelion"))
    g.put(12, 2, 11, lantern(False))
    path(g, 4, 11)
    return g.result("farmhouse", "Farmhouse", 6, 100, 2)

def turret_cottage():
    """A cottage with a stone turret at its corner."""
    g = G(11, 9, 13)
    g.box(1, 7, 0, 0, 1, 7, m("oak_planks")); g.box(7, 10, 0, 0, 4, 7, m("stone_bricks"))
    g.body(1, 7, 1, 7, 1, 3, m("bricks"), post=log("dark_oak_log"), sill=m("stone_bricks"), beam="dark_oak_log", win=2)
    g.roof_z(0, 8, 0, 8, 4, "dark_oak", gable=m("bricks"), gz=(1, 7), cap="spruce")
    g.put(4, 5, 7, m("glass_pane"))
    g.body(7, 10, 4, 7, 1, 9, m("stone_bricks"), post=m("polished_andesite"), win=-9)
    for y in (2, 5, 8):
        g.put(10, y, 5, m("iron_bars")); g.put(8, y, 7, m("iron_bars")); g.put(9, y, 7, m("iron_bars")) if y == 8 else None
    g.hip(7, 10, 4, 7, 10, "dark_oak")
    g.clear(8, 9, 1, 9, 5, 6)                                # the cottage's eave runs through here
    g.clear(7, 7, 1, 2, 5, 5)
    g.door(4, 1, 7, "dark_oak")
    g.beds([2, 3, 6], 1, 2, "red")
    furnish(g, [(9, 6, "bookshelf", "bookshelf"), (8, 6, "lectern[facing=north]"), (2, 6, "barrel[facing=up]", "potted_red_tulip")])
    rug(g, 3, 5, 4, 5, "red")
    g.put(4, 7, 4, lantern())
    path(g, 4, 8)
    return g.result("turret_cottage", "Turret Cottage", 3, 65, 2, extra_stone=60)

def saltbox():
    """Tall in front, with the roof running long and low to the back."""
    g = G(9, 11, 11)
    def top(z): return 9 - abs(z - 7) if z >= 7 else 9 - (7 - z)
    g.box(1, 7, 0, 0, 1, 9, m("spruce_planks"))
    for z in range(1, 10):
        for x in range(1, 8):
            for y in range(1, top(z)):
                edge = x in (1, 7) or z in (1, 9)
                if not edge: g.air(x, y, z)
                elif (x in (1, 7) and z in (1, 9)) : g.put(x, y, z, log("spruce_log"))
                elif y == 1: g.put(x, y, z, m("cobblestone"))
                else: g.put(x, y, z, m("oak_planks"))
    for z in range(0, 11):
        for x in range(0, 9):
            if z == 7: g.put(x, 9, z, slab("dark_oak"))
            else: g.put(x, top(z), z, stairs("spruce" if 0 < x < 8 else "dark_oak", "north" if z > 7 else "south"))
    for x in (2, 4, 6):
        g.put(x, 5, 9, m("glass_pane"))
        if x != 4: g.put(x, 2, 9, m("glass_pane"))
    for z in (3, 6): g.put(1, 2, z, m("glass_pane")); g.put(7, 2, z, m("glass_pane"))
    g.door(4, 1, 9)
    g.beds([2, 3, 5, 6], 1, 2, "blue")
    for x in range(2, 7): g.put(x, 4, 8, log("spruce_log", "x"))
    furnish(g, [(2, 8, "crafting_table"), (6, 8, "smoker[facing=west]"), (6, 7, "barrel[facing=up]", "potted_cornflower")])
    rug(g, 3, 5, 6, 7, "blue")
    g.put(4, 3, 8, lantern())
    path(g, 4, 10)
    return g.result("saltbox", "Saltbox House", 4, 63, 2)

def veranda_villa():
    """A hipped roof spread wide over a veranda that runs all the way round."""
    g = G(13, 12, 11)
    g.box(1, 11, 0, 0, 1, 10, m("birch_planks"))
    g.body(3, 9, 3, 8, 1, 3, m("white_terracotta"), post=log("stripped_birch_log"), sill=m("stone_bricks"), beam="stripped_birch_log", win=2)
    for x, z in ((1, 1), (11, 1), (1, 10), (11, 10), (4, 10), (8, 10), (1, 5), (11, 5), (4, 1), (8, 1)):
        g.box(x, x, 1, 3, z, z, m("birch_fence"))
    for x in range(1, 12):
        for z in (1, 10):
            if g.get(x, 1, z) is None and abs(x - 6) > 1: g.put(x, 1, z, m("birch_fence"))
    for z in range(2, 10):
        for x in (1, 11):
            if g.get(x, 1, z) is None: g.put(x, 1, z, m("birch_fence"))
    g.hip(0, 12, 0, 11, 4, "spruce", cap="dark_oak")
    g.door(6, 1, 8, "birch")
    g.beds([4, 5, 7, 8], 1, 4, "yellow")
    furnish(g, [(6, 4, "bookshelf", "bookshelf")])
    g.put(6, 8, 5, lantern())
    g.put(2, 1, 9, m("barrel[facing=up]")); g.put(2, 2, 9, m("potted_azalea_bush")); g.put(10, 1, 9, m("composter"))
    path(g, 6, 11)
    return g.result("veranda_villa", "Veranda Villa", 4, 90, 2, extra_wood=60)

def desert_house():
    """Flat-roofed, with an outside stair up to a walled roof terrace."""
    g = G(9, 9, 8)
    g.box(1, 7, 0, 0, 1, 7, m("smooth_sandstone"))
    g.body(1, 7, 1, 7, 1, 4, m("smooth_sandstone"), post=m("cut_sandstone"), sill=m("sandstone"), win=2)
    for x in (1, 7):
        for z in (2, 4, 6): g.put(x, 2, z, m("smooth_sandstone") if z != 4 else m("glass_pane"))
    g.box(1, 7, 5, 5, 1, 7, m("smooth_sandstone"))
    for x in range(1, 8):
        for z in range(1, 8):
            if x in (1, 7) or z in (1, 7): g.put(x, 6, z, m("sandstone_wall"))
    g.put(7, 6, 1, AIR); g.put(7, 6, 2, AIR)
    for i, z in enumerate((6, 5, 4, 3, 2)): g.put(8, 1 + i, z, stairs("sandstone", "north"))
    for i, z in enumerate((5, 4, 3, 2)): g.box(8, 8, 1, 1 + i, z, z, m("sandstone"))
    g.box(8, 8, 1, 5, 1, 1, m("sandstone"))
    g.door(4, 1, 7, "acacia")
    for x in (3, 5): g.put(x, 1, 8, m("acacia_fence")); g.put(x, 2, 8, m("acacia_fence"))
    for x in (3, 4, 5): g.put(x, 3, 8, slab("acacia"))
    g.beds([2, 3, 6], 1, 2, "orange")
    furnish(g, [(6, 6, "barrel[facing=up]", "potted_cactus"), (2, 6, "decorated_pot[facing=south]")])
    rug(g, 3, 5, 4, 5, "orange")
    g.put(2, 6, 2, m("potted_dead_bush")); g.put(6, 6, 6, m("composter")); g.put(2, 6, 6, m("decorated_pot[facing=south]"))
    g.put(4, 4, 4, lantern())
    return g.result("desert_house", "Terrace House", 3, 49, 1, extra_stone=80)

def barn_house():
    """A barn roof, steep below and shallow above, over a hall with a hayloft."""
    g = G(11, 11, 10)
    prof = {0: 4, 1: 5, 2: 7, 3: 7, 4: 8, 5: 8}                   # lowest roof block, by distance from the eave
    g.box(1, 9, 0, 0, 1, 9, m("oak_planks"))
    g.body(1, 9, 1, 9, 1, 4, m("spruce_planks"), post=log("dark_oak_log"), sill=m("cobblestone"), win=2, glass="glass_pane")
    for z in range(0, 11):
        for x in range(0, 11):
            e = min(x, 10 - x); f = "east" if x < 5 else "west"; w = "dark_oak" if z in (0, 10) else "spruce"
            if e == 0: g.put(x, 4, z, stairs(w, f))
            elif e == 1: g.put(x, 5, z, m(w + "_planks")); g.put(x, 6, z, stairs(w, f))
            elif e == 2: g.put(x, 7, z, stairs(w, f))
            elif e == 3: g.put(x, 7, z, slab(w, "top"))
            else: g.put(x, 8, z, slab(w))
            if 1 <= z <= 9 and e >= 2:
                for y in range(5, prof[e]):
                    if z in (1, 9): g.put(x, y, z, m("spruce_planks"))
                    else: g.air(x, y, z)
    g.put(5, 6, 9, m("glass_pane")); g.put(5, 5, 9, m("glass_pane")); g.put(5, 6, 1, m("glass_pane"))
    for x in (4, 6): g.put(x, 1, 9, log("stripped_dark_oak_log")); g.put(x, 2, 9, log("stripped_dark_oak_log")); g.put(x, 3, 9, log("stripped_dark_oak_log"))
    g.put(5, 3, 9, log("stripped_dark_oak_log", "x"))
    g.door(5, 1, 9, "dark_oak")
    g.beds([2, 3, 4, 6, 7], 1, 2, "brown")
    g.box(2, 8, 4, 4, 2, 4, m("spruce_planks")); g.put(8, 4, 3, AIR)
    for y in range(1, 5): g.put(8, y, 3, m("ladder[facing=west]"))
    g.put(2, 5, 2, m("hay_block")); g.put(3, 5, 2, m("hay_block")); g.put(2, 6, 2, m("hay_block")); g.put(7, 5, 2, m("hay_block"))
    furnish(g, [(2, 8, "hay_block"), (8, 8, "barrel[facing=up]", "potted_dandelion"), (8, 7, "crafting_table")])
    g.put(5, 7, 6, lantern())
    path(g, 5, 10)
    return g.result("barn_house", "Barn House", 5, 81, 2, extra_wood=60)

def courtyard_house():
    """Three ranges round a paved court, entered under a beam between the wings."""
    g = G(13, 11, 10)
    wall, post = m("white_terracotta"), log("dark_oak_log")
    g.box(1, 11, 0, 0, 1, 9, m("spruce_planks")); g.box(5, 7, 0, 0, 6, 10, m("stone_bricks"))
    for x0 in (1, 8):
        g.body(x0, x0 + 3, 5, 9, 1, 3, wall, post=post, sill=m("stone_bricks"), beam="dark_oak_log", win=2)
        g.roof_z(x0 - 1, x0 + 4, 5, 10, 4, "dark_oak", gable=wall, gz=(9,))
    g.body(1, 11, 1, 5, 1, 3, wall, post=post, sill=m("stone_bricks"), beam="dark_oak_log", win=2)
    g.roof_x(0, 12, 0, 6, 4, "dark_oak", gable=wall, gx=(1, 11), cap="spruce")
    for x in (2, 3, 9, 10): g.clear(x, x, 1, 2, 5, 5)
    g.clear(6, 6, 1, 2, 5, 5)                                # the hall opens on the court
    g.clear(5, 7, 1, 3, 6, 8)
    for x in (5, 6, 7):
        g.put(x, 3, 9, log("dark_oak_log", "x"))
        for y in (1, 2): g.put(x, y, 9, wall if y == 1 else m("glass_pane"))
    g.door(6, 1, 9, "dark_oak")
    for x in (5, 7): g.put(x, 1, 8, lantern(False))
    g.put(5, 1, 6, m("flowering_azalea"))
    g.put(7, 1, 6, m("flowering_azalea"))
    g.put(5, 0, 6, m("moss_block")); g.put(7, 0, 6, m("moss_block"))
    g.beds([2, 3, 9, 10], 1, 2, "purple", chest_z=6)
    g.beds([4, 8], 1, 2, "purple", chest_z=False)
    g.put(2, 1, 7, m("chest[facing=south]")); g.put(10, 1, 7, m("chest[facing=south]"))
    furnish(g, [(6, 2, "bookshelf", "bookshelf"), (3, 8, "barrel[facing=up]", "potted_allium"),
                (9, 8, "smoker[facing=north]")])
    rug(g, 5, 7, 3, 4, "purple")
    return g.result("courtyard_house", "Courtyard House", 6, 100, 2, extra_stone=40)

def twin_gable():
    """Two gables side by side, with a valley between."""
    g = G(12, 10, 9)
    g.box(1, 10, 0, 0, 1, 8, m("dark_oak_planks"))
    g.body(1, 10, 1, 8, 1, 3, m("terracotta"), post=log("spruce_log"), sill=m("mossy_stone_bricks"), beam="spruce_log", win=-9)
    for x in (3, 8): g.put(x, 2, 1, m("glass_pane")); g.put(x, 2, 8, m("glass_pane"))
    for z in (3, 6): g.put(1, 2, z, m("glass_pane")); g.put(10, 2, z, m("glass_pane"))
    g.roof_z(0, 5, 0, 9, 4, "spruce", gable=m("terracotta"), gz=(1, 8))
    g.roof_z(6, 11, 0, 9, 4, "spruce", gable=m("terracotta"), gz=(1, 8))
    for z in range(1, 9): g.put(5, 3, z, log("spruce_log", "z")) if z in (1, 8) else None
    g.put(2, 5, 8, m("glass_pane")); g.put(3, 5, 8, m("glass_pane")); g.put(8, 5, 8, m("glass_pane")); g.put(9, 5, 8, m("glass_pane"))
    g.door(3, 1, 8)
    g.put(8, 2, 8, m("glass_pane")); g.put(7, 2, 8, m("glass_pane")); g.put(9, 2, 8, m("glass_pane"))
    g.put(7, 1, 9, stairs("spruce", "north", "top")); g.put(8, 1, 9, stairs("spruce", "north", "top")); g.put(9, 1, 9, stairs("spruce", "north", "top"))
    g.put(7, 2, 9, m("potted_red_tulip")); g.put(8, 2, 9, m("potted_fern")); g.put(9, 2, 9, m("potted_red_tulip"))
    g.beds([2, 3, 4, 7, 8, 9], 1, 2, "green")
    for y in (1, 2): g.put(5, y, 2, m("bookshelf")); g.put(6, y, 2, m("bookshelf"))
    furnish(g, [(9, 7, "barrel[facing=up]", "potted_fern"), (6, 7, "crafting_table")])
    rug(g, 4, 7, 4, 6, "green")
    g.put(5, 3, 5, log("spruce_log", "z")); g.put(6, 3, 5, log("spruce_log", "z"))
    path(g, 3, 9)
    return g.result("twin_gable_house", "Twin-Gable House", 6, 80, 2)

def keep_house():
    """A square stone keep of three floors, with a walk round the top."""
    g = G(9, 9, 15)
    stone = m("stone_bricks")
    g.box(1, 7, 0, 0, 1, 7, m("spruce_planks"))
    g.body(1, 7, 1, 7, 1, 11, stone, post=m("polished_andesite"), win=-9)
    for y in (4, 8): g.box(2, 6, y, y, 2, 6, m("spruce_planks")); g.put(2, y, 2, AIR)
    for y in range(1, 12): g.put(2, y, 2, m("ladder[facing=south]"))
    for y in (2, 6, 10):
        for x, z in ((4, 1), (4, 7), (1, 4), (7, 4)):
            if not (y == 2 and z == 7): g.put(x, y, z, m("iron_bars"))
    for x in range(0, 9):
        for z in range(0, 9):
            if x in (0, 8) or z in (0, 8):
                f = "east" if x == 0 else "west" if x == 8 else "south" if z == 0 else "north"
                g.put(x, 11, z, stairs("stone_brick", f, "top"))
                g.put(x, 12, z, stone)
                if (x + z) % 2 == 0: g.put(x, 13, z, stone)
            else: g.put(x, 12, z, m("smooth_stone") if (x, z) != (2, 2) else AIR)
    g.put(4, 13, 4, m("cobblestone_wall")); g.put(4, 14, 4, lantern(False))
    g.door(4, 1, 7, "dark_oak")
    g.beds([4, 5, 6], 1, 2, "gray")
    furnish(g, [(6, 6, "barrel[facing=up]"), (2, 6, "crafting_table")])
    furnish(g, [(4, 2, "bookshelf", "bookshelf"), (5, 2, "bookshelf", "bookshelf"), (6, 2, "bookshelf", "bookshelf"), (6, 5, "lectern[facing=west]")], y=5)
    rug(g, 3, 5, 4, 5, "red", y=5)
    furnish(g, [(5, 2, "barrel[facing=up]"), (6, 2, "barrel[facing=up]", "barrel[facing=up]"), (6, 6, "hay_block"), (4, 6, "decorated_pot[facing=south]")], y=9)
    for y in (3, 7, 11): g.put(4, y, 4, lantern())
    path(g, 4, 8)
    return g.result("keep_house", "Keep House", 3, 49, 2, extra_stone=200)

def dormer_cottage():
    """Low eaves and a broad roof, with two dormer windows looking out of it."""
    g = G(11, 10, 9)
    g.box(1, 9, 0, 0, 1, 7, m("birch_planks"))
    g.body(1, 9, 1, 7, 1, 2, m("white_terracotta"), post=log("stripped_oak_log"), sill=m("cobblestone"), win=2)
    g.roof_x(0, 10, 0, 8, 3, "oak", gable=m("white_terracotta"), gx=(1, 9), cap="spruce")
    for x in (3, 7):
        g.put(x, 5, 7, m("glass_pane")); g.put(x - 1, 5, 7, m("oak_planks")); g.put(x + 1, 5, 7, m("oak_planks"))
        for a in (-1, 0, 1): g.put(x + a, 6, 7, slab("spruce"))
        g.put(x, 5, 6, AIR); g.put(x, 6, 6, slab("spruce"))
    g.put(1, 4, 4, m("glass_pane")); g.put(9, 4, 4, m("glass_pane"))
    g.door(5, 1, 7, "oak")
    for x in (4, 6): g.put(x, 1, 8, m("oak_fence")); g.put(x, 2, 8, m("oak_fence"))
    g.beds([2, 3, 8], 1, 2, "pink")
    furnish(g, [(8, 6, "barrel[facing=up]", "potted_pink_tulip"), (2, 6, "loom[facing=south]"), (5, 2, "bookshelf", "bookshelf")])
    rug(g, 4, 6, 4, 5, "pink")
    g.put(5, 6, 4, lantern())
    for x in (2, 8): g.put(x, 0, 8, m("grass_block")); g.put(x, 1, 8, m("flowering_azalea"))
    path(g, 5, 9)
    return g.result("dormer_cottage", "Dormer Cottage", 3, 63, 1)

def townhouse():
    """Narrow and tall, each floor leaning out a little further over the street."""
    g = G(7, 10, 16)
    g.box(1, 5, 0, 0, 1, 6, m("spruce_planks"))
    g.body(1, 5, 1, 6, 1, 3, m("stone_bricks"), post=m("polished_andesite"), win=2)
    g.box(1, 5, 4, 4, 1, 7, m("spruce_planks"))
    g.body(1, 5, 1, 7, 5, 7, m("white_terracotta"), post=log("dark_oak_log"), beam="dark_oak_log", win=6)
    g.box(1, 5, 8, 8, 1, 8, m("spruce_planks"))
    g.body(1, 5, 1, 8, 9, 10, m("white_terracotta"), post=log("dark_oak_log"), win=10)
    g.roof_z(0, 6, 0, 9, 11, "dark_oak", gable=m("white_terracotta"), gz=(1, 8), cap="spruce")
    g.put(3, 12, 8, m("glass_pane"))
    for y in (4, 8): g.put(3, y, 2, AIR)
    for y in range(1, 10): g.put(3, y, 2, m("ladder[facing=south]"))
    for x in (2, 4): g.put(x, 3, 7, stairs("dark_oak", "north", "top")); g.put(x, 7, 8, stairs("dark_oak", "north", "top"))
    g.put(3, 3, 7, lantern())
    g.door(3, 1, 6)
    g.beds([2, 4], 1, 2, "light_blue")
    furnish(g, [(4, 5, "bookshelf", "bookshelf"), (2, 5, "lectern[facing=east]"), (4, 3, "barrel[facing=up]", "potted_cornflower")], y=5)
    rug(g, 2, 4, 4, 6, "light_blue", y=5)
    furnish(g, [(2, 6, "barrel[facing=up]"), (4, 6, "barrel[facing=up]"), (4, 4, "hay_block"), (2, 4, "decorated_pot[facing=south]")], y=9)
    g.put(3, 7, 4, lantern())
    path(g, 3, 7)
    return g.result("townhouse", "Townhouse", 2, 35, 2, extra_wood=120, extra_stone=30)

def log_cabin():
    """Round logs, a single-pitched roof, and a porch along the front and one side."""
    g = G(11, 10, 9)
    g.box(1, 9, 0, 0, 1, 8, m("spruce_planks"))
    def roof(z):                                             # (layer, half) of the roof over this row
        i = 9 - z
        return 4 + i // 4, "bottom" if (i // 2) % 2 == 0 else "top"
    for z in range(1, 7):
        for x in range(1, 8):
            edge = x in (1, 7) or z in (1, 6)
            ry, half = roof(z)
            for y in range(1, ry):
                if not edge: g.air(x, y, z)
                elif x in (1, 7) and z in (1, 6): g.put(x, y, z, log("spruce_log"))
                else: g.put(x, y, z, log("spruce_log", "x" if z in (1, 6) else "z"))
    for x in (3, 5): g.put(x, 2, 6, m("glass_pane")) if x != 4 else None
    for z in (3, 4): g.put(1, 2, z, m("glass_pane")); g.put(7, 2, z, m("glass_pane"))
    g.put(3, 5, 1, m("glass_pane")); g.put(5, 5, 1, m("glass_pane"))
    for z in range(0, 10):
        ry, half = roof(z)
        for x in range(0, 11):
            g.put(x, ry, z, slab("dark_oak" if x in (0, 10) or z in (0, 9) else "spruce", half))
    for x, z in ((1, 8), (5, 8), (9, 8), (9, 4), (9, 1)):
        ry, _ = roof(z)
        g.box(x, x, 1, ry - 1, z, z, m("spruce_fence"))
    for x in range(1, 10):
        if g.get(x, 1, 8) is None and x not in (3, 4): g.put(x, 1, 8, m("spruce_fence"))
    for z in range(2, 8):
        if g.get(9, 1, z) is None: g.put(9, 1, z, m("spruce_fence"))
    g.door(4, 1, 6)
    g.beds([2, 6], 1, 2, "red", chest_z=5)
    furnish(g, [(4, 2, "smoker[facing=south]"), (8, 2, "barrel[facing=up]", "potted_fern"), (8, 6, "hay_block"), (8, 5, "composter")])
    rug(g, 3, 5, 3, 4, "red")
    return g.result("log_cabin", "Log Cabin", 2, 60, 1, extra_wood=40)

def pagoda():
    """Two tiers of roof, the upper one over a lantern storey."""
    g = G(11, 12, 13)
    roof = "dark_prismarine"
    g.box(2, 8, 0, 0, 2, 8, m("cherry_planks"))
    g.body(2, 8, 2, 8, 1, 3, m("cherry_planks"), post=log("dark_oak_log"), sill=m("stone_bricks"), win=2, glass="white_stained_glass_pane")
    for x in range(0, 11):
        for z in range(0, 11):
            if x in (0, 10) or z in (0, 10):
                corner = x in (0, 10) and z in (0, 10)
                g.put(x, 4 if corner else 3, z, slab(roof, "bottom" if corner else "top"))
    g.hip(1, 9, 1, 9, 4, roof, layers=2)
    g.body(3, 7, 3, 7, 5, 7, m("cherry_planks"), post=log("dark_oak_log"), win=6, glass="white_stained_glass_pane")
    g.clear(4, 6, 4, 4, 4, 6)
    for x in range(1, 10):
        for z in range(1, 10):
            if x in (1, 9) or z in (1, 9):
                corner = x in (1, 9) and z in (1, 9)
                g.put(x, 8 if corner else 7, z, slab(roof, "bottom" if corner else "top"))
    g.hip(2, 8, 2, 8, 8, roof, layers=3)
    g.put(5, 11, 5, m("dark_prismarine")); g.put(5, 12, 5, lantern(False))
    g.put(5, 10, 5, lantern())
    g.door(5, 1, 8, "cherry")
    g.beds([3, 4, 6, 7], 1, 3, "pink")
    furnish(g, [(7, 7, "decorated_pot[facing=south]"), (3, 7, "barrel[facing=up]", "potted_pink_tulip")])
    for x in (3, 7): g.put(x, 1, 9, m("cherry_fence")); g.put(x, 2, 9, lantern(False))
    path(g, 5, 9); path(g, 5, 10); path(g, 5, 11)
    return g.result("pagoda_house", "Pagoda House", 4, 81, 2, extra_stone=60)

def greenhouse_cottage():
    """A cottage with a glasshouse built against its side."""
    g = G(12, 9, 9)
    g.box(1, 6, 0, 0, 1, 7, m("oak_planks")); g.box(7, 10, 0, 0, 2, 6, m("moss_block"))
    g.body(1, 6, 1, 7, 1, 3, m("bricks"), post=log("stripped_spruce_log"), sill=m("stone_bricks"), beam="stripped_spruce_log", win=2)
    g.roof_z(0, 7, 0, 8, 4, "spruce", gable=m("bricks"), gz=(1, 7))
    g.put(3, 5, 7, m("glass_pane")); g.put(4, 5, 7, m("glass_pane"))
    for x in range(7, 11):
        for z in range(2, 7):
            edge = x == 10 or z in (2, 6)
            corner = x == 10 and z in (2, 6)
            for y in (1, 2):
                if corner: g.put(x, y, z, log("stripped_spruce_log"))
                elif edge: g.put(x, y, z, m("glass"))
                else: g.air(x, y, z)
            g.put(x, 3, z, m("glass") if not (x == 10 or z in (2, 6)) else slab("spruce"))
    g.clear(6, 6, 1, 2, 4, 4)
    for x, z, f in ((8, 3, "flowering_azalea"), (9, 3, "fern"), (9, 5, "azalea"), (8, 5, "poppy"), (9, 4, "cornflower")): g.put(x, 1, z, m(f))
    g.put(7, 1, 3, m("composter")); g.put(7, 1, 5, m("barrel[facing=up]"))
    g.door(3, 1, 7, "oak")
    g.beds([2, 5], 1, 2, "lime")
    furnish(g, [(2, 6, "crafting_table"), (5, 6, "barrel[facing=up]", "potted_fern")])
    rug(g, 3, 4, 3, 5, "lime")
    path(g, 3, 8)
    return g.result("greenhouse_cottage", "Greenhouse Cottage", 2, 62, 1)

def vault_house():
    """A rounded roof of weathered copper over brick walls."""
    g = G(9, 10, 8)
    cu = "waxed_weathered_cut_copper"
    g.box(1, 7, 0, 0, 1, 8, m("dark_oak_planks"))
    g.body(1, 7, 1, 8, 1, 2, m("bricks"), post=m("stone_bricks"), sill=m("stone_bricks"), win=2)
    for z in range(0, 10):
        for x in range(0, 9):
            e = min(x, 8 - x); f = "east" if x < 4 else "west"
            if e == 0: g.put(x, 2, z, stairs(cu, f, "top")); g.put(x, 3, z, stairs(cu, f))
            elif e == 1: g.put(x, 4, z, stairs(cu, f))
            elif e == 2: g.put(x, 4, z, slab(cu, "top"))
            else: g.put(x, 5, z, slab(cu))
            if 1 <= z <= 8:
                for y in range(3, {0: 3, 1: 4, 2: 4, 3: 5, 4: 5}[e]):
                    if e >= 1:
                        if z in (1, 8): g.put(x, y, z, m("bricks"))
                        else: g.air(x, y, z)
                if e >= 3 and z in (1, 8): g.put(x, 4, z, m("glass_pane") if x == 4 else m("bricks"))
                elif e >= 3: g.air(x, 4, z)
    g.door(4, 1, 8, "dark_oak")
    g.beds([2, 3, 6], 1, 2, "cyan")
    furnish(g, [(6, 7, "barrel[facing=up]", "potted_blue_orchid"), (2, 7, "bookshelf", "bookshelf"), (6, 6, "crafting_table")])
    rug(g, 3, 5, 5, 6, "cyan")
    g.put(4, 4, 4, lantern())
    path(g, 4, 9)
    return g.result("vault_house", "Copper Vault House", 3, 56, 2, extra_stone=80)

def chalet():
    """Four gables, one to each side, and a balcony over the door."""
    g = G(11, 12, 11)
    def top(x, z): return 4 + max(min(x, 10 - x), min(z, 10 - z))
    g.box(1, 9, 0, 0, 1, 9, m("spruce_planks"))
    g.body(1, 9, 1, 9, 1, 3, m("stone_bricks"), post=log("spruce_log"), sill=m("cobblestone"), win=2)
    for x in range(0, 11):
        for z in range(0, 11):
            a, b = min(x, 10 - x), min(z, 10 - z)
            y = top(x, z)
            if a == b: g.put(x, y, z, m("dark_oak_planks") if a < 5 else slab("dark_oak"))
            elif a > b: g.put(x, y, z, stairs("spruce", "east" if x < 5 else "west") if a < 5 else slab("dark_oak"))
            else: g.put(x, y, z, stairs("spruce", "south" if z < 5 else "north") if b < 5 else slab("dark_oak"))
            if 1 <= x <= 9 and 1 <= z <= 9:
                for yy in range(4, y):
                    if x in (1, 9) or z in (1, 9): g.put(x, yy, z, m("spruce_planks"))
                    else: g.air(x, yy, z)
    for x, z in ((5, 1), (1, 5), (9, 5)): g.put(x, 6, z, m("glass_pane")); g.put(x, 5, z, m("glass_pane"))
    g.box(2, 8, 4, 4, 2, 4, m("spruce_planks")); g.put(8, 4, 3, AIR)
    for y in range(1, 5): g.put(8, y, 3, m("ladder[facing=west]"))
    for x in range(3, 8): g.put(x, 4, 10, slab("spruce", "top")); g.put(x, 5, 10, m("spruce_fence"))
    for x in (3, 7): g.put(x, 1, 10, m("spruce_fence")); g.put(x, 2, 10, m("spruce_fence")); g.put(x, 3, 10, m("spruce_fence"))
    g.put(5, 5, 9, m("glass_pane")); g.put(5, 6, 9, m("glass_pane")); g.put(4, 5, 9, m("glass_pane")); g.put(6, 5, 9, m("glass_pane"))
    g.door(5, 1, 9)
    g.beds([2, 3, 4, 6, 7], 1, 2, "red")
    furnish(g, [(8, 8, "smoker[facing=west]"), (8, 7, "barrel[facing=up]", "potted_red_tulip"), (2, 8, "crafting_table")])
    furnish(g, [(2, 2, "hay_block"), (3, 2, "barrel[facing=up]"), (5, 2, "bookshelf")], y=5)
    rug(g, 4, 6, 6, 7, "red")
    g.put(5, 8, 6, lantern())
    path(g, 5, 10); path(g, 5, 11)
    return g.result("chalet", "Chalet", 5, 81, 2, extra_wood=60)

builders = [a_frame, roundhouse, stilt_house, turf_house, l_farmhouse, turret_cottage, saltbox, veranda_villa, desert_house,
            barn_house, courtyard_house, twin_gable, keep_house, dormer_cottage, townhouse, log_cabin, pagoda,
            greenhouse_cottage, vault_house, chalet]

def check(d):
    """What the game needs of a house: every bed with free floor at its foot and a chest, a door that opens on air."""
    g, problems = d["_grid"], []
    heads = [(p, b) for p, b in g.c.items() if "part=head" in b]
    if len(heads) != d["beds"]: problems.append(f"{len(heads)} bed heads for {d['beds']} beds")
    chests = [p for p, b in g.c.items() if b.startswith("minecraft:chest")]
    if len(chests) < d["beds"]: problems.append(f"{len(chests)} chests")
    for (x, y, z), _ in heads:
        if g.get(x, y, z + 2) not in (AIR,) and "carpet" not in (g.get(x, y, z + 2) or ""): problems.append(f"bedside of {x},{z} is {g.get(x, y, z + 2)}")
        if g.get(x, y + 1, z + 2) not in (AIR, None): problems.append(f"no headroom at bedside {x},{z}")
        if g.get(x, y - 1, z) in (AIR, None): problems.append(f"bed {x},{z} has no floor")
    for (x, y, z), b in g.c.items():
        if "door[half=lower" in b:
            for dz in (-1, 1):
                for dy in (0, 1):
                    if 0 <= z + dz < g.D and g.get(x, y + dy, z + dz) not in (AIR, None) and "carpet" not in g.get(x, y + dy, z + dz):
                        problems.append(f"door blocked at {x},{y + dy},{z + dz} by {g.get(x, y + dy, z + dz)}")
        if b == lantern() and g.get(x, y + 1, z) in (AIR, None): problems.append(f"lantern hangs from nothing at {x},{y},{z}")
        if "ladder" in b or "carpet" in b or "potted" in b:
            if "ladder" not in b and g.get(x, y - 1, z) in (AIR, None): problems.append(f"{b} floats at {x},{y},{z}")
    return problems

designs = [b() for b in builders]

if __name__ == "__main__":
    out = sys.argv[1]
    bad = 0
    for d in designs:
        problems = check(d)
        bad += len(problems)
        g = d.pop("_grid")
        open(os.path.join(out, d["id"] + ".json"), "w").write(dump(d))
        print(d["id"], f"{g.W}x{g.D}x{len(d['layers'])}", d["cost"], d["beds"], "palette", len(d["palette"]), *problems)
        if len(sys.argv) > 2 and sys.argv[2] in (d["id"], "all"):
            for i, layer in enumerate(d["layers"]):
                print("layer", i); print("\n".join("|" + r + "|" for r in layer))
    sys.exit(1 if bad else 0)
