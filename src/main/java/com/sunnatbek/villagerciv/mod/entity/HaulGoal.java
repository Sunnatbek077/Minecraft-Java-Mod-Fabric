package com.sunnatbek.villagerciv.mod.entity;

import com.sunnatbek.villagerciv.mod.EmbodimentController;
import com.sunnatbek.villagerciv.mod.PersonalChests;
import com.sunnatbek.villagerciv.mod.VillagerCivMod;
import com.sunnatbek.villagerciv.mod.WorkDay;
import com.sunnatbek.villagerciv.sim.Activity;
import com.sunnatbek.villagerciv.sim.Citizen;
import com.sunnatbek.villagerciv.sim.Commodity;
import com.sunnatbek.villagerciv.sim.Labor;
import com.sunnatbek.villagerciv.sim.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.List;

/**
 * The end of a working day: a citizen empties the chest by its bed and carries what was in it to
 * the storehouse, where it becomes the settlement's. The same trip is made in the middle of the
 * day if the chest is filling up.
 *
 * <p>Nothing is ever lost on the way. If the trip is cut short, by nightfall, a fright or the
 * server stopping, the load counts as delivered.
 */
final class HaulGoal extends Goal {
	private enum Phase {
		TO_CHEST,
		TO_STORE
	}

	private static final Commodity[] COMMODITIES = Commodity.values();
	private static final double REACH_SQR = 4.0 * 4.0;
	private static final int REPATH_INTERVAL_TICKS = 20;
	private static final int TRAVEL_TIMEOUT_TICKS = 1_200;
	/** How often a citizen thinks about its chest. */
	private static final int CHECK_INTERVAL_TICKS = 60;
	/** A chest this full is emptied without waiting for the evening. */
	private static final int FULL_SLOTS = 18;

	private final CitizenEntity entity;
	private Phase phase;
	private BlockPos chest;
	private List<ItemStack> load;
	private boolean finished;
	private int travelled;
	private int repathIn;
	private int nextCheckTick;

	HaulGoal(CitizenEntity entity) {
		this.entity = entity;
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	/**
	 * The citizen's chest, if there is something in it that ought to go to the storehouse now:
	 * it is evening and the chest is not empty, or the chest is filling up. Null otherwise.
	 */
	static BlockPos chestToEmpty(CitizenEntity entity) {
		Settlement settlement = entity.settlement();
		Citizen citizen = entity.citizen();
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		if (settlement == null || citizen == null || embodiment == null || citizen.isChild() || citizen.home() == Citizen.NO_HOME
				|| !(entity.level() instanceof ServerLevel level)) {
			return null;
		}
		BlockPos chest = embodiment.buildSites().chestOf(level, settlement, citizen.home(), citizen.bed());
		if (chest == null) {
			return null;
		}
		int used = PersonalChests.usedSlots(level, chest);
		return used >= FULL_SLOTS || (used > 0 && WorkDay.isEvening(level)) ? chest : null;
	}

	@Override
	public boolean canUse() {
		if (entity.isSleeping()) {
			return false;
		}
		// By day a look now and then is enough. In the evening it is looked at every time: going
		// home to bed is weighed against this at the same moment, and must not get in first.
		if (!WorkDay.isEvening(entity.level())) {
			if (entity.tickCount < nextCheckTick) {
				return false;
			}
			nextCheckTick = entity.tickCount + CHECK_INTERVAL_TICKS;
		}
		chest = chestToEmpty(entity);
		return chest != null || carriesAtDayEnd(entity);
	}

	/** Evening, and still a part load in hand from the day's work: that goes in tonight as well. */
	static boolean carriesAtDayEnd(CitizenEntity entity) {
		Citizen citizen = entity.citizen();
		return entity.settlement() != null && citizen != null && citizen.carryingAmount() > 0 && WorkDay.isEvening(entity.level());
	}

	@Override
	public boolean canContinueToUse() {
		return !finished;
	}

	@Override
	public void start() {
		finished = false;
		// With no chest to empty there is only what is in hand: straight to the storehouse.
		phase = chest != null ? Phase.TO_CHEST : Phase.TO_STORE;
		load = chest != null ? null : new java.util.ArrayList<>();
		travelled = 0;
		repathIn = 0;
	}

	@Override
	public void stop() {
		deliver();
		entity.getNavigation().stop();
		entity.citizen().setActivity(Activity.IDLE);
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		Settlement settlement = entity.settlement();
		if (embodiment == null || settlement == null || !(entity.level() instanceof ServerLevel level)) {
			finished = true;
			return;
		}
		entity.citizen().setActivity(Activity.HAULING);
		BlockPos target = phase == Phase.TO_CHEST && chest != null ? chest : embodiment.buildSites().deliveryPoint(level, settlement);
		if (entity.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5) <= REACH_SQR) {
			if (phase == Phase.TO_CHEST) {
				load = PersonalChests.empty(level, chest);
				phase = Phase.TO_STORE;
				travelled = 0;
				repathIn = 0;
			} else {
				finished = true;
			}
			return;
		}
		if (++travelled > TRAVEL_TIMEOUT_TICKS) {
			finished = true;
			return;
		}
		if (--repathIn <= 0 && embodiment.takePathSlot()) {
			repathIn = REPATH_INTERVAL_TICKS;
			entity.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.6);
		}
	}

	/**
	 * What is being carried goes into the storehouse and into the stock. Where there is no
	 * storehouse to put it in, it is counted all the same.
	 */
	private void deliver() {
		Settlement settlement = entity.settlement();
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		if (load == null || settlement == null) {
			return;
		}
		// What was still in hand goes in with it: a miner's finds as they are, anything else by count.
		Citizen citizen = entity.citizen();
		if (citizen.carryingAmount() > 0) {
			if (!entity.pack().isEmpty()) {
				load.addAll(entity.pack());
				citizen.dropLoad();
			} else {
				Labor.deliver(settlement, citizen, embodiment == null ? 0 : embodiment.gameTick());
			}
			entity.pack().clear();
		}
		if (embodiment != null && entity.level() instanceof ServerLevel level) {
			embodiment.deposit(level, settlement, load);
		} else {
			int[] worth = PersonalChests.worth(load);
			for (Commodity commodity : COMMODITIES) {
				if (worth[commodity.ordinal()] > 0) {
					settlement.ledger().add(commodity, worth[commodity.ordinal()]);
				}
			}
		}
		load = null;
	}
}
