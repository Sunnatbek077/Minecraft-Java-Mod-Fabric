package com.sunnatbek.villagerciv.mod.entity;

import com.sunnatbek.villagerciv.build.Building;
import com.sunnatbek.villagerciv.build.Fortification;
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
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.List;

/**
 * A guard's post on the wall: a watchtower's platform, which holds up to three, or the railed
 * platform over a gate. The guard walks to the foot of its post, goes up, and stays there day and night with a
 * crossbow, shooting at any monster it can see. Neither has a stair a villager could find its
 * way up, so the last step up is simply made; so is the first step down when the guard is stood
 * down.
 */
final class TowerPostGoal extends Goal {
	/** The platform of the watchtower design: its floor is the sixth layer, one in from the corner. */
	private static final int PLATFORM_HEIGHT = 6;
	/** The gate's platform is the floor over its passage, one layer lower. */
	private static final int GATE_PLATFORM_HEIGHT = 5;
	private static final double FOOT_REACH_SQR = 6.0 * 6.0;
	private static final int REPATH_INTERVAL_TICKS = 20;
	private static final int TRAVEL_TIMEOUT_TICKS = 600;
	private static final double SHOOT_RANGE = 24.0;
	/** A pause after a shot, how often an idle guard looks about, and the time a shot takes: drawing, then aiming. */
	private static final int RELOAD_TICKS = 15;
	private static final int LOOK_TICKS = 10;
	private static final int DRAW_TICKS = 25;
	private static final int AIM_TICKS = 8;

	private final CitizenEntity entity;
	private Building tower;
	private int travelled;
	private int repathIn;
	private int reload;
	private int draw;
	private int aim;
	private Monster target;

