package com.sunnatbek.villagerciv.mod.entity;

import com.sunnatbek.villagerciv.build.Building;
import com.sunnatbek.villagerciv.mod.EmbodimentController;
import com.sunnatbek.villagerciv.mod.Librarians;
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
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.List;

/** A librarian at its lectern: it stands before it through the day, reading, and now and then turns a page. */
final class StudyGoal extends Goal {
	private static final double AT_LECTERN_SQR = 0.7 * 0.7;
	private static final double NEAR_SQR = 3.0 * 3.0;
	private static final int REPATH_INTERVAL_TICKS = 20;
	private static final int TRAVEL_TIMEOUT_TICKS = 600;
	private static final int PAGE_TICKS = 120;
	private static final double UPSTAIRS_SQR = 10.0 * 10.0;

	private final CitizenEntity entity;
	private BlockPos[] lectern;
	private int travelled;
	private int repathIn;
	private int timer;
	private int waitUntilTick;

	StudyGoal(CitizenEntity entity) {
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
		if (settlement == null || citizen == null || embodiment == null || citizen.job() != JobType.STUDY || !WorkDay.isDay(level)) {
			return false;
		}
		Building library = settlement.building(citizen.jobSite());
		int place = Librarians.lecternOf(settlement, citizen);
		List<BlockPos[]> lecterns = library == null || place < 0 ? List.of() : embodiment.buildSites().lecterns(level, settlement, library);
		int seat = place % com.sunnatbek.villagerciv.build.Blueprint.LIBRARY_SEATS;
		if (seat >= lecterns.size() || place < 0) {
			waitUntilTick = entity.tickCount + 100;
			return false;
		}
		lectern = lecterns.get(seat);
		return true;
	}

	@Override
	public boolean canContinueToUse() {
		return lectern != null && entity.citizen().job() == JobType.STUDY && WorkDay.isDay(entity.level());
	}

	@Override
	public void start() {
		travelled = 0;
		repathIn = 0;
		timer = 0;
	}

	@Override
	public void stop() {
		entity.getNavigation().stop();
		entity.citizen().setActivity(Activity.IDLE);
		lectern = null;
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		Citizen citizen = entity.citizen();
		if (embodiment == null || lectern == null || citizen.job() != JobType.STUDY || !(entity.level() instanceof ServerLevel level)) {
			lectern = null;
			return;
		}
		BlockPos stand = lectern[1];
		double distance = entity.distanceToSqr(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
		boolean near = distance <= NEAR_SQR && entity.getNavigation().isDone() && Math.abs(stand.getY() - entity.getY()) < 1.5;
		if (distance > AT_LECTERN_SQR && !near) {
			citizen.setActivity(Activity.WALKING);
			// Up the ladder to the floor above: taken as climbed once it has got to the building.
			boolean upstairs = stand.getY() - entity.getY() > 2.5 && distance <= UPSTAIRS_SQR + 36.0;
			if (++travelled > TRAVEL_TIMEOUT_TICKS || upstairs) {
				entity.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
				travelled = 0;
			} else if (--repathIn <= 0 && embodiment.takePathSlot()) {
				repathIn = REPATH_INTERVAL_TICKS;
				entity.getNavigation().moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0.6);
			}
			return;
		}
		if (distance > AT_LECTERN_SQR) {
			entity.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
		}
		travelled = 0;
		entity.getNavigation().stop();
		citizen.setActivity(Activity.WORKING);
		double dx = lectern[0].getX() + 0.5 - entity.getX();
		double dz = lectern[0].getZ() + 0.5 - entity.getZ();
		float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
		entity.setYRot(yaw);
		entity.setYBodyRot(yaw);
		entity.getLookControl().setLookAt(lectern[0].getX() + 0.5, lectern[0].getY() + 1.0, lectern[0].getZ() + 0.5);
		if (++timer < PAGE_TICKS) {
			return;
		}
		timer = 0;
		Labor.reportProgress(citizen, embodiment.gameTick());
		level.playSound(null, lectern[0], SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 0.7f, 0.9f + entity.getRandom().nextFloat() * 0.2f);
		level.sendParticles(ParticleTypes.ENCHANT, lectern[0].getX() + 0.5, lectern[0].getY() + 1.6, lectern[0].getZ() + 0.5, 6, 0.3, 0.2, 0.3, 0.5);
	}
}
