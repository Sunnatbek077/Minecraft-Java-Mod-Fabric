package com.sunnatbek.villagerciv.mod.entity;

import com.sunnatbek.villagerciv.mod.EmbodimentController;
import com.sunnatbek.villagerciv.mod.PersonalChests;
import com.sunnatbek.villagerciv.mod.VillagerCivMod;
import com.sunnatbek.villagerciv.mod.WorkDay;
import com.sunnatbek.villagerciv.mod.WorkSites;
import com.sunnatbek.villagerciv.sim.Activity;
import com.sunnatbek.villagerciv.sim.Citizen;
import com.sunnatbek.villagerciv.sim.JobType;
import com.sunnatbek.villagerciv.sim.Labor;
import com.sunnatbek.villagerciv.sim.Settlement;
import com.sunnatbek.villagerciv.sim.ToolKind;
import com.sunnatbek.villagerciv.sim.Tools;
import com.sunnatbek.villagerciv.sim.WorkSite;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * A settled citizen doing its job where the player can see it: walk to the site, do one thing,
 * and when the load is full carry it home to its chest (or to the Town Center, having none). A small state machine; what to do next
 * is decided by {@link WorkSites}, and whether this citizen has the job at all by the job board.
 */
final class WorkGoal extends Goal {
	private enum Phase {
		TO_WORK,
		WORKING,
		TO_STOCKPILE
	}

	private static final double REACH_SQR = 2.5 * 2.5;
	private static final double CLEAR_REACH_SQR = 3.5 * 3.5;
	private static final double STOCKPILE_REACH_SQR = 4.0 * 4.0;
	private static final int REPATH_INTERVAL_TICKS = 20;
	/** How many steps of a mine stair a miner makes for at a time, and how far from its mouth a mine reaches. */
	private static final int STAIR_LEG = 8;
	private static final int MINE_SPAN = 40;
	private static final int HOIST_FROM = 24;
	private static final int HOIST_RIDE = 16;
	/**
	 * Give up on a spot that cannot be reached within this long. A minute: the walk down a deep
	 * mine and back up with a load is a long one, and must not be mistaken for being stuck.
	 */
	private static final int TRAVEL_TIMEOUT_TICKS = 1_200;
	/** Look again this long after finding nothing to do. */
	private static final int IDLE_WAIT_TICKS = 100;

	private final CitizenEntity entity;
	private Phase phase;
	private WorkSites.Action action;
	private int timer;
	private int travelled;
	private int repathIn;
	private int waitUntilTick;
	private boolean finished;
	/** Whether this trip has already been restarted from the Town Center once. */
	private boolean unstuck;

	WorkGoal(CitizenEntity entity) {
		this.entity = entity;
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		return entity.tickCount >= waitUntilTick && hasWork();
	}

	@Override
	public boolean canContinueToUse() {
		return !finished && hasWork();
	}

	/** Working hours, a job, and a site to do it at. */
	private boolean hasWork() {
		Settlement settlement = entity.settlement();
		Citizen citizen = entity.citizen();
		return settlement != null && citizen.job() != null && citizen.job().hasWorkSite() && WorkDay.isDay(entity.level())
				&& settlement.site(citizen.jobSite()) != null;
	}

	@Override
	public void start() {
		finished = false;
		action = null;
		// A load picked up before an interruption (night, a fright) is brought home first.
		phase = entity.citizen().carryingAmount() > 0 ? Phase.TO_STOCKPILE : Phase.TO_WORK;
		travelled = 0;
		repathIn = 0;
	}

