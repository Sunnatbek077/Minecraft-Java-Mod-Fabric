"""Draws the strong points of a city's great wall: its tower and its gatehouse. The wall itself is
worked out in the game, cell by cell (ShapedWall); these two stand in it.

Run: python3 tools/gen_stage3.py <blueprints dir> [print]

The wall they stand in is four blocks thick, six high, with a walk on top (the block at layer 6,
so one stands at layer 7). Both designs open on to that walk.
"""
import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from gen_houses import dump
from gen_unique_houses import G, m, stairs, slab, log, lantern, AIR

SB, DS, DB, PA, CH = m("stone_bricks"), m("polished_deepslate"), m("deepslate_bricks"), m("polished_andesite"), m("chiseled_stone_bricks")

def finish(g, id, name, wood, stone):
    d = g.result(id, name, 0, 0, 2)
    d.update({"category": "defense", "cost": {"wood": wood, "stone": stone}})
    d.pop("_grid")
    return d

def great_tower():
    """Seven square and eleven high to its fighting platform, with a way through at the level of the wall walk."""
    g = G(7, 7, 14)
    g.box(0, 6, 0, 0, 0, 6, DS)
    for y in range(1, 11):
        for x in range(7):
            for z in range(7):
                edge = x in (0, 6) or z in (0, 6)
                corner = x in (0, 6) and z in (0, 6)
                if not edge:
                    g.put(x, y, z, AIR)
                elif corner:
                    g.put(x, y, z, DB)
                else:
                    g.put(x, y, z, DS if y == 1 else CH if y in (6, 10) else m("mossy_stone_bricks") if (x * 3 + z * 5 + y) % 9 == 0 else SB)
    # floors at the level of the walk and under the platform, and a ladder up one corner
    for y in (6, 10):
        g.box(1, 5, y, y, 1, 5, m("smooth_stone") if y == 6 else PA)
    for y in range(1, 11):
        g.put(1, y, 5, m("ladder[facing=north]"))
    # the way through: an opening in the middle of every face, where the walk meets the tower
    for y in (7, 8):
        for i in (2, 3, 4):
            g.put(i, y, 0, AIR); g.put(i, y, 6, AIR); g.put(0, y, i, AIR); g.put(6, y, i, AIR)
    for i in (2, 4):
        for y in (9,):
            pass
    # arrow slits low down, and lanterns under both floors
    for y in (3, 4):
        g.put(3, y, 0, m("iron_bars")); g.put(3, y, 6, m("iron_bars")); g.put(0, y, 3, m("iron_bars")); g.put(6, y, 3, m("iron_bars"))
    g.put(3, 5, 3, lantern()); g.put(3, 9, 3, lantern())
    # the fighting platform: a parapet, merlons, and a light on every corner
    for x in range(7):
        for z in range(7):
            if x in (0, 6) or z in (0, 6):
                corner = x in (0, 6) and z in (0, 6)
                g.put(x, 11, z, DB if corner else SB)
                if corner:
                    g.put(x, 12, z, DB); g.put(x, 13, z, lantern(False))
                elif (x + z) % 2 == 0:
                    g.put(x, 12, z, SB)
            else:
                g.put(x, 11, z, AIR); g.put(x, 12, z, AIR)
    return finish(g, "great_tower", "Great Tower", 30, 520)

