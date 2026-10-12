"""Draws the library: two floors of twenty-four lecterns each, in three rows, with a librarian's
place before every one. One such hall holds a lectern for every enchantment the game has.

Run: python3 tools/gen_library.py <blueprints dir> [print]

Every lectern faces the door (south), and the cell in front of it is bare floor: that is where
its librarian stands.
"""
import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from gen_houses import dump
from gen_unique_houses import G, m, stairs, slab, log, lantern, AIR, rug

def library():
    g = G(13, 12, 16)
    g.box(1, 11, 0, 0, 1, 9, m("dark_oak_planks"))
    g.body(1, 11, 1, 9, 1, 8, m("stone_bricks"), post=log("dark_oak_log"), sill=m("polished_andesite"), beam="dark_oak_log", win=2)
    # the upper floor, its own row of windows, and the ladder up the back wall
    g.box(2, 10, 5, 5, 2, 8, m("spruce_planks"))
    for x in range(2, 11):
        for z in (1, 9):
            g.put(x, 4, z, log("dark_oak_log", "x"))
            if x % 2 == 0 and x != 6: g.put(x, 7, z, m("glass_pane"))
    for z in (3, 5, 7):
        g.put(1, 4, z, log("dark_oak_log", "z")); g.put(11, 4, z, log("dark_oak_log", "z"))
        g.put(1, 7, z, m("glass_pane")); g.put(11, 7, z, m("glass_pane"))
    for y in range(1, 6): g.put(6, y, 2, m("ladder[facing=south]"))
    g.put(6, 6, 2, AIR); g.put(6, 7, 2, AIR)
    g.roof_z(0, 12, 0, 10, 9, "dark_oak", gable=m("stone_bricks"), gz=(1, 9), cap="spruce")
    g.put(6, 11, 9, m("glass_pane")); g.put(6, 12, 9, m("glass_pane")); g.put(6, 11, 1, m("glass_pane"))
    g.door(6, 1, 9, "dark_oak")
    for floor in (1, 6):
        # books along the back wall, and three rows of eight lecterns with the aisle down the middle
        for x in range(2, 11):
            if x != 6:
                for y in (floor, floor + 1): g.put(x, y, 2, m("bookshelf"))
        for z in (3, 5, 7):
            for x in (2, 3, 4, 5, 7, 8, 9, 10):
                g.put(x, floor, z, m("lectern[facing=south]"))
        rug(g, 6, 6, 3, 8, "red" if floor == 1 else "purple", y=floor)
    g.put(6, 6, 8, m("enchanting_table"))
    for z in (4, 6):
        g.put(4, 4, z, lantern()); g.put(8, 4, z, lantern())     # under the upper floor
    for z in (3, 6):
        for x in range(2, 11): g.put(x, 10, z, log("dark_oak_log", "x"))
        g.put(4, 9, z, lantern()); g.put(8, 9, z, lantern())
    # a porch: two posts, a lantern each, and a step of path
    for x in (4, 8): g.put(x, 1, 10, m("dark_oak_fence")); g.put(x, 2, 10, lantern(False))
    g.put(6, 0, 10, m("dirt_path")); g.put(6, 0, 11, m("dirt_path"))
    d = g.result("library", "Library", 0, 0, 2)
    d.update({"category": "workshop", "cost": {"wood": 420, "stone": 300}})
    grid = d.pop("_grid")
    # every lectern has bare floor in front of it, and room to stand
    for (x, y, z), b in grid.c.items():
        if "lectern" in b:
            assert grid.get(x, y, z + 1) == AIR and grid.get(x, y + 1, z + 1) == AIR and grid.get(x, y - 1, z + 1) not in (None, AIR), (x, y, z)
    assert sum("lectern" in b for b in grid.c.values()) == 48
    return d

if __name__ == "__main__":
    d = library()
    open(os.path.join(sys.argv[1], d["id"] + ".json"), "w").write(dump(d))
    print(d["id"], f'{len(d["layers"][0][0])}x{len(d["layers"][0])}x{len(d["layers"])}', d["cost"])
    if len(sys.argv) > 2:
        L = d["layers"]
        for a in range(0, len(L), 6):
            for z in range(len(L[0])):
                print("  ".join("|" + c[z] + "|" for c in L[a:a + 6]))
            print()
        print(" ".join(f"{k}={v.replace('minecraft:','')}" for k, v in d["palette"].items()))
