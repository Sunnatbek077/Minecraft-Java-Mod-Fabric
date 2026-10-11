package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.api.CitizenDiedEvent;
import com.sunnatbek.villagerciv.api.CitizenId;
import com.sunnatbek.villagerciv.api.CitizenLeftEvent;
import com.sunnatbek.villagerciv.api.CitizenRecruitedEvent;
import com.sunnatbek.villagerciv.api.CivilizationCreatedEvent;
import com.sunnatbek.villagerciv.api.ExpeditionId;
import com.sunnatbek.villagerciv.api.SettlementFoundedEvent;
import com.sunnatbek.villagerciv.api.SettlementId;
import com.sunnatbek.villagerciv.build.BlueprintLibrary;
import com.sunnatbek.villagerciv.build.Construction;
import com.sunnatbek.villagerciv.build.Housing;
import com.sunnatbek.villagerciv.api.EventBus;
import com.sunnatbek.villagerciv.scheduler.QualityPreset;
import com.sunnatbek.villagerciv.scheduler.SimulationScheduler;
import com.sunnatbek.villagerciv.scheduler.TaskPriority;
import com.sunnatbek.villagerciv.scheduler.TaskResult;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The running simulation: state plus the services that drive it. It knows nothing about
 * Minecraft; the platform layer (or the headless harness) feeds it ticks.
 */
public final class Simulation {
	private final SimulationState state;
	private final EventBus events = new EventBus();
	private final SimulationScheduler scheduler;
	private final Recruitment recruitment = new Recruitment();
	private final BlueprintLibrary blueprints = BlueprintLibrary.builtIn();
	private long gameTick;
	private boolean daytime = true;

	public Simulation(SimulationState state, QualityPreset preset, LongSupplier nanoTime) {
		this.state = state;
		this.scheduler = new SimulationScheduler(preset, nanoTime);
		scheduler.scheduleRecurring("events", TaskPriority.INTERRUPT, 1, 0, context -> {
			events.drain(context::hasBudget);
			return TaskResult.DONE;
		});
		scheduler.scheduleRecurring("expeditions", TaskPriority.VIRTUAL_BATCH, 200, 0, context -> {
			for (Expedition expedition : List.copyOf(state.expeditions().all())) {
				for (Citizen deserter : ExpeditionUpkeep.update(expedition, context.gameTick(), events)) {
					removeMember(expedition, deserter.id());
					events.publish(new CitizenLeftEvent(expedition.player(), deserter.id(), deserter.name(), expedition.size() == 0));
				}
			}
			return TaskResult.DONE;
		});
		scheduler.scheduleRecurring("labor", TaskPriority.VIRTUAL_BATCH, Labor.CYCLE_TICKS, 100, context -> {
			for (Settlement settlement : state.settlements().all()) {
				for (Citizen leaving : SettlementUpkeep.update(settlement, context.gameTick())) {
					settlement.remove(leaving.id());
					RoleAssignment.assign(settlement.members());
					events.publish(new CitizenLeftEvent(settlement.founder(), leaving.id(), leaving.name(), false));
				}
				Population.update(settlement, context.gameTick(), state.ids(), events);
				ResourceManager.update(settlement, blueprints, context.gameTick(), events);
				Trade.update(settlement);
				Tools.makeMissing(settlement);
				Defense.update(settlement, blueprints, context.gameTick());
				Construction.reserveStoreLot(settlement, blueprints, state.ids());
				SitePicker.ensureSites(settlement);
				Construction.update(settlement, blueprints, state.ids());
				Housing.assign(settlement);
				Labor.update(settlement, context.gameTick(), daytime);
			}
			return TaskResult.DONE;
		});
	}

	/**
	 * Advances the simulation by one server tick.
	 *
	 * @param serverMspt how long recent server ticks took, used to throttle the mod
	 */
	public void tick(long gameTick, double serverMspt) {
		this.gameTick = gameTick;
		scheduler.tick(gameTick, serverMspt);
	}

	/** A player gives goods to a settlement; see {@link ResourceManager#contribute}. */
	public void contribute(Settlement settlement, UUID player, Commodity commodity, int units) {
		ResourceManager.contribute(settlement, player, commodity, units, gameTick, events);
	}

