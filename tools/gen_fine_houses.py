"""Draws the finer houses: timber-framed, with a porch, window boxes, a hedge, rugs and furniture,
and for the larger ones an upper storey that juts out over the door.

Run: python3 tools/gen_fine_houses.py <blueprints dir> [id to print]

Layout of the footprint: one spare column on either side and one row behind (for the eaves and
the window boxes), and two rows in front: the porch, under the upper storey where there is one,
and a strip of garden. The house walls are columns 1..W and rows 1..D; the door is in row D.
"""
import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from gen_houses import dump, bed_cols

def stairs(wood, facing, half="bottom"):
    return f"minecraft:{wood}_stairs[facing={facing},half={half}]"

def fine_house(id, name, W, D, beds, upper, roof, trim, plinth, infill, log, floor, bed, rug, upper_infill=None,
               glass="glass_pane", chimney=True, hedge="oak_leaves", pots=("potted_red_tulip", "potted_cornflower")):
    assert not upper or D >= 9, "an upper storey needs room for its stair"
    FW, FD = W + 2, D + 3
    c = (W + 1) // 2
    h = 3 if upper else 4                       # height of the ground storey walls
    top = h + 4 if upper else h                 # last layer of wall
    front = D + 1 if upper else D               # row of the front wall under the roof
    RB = top + 1                                # first layer of roof
    ridge = RB + (W + 1) // 2
    H = ridge + 3
    g = [[[' '] * FW for _ in range(FD)] for _ in range(H)]
    def put(y, z, x, ch):
        g[y][z][x] = ch
    def post(i, n):
        return i == 1 or i == n or ((i - 1) % 3 == 0 and i < n - 1)
    def window(i, n):
        return not post(i, n) and (i - 1) % 3 == 1

    # --- ground: plinth, floor, porch floor, garden path
    for z in range(1, D + 1):
        for x in range(1, W + 1):
            put(0, z, x, 'F' if 1 < x < W and 1 < z < D else 'C')
    for x in range(1, W + 1):
        put(0, D + 1, x, 'C')
    put(0, D + 2, c, 'p')

    # --- a storey of walls: stone course (ground only), framed infill with windows, a beam on top
    def storey(y0, y1, z0, z1, fill, ground):
        for y in range(y0, y1 + 1):
            for z in range(z0, z1 + 1):
                for x in range(1, W + 1):
                    edge = x in (1, W) or z in (z0, z1)
                    if not edge:
                        put(y, z, x, '.')
                        continue
                    along_x = z in (z0, z1)
                    i, n = (x, W) if along_x else (z - z0 + 1, z1 - z0 + 1)
                    corner = x in (1, W) and z in (z0, z1)
                    if corner or (post(i, n) and not (ground and y == y0)):
                        ch = 'L'
                    elif ground and y == y0:
                        ch = 'S'
                    elif y == y1:
                        ch = 'X' if along_x else 'Y'
                    elif y == y0 + 1 and window(i, n):
                        ch = 'G'
                    else:
                        ch = fill
                    put(y, z, x, ch)
    storey(1, h, 1, D, 'P', True)
    put(1, D, c, 'D'); put(2, D, c, 'd')
    for y in range(3, h):
        put(y, D, c, 'P')

    # window boxes under the side windows of the ground storey
    for z in range(2, D):
        if window(z, D):
            put(1, z, 0, 'T'); put(2, z, 0, 'u')
            put(1, z, W + 1, 'V'); put(2, z, W + 1, 'v')

    # --- beds, their chests, a rug, and furniture along the walls
    for x in bed_cols(W, beds):
        put(1, 2, x, 'b'); put(1, 3, x, 'B'); put(1, 5, x, 'h')
    for z in range(6, D - 1):
        for x in range(c - 1, c + 2):
            put(1, z, x, 'r')
    east = [('m', None), ('a', 'u'), ('f', 'g')]            # smoker; barrel with a plant; table
    for k, (key, above) in enumerate(east):
        z = D - 1 - k
        if z > 5:
            put(1, z, W - 1, key)
            if above:
                put(2, z, W - 1, above)
            if key == 'f' and W >= 9:
                put(1, z, W - 2, 'H')                      # a chair at the table
    if not upper:
        west = ['k', 't', 'z']
        for k, key in enumerate(west):
            z = D - 1 - k
            if z > 5:
                put(1, z, 2, key)
                if key == 'k':
                    put(2, z, 2, 'k')

    # --- the porch: posts, a rail, and a hedge with flowers in front
    for x in (1, W):
        for y in range(1, h):
            put(y, D + 1, x, 'L')
    for x in range(2, W):
        if abs(x - c) >= 2:
            put(1, D + 1, x, 'f')
    for x in range(1, W + 1):
        if abs(x - c) >= 2:
            put(1, D + 2, x, 'o' if x not in (1, W) else 'q')
    for x in (1, W):
        put(2, D + 2, x, 'u' if x == 1 else 'v')

    if upper:
        # brackets and lanterns under the jetty
        for x in (1, W):
            put(h, D + 1, x, 'L')
        for x in range(2, W):
            put(h, D + 1, x, 'J' if x in (2, W - 1) else ' ')
        put(h, D + 1, c - 1, 'n'); put(h, D + 1, c + 1, 'n')
        # the upper floor, out over the porch, with the stair coming up by the west wall
        F1 = h + 1
        for z in range(1, D + 2):
            for x in range(1, W + 1):
                put(F1, z, x, 'U')
        for k in range(3):
            put(1 + k, D - 1 - k, 2, 'i')
            for y in range(2 + k, F1 + 1):
                put(y, D - 1 - k, 2, '.')
        storey(F1 + 1, top, 1, D + 1, 'Q' if upper_infill else 'P', False)
        # a banister, books along the back wall, a reading desk, a rug, pots in the corners
        for x in range(3, W - 1):
            put(F1 + 1, 2, x, 'k'); put(F1 + 2, 2, x, 'k')
        put(F1 + 1, 4, W - 1, 'j')
        for z in range(4, D - 1):
            for x in range(c, c + 2):
                put(F1 + 1, z, x, 'r')
        for z in range(D - 3, D):
            put(F1 + 1, z, 3, 'f')
        put(F1 + 1, D, W - 1, 'z'); put(F1 + 1, 2, 2, 'z')
        # window boxes under the upper front windows
        for x in range(2, W):
            if window(x, W):
                put(F1 + 1, D + 2, x, 'N'); put(F1 + 2, D + 2, x, 'u' if x < c else 'v')
        put(h, 5, c, 'n')                                   # a lamp over the ground floor
    else:
        # a pent roof over the porch, and lanterns on its posts
        for x in range(1, W + 1):
            put(h, D + 1, x, 's')
        put(h - 1, D + 1, c - 1, 'n'); put(h - 1, D + 1, c + 1, 'n')

    # --- the roof: ridge front to back, a darker board at each gable end
    z0, z1 = 0, front + 1
    k = 0
    while True:
        lo, hi = k, W + 1 - k
        y = RB + k
        for z in range(z0, z1 + 1):
            edge = z in (z0, z1)
            if lo == hi:
                put(y, z, lo, 'R')
                continue
            put(y, z, lo, 'w' if edge else 'W'); put(y, z, hi, 'e' if edge else 'E')
            for x in range(lo + 1, hi):
                if z in (1, front):
                    put(y, z, x, 'A' if k >= 1 else 'X')
                elif 1 < z < front:
                    put(y, z, x, '.')
        if lo == hi:
            break
        k += 1
    for x in (1, W):
        for z in (1, front):
            put(RB, z, x, 'L')
    for z in range(2, front):
        put(RB, z, 1, 'Y'); put(RB, z, W, 'Y')
    if W >= 7:
        put(RB + 1, front, c, 'G')                          # a window in the gable
    put(ridge - 1, (1 + front) // 2, c, 'n')                # a lamp under the ridge

    # --- a chimney up the back wall
    if chimney:
        for y in range(1, ridge + 1):
            put(y, 1, W - 1, 'K')
        put(ridge + 1, 1, W - 1, 'c')

    roof_wood, trim_wood = roof, trim
    palette = {
        'C': f"minecraft:{plinth}", 'S': f"minecraft:{plinth}", 'F': f"minecraft:{floor}", 'U': f"minecraft:{floor}",
        'L': f"minecraft:{log}[axis=y]", 'X': f"minecraft:{log}[axis=x]", 'Y': f"minecraft:{log}[axis=z]",
        'P': f"minecraft:{infill}", 'Q': f"minecraft:{upper_infill or infill}", 'A': f"minecraft:{upper_infill or infill}",
        'G': f"minecraft:{glass}",
        'W': stairs(roof_wood, "east"), 'E': stairs(roof_wood, "west"),
        'w': stairs(trim_wood, "east"), 'e': stairs(trim_wood, "west"),
        'R': f"minecraft:{trim_wood}_slab[type=bottom]", 's': f"minecraft:{roof_wood}_slab[type=bottom]",
        'D': "minecraft:spruce_door[half=lower,facing=north,hinge=left]", 'd': "minecraft:spruce_door[half=upper,facing=north,hinge=left]",
        'b': f"minecraft:{bed}_bed[facing=north,part=head]", 'B': f"minecraft:{bed}_bed[facing=north,part=foot]",
        'h': "minecraft:chest[facing=south]",
        'n': "minecraft:lantern[hanging=true]", 'f': "minecraft:spruce_fence",
        'o': f"minecraft:{hedge}[persistent=true]", 'q': "minecraft:mossy_cobblestone",
        'r': f"minecraft:{rug}_carpet", 'g': "minecraft:brown_carpet",
        'k': "minecraft:bookshelf", 't': "minecraft:crafting_table", 'm': "minecraft:smoker[facing=west]",
        'a': "minecraft:barrel[facing=up]", 'z': "minecraft:decorated_pot[facing=south]", 'j': "minecraft:lectern[facing=west]",
        'T': stairs(trim_wood, "east", "top"), 'V': stairs(trim_wood, "west", "top"),
        'J': stairs(trim_wood, "north", "top"), 'N': stairs(trim_wood, "north", "top"),
        'H': stairs(roof_wood, "east"), 'i': stairs(roof_wood, "north"),
        'u': f"minecraft:{pots[0]}", 'v': f"minecraft:{pots[1]}",
        'K': "minecraft:cobblestone", 'c': "minecraft:campfire[lit=true]", 'p': "minecraft:dirt_path",
    }
    layers = [["".join(row) for row in layer] for layer in g]
    while all(set(r) <= {' '} for r in layers[-1]):
        layers.pop()
    used = set("".join("".join(l) for l in layers))
    palette = {k: v for k, v in palette.items() if k in used}
    area = W * D
    # Fine houses are a village's: they take more land and material for a bed than a camp can spare.
    return {"id": id, "name": name, "category": "housing", "beds": beds, "min_stage": 2,
            "cost": {"wood": (30 * beds + 3 * area + (120 if upper else 0)) // 10 * 10, "stone": (area + (30 if chimney else 0)) // 10 * 10},
            "late": "".join(k for k in "DdBbnhrguvz" if k in used), "palette": palette, "layers": layers}

designs = [
    fine_house("gabled_cottage", "Gabled Cottage", 7, 8, 2, False, "spruce", "dark_oak", "stone_bricks", "white_terracotta", "dark_oak_log",
               "spruce_planks", "red", "red"),
    fine_house("thatchers_house", "Thatcher's House", 7, 9, 2, False, "oak", "spruce", "mossy_cobblestone", "oak_planks", "stripped_oak_log",
               "oak_planks", "yellow", "yellow", hedge="flowering_azalea_leaves", pots=("potted_dandelion", "potted_oxeye_daisy")),
    fine_house("bakers_house", "Baker's House", 9, 8, 3, False, "dark_oak", "spruce", "bricks", "white_terracotta", "spruce_log",
               "birch_planks", "orange", "orange", pots=("potted_orange_tulip", "potted_red_tulip")),
    fine_house("scholars_house", "Scholar's House", 7, 9, 2, True, "dark_oak", "spruce", "stone_bricks", "white_terracotta", "dark_oak_log",
               "spruce_planks", "blue", "blue", pots=("potted_cornflower", "potted_blue_orchid")),
    fine_house("merchants_house", "Merchant's House", 9, 9, 4, True, "spruce", "dark_oak", "polished_andesite", "white_terracotta", "stripped_spruce_log",
               "dark_oak_planks", "purple", "purple", glass="light_gray_stained_glass_pane", pots=("potted_allium", "potted_lily_of_the_valley")),
    fine_house("vintners_house", "Vintner's House", 9, 10, 4, True, "dark_oak", "dark_oak", "mossy_stone_bricks", "terracotta", "dark_oak_log",
               "spruce_planks", "green", "green", hedge="spruce_leaves", pots=("potted_fern", "potted_azalea_bush")),
    fine_house("cherry_house", "Cherry House", 7, 9, 2, True, "cherry", "dark_oak", "stone_bricks", "white_terracotta", "cherry_log",
               "cherry_planks", "pink", "pink", hedge="cherry_leaves", pots=("potted_pink_tulip", "potted_allium")),
    fine_house("stewards_house", "Steward's House", 11, 9, 4, True, "dark_oak", "spruce", "stone_bricks", "white_terracotta", "spruce_log",
               "oak_planks", "red", "red", upper_infill="spruce_planks", pots=("potted_red_tulip", "potted_white_tulip")),
    fine_house("mud_brick_house", "Mud-Brick House", 9, 8, 3, False, "spruce", "dark_oak", "packed_mud", "mud_bricks", "stripped_dark_oak_log",
               "spruce_planks", "brown", "light_gray", hedge="azalea_leaves", pots=("potted_dead_bush", "potted_fern")),
    fine_house("deepslate_lodge", "Deepslate Lodge", 11, 10, 4, True, "dark_oak", "dark_oak", "polished_deepslate", "deepslate_bricks", "dark_oak_log",
               "spruce_planks", "cyan", "cyan", upper_infill="spruce_planks", glass="yellow_stained_glass_pane",
               hedge="dark_oak_leaves", pots=("potted_cornflower", "potted_blue_orchid")),
]

if __name__ == "__main__":
    out = sys.argv[1]
    for d in designs:
        open(os.path.join(out, d["id"] + ".json"), "w").write(dump(d))
        print(d["id"], f'{len(d["layers"][0][0])}x{len(d["layers"][0])}x{len(d["layers"])}', d["cost"], d["beds"])
        if len(sys.argv) > 2 and sys.argv[2] == d["id"]:
            for i, layer in enumerate(d["layers"]):
                print("layer", i); print("\n".join("|" + r + "|" for r in layer))
