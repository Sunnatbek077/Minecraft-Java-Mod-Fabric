"""Draws the workshops: the smithy and the mason's yard. Each is one of the house shells from
gen_houses.py with the beds taken out and the fittings of its trade put in.

Run: python3 tools/gen_workshops.py <blueprints dir> [id to print]
"""
import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from gen_houses import house, dump

def workshop(shell, name, fittings, palette, cost):
    """fittings: {(x, z): key} on the floor layer; palette: the keys' blocks."""
    d = dict(shell)
    layers = [list(layer) for layer in d["layers"]]
    floor = [list(row) for row in layers[1]]
    for z, row in enumerate(floor):
        for x, cell in enumerate(row):
            if cell in "bBhTUVQ":            # beds, bedside chests and house furniture
                floor[z][x] = '.'
    for (x, z), key in fittings.items():
        assert floor[z][x] == '.', (d["id"], x, z, floor[z][x])
        floor[z][x] = key
    layers[1] = ["".join(row) for row in floor]
    used = set("".join("".join(layer) for layer in layers))
    d["layers"] = layers
    d["name"] = name
    d["category"] = "workshop"
    d["beds"] = 0
    d["cost"] = cost
    d["palette"] = {k: v for k, v in {**d["palette"], **palette}.items() if k in used}
    d["late"] = "".join(k for k in d["late"] if k in used)
    return d

# The smithy: stone below, dark timber above, a chimney over the forge. Inside (wall cells are
# 1 and 7, the door is at x=4 in the south wall): two blast furnaces under the chimney, the anvil
# in the middle of the floor, quenching trough, grindstone and smithing table round the walls.
smithy = workshop(
    house("smithy", "Smithy", 7, 7, 3, 2, "ew", "dark_oak", "cobblestone", "stone_bricks", "dark_oak_log", "polished_andesite",
          "stone_bricks", "red", chimney=True, stone_heavy=True, furniture=()),
    "Smithy",
    {(5, 2): '1', (6, 2): '1', (4, 4): '2', (2, 2): '3', (2, 4): '4', (2, 6): '5', (6, 6): '6', (6, 5): '6'},
    {'1': "minecraft:blast_furnace[facing=south,lit=true]", '2': "minecraft:anvil[facing=west]",
     '3': "minecraft:smithing_table", '4': "minecraft:water_cauldron[level=3]",
     '5': "minecraft:grindstone[face=floor,facing=north]", '6': "minecraft:barrel[facing=up]"},
    {"wood": 140, "stone": 180})

# The mason's yard: a low stone shed with the cutter in the middle and cut stone stacked about.
yard = workshop(
    house("masons_yard", "Mason's Yard", 7, 7, 2, 2, "ns", "spruce", "stone_bricks", "cobblestone", "stripped_spruce_log", "stone",
          "stone_bricks", "white", stone_heavy=True, furniture=()),
    "Mason's Yard",
    {(4, 4): '1', (2, 2): '2', (3, 2): '3', (6, 2): '4', (2, 6): '5', (6, 6): '6', (6, 5): '2'},
    {'1': "minecraft:stonecutter[facing=south]", '2': "minecraft:chiseled_stone_bricks", '3': "minecraft:polished_andesite",
     '4': "minecraft:polished_diorite", '5': "minecraft:smooth_stone", '6': "minecraft:polished_granite"},
    {"wood": 100, "stone": 200})

# The market: a wide, airy hall of birch with the counter facing the door and goods stacked round the walls.
market = workshop(
    house("market", "Market", 9, 7, 3, 3, "ew", "birch", "stone_bricks", "birch_planks", "stripped_birch_log", "oak_planks",
          "birch_planks", "white", frame=True, awning=True, furniture=()),
    "Market",
    {(5, 4): '1', (4, 4): '2', (6, 4): '2', (2, 2): '3', (3, 2): '4', (8, 2): '5', (7, 2): '6', (2, 6): '3', (8, 6): '7', (8, 5): '4'},
    {'1': "minecraft:cartography_table", '2': "minecraft:spruce_slab[type=top]", '3': "minecraft:hay_block[axis=y]",
     '4': "minecraft:barrel[facing=up]", '5': "minecraft:melon", '6': "minecraft:pumpkin", '7': "minecraft:composter"},
    {"wood": 200, "stone": 80})

if __name__ == "__main__":
    out = sys.argv[1]
    for d in (smithy, yard, market):
        open(os.path.join(out, d["id"] + ".json"), "w").write(dump(d))
        print(d["id"], f'{len(d["layers"][0][0])}x{len(d["layers"][0])}x{len(d["layers"])}', d["cost"])
        if len(sys.argv) > 2 and sys.argv[2] == d["id"]:
            for i, layer in enumerate(d["layers"][:4]):
                print("layer", i); print("\n".join("|" + r + "|" for r in layer))