	/**
	 * The patron hands the Leader a Development Plan. It is taken up alongside any plans the
	 * settlement already follows.
	 *
	 * @return false if the settlement follows this plan already
	 */
	public boolean adoptPlan(Settlement settlement, Policy plan) {
		return settlement.adoptPlan(plan);
	}

	/** Told by the platform each tick: citizens work by day and rest at night. */
	public void setDaytime(boolean daytime) {
		this.daytime = daytime;
	}

	public boolean isDaytime() {
		return daytime;
	}

	public Civilization createCivilization(String name) {
		Civilization civilization = state.createCivilization(name, gameTick);
		events.publish(new CivilizationCreatedEvent(civilization.id()));
		return civilization;
	}

	/** The expedition travelling with this player, if any. */
	public Optional<Expedition> expeditionOf(UUID player) {
		for (Expedition expedition : state.expeditions().all()) {
			if (expedition.player().equals(player)) {
				return Optional.of(expedition);
			}
		}
		return Optional.empty();
	}

	/** What a villager would answer right now, without asking yet. */
	public Recruitment.Offer previewRecruit(UUID player, UUID villager, String profession, boolean night) {
		boolean unemployed = isUnemployed(profession);
		return new Recruitment.Offer(Recruitment.acceptChance(unemployed, night), unemployed, night,
				recruitment.cooldownRemaining(villager, gameTick), expeditionOf(player).map(Expedition::size).orElse(0));
	}

	private static boolean isUnemployed(String profession) {
		return profession.equals("none") || profession.equals("nitwit");
	}

	/**
	 * A player asks a villager to join. On acceptance the citizen record exists and belongs to the
	 * player's expedition (created on the first recruit); the caller turns the villager into its body.
	 *
	 * @param profession vanilla profession path, e.g. {@code "farmer"} or {@code "none"}
	 * @param givenName  the villager's custom name, or null to pick one
	 */
	public Recruitment.Result recruit(UUID player, UUID villager, String profession, String givenName, boolean night,
			double x, double y, double z) {
		// Asked on the land of the player's own settlement, a villager joins it there and then;
		// anywhere else they join the expedition and have to be walked home.
		Settlement home = state.territoryOwner(ChunkKey.ofBlock((int) Math.floor(x), (int) Math.floor(z)))
				.filter(settlement -> settlement.founder().equals(player)).orElse(null);
		Expedition expedition = expeditionOf(player).orElse(null);
		if (home == null && expedition != null && expedition.size() >= Recruitment.MAX_EXPEDITION_SIZE) {
			return new Recruitment.ExpeditionFull();
		}
		long cooldown = recruitment.cooldownRemaining(villager, gameTick);
		if (cooldown > 0) {
			return new Recruitment.Refused(cooldown);
		}
		IdGenerator random = state.ids();
		boolean unemployed = isUnemployed(profession);
		if (random.nextDouble() >= Recruitment.acceptChance(unemployed, night)) {
			recruitment.startCooldown(villager, gameTick);
			return new Recruitment.Refused(0);
		}

		if (home == null && expedition == null) {
			expedition = new Expedition(new ExpeditionId(random.next()), player, gameTick);
			state.expeditions().add(expedition);
		}
		Trait[] all = Trait.values();
		EnumSet<Trait> traits = EnumSet.of(all[random.nextInt(all.length)]);
		if (random.nextDouble() < 0.5) {
			traits.add(all[random.nextInt(all.length)]);
		}
		String name = givenName != null ? givenName
				: CitizenNames.pick(random, home != null ? home.members() : expedition.members());
		Citizen citizen = new Citizen(new CitizenId(random.next()), name, gameTick, Role.affinityOfProfession(profession), traits);
		citizen.setPosition(x, y, z);
		if (home != null) {
			home.add(citizen);
			RoleAssignment.assign(home.members());
			return new Recruitment.Accepted(home, citizen);
		}
		expedition.add(citizen);
		expedition.setFood(expedition.food() + Recruitment.STARTING_MEALS_PER_RECRUIT);
		RoleAssignment.assign(expedition.members());
		events.publish(new CitizenRecruitedEvent(expedition.id(), citizen.id()));
		return new Recruitment.Accepted(expedition, citizen);
	}

