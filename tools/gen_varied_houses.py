"""Draws a third set of twenty houses, odd ones out each: a lighthouse, a windmill, a yurt, a house
dug into a hill, a cottage of pumpkins, an upturned boat, an obsidian tower, a house of ice, and
others in honeycomb, amethyst, dripstone, resin, copper, glazed tiles, concrete and lapis.

Run: python3 tools/gen_varied_houses.py <blueprints dir> [id to print | all]

Uses the grid toolkit of gen_unique_houses.py; see there for how a design is drawn.
"""
import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from gen_houses import dump
from gen_unique_houses import G, AIR, m, stairs, slab, log, lantern, furnish, rug, path, check
from gen_more_houses import light

def disc(c):
    """Round plans: inside(x, z, r) and rim(x, z, r) about the centre (c, c)."""
    def inside(x, z, r): return max(abs(x - c), abs(z - c)) <= r and abs(x - c) + abs(z - c) <= 2 * r - 1
    def rim(x, z, r): return inside(x, z, r) and not all(inside(x + a, z + b, r) for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1)))
    return inside, rim

def lighthouse():
    """A round tower striped red and white, with a lamp room of glass and a railed gallery round it."""
    g = G(9, 11, 18)
    inside, rim = disc(4)
    for x in range(9):
        for z in range(9):
            if not inside(x, z, 4): continue
            g.put(x, 0, z, m("smooth_stone"))
            for y in range(1, 13):
                if rim(x, z, 4): g.put(x, y, z, m("red_concrete") if (y - 1) // 3 % 2 else m("white_concrete"))
                else: g.air(x, y, z)
            if not rim(x, z, 4):
                g.put(x, 5, z, m("spruce_planks")); g.put(x, 9, z, m("spruce_planks"))
            g.put(x, 13, z, m("smooth_stone"))
            if rim(x, z, 4): g.put(x, 14, z, m("iron_bars"))
            if inside(x, z, 2):
                for y in (14, 15): g.put(x, y, z, m("glass") if rim(x, z, 2) else AIR)
                g.put(x, 16, z, m("red_concrete"))
    g.put(4, 14, 4, m("glowstone")); g.put(4, 17, 4, m("lightning_rod[facing=up]"))
    for y in (2, 6, 10):
        g.put(4, y, 0, m("glass_pane")); g.put(8, y, 4, m("glass_pane"))
    for y in range(1, 14): g.put(1, y, 3, m("ladder[facing=east]"))
    g.door(4, 1, 8, "spruce")
    g.beds([2, 6], 1, 1, "red")
    furnish(g, [(6, 3, "barrel[facing=up]"), (6, 5, "bookshelf"), (3, 6, "cartography_table")], y=6)
    furnish(g, [(6, 4, "barrel[facing=up]", "potted_cornflower"), (4, 6, "crafting_table")], y=10)
    light(g, 4, 4, 4); light(g, 4, 8, 4); light(g, 4, 12, 4)
    path(g, 4, 9); path(g, 4, 10)
    return g.result("lighthouse", "Lighthouse", 2, 49, 2, extra_stone=200)

def windmill():
    """A stone house with a mill tower on its back, and four sails of white cloth on the front of it."""
    g = G(11, 11, 16)
    g.box(1, 9, 0, 0, 1, 8, m("polished_andesite"))
    g.body(1, 9, 1, 8, 1, 4, m("stone"), post=m("cobblestone"), sill=m("cobblestone"), win=2)
    g.box(1, 9, 5, 5, 1, 8, slab("smooth_stone"))
    g.body(3, 7, 2, 6, 6, 11, m("stripped_birch_wood[axis=y]"), post=log("stripped_spruce_log"), win=8)
    g.roof_z(2, 8, 1, 7, 12, "spruce", gable=m("stripped_birch_wood[axis=y]"), gz=(2, 6))
    g.put(5, 9, 7, log("stripped_spruce_log", "z")); g.put(5, 9, 8, m("stripped_spruce_wood[axis=z]"))
    for y in range(10, 14): g.put(5, y, 8, m("spruce_fence")); g.put(6, y, 8, m("white_wool"))
    for y in range(6, 9): g.put(5, y, 8, m("spruce_fence")); g.put(4, y, 8, m("white_wool"))
    for x in range(1, 5): g.put(x, 9, 8, m("spruce_fence")); g.put(x, 10, 8, m("white_wool"))
    for x in range(6, 10): g.put(x, 9, 8, m("spruce_fence")); g.put(x, 8, 8, m("white_wool"))
    g.door(5, 1, 8, "spruce")
    g.beds([2, 3, 7, 8], 1, 2, "white")
    furnish(g, [(5, 2, "hay_block"), (2, 7, "barrel[facing=up]", "potted_pink_tulip"), (8, 7, "crafting_table")])
    light(g, 5, 4, 4)
    path(g, 5, 9); path(g, 5, 10)
    return g.result("windmill", "Windmill House", 4, 72, 2, extra_wood=60, extra_stone=80)

def yurt():
    """A round tent of felt on a lattice, with a stepped cone of a roof."""
    g = G(11, 11, 8)
    inside, rim = disc(5)
    for x in range(11):
        for z in range(11):
            if inside(x, z, 4):
                g.put(x, 0, z, m("coarse_dirt"))
                for y in (1, 2):
                    g.put(x, y, z, m("white_wool") if rim(x, z, 4) else AIR)
            for k in range(4):
                outer, inner = 5 - k, 3 - k
                if inside(x, z, outer) and not (inner > 0 and inside(x, z, inner)):
                    g.put(x, 3 + k, z, m("brown_wool") if k % 2 == 0 else m("white_wool"))
                elif inner > 0 and inside(x, z, inner):
                    g.put(x, 3 + k, z, AIR)
    g.door(5, 1, 9, "spruce")
    g.beds([3, 7], 1, 2, "brown")
    furnish(g, [(5, 2, "barrel[facing=up]", "potted_azure_bluet"), (3, 7, "decorated_pot[facing=south]"), (7, 7, "note_block")])
    rug(g, 4, 6, 6, 7, "brown")
    light(g, 5, 5, 5)
    path(g, 5, 10)
    return g.result("yurt", "Yurt", 2, 52, 1)

def hill_house():
    """Dug into a grassy mound: only its timber front shows, with a round door and two windows."""
    g = G(11, 10, 8)
    g.box(1, 9, 0, 0, 1, 7, m("oak_planks"))
    g.body(1, 9, 1, 7, 1, 3, m("dirt"), post=m("dirt"), win=-9)
    for x in range(1, 10):
        for y in (1, 2, 3): g.put(x, y, 7, m("stripped_oak_wood[axis=y]"))
    g.put(3, 2, 7, m("glass_pane")); g.put(7, 2, 7, m("glass_pane"))
    g.box(0, 0, 1, 3, 0, 8, m("dirt")); g.box(10, 10, 1, 3, 0, 8, m("dirt")); g.box(1, 9, 1, 3, 0, 0, m("dirt"))
    for x in list(range(0, 3)) + list(range(8, 11)):
        for y in (1, 2, 3): g.put(x, y, 8, m("dirt"))
    for x in range(11):
        for z in range(9):
            covered = 1 <= x <= 9 and 1 <= z <= 7
            g.put(x, 4, z, m("dirt") if covered else m("grass_block"))
            if covered: g.put(x, 5, z, m("dirt") if 3 <= x <= 7 and 2 <= z <= 6 else m("grass_block"))
            if 3 <= x <= 7 and 2 <= z <= 6: g.put(x, 6, z, m("grass_block"))
    for y in (5, 6, 7): g.put(8, y, 2, m("bricks"))
    g.put(4, 7, 4, m("oxeye_daisy")); g.put(6, 7, 3, m("blue_orchid")); g.put(5, 7, 5, m("short_grass"))
    g.door(5, 1, 7, "oak")
    g.beds([2, 8], 1, 2, "green")
    furnish(g, [(5, 2, "barrel[facing=up]", "potted_torchflower"), (2, 6, "crafting_table"), (8, 6, "furnace[facing=west]")])
    rug(g, 4, 6, 3, 5, "green")
    light(g, 5, 3, 4)
    path(g, 5, 8); path(g, 5, 9)
    return g.result("hill_house", "Hill House", 2, 63, 1)

def pumpkin_cottage():
    """Walls of pumpkins, a jack o'lantern at every corner and orange wool in the gables."""
    g = G(9, 10, 9)
    g.box(1, 7, 0, 0, 1, 7, m("oak_planks"))
    g.body(1, 7, 1, 7, 1, 3, m("pumpkin"), post=m("jack_o_lantern[facing=south]"), win=2)
    g.roof_x(0, 8, 0, 8, 4, "dark_oak", gable=m("orange_wool"), gx=(1, 7), cap="dark_oak")
    g.door(4, 1, 7, "spruce")
    g.beds([2, 6], 1, 2, "orange")
    furnish(g, [(4, 2, "hay_block", "carved_pumpkin[facing=south]"), (2, 6, "composter"), (6, 6, "smoker[facing=west]")])
    rug(g, 3, 5, 3, 3, "orange")
    light(g, 4, 7, 4)
    path(g, 4, 8); path(g, 4, 9)
    return g.result("pumpkin_cottage", "Pumpkin Cottage", 2, 49, 1)

def beekeepers_house():
    """Honeycomb walls in an oak frame, hives inside and a bee nest on a post either side of the door."""
    g = G(9, 11, 10)
    g.box(1, 7, 0, 0, 1, 8, m("oak_planks"))
    g.body(1, 7, 1, 8, 1, 3, m("honeycomb_block"), post=log("stripped_oak_log"), sill=m("oak_planks"), beam="oak_log",
           glass="yellow_stained_glass_pane", win=2)
    g.roof_z(0, 8, 0, 9, 4, "oak", gable=m("honeycomb_block"), gz=(1, 8), cap="birch")
    g.door(4, 1, 8, "oak")
    g.beds([2, 3, 6], 1, 2, "yellow")
    furnish(g, [(2, 7, "beehive[facing=east]"), (6, 7, "beehive[facing=west]"), (4, 2, "barrel[facing=up]", "candle[candles=4,lit=true]")])
    for x in (1, 7): g.put(x, 1, 9, m("oak_fence")); g.put(x, 2, 9, m("bee_nest[facing=south]"))
    rug(g, 3, 5, 6, 6, "yellow")
    light(g, 4, 7, 4)
    path(g, 4, 9); path(g, 4, 10)
    return g.result("beekeepers_house", "Beekeeper's House", 3, 56, 1)

def amethyst_house():
    """Purple amethyst walls with windows of tinted glass, and crystals on the shelves."""
    g = G(9, 10, 10)
    g.box(1, 7, 0, 0, 1, 7, m("deepslate_tiles"))
    g.body(1, 7, 1, 7, 1, 3, m("amethyst_block"), post=m("polished_deepslate"), sill=m("calcite"), glass="tinted_glass", win=2)
    top = g.hip(0, 8, 0, 8, 4, "deepslate_brick", cap="deepslate_brick")
    g.door(4, 1, 7, "spruce")
    g.beds([2, 6], 1, 2, "purple")
    furnish(g, [(4, 2, "bookshelf", "amethyst_cluster[facing=up]"), (2, 6, "barrel[facing=up]", "purple_candle[candles=3,lit=true]"),
                (6, 6, "enchanting_table")])
    rug(g, 3, 5, 3, 3, "purple")
    light(g, 4, top - 1, 4)
    path(g, 4, 8); path(g, 4, 9)
    return g.result("amethyst_house", "Amethyst House", 2, 49, 3, extra_stone=120)

def dripstone_hut():
    """Brown dripstone under a roof of mud brick."""
    g = G(9, 10, 9)
    g.box(1, 7, 0, 0, 1, 7, m("tuff"))
    g.body(1, 7, 1, 7, 1, 3, m("dripstone_block"), post=m("cobbled_deepslate"), win=2)
    g.roof_x(0, 8, 0, 8, 4, "mud_brick", gable=m("dripstone_block"), gx=(1, 7), cap="mud_brick")
    g.door(4, 1, 7, "acacia")
    g.beds([2, 6], 1, 2, "brown")
    furnish(g, [(4, 2, "cauldron"), (2, 6, "barrel[facing=up]", "potted_dead_bush"), (6, 6, "furnace[facing=west]")])
    light(g, 4, 7, 4)
    path(g, 4, 8); path(g, 4, 9)
    return g.result("dripstone_hut", "Dripstone Hut", 2, 49, 1, extra_stone=60)

def resin_cottage():
    """Orange resin brick on pale oak, with eyeblossoms in pots."""
    g = G(9, 10, 10)
    g.box(1, 7, 0, 0, 1, 7, m("pale_oak_planks"))
    g.body(1, 7, 1, 7, 1, 3, m("resin_bricks"), post=m("chiseled_resin_bricks"), sill=m("resin_block"),
           glass="orange_stained_glass_pane", win=2)
    g.roof_z(0, 8, 0, 8, 4, "resin_brick", gable=m("resin_bricks"), gz=(1, 7), cap="resin_brick")
    g.door(4, 1, 7, "pale_oak")
    g.beds([2, 6], 1, 2, "orange")
    furnish(g, [(4, 2, "barrel[facing=up]", "potted_open_eyeblossom"), (2, 6, "bookshelf", "potted_closed_eyeblossom"), (6, 6, "crafting_table")])
    light(g, 4, 7, 4)
    path(g, 4, 8); path(g, 4, 9)
    return g.result("resin_cottage", "Resin Cottage", 2, 49, 2, extra_stone=80)

def obsidian_tower():
    """A round black tower, weeping purple here and there, with an enchanter's room up the ladder."""
    g = G(9, 9, 17)
    inside, rim = disc(4)
    for x in range(9):
        for z in range(9):
            if inside(x, z, 3):
                g.put(x, 0, z, m("obsidian"))
                for y in range(1, 12):
                    if rim(x, z, 3): g.put(x, y, z, m("crying_obsidian") if (x + y + z) % 7 == 0 else m("obsidian"))
                    else: g.air(x, y, z)
                if not rim(x, z, 3):
                    g.put(x, 5, z, m("polished_deepslate")); g.put(x, 9, z, m("polished_deepslate"))
            for k, (outer, inner) in enumerate(((4, 2), (3, 1), (2, 0))):
                if inside(x, z, outer) and not (inner and inside(x, z, inner)): g.put(x, 12 + k, z, m("blackstone"))
                elif inner and inside(x, z, inner): g.put(x, 12 + k, z, AIR)
    g.put(4, 15, 4, m("crying_obsidian"))
    for y in (2, 6, 10):
        g.put(4, y, 1, m("purple_stained_glass_pane")); g.put(7, y, 4, m("purple_stained_glass_pane"))
    for y in range(1, 10): g.put(2, y, 4, m("ladder[facing=east]"))
    g.door(4, 1, 7, "dark_oak")
    g.beds([3, 5], 1, 2, "black")
    furnish(g, [(4, 2, "enchanting_table"), (3, 2, "bookshelf"), (5, 2, "bookshelf"), (6, 4, "brewing_stand")], y=6)
    furnish(g, [(3, 6, "barrel[facing=up]"), (6, 4, "cauldron")], y=10)
    light(g, 4, 4, 4); light(g, 4, 8, 4); light(g, 4, 13, 4)
    path(g, 4, 8)
    return g.result("obsidian_tower", "Obsidian Tower", 2, 49, 3, extra_stone=220)

def ice_house():
    """Packed ice between pillars of blue ice, under a white roof, lit by a froglight."""
    g = G(9, 10, 10)
    g.box(1, 7, 0, 0, 1, 7, m("polished_diorite"))
    g.body(1, 7, 1, 7, 1, 3, m("packed_ice"), post=m("blue_ice"), sill=m("snow_block"), glass="white_stained_glass_pane", win=2)
    top = g.hip(0, 8, 0, 8, 4, "smooth_quartz", cap="smooth_quartz")
    g.door(4, 1, 7, "spruce")
    g.beds([2, 6], 1, 2, "light_blue")
    furnish(g, [(4, 2, "pearlescent_froglight[axis=y]"), (2, 6, "barrel[facing=up]", "potted_white_tulip"), (6, 6, "smoker[facing=west]")])
    rug(g, 3, 5, 3, 3, "white")
    light(g, 4, top - 1, 4)
    path(g, 4, 8); path(g, 4, 9)
    return g.result("ice_house", "Ice House", 2, 49, 2, extra_stone=80)

def thatched_cottage():
    """Grey clay walls under a deep roof of thatch, laid in straw bales."""
    g = G(11, 10, 10)
    g.box(1, 9, 0, 0, 1, 7, m("oak_planks"))
    g.body(1, 9, 1, 7, 1, 3, m("light_gray_terracotta"), post=log("stripped_oak_log"), sill=m("cobblestone"), beam="oak_log", win=2)
    k = 0
    while k <= 8 - k:
        lo, hi = k, 8 - k
        for x in range(11):
            g.put(x, 4 + k, lo, m("hay_block[axis=x]")); g.put(x, 4 + k, hi, m("hay_block[axis=x]"))
            for z in range(lo + 1, hi):
                g.put(x, 4 + k, z, m("light_gray_terracotta") if x in (1, 9) else AIR)
        k += 1
    g.door(5, 1, 7, "oak")
    g.beds([2, 3, 7, 8], 1, 2, "lime")
    furnish(g, [(5, 2, "barrel[facing=up]", "potted_dandelion"), (2, 6, "crafting_table"), (8, 6, "furnace[facing=west]")])
    light(g, 5, 7, 4)
    path(g, 5, 8); path(g, 5, 9)
    return g.result("thatched_cottage", "Thatched Cottage", 4, 63, 1)

def machiya():
    """A town house in the eastern manner: white screens in a dark frame, under two tiers of grey roof."""
    g = G(11, 10, 10)
    g.box(1, 9, 0, 0, 1, 7, m("birch_planks"))
    g.body(1, 9, 1, 7, 1, 3, m("white_wool"), post=log("stripped_dark_oak_log"), sill=m("dark_oak_planks"), beam="dark_oak_log",
           glass="white_stained_glass_pane", win=2)
    g.hip(0, 10, 0, 8, 4, "polished_andesite", layers=1)
    top = g.hip(1, 9, 1, 7, 5, "polished_andesite", cap="polished_andesite")
    g.door(5, 1, 7, "spruce")
    g.beds([2, 3, 7, 8], 1, 2, "green")
    furnish(g, [(5, 2, "barrel[facing=up]", "potted_cherry_sapling"), (2, 6, "white_candle[candles=2,lit=true]"), (8, 6, "barrel[facing=up]", "potted_fern")])
    rug(g, 4, 6, 3, 5, "green")
    light(g, 5, top - 1, 4)
    path(g, 5, 8); path(g, 5, 9)
    return g.result("machiya", "Machiya", 4, 63, 2)

def pueblo():
    """Brown adobe in two blocks, the smaller standing on the roof of the larger, with beams poking out."""
    g = G(11, 11, 9)
    g.box(1, 9, 0, 0, 1, 8, m("smooth_sandstone"))
    g.body(1, 9, 1, 8, 1, 3, m("brown_terracotta"), post=m("terracotta"), sill=m("terracotta"), win=2)
    g.box(1, 9, 4, 4, 1, 8, m("brown_terracotta"))
    g.body(1, 5, 1, 4, 5, 7, m("brown_terracotta"), post=m("terracotta"), win=6)
    g.box(1, 5, 8, 8, 1, 4, m("brown_terracotta"))
    for z in (2, 4, 6):
        g.put(0, 3, z, log("stripped_spruce_log", "x")); g.put(10, 3, z, log("stripped_spruce_log", "x"))
    for z in (2, 3): g.put(0, 7, z, log("stripped_spruce_log", "x")); g.put(6, 7, z, log("stripped_spruce_log", "x"))
    g.door(5, 1, 8, "acacia")
    g.beds([2, 3, 7, 8], 1, 2, "red")
    furnish(g, [(5, 2, "furnace[facing=south]"), (2, 7, "barrel[facing=up]", "potted_cactus"), (8, 7, "decorated_pot[facing=south]")])
    light(g, 5, 3, 5)
    path(g, 5, 9); path(g, 5, 10)
    return g.result("pueblo", "Pueblo", 4, 72, 1, extra_stone=60)

def stone_hall():
    """A long hall of smooth stone with a table down the middle and candles on it."""
    g = G(13, 12, 11)
    g.box(1, 11, 0, 0, 1, 9, m("polished_andesite"))
    g.body(1, 11, 1, 9, 1, 4, m("smooth_stone"), post=m("andesite"), sill=m("stone_bricks"), win=2)
    g.roof_x(0, 12, 0, 10, 5, "andesite", gable=m("smooth_stone"), gx=(1, 11), cap="andesite")
    g.door(6, 1, 9, "spruce")
    g.beds([2, 3, 4, 8, 9, 10], 1, 2, "gray")
    for x in (3, 4, 5, 7, 8, 9): g.put(x, 1, 7, slab("smooth_stone", "top"))
    g.put(4, 2, 7, m("white_candle[candles=3,lit=true]")); g.put(8, 2, 7, m("white_candle[candles=3,lit=true]"))
    furnish(g, [(6, 2, "barrel[facing=up]", "potted_fern"), (2, 8, "crafting_table"), (10, 8, "smoker[facing=west]")])
    light(g, 6, 9, 5)
    path(g, 6, 10); path(g, 6, 11)
    return g.result("stone_hall", "Stone Hall", 6, 99, 2, extra_stone=150)

def copper_house():
    """Bright copper, kept from going green with wax, with grates for windows and a copper lamp."""
    g = G(9, 10, 10)
    g.box(1, 7, 0, 0, 1, 7, m("spruce_planks"))
    g.body(1, 7, 1, 7, 1, 3, m("waxed_copper_block"), post=m("waxed_cut_copper"), sill=m("waxed_chiseled_copper"),
           glass="waxed_copper_grate", win=2)
    g.roof_z(0, 8, 0, 8, 4, "waxed_cut_copper", gable=m("waxed_copper_block"), gz=(1, 7), cap="waxed_cut_copper")
    g.door(4, 1, 7, "spruce")
    g.beds([2, 6], 1, 2, "orange")
    furnish(g, [(4, 2, "waxed_copper_bulb[lit=true]"), (2, 6, "barrel[facing=up]"), (6, 6, "stonecutter[facing=west]")])
    light(g, 4, 7, 4)
    path(g, 4, 8); path(g, 4, 9)
    return g.result("copper_house", "Copper House", 2, 49, 3, extra_stone=160)

def tiled_house():
    """Blue clay faced with glazed tiles, under a flat roof with a crenellated parapet."""
    g = G(9, 10, 7)
    g.box(1, 7, 0, 0, 1, 7, m("cyan_terracotta"))
    g.body(1, 7, 1, 7, 1, 3, m("light_blue_terracotta"), post=m("blue_glazed_terracotta[facing=north]"),
           sill=m("light_blue_glazed_terracotta[facing=north]"), win=2)
    g.box(1, 7, 4, 4, 1, 7, m("cyan_terracotta"))
    for x in range(1, 8):
        for z in range(1, 8):
            if (x in (1, 7) or z in (1, 7)) and (x + z) % 2 == 0: g.put(x, 5, z, m("cyan_terracotta"))
    g.door(4, 1, 7, "birch")
    g.beds([2, 6], 1, 2, "cyan")
    furnish(g, [(4, 2, "decorated_pot[facing=south]"), (2, 6, "barrel[facing=up]", "potted_cornflower"),
                (6, 6, "cyan_glazed_terracotta[facing=north]", "potted_blue_orchid")])
    rug(g, 3, 5, 3, 3, "light_blue")
    light(g, 4, 3, 4)
    path(g, 4, 8); path(g, 4, 9)
    return g.result("tiled_house", "Tiled House", 2, 49, 2, extra_stone=60)

def modern_house():
    """A low box of grey concrete with wide windows, under a flat roof that overhangs on every side."""
    g = G(13, 11, 7)
    g.box(1, 11, 0, 0, 1, 8, m("white_concrete"))
    g.body(1, 11, 1, 8, 1, 3, m("light_gray_concrete"), post=m("black_concrete"), win=-9)
    for x in range(2, 11):
        g.put(x, 2, 8, m("glass")); g.put(x, 2, 1, m("glass"))
    for z in range(2, 8):
        g.put(1, 2, z, m("glass")); g.put(11, 2, z, m("glass"))
    g.box(0, 12, 4, 4, 0, 9, m("gray_concrete"))
    g.door(6, 1, 8, "birch")
    g.beds([2, 3, 9, 10], 1, 2, "black")
    furnish(g, [(6, 2, "ochre_froglight[axis=y]"), (2, 7, "barrel[facing=up]", "potted_azalea_bush"), (10, 7, "barrel[facing=up]", "potted_bamboo")])
    rug(g, 5, 7, 5, 6, "gray")
    light(g, 6, 3, 4)
    path(g, 6, 9); path(g, 6, 10)
    return g.result("modern_house", "Modern House", 4, 88, 3, extra_stone=120)

def boathouse():
    """Low walls under a roof that is a boat turned upside down, its keel along the top."""
    g = G(9, 12, 9)
    g.box(1, 7, 0, 0, 1, 10, m("oak_planks"))
    g.body(1, 7, 1, 10, 1, 2, m("spruce_planks"), post=log("spruce_log"), win=-9)
    for z in range(12):
        for x in (0, 1, 7, 8): g.put(x, 3, z, m("dark_oak_planks"))
        for x in (1, 7): g.put(x, 4, z, m("dark_oak_planks"))
        for x in (2, 6): g.put(x, 5, z, m("dark_oak_planks"))
        for x in (3, 4, 5): g.put(x, 6, z, m("dark_oak_planks"))
        g.put(4, 7, z, log("stripped_dark_oak_log", "z"))
        for x in range(2, 7):
            for y in (3, 4): g.put(x, y, z, m("spruce_planks") if z in (1, 10) else AIR)
        for x in (3, 4, 5): g.put(x, 5, z, m("spruce_planks") if z in (1, 10) else AIR)
    g.put(4, 4, 1, m("glass_pane")); g.put(4, 4, 10, m("glass_pane"))
    g.door(4, 1, 10, "spruce")
    g.beds([2, 3, 5, 6], 1, 2, "blue")
    furnish(g, [(4, 2, "barrel[facing=up]"), (2, 8, "cartography_table"), (6, 8, "barrel[facing=up]", "potted_fern")])
    light(g, 4, 5, 5); light(g, 4, 5, 8)
    path(g, 4, 11)
    return g.result("boathouse", "Upturned Boat House", 4, 70, 1)

def domed_house():
    """White walls under a dome of lapis, with a lamp on the very top."""
    g = G(11, 11, 10)
    g.box(1, 9, 0, 0, 1, 9, m("birch_planks"))
    g.body(1, 9, 1, 9, 1, 3, m("white_terracotta"), post=m("smooth_quartz"), sill=m("lapis_block"), glass="blue_stained_glass_pane", win=2)
    g.box(1, 9, 4, 4, 1, 9, m("white_terracotta"))
    for x in range(11):
        for z in range(11):
            for y in range(5, 9):
                d = (x - 5) ** 2 + (z - 5) ** 2 + (y - 4) ** 2
                if 9 < d <= 16.5: g.put(x, y, z, m("lapis_block"))
                elif d <= 9: g.put(x, y, z, AIR)
    g.put(5, 9, 5, lantern(False))
    g.door(5, 1, 9, "birch")
    g.beds([2, 3, 7, 8], 1, 2, "blue")
    furnish(g, [(5, 2, "barrel[facing=up]", "potted_lily_of_the_valley"), (2, 8, "lectern[facing=east]"), (8, 8, "bookshelf", "candle[candles=2,lit=true]")])
    light(g, 5, 3, 5)
    path(g, 5, 10)
    return g.result("domed_house", "Domed House", 4, 81, 2, extra_stone=120)

builders = [lighthouse, windmill, yurt, hill_house, pumpkin_cottage, beekeepers_house, amethyst_house, dripstone_hut, resin_cottage,
            obsidian_tower, ice_house, thatched_cottage, machiya, pueblo, stone_hall, copper_house, tiled_house, modern_house,
            boathouse, domed_house]

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
