"""Draws what a settlement builds once it may grow beyond its first wall: the stone gate and tower
of the second ring (the wooden ones, in stone), and the public buildings: well, fountain, tavern
and chapel.

Run: python3 tools/gen_stage2.py <blueprints dir>
"""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from gen_houses import dump
from gen_fine_houses import fine_house

OUT = sys.argv[1] if len(sys.argv) > 1 else "."

def in_stone(source, id, name, swaps, cost):
    d = json.load(open(os.path.join(OUT, source + ".json")))
    d["id"], d["name"], d["cost"] = id, name, cost
    d["palette"] = {k: swaps.get(v, v) for k, v in d["palette"].items()}
    return d

stone_gate = in_stone("palisade_gate", "stone_gate", "Stone Gate", {
    "minecraft:dark_oak_log[axis=y]": "minecraft:stone_bricks",
    "minecraft:dark_oak_log[axis=x]": "minecraft:chiseled_stone_bricks",
    "minecraft:spruce_planks": "minecraft:polished_andesite",
    "minecraft:dark_oak_slab[type=bottom]": "minecraft:stone_brick_slab[type=bottom]",
    "minecraft:spruce_fence": "minecraft:stone_brick_wall",
}, {"wood": 20, "stone": 140})

stone_tower = in_stone("watchtower", "stone_tower", "Stone Tower", {
    "minecraft:spruce_log[axis=y]": "minecraft:stone_bricks",
    "minecraft:stripped_spruce_log[axis=y]": "minecraft:cobblestone",
    "minecraft:spruce_planks": "minecraft:polished_andesite",
    "minecraft:spruce_fence": "minecraft:stone_brick_wall",
    "minecraft:dark_oak_slab[type=bottom]": "minecraft:stone_brick_slab[type=bottom]",
    "minecraft:dark_oak_slab[type=top]": "minecraft:stone_brick_slab[type=top]",
}, {"wood": 20, "stone": 220})

def grid(w, d, h):
    return [[[' '] * w for _ in range(d)] for _ in range(h)]

def pack(g, id, name, palette, cost, late=""):
    layers = [["".join(r) for r in layer] for layer in g]
    used = set("".join("".join(l) for l in layers))
    return {"id": id, "name": name, "category": "public", "beds": 0, "cost": cost,
            "late": "".join(k for k in late if k in used), "palette": {k: v for k, v in palette.items() if k in used}, "layers": layers}

# --- The well: a ring of stone round the water, four posts and a little roof with the lamp under it.
g = grid(5, 5, 5)
for z in range(1, 4):
    for x in range(1, 4):
        centre = x == 2 and z == 2
        g[0][z][x] = 'w' if centre else 'S'
        g[1][z][x] = '.' if centre else ('k' if (x != 2 and z != 2) else 'c')
        g[2][z][x] = 'f' if (x != 2 and z != 2) else '.'
        g[3][z][x] = 'r'
g[4][2][2] = 'r'
g[2][2][2] = 'n'
well = pack(g, "well", "Well", {
    'S': "minecraft:stone_bricks", 'w': "minecraft:water", 'k': "minecraft:mossy_stone_bricks", 'c': "minecraft:cobblestone_wall",
    'f': "minecraft:spruce_fence", 'r': "minecraft:dark_oak_slab[type=bottom]", 'n': "minecraft:lantern[hanging=true]",
}, {"wood": 30, "stone": 40}, "n")

# --- The fountain: a square basin with a pillar the water runs down, and benches at the corners.
g = grid(9, 9, 6)
for z in range(1, 8):
    for x in range(1, 8):
        rim = x in (2, 6) or z in (2, 6)
        outer = x in (1, 7) or z in (1, 7)
        if outer:
            g[0][z][x] = 'p'                       # paving round the basin
            continue
        g[0][z][x] = 'S'
        g[1][z][x] = 'S' if rim else '.'
for y in range(1, 4):
    g[y][4][4] = 'C'
g[4][4][4] = 'w'
for x, z, key in ((1, 1, 'e'), (7, 1, 'a'), (1, 7, 'e'), (7, 7, 'a')):
    g[1][z][x] = key
for x, z in ((4, 1), (4, 7), (1, 4), (7, 4)):
    g[1][z][x] = 'l'
