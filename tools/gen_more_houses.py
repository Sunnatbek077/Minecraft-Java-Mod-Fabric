"""Draws twenty more houses, each in woods and stones none of the others use: bamboo, mangrove,
jungle, acacia, the two nether woods, pale oak; blackstone, tuff, red sandstone, prismarine, quartz,
end stone, nether brick, granite, calcite, basalt, snow, giant mushroom and coloured terracotta.
They run from a one-bed hut to a six-bed manor, and each is furnished after its own kind.

Run: python3 tools/gen_more_houses.py <blueprints dir> [id to print | all]

Uses the grid toolkit of gen_unique_houses.py; see there for how a design is drawn.
"""
import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from gen_houses import dump
from gen_unique_houses import G, AIR, m, stairs, slab, log, lantern, furnish, rug, path, check

def light(g, x, y, z, kind="lantern"):
    """A lamp hanging from whatever is above."""
    assert g.get(x, y + 1, z) not in (AIR, None), (x, y, z)
    g.put(x, y, z, lantern() if kind == "lantern" else m(kind))

def bamboo_hut():
    """One room of bamboo under a mosaic roof: the smallest house there is."""
    g = G(7, 9, 9)
    g.box(1, 5, 0, 0, 1, 6, m("bamboo_mosaic"))
    g.body(1, 5, 1, 6, 1, 3, m("bamboo_planks"), post=log("bamboo_block"), beam="stripped_bamboo_block", win=2)
    g.roof_z(0, 6, 0, 7, 4, "bamboo_mosaic", gable=m("bamboo_planks"), gz=(1, 6), cap="bamboo")
    g.door(3, 1, 6, "bamboo")
    g.beds([2], 1, 2, "lime")
    furnish(g, [(4, 2, "barrel[facing=up]", "potted_bamboo"), (4, 4, "composter")])
    light(g, 3, 6, 3)
    path(g, 3, 7); path(g, 3, 8)
    return g.result("bamboo_hut", "Bamboo Hut", 1, 30, 1)

def mangrove_lodge():
    """Red mangrove boards on a footing of mud brick, with packed mud in the gables."""
    g = G(9, 10, 10)
    g.box(1, 7, 0, 0, 1, 7, m("mangrove_planks"))
    g.body(1, 7, 1, 7, 1, 3, m("mangrove_planks"), post=log("mangrove_log"), sill=m("mud_bricks"), beam="stripped_mangrove_log", win=2)
    g.roof_x(0, 8, 0, 8, 4, "mangrove", gable=m("packed_mud"), gx=(1, 7), cap="mangrove")
    g.door(4, 1, 7, "mangrove")
    g.beds([2, 6], 1, 2, "brown")
    furnish(g, [(4, 2, "barrel[facing=up]", "potted_mangrove_propagule"), (2, 6, "composter"), (6, 6, "mangrove_roots", "potted_fern")])
    rug(g, 3, 5, 3, 3, "brown")
    light(g, 4, 7, 4)
    path(g, 4, 8); path(g, 4, 9)
    return g.result("mangrove_lodge", "Mangrove Lodge", 2, 49, 1)

def jungle_longhouse():
    """Long and narrow, of jungle wood on mossy stone, with a jukebox by the door."""
    g = G(9, 14, 10)
    g.box(1, 7, 0, 0, 1, 11, m("jungle_planks"))
    g.body(1, 7, 1, 11, 1, 3, m("jungle_planks"), post=log("jungle_log"), sill=m("mossy_cobblestone"), beam="stripped_jungle_log", win=2)
    g.roof_z(0, 8, 0, 12, 4, "jungle", gable=m("jungle_planks"), gz=(1, 11), cap="bamboo")
    g.door(4, 1, 11, "jungle")
    g.beds([2, 3, 5, 6], 1, 2, "green")
    furnish(g, [(2, 10, "jukebox"), (6, 10, "barrel[facing=up]", "potted_jungle_sapling"), (2, 8, "crafting_table"), (6, 8, "composter")])
    rug(g, 3, 5, 7, 9, "lime")
    light(g, 4, 7, 3); light(g, 4, 7, 9)
    path(g, 4, 12); path(g, 4, 13)
    return g.result("jungle_longhouse", "Jungle Longhouse", 4, 77, 1)

