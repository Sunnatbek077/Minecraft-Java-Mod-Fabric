package com.sunnatbek.villagerciv.mod;

import com.sunnatbek.villagerciv.api.CitizenBornEvent;
import com.sunnatbek.villagerciv.api.CitizenId;
import com.sunnatbek.villagerciv.api.SettlementStageEvent;
import com.sunnatbek.villagerciv.api.CitizenLeftEvent;
import com.sunnatbek.villagerciv.api.RequestEvent;
import com.sunnatbek.villagerciv.api.ExpeditionHungryEvent;
import com.sunnatbek.villagerciv.mod.net.ExpeditionInfoPayload;
import com.sunnatbek.villagerciv.mod.entity.CitizenEntity;
import com.sunnatbek.villagerciv.mod.net.SettlementInfoPayload;
import com.sunnatbek.villagerciv.build.Blueprint;
import com.sunnatbek.villagerciv.build.Building;
import com.sunnatbek.villagerciv.build.Construction;
import com.sunnatbek.villagerciv.sim.Activity;
import com.sunnatbek.villagerciv.scheduler.TickContext;
import com.sunnatbek.villagerciv.sim.ChunkKey;
import com.sunnatbek.villagerciv.sim.Citizen;
import com.sunnatbek.villagerciv.sim.CitizenGroup;
import com.sunnatbek.villagerciv.sim.Commodity;
import com.sunnatbek.villagerciv.sim.Settlement;
import com.sunnatbek.villagerciv.sim.Expedition;
import com.sunnatbek.villagerciv.sim.ExpeditionUpkeep;
import com.sunnatbek.villagerciv.sim.Policy;
import com.sunnatbek.villagerciv.sim.Request;
import com.sunnatbek.villagerciv.sim.SiteScore;
import com.sunnatbek.villagerciv.sim.Role;
import com.sunnatbek.villagerciv.sim.Simulation;
import com.sunnatbek.villagerciv.sim.ToolKind;
import com.sunnatbek.villagerciv.sim.WorkSite;
import com.sunnatbek.villagerciv.sim.Tools;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides which citizen records get a body, and moves state between record and entity.
 *
 * <p>Single ownership is the rule: a record has at most one body, and this class is the only
 * place that creates one. Losing a body (chunk unload, logout, crash) loses nothing, because
 * bodies are never saved and records are updated while a body lives.
 */
public final class EmbodimentController {
	/** A camped expedition resumes travelling when its player comes back within this range. */
	private static final double REJOIN_DISTANCE_SQR = 64.0 * 64.0;
	/** A player this near a settlement's Town Center is in the settlement, wherever in it they stand. */
	private static final double SETTLEMENT_DISTANCE_SQR = 110.0 * 110.0;
	/** A member left further behind than this rejoins next to the player instead of where it stood. */
	private static final double LEFT_BEHIND_DISTANCE_SQR = 48.0 * 48.0;

	/** How often {@link #update} runs. Short, so a group still appears within a second or two. */
	static final int UPDATE_INTERVAL_TICKS = 5;
	private static final int MAX_SPAWNS_PER_UPDATE = 2;
	private static final int MAX_PATHS_PER_TICK = 2;
	private static final int MAX_DEFERRED_EDITS_PER_RUN = 48;
	private static final int CLAIM_RESET_UPDATES = 240;

	private final MinecraftServer server;
	private final Simulation simulation;
	private final Map<CitizenId, CitizenEntity> bodies = new HashMap<>();
	private final BuildSites buildSites;
	private final Warehouse warehouse = new Warehouse(this);
	private final Landscaper landscaper = new Landscaper();
	private static final long RESURVEY_TICKS = 6_000;
	private static final int MAX_DEMOLITIONS_PER_RUN = 2;
	private final Map<com.sunnatbek.villagerciv.api.SettlementId, Long> resurveyedAt = new HashMap<>();
	private final Lamplighter lamplighter = new Lamplighter();
	private final Set<Long> minesLookedAt = new java.util.HashSet<>();
	private int spawnsLeft;
	// Blocks a worker is on its way to or busy with, so two do not go for the same one.
	private final Set<Long> claimedBlocks = new HashSet<>();
	private int updatesSinceClaimReset;
	private int pathSlotTick;
	private int pathSlotsLeft;

	EmbodimentController(MinecraftServer server, Simulation simulation) {
		this.server = server;
		this.simulation = simulation;
		this.buildSites = new BuildSites(simulation.blueprints());
		buildSites.validateBlueprints();
	}

	public BuildSites buildSites() {
		return buildSites;
	}

