package com.sunnatbek.villagerciv.mod.entity;

import com.sunnatbek.villagerciv.mod.EmbodimentController;
import com.sunnatbek.villagerciv.mod.VillagerCivMod;
import com.sunnatbek.villagerciv.sim.Activity;
import com.sunnatbek.villagerciv.sim.Citizen;
import com.sunnatbek.villagerciv.sim.Settlement;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;
import java.util.List;

/**
 * A guard on patrol inside the settlement. It walks from one part of the place to another, day
 * and night, and stops a moment at each. The settlement knows of every monster on its ground,
 * wherever it came in, and a patrol goes for the nearest: one with a sword closes with it, one
 * with a crossbow comes within sight and shoots.
 */
final class PatrolGoal extends Goal {
	private static final int REPATH_INTERVAL_TICKS = 20;
	private static final int LEG_TIMEOUT_TICKS = 500;
	private static final double ARRIVED_SQR = 3.0 * 3.0;
	private static final int LOOK_FOR_TROUBLE_TICKS = 20;
	/** How near a crossbow comes before it shoots, and how it shoots: as from a tower. */
	private static final double SHOOT_RANGE_SQR = 14.0 * 14.0;
	private static final int RELOAD_TICKS = 15;
	private static final int DRAW_TICKS = 25;
	private static final int AIM_TICKS = 8;

	private final CitizenEntity entity;
	private double goalX;
	private double goalZ;
	private boolean walking;
	private int legTicks;
	private int pause;
	private int repathIn;
	private int lookIn;
	private Monster target;
	private int reload;
	private int draw;
	private int aim;

	PatrolGoal(CitizenEntity entity) {
		this.entity = entity;
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	private boolean onPatrol() {
		Citizen citizen = entity.citizen();
		return citizen != null && citizen.patrol() != Citizen.Patrol.NONE && entity.settlement() != null
				&& VillagerCivMod.embodiment() != null && entity.level() instanceof ServerLevel;
	}

	@Override
	public boolean canUse() {
		return onPatrol();
	}

	@Override
	public boolean canContinueToUse() {
		return onPatrol();
	}

	@Override
	public void start() {
		walking = false;
		pause = 0;
		lookIn = 0;
	}

	@Override
	public void stop() {
		entity.getNavigation().stop();
		lower();
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
			return;
		}
		Citizen citizen = entity.citizen();
		citizen.setActivity(Activity.ON_WATCH);
		boolean archer = citizen.patrol() == Citizen.Patrol.CROSSBOW;
		if (target != null && !target.isAlive()) {
			if (System.getProperty("villagerciv.autotest") != null) {
				VillagerCivMod.LOGGER.info("AUTOTEST patrol {} ({}) saw the end of a {} at {}", citizen.name(), citizen.patrol(),
						target.getType().toShortString(), target.blockPosition().toShortString());
			}
			lower();
		}
		// Whatever it has itself caught sight of comes first, however many others are after it.
		if (target == null && entity.getTarget() instanceof Monster seen && seen.isAlive() && (archer || !(seen instanceof Creeper))) {
			target = seen;
			legTicks = 0;
		}
		if (target == null && --lookIn <= 0) {
			lookIn = LOOK_FOR_TROUBLE_TICKS;
			legTicks = 0;
			// A sword leaves creepers to the crossbows.
			target = embodiment.intruder(level, settlement, entity, !archer);
			if (target != null) {
				embodiment.answer(target, entity);
			}
		}
		if (target != null) {
			if (--lookIn <= 0) {
				lookIn = LOOK_FOR_TROUBLE_TICKS;
				embodiment.answer(target, entity);
			}
			if (archer) {
				shootAt(level, embodiment);
			} else {
				// Its sword arm does the rest: with something to fight, a defender closes with it.
				if (entity.getTarget() != target) {
					entity.setTarget(target);
				}
				if (++legTicks > LEG_TIMEOUT_TICKS) {
					// Been after it a long while and it still stands: it cannot be got at.
					legTicks = 0;
					embodiment.outOfReach(target);
					lower();
				}
			}
			return;
		}
		walkTheRounds(level, settlement, embodiment);
	}