def acacia_house():
    """Orange clay walls between acacia posts, under a stepped flat roof."""
    g = G(11, 10, 8)
    g.box(1, 9, 0, 0, 1, 7, m("acacia_planks"))
    g.body(1, 9, 1, 7, 1, 3, m("orange_terracotta"), post=log("acacia_log"), sill=m("red_sandstone"), beam="stripped_acacia_log", win=2)
    g.box(0, 10, 4, 4, 0, 8, slab("acacia"))
    g.box(2, 8, 4, 4, 2, 6, m("acacia_planks")); g.box(3, 7, 5, 5, 3, 5, slab("acacia"))
    g.door(5, 1, 7, "acacia")
    g.beds([2, 3, 8], 1, 2, "yellow")
    furnish(g, [(5, 2, "loom[facing=south]"), (8, 6, "barrel[facing=up]", "potted_acacia_sapling"), (2, 6, "decorated_pot[facing=south]")])
    rug(g, 4, 6, 3, 3, "orange")
    light(g, 5, 3, 4)
    path(g, 5, 8); path(g, 5, 9)
    return g.result("acacia_house", "Acacia House", 3, 63, 1)

def crimson_house():
    """Crimson boards on nether brick, lit by a shroomlight, under a hipped roof of red nether brick."""
    g = G(9, 10, 10)
    g.box(1, 7, 0, 0, 1, 7, m("crimson_planks"))
    g.body(1, 7, 1, 7, 1, 3, m("crimson_planks"), post=log("crimson_stem"), sill=m("nether_bricks"), beam="stripped_crimson_stem",
           glass="red_stained_glass_pane", win=2)
    top = g.hip(0, 8, 0, 8, 4, "red_nether_brick", cap="nether_brick")
    g.door(4, 1, 7, "crimson")
    g.beds([2, 6], 1, 2, "magenta")
    furnish(g, [(4, 2, "shroomlight", "potted_crimson_fungus"), (2, 6, "barrel[facing=up]"), (6, 6, "smoker[facing=west]")])
    rug(g, 3, 5, 3, 3, "magenta")
    light(g, 4, top - 1, 4, "soul_lantern[hanging=true]")
    path(g, 4, 8); path(g, 4, 9)
    return g.result("crimson_house", "Crimson House", 2, 49, 2, extra_stone=40)

def warped_house():
    """Blue-green warped wood on blackstone, with a shelf of books for every bed."""
    g = G(9, 11, 11)
    g.box(1, 7, 0, 0, 1, 8, m("warped_planks"))
    g.body(1, 7, 1, 8, 1, 3, m("warped_planks"), post=log("warped_stem"), sill=m("polished_blackstone_bricks"), beam="stripped_warped_stem",
           glass="cyan_stained_glass_pane", win=2)
    g.roof_z(0, 8, 0, 9, 4, "warped", gable=m("warped_planks"), gz=(1, 8), cap="crimson")
    g.door(4, 1, 8, "warped")
    g.beds([2, 3, 6], 1, 2, "cyan")
    furnish(g, [(4, 2, "chiseled_bookshelf[facing=south]", "chiseled_bookshelf[facing=south]"),
                (6, 7, "barrel[facing=up]", "potted_warped_fungus"), (2, 7, "cartography_table")])
    rug(g, 3, 5, 6, 6, "cyan")
    light(g, 4, 7, 4, "soul_lantern[hanging=true]")
    path(g, 4, 9); path(g, 4, 10)
    return g.result("warped_house", "Warped House", 3, 56, 2, extra_stone=30)

def pale_oak_cottage():
    """Pale boards on white calcite, under a dark roof of deepslate tiles; a candle burns on the shelf."""
    g = G(9, 10, 10)
    g.box(1, 7, 0, 0, 1, 7, m("pale_oak_planks"))
    g.body(1, 7, 1, 7, 1, 3, m("pale_oak_planks"), post=log("pale_oak_log"), sill=m("calcite"), beam="stripped_pale_oak_log",
           glass="light_gray_stained_glass_pane", win=2)
    g.roof_x(0, 8, 0, 8, 4, "deepslate_tile", gable=m("pale_oak_planks"), gx=(1, 7), cap="deepslate_tile")
    g.door(4, 1, 7, "pale_oak")
    g.beds([2, 6], 1, 2, "light_gray")
    furnish(g, [(4, 2, "bookshelf", "candle[candles=3,lit=true]"), (2, 6, "barrel[facing=up]", "potted_pale_oak_sapling"), (6, 6, "fletching_table")])
    rug(g, 3, 5, 3, 3, "gray")
    light(g, 4, 7, 4)
    path(g, 4, 8); path(g, 4, 9)
    return g.result("pale_oak_cottage", "Pale Oak Cottage", 2, 49, 2, extra_stone=40)

