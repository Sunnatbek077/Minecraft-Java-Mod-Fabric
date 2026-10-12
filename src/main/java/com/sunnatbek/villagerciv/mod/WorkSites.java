package com.sunnatbek.villagerciv.mod;

import com.sunnatbek.villagerciv.sim.Commodity;
import com.sunnatbek.villagerciv.sim.WorkSite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * What work looks like in the world. For each kind of site this decides the next thing to do
 * there and carries it out, one small block change at a time.
 *
 * <p>Fields and groves keep no plan of their own: the next action is read off the blocks that
 * are there. So work resumes correctly after a restart, a chunk reload, or someone interfering.
 *
 * <p>Two rules hold for every action. The ground is made level first: nothing is tilled or
 * planted on a slope. And only natural blocks are ever changed; anything built is left alone.
 */
public final class WorkSites {
	public enum Kind {
		/** Bring a column to site level: clear what stands above, fill what is missing below. */
		LEVEL(30),
		/** Dig the well in the middle of a field. */
		WATER(60),
		/** Till the soil and sow it. */
		SOW(30),
		HARVEST(20),
		/** Weed and water a growing crop, which brings it on a stage. */
		TEND_CROP(120),
		PLANT_SAPLING(30),
		/** See to a sapling so that it shoots up now instead of in its own time. */
		TEND_SAPLING(40),
		/** Fell a grown tree. */
		CHOP(100),
		/** Cut the next step of the mine stair. */
		DIG_STEP(80),
		/** Drive a gallery one block further out from the stair. */
		DIG_GALLERY(70),
		/** Cut sideways from the stair to ore that lies in the rock near it. */
		PROSPECT(90),
		/** Cut stone at the deep faces of a mine whose stair and galleries are done. Changes nothing that can be seen. */
		QUARRY(160),
		/** Dig out a stretch of the stair that has caved in. */
		CLEAR_STEP(40);

		private final int ticks;

		Kind(int ticks) {
			this.ticks = ticks;
		}

		/** How long a suited worker takes. */
		public int ticks() {
			return ticks;
		}
	}

	/**
	 * @param pos   the block the worker deals with
	 * @param stand where to stand while doing it
	 */
	public record Action(Kind kind, BlockPos pos, BlockPos stand) {
	}

	/** What an action brought in: nothing, or some units of a commodity to carry home. */
	/**
	 * @param items for ore, what exactly was found: coal, raw iron, gold, diamonds and so on. The
	 *              amount is the count the settlement's books go by; the items are what the miner
	 *              actually carries home.
	 */
	public record Yield(Commodity commodity, int amount, List<ItemStack> items) {
		static final Yield NONE = new Yield(null, 0);

		Yield(Commodity commodity, int amount) {
			this(commodity, amount, List.of());
		}
	}

	/** Trees stand on every third column of a grove, so their crowns have room. */
	private static final int TREE_SPACING = 3;
	private static final int MAX_LEVEL_CHANGE = 3;
	private static final int MAX_TREE_LOGS = 64;
	/** One harvested tile in this many gives a bale of straw besides its grain. */
	private static final int STRAW_ONE_IN = 4;
	/** A mine stair turns a corner after this many steps, winding down as a square helix. */
	private static final int STAIR_SIDE = 12;
	private static final int MAX_STAIR_STEPS = 144;
	private static final int TORCH_EVERY = 6;
	/** How much gravel or sand lying on a step is dug out along with it. */
	private static final int MAX_LOOSE_GROUND = 12;
	/** Galleries leave the stair from the middle of each side, once it is this many steps down. */
	private static final int FIRST_GALLERY_STEP = STAIR_SIDE + STAIR_SIDE / 2;
	private static final int GALLERY_LENGTH = 20;
	/** How much of a vein is taken in one go, and how far from the tunnel it is followed. */
	private static final int MAX_VEIN_BLOCKS = 24;
	private static final int VEIN_REACH = 6;
	/** How far to either side of the stair, and above it, ore is cut out; and how many steps are looked at each time. */
	private static final int PROSPECT_REACH = 5;
	private static final int PROSPECT_STEPS_PER_LOOK = 3;
	/** How many of the deepest steps the miners of a worked-out mine spread over, and the stone one spell there brings up. */
	private static final int QUARRY_FACES = 6;
	private static final int QUARRY_STONE = 3;

	private WorkSites() {
	}

