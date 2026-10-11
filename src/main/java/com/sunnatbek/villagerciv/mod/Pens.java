package com.sunnatbek.villagerciv.mod;

import com.sunnatbek.villagerciv.build.Building;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * The animals in a settlement's pens. A pen holds one kind: cattle, sheep, pigs or chickens. Its
 * herder brings the first pair in, sees that they breed, and when the pen is full enough takes
 * a grown animal for the store. The animals are ordinary ones, and live in the pen as they would
 * anywhere; the pen's fence is what keeps them.
 */
public final class Pens {
	/** A pen is stocked with this many to begin with, bred up to {@link #FULL}, and thinned while more than {@link #KEPT} are grown. */
	private static final int FIRST = 2;
	private static final int FULL = 10;
	private static final int KEPT = 4;
	/** How long a young one takes to grow: a good part of a day, not the game's own twenty minutes of play. */
	private static final int GROWING_TICKS = 4_000;

	private Pens() {
	}

	/** What happened at a pen when its herder saw to it. */
	public enum Tended {
		/** Nothing could be done: the pen is not loaded, or is of a design that keeps no animal. */
		NOTHING,
		BROUGHT_IN,
		BRED,
		/** A grown animal was taken, and what it yields is in {@link #yieldOf}. */
		TAKEN
	}

	/** The animal a pen of this design keeps, or null if it is no pen. */
	static EntityType<? extends Animal> animalOf(String blueprint) {
		return switch (blueprint) {
			case "cattle_pen" -> EntityTypes.COW;
			case "sheep_pen" -> EntityTypes.SHEEP;
			case "pig_sty" -> EntityTypes.PIG;
			case "chicken_coop" -> EntityTypes.CHICKEN;
			default -> null;
		};
	}

	/** What one grown animal from this pen comes to in the store: three meals' worth, whatever the animal. */
	public static List<ItemStack> yieldOf(String blueprint) {
		List<ItemStack> goods = new ArrayList<>();
		Item meat = switch (blueprint) {
			case "cattle_pen" -> Items.BEEF;
			case "sheep_pen" -> Items.MUTTON;
			case "pig_sty" -> Items.PORKCHOP;
			default -> Items.CHICKEN;
		};
		if (meat == Items.CHICKEN) {
			goods.add(new ItemStack(Items.CHICKEN, 1));
			goods.add(new ItemStack(Items.EGG, 2));
		} else {
			goods.add(new ItemStack(meat, 3));
		}
		return goods;
	}

	/** The inside of the pen: its footprint within the fence, from the ground to above an animal's head. */
	static AABB inside(Building pen) {
		return new AABB(pen.x() + 1, pen.y() + 1, pen.z() + 1, pen.x() + pen.sizeX() - 1, pen.y() + 4, pen.z() + pen.sizeZ() - 1);
	}

	/** The animals of the pen's kind that are in it. */
	public static List<Animal> animals(ServerLevel level, Building pen) {
		EntityType<? extends Animal> kind = animalOf(pen.blueprint());
		if (kind == null || pen.y() == Building.Y_UNKNOWN) {
			return List.of();
		}
		return level.getEntitiesOfClass(Animal.class, inside(pen), animal -> animal.getType() == kind && animal.isAlive());
	}

	/** One round of the herder's work at a pen. */
	public static Tended tend(ServerLevel level, Building pen) {
		EntityType<? extends Animal> kind = animalOf(pen.blueprint());
		if (kind == null || pen.y() == Building.Y_UNKNOWN || pen.stage() != Building.Stage.COMPLETE
				|| !level.hasChunk(pen.x() >> 4, pen.z() >> 4)) {
			return Tended.NOTHING;
		}
		List<Animal> animals = animals(level, pen);
		double x = pen.x() + pen.sizeX() / 2 + 0.5;
		double y = pen.y() + 1;
		double z = pen.z() + pen.sizeZ() / 2 + 0.5;
		if (animals.size() < FIRST) {
			// Caught on the land round the settlement and led in.
			return add(level, kind, x, y, z, false) ? Tended.BROUGHT_IN : Tended.NOTHING;
		}
		List<Animal> grown = new ArrayList<>();
		for (Animal animal : animals) {
			if (!animal.isBaby()) {
				grown.add(animal);
			}
		}
		if (grown.size() > KEPT) {
			Animal taken = grown.get(level.getRandom().nextInt(grown.size()));
			level.sendParticles(ParticleTypes.POOF, taken.getX(), taken.getY() + 0.5, taken.getZ(), 8, 0.3, 0.3, 0.3, 0.02);
			taken.discard();
			return Tended.TAKEN;
		}
		if (animals.size() < FULL && grown.size() >= 2) {
			Animal parent = grown.get(level.getRandom().nextInt(grown.size()));
			if (add(level, kind, parent.getX(), parent.getY(), parent.getZ(), true)) {
				level.sendParticles(ParticleTypes.HEART, parent.getX(), parent.getY() + 1.0, parent.getZ(), 5, 0.4, 0.3, 0.4, 0.0);
				return Tended.BRED;
			}
		}
		return Tended.NOTHING;
	}

	private static boolean add(ServerLevel level, EntityType<? extends Animal> kind, double x, double y, double z, boolean young) {
		Animal animal = kind.create(level, EntitySpawnReason.BREEDING);
		if (animal == null) {
			return false;
		}
		animal.snapTo(x, y, z, level.getRandom().nextFloat() * 360.0f, 0.0f);
		if (young) {
			animal.setAge(-GROWING_TICKS);
		}
		animal.setPersistenceRequired();
		level.addFreshEntity(animal);
		level.playSound(null, animal.blockPosition(), SoundEvents.GRASS_STEP, SoundSource.NEUTRAL, 0.6f, 1.0f);
		return true;
	}
}