def blackstone_keep():
    """A square tower of blackstone brick: arrow slits, a store room up a ladder, battlements on top."""
    g = G(9, 10, 11)
    g.box(1, 7, 0, 0, 1, 7, m("polished_blackstone"))
    g.body(1, 7, 1, 7, 1, 7, m("polished_blackstone_bricks"), post=m("chiseled_polished_blackstone"), win=-9)
    for y in (2, 6):
        g.put(1, y, 4, m("iron_bars")); g.put(7, y, 4, m("iron_bars")); g.put(4, y, 1, m("iron_bars"))
    g.put(3, 6, 7, m("iron_bars")); g.put(5, 6, 7, m("iron_bars"))
    g.box(2, 6, 4, 4, 2, 6, m("blackstone")); g.put(6, 4, 5, AIR)
    for y in range(1, 5): g.put(6, y, 5, m("ladder[facing=west]"))
    g.box(1, 7, 8, 8, 1, 7, m("polished_blackstone"))
    for x in range(1, 8):
        for z in range(1, 8):
            if (x in (1, 7) or z in (1, 7)) and (x + z) % 2 == 0: g.put(x, 9, z, m("polished_blackstone_brick_wall"))
    g.door(4, 1, 7, "dark_oak")
    g.beds([2, 3], 1, 2, "gray")
    furnish(g, [(6, 2, "blast_furnace[facing=south]"), (5, 2, "barrel[facing=up]")])
    furnish(g, [(2, 2, "barrel[facing=up]"), (3, 2, "smithing_table"), (5, 2, "gilded_blackstone"), (2, 6, "bookshelf")], y=5)
    light(g, 4, 3, 4); light(g, 4, 7, 4)
    path(g, 4, 8); path(g, 4, 9)
    return g.result("blackstone_keep", "Blackstone Keep", 2, 49, 2, extra_stone=160)

def tuff_house():
    """Grey tuff brick under a copper roof, a lightning rod on its ridge and a stonecutter within."""
    g = G(11, 10, 11)
    g.box(1, 9, 0, 0, 1, 7, m("polished_tuff"))
    g.body(1, 9, 1, 7, 1, 3, m("tuff_bricks"), post=m("chiseled_tuff"), sill=m("polished_tuff"), win=2)
    top = g.roof_z(0, 10, 0, 8, 4, "waxed_exposed_cut_copper", gable=m("tuff_bricks"), gz=(1, 7))
    g.put(5, top + 1, 4, m("lightning_rod[facing=up]"))
    g.door(5, 1, 7, "birch")
    g.beds([2, 3, 7, 8], 1, 2, "orange")
    furnish(g, [(5, 2, "stonecutter[facing=south]"), (8, 6, "barrel[facing=up]", "potted_azure_bluet"), (2, 6, "grindstone[face=floor,facing=south]")])
    rug(g, 4, 6, 3, 3, "light_gray")
    light(g, 5, top - 1, 4)
    path(g, 5, 8); path(g, 5, 9)
    return g.result("tuff_house", "Tuff House", 4, 63, 2, extra_stone=90)

def red_sandstone_villa():
    """High rooms of red sandstone under a flat roof with a parapet, and pots of cactus by the door."""
    g = G(11, 12, 8)
    g.box(1, 9, 0, 0, 1, 9, m("smooth_red_sandstone"))
    g.body(1, 9, 1, 9, 1, 4, m("red_sandstone"), post=m("cut_red_sandstone"), sill=m("smooth_red_sandstone"),
           glass="orange_stained_glass_pane", win=2)
    g.box(1, 9, 5, 5, 1, 9, m("smooth_red_sandstone"))
    for x in range(1, 10):
        for z in range(1, 10):
            if x in (1, 9) or z in (1, 9): g.put(x, 6, z, m("red_sandstone_wall"))
    g.door(5, 1, 9, "acacia")
    g.beds([2, 3, 7, 8], 1, 2, "red")
    furnish(g, [(5, 2, "decorated_pot[facing=south]"), (2, 8, "barrel[facing=up]", "potted_cactus"), (8, 8, "smoker[facing=west]"),
                (8, 7, "cut_red_sandstone", "potted_dead_bush")])
    rug(g, 4, 6, 6, 7, "orange")
    light(g, 5, 4, 3); light(g, 5, 4, 6)
    path(g, 5, 10); path(g, 5, 11)
    return g.result("red_sandstone_villa", "Red Sandstone Villa", 4, 81, 2, extra_stone=120)

