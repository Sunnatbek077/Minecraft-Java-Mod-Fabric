import json, sys, os

def stairs(wood, facing): return f"minecraft:{wood}_stairs[facing={facing},half=bottom]"

def house(id, name, W, D, h, beds, roof, wood_roof, base, upper, log, floor, gable, bed_color,
          frame=False, chimney=False, awning=False, furniture=("crafting_table", "barrel[facing=up]"),
          foundation="cobblestone", glass="glass_pane", stone_heavy=False, extra=None):
    FW, FD = W + 2, D + 2
    c = (W + 1) // 2
    if roof == "ns":
        top = h + 1 + (W + 1) // 2
    elif roof == "ew":
        top = h + 1 + (D + 1) // 2
    else:
        top = h + 2
    H = top + 1 + (3 if chimney else 0)
    g = [[[' '] * FW for _ in range(FD)] for _ in range(H)]
    def put(y, z, x, ch):
        g[y][z][x] = ch
    inside = lambda x, z: 1 < x < W and 1 < z < D
    wall = lambda x, z: 1 <= x <= W and 1 <= z <= D and not inside(x, z)
    corner = lambda x, z: x in (1, W) and z in (1, D)
    # foundation and floor
    for z in range(1, D + 1):
        for x in range(1, W + 1):
            put(0, z, x, 'F' if inside(x, z) else 'C')
    # walls
    for y in range(1, h + 1):
        for z in range(1, D + 1):
            for x in range(1, W + 1):
                if inside(x, z):
                    put(y, z, x, '.')
                    continue
                i = x if z in (1, D) else z          # position along its wall
                n = W if z in (1, D) else D
                s = next((k for k in (3, 4, 5) if (n - 1) % k == 0), 0) if frame else 0
                post = s and (i - 1) % s == 0
                off = (i - 1) % s if s else 0
                if corner(x, z):
                    ch = 'L'
                elif post and y >= 2:
                    ch = 'L'
                elif y == 1:
                    ch = 'S'
                elif frame and y == h and h >= 3:
                    ch = 'X' if z in (1, D) else 'Y'
                else:
                    ch = 'P'
                if y == 2 and ch == 'P':
                    if s:
                        if s == 3 or 1 < off < s - 1:
                            ch = 'G'
                    elif i % 2 == 0:
                        ch = 'G'
                put(y, z, x, ch)
    put(1, D, c, 'D'); put(2, D, c, 'd')
    if h >= 3 and g[3][D][c] == 'G':
        put(3, D, c, 'P')
    # roof
    if roof == "ns":
        y = h + 1
        for z in range(1, D + 1):
            for x in range(1, W + 1):
                put(y, z, x, '.' if inside(x, z) else ('X' if z in (1, D) else 'Y'))
        for x in (1, W):
            for z in (1, D):
                put(y, z, x, 'L')
        k = 0
        while True:
            lo, hi = k, W + 1 - k
            y = h + 1 + k
            if lo > hi: break
            for z in range(FD):
                if lo == hi:
                    put(y, z, lo, 'R')
                else:
                    put(y, z, lo, 'W'); put(y, z, hi, 'E')
                    if k >= 1:
                        for x in range(lo + 1, hi):
                            put(y, z, x, 'A' if z in (1, D) else ('.' if 1 < z < D else ' '))
            if lo == hi: break
            k += 1
        ridge_y = h + 1 + (W + 1) // 2
        put(ridge_y - 1, (D + 1) // 2, c, 'n')
        if W >= 7:
            put(h + 2, D, c, 'G')
    elif roof == "ew":
        y = h + 1
        for z in range(1, D + 1):
            for x in range(1, W + 1):
                put(y, z, x, '.' if inside(x, z) else ('X' if z in (1, D) else 'Y'))
        for x in (1, W):
            for z in (1, D):
                put(y, z, x, 'L')
        k = 0
        while True:
            lo, hi = k, D + 1 - k
            y = h + 1 + k
            if lo > hi: break
            for x in range(FW):
                if lo == hi:
                    put(y, lo, x, 'R')
                else:
                    put(y, lo, x, 'N'); put(y, hi, x, 'Z')
                    if k >= 1:
                        for z in range(lo + 1, hi):
                            put(y, z, x, 'A' if x in (1, W) else ('.' if 1 < x < W else ' '))
            if lo == hi: break
            k += 1
        ridge_y = h + 1 + (D + 1) // 2
        put(ridge_y - 1, (D + 1) // 2, c, 'n')
        if D >= 7:
            put(h + 2, (D + 1) // 2, 1, 'G'); put(h + 2, (D + 1) // 2, W, 'G')
    else:  # flat, with a parapet
        for z in range(1, D + 1):
            for x in range(1, W + 1):
                put(h + 1, z, x, 'A')
                if not inside(x, z):
                    i = x if z in (1, D) else z
                    put(h + 2, z, x, 'S' if (corner(x, z) or i % 2 == 1) else 'r')
        put(h, (D + 1) // 2, c, 'n')
    # chimney up the back wall
    if chimney:
        cx = W - 1
        if roof == "ns": ctop = h + 4
        elif roof == "ew": ctop = h + 3
        else: ctop = h + 3
        for y in range(1, ctop + 1):
            put(y, 1, cx, 'K')
        put(ctop + 1, 1, cx, 'c')
    # beds, chests, furniture
    for x in bed_cols(W, beds):
        put(1, 2, x, 'b'); put(1, 3, x, 'B'); put(1, 5, x, 'h')
    spots = [(D - 1, 2), (D - 1, W - 1), (D - 2, 2), (D - 2, W - 1)]
    keys = "TUVQ"
    for (z, x), key, _ in zip(spots, keys, furniture):
        if z > 5:
            put(1, z, x, key)
    # front garden
    put(1, D + 1, 1, 'f'); put(2, D + 1, 1, 'l')
    put(1, D + 1, W, 'f'); put(2, D + 1, W, 'l')
    for x in (2, W - 1):
        if abs(x - c) >= 1 and x not in (1, W):
            put(1, D + 1, x, 'o')
    if awning and h >= 3:
        for x in (c - 1, c, c + 1):
            put(3, D + 1, x, 'r')
        put(1, D + 1, c - 1, 'f'); put(2, D + 1, c - 1, 'f')
        put(1, D + 1, c + 1, 'f'); put(2, D + 1, c + 1, 'f')
    palette = {
        'C': f"minecraft:{foundation}",
        'F': f"minecraft:{floor}",
        'L': f"minecraft:{log}[axis=y]",
        'X': f"minecraft:{log}[axis=x]",
        'Y': f"minecraft:{log}[axis=z]",
        'S': f"minecraft:{base}",
        'P': f"minecraft:{upper}",
        'A': f"minecraft:{gable}",
        'G': f"minecraft:{glass}",
        'W': stairs(wood_roof, "east"), 'E': stairs(wood_roof, "west"),
        'N': stairs(wood_roof, "south"), 'Z': stairs(wood_roof, "north"),
        'R': f"minecraft:{wood_roof}_slab[type=bottom]",
        'r': f"minecraft:{wood_roof}_slab[type=bottom]",
        'K': "minecraft:cobblestone", 'c': "minecraft:campfire[lit=true]",
        'D': "minecraft:spruce_door[half=lower,facing=north,hinge=left]",
        'd': "minecraft:spruce_door[half=upper,facing=north,hinge=left]",
        'b': f"minecraft:{bed_color}_bed[facing=north,part=head]",
        'B': f"minecraft:{bed_color}_bed[facing=north,part=foot]",
        'h': "minecraft:chest[facing=south]",
        'n': "minecraft:lantern[hanging=true]", 'l': "minecraft:lantern[hanging=false]",
        'f': "minecraft:spruce_fence", 'o': "minecraft:oak_leaves[persistent=true]",
    }
    for key, item in zip(keys, furniture):
        palette[key] = f"minecraft:{item}"
    if extra:
        palette.update(extra)
    layers = [["".join(row) for row in layer] for layer in g]
    while all(set(r) <= {' '} for r in layers[-1]):
        layers.pop()
    used = set("".join("".join(l) for l in layers))
    palette = {k: v for k, v in palette.items() if k in used}
    area = W * D
    wood = (20 * beds + 2 * area + (h - 2) * 20) // 10 * 10
    stone = ((2 if stone_heavy else 1) * area) // 10 * 10
    late = "".join(k for k in "DdBbnlh" if k in used)
    return {"id": id, "name": name, "category": "housing", "beds": beds,
            "cost": {"wood": wood, "stone": stone}, "late": late, "palette": palette, "layers": layers}

def bed_cols(W, beds):
    return {2: [2, W - 1], 3: [2, 3, W - 1], 4: [2, 3, W - 2, W - 1]}[beds]

designs = [
    house("birch_cabin", "Birch Cabin", 5, 7, 2, 2, "ns", "oak", "cobblestone", "birch_planks", "oak_log", "birch_planks", "birch_planks", "yellow"),
    house("stone_croft", "Stone Croft", 7, 7, 2, 2, "ew", "dark_oak", "cobblestone", "stone_bricks", "dark_oak_log", "spruce_planks", "spruce_planks", "gray",
          chimney=True, stone_heavy=True, furniture=("smoker[facing=south]", "barrel[facing=up]")),
    house("longhouse", "Longhouse", 7, 11, 2, 4, "ns", "spruce", "mossy_cobblestone", "spruce_planks", "spruce_log", "oak_planks", "stripped_spruce_log[axis=y]", "brown",
          chimney=True, furniture=("crafting_table", "barrel[facing=up]", "loom[facing=south]", "barrel[facing=up]")),
    house("timber_house", "Timber House", 9, 9, 3, 4, "ns", "dark_oak", "stone_bricks", "white_terracotta", "dark_oak_log", "oak_planks", "white_terracotta", "red",
          frame=True, awning=True, furniture=("crafting_table", "bookshelf", "barrel[facing=up]", "bookshelf")),
    house("tower_house", "Tower House", 5, 7, 4, 2, "flat", "stone_brick", "stone_bricks", "stone_bricks", "stripped_dark_oak_log", "spruce_planks", "stone_bricks", "blue",
          stone_heavy=True, awning=True, furniture=("cartography_table", "barrel[facing=up]")),
    house("brick_house", "Brick House", 7, 9, 3, 3, "ew", "spruce", "stone_bricks", "bricks", "stripped_oak_log", "oak_planks", "bricks", "green",
          chimney=True, stone_heavy=True, furniture=("smoker[facing=south]", "crafting_table", "barrel[facing=up]", "bookshelf")),
    house("mossy_lodge", "Mossy Lodge", 9, 7, 2, 3, "ew", "spruce", "mossy_cobblestone", "spruce_planks", "stripped_dark_oak_log", "spruce_planks", "mossy_stone_bricks", "lime",
          furniture=("fletching_table", "barrel[facing=up]")),
    house("weavers_house", "Weaver's House", 7, 9, 3, 2, "ns", "birch", "cobblestone", "oak_planks", "stripped_oak_log", "birch_planks", "oak_planks", "pink",
          frame=True, furniture=("loom[facing=south]", "barrel[facing=up]", "crafting_table", "bookshelf")),
    house("manor", "Manor", 11, 9, 4, 4, "ew", "dark_oak", "stone_bricks", "white_terracotta", "spruce_log", "dark_oak_planks", "white_terracotta", "purple",
          frame=True, chimney=True, awning=True, furniture=("crafting_table", "bookshelf", "smoker[facing=south]", "bookshelf")),
    house("deepslate_hall", "Deepslate Hall", 9, 11, 3, 4, "ns", "dark_oak", "cobbled_deepslate", "deepslate_bricks", "dark_oak_log", "spruce_planks", "polished_deepslate", "cyan",
          chimney=True, stone_heavy=True, foundation="cobbled_deepslate",
          furniture=("smoker[facing=south]", "barrel[facing=up]", "crafting_table", "bookshelf")),
]

def dump(d):
    lines = ["{"]
    for k in ("id", "name", "category", "beds", "min_stage", "cost", "late"):
        if k not in d:
            continue
        lines.append(f"\t{json.dumps(k)}: {json.dumps(d[k])},")
    lines.append('\t"palette": {')
    items = list(d["palette"].items())
    for i, (k, v) in enumerate(items):
        lines.append(f"\t\t{json.dumps(k)}: {json.dumps(v)}" + ("," if i < len(items) - 1 else ""))
    lines.append("\t},")
    lines.append('\t"layers": [')
    for i, layer in enumerate(d["layers"]):
        lines.append("\t\t[")
        for j, row in enumerate(layer):
            lines.append(f"\t\t\t{json.dumps(row)}" + ("," if j < len(layer) - 1 else ""))
        lines.append("\t\t]" + ("," if i < len(d["layers"]) - 1 else ""))
    lines.append("\t]")
    lines.append("}")
    return "\n".join(lines) + "\n"

if __name__ == "__main__":
    OUT = sys.argv[1]
    for d in designs:
        open(os.path.join(OUT, d["id"] + ".json"), "w").write(dump(d))
        print(d["id"], f'{len(d["layers"][0][0])}x{len(d["layers"][0])}x{len(d["layers"])}', d["cost"], d["beds"])
    if len(sys.argv) > 2:
        d = [x for x in designs if x["id"] == sys.argv[2]][0]
        for i, layer in enumerate(d["layers"]):
            print("layer", i); print("\n".join("|" + r + "|" for r in layer))
