package com.sunnatbek.villagerciv.mod.entity;

import com.sunnatbek.villagerciv.mod.WorkDay;
import com.sunnatbek.villagerciv.mod.EmbodimentController;
import com.sunnatbek.villagerciv.sim.Citizen;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Light follow behaviour for a travelling expedition. Every member has its own place in a loose
 * formation around the player, so the group spreads out instead of piling onto one block. A
 * member left far behind, or with no way to walk there, catches up by teleport, so difficult
 * terrain never costs long path searches.
 */
final class FollowExpeditionGoal extends Goal {
	/** Leave the formation place only when it has moved this far away, so small steps do not shuffle the group. */
	private static final double START_DISTANCE_SQR = 3.5 * 3.5;
	private static final double ARRIVED_DISTANCE_SQR = 1.0 * 1.0;
	private static final double HURRY_DISTANCE_SQR = 12.0 * 12.0;
	private static final double TELEPORT_DISTANCE_SQR = 48.0 * 48.0;
	/** Closer than this to the player a missing path is not worth a teleport: the member just waits. */
	private static final double CLOSE_ENOUGH_DISTANCE_SQR = 8.0 * 8.0;
	private static final int REPATH_INTERVAL_TICKS = 10;
	/** Repath attempts without any route (about 3 seconds) before giving up on walking. */
	private static final int MAX_FAILED_REPATHS = 6;
	private static final double GOLDEN_ANGLE = 2.399963;

	private final CitizenEntity citizen;
	private ServerPlayer target;
	private int repathIn;
	private int failedRepaths;
	private boolean stuckNearby;
	private int waitUntilTick;

	FollowExpeditionGoal(CitizenEntity citizen) {
		this.citizen = citizen;
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		target = citizen.followTarget();
		return target != null && citizen.tickCount >= waitUntilTick && distanceToPlaceSqr() > START_DISTANCE_SQR;
	}

	@Override
	public boolean canContinueToUse() {
		target = citizen.followTarget();
		return target != null && !stuckNearby && distanceToPlaceSqr() > ARRIVED_DISTANCE_SQR;
	}

	@Override
	public void start() {
		repathIn = 0;
		failedRepaths = 0;
		stuckNearby = false;
	}

	@Override
	public void stop() {
		target = null;
		citizen.getNavigation().stop();
	}

	@Override
	public void tick() {
		citizen.getLookControl().setLookAt(target, 10.0f, citizen.getMaxHeadXRot());
		if (--repathIn > 0) {
			return;
		}
		repathIn = adjustedTickDelay(REPATH_INTERVAL_TICKS);
		double distanceSqr = citizen.distanceToSqr(target);
		// Too far behind, or no way to walk there (a cliff, a shaft, a bridge in the air): catch up directly.
		if (distanceSqr > TELEPORT_DISTANCE_SQR || failedRepaths >= MAX_FAILED_REPATHS) {
			Vec3 spot = EmbodimentController.findSpotNear(target);
			if (spot != null) {
				citizen.getNavigation().stop();
				citizen.teleportTo(spot.x, spot.y, spot.z);
				failedRepaths = 0;
			}
			return;
		}

		Vec3 place = place();
		citizen.getNavigation().moveTo(place.x, place.y, place.z, distanceSqr > HURRY_DISTANCE_SQR ? 0.9 : 0.6);
		// moveTo also succeeds with a path that merely ends as close as possible; that does not count.
		Path path = citizen.getNavigation().getPath();
		if (path != null && path.canReach()) {
			failedRepaths = 0;
		} else if (distanceSqr > CLOSE_ENOUGH_DISTANCE_SQR) {
			failedRepaths++;
		} else if (citizen.getNavigation().isDone()) {
			// The place itself is unreachable (inside a wall, say) but the player is near: good enough.
			stuckNearby = true;
			// Do not search again right away: an unreachable place would cost a path search every tick.
			waitUntilTick = citizen.tickCount + 60;
		}
	}

	private double distanceToPlaceSqr() {
		Vec3 place = place();
		return citizen.distanceToSqr(place.x, place.y, place.z);
	}

	/**
	 * This member's place: a point on a spiral around the player. The directions are fixed in the
	 * world rather than tied to where the player looks, so turning around moves nobody.
	 */
	private Vec3 place() {
		int index = 0;
		for (Citizen member : citizen.group().members()) {
			if (member == citizen.citizen()) {
				break;
			}
			index++;
		}
		double angle = index * GOLDEN_ANGLE;
		// After dark the group huddles closer to the player.
		double radius = (2.2 + 0.45 * index) * (!WorkDay.isDay(citizen.level()) ? 0.6 : 1.0);
		return new Vec3(target.getX() + Math.cos(angle) * radius, target.getY(), target.getZ() + Math.sin(angle) * radius);
	}
}