def prismarine_house():
    """Sea-green brick with a sea lantern at the peak and another under the floor."""
    g = G(9, 10, 10)
    g.box(1, 7, 0, 0, 1, 7, m("dark_prismarine")); g.put(4, 0, 4, m("sea_lantern"))
    g.body(1, 7, 1, 7, 1, 3, m("prismarine_bricks"), post=m("dark_prismarine"), sill=m("prismarine"),
           glass="light_blue_stained_glass_pane", win=2)
    top = g.hip(0, 8, 0, 8, 4, "prismarine_brick")
    g.put(4, top, 4, m("sea_lantern"))
    g.door(4, 1, 7, "birch")
    g.beds([2, 6], 1, 2, "light_blue")
    furnish(g, [(4, 2, "water_cauldron[level=3]"), (2, 6, "barrel[facing=up]", "potted_blue_orchid"), (6, 6, "cartography_table")])
    rug(g, 3, 5, 3, 3, "light_blue")
    path(g, 4, 8); path(g, 4, 9)
    return g.result("prismarine_house", "Prismarine House", 2, 49, 2, extra_stone=110)

def quartz_manor():
    """The grandest of them: white quartz with pillars at the corners, under a roof of green copper."""
    g = G(13, 12, 12)
    g.box(1, 11, 0, 0, 1, 9, m("smooth_quartz"))
    g.body(1, 11, 1, 9, 1, 4, m("quartz_bricks"), post=log("quartz_pillar"), sill=m("chiseled_quartz_block"),
           glass="white_stained_glass_pane", win=2)
    top = g.hip(0, 12, 0, 10, 5, "waxed_oxidized_cut_copper")
    g.door(6, 1, 9, "birch")
    g.beds([2, 3, 4, 8, 9, 10], 1, 2, "white")
    furnish(g, [(6, 2, "chiseled_bookshelf[facing=south]", "chiseled_bookshelf[facing=south]"),
                (2, 8, "quartz_pillar[axis=y]", "potted_white_tulip"), (10, 8, "quartz_pillar[axis=y]", "potted_lily_of_the_valley"),
                (10, 7, "lectern[facing=west]"), (2, 7, "loom[facing=east]")])
    rug(g, 5, 7, 6, 8, "white")
    light(g, 6, top - 1, 5)
    path(g, 6, 10); path(g, 6, 11)
    return g.result("quartz_manor", "Quartz Manor", 6, 99, 3, extra_stone=200)

def end_stone_house():
    """Pale end stone brick between purple pillars, lit by an end rod; a brewing stand at the back."""
    g = G(9, 10, 10)
    g.box(1, 7, 0, 0, 1, 7, m("purpur_block"))
    g.body(1, 7, 1, 7, 1, 3, m("end_stone_bricks"), post=log("purpur_pillar"), glass="magenta_stained_glass_pane", win=2)
    g.roof_x(0, 8, 0, 8, 4, "purpur", gable=m("end_stone_bricks"), gx=(1, 7), cap="purpur")
    g.door(4, 1, 7, "birch")
    g.beds([2, 6], 1, 2, "purple")
    furnish(g, [(4, 2, "brewing_stand"), (2, 6, "barrel[facing=up]", "potted_allium"), (6, 6, "bookshelf")])
    rug(g, 3, 5, 3, 3, "purple")
    light(g, 4, 7, 4, "end_rod[facing=down]")
    path(g, 4, 8); path(g, 4, 9)
    return g.result("end_stone_house", "End Stone House", 2, 49, 3, extra_stone=110)

def nether_forge_house():
    """A smith's house of nether brick with a basalt chimney, an anvil and a cauldron of lava."""
    g = G(9, 11, 11)
    g.box(1, 7, 0, 0, 1, 8, m("blackstone"))
    g.body(1, 7, 1, 8, 1, 3, m("nether_bricks"), post=log("polished_basalt"), sill=m("red_nether_bricks"), glass="iron_bars", win=2)
    g.roof_z(0, 8, 0, 9, 4, "polished_blackstone_brick", gable=m("red_nether_bricks"), gz=(1, 8))
    g.box(6, 6, 1, 9, 1, 1, log("basalt"))
    g.door(4, 1, 8, "dark_oak")
    g.beds([2, 3], 1, 2, "black")
    furnish(g, [(6, 2, "blast_furnace[facing=south]"), (6, 4, "anvil[facing=north]"), (6, 6, "lava_cauldron"), (2, 7, "barrel[facing=up]")])
    light(g, 4, 7, 4, "soul_lantern[hanging=true]")
    path(g, 4, 9); path(g, 4, 10)
    return g.result("nether_forge_house", "Nether Forge House", 2, 56, 2, extra_stone=130)