	/**
	 * A player's expedition moves into a settlement they founded: the followers become citizens
	 * of it and what food they carried goes into its store. The settlement houses them as it
	 * would anyone; until then they sleep rough.
	 *
	 * @return those who joined; empty if the player has no expedition or the settlement is not theirs
	 */
	public List<Citizen> settleIn(UUID player, Settlement settlement) {
		Expedition expedition = expeditionOf(player).orElse(null);
		if (expedition == null || !settlement.founder().equals(player)) {
			return List.of();
		}
		List<Citizen> joined = List.copyOf(expedition.members());
		for (Citizen citizen : joined) {
			expedition.remove(citizen.id());
			settlement.add(citizen);
		}
		if (expedition.food() > 0) {
			settlement.ledger().add(Commodity.FOOD, expedition.food());
		}
		state.expeditions().remove(expedition.id());
		RoleAssignment.assign(settlement.members());
		return joined;
	}

	/** Removes a dead citizen; the survivors redistribute the roles. An emptied expedition ends. */
	public void citizenDied(ExpeditionId expeditionId, CitizenId citizenId) {
		Expedition expedition = state.expeditions().get(expeditionId).orElse(null);
		if (expedition != null && removeMember(expedition, citizenId)) {
			events.publish(new CitizenDiedEvent(citizenId, expedition.player()));
		}
	}

	/** Removes a dead citizen from whatever group it belonged to. */
	public void citizenDied(CitizenGroup group, CitizenId citizenId) {
		switch (group) {
			case Expedition expedition -> citizenDied(expedition.id(), citizenId);
			case Settlement settlement -> {
				// A settlement that lost everyone stays as a record: its buildings remain.
				if (settlement.remove(citizenId).isPresent()) {
					RoleAssignment.assign(settlement.members());
					events.publish(new CitizenDiedEvent(citizenId, settlement.founder()));
				}
			}
		}
	}

	/**
	 * Whether this player's expedition may settle with its Town Center at the given block.
	 * Terrain is judged separately, by the site survey.
	 */
	public Founding.Refusal canFound(UUID player, int x, int z) {
		Expedition expedition = expeditionOf(player).orElse(null);
		if (expedition == null) {
			return Founding.Refusal.NO_EXPEDITION;
		}
		if (expedition.size() < Founding.MIN_FOUNDERS) {
			return Founding.Refusal.TOO_FEW_FOUNDERS;
		}
		if (expedition.state() != Expedition.State.FOLLOWING) {
			return Founding.Refusal.NOT_TRAVELLING;
		}
		for (long chunk : claimAround(x, z)) {
			if (state.territoryOwner(chunk).isPresent()) {
				return Founding.Refusal.TERRITORY_TAKEN;
			}
		}
		return Founding.Refusal.NONE;
	}

	/**
	 * The expedition settles: it becomes a settlement with the same citizens, its supplies go into
	 * the new stockpile, and the surrounding chunks are claimed. The expedition record is gone
	 * afterwards; nothing has to be converted because both use the same citizen records.
	 *
	 * @return the new settlement, or empty if founding is not allowed (see {@link #canFound})
	 */
	public Optional<Settlement> found(UUID player, int x, int y, int z, SiteSurvey.Result survey) {
		if (canFound(player, x, z) != Founding.Refusal.NONE || !survey.score().canSettle()) {
			return Optional.empty();
		}
		Expedition expedition = expeditionOf(player).orElseThrow();
		IdGenerator random = state.ids();
		Settlement settlement = new Settlement(new SettlementId(random.next()), Founding.settlementName(random),
				player, gameTick, x, y, z);
		for (Citizen citizen : List.copyOf(expedition.members())) {
			expedition.remove(citizen.id());
			settlement.add(citizen);
		}
		if (expedition.food() > 0) {
			settlement.ledger().add(Commodity.FOOD, expedition.food());
		}
		for (long chunk : claimAround(x, z)) {
			settlement.claim(chunk);
		}
		settlement.setSurvey(survey.score(), survey.plots());
		state.expeditions().remove(expedition.id());
		state.settlements().add(settlement);
		state.territoryChanged();
		events.publish(new SettlementFoundedEvent(settlement.id()));
		return Optional.of(settlement);
	}

