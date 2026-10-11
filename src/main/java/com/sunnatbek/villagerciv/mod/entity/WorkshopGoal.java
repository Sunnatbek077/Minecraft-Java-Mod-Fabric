package com.sunnatbek.villagerciv.mod.entity;

import com.sunnatbek.villagerciv.build.Building;
import com.sunnatbek.villagerciv.mod.EmbodimentController;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.component.SwingAnimation;

import java.util.EnumSet;

/**
 * A tradesman at its bench: the smith at the anvil, the mason at the stonecutter. It walks to
 * its workshop, stands at the bench and works there through the day. What the work is worth is
 * reckoned elsewhere, from the plain fact that someone holds the job; this is what it looks and
 * sounds like.
 */
final class WorkshopGoal extends Goal {
	private static final double BENCH_REACH_SQR = 0.6 * 0.6;
	private static final double NEAR_SQR = 3.0 * 3.0;
	private static final int REPATH_INTERVAL_TICKS = 20;
	private static final int TRAVEL_TIMEOUT_TICKS = 600;
	/** A stroke at the bench every so often, and the ring of it less often than that. */
	private static final int STROKE_TICKS = 25;
	private static final int STROKES_PER_SOUND = 3;

	private final CitizenEntity entity;
	private BlockPos[] bench;
	private boolean finished;
	private int travelled;
	private int repathIn;
	private int timer;
	private int strokes;
	private int waitUntilTick;

	WorkshopGoal(CitizenEntity entity) {
		this.entity = entity;
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	private BlockPos[] findBench() {
		Settlement settlement = entity.settlement();
		Citizen citizen = entity.citizen();
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		if (settlement == null || citizen == null || embodiment == null || citizen.job() == null || citizen.job().workshop() == null
				|| !WorkDay.isDay(entity.level()) || !(entity.level() instanceof ServerLevel level)) {
			return null;
		}
		Building workshop = settlement.building(citizen.jobSite());
		return workshop == null ? null : embodiment.buildSites().workBench(level, settlement, workshop, benchOf(citizen.job()));
	}

	/** The block each trade is worked at. */
	private static String benchOf(JobType job) {
		return switch (job) {
			case SMITH -> "minecraft:anvil";
			case MASON -> "minecraft:stonecutter";
			default -> "minecraft:cartography_table";
		};
	}

	@Override
	public boolean canUse() {
		if (entity.tickCount < waitUntilTick) {
			return false;
		}
		bench = findBench();
		return bench != null;
	}

	@Override
	public boolean canContinueToUse() {
		Citizen citizen = entity.citizen();
		return !finished && citizen.job() != null && citizen.job().workshop() != null && WorkDay.isDay(entity.level());
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
		if (embodiment == null || bench == null || !(entity.level() instanceof ServerLevel level)) {
			finished = true;
			return;
		}
		BlockPos stand = bench[1];
		Citizen citizen = entity.citizen();
		// The job board can take the job away between one tick and the next.
		if (citizen.job() == null || citizen.job().workshop() == null) {
			finished = true;
			return;
		}
		double distance = entity.distanceToSqr(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
		// At the bench, or as near as the walk got it: a tradesman who stops a step short in its
		// own doorway is put in its place rather than left to look idle there.
		boolean near = distance <= NEAR_SQR && entity.getNavigation().isDone();
		if (distance > BENCH_REACH_SQR && !near) {
			citizen.setActivity(Activity.WALKING);
			if (++travelled > TRAVEL_TIMEOUT_TICKS) {
				// It is the settlement's own workshop: if the way in cannot be found, it is taken as found.
				entity.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
				travelled = 0;
			} else if (--repathIn <= 0 && embodiment.takePathSlot()) {
				repathIn = REPATH_INTERVAL_TICKS;
				entity.getNavigation().moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0.6);
			}
			return;
		}
		if (distance > BENCH_REACH_SQR) {
			entity.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
		}
		travelled = 0;
		entity.getNavigation().stop();
		citizen.setActivity(Activity.WORKING);
		// Squarely facing the bench, body and all, not just glancing at it.
		double dx = bench[0].getX() + 0.5 - entity.getX();
		double dz = bench[0].getZ() + 0.5 - entity.getZ();
		float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
		entity.setYRot(yaw);
		entity.setYBodyRot(yaw);
		entity.getLookControl().setLookAt(bench[0].getX() + 0.5, bench[0].getY() + 0.9, bench[0].getZ() + 0.5);
		if (++timer < STROKE_TICKS) {
			return;
		}
		timer = 0;
		entity.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		Labor.reportProgress(citizen, embodiment.gameTick());
		double x = bench[0].getX() + 0.5;
		double y = bench[0].getY() + 1.05;
		double z = bench[0].getZ() + 0.5;
		switch (citizen.job()) {
			case SMITH -> {
				// Hammer on hot iron: a ring and a spray of sparks at every blow.
				level.playSound(null, bench[0], SoundEvents.ANVIL_PLACE, SoundSource.NEUTRAL, 0.3f, 1.3f + entity.getRandom().nextFloat() * 0.3f);
				level.sendParticles(ParticleTypes.CRIT, x, y, z, 6, 0.15, 0.05, 0.15, 0.25);
				level.sendParticles(ParticleTypes.LAVA, x, y, z, 1, 0.1, 0.0, 0.1, 0.0);
			}
			case MASON -> {
				level.playSound(null, bench[0], SoundEvents.STONE_BREAK, SoundSource.NEUTRAL, 0.6f, 0.9f + entity.getRandom().nextFloat() * 0.2f);
				level.sendParticles(ParticleTypes.POOF, x, y - 0.4, z, 3, 0.15, 0.05, 0.15, 0.01);
			}
			default -> {
				if (++strokes % STROKES_PER_SOUND == 0) {
					level.playSound(null, bench[0], SoundEvents.VILLAGER_TRADE, SoundSource.NEUTRAL, 0.6f, 1.0f);
					level.sendParticles(ParticleTypes.HAPPY_VILLAGER, x, y + 0.3, z, 2, 0.2, 0.1, 0.2, 0.0);
				}
			}
		}
	}
}
