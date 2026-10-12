package com.sunnatbek.villagerciv.mod;

import com.sunnatbek.villagerciv.sim.Commodity;
import com.sunnatbek.villagerciv.sim.ToolKind;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * What an item is worth to a settlement: which commodity it counts as, and how many units one
 * item gives. Items that are not listed mean nothing to a stockpile.
 *
 * <p>This table is code for now; it becomes datapack content together with the other registries.
 */
public final class CommodityValues {
	/** @param tool for {@link Commodity#TOOLS}, which kind of tool it is; otherwise null */
	public record Value(Commodity commodity, int unitsPerItem, ToolKind tool) {
		Value(Commodity commodity, int unitsPerItem) {
			this(commodity, unitsPerItem, null);
		}
	}

	private CommodityValues() {
	}

	/** One meal per four nutrition points, at least one. Shared with feeding a citizen by hand. */
	public static int mealsOf(FoodProperties food) {
		return Math.max(1, food.nutrition() / 4);
	}

	public static Value of(ItemStack stack) {
		// Straw: fodder and bedding. Kept with the food, and worth a little of it.
		if (stack.is(Items.HAY_BLOCK)) {
			return new Value(Commodity.FOOD, 1);
		}
		// What the herds give besides meat: hides, fleeces, feathers. Kept with the food, as
		// farm produce, and like straw worth a little of it.
		if (stack.is(Items.LEATHER) || stack.is(Items.FEATHER) || stack.typeHolder().is(ItemTags.WOOL)) {
			return new Value(Commodity.FOOD, 1);
		}
		// Eggs are eaten, though not as they are laid.
		if (stack.is(Items.EGG)) {
			return new Value(Commodity.FOOD, 1);
		}
		FoodProperties food = stack.get(DataComponents.FOOD);
		if (food != null) {
			return new Value(Commodity.FOOD, mealsOf(food));
		}
		Holder<Item> item = stack.typeHolder();
		if (item.is(ItemTags.LOGS)) {
			return new Value(Commodity.WOOD, 4);
		}
		if (item.is(ItemTags.PLANKS)) {
			return new Value(Commodity.WOOD, 1);
		}
		if (item.is(ItemTags.STONE_CRAFTING_MATERIALS) || item.is(ItemTags.STONE_TOOL_MATERIALS)
				|| stack.is(Items.STONE) || stack.is(Items.STONE_BRICKS)
				|| stack.is(Items.ANDESITE) || stack.is(Items.DIORITE) || stack.is(Items.GRANITE)
				// The rest of what a mine turns up: all of it something to build or mend with.
				|| stack.is(Items.TUFF) || stack.is(Items.DEEPSLATE) || stack.is(Items.CALCITE) || stack.is(Items.DRIPSTONE_BLOCK)
				|| stack.is(Items.SMOOTH_BASALT) || stack.is(Items.GRAVEL) || stack.is(Items.FLINT) || stack.is(Items.DIRT)
				|| stack.is(Items.CLAY_BALL) || item.is(ItemTags.SAND)) {
			return new Value(Commodity.STONE, 1);
		}
		if (stack.is(Items.AMETHYST_BLOCK) || stack.is(Items.AMETHYST_SHARD)) {
			return new Value(Commodity.ORE, 2);
		}
		// What a mine yields besides iron. The rarer finds count for more.
		if (stack.is(Items.DIAMOND) || stack.is(Items.EMERALD)) {
			return new Value(Commodity.ORE, 4);
		}
		if (stack.is(Items.RAW_GOLD) || stack.is(Items.GOLD_INGOT)) {
			return new Value(Commodity.ORE, 2);
		}
		if (stack.is(Items.REDSTONE) || stack.is(Items.LAPIS_LAZULI)) {
			return new Value(Commodity.ORE, 1);
		}
		if (item.is(ItemTags.COALS) || stack.is(Items.RAW_IRON) || stack.is(Items.RAW_COPPER) || stack.is(Items.RAW_GOLD)
				|| stack.is(Items.IRON_INGOT) || stack.is(Items.COPPER_INGOT) || stack.is(Items.GOLD_INGOT)) {
			return new Value(Commodity.ORE, 1);
		}
		ToolKind tool = item.is(ItemTags.HOES) ? ToolKind.HOE
				: item.is(ItemTags.AXES) ? ToolKind.AXE
				: item.is(ItemTags.PICKAXES) ? ToolKind.PICKAXE
				: item.is(ItemTags.SHOVELS) ? ToolKind.SHOVEL
				: item.is(ItemTags.SWORDS) ? ToolKind.SWORD
				: null;
		return tool == null ? null : new Value(Commodity.TOOLS, 1, tool);
	}
}
