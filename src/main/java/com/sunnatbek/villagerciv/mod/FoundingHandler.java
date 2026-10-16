package com.sunnatbek.villagerciv.mod;

import com.sunnatbek.villagerciv.mod.net.SiteReportPayload;
import com.sunnatbek.villagerciv.scheduler.TaskPriority;
import com.sunnatbek.villagerciv.scheduler.TaskResult;
import com.sunnatbek.villagerciv.sim.Citizen;
import com.sunnatbek.villagerciv.sim.Expedition;
import com.sunnatbek.villagerciv.build.ShapedWall;
import com.sunnatbek.villagerciv.sim.Founding;
import com.sunnatbek.villagerciv.sim.Role;
import com.sunnatbek.villagerciv.sim.Settlement;
import com.sunnatbek.villagerciv.sim.Simulation;
import com.sunnatbek.villagerciv.sim.SiteSurvey;
import com.sunnatbek.villagerciv.sim.TerrainSnapshot;
import com.sunnatbek.villagerciv.sim.TerrainSnapshot.Surface;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Founding a settlement with the Founding Charter.
 *
 * <p>The player proposes a spot; the Leader surveys it and reports; the player confirms or looks
 * elsewhere. The survey reads about ten thousand columns, so it is taken as a snapshot over
 * several ticks inside the scheduler's budget, analysed on the mod's worker thread, and only the
 * result comes back to the game thread. The world itself is never touched off-thread.
 */
final class FoundingHandler {
	/** Columns copied between two looks at the tick budget. */
	private static final int SNAPSHOT_BATCH = 32;
	/** Vanilla villages keep their own ground: sections (16 blocks) of distance required. */
	private static final int VILLAGE_CLEARANCE_SECTIONS = 3;
	private static final long PROPOSAL_LIFETIME_TICKS = 20 * 60 * 5;
	private static final double MAX_CONFIRM_DISTANCE_SQR = 32.0 * 32.0;

	private record Proposal(BlockPos site, SiteSurvey.Result survey, long expiresAtTick) {
	}