	/**
	 * The deferred world-edit queue at work. Building goes on while nobody is near, as numbers
	 * only; here the world is brought up to date with it once its chunks are loaded, a few blocks
	 * at a time and only while the tick budget lasts.
	 */
	void applyDeferredEdits(TickContext context) {
		ServerLevel level = server.overworld();
		if (level.players().isEmpty()) {
			return;
		}
		int applied = 0;
		for (Settlement settlement : simulation.state().settlements().all()) {
			// What has been condemned comes down, a few buildings at a time.
			List<Building> gone = new ArrayList<>();
			for (Building building : settlement.buildings()) {
				if (building.stage() == Building.Stage.CONDEMNED && gone.size() < MAX_DEMOLITIONS_PER_RUN
						&& buildSites.demolish(level, settlement, building)) {
					gone.add(building);
				}
			}
			gone.forEach(settlement::strike);
			for (Building building : settlement.buildings()) {
				if (building.stage() == Building.Stage.CONDEMNED) {
					continue;
				}
				buildSites.checkDesign(settlement, building);
				buildSites.bringToGrade(level, settlement, building, goods -> warehouse.deposit(level, settlement, goods));
				while (building.applied() < building.progress() && applied < MAX_DEFERRED_EDITS_PER_RUN) {
					if (buildSites.applyNext(level, settlement, building) == BuildSites.Outcome.NOT_NOW) {
						break;
					}
					applied++;
					if ((applied & 7) == 0 && !context.hasBudget()) {
						return;
					}
				}
			}
		}
	}

	private static final int INTRUDER_LOOK_TICKS = 20;
	private final Map<com.sunnatbek.villagerciv.api.SettlementId, List<net.minecraft.world.entity.monster.Monster>> intruders = new HashMap<>();
	private final Map<com.sunnatbek.villagerciv.api.SettlementId, Long> intrudersSeenAt = new HashMap<>();