	@Override
	public void stop() {
		// The end of a shift finds a miner far underground. The long climb is not acted out: it
		// comes up at the pit head, and walks home (or to the storehouse) from there.
		WorkSite mine = ownMine();
		if (mine != null && !WorkDay.isDay(entity.level()) && stepOf(mine, entity.getY()) > STAIR_LEG
				&& Math.abs(entity.getX() - mine.x()) <= MINE_SPAN && Math.abs(entity.getZ() - mine.z()) <= MINE_SPAN) {
			BlockPos top = WorkSites.stairStand(mine, 0);
			entity.teleportTo(top.getX() + 0.5, top.getY(), top.getZ() + 0.5);
		}
		release();
		entity.getNavigation().stop();
		// "Waiting" outlives the goal: it is why the citizen stands about until it looks again.
		if (entity.citizen().activity() != Activity.WAITING) {
			entity.citizen().setActivity(Activity.IDLE);
		}
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		if (embodiment == null || !(entity.level() instanceof ServerLevel level)) {
			finished = true;
			return;
		}
		Settlement settlement = entity.settlement();
		Citizen citizen = entity.citizen();
		// The job board can take the job away between one tick and the next.
		if (settlement == null || citizen.job() == null || !citizen.job().hasWorkSite() || settlement.site(citizen.jobSite()) == null) {
			finished = true;
			return;
		}
		WorkSite site = settlement.site(citizen.jobSite());

		citizen.setActivity(switch (phase) {
			case TO_WORK -> Activity.WALKING;
			case WORKING -> Activity.WORKING;
			case TO_STOCKPILE -> Activity.HAULING;
		});
		switch (phase) {
			case TO_WORK -> {
				if (action == null) {
					action = WorkSites.next(level, site, embodiment.claimedBlocks(), entity.getId() * 7);
					if (action == null) {
						// Nothing to do right now. Bring home what there is, then look again later.
						if (citizen.carryingAmount() > 0) {
							phase = Phase.TO_STOCKPILE;
						} else {
							pause();
							citizen.setActivity(Activity.WAITING);
						}
						return;
					}
					embodiment.claimedBlocks().add(action.pos().asLong());
					travelled = 0;
					repathIn = 0;
				}
				BlockPos stand = action.stand();
				// A blocked stair is cleared from whichever side the miner is on: one caught below
				// it cannot get to the step above, but is within arm's reach of the blockage.
				boolean atBlockage = action.kind() == WorkSites.Kind.CLEAR_STEP
						&& entity.distanceToSqr(action.pos().getX() + 0.5, action.pos().getY() + 1, action.pos().getZ() + 0.5) <= CLEAR_REACH_SQR;
				if (atBlockage || entity.distanceToSqr(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5) <= REACH_SQR) {
					entity.getNavigation().stop();
					timer = action.kind().ticks() * 100 / Labor.efficiencyPercent(settlement, citizen, citizen.job());
					// Moving earth is shovel work, whatever the job.
					boolean earthwork = action.kind() == WorkSites.Kind.LEVEL || action.kind() == WorkSites.Kind.WATER;
					if (earthwork && Tools.equipped(settlement, citizen, ToolKind.SHOVEL)) {
						timer = timer * 100 / Tools.SHOVEL_SPEED_PERCENT;
					}
					phase = Phase.WORKING;
				} else if (!walkTo(embodiment, stand)) {
					release();
					pause();
				}
			}
			case WORKING -> {
				BlockPos pos = action.pos();
				entity.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
				if (--timer > 0) {
					return;
				}
				WorkSites.Yield yield = WorkSites.perform(level, site, action);
				release();
				if (yield.amount() > 0) {
					citizen.carry(yield.commodity(), yield.amount());
					entity.pack().addAll(yield.items());
				}
				Labor.reportProgress(citizen, embodiment.gameTick());
				// A miner's way up is a longer one, even with the hoist: it takes half as much again.
				int load = citizen.job() == JobType.MINE ? Labor.CARRY_CAPACITY * 3 / 2 : Labor.CARRY_CAPACITY;
				phase = citizen.carryingAmount() >= load ? Phase.TO_STOCKPILE : Phase.TO_WORK;
				travelled = 0;
				repathIn = 0;
			}
			case TO_STOCKPILE -> {
				// A load goes into the chest by the citizen's own bed; one who has no chest
				// brings it to the Town Center.
				BlockPos chest = embodiment.buildSites().chestOf(level, settlement, citizen.home(), citizen.bed());
				BlockPos stockpile = chest != null ? chest : embodiment.buildSites().deliveryPoint(level, settlement);
				if (entity.distanceToSqr(stockpile.getX() + 0.5, stockpile.getY(), stockpile.getZ() + 0.5) <= STOCKPILE_REACH_SQR) {
					if (chest != null && citizen.carrying() != null && citizen.carryingAmount() > 0) {
						// Set down at home, the load is not the settlement's yet: it is counted
						// when it reaches the storehouse. Only what the chest has no room for is
						// counted here and now.
						// A miner's finds go in as what they are; anything else as the plain goods it is.
						int left;
						if (entity.pack().isEmpty()) {
							left = PersonalChests.stock(level, chest, citizen.carrying(), citizen.carryingAmount());
						} else {
							// What the chest has no room for is not left standing in the doorway, nor
							// thrown away: it is taken as carried straight on to the storehouse.
							if (!PersonalChests.put(level, chest, entity.pack())) {
								embodiment.deposit(level, settlement, entity.pack());
							}
							left = 0;
						}
						citizen.carry(citizen.carrying(), left - citizen.carryingAmount());
					}
					if (chest == null && !entity.pack().isEmpty()) {
						// No chest of its own: what it found goes into the storehouse as it is,
						// and is counted there, item by item, instead of by the tally in hand.
						embodiment.deposit(level, settlement, entity.pack());
						citizen.dropLoad();
					}
					entity.pack().clear();
					Labor.deliver(settlement, citizen, embodiment.gameTick());
					phase = Phase.TO_WORK;
					travelled = 0;
					repathIn = 0;
				} else if (!walkTo(embodiment, stockpile)) {
					// Cannot get there now; keep the load and try again after a rest.
					pause();
				}
			}
		}
	}

