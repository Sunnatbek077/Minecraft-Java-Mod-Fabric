package com.sunnatbek.villagerciv.mod;

import com.sunnatbek.villagerciv.sim.Commodity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * The chest by a citizen's bed, where it puts down what it brought in from work. What lies in it
 * is real and not yet the settlement's: it is counted into the stock only when its owner carries
 * it to the storehouse, in the evening or when the chest is filling up.
 */
public final class PersonalChests {
	private static final Commodity[] COMMODITIES = Commodity.values();

	private PersonalChests() {
	}

	/** The item a unit of stock is shown as, or null for what is not kept in chests. */
	static Item itemOf(Commodity commodity) {
		return switch (commodity) {
			case FOOD -> Items.BREAD;
			case WOOD -> Items.OAK_LOG;
			case STONE -> Items.COBBLESTONE;
			case ORE -> Items.RAW_IRON;
			case TOOLS -> null;
		};
	}

	/** Units of stock one such item stands for; the same figures a gift to the Town Center is counted by. */
	static int unitsPerItem(Commodity commodity) {
		return commodity == Commodity.WOOD ? 4 : 1;
	}

	/**
	 * Puts a load into a chest as items.
	 *
	 * @return the units that are not in the chest afterwards: what did not fit, or was less than one item
	 */
	public static int stock(ServerLevel level, BlockPos pos, Commodity commodity, int units) {
		Item item = itemOf(commodity);
		if (item == null || !(level.getBlockEntity(pos) instanceof Container chest)) {
			return units;
		}
		int perItem = unitsPerItem(commodity);
		int left = add(chest, item, units / perItem);
		chest.setChanged();
		return left * perItem + units % perItem;
	}

	/** How many of the chest's slots have something in them. */
	public static int usedSlots(ServerLevel level, BlockPos pos) {
		if (!(level.getBlockEntity(pos) instanceof Container chest)) {
			return 0;
		}
		int used = 0;
		for (int slot = 0; slot < chest.getContainerSize(); slot++) {
			if (!chest.getItem(slot).isEmpty()) {
				used++;
			}
		}
		return used;
	}

	/**
	 * Takes everything of worth out of a chest.
	 *
	 * @return what was taken; things a stockpile has no use for are left lying
	 */
	public static List<ItemStack> empty(ServerLevel level, BlockPos pos) {
		List<ItemStack> taken = new ArrayList<>();
		if (!(level.getBlockEntity(pos) instanceof Container chest)) {
			return taken;
		}
		for (int slot = 0; slot < chest.getContainerSize(); slot++) {
			ItemStack stack = chest.getItem(slot);
			CommodityValues.Value value = stack.isEmpty() ? null : CommodityValues.of(stack);
			if (value != null && value.tool() == null) {
				taken.add(stack);
				chest.setItem(slot, ItemStack.EMPTY);
			}
		}
		chest.setChanged();
		return taken;
	}

	/**
	 * Puts stacks into a chest as they are.
	 *
	 * @return whether all of them went in; what did not is left in the list
	 */
	public static boolean put(ServerLevel level, BlockPos pos, List<ItemStack> stacks) {
		if (!(level.getBlockEntity(pos) instanceof Container chest)) {
			return stacks.isEmpty();
		}
		stacks.removeIf(stack -> {
			int left = add(chest, stack.getItem(), stack.getCount());
			stack.setCount(left);
			return left == 0;
		});
		chest.setChanged();
		return stacks.isEmpty();
	}

	/** What a load of goods comes to, by commodity. */
	public static int[] worth(List<ItemStack> stacks) {
		int[] units = new int[COMMODITIES.length];
		for (ItemStack stack : stacks) {
			CommodityValues.Value value = CommodityValues.of(stack);
			if (value != null && value.tool() == null) {
				units[value.commodity().ordinal()] += value.unitsPerItem() * stack.getCount();
			}
		}
		return units;
	}

	/** @return how many items did not fit */
	static int add(Container chest, Item item, int count) {
		for (int slot = 0; slot < chest.getContainerSize() && count > 0; slot++) {
			ItemStack held = chest.getItem(slot);
			if (held.isEmpty()) {
				int moved = Math.min(count, new ItemStack(item).getMaxStackSize());
				chest.setItem(slot, new ItemStack(item, moved));
				count -= moved;
			} else if (held.is(item) && held.getCount() < held.getMaxStackSize()) {
				int moved = Math.min(count, held.getMaxStackSize() - held.getCount());
				held.grow(moved);
				count -= moved;
			}
		}
		return count;
	}
}
