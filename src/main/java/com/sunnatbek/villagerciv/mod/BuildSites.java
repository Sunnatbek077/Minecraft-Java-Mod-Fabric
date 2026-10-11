package com.sunnatbek.villagerciv.mod;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sunnatbek.villagerciv.build.Blueprint;
import com.sunnatbek.villagerciv.build.BlueprintLibrary;
import com.sunnatbek.villagerciv.build.Building;
import com.sunnatbek.villagerciv.build.ConstructionPlan;
import com.sunnatbek.villagerciv.build.Street;
import com.sunnatbek.villagerciv.sim.Settlement;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Puts buildings into the world, one construction step at a time. The same code serves a builder
 * laying a block while the player watches and the catch-up that brings the world up to date with
 * work done while nobody was near.
 *
 * <p>Every step looks at what is actually there first. A step that is already satisfied changes
 * nothing, so going over a building again (after a restart, or to repair it) is harmless. And a
 * step never overwrites something that was put there by someone else: the settlement builds on
 * natural ground and replaces only its own blocks.
 */
public final class BuildSites {
	/** How high above a lot's level the ground is cut away, and how deep below it a hollow is filled. */
	private static final int LOT_CUT = 40;
	private static final int LOT_FILL = 24;
	/** A wall standing this far from the settlement's level, or further, is taken down and built again at it. */
	private static final int WALL_OFF_GRADE = 2;
	/** A house is left alone a little longer: two blocks either way keeps any two buildings within four of each other. */
	private static final int HOUSE_OFF_GRADE = 3;
	private static final int WALL_FOOTING_DEPTH = 16;
	private static final Rotation[] ROTATIONS = {
			Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90
	};

	/** What happened when a step was carried out. */
	public enum Outcome {
		/** The world was changed. */
		CHANGED,
		/** Nothing to do: the step was already satisfied, or it is not ours to touch. */
		SKIPPED,
		/** The chunk is not loaded, or the design is unknown: try again later. */
		NOT_NOW
	}

	/** A building's steps, with what is needed to carry them out. */
	private record Plan(String blueprint, List<ConstructionPlan.Step> steps, int firstBlockStep, Set<Block> ownBlocks) {
	}

	private final BlueprintLibrary library;
	private final Map<String, BlockState> states = new HashMap<>();
	private final Map<Building, Plan> plans = new IdentityHashMap<>();
	private final Set<Building> wallsLookedAt = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

	BuildSites(BlueprintLibrary library) {
		this.library = library;
	}

	/** Checks every palette entry of every blueprint once, so a typo shows up at load, not mid-wall. */
	void validateBlueprints() {
		for (Blueprint blueprint : library.all()) {
			for (String state : blueprint.palette().values()) {
				if (state(state) == null) {
					VillagerCivMod.LOGGER.error("Blueprint '{}' uses an unknown block state: {}", blueprint.id(), state);
				}
			}
		}
	}