def great_gate():
    """A gatehouse across the great wall: double doors between two turrets, the walk carried over the passage, and stairs up to it inside."""
    g = G(14, 7, 13)
    g.box(0, 13, 0, 0, 0, 6, DS)
    # the wall itself, rows 1..4: fill, walk, outer parapet with merlons, inner low parapet
    for x in range(14):
        for z in range(1, 5):
            passage = x in (6, 7)
            for y in range(1, 6):
                if passage and y <= 4:
                    g.put(x, y, z, AIR)
                else:
                    g.put(x, y, z, SB if z in (1, 4) or (passage and y == 5) else m("cobbled_deepslate"))
            g.put(x, 6, z, CH if z == 4 else m("deepslate_tiles") if z == 1 else m("smooth_stone") if z == 3 else PA)
            for y in (7, 8, 9):
                g.put(x, y, z, AIR)
        g.put(x, 7, 4, SB)
        if x % 2 == 0: g.put(x, 8, 4, SB)
        if x not in (5, 6, 7, 8): g.put(x, 7, 1, m("stone_brick_wall"))
    g.put(6, 1, 4, DS) if False else None
    # the arch and the doors, in the outer face
    g.put(5, 4, 4, DB); g.put(8, 4, 4, DB)
    for y in (1, 2, 3):
        g.put(5, y, 4, DB); g.put(8, y, 4, DB)
    g.put(6, 1, 4, m("dark_oak_door[half=lower,facing=north,hinge=left]")); g.put(6, 2, 4, m("dark_oak_door[half=upper,facing=north,hinge=left]"))
    g.put(7, 1, 4, m("dark_oak_door[half=lower,facing=north,hinge=right]")); g.put(7, 2, 4, m("dark_oak_door[half=upper,facing=north,hinge=right]"))
    g.put(6, 3, 4, m("iron_bars")); g.put(7, 3, 4, m("iron_bars")); g.put(6, 4, 4, DB); g.put(7, 4, 4, DB)
    g.put(6, 4, 2, lantern()); g.put(7, 4, 3, lantern())
    # two turrets, standing out from the wall on either side of the way in
    for x0 in (1, 9):
        for x in range(x0, x0 + 4):
            for z in (4, 5, 6):
                edge = x in (x0, x0 + 3) or z == 6
                for y in range(1, 10):
                    g.put(x, y, z, DB if x in (x0, x0 + 3) and z == 6 else DS if y == 1 else CH if y == 6 else SB if edge or z == 4 else m("cobbled_deepslate"))
                g.put(x, 10, z, SB if edge or z == 4 else AIR)
                if edge or z == 4:
                    if (x + z) % 2 == 0: g.put(x, 11, z, SB)
                else:
                    g.put(x, 9, z, PA)
        for x in (x0, x0 + 3):
            g.put(x, 11, 6, DB); g.put(x, 12, 6, lantern(False))
        g.put(x0 + 1, 4, 6, m("iron_bars")); g.put(x0 + 2, 4, 6, m("iron_bars"))
    for z in (5, 6):
        for x in (5, 6, 7, 8):
            g.clear(x, x, 1, 9, z, z)
    # stairs up to the walk on the town side, a flight from either end
    for x in range(0, 6):
        g.put(x, x + 1, 0, stairs("stone_brick", "east"))
        for y in range(1, x + 1): g.put(x, y, 0, SB)
        for y in range(x + 2, 10): g.put(x, y, 0, AIR)
        g.put(13 - x, x + 1, 0, stairs("stone_brick", "west"))
        for y in range(1, x + 1): g.put(13 - x, y, 0, SB)
        for y in range(x + 2, 10): g.put(13 - x, y, 0, AIR)
    for x in (6, 7):
        g.clear(x, x, 1, 5, 0, 0)
        g.put(x, 6, 0, slab("stone_brick", "top"))
        g.clear(x, x, 7, 9, 0, 0)
    return finish(g, "great_gate", "Great Gate", 60, 900)

designs = [great_tower(), great_gate()]

if __name__ == "__main__":
    for d in designs:
        open(os.path.join(sys.argv[1], d["id"] + ".json"), "w").write(dump(d))
        print(d["id"], f'{len(d["layers"][0][0])}x{len(d["layers"][0])}x{len(d["layers"])}', d["cost"], "late:", d["late"])
        if len(sys.argv) > 2:
            L = d["layers"]
            for a in range(0, len(L), 7):
                for z in range(len(L[0])):
                    print("  ".join("|" + c[z] + "|" for c in L[a:a + 7]))
                print()
            print(" ".join(f"{k}={v.replace('minecraft:','')}" for k, v in d["palette"].items()))
