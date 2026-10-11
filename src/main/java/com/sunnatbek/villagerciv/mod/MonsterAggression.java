package com.sunnatbek.villagerciv.mod;

import com.sunnatbek.villagerciv.mod.entity.CitizenEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;

import java.lang.reflect.Field;

/**
 * Can make the night dangerous for citizens. The game's zombies and skeletons hunt players and
 * vanilla villagers; a citizen is neither, so left alone they walk straight past one. Here every
 * zombie and skeleton that enters the world is given citizens as prey as well, but acts on it
 * only while the player has turned that on ({@code /villagerciv danger on}). By default
 * settlements grow in peace.
 *
 * <p>A mob's list of targets is not open to other code, so it is reached by reflection, once,
 * when the mod starts. If that ever fails (a future game version renaming things), the mod still
 * works: monsters simply ignore citizens again, and the log says so.
 */
final class MonsterAggression {
	private static final int TARGET_PRIORITY = 3;
	private static final double NOTICE_DISTANCE = 12.0;
	private static final Field TARGET_SELECTOR = findTargetSelector();

	private MonsterAggression() {
	}

	private static Field findTargetSelector() {
		try {
			Field field = Mob.class.getDeclaredField("targetSelector");
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException | RuntimeException e) {
			VillagerCivMod.LOGGER.error("Monsters will ignore citizens: cannot reach their target list", e);
			return null;
		}
	}

	static void register() {
		if (TARGET_SELECTOR == null) {
			return;
		}
		ServerEntityEvents.ENTITY_LOAD.register((entity, _) -> {
			if (entity instanceof Zombie || entity instanceof AbstractSkeleton) {
				Mob mob = (Mob) entity;
				try {
					GoalSelector targets = (GoalSelector) TARGET_SELECTOR.get(mob);
					targets.addGoal(TARGET_PRIORITY, new NearestAttackableTargetGoal<>(mob, CitizenEntity.class, true) {
						// A monster goes for a citizen it comes across, not for one it senses
						// through half a settlement: no gathering from afar to besiege the houses.
						@Override
						protected double getFollowDistance() {
							return NOTICE_DISTANCE;
						}

						// Asked every time rather than decided once, so the setting takes
						// effect at once, for monsters already in the world too.
						@Override
						public boolean canUse() {
							return VillagerCivMod.monstersHuntCitizens() && super.canUse();
						}

						@Override
						public boolean canContinueToUse() {
							return VillagerCivMod.monstersHuntCitizens() && super.canContinueToUse();
						}
					});
				} catch (IllegalAccessException e) {
					VillagerCivMod.LOGGER.error("Could not make {} hunt citizens", entity, e);
				}
			}
		});
	}
}
