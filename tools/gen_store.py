"""Draws the three tiers of the main storehouse (great_store_1..3) on one 25x25 lot, and
great_store_0: the hall of tier 1 by itself, on a 13x15 lot.

Each tier contains everything of the tier before it, so enlarging the building only adds blocks
(and opens the two doorways into the wings).
"""
import json, os, sys
OUT = sys.argv[1]
N = 25
HX0, HX1, HZ0, HZ1 = 7, 17, 2, 14      # hall walls
HH = 5                                   # hall wall height
CX = 12                                  # aisle / door column

PALETTE = {
    'C': "minecraft:cobblestone", 'F': "minecraft:spruce_planks", 'f': "minecraft:polished_andesite",
    'S': "minecraft:stone_bricks", 'P': "minecraft:spruce_planks", 'A': "minecraft:spruce_planks",
    'L': "minecraft:dark_oak_log[axis=y]", 'X': "minecraft:dark_oak_log[axis=x]", 'Y': "minecraft:dark_oak_log[axis=z]",
    'G': "minecraft:glass_pane",
    'W': "minecraft:dark_oak_stairs[facing=east,half=bottom]", 'E': "minecraft:dark_oak_stairs[facing=west,half=bottom]",
    'R': "minecraft:dark_oak_slab[type=bottom]",
    'D': "minecraft:spruce_door[half=lower,facing=north,hinge=left]", 'd': "minecraft:spruce_door[half=upper,facing=north,hinge=left]",
    'n': "minecraft:lantern[hanging=true]", 'l': "minecraft:lantern[hanging=false]",
    'k': "minecraft:spruce_fence", 'o': "minecraft:oak_leaves[persistent=true]",
    # emblems over the sections
    'h': "minecraft:hay_block[axis=y]", 'g': "minecraft:stripped_oak_wood[axis=y]", 'c': "minecraft:chiseled_stone_bricks",
    'r': "minecraft:raw_iron_block", 's': "minecraft:smithing_table",
    # wings and yard
    'M': "minecraft:mossy_stone_bricks", 'B': "minecraft:stone_bricks", 'b': "minecraft:stone_brick_slab[type=bottom]",
    'w': "minecraft:stone_brick_wall", 'p': "minecraft:stone_bricks",
    'H': "minecraft:hay_block[axis=y]", 'U': "minecraft:barrel[facing=up]", 'Q': "minecraft:composter",
    'V': "minecraft:water_cauldron[level=3]", 'Z': "minecraft:bell[attachment=floor,facing=north]",
    # chests: commodity and the way they face
    '1': "minecraft:chest[facing=east]", '2': "minecraft:chest[facing=east]", '3': "minecraft:chest[facing=west]",
    '4': "minecraft:chest[facing=west]", '5': "minecraft:chest[facing=south]", '6': "minecraft:chest[facing=west]",
    '7': "minecraft:chest[facing=east]", '8': "minecraft:chest[facing=south]", '9': "minecraft:chest[facing=north]",
    '0': "minecraft:chest[facing=west]", '@': "minecraft:chest[facing=south]", '#': "minecraft:chest[facing=north]",
    '%': "minecraft:chest[facing=west]", '&': "minecraft:chest[facing=east]",
}
STORAGE = {'1': "food", '2': "wood", '3': "stone", '4': "ore", '5': "tools", '6': "wood", '7': "stone",
           '8': "food", '9': "food", '0': "ore", '@': "ore", '#': "ore", '%': "food", '&': "ore"}