	private BlockState state(String text) {
		return states.computeIfAbsent(text, key -> {
			try {
				return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, key, false).blockState();
			} catch (CommandSyntaxException e) {
				return null;
			}
		});
	}

	private Plan plan(Settlement settlement, Building building) {
		Plan plan = plans.get(building);
		// A building that was enlarged is of another design now, and needs its steps drawn up again.
		if (plan == null || !plan.blueprint().equals(building.blueprint())) {
			Blueprint blueprint = library.get(building.blueprint()).orElse(null);
			if (blueprint == null) {
				return null; // a design that no longer exists: the building is kept, but not touched
			}
			Street street = building.street() >= 0 && building.street() < settlement.streets().size()
					? settlement.streets().get(building.street()) : null;
			List<ConstructionPlan.Step> steps = ConstructionPlan.of(building, blueprint, street);
			int firstBlock = steps.size();
			for (int i = 0; i < steps.size(); i++) {
				if (steps.get(i).kind() == ConstructionPlan.Kind.PLACE) {
					firstBlock = i;
					break;
				}
			}
			Set<Block> own = new HashSet<>();
			for (String text : blueprint.palette().values()) {
				BlockState state = state(text);
				if (state != null) {
					own.add(state.getBlock());
				}
			}
			plan = new Plan(blueprint.id(), steps, firstBlock, own);
			plans.put(building, plan);
			if (building.design() != blueprint.fingerprint()) {
				// The design has changed since this was built (more chests in the storehouse,
				// say), or it was built before designs were kept track of. A finished building is
				// gone over again, which adds what is new and nothing else.
				boolean finished = building.stage() == Building.Stage.COMPLETE;
				building.redesigned(steps.size());
				building.setDesign(blueprint.fingerprint());
				if (finished) {
					repair(settlement, building);
				}
			}
		}
		return plan;
	}

	/**
	 * Where a citizen spends the night: the floor at the foot of its own bed, inside its home.
	 * Null if there is no such place yet (the building is unfinished, unloaded, or of a design
	 * that no longer exists).
	 */
	public BlockPos bedsideOf(Settlement settlement, int home, int bed) {
		return bedCell(settlement, home, bed, 2);
	}

	/** The head block of a citizen's bed, or null under the same conditions as {@link #bedsideOf}. */
	public BlockPos bedHeadOf(Settlement settlement, int home, int bed) {
		return bedCell(settlement, home, bed, 0);
	}

	/** A cell in line with a bed: {@code towardsFoot} cells from its head, away from the back wall. */
	private BlockPos bedCell(Settlement settlement, int home, int bed, int towardsFoot) {
		Building building = settlement.building(home);
		if (building == null || building.y() == Building.Y_UNKNOWN || building.applied() < building.totalSteps()) {
			return null;
		}
		Blueprint blueprint = library.get(building.blueprint()).orElse(null);
		if (blueprint == null) {
			return null;
		}
		List<Blueprint.Cell> heads = blueprint.bedHeads();
		if (bed < 0 || bed >= heads.size()) {
			return null;
		}
		// Beds lie head to the back wall, foot towards the door: two cells on from the head is the
		// free floor at the bed's foot.
		Blueprint.Cell head = heads.get(bed);
		int standZ = Math.min(blueprint.depth() - 1, head.z() + towardsFoot);
		return new BlockPos(building.x() + ConstructionPlan.rotatedX(blueprint, building.rotation(), head.x(), standZ),
				building.y() + head.y(),
				building.z() + ConstructionPlan.rotatedZ(blueprint, building.rotation(), head.x(), standZ));
	}

	/**
	 * Sets a design down in the world all at once, front to the south, with its north-west corner
	 * at {@code origin} and its floor in the ground there. For looking at designs; it belongs to
	 * no settlement and nothing is charged for it.
	 */
	void showcase(ServerLevel level, Blueprint blueprint, BlockPos origin) {
		for (int pass = 0; pass < 2; pass++) {
			for (int y = 0; y < blueprint.height(); y++) {
				for (int z = 0; z < blueprint.depth(); z++) {
					for (int x = 0; x < blueprint.width(); x++) {
						char cell = blueprint.cell(x, y, z);
						boolean late = cell != Blueprint.LEAVE && cell != Blueprint.CLEAR && blueprint.isLate(cell);
						if (cell == Blueprint.LEAVE || late != (pass == 1) || (cell == Blueprint.CLEAR && pass == 1)) {
							continue;
						}
						BlockPos pos = origin.offset(x, y, z);
						BlockState wanted = cell == Blueprint.CLEAR ? Blocks.AIR.defaultBlockState() : state(blueprint.palette().get(cell));
						if (wanted == null) {
							continue;
						}
						if (wanted.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) || wanted.hasProperty(BlockStateProperties.BED_PART)) {
							level.setBlock(pos, wanted, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
						} else {
							level.setBlock(pos, Block.updateFromNeighbourShapes(wanted, level, pos), Block.UPDATE_ALL);
						}
					}
				}
			}
		}
	}

	/** Brings a building that was built from an older version of its design up to date. */
	void checkDesign(Settlement settlement, Building building) {
		plan(settlement, building);
	}

	/**
	 * The chest that goes with a citizen's bed: the one nearest to it in its home. Null if the
	 * home has none (not every design has the room), or it is not standing there right now.
	 */
	public BlockPos chestOf(ServerLevel level, Settlement settlement, int home, int bed) {
		Building building = settlement.building(home);
		if (building == null || building.y() == Building.Y_UNKNOWN || building.stage() != Building.Stage.COMPLETE) {
			return null;
		}
		Blueprint blueprint = library.get(building.blueprint()).orElse(null);
		if (blueprint == null) {
			return null;
		}
		List<Blueprint.Cell> heads = blueprint.bedHeads();
		if (bed < 0 || bed >= heads.size()) {
			return null;
		}
		Blueprint.Cell head = heads.get(bed);
		Blueprint.Cell nearest = null;
		int nearestDistance = Integer.MAX_VALUE;
		for (Blueprint.Cell chest : blueprint.chests()) {
			int distance = Math.abs(chest.x() - head.x()) + Math.abs(chest.z() - head.z()) + Math.abs(chest.y() - head.y());
			if (distance < nearestDistance) {
				nearest = chest;
				nearestDistance = distance;
			}
		}
		if (nearest == null) {
			return null;
		}
		BlockPos pos = new BlockPos(building.x() + ConstructionPlan.rotatedX(blueprint, building.rotation(), nearest.x(), nearest.z()),
				building.y() + nearest.y(),
				building.z() + ConstructionPlan.rotatedZ(blueprint, building.rotation(), nearest.x(), nearest.z()));
		return level.hasChunkAt(pos) && level.getBlockState(pos).is(Blocks.CHEST) ? pos : null;
	}

	/** The chests of the settlement's main storehouse that stand in the world right now, section by section. */
	List<Warehouse.StoreChest> storeChests(ServerLevel level, Settlement settlement) {
		Building store = settlement.mainStore();
		Blueprint blueprint = store == null || store.y() == Building.Y_UNKNOWN || !isLoaded(level, store)
				? null : library.get(store.blueprint()).orElse(null);
		if (blueprint == null) {
			return List.of();
		}
		List<Warehouse.StoreChest> chests = new java.util.ArrayList<>();
		for (Blueprint.StoreCell cell : blueprint.storeCells()) {
			BlockPos pos = new BlockPos(
					store.x() + ConstructionPlan.rotatedX(blueprint, store.rotation(), cell.cell().x(), cell.cell().z()),
					store.y() + cell.cell().y(),
					store.z() + ConstructionPlan.rotatedZ(blueprint, store.rotation(), cell.cell().x(), cell.cell().z()));
			if (level.getBlockState(pos).is(Blocks.CHEST)) {
				chests.add(new Warehouse.StoreChest(pos, cell.commodity()));
			}
		}
		return chests;
	}

	/**
	 * Where goods are brought: into the main storehouse if it stands, and to the Town Center
	 * otherwise.
	 */
	public BlockPos deliveryPoint(ServerLevel level, Settlement settlement) {
		List<Warehouse.StoreChest> chests = storeChests(level, settlement);
		return chests.isEmpty() ? new BlockPos(settlement.anchorX(), settlement.anchorY(), settlement.anchorZ())
				: chests.get(chests.size() / 2).pos();
	}

	/**
	 * Where the work is done in a workshop: the block worked at (the anvil, the stonecutter).
	 * The worker stands on the floor just in front of it, on the side the door is on. Null if
	 * the workshop is not standing in loaded ground.
	 */
	public BlockPos[] workBench(ServerLevel level, Settlement settlement, Building workshop, String blockState) {
		Blueprint blueprint = workshop.y() == Building.Y_UNKNOWN || workshop.stage() != Building.Stage.COMPLETE || !isLoaded(level, workshop)
				? null : library.get(workshop.blueprint()).orElse(null);
		Blueprint.Cell bench = blueprint == null ? null : blueprint.cellOf(blockState);
		if (bench == null) {
			return null;
		}
		return new BlockPos[] {cellPos(workshop, blueprint, bench.x(), bench.y(), bench.z()),
				cellPos(workshop, blueprint, bench.x(), bench.y(), Math.min(blueprint.depth() - 1, bench.z() + 1))};
	}

	private static BlockPos cellPos(Building building, Blueprint blueprint, int x, int y, int z) {
		return new BlockPos(building.x() + ConstructionPlan.rotatedX(blueprint, building.rotation(), x, z), building.y() + y,
				building.z() + ConstructionPlan.rotatedZ(blueprint, building.rotation(), x, z));
	}

	/** Where a builder stands: on the verge in front of the building, where a block can never wall it in. */
	public BlockPos standPoint(ServerLevel level, Building building) {
		int x = building.x() + building.sizeX() / 2;
		int z = building.z() + building.sizeZ() / 2;
		// A wall faces outwards, and its builders stay on the inside of it.
		int facing = building.category() == Blueprint.Category.DEFENSE ? building.rotation() + 2 : building.rotation();
		switch (facing & 3) {
			case 0 -> z = building.z() + building.sizeZ() + 1;
			case 1 -> x = building.x() - 2;
			case 2 -> z = building.z() - 2;
			default -> x = building.x() + building.sizeX() + 1;
		}
		return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
	}

	/**
	 * Whether a citizen can stand and work at this building's site: its ground is loaded, and
	 * near enough to a player for the game to be running the creatures on it. A body sent to
	 * ground that is loaded but not run simply stops existing there.
	 */
	public boolean isAlive(ServerLevel level, Building building) {
		return isLoaded(level, building)
				&& level.isPositionEntityTicking(new BlockPos(building.x() + building.sizeX() / 2, 0, building.z() + building.sizeZ() / 2))
				&& level.isPositionEntityTicking(standPoint(level, building));
	}

	public boolean isLoaded(ServerLevel level, Building building) {
		return level.hasChunk(building.x() >> 4, building.z() >> 4)
				&& level.hasChunk((building.x() + building.sizeX() - 1) >> 4, (building.z() + building.sizeZ() - 1) >> 4);
	}

	/**
	 * Carries out the building's next unapplied step and moves its cursor past it.
	 *
	 * @return what happened; after {@link Outcome#NOT_NOW} the cursor has not moved
	 */
	public Outcome applyNext(ServerLevel level, Settlement settlement, Building building) {
		Plan plan = plan(settlement, building);
		if (plan == null || !isLoaded(level, building)) {
			return Outcome.NOT_NOW;
		}
		if (building.applied() >= plan.steps().size()) {
			return Outcome.SKIPPED;
		}
		if (building.y() == Building.Y_UNKNOWN) {
			// Everything stands at the settlement's one level; the footprint is brought to it before building.
			building.setY(settlement.grade());
		}
		ConstructionPlan.Step step = plan.steps().get(building.applied());
		Outcome outcome = switch (step.kind()) {
			case PAVE -> pave(level, step);
			case LEVEL -> level(level, building, new BlockPos(step.x(), building.y(), step.z()));
			case PLACE -> place(level, building, plan, step);
		};
		if (outcome != Outcome.NOT_NOW) {
			building.stepApplied();
			if (building.stage() == Building.Stage.UNDER_REPAIR && building.applied() >= plan.steps().size()) {
				building.setStage(Building.Stage.COMPLETE);
			}
		}
		return outcome;
	}

	/** A stretch of palisade, as opposed to a gate or a tower: those stand where people walk, on the ground as it is. */
	private static boolean isWallRun(Building building) {
		return building.category() == Blueprint.Category.DEFENSE && com.sunnatbek.villagerciv.build.WallDesigns.isRun(building.blueprint());
	}

	/**
	 * A finished building that stands off the settlement's level (it was built when lots and
	 * walls still followed the land, up a bank or down in a hollow) is brought to it: what stands
	 * is taken down, and it is built again from the right height, the ground cut or filled to
	 * suit. A wall is held to the level more closely than a house. Looked at once for each
	 * building in a session, and by day, when nobody is in bed in it.
	 *
	 * @param salvage takes what lay in the building's chests, to be carried to the storehouse
	 */
	void bringToGrade(ServerLevel level, Settlement settlement, Building building, java.util.function.Consumer<List<net.minecraft.world.item.ItemStack>> salvage) {
		// The main storehouse shows the stock itself, chest by chest: it is left where it stands.
		if (building.stage() != Building.Stage.COMPLETE || building.y() == Building.Y_UNKNOWN || building == settlement.mainStore()
				|| wallsLookedAt.contains(building) || !isLoaded(level, building) || !WorkDay.isDay(level)) {
			return;
		}
		wallsLookedAt.add(building);
		int target = settlement.grade();
		int allowed = building.category() == Blueprint.Category.DEFENSE ? WALL_OFF_GRADE : HOUSE_OFF_GRADE;
		Plan plan = Math.abs(target - building.y()) >= allowed ? plan(settlement, building) : null;
		if (plan == null) {
			return;
		}
		for (ConstructionPlan.Step step : plan.steps()) {
			if (step.kind() == ConstructionPlan.Kind.PLACE) {
				BlockPos pos = new BlockPos(step.x(), building.y() + step.dy(), step.z());
				if (plan.ownBlocks().contains(level.getBlockState(pos).getBlock())) {
					List<net.minecraft.world.item.ItemStack> goods = PersonalChests.empty(level, pos);
					if (!goods.isEmpty()) {
						salvage.accept(goods);
					}
					level.removeBlockEntity(pos);
					// Without asking the neighbours: a lantern whose beam goes first is not to fall as an item.
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
				}
			}
		}
		building.setY(target);
		building.setCursors(building.progress(), 0);
		building.setStage(Building.Stage.UNDER_REPAIR);
	}

	/** A street follows the land: each cell becomes path where it lies. */
	private static Outcome pave(ServerLevel level, ConstructionPlan.Step step) {
		if (!level.hasChunk(step.x() >> 4, step.z() >> 4)) {
			return Outcome.NOT_NOW;
		}
		BlockPos ground = new BlockPos(step.x(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, step.x(), step.z()) - 1, step.z());
		if (!WorkSites.isSoil(level.getBlockState(ground))) {
			return Outcome.SKIPPED;
		}
		BlockState above = level.getBlockState(ground.above());
		if (!above.isAir()) {
			if (!above.canBeReplaced()) {
				return Outcome.SKIPPED;
			}
			level.setBlock(ground.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
		level.setBlock(ground, Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_ALL);
		return Outcome.CHANGED;
	}

	private static Outcome level(ServerLevel level, Building building, BlockPos ground) {
		if (isWallRun(building)) {
			// A wall stands on a footing of its own (see place), so a hollow under it is not
			// filled; higher ground on its line is cut away. What stands on the line already is
			// never felled as a tree: a palisade is made of logs.
			return WorkSites.levelColumn(level, ground, LOT_CUT, 0) ? Outcome.CHANGED : Outcome.SKIPPED;
		}
		BlockState soil = level.getBlockState(ground);
		boolean even = !soil.isAir() && !soil.canBeReplaced();
		for (int dy = 1; even && dy <= LOT_CUT; dy++) {
			even = level.getBlockState(ground.above(dy)).isAir();
		}
		if (even) {
			return Outcome.SKIPPED;
		}
		// A street lamp standing where a house is to go is taken down again.
		for (int dy = 4; dy >= 1; dy--) {
			if (Lamplighter.isLampPart(level.getBlockState(ground.above(dy)))) {
				level.destroyBlock(ground.above(dy), false);
			}
		}
		// A tree rooted on the lot comes down whole; one trunk left standing would end up indoors.
		for (int dy = -1; dy <= LOT_CUT; dy++) {
			BlockPos pos = ground.above(dy);
			if (level.getBlockState(pos).typeHolder().is(BlockTags.LOGS) && WorkSites.isSoil(level.getBlockState(pos.below()))) {
				WorkSites.fellTree(level, pos, log -> building.contains(log.getX(), log.getZ()));
			}
		}
		// A lot is made level whatever it takes: a bank is cut away, a pit is filled to the brim.
		return WorkSites.levelColumn(level, ground, LOT_CUT, LOT_FILL) ? Outcome.CHANGED : Outcome.SKIPPED;
	}

	private Outcome place(ServerLevel level, Building building, Plan plan, ConstructionPlan.Step step) {
		BlockPos pos = new BlockPos(step.x(), building.y() + step.dy(), step.z());
		BlockState current = level.getBlockState(pos);
		BlockState wanted = Blocks.AIR.defaultBlockState();
		if (step.state() != null) {
			BlockState parsed = state(step.state());
			if (parsed == null) {
				return Outcome.SKIPPED;
			}
			wanted = parsed.rotate(ROTATIONS[building.rotation() & 3]);
		}
		if (current == wanted) {
			return Outcome.SKIPPED;
		}
		// Only natural ground and the building's own materials may be replaced.
		if (!current.isAir() && !WorkSites.isNatural(current) && !plan.ownBlocks().contains(current.getBlock())) {
			return Outcome.SKIPPED;
		}
		if (wanted.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) || wanted.hasProperty(BlockStateProperties.BED_PART)) {
			// Doors and beds are two blocks that each vanish without the other. They are set
			// exactly as designed, without asking the neighbours, so the first half survives
			// until the second arrives.
			level.setBlock(pos, wanted, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
		} else {
			// Everything else settles against its neighbours: panes and fences join up, stairs turn corners.
			level.setBlock(pos, Block.updateFromNeighbourShapes(wanted, level, pos), Block.UPDATE_ALL);
		}
		if (step.dy() == 1 && isWallRun(building)) {
			// The wall's footing: stone down to solid ground, so nothing can pass beneath it.
			BlockPos.MutableBlockPos below = pos.mutable();
			for (int depth = 0; depth < WALL_FOOTING_DEPTH; depth++) {
				BlockState under = level.getBlockState(below.move(0, -1, 0));
				if (!under.isAir() && !under.canBeReplaced()) {
					break;
				}
				level.setBlock(below, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
		return Outcome.CHANGED;
	}

	/**
	 * A block of a finished building was broken: go over the whole building again. Nothing that
	 * is still right is touched, so only what is missing gets put back.
	 */
	void repair(Settlement settlement, Building building) {
		Plan plan = plan(settlement, building);
		if (plan == null || building.stage() == Building.Stage.UNDER_CONSTRUCTION || building.stage() == Building.Stage.PLANNED) {
			return;
		}
		// From the first block, not from the start: the ground was levelled long ago, and
		// levelling again would take the walls for a bump.
		building.setCursors(building.progress(), Math.min(building.applied(), plan.firstBlockStep()));
		building.setStage(Building.Stage.UNDER_REPAIR);
	}

	/** How long a builder takes over a step. */
	public static int ticksFor(ConstructionPlan.Kind kind) {
		return switch (kind) {
			case PAVE -> 8;
			case LEVEL -> 15;
			case PLACE -> 6;
		};
	}

	/** Kind of the next unapplied step, or null if there is none. */
	public ConstructionPlan.Kind nextKind(Settlement settlement, Building building) {
		Plan plan = plan(settlement, building);
		return plan == null || building.applied() >= plan.steps().size() ? null : plan.steps().get(building.applied()).kind();
	}
}