	private final MinecraftServer server;
	private final Simulation simulation;
	private final EmbodimentController embodiment;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "villagerciv-worker");
		thread.setDaemon(true);
		// Analysis must never compete with rendering or chunk generation for a core.
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});
	private final Map<UUID, BlockPos> surveying = new HashMap<>();
	private final java.util.Set<com.sunnatbek.villagerciv.api.SettlementId> expanding = new java.util.HashSet<>();
	private final Map<UUID, Proposal> proposals = new HashMap<>();

	FoundingHandler(MinecraftServer server, Simulation simulation, EmbodimentController embodiment) {
		this.server = server;
		this.simulation = simulation;
		this.embodiment = embodiment;
	}

	void shutdown() {
		worker.shutdownNow();
	}

	/** Charter used on a block: check the hard conditions, then let the Leader survey the place. */
	void propose(ServerPlayer player, BlockPos clicked, Direction face) {
		ServerLevel level = player.level();
		if (level.dimension() != Level.OVERWORLD) {
			fail(player, Component.translatable("message.villagerciv.found.wrong_dimension"));
			return;
		}
		BlockPos site = level.getBlockState(clicked).canBeReplaced() ? clicked : clicked.relative(face);
		Component refusal = refusalMessage(simulation.canFound(player.getUUID(), site.getX(), site.getZ()));
		if (refusal != null) {
			fail(player, refusal);
			return;
		}
		if (level.isCloseToVillage(site, VILLAGE_CLEARANCE_SECTIONS)) {
			fail(player, Component.translatable("message.villagerciv.found.near_village"));
			return;
		}
		if (surveying.putIfAbsent(player.getUUID(), site) != null) {
			return; // one survey at a time
		}
		proposals.remove(player.getUUID());
		player.sendSystemMessage(Component.translatable("message.villagerciv.found.surveying", leaderName(player)), true);

		TerrainSnapshot snapshot = new TerrainSnapshot(site.getX(), site.getZ(), Founding.SURVEY_RADIUS);
		UUID playerId = player.getUUID();
		int[] next = {0};
		simulation.scheduler().submit("site-survey", TaskPriority.SETTLEMENT_AI, context -> {
			BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
			while (next[0] < snapshot.columnCount()) {
				int end = Math.min(next[0] + SNAPSHOT_BATCH, snapshot.columnCount());
				for (; next[0] < end; next[0]++) {
					copyColumn(level, snapshot, next[0], cursor);
				}
				if (!context.hasBudget()) {
					return TaskResult.CONTINUE;
				}
			}
			worker.execute(() -> {
				SiteSurvey.Result result = SiteSurvey.analyse(snapshot);
				server.execute(() -> report(playerId, site, result));
			});
			return TaskResult.DONE;
		});
	}

	/**
	 * The patron offers a town its Expansion Charter. If the town may take it, the land out to
	 * the new bounds is surveyed (over several ticks, like a founding survey) and the settlement
	 * is then enlarged; the charter is used up when that is done.
	 */
	void proposeExpansion(ServerPlayer player, Settlement settlement, ItemStack stack) {
		String leader = leaderOf(settlement);
		boolean grand = stack.is(ModContent.GRAND_CHARTER);
		boolean byGrandCharter = grand;
		if (!grand && settlement.expansion() == 1 && settlement.mayExpandAgain()) {
			// A city with its wall finished is ready to grow again, and a charter that names an
			// outline is leave enough: it grows on it now, into a great wall of that outline.
			settlement.setPlannedShape(ModContent.shapeOf(stack));
			grand = true;
		}
		if (!grand && settlement.expansion() == 1) {
			// A town that is not ready to grow again keeps the charter for the day it is: the
			// great wall it builds as a city will have the outline the charter names.
			ShapedWall.Shape chosen = ModContent.shapeOf(stack);
			String outline = "shape.villagerciv." + chosen.name().toLowerCase(java.util.Locale.ROOT);
			if (chosen == settlement.plannedShape() || (settlement.plannedShape() == null && chosen == settlement.wallShape())) {
				player.sendSystemMessage(Component.translatable("message.villagerciv.expand.shape_same", leader, Component.translatable(outline)), true);
				return;
			}
			settlement.setPlannedShape(chosen);
			stack.consume(1, player);
			player.sendSystemMessage(Component.translatable("message.villagerciv.expand.shape_kept", leader, Component.translatable(outline)));
			return;
		}
		if (grand ? settlement.expansion() >= 2 : settlement.expansion() > 0) {
			player.sendSystemMessage(Component.translatable("message.villagerciv.expand.already", leader), true);
			return;
		}
		if (grand ? !settlement.mayExpandAgain() : !settlement.mayExpand()) {
			player.sendSystemMessage(Component.translatable(grand ? "message.villagerciv.expand.not_a_city" : "message.villagerciv.expand.too_small", leader), true);
			return;
		}
		if (!expanding.add(settlement.id())) {
			player.sendSystemMessage(Component.translatable("message.villagerciv.expand.busy"), true);
			return;
		}
		// The Grand Charter keeps the outline the town has, unless another was chosen for it.
		ShapedWall.Shape shape = !grand ? ModContent.shapeOf(stack) : settlement.plannedShape() != null ? settlement.plannedShape() : settlement.wallShape();
		boolean great = grand;
		stack.consume(1, player);
		player.sendSystemMessage(Component.translatable("message.villagerciv.expand.surveying", leader));
		UUID playerId = player.getUUID();
		expand(settlement, shape, () -> {
			ServerPlayer online = server.getPlayerList().getPlayer(playerId);
			if (online != null) {
				online.sendSystemMessage(Component.translatable(settlement.wallPieces() == 0 ? "message.villagerciv.expand.done.open"
						: great ? "message.villagerciv.expand.done.grand" : "message.villagerciv.expand.done", leader));
			}
		});
	}

	/**
	 * A town that was handed a charter before it was ready keeps it, and grows on it by itself
	 * the day it is a city with its wall finished. Called now and then; does nothing otherwise.
	 */
	void growOnKeptCharter(Settlement settlement) {
		if (settlement.plannedShape() == null || !settlement.mayExpandAgain() || expanding.contains(settlement.id())) {
			return;
		}
		ShapedWall.Shape shape = settlement.plannedShape();
		String leader = leaderOf(settlement);
		UUID founder = settlement.founder();
		expand(settlement, shape, () -> {
			ServerPlayer online = server.getPlayerList().getPlayer(founder);
			if (online != null) {
				online.sendSystemMessage(Component.translatable("message.villagerciv.expand.done.grand", leader));
			}
		});
	}

	/** Surveys the wider land round a settlement and enlarges it, in a square; {@code then} runs on the server thread afterwards. */
	void expand(Settlement settlement, Runnable then) {
		expand(settlement, ShapedWall.Shape.SQUARE, then);
	}

	/** The same, with the outline the new wall is to have. */
	void expand(Settlement settlement, ShapedWall.Shape shape, Runnable then) {
		expanding.add(settlement.id());
		ServerLevel level = server.overworld();
		TerrainSnapshot snapshot = new TerrainSnapshot(settlement.anchorX(), settlement.anchorZ(), Founding.expandedRadius(settlement.expansion() + 1));
		int[] next = {0};
		simulation.scheduler().submit("expansion-survey", TaskPriority.SETTLEMENT_AI, context -> {
			BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
			while (next[0] < snapshot.columnCount()) {
				int end = Math.min(next[0] + SNAPSHOT_BATCH, snapshot.columnCount());
				for (; next[0] < end; next[0]++) {
					int x = snapshot.blockX(next[0]);
					int z = snapshot.blockZ(next[0]);
					// Land that far out is seldom all loaded: what cannot be seen is taken as
					// ground to build on, and looked over again when the settlement gets there.
					if (!level.hasChunk(x >> 4, z >> 4)) {
						snapshot.set(next[0], settlement.grade(), Surface.SOIL);
					} else {
						copyColumn(level, snapshot, next[0], cursor);
					}
				}
				if (!context.hasBudget()) {
					return TaskResult.CONTINUE;
				}
			}
			worker.execute(() -> {
				SiteSurvey.Result result = SiteSurvey.analyse(snapshot);
				server.execute(() -> {
					expanding.remove(settlement.id());
					simulation.expand(settlement, result.plots(), shape);
					then.run();
				});
			});
			return TaskResult.DONE;
		});
	}

	/**
	 * Looks over a settlement's land again, as it is now. The first survey saw the land as it
	 * lay: hills, hollows, ground too far off to be loaded. Since then the levellers have been
	 * at it, and what was too rough to build on may be good ground. Runs in the background, like
	 * the first; nothing but the settlement's map of buildable ground changes.
	 */
	void resurvey(Settlement settlement) {
		if (!expanding.add(settlement.id())) {
			return;
		}
		ServerLevel level = server.overworld();
		TerrainSnapshot snapshot = new TerrainSnapshot(settlement.anchorX(), settlement.anchorZ(),
				settlement.expansion() > 0 ? Founding.expandedRadius(settlement.expansion()) : Founding.SURVEY_RADIUS);
		int[] next = {0};
		simulation.scheduler().submit("land-resurvey", TaskPriority.BACKGROUND, context -> {
			BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
			while (next[0] < snapshot.columnCount()) {
				int end = Math.min(next[0] + SNAPSHOT_BATCH, snapshot.columnCount());
				for (; next[0] < end; next[0]++) {
					int x = snapshot.blockX(next[0]);
					int z = snapshot.blockZ(next[0]);
					// Ground that was buildable and cannot be seen just now is not written off.
					if (!level.hasChunk(x >> 4, z >> 4) && settlement.plots().isBuildable(x, z)) {
						snapshot.set(next[0], settlement.grade(), Surface.SOIL);
					} else {
						copyColumn(level, snapshot, next[0], cursor);
					}
				}
				if (!context.hasBudget()) {
					return TaskResult.CONTINUE;
				}
			}
			worker.execute(() -> {
				SiteSurvey.Result result = SiteSurvey.analyse(snapshot);
				server.execute(() -> {
					expanding.remove(settlement.id());
					int before = settlement.plots().buildableCount();
					settlement.setSurvey(settlement.siteScore(), result.plots());
					VillagerCivMod.LOGGER.info("{} looked over its land again: {} buildable columns, {} before",
							settlement.name(), result.plots().buildableCount(), before);
				});
			});
			return TaskResult.DONE;
		});
	}

	private static String leaderOf(Settlement settlement) {
		for (com.sunnatbek.villagerciv.sim.Citizen citizen : settlement.members()) {
			if (citizen.role() == com.sunnatbek.villagerciv.sim.Role.LEADER) {
				return citizen.name();
			}
		}
		return "The Leader";
	}

	private static void copyColumn(ServerLevel level, TerrainSnapshot snapshot, int index, BlockPos.MutableBlockPos cursor) {
		int x = snapshot.blockX(index);
		int z = snapshot.blockZ(index);
		// Never load a chunk for a survey: what is not loaded stays unknown.
		if (!level.hasChunk(x >> 4, z >> 4)) {
			snapshot.set(index, 0, Surface.UNKNOWN);
			return;
		}
		int top = com.sunnatbek.villagerciv.mod.Heights.top(level, Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
		BlockState state = level.getBlockState(cursor.set(x, top, z));
		// A street lamp is not a building: the ground it stands on is what counts.
		for (int down = 0; down < 4 && Lamplighter.isLampPart(state); down++) {
			top--;
			state = level.getBlockState(cursor.set(x, top, z));
		}
		Surface surface = classify(state);
		// Under a tree the ground level is what matters, not the canopy.
		int height = surface == Surface.TREE ? com.sunnatbek.villagerciv.mod.Heights.top(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1 : top;
		snapshot.set(index, height, surface);
	}

	private static Surface classify(BlockState state) {
		FluidState fluid = state.getFluidState();
		if (fluid.typeHolder().is(FluidTags.WATER)) {
			return Surface.WATER;
		}
		if (fluid.typeHolder().is(FluidTags.LAVA)) {
			return Surface.LAVA;
		}
		var block = state.typeHolder();
		if (block.is(BlockTags.LEAVES) || block.is(BlockTags.LOGS)) {
			return Surface.TREE;
		}
		if (block.is(BlockTags.DIRT) || block.is(BlockTags.GRASS_BLOCKS)) {
			return Surface.SOIL;
		}
		if (block.is(BlockTags.SAND)) {
			return Surface.SAND;
		}
		if (block.is(BlockTags.BASE_STONE_OVERWORLD) || block.is(BlockTags.IRON_ORES) || block.is(BlockTags.COPPER_ORES)
				|| state.is(Blocks.COBBLESTONE) || state.is(Blocks.GRAVEL) || state.is(Blocks.COAL_ORE)) {
			return Surface.STONE;
		}
		if (block.is(BlockTags.SNOW) || block.is(BlockTags.ICE)) {
			return Surface.SNOW;
		}
		return Surface.OTHER;
	}

	/** Back on the game thread with the finished survey: show it to the player. */
	private void report(UUID playerId, BlockPos site, SiteSurvey.Result result) {
		surveying.remove(playerId);
		ServerPlayer player = server.getPlayerList().getPlayer(playerId);
		if (player == null) {
			return;
		}
		proposals.put(playerId, new Proposal(site, result, simulation.gameTick() + PROPOSAL_LIFETIME_TICKS));
		ServerPlayNetworking.send(player, new SiteReportPayload(site, leaderName(player), result.score()));
	}

	/** The player accepted the surveyed place. Everything is checked again before anything changes. */
	void confirm(ServerPlayer player, BlockPos site) {
		Proposal proposal = proposals.get(player.getUUID());
		if (proposal == null || !proposal.site().equals(site) || simulation.gameTick() > proposal.expiresAtTick()) {
			fail(player, Component.translatable("message.villagerciv.found.expired"));
			return;
		}
		ServerLevel level = player.level();
		ItemStack charter = player.getMainHandItem().is(ModContent.FOUNDING_CHARTER) ? player.getMainHandItem() : player.getOffhandItem();
		if (!charter.is(ModContent.FOUNDING_CHARTER) || level.dimension() != Level.OVERWORLD
				|| player.distanceToSqr(site.getX() + 0.5, site.getY(), site.getZ() + 0.5) > MAX_CONFIRM_DISTANCE_SQR) {
			return;
		}
		Component refusal = refusalMessage(simulation.canFound(player.getUUID(), site.getX(), site.getZ()));
		if (refusal != null) {
			fail(player, refusal);
			return;
		}
		if (!level.getBlockState(site).canBeReplaced()) {
			fail(player, Component.translatable("message.villagerciv.found.blocked"));
			return;
		}
		Settlement settlement = simulation.found(player.getUUID(), site.getX(), site.getY(), site.getZ(), proposal.survey()).orElse(null);
		if (settlement == null) {
			return;
		}
		proposals.remove(player.getUUID());
		charter.consume(1, player);
		level.setBlockAndUpdate(site, ModContent.TOWN_CENTER.defaultBlockState());
		// Handoff: the founders stop following and belong to the settlement from now on.
		embodiment.adopt(settlement);

		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, site.getX() + 0.5, site.getY() + 1.2, site.getZ() + 0.5, 30, 1.5, 0.8, 1.5, 0.0);
		level.playSound(null, site, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 1.5f, 1.0f);
		player.sendSystemMessage(Component.translatable("message.villagerciv.found.founded", settlement.name()));
		player.sendSystemMessage(Component.translatable("message.villagerciv.found.patron"));
	}

	/**
	 * The Town Center block is only a marker for the settlement record, so losing the block loses
	 * nothing: the settlers simply raise it again on the same spot. If something solid was put
	 * there instead, they wait until the spot is free rather than destroy what is not theirs.
	 */
	void maintainTownCenters() {
		ServerLevel level = server.overworld();
		for (Settlement settlement : simulation.state().settlements().all()) {
			BlockPos anchor = new BlockPos(settlement.anchorX(), settlement.anchorY(), settlement.anchorZ());
			if (!level.isLoaded(anchor)) {
				continue;
			}
			BlockState state = level.getBlockState(anchor);
			if (state.is(ModContent.TOWN_CENTER) || !state.canBeReplaced()) {
				continue;
			}
			level.setBlockAndUpdate(anchor, ModContent.TOWN_CENTER.defaultBlockState());
			level.playSound(null, anchor, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1.0f, 0.8f);
			ServerPlayer founder = server.getPlayerList().getPlayer(settlement.founder());
			if (founder != null) {
				founder.sendSystemMessage(Component.translatable("message.villagerciv.settlement.town_center_rebuilt", settlement.name()));
			}
		}
	}

	private String leaderName(ServerPlayer player) {
		Expedition expedition = simulation.expeditionOf(player.getUUID()).orElse(null);
		if (expedition != null) {
			for (Citizen citizen : expedition.members()) {
				if (citizen.role() == Role.LEADER) {
					return citizen.name();
				}
			}
		}
		return "The Leader";
	}

	private static Component refusalMessage(Founding.Refusal refusal) {
		return switch (refusal) {
			case NONE -> null;
			case NO_EXPEDITION -> Component.translatable("message.villagerciv.found.no_expedition");
			case TOO_FEW_FOUNDERS -> Component.translatable("message.villagerciv.found.too_few", Founding.MIN_FOUNDERS);
			case NOT_TRAVELLING -> Component.translatable("message.villagerciv.found.not_travelling");
			case TERRITORY_TAKEN -> Component.translatable("message.villagerciv.found.territory_taken");
			case BAD_SITE -> Component.translatable("message.villagerciv.found.blocked");
		};
	}

	private static void fail(ServerPlayer player, Component message) {
		player.sendSystemMessage(message, true);
	}
}