def build(tier):
    H = 13
    g = [[[' '] * N for _ in range(N)] for _ in range(H)]
    def put(y, z, x, ch): g[y][z][x] = ch
    inside = lambda x, z: HX0 < x < HX1 and HZ0 < z < HZ1
    # ---- the hall
    for z in range(HZ0, HZ1 + 1):
        for x in range(HX0, HX1 + 1):
            put(0, z, x, ('f' if x == CX else 'F') if inside(x, z) else 'C')
            for y in range(1, HH + 1):
                if inside(x, z):
                    put(y, z, x, '.'); continue
                corner = x in (HX0, HX1) and z in (HZ0, HZ1)
                ns_wall = z in (HZ0, HZ1)
                i = (x - HX0) if ns_wall else (z - HZ0)
                post = corner or (i == 5 if ns_wall else i % 3 == 0)
                if post and (y >= 2 or corner): ch = 'L'
                elif y == 1: ch = 'S'
                elif y == HH: ch = 'X' if ns_wall else 'Y'
                elif y == 4 and not post and (i % 2 == 0 if ns_wall else i % 3 == 1): ch = 'G'
                else: ch = 'P'
                put(y, z, x, ch)
    put(1, HZ1, CX, 'D'); put(2, HZ1, CX, 'd'); put(3, HZ1, CX, 'P')
    # emblems
    put(3, 5, HX0, 'h'); put(3, 11, HX0, 'g'); put(3, 5, HX1, 'c'); put(3, 11, HX1, 'r'); put(3, HZ0, CX, 's')
    # tie beams with lanterns
    for z in (5, 8, 11):
        for x in range(HX0 + 1, HX1):
            put(HH, z, x, 'X')
        put(HH - 1, z, CX, 'n')
    # roof: ridge north to south
    W = HX1 - HX0 + 1
    for k in range(0, W // 2 + 2):
        lo, hi = HX0 - 1 + k, HX1 + 1 - k
        y = HH + 1 + k
        for z in range(HZ0 - 1, HZ1 + 2):
            if lo == hi:
                put(y, z, lo, 'R'); continue
            put(y, z, lo, 'W'); put(y, z, hi, 'E')
            for x in range(lo + 1, hi):
                if k == 0:
                    if not inside(x, z) and HZ0 <= z <= HZ1:
                        put(y, z, x, 'L' if (x in (HX0, HX1) and z in (HZ0, HZ1)) else ('X' if z in (HZ0, HZ1) else 'Y'))
                    elif inside(x, z):
                        put(y, z, x, '.')
                else:
                    put(y, z, x, 'A' if z in (HZ0, HZ1) else ('.' if HZ0 < z < HZ1 else ' '))
        if lo == hi: break
    put(HH + 2, HZ1, CX, 'G'); put(HH + 3, HZ1, CX, 'G')
    # wall chests, two high like racks: five sections
    for y in (1, 2):
        for z in range(3, 8):
            put(y, z, HX0 + 1, '1'); put(y, z, HX1 - 1, '3')
        for z in range(9, 14):
            put(y, z, HX0 + 1, '2'); put(y, z, HX1 - 1, '4')
        for x in range(10, 15):
            put(y, 3, x, '5')
    # two double rows down the hall, back to back, each half in the section of the wall beside it
    for z in range(5, 13):
        north = z <= 8
        put(1, z, 10, '%' if north else '6'); put(1, z, 11, '1' if north else '2')
        put(1, z, 13, '3' if north else '4'); put(1, z, 14, '7' if north else '&')
    # lantern posts by the door
    for x in (CX - 2, CX + 2):
        put(1, HZ1 + 1, x, 'k'); put(2, HZ1 + 1, x, 'l')
    if tier >= 2:
        # two stone wings with flat roofs and parapets
        for side in (-1, 1):
            wall_x = HX0 if side < 0 else HX1          # shared with the hall
            outer = 1 if side < 0 else 23
            xs = range(1, HX0) if side < 0 else range(HX1 + 1, 24)
            for z in range(5, 12):
                for x in xs:
                    edge = x == outer or z in (5, 11)
                    put(0, z, x, 'C' if edge else 'F')
                    for y in range(1, 4):
                        if not edge: put(y, z, x, '.')
                        elif x == outer and z in (5, 11): put(y, z, x, 'B')
                        elif y == 1: put(y, z, x, 'M')
                        elif y == 2 and ((x == outer and z in (7, 9)) or (z in (5, 11) and x in (outer + 2 * (-side) * -1 + 0,))): put(y, z, x, 'G')
                        else: put(y, z, x, 'B')
                    put(4, z, x, 'B')
                    if edge:
                        i = z if x == outer else x
                        put(5, z, x, 'B' if (x == outer and z in (5, 11)) or i % 2 == 1 else 'b')
            mid = (outer + wall_x) // 2
            put(2, 5, mid, 'G'); put(2, 11, mid, 'G')
            put(3, 8, mid, 'n')
            # doorway from the hall
            put(1, 8, wall_x, '.'); put(2, 8, wall_x, '.')
            inner = outer - side                         # first interior column
            if side < 0:
                for z in range(6, 11): put(1, z, inner, '1')
                for x in (3, 4, 5): put(1, 6, x, '8'); put(1, 10, x, '9')
            else:
                for z in range(6, 11): put(1, z, inner, '0')
                for x in (19, 20, 21): put(1, 6, x, '@'); put(1, 10, x, '#')
    if tier >= 3:
        # walled yard in front, with a paved way from the gate to the door
        for z in range(15, 25):
            for x in range(CX - 1, CX + 2):
                put(0, z, x, 'p')
        for z in range(15, 24):
            put(1, z, 3, 'w'); put(1, z, 21, 'w')
        for x in range(3, 22):
            if abs(x - CX) > 1:
                put(1, 23, x, 'w')
        for x in (3, 21, CX - 2, CX + 2):
            put(1, 23, x, 'B'); put(2, 23, x, 'B'); put(3, 23, x, 'l')
        put(1, 17, 5, 'H'); put(1, 18, 5, 'H'); put(1, 17, 6, 'H'); put(2, 17, 5, 'H')
        put(1, 17, 19, 'U'); put(1, 18, 19, 'U'); put(1, 17, 18, 'U'); put(2, 17, 19, 'U')
        put(1, 21, 6, 'Q'); put(1, 21, 18, 'V')
        put(1, 20, 8, 'B'); put(2, 20, 8, 'Z')
        for x in (8, 9, 15, 16):
            put(1, 15, x, 'o')
    layers = [["".join(r) for r in layer] for layer in g]
    while all(set(r) <= {' '} for r in layers[-1]):
        layers.pop()
    used = set("".join("".join(l) for l in layers))
    cost = {1: (240, 120), 2: (260, 220), 3: (200, 260)}[tier]
    return {
        "id": f"great_store_{tier}", "name": ["Storehouse Hall", "Storehouse", "Great Storehouse"][tier - 1],
        "category": "storage", "beds": 0, "cost": {"wood": cost[0], "stone": cost[1]},
        "late": "".join(k for k in "DdnlZ" if k in used),
        "storage": {k: v for k, v in STORAGE.items() if k in used},
        "palette": {k: v for k, v in PALETTE.items() if k in used},
        "layers": layers,
    }

def dump(d):
    lines = ["{"]
    for k in ("id", "name", "category", "beds", "cost", "late", "storage"):
        lines.append(f"\t{json.dumps(k)}: {json.dumps(d[k])},")
    lines.append('\t"palette": {')
    items = list(d["palette"].items())
    lines += [f"\t\t{json.dumps(k)}: {json.dumps(v)}" + ("," if i < len(items) - 1 else "") for i, (k, v) in enumerate(items)]
    lines += ["\t},", '\t"layers": [']
    for i, layer in enumerate(d["layers"]):
        lines.append("\t\t[")
        lines += [f"\t\t\t{json.dumps(row)}" + ("," if j < len(layer) - 1 else "") for j, row in enumerate(layer)]
        lines.append("\t\t]" + ("," if i < len(d["layers"]) - 1 else ""))
    lines += ["\t]", "}"]
    return "\n".join(lines) + "\n"

def compact():
    """The hall alone on a lot of its own size, for a settlement that has no 25x25 left to give."""
    d = build(1)
    d["id"] = "great_store_0"
    d["layers"] = [[row[6:19] for row in layer[1:16]] for layer in d["layers"]]
    return d

for tier in (0, 1, 2, 3):
    d = compact() if tier == 0 else build(tier)
    open(os.path.join(OUT, d["id"] + ".json"), "w").write(dump(d))
    if len(sys.argv) > 2 and int(sys.argv[2]) == tier:
        for i, layer in enumerate(d["layers"]):
            print("layer", i); print("\n".join("|" + r + "|" for r in layer))
