"""Draws the pens a settlement keeps its animals in: one for cattle, one for sheep, a pig sty and
a chicken coop. Each is a fenced yard with a gate in front, a roofed corner, and a trough.

Run: python3 tools/gen_pens.py <blueprints dir> [id to print]

The middle of every yard is left bare: that is where the animals are set down. And nothing an
animal could climb on (a bale, a trough) stands next to the fence: from the top of a block beside
it, a fence is a step, and the herd walks out over it.
"""
import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from gen_houses import dump
from gen_unique_houses import G, m, stairs, slab, log, lantern, AIR

def yard(g, W, D, fence, post, ground, gate_x):
    """A fenced rectangle over the whole footprint, lantern posts at the corners, a gate in front."""
    for x in range(W):
        for z in range(D):
            g.put(x, 0, z, m(ground))
            edge = x in (0, W - 1) or z in (0, D - 1)
            corner = x in (0, W - 1) and z in (0, D - 1)
            if corner:
                g.put(x, 1, z, post); g.put(x, 2, z, post); g.put(x, 3, z, lantern(False))
            elif edge:
                g.put(x, 1, z, m(fence)); g.put(x, 2, z, AIR)
            else:
                g.clear(x, x, 1, 3, z, z)
    g.put(gate_x, 1, D - 1, m(fence + "_gate[facing=south]"))

def shelter(g, x0, x1, z0, z1, post, roof, y=3):
    """An open-sided roof on four posts."""
    for x in (x0, x1):
        for z in (z0, z1):
            g.box(x, x, 1, y - 1, z, z, post)
    g.box(x0, x1, y, y, z0, z1, slab(roof))

def pen(g, id, name, wood, stone):
    d = g.result(id, name, 0, 0, 2)
    d.update({"category": "workshop", "cost": {"wood": wood, "stone": stone}})
    d.pop("_grid")
    return d

def cattle_pen():
    g = G(11, 9, 5)
    yard(g, 11, 9, "oak_fence", log("oak_log"), "grass_block", 5)
    shelter(g, 1, 4, 1, 3, log("stripped_oak_log"), "spruce")
    g.put(2, 1, 2, m("hay_block")); g.put(3, 1, 2, m("hay_block")); g.put(2, 2, 2, m("hay_block")); g.put(3, 2, 2, m("hay_block"))
    g.put(8, 1, 2, m("water_cauldron[level=3]")); g.put(7, 1, 2, m("water_cauldron[level=3]"))
    for x in (2, 3): g.put(x, 0, 2, m("coarse_dirt")); g.put(x, 0, 3, m("coarse_dirt"))
    return pen(g, "cattle_pen", "Cattle Pen", 90, 10)

def sheep_pen():
    g = G(9, 9, 5)
    yard(g, 9, 9, "birch_fence", log("birch_log"), "grass_block", 4)
    shelter(g, 5, 7, 1, 3, log("stripped_birch_log"), "birch")
    g.put(6, 1, 2, m("white_wool")); g.put(6, 2, 2, m("light_gray_wool"))
    g.put(2, 1, 2, m("water_cauldron[level=3]"))
    g.put(2, 1, 6, m("hay_block")); g.put(2, 2, 6, m("hay_block"))
    for x, z, f in ((2, 6, "dandelion"), (6, 6, "poppy")): g.put(x, 1, z, m(f))
    return pen(g, "sheep_pen", "Sheep Pen", 80, 10)

def pig_sty():
    g = G(9, 7, 5)
    yard(g, 9, 7, "mud_brick_wall", m("mud_bricks"), "coarse_dirt", 4)
    g.put(4, 1, 6, m("spruce_fence_gate[facing=south]"))
    # the sty itself: a low roofed stall against the back wall
    for x in (1, 4):
        g.box(x, x, 1, 2, 1, 1, m("mud_bricks")); g.box(x, x, 1, 2, 2, 2, log("stripped_spruce_log"))
    g.box(1, 4, 3, 3, 1, 2, slab("spruce"))
    for x in (2, 3): g.put(x, 0, 1, m("podzol")); g.put(x, 0, 2, m("podzol"))
    g.put(6, 1, 2, m("water_cauldron[level=3]")); g.put(6, 1, 4, m("hay_block")); g.put(6, 2, 4, m("hay_block"))
    for x, z in ((6, 3), (2, 4), (5, 5)): g.put(x, 0, z, m("rooted_dirt"))
    return pen(g, "pig_sty", "Pig Sty", 50, 40)

def chicken_coop():
    g = G(7, 9, 6)
    yard(g, 7, 9, "oak_fence", log("spruce_log"), "grass_block", 3)
    # the coop: a little house on short legs at the back, with a ramp down into the yard
    for x in (1, 5):
        for z in (1, 3): g.put(x, 1, z, m("oak_fence"))
    g.box(1, 5, 2, 2, 1, 3, m("spruce_planks"))
    for x in range(1, 6):
        for z in range(1, 4):
            if x in (1, 5) or z in (1, 3): g.put(x, 3, z, m("birch_planks"))
            else: g.put(x, 3, z, m("hay_block"))
    g.put(3, 3, 3, m("spruce_trapdoor[facing=south,half=bottom,open=true]"))   # the pop-hole, shut: a hen on the roof is a hen over the fence
    for z in range(0, 5):
        k = z if z <= 2 else 4 - z
        for x in range(0, 7): g.put(x, 4, z, slab("dark_oak", "bottom" if k == 0 else "top")) if k < 2 else g.put(x, 5, z, slab("dark_oak"))
    g.put(2, 1, 6, m("water_cauldron[level=3]")); g.put(4, 1, 6, m("hay_block")); g.put(4, 2, 6, m("hay_block"))
    return pen(g, "chicken_coop", "Chicken Coop", 60, 10)

designs = [cattle_pen(), sheep_pen(), pig_sty(), chicken_coop()]

if __name__ == "__main__":
    for d in designs:
        open(os.path.join(sys.argv[1], d["id"] + ".json"), "w").write(dump(d))
        print(d["id"], f'{len(d["layers"][0][0])}x{len(d["layers"][0])}x{len(d["layers"])}', d["cost"])
        if len(sys.argv) > 2 and sys.argv[2] in (d["id"], "all"):
            L = d["layers"]
            for z in range(len(L[0])):
                print("  ".join("|" + c[z] + "|" for c in L))
            print(" ".join(f"{k}={v.replace('minecraft:','')}" for k, v in d["palette"].items()))
