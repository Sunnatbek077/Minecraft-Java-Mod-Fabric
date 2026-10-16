package com.sunnatbek.villagerciv.mod.entity;

import com.sunnatbek.villagerciv.mod.EmbodimentController;
import com.sunnatbek.villagerciv.mod.VillagerCivMod;
import com.sunnatbek.villagerciv.mod.WorkDay;
import com.sunnatbek.villagerciv.sim.Activity;
import com.sunnatbek.villagerciv.sim.Castle;
import com.sunnatbek.villagerciv.sim.Citizen;
import com.sunnatbek.villagerciv.sim.JobType;
import com.sunnatbek.villagerciv.sim.Labor;
import com.sunnatbek.villagerciv.sim.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.component.SwingAnimation;

import java.util.EnumSet;
import java.util.Random;

/**
 * One of the castle's brigade at work. It goes up to where the newest course is being laid and
 * works along it, moving on with the work; at the end of the day it comes back down to the town
 * to go home. Up and down it does not climb in sight: the castle stands far above the streets,
 * and a brigade on the stairs would spend the day on them.
 */
final class CastleGoal extends Goal {
	private static final int MOVE_ON_TICKS = 300;
	private static final int SWING_TICKS = 12;
	private static final int LOOK_AGAIN_TICKS = 100;

	private final CitizenEntity entity;
	private final Random random = new Random();
	private BlockPos spot;
	private int timer;
	private int waitUntilTick;

	CastleGoal(CitizenEntity entity) {
		this.entity = entity;
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	private Settlement castleSite() {
		Settlement settlement = entity.settlement();
		Citizen citizen = entity.citizen();
		if (settlement == null || citizen == null || citizen.job() != JobType.BUILD || citizen.jobSite() != Labor.CASTLE
				|| !Castle.isBuilding(settlement) || !WorkDay.isDay(entity.level())) {
			return null;
		}
		return settlement;
	}

	@Override
	public boolean canUse() {
		if (entity.tickCount < waitUntilTick || castleSite() == null) {
			return false;
		}
		spot = pickSpot();
		if (spot == null) {
			waitUntilTick = entity.tickCount + LOOK_AGAIN_TICKS;
		}
		return spot != null;
	}

	@Override
	public boolean canContinueToUse() {
		return castleSite() != null;
	}

	private BlockPos pickSpot() {
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		Settlement settlement = entity.settlement();
		if (embodiment == null || settlement == null || !(entity.level() instanceof ServerLevel level)) {
			return null;
		}
		return embodiment.castles().workSpot(level, settlement, random);
	}

	@Override
	public void start() {
		timer = 0;
		goUp();
	}

	private void goUp() {
		entity.getNavigation().stop();
		entity.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
		entity.citizen().setActivity(Activity.BUILDING);
	}

	@Override
	public void tick() {
		timer++;
		if (timer % SWING_TICKS == 0) {
			entity.getLookControl().setLookAt(spot.getX() + 0.5 + random.nextInt(5) - 2, spot.getY() - 1, spot.getZ() + 0.5 + random.nextInt(5) - 2);
			entity.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		}
		if (timer >= MOVE_ON_TICKS) {
			BlockPos next = pickSpot();
			if (next != null) {
				spot = next;
				goUp();
			}
			timer = 0;
		}
	}

	@Override
	public void stop() {
		Settlement settlement = entity.settlement();
		// Back down to the town for the night, a step or two from the Town Center.
		if (settlement != null && settlement.hasCastle() && entity.getY() > settlement.castleY() - 4) {
			double angle = random.nextDouble() * Math.PI * 2;
			double away = 4 + random.nextDouble() * 10;
			entity.teleportTo(settlement.anchorX() + 0.5 + Math.cos(angle) * away, settlement.anchorY(),
					settlement.anchorZ() + 0.5 + Math.sin(angle) * away);
		}
		entity.getNavigation().stop();
		entity.citizen().setActivity(Activity.IDLE);
		spot = null;
	}
}