	/** Keeps walking towards a spot. Returns false once that has taken too long. */
	private boolean walkTo(EmbodimentController embodiment, BlockPos target) {
		if (++travelled > TRAVEL_TIMEOUT_TICKS) {
			// A miner who cannot find the way up from the depths is brought to the pit head
			// rather than left below with a full load for good.
			WorkSite mine = ownMine();
			if (mine != null && stepOf(mine, entity.getY()) > STAIR_LEG) {
				BlockPos top = WorkSites.stairStand(mine, 0);
				entity.teleportTo(top.getX() + 0.5, top.getY(), top.getZ() + 0.5);
				travelled = 0;
				repathIn = 0;
				return true;
			}
			// Anyone else who cannot get where it is going in a minute is stuck somewhere: in a
			// pit, behind a fence, at a door that will not open. It is set down by the Town
			// Center, from where every street leads on, and tries again.
			Settlement settlement = entity.settlement();
			if (settlement != null && !unstuck) {
				unstuck = true;
				entity.teleportTo(settlement.anchorX() + 2.5, settlement.anchorY() + 1, settlement.anchorZ() + 0.5);
				travelled = 0;
				repathIn = 0;
				return true;
			}
			unstuck = false;
			return false;
		}
		// Path searches are the costly part of walking: they are rationed across all citizens.
		if (--repathIn <= 0 && embodiment.takePathSlot()) {
			repathIn = REPATH_INTERVAL_TICKS;
			BlockPos next = viaStair(target);
			entity.getNavigation().moveTo(next.getX() + 0.5, next.getY(), next.getZ() + 0.5, 0.6);
		}
		return true;
	}

	/** The mine this citizen works, if it is a miner and the mine's level is known. */
	private WorkSite ownMine() {
		Settlement settlement = entity.settlement();
		Citizen citizen = entity.citizen();
		WorkSite site = settlement == null || citizen.job() != JobType.MINE ? null : settlement.site(citizen.jobSite());
		return site != null && site.y() != WorkSite.Y_UNKNOWN ? site : null;
	}

	/** Which step of the stair is at this height; 0 or less at and above the entrance. */
	private static int stepOf(WorkSite mine, double y) {
		return mine.y() - (int) Math.floor(y) + 1;
	}

	/**
	 * Where to head for next on the way to a place. For everyone but a miner, and for a miner
	 * above ground, that is the place itself. But a deep mine winds down a long way under one
	 * spot, with galleries off it, and a path searched for straight from the bottom to a house
	 * above ends in whichever gallery happens to lie nearest beneath that house. So a miner goes
	 * up and down its stair a short leg at a time, from one step to another a few steps on.
	 */
	private BlockPos viaStair(BlockPos target) {
		WorkSite mine = ownMine();
		if (mine == null || Math.abs(entity.getX() - mine.x()) > MINE_SPAN || Math.abs(entity.getZ() - mine.z()) > MINE_SPAN) {
			return target;
		}
		int here = Math.max(0, stepOf(mine, entity.getY()));
		boolean targetInMine = Math.abs(target.getX() - mine.x()) <= MINE_SPAN && Math.abs(target.getZ() - mine.z()) <= MINE_SPAN
				&& target.getY() < mine.y() - 2;
		int there = targetInMine ? Math.max(0, stepOf(mine, target.getY())) : 0;
		if (Math.abs(there - here) <= STAIR_LEG) {
			return target;
		}
		// Most of a long way up or down is ridden, not walked: the mine has a hoist. A hundred
		// steps on foot each way would leave no time in the day for mining.
		if (Math.abs(there - here) > HOIST_FROM) {
			BlockPos stop = WorkSites.stairStand(mine, here + (there > here ? HOIST_RIDE : -HOIST_RIDE));
			entity.teleportTo(stop.getX() + 0.5, stop.getY(), stop.getZ() + 0.5);
			return stop;
		}
		return WorkSites.stairStand(mine, here + (there > here ? STAIR_LEG : -STAIR_LEG));
	}

	private void pause() {
		finished = true;
		waitUntilTick = entity.tickCount + IDLE_WAIT_TICKS;
	}

	private void release() {
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		if (action != null && embodiment != null) {
			embodiment.claimedBlocks().remove(action.pos().asLong());
		}
		action = null;
	}
}