def granite_house():
    """Polished red granite with brick corners and a brick roof, floored in cherry."""
    g = G(10, 10, 10)
    g.box(1, 8, 0, 0, 1, 7, m("cherry_planks"))
    g.body(1, 8, 1, 7, 1, 3, m("polished_granite"), post=m("bricks"), sill=m("granite"), win=2)
    g.roof_z(0, 9, 0, 8, 4, "brick", gable=m("polished_granite"), gz=(1, 7))
    g.door(4, 1, 7, "cherry")
    g.beds([2, 3, 7], 1, 2, "pink")
    furnish(g, [(5, 2, "bookshelf", "potted_orange_tulip"), (6, 2, "barrel[facing=up]"), (7, 6, "smoker[facing=west]"), (2, 6, "crafting_table")])
    g.put(6, 2, 2, lantern(False))
    rug(g, 4, 6, 3, 3, "pink")
    path(g, 4, 8); path(g, 4, 9)
    return g.result("granite_house", "Granite House", 3, 56, 2, extra_stone=90)

def calcite_cottage():
    """White calcite walls framed in pale birch, with a weaver's loom."""
    g = G(9, 12, 10)
    g.box(1, 7, 0, 0, 1, 9, m("birch_planks"))
    g.body(1, 7, 1, 9, 1, 3, m("calcite"), post=log("stripped_birch_log"), sill=m("polished_diorite"), beam="birch_log", win=2)
    g.roof_z(0, 8, 0, 10, 4, "birch", gable=m("calcite"), gz=(1, 9), cap="birch")
    g.door(4, 1, 9, "birch")
    g.beds([2, 3, 5, 6], 1, 2, "blue")
    furnish(g, [(2, 8, "loom[facing=east]"), (6, 8, "barrel[facing=up]", "potted_oxeye_daisy"), (6, 7, "composter")])
    rug(g, 3, 5, 6, 7, "blue")
    light(g, 4, 7, 5)
    path(g, 4, 10); path(g, 4, 11)
    return g.result("calcite_cottage", "Calcite Cottage", 4, 63, 1, extra_stone=50)

def igloo():
    """A dome of snow with windows of ice, and a white rug on the floor."""
    g = G(9, 10, 7)
    def inside(x, y, z, r): return (x - 4) ** 2 + (z - 4) ** 2 + (y - 0.5) ** 2 <= r * r
    for x in range(9):
        for z in range(9):
            if inside(x, 1, z, 3.8): g.put(x, 0, z, m("snow_block"))
            for y in range(1, 6):
                if inside(x, y, z, 2.7): g.air(x, y, z)
                elif inside(x, y, z, 3.8): g.put(x, y, z, m("snow_block"))
    for x, z in ((1, 4), (7, 4), (4, 1)): g.put(x, 2, z, m("packed_ice"))
    g.door(4, 1, 7)
    g.beds([3, 5], 1, 2, "white")
    furnish(g, [(4, 2, "crafting_table"), (2, 4, "barrel[facing=up]"), (6, 4, "smoker[facing=west]")])
    g.put(2, 2, 4, lantern(False))
    rug(g, 2, 6, 2, 6, "white")
    path(g, 4, 8); path(g, 4, 9)
    return g.result("igloo", "Igloo", 2, 45, 1)