	TowerPostGoal(CitizenEntity entity) {
		this.entity = entity;
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	private Building post() {
		Settlement settlement = entity.settlement();
		Citizen citizen = entity.citizen();
		if (settlement == null || citizen == null || citizen.post() == Citizen.NO_POST) {
			return null;
		}
		Building building = settlement.building(citizen.post());
		return building != null && building.y() != Building.Y_UNKNOWN && building.stage() == Building.Stage.COMPLETE ? building : null;
	}

	@Override
	public boolean canUse() {
		tower = post();
		return tower != null;
	}

	@Override
	public boolean canContinueToUse() {
		return post() == tower && tower != null;
	}

	@Override
	public void start() {
		travelled = 0;
		repathIn = 0;
		reload = RELOAD_TICKS;
	}

	@Override
	public void stop() {
		// Stood down: back to the ground on the settlement's side of the tower.
		if (tower != null && onPlatform()) {
			Settlement settlement = entity.settlement();
			double cx = tower.x() + tower.sizeX() / 2.0;
			double cz = tower.z() + tower.sizeZ() / 2.0;
			double dx = settlement == null ? 0 : Math.signum(settlement.anchorX() + 0.5 - cx);
			double dz = settlement == null ? 1 : Math.signum(settlement.anchorZ() + 0.5 - cz);
			entity.teleportTo(cx + dx * 4, tower.y() + 1, cz + dz * 4);
		}
		lower();
		entity.getNavigation().stop();
		tower = null;
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	/** Where on a watchtower's platform each of its guards stands: apart, and clear of the lantern in the middle. */
	private static final double[][] TOWER_PLACES = {{1.5, 1.5}, {3.5, 3.5}, {1.5, 3.5}};

	private boolean onGate() {
		return tower.blueprint().equals(Fortification.GATE);
	}

	private double platformX() {
		return onGate() ? tower.x() + tower.sizeX() / 2.0 : tower.x() + TOWER_PLACES[place()][0];
	}

	private double platformZ() {
		return onGate() ? tower.z() + tower.sizeZ() / 2.0 : tower.z() + TOWER_PLACES[place()][1];
	}

	/** Both a tower and a gate have a railed platform under a roof, at a set height in their design. */
	private double platformY() {
		return tower.y() + (onGate() ? GATE_PLATFORM_HEIGHT : PLATFORM_HEIGHT);
	}

	private int place() {
		return Math.floorMod(entity.citizen().postPlace(), TOWER_PLACES.length);
	}

	private boolean onPlatform() {
		return Math.abs(entity.getY() - platformY()) < 1.5
				&& entity.getX() > tower.x() && entity.getX() < tower.x() + tower.sizeX()
				&& entity.getZ() > tower.z() && entity.getZ() < tower.z() + tower.sizeZ();
	}

	@Override
	public void tick() {
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		if (embodiment == null || !(entity.level() instanceof ServerLevel level)) {
			return;
		}
		entity.citizen().setActivity(Activity.ON_WATCH);
		if (!onPlatform()) {
			double footX = tower.x() + tower.sizeX() / 2.0;
			double footZ = tower.z() + tower.sizeZ() / 2.0;
			double dx = entity.getX() - footX;
			double dz = entity.getZ() - footZ;
			if (dx * dx + dz * dz <= FOOT_REACH_SQR || ++travelled > TRAVEL_TIMEOUT_TICKS) {
				entity.getNavigation().stop();
				entity.teleportTo(platformX(), platformY(), platformZ());
				travelled = 0;
			} else if (--repathIn <= 0 && embodiment.takePathSlot()) {
				repathIn = REPATH_INTERVAL_TICKS;
				entity.getNavigation().moveTo(footX, tower.y() + 1, footZ, 0.7);
			}
			return;
		}
		entity.getNavigation().stop();
		if (target != null && (!target.isAlive() || !entity.hasLineOfSight(target))) {
			lower();
		}
		if (target == null) {
			// Between shots: look for something to shoot at.
			if (--reload > 0) {
				return;
			}
			reload = LOOK_TICKS;
			target = nearestVisibleMonster(level);
			if (target == null) {
				return;
			}
			// Seen one. The crossbow is drawn back, which is what an onlooker sees as loading.
			entity.startUsingItem(InteractionHand.MAIN_HAND);
			draw = DRAW_TICKS;
			aim = AIM_TICKS;
		}
		entity.getLookControl().setLookAt(target, 30.0f, 30.0f);
		if (draw > 0) {
			if (--draw == 0) {
				entity.stopUsingItem();
				entity.getMainHandItem().set(DataComponents.CHARGED_PROJECTILES,
						ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.ARROW))));
				entity.playSound(SoundEvents.CROSSBOW_LOADING_END.value(), 1.0f, 1.0f);
			}
			return;
		}
		// Loaded and levelled: a moment to aim, then the shot.
		if (--aim > 0) {
			return;
		}
		Arrow arrow = new Arrow(level, entity, new ItemStack(Items.ARROW), new ItemStack(Items.CROSSBOW));
		double dx = target.getX() - entity.getX();
		double dz = target.getZ() - entity.getZ();
		double dy = target.getY(0.5) - arrow.getY();
		double flat = Math.sqrt(dx * dx + dz * dz);
		// Aimed a little high, as an arrow drops on the way.
		arrow.shoot(dx, dy + flat * 0.12, dz, 2.2f, 4.0f);
		arrow.setBaseDamage(4.0);
		arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
		level.addFreshEntity(arrow);
		entity.playSound(SoundEvents.CROSSBOW_SHOOT, 1.0f, 1.0f);
		lower();
		reload = RELOAD_TICKS;
	}

	/** Lets the crossbow down: nothing drawn, nothing loaded, nobody in the sights. */
	private void lower() {
		if (entity.isUsingItem()) {
			entity.stopUsingItem();
		}
		entity.getMainHandItem().remove(DataComponents.CHARGED_PROJECTILES);
		target = null;
		draw = 0;
		aim = 0;
	}

	private Monster nearestVisibleMonster(ServerLevel level) {
		List<Monster> monsters = level.getEntitiesOfClass(Monster.class,
				new AABB(entity.blockPosition()).inflate(SHOOT_RANGE, 12.0, SHOOT_RANGE), monster -> monster.isAlive());
		Monster nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (Monster monster : monsters) {
			double distance = monster.distanceToSqr(entity);
			if (distance < nearestDistance && entity.hasLineOfSight(monster)) {
				nearest = monster;
				nearestDistance = distance;
			}
		}
		return nearest;
	}
}