	private void walkTheRounds(ServerLevel level, Settlement settlement, EmbodimentController embodiment) {
		if (pause > 0) {
			pause--;
			return;
		}
		if (!walking) {
			int[] bounds = settlement.groundBounds();
			goalX = bounds[0] + entity.getRandom().nextInt(Math.max(1, bounds[2] - bounds[0] + 1)) + 0.5;
			goalZ = bounds[1] + entity.getRandom().nextInt(Math.max(1, bounds[3] - bounds[1] + 1)) + 0.5;
			walking = true;
			legTicks = 0;
			repathIn = 0;
		}
		double dx = goalX - entity.getX();
		double dz = goalZ - entity.getZ();
		if (dx * dx + dz * dz <= ARRIVED_SQR || ++legTicks > LEG_TIMEOUT_TICKS) {
			// There, or as near as the way went: a look round, then on to somewhere else.
			entity.getNavigation().stop();
			walking = false;
			pause = 40 + entity.getRandom().nextInt(80);
			return;
		}
		if (--repathIn <= 0 && embodiment.takePathSlot()) {
			repathIn = REPATH_INTERVAL_TICKS;
			int y = com.sunnatbek.villagerciv.mod.Heights.top(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(goalX), (int) Math.floor(goalZ));
			entity.getNavigation().moveTo(goalX, y, goalZ, 0.55);
		}
	}

	private void shootAt(ServerLevel level, EmbodimentController embodiment) {
		boolean inSight = entity.distanceToSqr(target) <= SHOOT_RANGE_SQR && entity.hasLineOfSight(target);
		if (!inSight) {
			lowerBow();
			if (--repathIn <= 0 && embodiment.takePathSlot()) {
				repathIn = REPATH_INTERVAL_TICKS;
				entity.getNavigation().moveTo(target, 0.8);
			}
			if (++legTicks > LEG_TIMEOUT_TICKS) {
				// Cannot get a sight of it: something else, or back to the rounds.
				legTicks = 0;
				embodiment.outOfReach(target);
				lower();
			}
			return;
		}
		legTicks = 0;
		entity.getNavigation().stop();
		entity.getLookControl().setLookAt(target, 30.0f, 30.0f);
		if (reload > 0) {
			reload--;
			return;
		}
		if (draw == 0 && aim == 0) {
			// The crossbow is drawn back, which is what an onlooker sees as loading.
			entity.startUsingItem(InteractionHand.MAIN_HAND);
			draw = DRAW_TICKS;
			aim = AIM_TICKS;
			return;
		}
		if (draw > 0) {
			if (--draw == 0) {
				entity.stopUsingItem();
				entity.getMainHandItem().set(DataComponents.CHARGED_PROJECTILES,
						ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.ARROW))));
				entity.playSound(SoundEvents.CROSSBOW_LOADING_END.value(), 1.0f, 1.0f);
			}
			return;
		}
		if (--aim > 0) {
			return;
		}
		Arrow arrow = new Arrow(level, entity, new ItemStack(Items.ARROW), new ItemStack(Items.CROSSBOW));
		arrow.setPos(entity.getX(), entity.getEyeY() - 0.1, entity.getZ());
		double dx = target.getX() - entity.getX();
		double dz = target.getZ() - entity.getZ();
		double dy = target.getY(0.5) - arrow.getY();
		// Aimed a little high, as an arrow drops on the way.
		arrow.shoot(dx, dy + Math.sqrt(dx * dx + dz * dz) * 0.08, dz, 2.4f, 2.0f);
		arrow.setBaseDamage(4.0);
		arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
		level.addFreshEntity(arrow);
		entity.playSound(SoundEvents.CROSSBOW_SHOOT, 1.0f, 1.0f);
		lowerBow();
		reload = RELOAD_TICKS;
		if (target instanceof Creeper && !target.isAlive()) {
			target = null;
		}
	}

	private void lowerBow() {
		if (entity.isUsingItem()) {
			entity.stopUsingItem();
		}
		entity.getMainHandItem().remove(DataComponents.CHARGED_PROJECTILES);
		draw = 0;
		aim = 0;
	}

	/** Lets go of whatever was being fought. */
	private void lower() {
		lowerBow();
		if (target != null && entity.getTarget() == target) {
			entity.setTarget(null);
		}
		target = null;
		reload = 0;
	}
}