fountain = pack(g, "fountain", "Fountain", {
    'S': "minecraft:stone_bricks", 'p': "minecraft:polished_andesite", 'C': "minecraft:chiseled_stone_bricks", 'w': "minecraft:water",
    'e': "minecraft:spruce_stairs[facing=west,half=bottom]", 'a': "minecraft:spruce_stairs[facing=east,half=bottom]",
    'l': "minecraft:lantern[hanging=false]",
}, {"wood": 20, "stone": 160}, "l")

def public_hall(shell, name, fittings, palette, cost):
    """One of the fine houses with its beds out and the fittings of a public house in."""
    d = dict(shell)
    layers = [list(layer) for layer in d["layers"]]
    floor = [list(row) for row in layers[1]]
    above = [list(row) for row in layers[2]]
    for z, row in enumerate(floor):
        for x, cell in enumerate(row):
            if cell in "bBhmatzkfH" and 1 < x < len(row) - 2 and 1 < z < len(floor) - 3:
                floor[z][x] = '.'
                if above[z][x] in "gu":
                    above[z][x] = '.'
    for (x, z), key in fittings.items():
        assert floor[z][x] in '.r', (d["id"], x, z, floor[z][x])
        floor[z][x] = key
    layers[1] = ["".join(r) for r in floor]
    layers[2] = ["".join(r) for r in above]
    used = set("".join("".join(l) for l in layers))
    d.update(name=name, category="public", beds=0, cost=cost, layers=layers)
    d.pop("min_stage", None)
    d["palette"] = {k: v for k, v in {**d["palette"], **palette}.items() if k in used}
    d["late"] = "".join(k for k in d["late"] if k in used)
    return d

# --- The tavern: two storeys, a bar of barrels along the back, tables with benches on the rug.
tavern = public_hall(
    fine_house("tavern", "Tavern", 11, 9, 4, True, "dark_oak", "spruce", "stone_bricks", "white_terracotta", "dark_oak_log",
               "spruce_planks", "red", "red", pots=("potted_red_tulip", "potted_dandelion")),
    "Tavern",
    {(3, 2): '1', (4, 2): '1', (5, 2): '1', (6, 2): '1', (7, 2): '1', (8, 2): '1', (9, 2): '1',
     (4, 3): '2', (6, 3): '2', (8, 3): '2',
     (9, 5): '3', (9, 6): '4', (9, 7): '3', (4, 5): '3'},
    {'1': "minecraft:barrel[facing=south]", '2': "minecraft:spruce_slab[type=top]", '3': "minecraft:spruce_fence",
     '4': "minecraft:spruce_stairs[facing=east,half=bottom]"},
    {"wood": 520, "stone": 140})

# --- The chapel: one tall room, benches facing a lectern, a bell under the ridge.
chapel = public_hall(
    fine_house("chapel", "Chapel", 7, 10, 2, False, "dark_oak", "dark_oak", "stone_bricks", "stone_bricks", "stripped_dark_oak_log",
               "polished_andesite", "white", "red", glass="yellow_stained_glass_pane", chimney=False,
               pots=("potted_white_tulip", "potted_lily_of_the_valley")),
    "Chapel",
    {(4, 2): '1', (3, 2): '2', (5, 2): '2',
     (2, 5): '3', (3, 5): '3', (5, 5): '3', (6, 5): '3', (2, 7): '3', (3, 7): '3', (5, 7): '3', (6, 7): '3'},
    {'1': "minecraft:lectern[facing=south]", '2': "minecraft:lantern[hanging=false]",
     '3': "minecraft:dark_oak_stairs[facing=south,half=bottom]"},
    {"wood": 300, "stone": 220})

if __name__ == "__main__":
    for d in (stone_gate, stone_tower, well, fountain, tavern, chapel):
        open(os.path.join(OUT, d["id"] + ".json"), "w").write(dump(d))
        print(d["id"], f'{len(d["layers"][0][0])}x{len(d["layers"][0])}x{len(d["layers"])}', d["category"], d["cost"])
    for d in (tavern, chapel):
        if len(sys.argv) > 2 and sys.argv[2] == d["id"]:
            for i, layer in enumerate(d["layers"][:3]):
                print("layer", i); print("\n".join("|" + r + "|" for r in layer))