def mushroom_house():
    """A house in a giant mushroom: white stem for walls and a red cap, spotted white, for a roof."""
    g = G(11, 11, 9)
    def inside(x, z, r): return max(abs(x - 5), abs(z - 5)) <= r and abs(x - 5) + abs(z - 5) <= 2 * r - 1
    def rim(x, z, r): return inside(x, z, r) and not all(inside(x + a, z + b, r) for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1)))
    for x in range(11):
        for z in range(11):
            if inside(x, z, 3):
                g.put(x, 0, z, m("brown_mushroom_block"))
                for y in (1, 2, 3):
                    if rim(x, z, 3): g.put(x, y, z, m("mushroom_stem"))
                    else: g.air(x, y, z)
            if inside(x, z, 5):
                if not inside(x, z, 3) or rim(x, z, 3): g.put(x, 4, z, m("red_mushroom_block"))
                else: g.air(x, 4, z)
            if inside(x, z, 4): g.put(x, 5, z, m("red_mushroom_block"))
            if inside(x, z, 2): g.put(x, 6, z, m("red_mushroom_block"))
    for x, y, z in ((3, 5, 3), (7, 5, 4), (4, 5, 7), (6, 6, 5), (2, 4, 6), (8, 4, 7), (5, 4, 1), (4, 6, 4)): g.put(x, y, z, m("mushroom_stem"))
    for x, z in ((2, 5), (8, 5), (5, 2)): g.put(x, 2, z, m("glass_pane"))
    g.door(5, 1, 8, "oak")
    g.beds([4, 6], 1, 3, "red")
    furnish(g, [(5, 3, "barrel[facing=up]", "potted_red_mushroom"), (3, 7, "composter"), (7, 7, "smoker[facing=west]"),
                (3, 5, "brown_mushroom_block", "potted_brown_mushroom")])
    rug(g, 5, 5, 6, 6, "red")
    light(g, 5, 4, 5)
    path(g, 5, 9); path(g, 5, 10)
    return g.result("mushroom_house", "Mushroom House", 2, 60, 1)

def basalt_lodge():
    """Dark basalt between polished pillars, with a warm red roof of mangrove."""
    g = G(11, 11, 11)
    g.box(1, 9, 0, 0, 1, 8, m("dark_oak_planks"))
    g.body(1, 9, 1, 8, 1, 3, m("smooth_basalt"), post=log("polished_basalt"), sill=m("cobbled_deepslate"), beam="basalt",
           glass="gray_stained_glass_pane", win=2)
    g.roof_x(0, 10, 0, 9, 4, "mangrove", gable=m("smooth_basalt"), gx=(1, 9))
    g.door(5, 1, 8, "mangrove")
    g.beds([2, 3, 7, 8], 1, 2, "gray")
    furnish(g, [(5, 2, "smoker[facing=south]"), (2, 7, "barrel[facing=up]"), (8, 7, "fletching_table")])
    g.put(2, 2, 7, lantern(False))
    rug(g, 4, 6, 3, 3, "red")
    path(g, 5, 9); path(g, 5, 10)
    return g.result("basalt_lodge", "Basalt Lodge", 4, 72, 2, extra_stone=100)

def terracotta_rowhouse():
    """Narrow and tall, in blue and yellow clay under a brick roof, with a study up the ladder."""
    g = G(7, 10, 13)
    g.box(1, 5, 0, 0, 1, 7, m("birch_planks"))
    g.body(1, 5, 1, 7, 1, 7, m("blue_terracotta"), post=m("yellow_terracotta"), sill=m("bricks"), win=2)
    for z in (1, 7):
        for x in (2, 4): g.put(x, 6, z, m("glass_pane"))
    g.box(2, 4, 4, 4, 2, 6, m("birch_planks")); g.put(4, 4, 5, AIR)
    for y in range(1, 5): g.put(4, y, 5, m("ladder[facing=west]"))
    top = g.roof_z(0, 6, 0, 8, 8, "brick", gable=m("blue_terracotta"), gz=(1, 7), cap="brick")
    g.door(3, 1, 7, "cherry")
    g.beds([2, 3], 1, 2, "yellow")
    furnish(g, [(2, 2, "bookshelf", "bookshelf"), (3, 2, "lectern[facing=south]"), (2, 6, "barrel[facing=up]", "potted_cornflower")], y=5)
    rug(g, 2, 3, 4, 5, "yellow", y=5)
    light(g, 3, 3, 4); light(g, 3, top - 1, 4)
    path(g, 3, 8); path(g, 3, 9)
    return g.result("terracotta_rowhouse", "Terracotta Rowhouse", 2, 35, 2, extra_stone=70)

builders = [bamboo_hut, mangrove_lodge, jungle_longhouse, acacia_house, crimson_house, warped_house, pale_oak_cottage,
            blackstone_keep, tuff_house, red_sandstone_villa, prismarine_house, quartz_manor, end_stone_house, nether_forge_house,
            granite_house, calcite_cottage, igloo, mushroom_house, basalt_lodge, terracotta_rowhouse]

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