	/**
	 * The patron's Expansion Charter takes effect: the settlement may grow beyond its first wall.
	 * It is given the wider survey of its land, claims the ground that goes with it (where nobody
	 * else has), and, if it is a walled place or means to be one, plans a second ring of stone
	 * round the whole. The first wall stays standing inside the second.
	 *
	 * @return false if it had been enlarged already
	 */
	public boolean expand(Settlement settlement, Settlement.PlotMap widerPlots) {
		if (settlement.expansion() > 0) {
			return false;
		}
		settlement.setExpansion(1);
		settlement.setSurvey(settlement.siteScore(), widerPlots);
		int reach = (Founding.EXPANDED_RADIUS >> 4) + 1;
		for (int cx = (settlement.anchorX() >> 4) - reach; cx <= (settlement.anchorX() >> 4) + reach; cx++) {
			for (int cz = (settlement.anchorZ() >> 4) - reach; cz <= (settlement.anchorZ() >> 4) + reach; cz++) {
				double dx = (cx << 4) + 8 - settlement.anchorX();
				double dz = (cz << 4) + 8 - settlement.anchorZ();
				long chunk = ChunkKey.of(cx, cz);
				if (Math.max(Math.abs(dx), Math.abs(dz)) <= Founding.EXPANDED_RADIUS && state.territoryOwner(chunk).isEmpty()) {
					settlement.claim(chunk);
				}
			}
		}
		state.territoryChanged();
		if (settlement.wallPieces() > 0 || settlement.hasPlan(Policy.MILITARY)) {
			List<com.sunnatbek.villagerciv.build.WallPiece> pieces = new ArrayList<>(settlement.wallPlan());
			List<com.sunnatbek.villagerciv.build.WallPiece> outer = com.sunnatbek.villagerciv.build.Fortification.planOuter(settlement, blueprints);
			pieces.addAll(outer);
			settlement.setWallPlan(pieces, settlement.wallPieces() + outer.size());
		}
		return true;
	}

	/** Chunks whose centre lies within the first claim radius of a block. */
	private static List<Long> claimAround(int x, int z) {
		List<Long> chunks = new ArrayList<>();
		int reach = (Founding.CLAIM_RADIUS >> 4) + 1;
		int centerChunkX = x >> 4;
		int centerChunkZ = z >> 4;
		for (int cx = centerChunkX - reach; cx <= centerChunkX + reach; cx++) {
			for (int cz = centerChunkZ - reach; cz <= centerChunkZ + reach; cz++) {
				double dx = (cx << 4) + 8 - x;
				double dz = (cz << 4) + 8 - z;
				if (dx * dx + dz * dz <= Founding.CLAIM_RADIUS * Founding.CLAIM_RADIUS) {
					chunks.add(ChunkKey.of(cx, cz));
				}
			}
		}
		return chunks;
	}

	private boolean removeMember(Expedition expedition, CitizenId citizenId) {
		if (expedition.remove(citizenId).isEmpty()) {
			return false;
		}
		if (expedition.size() == 0) {
			state.expeditions().remove(expedition.id());
		} else {
			RoleAssignment.assign(expedition.members());
		}
		return true;
	}

	/** The player's own hand killed a member: everyone else trusts the player much less. */
	public void betrayed(ExpeditionId expeditionId) {
		state.expeditions().get(expeditionId).ifPresent(expedition -> {
			for (Citizen citizen : expedition.members()) {
				citizen.setLoyalty(citizen.loyalty() - ExpeditionUpkeep.BETRAYAL_LOYALTY_LOSS);
			}
		});
	}

	/** Adds meals to an expedition's supply. */
	public void feed(ExpeditionId expeditionId, int meals) {
		state.expeditions().get(expeditionId).ifPresent(expedition -> expedition.setFood(expedition.food() + meals));
	}

	/**
	 * Ends an expedition: its members go back to village life.
	 *
	 * @return the former members
	 */
	public List<Citizen> disband(ExpeditionId expeditionId) {
		Expedition expedition = state.expeditions().get(expeditionId).orElse(null);
		if (expedition == null) {
			return List.of();
		}
		List<Citizen> members = new ArrayList<>(expedition.members());
		for (Citizen citizen : members) {
			expedition.remove(citizen.id());
		}
		state.expeditions().remove(expeditionId);
		return members;
	}

	public BlueprintLibrary blueprints() {
		return blueprints;
	}

	public SimulationState state() {
		return state;
	}

	public EventBus events() {
		return events;
	}

	public SimulationScheduler scheduler() {
		return scheduler;
	}

	public long gameTick() {
		return gameTick;
	}
}