	/** Learns the ground level of a site the first time its chunk is loaded. */
	public static boolean ensureGroundLevel(ServerLevel level, WorkSite site) {
		if (site.y() != WorkSite.Y_UNKNOWN) {
			return true;
		}
		if (!level.hasChunk(site.x() >> 4, site.z() >> 4)) {
			return false;
		}
		site.setY(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, site.x(), site.z()) - 1);
		return true;
	}

	/**
	 * The next thing to do at a site, or null if there is nothing to do right now (crops and
	 * saplings are growing, or the site is finished).
	 *
	 * @param claimed blocks other workers are already dealing with
	 * @param offset  where in the site to start looking, so workers spread out instead of queueing
	 */
	public static Action next(ServerLevel level, WorkSite site, Set<Long> claimed, int offset) {
		if (!ensureGroundLevel(level, site) || site.isExhausted()) {
			return null;
		}
		return switch (site.type()) {
			case FARM -> nextInSquare(level, site, claimed, offset, 1);
			case LUMBER -> nextInSquare(level, site, claimed, offset, TREE_SPACING);
			case MINE -> nextStep(level, site, claimed, offset);
			// Building is not done at a work site; see BuildSites.
			case BUILD, LEVEL, SMITH, MASON, TRADE, HERD, STUDY -> null;
		};
	}

	private static Action nextInSquare(ServerLevel level, WorkSite site, Set<Long> claimed, int offset, int spacing) {
		int half = WorkSite.HALF_WIDTH;
		int side = 2 * half + 1;
		BlockPos.MutableBlockPos ground = new BlockPos.MutableBlockPos();
		for (int i = 0; i < side * side; i++) {
			int index = Math.floorMod(offset + i, side * side);
			int dx = index % side - half;
			int dz = index / side - half;
			boolean field = site.type() == com.sunnatbek.villagerciv.sim.JobType.FARM;
			if (!field && (Math.floorMod(dx, spacing) != 0 || Math.floorMod(dz, spacing) != 0)) {
				continue;
			}
			ground.set(site.x() + dx, site.y(), site.z() + dz);
			if (!level.hasChunkAt(ground) || claimed.contains(ground.asLong())) {
				continue;
			}
			Kind kind = field ? fieldAction(level, ground, dx == 0 && dz == 0) : groveAction(level, ground);
			if (kind != null) {
				BlockPos pos = ground.immutable();
				// A tree is tended from beside it, never from the spot where its trunk will be:
				// a sapling can shoot up at any moment, around whoever is standing there.
				return new Action(kind, pos, field ? pos.above() : pos.above().east());
			}
		}
		return null;
	}

	private static Kind fieldAction(ServerLevel level, BlockPos ground, boolean well) {
		BlockState soil = level.getBlockState(ground);
		if (well) {
			return soil.getFluidState().typeHolder().is(FluidTags.WATER) ? null : levelOr(level, ground, Kind.WATER, true);
		}
		BlockState above = level.getBlockState(ground.above());
		if (soil.is(Blocks.FARMLAND)) {
			if (above.getBlock() instanceof CropBlock crop) {
				// A field is worked, not waited on: between sowing and reaping there is tending.
				return crop.isMaxAge(above) ? Kind.HARVEST : Kind.TEND_CROP;
			}
			return above.isAir() ? Kind.SOW : null;
		}
		Kind levelling = levelOr(level, ground, Kind.SOW, false);
		if (levelling != Kind.SOW) {
			return levelling;
		}
		return isSoil(soil) ? Kind.SOW : null;
	}

	private static Kind groveAction(ServerLevel level, BlockPos ground) {
		BlockState above = level.getBlockState(ground.above());
		if (above.typeHolder().is(BlockTags.LOGS)) {
			return Kind.CHOP;
		}
		if (above.typeHolder().is(BlockTags.SAPLINGS)) {
			// A grove is worked, not waited on: the lumberjacks bring their saplings on.
			return Kind.TEND_SAPLING;
		}
		Kind levelling = levelOr(level, ground, Kind.PLANT_SAPLING, false);
		if (levelling != Kind.PLANT_SAPLING) {
			return levelling;
		}
		return isSoil(level.getBlockState(ground)) ? Kind.PLANT_SAPLING : null;
	}

	/**
	 * {@link Kind#LEVEL} if the column still has to be brought to site level, {@code then} if it is
	 * level already, or null if it cannot be levelled without touching something that was built.
	 */
	private static Kind levelOr(ServerLevel level, BlockPos ground, Kind then, boolean groundMayBeAnything) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		boolean uneven = false;
		for (int dy = 1; dy <= MAX_LEVEL_CHANGE; dy++) {
			BlockState state = level.getBlockState(cursor.setWithOffset(ground, 0, dy, 0));
			if (state.isAir()) {
				continue;
			}
			if (!isNatural(state)) {
				return null;
			}
			uneven = true;
		}
		BlockState soil = level.getBlockState(ground);
		if (!soil.getFluidState().isEmpty()) {
			return groundMayBeAnything ? then : null;
		}
		if (soil.isAir() || soil.canBeReplaced()) {
			uneven = true;
		} else if (!isNatural(soil)) {
			return null;
		}
		return uneven ? Kind.LEVEL : then;
	}

	/**
	 * The mine is a stair with galleries off it. If any of the stair has caved in (gravel and
	 * sand come down when the rock under them is taken away), that is dug out first: the way down
	 * has to be open before a face can be worked. Then the galleries of the levels already reached
	 * are driven out, since that is where the rock and the ore are; the stair goes deeper only
	 * when there is no gallery left to work, or when all of them are taken by other miners.
	 */
	private static Action nextStep(ServerLevel level, WorkSite site, Set<Long> claimed, int offset) {
		for (int cut = 1; cut <= site.progress(); cut++) {
			BlockPos floor = stairFloor(site, cut);
			if (level.hasChunkAt(floor) && isBlocked(level, floor)) {
				// Whoever asks is sent, even if somebody is on the way already: a blocked stair
				// stops the whole mine, and clearing it twice over does no harm.
				return new Action(Kind.CLEAR_STEP, floor, stairFloor(site, cut - 1).above());
			}
		}
		// Ore first. A stair and its galleries only ever touch what they happen to run into; the
		// rock a few blocks to either side holds far more, and that is what the mine is for.
		Action ore = nextOreNearStair(level, site, claimed);
		if (ore != null) {
			return ore;
		}
		Action gallery = nextGalleryCell(level, site, claimed);
		if (gallery != null) {
			return gallery;
		}
		int step = site.progress() + 1;
		BlockPos floor = stairFloor(site, step);
		if (!level.hasChunkAt(floor)) {
			return null;
		}
		if (stairEnds(level, site)) {
			// The stair can go no deeper. That is not the end of the mine: there was ore and
			// gallery to work first, and it is only worked out when a whole look down the
			// stair has found no more of either.
			int[] search = PROSPECTED.get(site);
			if (search != null && search[1] >= site.progress() && nextGalleryCell(level, site, Set.of()) == null) {
				// Worked out as far as it is dug. A mine is not abandoned for that: its miners
				// go on cutting at the deep faces, which is slower going and poorer in ore, but
				// never comes to an end. A settlement inside its wall has nowhere to sink another.
				int from = Math.max(1, site.progress() - Math.floorMod(offset, QUARRY_FACES));
				BlockPos face = stairFloor(site, from);
				return new Action(Kind.QUARRY, face, face.above());
			}
			return null;
		}
		return claimed.contains(floor.asLong()) ? null : new Action(Kind.DIG_STEP, floor, stairFloor(site, step - 1).above());
	}

	/**
	 * Whether the stair has reached its end: its full depth, the bottom of the world, or
	 * something it must not cut into. Anything built, or any fluid, stops it: the miners neither
	 * flood their stair nor break into someone's cellar.
	 */
	private static boolean stairEnds(ServerLevel level, WorkSite site) {
		int step = site.progress() + 1;
		BlockPos floor = stairFloor(site, step);
		if (step > MAX_STAIR_STEPS || floor.getY() <= level.getMinY() + 6) {
			return true;
		}
		for (int dy = 0; dy <= 3; dy++) {
			BlockState state = level.getBlockState(floor.above(dy));
			if (!state.getFluidState().isEmpty() || (!state.isAir() && !isNatural(state))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A mine that was closed is looked at again, once, when its ground is next loaded: if there
	 * is still ore by its stair, gallery to drive or stair to cut, it is open after all. Returns
	 * whether it could be looked at. Mines
	 * were once closed the moment their stair met water, with most of their ore still in the rock.
	 */
	public static boolean reopenIfWorkable(ServerLevel level, WorkSite site) {
		if (site.type() != com.sunnatbek.villagerciv.sim.JobType.MINE || !site.isExhausted() || site.y() == WorkSite.Y_UNKNOWN
				|| !level.hasChunkAt(stairFloor(site, 0))) {
			return false; // cannot be judged yet
		}
		// Whatever is left in it, there is always the deep rock: a mine does not stay closed.
		site.setExhausted(false);
		return true;
	}

	/**
	 * How the search for ore along each mine's stair stands: {the step it has got to, how many
	 * steps in a row it has found nothing by}. Forgotten on restart, and then simply begun again.
	 */
	private static final java.util.Map<WorkSite, int[]> PROSPECTED = new java.util.IdentityHashMap<>();

	/**
	 * Looks at the rock around the next few stair steps and, if there is ore in it, sends a
	 * miner to that step to cut it out. The search moves down the stair a little at every call
	 * and starts again from the top when it reaches the bottom, so new stretches of stair are
	 * searched as they are dug and nothing is searched twice in a row.
	 */
	private static Action nextOreNearStair(ServerLevel level, WorkSite site, Set<Long> claimed) {
		if (site.progress() < 1) {
			return null;
		}
		int[] search = PROSPECTED.computeIfAbsent(site, _ -> new int[2]);
		for (int i = 0; i < PROSPECT_STEPS_PER_LOOK; i++) {
			search[0] = search[0] >= site.progress() ? 1 : search[0] + 1;
			BlockPos floor = stairFloor(site, search[0]);
			if (!level.hasChunkAt(floor)) {
				continue;
			}
			if (oreNear(level, floor, 1).isEmpty()) {
				search[1]++;
				continue;
			}
			search[1] = 0;
			if (!claimed.contains(floor.asLong())) {
				// Stay on this step until it is worked out: the next look comes back to it.
				search[0]--;
				return new Action(Kind.PROSPECT, floor, floor.above());
			}
		}
		return null;
	}

	/** Ore in the rock round a stair step that can be taken safely, nearest first, up to a limit. */
	private static List<BlockPos> oreNear(ServerLevel level, BlockPos floor, int limit) {
		List<BlockPos> found = new ArrayList<>();
		for (int reach = 1; reach <= PROSPECT_REACH && found.size() < limit; reach++) {
			for (BlockPos pos : BlockPos.betweenClosed(floor.offset(-reach, -2, -reach), floor.offset(reach, PROSPECT_REACH, reach))) {
				// Only the shell of each box: the inside was looked at with the smaller one.
				if (Math.max(Math.abs(pos.getX() - floor.getX()), Math.abs(pos.getZ() - floor.getZ())) != reach) {
					continue;
				}
				if (isOre(level.getBlockState(pos)) && !touchesFluid(level, pos)) {
					found.add(pos.immutable());
					if (found.size() >= limit) {
						break;
					}
				}
			}
		}
		return found;
	}

	/**
	 * A spell of work at the deep faces. It is reckoned, not dug: the faces lie beyond what the
	 * mine shows, so no block changes. It brings up stone of the kind found at that depth, and
	 * now and then a little ore, the common kinds far more often than the rare.
	 */
	private static Yield quarry(ServerLevel level, BlockPos face) {
		List<ItemStack> loot = new ArrayList<>();
		loot.add(new ItemStack(face.getY() < 0 ? Items.COBBLED_DEEPSLATE : Items.COBBLESTONE, QUARRY_STONE));
		int roll = level.getRandom().nextInt(100);
		boolean deep = face.getY() < 0;
		ItemStack ore = roll < 10 ? new ItemStack(Items.COAL, 2)
				: roll < 18 ? new ItemStack(Items.RAW_COPPER, 2)
				: roll < 25 ? new ItemStack(Items.RAW_IRON)
				: roll < 28 && deep ? new ItemStack(Items.REDSTONE, 3)
				: roll < 31 && deep ? new ItemStack(Items.LAPIS_LAZULI, 3)
				: roll < 33 && deep ? new ItemStack(Items.RAW_GOLD)
				: roll < 34 && deep ? new ItemStack(Items.DIAMOND)
				: ItemStack.EMPTY;
		if (!ore.isEmpty()) {
			loot.add(ore);
		}
		return new Yield(Commodity.STONE, QUARRY_STONE, loot);
	}

	/** Cuts out the ore near a stair step: the pockets it leaves are in solid rock, off the way down. */
	private static Yield prospect(ServerLevel level, BlockPos floor) {
		List<ItemStack> loot = new ArrayList<>();
		for (BlockPos pos : oreNear(level, floor, MAX_VEIN_BLOCKS)) {
			loot.add(findOf(level.getBlockState(pos)));
			BlockState over = level.getBlockState(pos.above());
			// Ore that was holding up a floor is replaced with stone; the rest just comes out.
			if (over.isAir() || over.is(Blocks.TORCH)) {
				level.setBlockAndUpdate(pos, Blocks.COBBLESTONE.defaultBlockState());
			} else {
				level.destroyBlock(pos, false);
			}
		}
		return loot.isEmpty() ? Yield.NONE : new Yield(Commodity.ORE, loot.size(), loot);
	}

	/**
	 * The next block of gallery to dig. Nothing is remembered about the galleries: how far each
	 * has got is read off the rock, by walking out from the stair until the way is not open. A
	 * gallery that has fallen in is therefore simply dug again, and one that met water, lava, a
	 * cave or somebody's cellar counts as finished, because walking out along it stops there.
	 */
	private static Action nextGalleryCell(ServerLevel level, WorkSite site, Set<Long> claimed) {
		for (int step = FIRST_GALLERY_STEP; step <= site.progress(); step += STAIR_SIDE) {
			BlockPos landing = stairFloor(site, step);
			int[] heading = stairHeading(site, step);
			// The stair winds to the right, so left of it is always away from the helix.
			int outX = heading[1];
			int outZ = -heading[0];
			for (int i = 1; i <= GALLERY_LENGTH; i++) {
				BlockPos floor = landing.offset(outX * i, 0, outZ * i);
				if (!level.hasChunkAt(floor) || !canTunnel(level, floor)) {
					break;
				}
				if (isBlocked(level, floor)) {
					if (claimed.contains(floor.asLong())) {
						break; // someone is at this face already: try the next gallery
					}
					return new Action(Kind.DIG_GALLERY, floor, landing.offset(outX * (i - 1), 0, outZ * (i - 1)).above());
				}
			}
		}
		return null;
	}

	/**
	 * Whether a gallery may pass over this floor block: solid footing, and nothing in or beside
	 * the walking space that is wet or was built. Miners stop a block short of trouble.
	 */
	private static boolean canTunnel(ServerLevel level, BlockPos floor) {
		BlockState footing = level.getBlockState(floor);
		if (footing.isAir() || footing.canBeReplaced() || !footing.getFluidState().isEmpty()) {
			return false;
		}
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dy = 1; dy <= 4; dy++) {
			BlockState state = level.getBlockState(cursor.setWithOffset(floor, 0, dy, 0));
			if (!state.getFluidState().isEmpty() || (dy <= 3 && !state.isAir() && !isNatural(state))) {
				return false;
			}
			if (dy <= 3 && (isWet(level, cursor.setWithOffset(floor, 1, dy, 0)) || isWet(level, cursor.setWithOffset(floor, -1, dy, 0))
					|| isWet(level, cursor.setWithOffset(floor, 0, dy, 1)) || isWet(level, cursor.setWithOffset(floor, 0, dy, -1)))) {
				return false;
			}
		}
		return true;
	}

	private static boolean isWet(ServerLevel level, BlockPos pos) {
		return !level.getBlockState(pos).getFluidState().isEmpty();
	}

	/**
	 * Floor block of stair step {@code step} (step 0 is the entrance, at ground level). Each step
	 * is one block further and one block lower; every {@link #STAIR_SIDE} steps the stair turns
	 * right, so it winds down a square helix that never meets itself.
	 */
	static BlockPos stairFloor(WorkSite site, int step) {
		int x = site.x();
		int z = site.z();
		int dx = site.directionX();
		int dz = site.directionZ();
		for (int i = 1; i <= step; i++) {
			x += dx;
			z += dz;
			if (i % STAIR_SIDE == 0) {
				int turned = -dz;
				dz = dx;
				dx = turned;
			}
		}
		return new BlockPos(x, site.y() - step, z);
	}

	/** Where one stands on a step of a mine's stair: the way in and out, step 0 being the entrance. */
	public static BlockPos stairStand(WorkSite site, int step) {
		return stairFloor(site, Math.max(0, step)).above();
	}

	/** Which way the stair is heading as it comes down onto step {@code step}: {dx, dz}. */
	private static int[] stairHeading(WorkSite site, int step) {
		int dx = site.directionX();
		int dz = site.directionZ();
		for (int turn = (step - 1) / STAIR_SIDE; turn > 0; turn--) {
			int turned = -dz;
			dz = dx;
			dx = turned;
		}
		return new int[] {dx, dz};
	}

	/** Carries out an action. The world may have changed since it was chosen, so everything is re-checked. */
	public static Yield perform(ServerLevel level, WorkSite site, Action action) {
		BlockPos pos = action.pos();
		switch (action.kind()) {
			case LEVEL -> levelColumn(level, pos);
			case WATER -> {
				if (levelColumn(level, pos)) {
					level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
				}
			}
			case SOW -> {
				BlockState soil = level.getBlockState(pos);
				if ((isSoil(soil) || soil.is(Blocks.FARMLAND)) && level.getBlockState(pos.above()).canBeReplaced()) {
					level.setBlockAndUpdate(pos, Blocks.FARMLAND.defaultBlockState());
					level.setBlockAndUpdate(pos.above(), Blocks.WHEAT.defaultBlockState());
				}
			}
			case HARVEST -> {
				BlockState crop = level.getBlockState(pos.above());
				if (crop.getBlock() instanceof CropBlock block && block.isMaxAge(crop)) {
					level.destroyBlock(pos.above(), false);
					// Part of the harvest is seed: the tile is sown again at once.
					level.setBlockAndUpdate(pos.above(), block.getStateForAge(0));
					// The grain goes home as bread; and of every few sheaves the straw is worth
					// binding into a bale as well.
					List<ItemStack> sheaf = new ArrayList<>();
					sheaf.add(new ItemStack(Items.BREAD));
					if (level.getRandom().nextInt(STRAW_ONE_IN) == 0) {
						sheaf.add(new ItemStack(Items.HAY_BLOCK));
					}
					return new Yield(Commodity.FOOD, 1, sheaf);
				}
			}
			case TEND_CROP -> {
				BlockState crop = level.getBlockState(pos.above());
				if (crop.getBlock() instanceof CropBlock block && !block.isMaxAge(crop)) {
					level.setBlockAndUpdate(pos.above(), block.getStateForAge(Math.min(block.getMaxAge(), block.getAge(crop) + 1)));
				}
			}
			case PLANT_SAPLING -> {
				if (isSoil(level.getBlockState(pos)) && level.getBlockState(pos.above()).canBeReplaced()) {
					level.setBlockAndUpdate(pos.above(), Blocks.OAK_SAPLING.defaultBlockState());
				}
			}
			case TEND_SAPLING -> {
				// Twice, as a sapling has two ages before it becomes a tree. One that has no
				// room to grow simply stays a sapling.
				for (int i = 0; i < 2; i++) {
					BlockState sapling = level.getBlockState(pos.above());
					if (sapling.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock block) {
						block.advanceTree(level, pos.above(), sapling, level.getRandom());
					}
				}
			}
			case CHOP -> {
				int logs = fellTree(level, pos.above());
				if (logs > 0) {
					// Replanting is part of felling: the grove renews itself.
					if (isSoil(level.getBlockState(pos))) {
						level.setBlockAndUpdate(pos.above(), Blocks.OAK_SAPLING.defaultBlockState());
					}
					return new Yield(Commodity.WOOD, logs * 4);
				}
			}
			case DIG_STEP -> {
				return digStep(level, site, pos);
			}
			case PROSPECT -> {
				return prospect(level, pos);
			}
			case QUARRY -> {
				return quarry(level, pos);
			}
			case DIG_GALLERY -> {
				return digGallery(level, pos);
			}
			case CLEAR_STEP -> {
				// What blocked the way is brought home like anything else dug: rock as stone, ore as ore.
				List<ItemStack> loot = new ArrayList<>();
				int[] won = clearHeadroom(level, pos, loot);
				return won[1] > 0 ? new Yield(Commodity.ORE, won[1], loot) : won[0] > 0 ? new Yield(Commodity.STONE, won[0], loot) : Yield.NONE;
			}
		}
		return Yield.NONE;
	}

	/** Clears natural blocks above the ground block and fills the ground in with dirt where it is missing. */
	static boolean levelColumn(ServerLevel level, BlockPos ground) {
		return levelColumn(level, ground, MAX_LEVEL_CHANGE, MAX_LEVEL_CHANGE);
	}

	/**
	 * @param cut  how far above the ground block the column is cleared
	 * @param fill how far below it a hollow is filled in
	 */
	static boolean levelColumn(ServerLevel level, BlockPos ground, int cut, int fill) {
		for (int dy = cut; dy >= 1; dy--) {
			BlockPos pos = ground.above(dy);
			BlockState state = level.getBlockState(pos);
			if (state.isAir()) {
				continue;
			}
			if (!isNatural(state)) {
				return false;
			}
			level.destroyBlock(pos, false);
		}
		for (int dy = 0; dy > -fill; dy--) {
			BlockPos pos = ground.below(-dy);
			BlockState state = level.getBlockState(pos);
			if (!state.isAir() && !state.canBeReplaced()) {
				break;
			}
			level.setBlockAndUpdate(pos, Blocks.DIRT.defaultBlockState());
		}
		return true;
	}

	/** Removes a tree's logs, starting at the trunk base. Leaves are left to decay on their own. */
	static int fellTree(ServerLevel level, BlockPos base) {
		return fellTree(level, base, _ -> true);
	}

	/**
	 * @param within which columns the felling may reach. Logs are logs: a trunk that touches the
	 *               corner post of a house would otherwise take the post with it.
	 */
	static int fellTree(ServerLevel level, BlockPos base, java.util.function.Predicate<BlockPos> within) {
		ArrayDeque<BlockPos> open = new ArrayDeque<>();
		Set<BlockPos> seen = new HashSet<>();
		open.add(base);
		seen.add(base);
		int logs = 0;
		while (!open.isEmpty() && logs < MAX_TREE_LOGS) {
			BlockPos pos = open.poll();
			if (!level.getBlockState(pos).typeHolder().is(BlockTags.LOGS)) {
				continue;
			}
			level.destroyBlock(pos, false);
			logs++;
			// A trunk only goes up and out, never down below where it was cut.
			for (BlockPos next : BlockPos.betweenClosed(pos.offset(-1, 0, -1), pos.offset(1, 1, 1))) {
				if (within.test(next) && seen.add(next.immutable())) {
					open.add(next.immutable());
				}
			}
		}
		return logs;
	}

	/** True if something that can be dug stands in the walking space above a stair step. */
	private static boolean isBlocked(ServerLevel level, BlockPos floor) {
		for (int dy = 1; dy <= 3; dy++) {
			BlockState state = level.getBlockState(floor.above(dy));
			if (!state.isAir() && !state.is(Blocks.TORCH) && state.getFluidState().isEmpty() && isNatural(state)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Opens the walking space above a stair step. Loose ground resting on it is taken out with
	 * it; left there, it would drop into the stair the moment its support went.
	 *
	 * @return {stone, ore} won
	 */
	private static int[] clearHeadroom(ServerLevel level, BlockPos floor, List<ItemStack> loot) {
		int top = 3;
		while (top < 3 + MAX_LOOSE_GROUND && level.getBlockState(floor.above(top + 1)).getBlock() instanceof FallingBlock) {
			top++;
		}
		int stone = 0;
		int ore = 0;
		for (int dy = top; dy >= 1; dy--) {
			BlockPos pos = floor.above(dy);
			BlockState state = level.getBlockState(pos);
			if (state.isAir() || state.is(Blocks.TORCH)) {
				continue;
			}
			if (isOre(state)) {
				ore++;
				loot.add(findOf(state));
			} else {
				// Everything else that comes out of a mine is worth carrying home too: stone of
				// every kind, gravel and the flint in it, earth, sand, clay.
				ItemStack spoil = spoilOf(level, state);
				if (!spoil.isEmpty()) {
					stone++;
					loot.add(spoil);
				}
			}
			level.destroyBlock(pos, false);
		}
		return new int[] {stone, ore};
	}

	private static Yield digStep(ServerLevel level, WorkSite site, BlockPos floor) {
		int step = site.progress() + 1;
		// The world may have changed since the step was chosen: water or lava may have found its way in.
		if (stairEnds(level, site)) {
			return Yield.NONE;
		}
		List<ItemStack> loot = new ArrayList<>();
		int[] won = clearHeadroom(level, floor, loot);
		int stone = won[0];
		int ore = won[1] + mineVein(level, floor, loot);
		BlockState ground = level.getBlockState(floor);
		if (ground.isAir() || ground.canBeReplaced()) {
			// The stair crossed a cave: lay a step so nobody falls.
			level.setBlockAndUpdate(floor, Blocks.COBBLESTONE.defaultBlockState());
		}
		if (step % TORCH_EVERY == 0) {
			level.setBlockAndUpdate(floor.above(), Blocks.TORCH.defaultBlockState());
		}
		site.setProgress(step);
		// One load is one commodity; ore is the better find, so it is what gets carried.
		return ore > 0 ? new Yield(Commodity.ORE, ore, loot) : stone > 0 ? new Yield(Commodity.STONE, stone, loot) : Yield.NONE;
	}

	private static Yield digGallery(ServerLevel level, BlockPos floor) {
		if (!canTunnel(level, floor)) {
			return Yield.NONE; // it changed since it was chosen; the next look ends the gallery here
		}
		List<ItemStack> loot = new ArrayList<>();
		int[] won = clearHeadroom(level, floor, loot);
		int ore = won[1] + mineVein(level, floor, loot);
		if (Math.floorMod(floor.getX() + floor.getZ(), TORCH_EVERY) == 0) {
			level.setBlockAndUpdate(floor.above(), Blocks.TORCH.defaultBlockState());
		}
		return ore > 0 ? new Yield(Commodity.ORE, ore, loot) : won[0] > 0 ? new Yield(Commodity.STONE, won[0], loot) : Yield.NONE;
	}

	/**
	 * Follows ore that the tunnel has laid bare. Any ore touching the walking space above this
	 * floor block is taken, and with it the ore that one touches, and so on through the vein, a
	 * short way into the rock. Ore with water or lava against it is left where it is. Where ore
	 * was underfoot, cobblestone goes back in its place, so the way stays whole.
	 *
	 * @return blocks of ore won
	 */
	private static int mineVein(ServerLevel level, BlockPos floor, List<ItemStack> loot) {
		ArrayDeque<BlockPos> open = new ArrayDeque<>();
		Set<BlockPos> vein = new HashSet<>();
		Set<BlockPos> seen = new HashSet<>();
		for (int dy = 1; dy <= 3; dy++) {
			BlockPos space = floor.above(dy);
			for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
				BlockPos beside = space.relative(side);
				if (seen.add(beside)) {
					open.add(beside);
				}
			}
		}
		while (!open.isEmpty() && vein.size() < MAX_VEIN_BLOCKS) {
			BlockPos pos = open.poll();
			if (!level.hasChunkAt(pos) || !isOre(level.getBlockState(pos)) || touchesFluid(level, pos)) {
				continue;
			}
			vein.add(pos);
			for (BlockPos next : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
				if (next.distManhattan(floor) <= VEIN_REACH && seen.add(next.immutable())) {
					open.add(next.immutable());
				}
			}
		}
		for (BlockPos pos : vein) {
			loot.add(findOf(level.getBlockState(pos)));
			BlockPos above = pos.above();
			BlockState over = level.getBlockState(above);
			boolean underfoot = !vein.contains(above) && (over.isAir() || over.is(Blocks.TORCH));
			if (underfoot) {
				level.setBlockAndUpdate(pos, Blocks.COBBLESTONE.defaultBlockState());
			} else {
				level.destroyBlock(pos, false);
			}
		}
		return vein.size();
	}

	/**
	 * What a miner keeps of a block that is not ore: the stone as it breaks (plain stone to
	 * cobble, deepslate to cobbled deepslate), every other kind of rock as it is, gravel (now and
	 * then with a flint in it), earth, sand and clay. Nothing for what is not worth the carrying.
	 */
	private static ItemStack spoilOf(ServerLevel level, BlockState state) {
		if (state.is(Blocks.STONE)) {
			return new ItemStack(Items.COBBLESTONE);
		}
		if (state.is(Blocks.DEEPSLATE)) {
			return new ItemStack(Items.COBBLED_DEEPSLATE);
		}
		if (state.is(Blocks.GRAVEL)) {
			return new ItemStack(level.getRandom().nextInt(10) == 0 ? Items.FLINT : Items.GRAVEL);
		}
		if (state.is(Blocks.CLAY)) {
			return new ItemStack(Items.CLAY_BALL, 4);
		}
		if (isSoil(state)) {
			return new ItemStack(Items.DIRT);
		}
		if (isRock(state) || state.typeHolder().is(BlockTags.SAND) || state.is(Blocks.CALCITE) || state.is(Blocks.DRIPSTONE_BLOCK)
				|| state.is(Blocks.SMOOTH_BASALT) || state.is(Blocks.AMETHYST_BLOCK)) {
			return new ItemStack(state.getBlock().asItem());
		}
		return ItemStack.EMPTY;
	}

	/** What a block of ore gives up: the same as it would to a player's pick, near enough. */
	private static ItemStack findOf(BlockState ore) {
		String name = ore.typeHolder().unwrapKey().map(key -> key.identifier().getPath()).orElse("");
		if (name.contains("coal")) {
			return new ItemStack(Items.COAL);
		}
		if (name.contains("copper")) {
			return new ItemStack(Items.RAW_COPPER, 2);
		}
		if (name.contains("gold")) {
			return new ItemStack(Items.RAW_GOLD);
		}
		if (name.contains("diamond")) {
			return new ItemStack(Items.DIAMOND);
		}
		if (name.contains("emerald")) {
			return new ItemStack(Items.EMERALD);
		}
		if (name.contains("redstone")) {
			return new ItemStack(Items.REDSTONE, 4);
		}
		if (name.contains("lapis")) {
			return new ItemStack(Items.LAPIS_LAZULI, 4);
		}
		return new ItemStack(Items.RAW_IRON);
	}

	private static boolean touchesFluid(ServerLevel level, BlockPos pos) {
		for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
			if (isWet(level, pos.relative(side))) {
				return true;
			}
		}
		return false;
	}

	/** Plain ground: soil, rock, sand or gravel. What a hollow is rimmed by, and what may be buried. */
	static boolean isGround(BlockState state) {
		return isSoil(state) || isRock(state) || state.typeHolder().is(BlockTags.SAND) || state.is(Blocks.GRAVEL);
	}

	static boolean isSoil(BlockState state) {
		Holder<Block> block = state.typeHolder();
		return block.is(BlockTags.DIRT) || block.is(BlockTags.GRASS_BLOCKS);
	}

	private static boolean isRock(BlockState state) {
		return state.typeHolder().is(BlockTags.BASE_STONE_OVERWORLD) || state.is(Blocks.COBBLESTONE);
	}

	private static boolean isOre(BlockState state) {
		return state.typeHolder().unwrapKey().map(key -> key.identifier().getPath().endsWith("_ore")).orElse(false);
	}

	/**
	 * Ground and growth the land came with. Everything else was put there by someone, and the
	 * settlement does not touch what it did not make.
	 */
	static boolean isNatural(BlockState state) {
		Holder<Block> block = state.typeHolder();
		return isSoil(state) || isRock(state) || isOre(state) || state.canBeReplaced()
				|| block.is(BlockTags.SAND) || block.is(BlockTags.LEAVES) || block.is(BlockTags.SNOW) || block.is(BlockTags.FLOWERS)
				|| state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.FARMLAND) || state.is(Blocks.TORCH);
	}
}
