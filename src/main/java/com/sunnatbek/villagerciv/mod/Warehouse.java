package com.sunnatbek.villagerciv.mod;

import com.sunnatbek.villagerciv.api.SettlementId;
import com.sunnatbek.villagerciv.sim.Commodity;
import com.sunnatbek.villagerciv.sim.Ledger;
import com.sunnatbek.villagerciv.sim.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the chests of a main storehouse and the settlement's ledger telling the same story.
 *
 * <p>The ledger stays the truth: it is what the settlement runs on while nobody is near and no
 * chest is loaded. The chests are its face. Whenever a player is close, each section is filled
 * to show what the ledger holds. And because the mod remembers what it last put there, anything
 * that differs the next time it looks was done by somebody's hand: more in the chests is a gift
 * and is added to the stock, less is a withdrawal and is taken off it.
 *
 * <p>Tools are not kept on show. One put into any chest of the storehouse is handed out at once,
 * the same as giving it at the Town Center.
 */
final class Warehouse {
	/** A chest of the storehouse and what its section holds. */
	record StoreChest(BlockPos pos, Commodity commodity) {
	}

	private static final Commodity[] COMMODITIES = Commodity.values();
	private static final double PLAYER_RANGE_SQR = 64.0 * 64.0;

	private final EmbodimentController embodiment;
	/** Units each storehouse was left showing, by commodity. Forgotten on restart, then read afresh. */
	private final Map<SettlementId, int[]> shown = new HashMap<>();

	Warehouse(EmbodimentController embodiment) {
		this.embodiment = embodiment;
	}

	void sync(ServerLevel level, Settlement settlement) {
		List<StoreChest> chests = embodiment.buildSites().storeChests(level, settlement);
		if (chests.isEmpty()) {
			return;
		}
		BlockPos middle = chests.getFirst().pos();
		ServerPlayer nearest = null;
		double nearestDistance = PLAYER_RANGE_SQR;
		for (ServerPlayer player : level.players()) {
			double distance = player.distanceToSqr(middle.getX() + 0.5, middle.getY(), middle.getZ() + 0.5);
			if (distance < nearestDistance && !player.isSpectator()) {
				nearest = player;
				nearestDistance = distance;
			}
		}
		if (nearest == null) {
			return; // nobody to see it or to have touched it
		}

		int[] actual = count(level, settlement, chests, nearest);
		int[] before = shown.computeIfAbsent(settlement.id(), _ -> actual.clone());
		Ledger ledger = settlement.ledger();
		boolean changed = false;
		for (Commodity commodity : COMMODITIES) {
			if (PersonalChests.itemOf(commodity) == null) {
				continue;
			}
			int c = commodity.ordinal();
			int byHand = actual[c] - before[c];
			if (byHand > 0) {
				embodiment.contribute(nearest, settlement, commodity, byHand);
			} else if (byHand < 0) {
				// What is promised to a building under way stays in the ledger.
				ledger.take(commodity, Math.min(-byHand, ledger.available(commodity)));
			}
			changed |= show(level, chests, commodity, ledger.onHand(commodity), actual[c]);
		}
		shown.put(settlement.id(), changed ? count(level, settlement, chests, nearest) : actual);
	}

	/**
	 * A citizen brings goods in: they are put into the chests of their sections just as they are
	 * (a miner's coal stays coal, gold stays gold) and counted into the stock. What the chests
	 * have no room for is counted all the same.
	 */
	void deposit(ServerLevel level, Settlement settlement, List<ItemStack> stacks) {
		List<StoreChest> chests = embodiment.buildSites().storeChests(level, settlement);
		int[] before = shown.get(settlement.id());
		for (ItemStack stack : stacks) {
			CommodityValues.Value value = CommodityValues.of(stack);
			if (value == null || value.tool() != null) {
				continue;
			}
			int count = stack.getCount();
			int left = count;
			for (StoreChest chest : chests) {
				if (left > 0 && chest.commodity() == value.commodity() && level.getBlockEntity(chest.pos()) instanceof Container container) {
					int after = PersonalChests.add(container, stack.getItem(), left);
					if (after != left) {
						container.setChanged();
					}
					left = after;
				}
			}
			settlement.ledger().add(value.commodity(), value.unitsPerItem() * count);
			// Put there by the settlement itself: remembered, so it is not taken for a gift.
			if (before != null) {
				before[value.commodity().ordinal()] += value.unitsPerItem() * (count - left);
			}
		}
	}

	/** Units of each commodity lying in the storehouse's chests, whichever section they were put in. */
	private int[] count(ServerLevel level, Settlement settlement, List<StoreChest> chests, ServerPlayer nearest) {
		int[] units = new int[COMMODITIES.length];
		for (StoreChest chest : chests) {
			if (!(level.getBlockEntity(chest.pos()) instanceof Container container)) {
				continue;
			}
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				ItemStack stack = container.getItem(slot);
				CommodityValues.Value value = stack.isEmpty() ? null : CommodityValues.of(stack);
				if (value == null) {
					continue;
				}
				if (value.tool() != null) {
					embodiment.contributeTools(nearest, settlement, value.tool(), stack.getCount());
					container.setItem(slot, ItemStack.EMPTY);
				} else {
					units[value.commodity().ordinal()] += value.unitsPerItem() * stack.getCount();
				}
			}
		}
		return units;
	}

	/**
	 * Brings what the chests hold of one commodity to what the ledger says, as far as the
	 * section has room for it.
	 *
	 * @return whether any chest was changed
	 */
	private static boolean show(ServerLevel level, List<StoreChest> chests, Commodity commodity, int wanted, int actual) {
		Item item = PersonalChests.itemOf(commodity);
		int perItem = PersonalChests.unitsPerItem(commodity);
		if (wanted - actual >= perItem) {
			int items = (wanted - actual) / perItem;
			int left = items;
			for (StoreChest chest : chests) {
				if (left > 0 && chest.commodity() == commodity && level.getBlockEntity(chest.pos()) instanceof Container container) {
					int after = PersonalChests.add(container, item, left);
					if (after != left) {
						container.setChanged();
					}
					left = after;
				}
			}
			return left != items;
		}
		if (actual > wanted) {
			int surplus = actual - wanted;
			boolean changed = false;
			// What is used up is taken from the plain stock first (the bread, the cobble, the raw
			// iron that stand for units in the books) and only then from the particular things
			// people brought in: a miner's coal and gold are not the first to go when a lamp is made.
			for (int pass = 0; pass < 2 && surplus > 0; pass++) {
				// Emptied from the far end, so a section thins out from its last chest.
				for (int i = chests.size() - 1; i >= 0 && surplus > 0; i--) {
					if (!(level.getBlockEntity(chests.get(i).pos()) instanceof Container container)) {
						continue;
					}
					for (int slot = container.getContainerSize() - 1; slot >= 0 && surplus > 0; slot--) {
						ItemStack stack = container.getItem(slot);
						CommodityValues.Value value = stack.isEmpty() ? null : CommodityValues.of(stack);
						if (value == null || value.tool() != null || value.commodity() != commodity || (pass == 0 && !stack.is(item))) {
							continue;
						}
						int take = Math.min(stack.getCount(), (surplus + value.unitsPerItem() - 1) / value.unitsPerItem());
						stack.shrink(take);
						if (stack.isEmpty()) {
							container.setItem(slot, ItemStack.EMPTY);
						}
						container.setChanged();
						surplus -= take * value.unitsPerItem();
						changed = true;
					}
				}
			}
			return changed;
		}
		return false;
	}
}