	/**
	 * The monster on a settlement's ground that is nearest to one of its guards, or null if the
	 * ground is clear. The whole of the ground inside the wall is watched, not just what the
	 * guard can see: word of something coming over the wall at the far end gets round. What is
	 * deep below ground, in caves and mines, is not the patrol's to chase.
	 *
	 * @param notCreepers leave creepers out: nothing to go at with a sword
	 */
	public net.minecraft.world.entity.monster.Monster intruder(ServerLevel level, Settlement settlement, net.minecraft.world.entity.Entity guard, boolean notCreepers) {
		long now = gameTick();
		if (now - intrudersSeenAt.getOrDefault(settlement.id(), -1L - INTRUDER_LOOK_TICKS) >= INTRUDER_LOOK_TICKS) {
			intrudersSeenAt.put(settlement.id(), now);
			int[] bounds = settlement.groundBounds();
			int grade = settlement.grade();
			intruders.put(settlement.id(), level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,
					// Up to the wall and no further: what stands outside it is for the guards on the wall.
					new net.minecraft.world.phys.AABB(bounds[0] - 3, grade - 4, bounds[1] - 3, bounds[2] + 4, grade + 30, bounds[3] + 4),
					monster -> monster.isAlive()));
		}
		net.minecraft.world.entity.monster.Monster nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (net.minecraft.world.entity.monster.Monster monster : intruders.getOrDefault(settlement.id(), List.of())) {
			if (!monster.isAlive() || (notCreepers && monster instanceof net.minecraft.world.entity.monster.Creeper)
					|| now < outOfReachUntil.getOrDefault(monster.getId(), 0L) || answering(monster, guard, now) >= GUARDS_PER_INTRUDER) {
				continue;
			}
			double distance = monster.distanceToSqr(guard);
			if (distance < nearestDistance) {
				nearest = monster;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	/** So many guards go for one monster; the rest keep to their rounds. */
	private static final int GUARDS_PER_INTRUDER = 4;
	private static final long ANSWER_LAPSE_TICKS = 60;
	private final Map<Integer, Map<Integer, Long>> answering = new HashMap<>();

	/** A patrol says it is still after a monster. Said every second or so; a guard that stops saying it no longer counts. */
	public void answer(net.minecraft.world.entity.monster.Monster monster, net.minecraft.world.entity.Entity guard) {
		if (answering.size() > 256) {
			answering.keySet().removeIf(id -> server.overworld().getEntity(id) == null);
		}
		answering.computeIfAbsent(monster.getId(), _ -> new HashMap<>()).put(guard.getId(), gameTick());
	}

	/** How many guards other than this one are after a monster. */
	private int answering(net.minecraft.world.entity.monster.Monster monster, net.minecraft.world.entity.Entity guard, long now) {
		Map<Integer, Long> guards = answering.get(monster.getId());
		if (guards == null) {
			return 0;
		}
		guards.values().removeIf(since -> now - since > ANSWER_LAPSE_TICKS);
		return guards.size() - (guards.containsKey(guard.getId()) ? 1 : 0);
	}

	private static final long OUT_OF_REACH_TICKS = 2_400;
	private final Map<Integer, Long> outOfReachUntil = new HashMap<>();

	/**
	 * A patrol could not get at a monster: it is walled in somewhere, or up where nobody can
	 * climb. It is left alone for a while, so that the whole watch does not stand looking at it
	 * while something else comes over the wall.
	 */
	public void outOfReach(net.minecraft.world.entity.monster.Monster monster) {
		if (outOfReachUntil.size() > 256) {
			outOfReachUntil.values().removeIf(until -> until < gameTick());
		}
		outOfReachUntil.put(monster.getId(), gameTick() + OUT_OF_REACH_TICKS);
	}

	/** How many citizens stand all but inside another one, for the reports: none, if they make room for each other. */
	int overlapping() {
		List<CitizenEntity> all = new ArrayList<>(bodies.values());
		int overlapping = 0;
		for (int i = 0; i < all.size(); i++) {
			for (int j = 0; j < all.size(); j++) {
				if (i != j && !all.get(i).isSleeping() && all.get(i).distanceToSqr(all.get(j)) < 0.3 * 0.3) {
					overlapping++;
					if (System.getProperty("villagerciv.autotest") != null && all.get(i).citizen() != null) {
						VillagerCivMod.LOGGER.info("AUTOTEST     inside another: {} job={} post={} patrol={} doing={}", all.get(i).citizen().name(),
								all.get(i).citizen().job(), all.get(i).citizen().post(), all.get(i).citizen().patrol(), all.get(i).citizen().activity());
					}
					break;
				}
			}
		}
		return overlapping;
	}

	/** How many monsters were on a settlement's ground at the last look, for the reports. */
	int intruderCount(Settlement settlement) {
		return (int) intruders.getOrDefault(settlement.id(), List.of()).stream().filter(monster -> monster.isAlive()).count();
	}

	public Landscaper landscaper() {
		return landscaper;
	}

	public Lamplighter lamplighter() {
		return lamplighter;
	}

	/** The ground of each settlement is looked over, a patch at a time, for holes to fill. */
	void scanGround() {
		ServerLevel level = server.overworld();
		if (level.players().isEmpty()) {
			return;
		}
		releaseStranded();
		for (Settlement settlement : simulation.state().settlements().all()) {
			// A settlement with no room left has its land looked over again now and then: what
			// the levellers have made of it since may be ground to build on.
			if (settlement.isOutOfRoom() && VillagerCivMod.founding() != null
					&& gameTick() - resurveyedAt.getOrDefault(settlement.id(), -RESURVEY_TICKS) >= RESURVEY_TICKS) {
				resurveyedAt.put(settlement.id(), gameTick());
				VillagerCivMod.founding().resurvey(settlement);
			}
			if (VillagerCivMod.founding() != null) {
				VillagerCivMod.founding().growOnKeptCharter(settlement);
			}
			// How many enchantments there are is the game's to say; the settlement wants a librarian for each.
			settlement.setStudies(Librarians.all(level).size());
			landscaper.scan(level, settlement);
			lamplighter.scan(level, settlement);
			for (WorkSite site : settlement.sites()) {
				long key = site.id() * 31L + settlement.anchorX();
				if (site.isExhausted() && !minesLookedAt.contains(key) && WorkSites.reopenIfWorkable(level, site)) {
					minesLookedAt.add(key);
				}
			}
		}
	}

	/** Goods carried in by a citizen go into the storehouse, or straight into the books where there is none. */
	public void deposit(ServerLevel level, Settlement settlement, List<ItemStack> stacks) {
		warehouse.deposit(level, settlement, stacks);
	}

	/** Storehouse chests and ledgers are compared, and brought back into step. */
	void syncWarehouses() {
		ServerLevel level = server.overworld();
		if (level.players().isEmpty()) {
			return;
		}
		for (Settlement settlement : simulation.state().settlements().all()) {
			warehouse.sync(level, settlement);
		}
	}

	/** A player broke a block: if it belonged to a finished building, the settlers put it right. */
	void onBlockBroken(BlockPos pos) {
		simulation.state().territoryOwner(ChunkKey.ofBlock(pos.getX(), pos.getZ())).ifPresent(settlement -> {
			for (Building building : settlement.buildings()) {
				if (building.contains(pos.getX(), pos.getZ())) {
					buildSites.repair(settlement, building);
				}
			}
		});
	}

	public Set<Long> claimedBlocks() {
		return claimedBlocks;
	}

	public long gameTick() {
		return simulation.gameTick();
	}

	/**
	 * Asks for one of this tick's path searches. Path finding is the most expensive thing a
	 * citizen does, so only a few may start per tick; the others simply ask again next tick.
	 */
	public boolean takePathSlot() {
		int tick = server.getTickCount();
		if (tick != pathSlotTick) {
			pathSlotTick = tick;
			pathSlotsLeft = MAX_PATHS_PER_TICK;
		}
		if (pathSlotsLeft <= 0) {
			return false;
		}
		pathSlotsLeft--;
		return true;
	}

	/** Periodic pass over all expeditions; runs inside the scheduler's budget. */
	void update() {
		releaseOrphans();
		// Creating an entity is the expensive part; a crowd appears over a few passes, not in one tick.
		spawnsLeft = MAX_SPAWNS_PER_UPDATE;
		// A claim whose worker vanished mid-task would block its block for good. Claims are only a
		// courtesy between workers (every action re-checks the world), so they are simply forgotten
		// now and then and taken afresh.
		if (++updatesSinceClaimReset >= CLAIM_RESET_UPDATES) {
			updatesSinceClaimReset = 0;
			claimedBlocks.clear();
		}
		for (Expedition expedition : simulation.state().expeditions().all()) {
			ServerPlayer player = server.getPlayerList().getPlayer(expedition.player());
			boolean present = player != null && player.isAlive() && player.level().dimension() == Level.OVERWORLD;

			if (expedition.state() == Expedition.State.FOLLOWING && !present) {
				expedition.setState(Expedition.State.CAMPED);
				if (player != null) {
					player.sendSystemMessage(Component.translatable("message.villagerciv.expedition.camped"));
				}
			} else if (expedition.state() == Expedition.State.CAMPED && present && isNearAnyMember(player, expedition)) {
				expedition.setState(Expedition.State.FOLLOWING);
				player.sendSystemMessage(Component.translatable("message.villagerciv.expedition.following"), true);
			}
			if (expedition.state() == Expedition.State.CAMPED) {
				embodyNearPlayers(expedition);
			} else if (present) {
				embodyMissing(expedition, player);
			}
		}
		for (Settlement settlement : simulation.state().settlements().all()) {
			embodyNearPlayers(settlement);
		}
	}

	/**
	 * A camp or a settlement is part of the world: its members are visible to whoever comes near,
	 * whether or not that is "their" player.
	 */
	private void embodyNearPlayers(CitizenGroup group) {
		ServerLevel level = server.overworld();
		List<ServerPlayer> players = level.players();
		if (players.isEmpty()) {
			return;
		}
		// A settlement is one place. With a player anywhere in or beside it, all of its people
		// are there to be seen and at their work, not only those within a stone's throw of where
		// the player happens to stand: the far side of the village would otherwise stand idle.
		boolean wholePlace = false;
		if (group instanceof Settlement settlement) {
			for (int i = 0; i < players.size() && !wholePlace; i++) {
				double dx = players.get(i).getX() - settlement.anchorX();
				double dz = players.get(i).getZ() - settlement.anchorZ();
				wholePlace = dx * dx + dz * dz <= SETTLEMENT_DISTANCE_SQR;
			}
		}
		for (Citizen citizen : group.members()) {
			if (spawnsLeft <= 0) {
				return;
			}
			if (citizen.isEmbodied() || !level.isLoaded(BlockPos.containing(citizen.x(), citizen.y(), citizen.z()))) {
				continue;
			}
			if (wholePlace) {
				materialize(level, group, citizen, citizen.x(), citizen.y(), citizen.z(), 0f);
				continue;
			}
			for (int i = 0; i < players.size(); i++) {
				// Measured along the ground only. A miner at the bottom of a deep mine is right
				// under the village, not far from it: counted by height as well, it would be
				// left without a body every time the world is opened, load in hand, for good.
				double dx = players.get(i).getX() - citizen.x();
				double dz = players.get(i).getZ() - citizen.z();
				if (dx * dx + dz * dz <= REJOIN_DISTANCE_SQR) {
					materialize(level, group, citizen, citizen.x(), citizen.y(), citizen.z(), 0f);
					break;
				}
			}
		}
	}

	/** Travelling members without a body rejoin: where they stood if that is close, else beside the player. */
	private void embodyMissing(Expedition expedition, ServerPlayer player) {
		ServerLevel level = player.level();
		for (Citizen citizen : expedition.members()) {
			if (spawnsLeft <= 0) {
				return;
			}
			if (citizen.isEmbodied()) {
				continue;
			}
			double distanceSqr = player.distanceToSqr(citizen.x(), citizen.y(), citizen.z());
			boolean loaded = level.isLoaded(BlockPos.containing(citizen.x(), citizen.y(), citizen.z()));
			if (loaded && distanceSqr <= LEFT_BEHIND_DISTANCE_SQR) {
				materialize(level, expedition, citizen, citizen.x(), citizen.y(), citizen.z(), 0f);
			} else {
				Vec3 spot = findSpotNear(player);
				if (spot != null) {
					materialize(level, expedition, citizen, spot.x, spot.y, spot.z, player.getYRot());
				}
			}
		}
	}

	private static boolean isNearAnyMember(ServerPlayer player, Expedition expedition) {
		for (Citizen citizen : expedition.members()) {
			if (player.distanceToSqr(citizen.x(), citizen.y(), citizen.z()) <= REJOIN_DISTANCE_SQR) {
				return true;
			}
		}
		return false;
	}

	/** Gives a record its body. Does nothing if it already has one. */
	public CitizenEntity materialize(ServerLevel level, CitizenGroup group, Citizen citizen, double x, double y, double z, float yRot) {
		CitizenEntity existing = bodies.get(citizen.id());
		if (existing != null && !existing.isRemoved()) {
			return existing;
		}
		// The entity budget: however many citizens there are, only so many have a body at once.
		// The rest go on as records, exactly as they do when nobody is near.
		if (bodies.size() >= simulation.scheduler().preset().entityBudget()) {
			return null;
		}
		CitizenEntity body = ModContent.CITIZEN.create(level, EntitySpawnReason.TRIGGERED);
		if (body == null) {
			return null;
		}
		spawnsLeft--;
		body.snapTo(x, y, z, yRot, 0f);
		body.setYHeadRot(yRot);
		body.bind(group, citizen);
		bodies.put(citizen.id(), body);
		citizen.setEmbodied(true);
		if (!level.addFreshEntity(body)) {
			bodies.remove(citizen.id());
			citizen.setEmbodied(false);
			return null;
		}
		return body;
	}

	/** True if {@code body} is the one body this controller created for its record. */
	public boolean isBody(CitizenEntity body) {
		CitizenId id = body.citizenId();
		return id != null && bodies.get(id) == body;
	}

	private static boolean hasRecord(CitizenEntity body) {
		return body.group().member(body.citizenId()).isPresent();
	}

	/**
	 * Bodies whose record is gone (the citizen left, or the expedition was disbanded) go back to
	 * being ordinary villagers on the spot.
	 */
	void releaseOrphans() {
		List<CitizenEntity> orphans = null;
		for (CitizenEntity body : bodies.values()) {
			if (!hasRecord(body)) {
				if (orphans == null) {
					orphans = new ArrayList<>();
				}
				orphans.add(body);
			}
		}
		if (orphans == null) {
			return;
		}
		for (CitizenEntity body : orphans) {
			bodies.remove(body.citizenId());
			if (body.isAlive() && body.level() instanceof ServerLevel level) {
				Villager villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.CONVERSION);
				if (villager != null) {
					villager.snapTo(body.getX(), body.getY(), body.getZ(), body.getYRot(), 0f);
					villager.finalizeSpawn(level, level.getCurrentDifficultyAt(body.blockPosition()), EntitySpawnReason.CONVERSION, null);
					villager.setCustomName(body.getCustomName());
					level.addFreshEntity(villager);
				}
			}
			body.discard();
		}
	}

	/**
	 * Bodies left standing where the game no longer runs things: a builder set down at a far
	 * stretch of wall while a player was there, and still there after the player has gone. Such a
	 * body does nothing, and while it exists its citizen's work is not reckoned either. It is let
	 * go, and its citizen counted as back in the settlement, working on as those nobody sees do.
	 */
	void releaseStranded() {
		List<CitizenEntity> stranded = null;
		for (CitizenEntity body : bodies.values()) {
			if (body.group() instanceof Settlement && body.isAlive() && body.level() instanceof ServerLevel level
					&& !level.isPositionEntityTicking(body.blockPosition())) {
				if (stranded == null) {
					stranded = new ArrayList<>();
				}
				stranded.add(body);
			}
		}
		if (stranded == null) {
			return;
		}
		for (CitizenEntity body : stranded) {
			Settlement settlement = (Settlement) body.group();
			Citizen citizen = body.citizen();
			body.discard();
			if (citizen != null) {
				// Each to a spot of its own round the Town Center, not all on one block.
				double angle = server.overworld().getRandom().nextDouble() * Math.PI * 2;
				double away = 4.0 + server.overworld().getRandom().nextDouble() * 10.0;
				citizen.setPosition(settlement.anchorX() + 0.5 + Math.cos(angle) * away, settlement.anchorY(), settlement.anchorZ() + 0.5 + Math.sin(angle) * away);
			}
		}
	}

	/** Called for every removal of a body, whatever the reason: the record takes over. */
	public void onBodyRemoved(CitizenEntity body) {
		CitizenId id = body.citizenId();
		if (id != null && bodies.get(id) == body) {
			bodies.remove(id);
			body.syncToRecord();
			body.citizen().setEmbodied(false);
		}
	}

	public void onDeath(CitizenEntity body, DamageSource source) {
		Citizen citizen = body.citizen();
		if (citizen == null || !isBody(body) || !hasRecord(body)) {
			return;
		}
		CitizenGroup group = body.group();
		Map<CitizenId, Role> rolesBefore = new HashMap<>();
		for (Citizen member : group.members()) {
			rolesBefore.put(member.id(), member.role());
		}
		bodies.remove(citizen.id());
		citizen.setEmbodied(false);
		simulation.citizenDied(group, citizen.id());
		boolean betrayed = group instanceof Expedition
				&& source.getEntity() instanceof ServerPlayer killer && killer.getUUID().equals(group.player());
		if (betrayed) {
			simulation.betrayed(((Expedition) group).id());
		}

		ServerPlayer player = server.getPlayerList().getPlayer(group.player());
		if (player == null) {
			return;
		}
		player.sendSystemMessage(Component.translatable("message.villagerciv.citizen.died", citizen.name()));
		if (group instanceof Expedition && group.size() == 0) {
			player.sendSystemMessage(Component.translatable("message.villagerciv.expedition.lost"));
		} else if (betrayed) {
			player.sendSystemMessage(Component.translatable("message.villagerciv.expedition.betrayed"));
		}
		for (Citizen member : group.members()) {
			Role role = member.role();
			if (role != rolesBefore.get(member.id()) && (role == Role.LEADER || role == Role.GENERAL)) {
				player.sendSystemMessage(Component.translatable("message.villagerciv.expedition.role_changed", member.name(), roleName(role)));
			}
		}
	}

	/** A member was handed food: it goes into the shared supply. */
	public void feed(ServerPlayer player, CitizenEntity body, int meals) {
		int food;
		switch (body.group()) {
			case Expedition expedition -> {
				simulation.feed(expedition.id(), meals);
				food = expedition.food();
			}
			case Settlement settlement -> {
				simulation.contribute(settlement, player.getUUID(), Commodity.FOOD, meals);
				food = settlement.ledger().onHand(Commodity.FOOD);
			}
		}
		if (body.level() instanceof ServerLevel level) {
			level.sendParticles(ParticleTypes.HAPPY_VILLAGER, body.getX(), body.getY() + 1.6, body.getZ(), 5, 0.3, 0.3, 0.3, 0.0);
		}
		player.sendSystemMessage(Component.translatable("message.villagerciv.expedition.fed",
				food, food / Math.max(1, body.group().size())), true);
	}

	/** A player gives goods to a settlement; they are added to its stockpile and credited to the player. */
	void contribute(ServerPlayer player, Settlement settlement, Commodity commodity, int units) {
		simulation.contribute(settlement, player.getUUID(), commodity, units);
		player.sendSystemMessage(Component.translatable("message.villagerciv.settlement.contributed", units,
				Component.translatable("commodity.villagerciv." + commodity.name().toLowerCase(Locale.ROOT)),
				settlement.name(), settlement.ledger().onHand(commodity)), true);
		server.overworld().sendParticles(ParticleTypes.HAPPY_VILLAGER, settlement.anchorX() + 0.5, settlement.anchorY() + 1.2,
				settlement.anchorZ() + 0.5, 6, 0.4, 0.3, 0.4, 0.0);
	}

	/** A player gives tools: they go to whoever would use that kind, and make their work faster. */
	void contributeTools(ServerPlayer player, Settlement settlement, ToolKind kind, int count) {
		// A tool someone is waiting for is worth a point; one more for the pile, a tenth.
		int wanted = Math.clamp(Tools.needed(settlement, kind) - settlement.tools(kind), 0, count);
		settlement.addReputation(player.getUUID(), wanted * 10 + (count - wanted));
		settlement.contributeTools(player.getUUID(), kind, count);
		player.sendSystemMessage(Component.translatable("message.villagerciv.settlement.contributed_tools", count,
				Component.translatable("tool.villagerciv." + kind.name().toLowerCase(Locale.ROOT)),
				settlement.name(), settlement.tools(kind), Tools.needed(settlement, kind)), true);
		server.overworld().sendParticles(ParticleTypes.HAPPY_VILLAGER, settlement.anchorX() + 0.5, settlement.anchorY() + 1.2,
				settlement.anchorZ() + 0.5, 6, 0.4, 0.3, 0.4, 0.0);
	}

	/** Opens the settlement screen. Anyone may look; a settlement is not a secret. */
	public void showSettlement(ServerPlayer player, Settlement settlement) {
		int[] stock = new int[Commodity.values().length];
		for (Commodity commodity : Commodity.values()) {
			stock[commodity.ordinal()] = settlement.ledger().onHand(commodity);
		}
		List<ExpeditionInfoPayload.Member> members = new ArrayList<>(settlement.size());
		for (Citizen citizen : settlement.members()) {
			// What a settler is doing says more than what it once was: show the job it holds.
			Role shown = citizen.job() == null ? citizen.role() : citizen.job().affinity();
			// One without a body is not acted out: it is simply at its work, or has none.
			Activity activity = citizen.isEmbodied() ? citizen.activity()
					: citizen.job() != null && simulation.isDaytime() ? Activity.AT_WORK : Activity.IDLE;
			members.add(new ExpeditionInfoPayload.Member(citizen.name(), shown, activity, Math.round(citizen.health()), citizen.loyalty()));
		}
		// The house is what is shown; while only the wall is going up, that is.
		Building project = settlement.activeBuilding() != null ? settlement.activeBuilding() : settlement.activeWallPiece();
		String projectName = project == null ? "" : blueprintName(project.blueprint());
		Construction.Status status = Construction.status(settlement, simulation.blueprints());
		if (settlement.mainStore() == null && settlement.storeSoughtAtStreets() == settlement.streets().size()) {
			// Looked for and not found: say so, or the patron waits for a storehouse that cannot come.
			player.sendSystemMessage(Component.translatable("message.villagerciv.settlement.no_store_lot", settlement.name()));
		}
		int needWood = 0;
		int needStone = 0;
		if (status == Construction.Status.WAITING_FOR_MATERIALS || status == Construction.Status.PLANNING) {
			// Nothing is started yet: name what is wanted and what it takes.
			projectName = blueprintName(Construction.wantedBlueprint(settlement, simulation.blueprints()));
			needWood = Construction.materialNeed(settlement, simulation.blueprints(), Commodity.WOOD);
			needStone = Construction.materialNeed(settlement, simulation.blueprints(), Commodity.STONE);
		}
		int wallBuilt = 0;
		for (Building building : settlement.buildings()) {
			if (building.category() == Blueprint.Category.DEFENSE && building.stage() == Building.Stage.COMPLETE) {
				wallBuilt++;
			}
		}
		int plans = 0;
		for (Policy plan : settlement.plans()) {
			plans |= 1 << plan.ordinal();
		}
		int[] statuses = new int[Commodity.values().length];
		for (Commodity commodity : Commodity.values()) {
			statuses[commodity.ordinal()] = settlement.status(commodity).ordinal();
		}
		List<SettlementInfoPayload.RequestLine> requests = new ArrayList<>();
		for (Request request : settlement.requests()) {
			long left = Math.max(0, request.deadlineTick() - simulation.gameTick());
			requests.add(new SettlementInfoPayload.RequestLine(request.commodity(), request.amount(), request.delivered(),
					request.purpose().equals(Request.FOOD_RESERVE) ? "" : blueprintName(request.purpose()),
					(int) ((left + ExpeditionUpkeep.TICKS_PER_DAY - 1) / ExpeditionUpkeep.TICKS_PER_DAY)));
		}
		int projectPercent = project == null ? 0 : project.progress() * 100 / Math.max(1, project.totalSteps());
		int[] toolsHave = new int[ToolKind.values().length];
		int[] toolsNeeded = new int[ToolKind.values().length];
		for (ToolKind kind : ToolKind.values()) {
			toolsHave[kind.ordinal()] = settlement.tools(kind);
			toolsNeeded[kind.ordinal()] = Tools.needed(settlement, kind);
		}
		ServerPlayNetworking.send(player, new SettlementInfoPayload(settlement.name(), settlement.stage(),
				player.getUUID().equals(settlement.founder()), stock, toolsHave, toolsNeeded, settlement.morale(),
				settlement.housingCapacity(), settlement.territory().size(),
				settlement.siteScore().total(), settlement.contributionOf(player.getUUID()),
				status, projectName, projectPercent, needWood, needStone,
				plans, statuses, settlement.threat(), settlement.guards(), wallBuilt, settlement.wallPieces(),
				settlement.reputationOf(player.getUUID()), requests, members));
	}

	/**
	 * The player's followers move into a settlement of theirs. With nobody following, the player
	 * is only told how it is done.
	 */
	void settleIn(ServerPlayer player, Settlement settlement) {
		List<Citizen> joined = simulation.settleIn(player.getUUID(), settlement);
		if (joined.isEmpty()) {
			player.sendSystemMessage(Component.translatable("message.villagerciv.settle.nobody"), true);
			return;
		}
		for (Citizen citizen : joined) {
			// Anyone left behind on the road is taken to have caught up.
			if (!bodies.containsKey(citizen.id())) {
				citizen.setPosition(settlement.anchorX() + 0.5, settlement.anchorY() + 1, settlement.anchorZ() + 2.5);
			}
		}
		adopt(settlement);
		player.sendSystemMessage(Component.translatable("message.villagerciv.settle.joined", joined.size(), settlement.name(),
				settlement.size()));
	}

	/** Whether this player has followers who could move into a settlement. */
	boolean hasFollowers(ServerPlayer player) {
		return simulation.expeditionOf(player.getUUID()).isPresent();
	}

	/** Founders stop being an expedition: their bodies now belong to the settlement. */
	void adopt(Settlement settlement) {
		for (Citizen citizen : settlement.members()) {
			CitizenEntity body = bodies.get(citizen.id());
			if (body != null) {
				body.syncToRecord();
				body.getNavigation().stop();
				body.bind(settlement, citizen);
			}
		}
	}

	/** The settlement whose Town Center stands at this block, or null. */
	public Settlement settlementAt(BlockPos pos) {
		for (Settlement settlement : simulation.state().settlements().all()) {
			if (settlement.anchorX() == pos.getX() && settlement.anchorY() == pos.getY() && settlement.anchorZ() == pos.getZ()) {
				return settlement;
			}
		}
		return null;
	}

	/** Opens the expedition screen for the expedition's own player. */
	public void showExpedition(ServerPlayer player, Expedition expedition) {
		List<ExpeditionInfoPayload.Member> members = new ArrayList<>(expedition.size());
		for (Citizen citizen : expedition.members()) {
			members.add(new ExpeditionInfoPayload.Member(citizen.name(), citizen.role(), Activity.FOLLOWING,
					Math.round(citizen.health()), citizen.loyalty()));
		}
		ServerPlayNetworking.send(player, new ExpeditionInfoPayload(expedition.state() == Expedition.State.FOLLOWING,
				expedition.food(), expedition.isStarving(), members));
	}

	/** The player sends everyone home: the members become ordinary villagers where they stand. */
	void disband(ServerPlayer player) {
		Expedition expedition = simulation.expeditionOf(player.getUUID()).orElse(null);
		if (expedition == null) {
			return;
		}
		for (CitizenEntity body : bodies.values()) {
			if (body.group() == expedition) {
				body.syncToRecord();
			}
		}
		simulation.disband(expedition.id());
		releaseOrphans();
		player.sendSystemMessage(Component.translatable("message.villagerciv.expedition.disbanded"));
	}

	/**
	 * The patron offers a Development Plan. The Leader takes it up alongside the plans already
	 * followed, and says what it thinks of it.
	 */
	public void proposePlan(ServerPlayer player, Settlement settlement, ItemStack stack, Policy plan) {
		String leader = "The Leader";
		for (Citizen citizen : settlement.members()) {
			if (citizen.role() == Role.LEADER) {
				leader = citizen.name();
			}
		}
		Component planName = Component.translatable("policy.villagerciv." + plan.name().toLowerCase(Locale.ROOT));
		if (!simulation.adoptPlan(settlement, plan)) {
			player.sendSystemMessage(Component.translatable("message.villagerciv.plan.same", leader, planName), true);
			return;
		}
		stack.consume(1, player);
		player.sendSystemMessage(Component.translatable("message.villagerciv.plan.accepted", leader, planName));
		// The Leader speaks its mind where the land does not suit the plan; the choice stays the patron's.
		SiteScore site = settlement.siteScore();
		if (plan == Policy.AGRARIAN && (site.water() == 0 || site.fertility() < 40)) {
			player.sendSystemMessage(Component.translatable("message.villagerciv.plan.doubt.agrarian", leader));
		} else if (plan == Policy.INDUSTRY && site.stone() < 20) {
			player.sendSystemMessage(Component.translatable("message.villagerciv.plan.doubt.industry", leader));
		}
		server.overworld().sendParticles(ParticleTypes.HAPPY_VILLAGER, settlement.anchorX() + 0.5, settlement.anchorY() + 1.2,
				settlement.anchorZ() + 0.5, 12, 0.5, 0.4, 0.5, 0.0);
	}

	void onRequest(RequestEvent event) {
		ServerPlayer player = server.getPlayerList().getPlayer(event.player());
		Settlement settlement = simulation.state().settlements().get(event.settlement()).orElse(null);
		if (player == null || settlement == null) {
			return;
		}
		Component what = Component.translatable("commodity.villagerciv." + event.commodity().name().toLowerCase(Locale.ROOT));
		String key = "message.villagerciv.request." + event.kind().name().toLowerCase(Locale.ROOT);
		if (event.kind() == RequestEvent.Kind.OPENED) {
			Component purpose = event.purpose().equals(Request.FOOD_RESERVE)
					? Component.translatable("message.villagerciv.request.purpose.food")
					: Component.translatable("message.villagerciv.request.purpose.building", blueprintName(event.purpose()));
			player.sendSystemMessage(Component.translatable(key, settlement.name(), event.amount(), what, purpose));
		} else {
			player.sendSystemMessage(Component.translatable(key, settlement.name(), what));
		}
	}

	private String blueprintName(String id) {
		return simulation.blueprints().get(id).map(Blueprint::name).orElse(id);
	}

	void onBorn(CitizenBornEvent event) {
		ServerPlayer player = server.getPlayerList().getPlayer(event.player());
		Settlement settlement = simulation.state().settlements().get(event.settlement()).orElse(null);
		if (player != null && settlement != null) {
			player.sendSystemMessage(Component.translatable(event.newcomer() ? "message.villagerciv.settlement.arrived" : "message.villagerciv.settlement.born", event.name(), settlement.name(),
					settlement.size()));
		}
	}

	void onStage(SettlementStageEvent event) {
		ServerPlayer player = server.getPlayerList().getPlayer(event.player());
		Settlement settlement = simulation.state().settlements().get(event.settlement()).orElse(null);
		if (player != null && settlement != null) {
			player.sendSystemMessage(Component.translatable(event.declined() ? "message.villagerciv.settlement.declined" : "message.villagerciv.settlement.stage", settlement.name(),
					Component.translatable("stage.villagerciv." + settlement.stage().name().toLowerCase(Locale.ROOT))));
		}
	}

	void onHungry(ExpeditionHungryEvent event) {
		simulation.state().expeditions().get(event.expedition()).ifPresent(expedition -> {
			ServerPlayer player = server.getPlayerList().getPlayer(expedition.player());
			if (player != null) {
				player.sendSystemMessage(Component.translatable("message.villagerciv.expedition.hungry"));
			}
		});
	}

	void onLeft(CitizenLeftEvent event) {
		ServerPlayer player = server.getPlayerList().getPlayer(event.player());
		if (player != null) {
			player.sendSystemMessage(Component.translatable("message.villagerciv.citizen.left", event.name()));
			if (event.groupEnded()) {
				player.sendSystemMessage(Component.translatable("message.villagerciv.expedition.lost"));
			}
		}
	}

	/** Removes the bodies of one player's expedition; the group camps where it stands. */
	void onPlayerLeave(ServerPlayer player) {
		simulation.expeditionOf(player.getUUID()).ifPresent(expedition -> {
			expedition.setState(Expedition.State.CAMPED);
			for (Citizen citizen : expedition.members()) {
				CitizenEntity body = bodies.get(citizen.id());
				if (body != null) {
					body.discard();
				}
			}
		});
	}

	/** Removes every body, writing its state back first. Used before the world is saved for the last time. */
	void dematerializeAll() {
		List<CitizenEntity> all = new ArrayList<>(bodies.values());
		for (CitizenEntity body : all) {
			body.discard();
		}
		bodies.clear();
	}

	int bodyCount() {
		return bodies.size();
	}

	/**
	 * A free, supported spot beside the player, or null when there is none right now (for example
	 * while the player is flying): the caller simply tries again later.
	 */
	public static Vec3 findSpotNear(ServerPlayer player) {
		if (!player.onGround()) {
			return null;
		}
		ServerLevel level = player.level();
		BlockPos origin = player.blockPosition();
		for (int attempt = 0; attempt < 8; attempt++) {
			BlockPos feet = origin.offset(player.getRandom().nextInt(5) - 2, 0, player.getRandom().nextInt(5) - 2);
			if (canStandAt(level, feet)) {
				return new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
			}
		}
		// Tight spot (a one-block pillar or tunnel): share the player's own block, if it really is ground.
		return canStandAt(level, origin) ? new Vec3(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5) : null;
	}

	private static boolean canStandAt(ServerLevel level, BlockPos feet) {
		return !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()
				&& level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
				&& level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
				&& level.getFluidState(feet).isEmpty();
	}

	public static Component roleName(Role role) {
		return Component.translatable("role.villagerciv." + role.name().toLowerCase(Locale.ROOT));
	}
}
