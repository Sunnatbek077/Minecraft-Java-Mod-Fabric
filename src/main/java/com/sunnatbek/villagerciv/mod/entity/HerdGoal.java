package com.sunnatbek.villagerciv.mod.entity;

import com.sunnatbek.villagerciv.build.Blueprint;
import com.sunnatbek.villagerciv.build.Building;
import com.sunnatbek.villagerciv.mod.EmbodimentController;
import com.sunnatbek.villagerciv.mod.Pens;
import com.sunnatbek.villagerciv.mod.VillagerCivMod;
import com.sunnatbek.villagerciv.mod.WorkDay;
import com.sunnatbek.villagerciv.sim.Activity;
import com.sunnatbek.villagerciv.sim.Citizen;
import com.sunnatbek.villagerciv.sim.JobType;
import com.sunnatbek.villagerciv.sim.Labor;
import com.sunnatbek.villagerciv.sim.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;

import java.util.EnumSet;
import java.util.List;

/**
 * A herder at its pen. It stands at the gate, feeds the animals over the fence, and every so
 * often does what the pen needs: brings a pair in, sees a young one born, or takes a grown
 * animal for the store.
 */
final class HerdGoal extends Goal {
	private static final double NEAR_SQR = 3.0 * 3.0;
	private static final int REPATH_INTERVAL_TICKS = 20;
	private static final int TRAVEL_TIMEOUT_TICKS = 600;
	/** A handful of feed over the fence every so often, and the pen seen to after so many of those. */
	private static final int FEED_TICKS = 40;
	private static final int FEEDS_PER_ROUND = 25;
	private static final int LOOK_FOR_STRAYS_TICKS = 100;
	private static final double CATCH_SQR = 2.5 * 2.5;
	private static final int CHASE_TIMEOUT_TICKS = 400;

	private final CitizenEntity entity;
	private Building pen;
	private BlockPos stand;
	private boolean finished;
	private int travelled;
	private int repathIn;
	private int timer;
	private int feeds;
	private int waitUntilTick;
	private Animal stray;
	private int chase;
	private int lookForStraysIn;

	HerdGoal(CitizenEntity entity) {
		this.entity = entity;
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		if (entity.tickCount < waitUntilTick || !(entity.level() instanceof ServerLevel level)) {
			return false;
		}
		Settlement settlement = entity.settlement();
		Citizen citizen = entity.citizen();
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		if (settlement == null || citizen == null || embodiment == null || citizen.job() != JobType.HERD || !WorkDay.isDay(level)) {
			return false;
		}
		pen = settlement.building(citizen.jobSite());
		if (pen == null || !Blueprint.isPen(pen.blueprint()) || pen.stage() != Building.Stage.COMPLETE || !embodiment.buildSites().isLoaded(level, pen)) {
			waitUntilTick = entity.tickCount + 100;
			return false;
		}
		stand = embodiment.buildSites().standPoint(level, pen);
		return true;
	}

	@Override
	public boolean canContinueToUse() {
		return !finished && entity.citizen().job() == JobType.HERD && WorkDay.isDay(entity.level());
	}

	@Override
	public void start() {
		finished = false;
		travelled = 0;
		repathIn = 0;
		timer = 0;
	}

	@Override
	public void stop() {
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
		Citizen citizen = entity.citizen();
		if (embodiment == null || settlement == null || pen == null || citizen.job() != JobType.HERD || !(entity.level() instanceof ServerLevel level)) {
			finished = true;
			return;
		}
		// First of all the herd is kept together: anything of its kind loose in the settlement is
		// gone after, caught, and carried back. If the pen is full already, it goes to the store.
		if (stray != null && !stray.isAlive()) {
			stray = null;
		}
		if (stray == null && --lookForStraysIn <= 0) {
			lookForStraysIn = LOOK_FOR_STRAYS_TICKS;
			List<Animal> loose = Pens.strays(level, settlement, pen);
			for (Animal animal : loose) {
				if (stray == null || animal.distanceToSqr(entity) < stray.distanceToSqr(entity)) {
					stray = animal;
				}
			}
			chase = 0;
		}
		if (stray != null) {
			citizen.setActivity(Activity.WALKING);
			if (entity.distanceToSqr(stray) <= CATCH_SQR || ++chase > CHASE_TIMEOUT_TICKS) {
				entity.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
				if (Pens.isFull(level, pen) && !stray.isBaby()) {
					Pens.take(level, stray);
					embodiment.deposit(level, settlement, Pens.yieldOf(pen.blueprint()));
				} else {
					Pens.bringIn(level, pen, stray);
				}
				Labor.reportProgress(citizen, embodiment.gameTick());
				stray = null;
				lookForStraysIn = 0;
			} else if (--repathIn <= 0 && embodiment.takePathSlot()) {
				repathIn = REPATH_INTERVAL_TICKS;
				entity.getNavigation().moveTo(stray, 0.75);
			}
			return;
		}
		if (entity.distanceToSqr(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5) > NEAR_SQR) {
			citizen.setActivity(Activity.WALKING);
			if (++travelled > TRAVEL_TIMEOUT_TICKS) {
				// It is the settlement's own pen: if the way to it cannot be found, it is taken as found.
				entity.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
				travelled = 0;
			} else if (--repathIn <= 0 && embodiment.takePathSlot()) {
				repathIn = REPATH_INTERVAL_TICKS;
				entity.getNavigation().moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0.6);
			}
			return;
		}
		travelled = 0;
		entity.getNavigation().stop();
		citizen.setActivity(Activity.WORKING);
		entity.getLookControl().setLookAt(pen.x() + pen.sizeX() / 2.0, pen.y() + 1.5, pen.z() + pen.sizeZ() / 2.0);
		if (++timer < FEED_TICKS) {
			return;
		}
		timer = 0;
		entity.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		Labor.reportProgress(citizen, embodiment.gameTick());
		List<Animal> animals = Pens.animals(level, pen);
		if (!animals.isEmpty()) {
			Animal fed = animals.get(entity.getRandom().nextInt(animals.size()));
			level.sendParticles(ParticleTypes.HAPPY_VILLAGER, fed.getX(), fed.getY() + fed.getBbHeight(), fed.getZ(), 2, 0.2, 0.1, 0.2, 0.0);
		}
		// An empty pen is stocked at once; after that the pen is seen to once in a while.
		if (++feeds < FEEDS_PER_ROUND && animals.size() >= 2) {
			return;
		}
		feeds = 0;
		if (Pens.tend(level, pen) == Pens.Tended.TAKEN) {
			embodiment.deposit(level, settlement, Pens.yieldOf(pen.blueprint()));
		}
	}
}
