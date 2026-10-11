package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.build.Building;

/**
 * The settlement's job board. Sites offer positions; idle citizens take the one that is most
 * needed and suits them best, and hold it on a lease. Nothing links the roles to each other in
 * code: farmers, lumberjacks and miners only meet in the stockpile they all fill.
 *
 * <p>Quotas come from plain rules for now; the Settlement AI takes that decision over later.
 */
public final class Labor {
	/** Labor is reviewed this often. */
	public static final long CYCLE_TICKS = 200;
	/** A job lapses if its holder shows no progress for this long. */
	public static final long LEASE_TICKS = 1_200;
	/** Units a citizen carries before walking back to the stockpile. */
	public static final int CARRY_CAPACITY = 16;

	private static final int NO_PLACE = -1;
	/** The "place" of a job that is done wherever it is needed. */
	private static final int EVERYWHERE = 0;
	private static final int MIN_WORKERS_FOR_LEVELLER = 6;
	public static final int MASON_BUILD_PERCENT = 130;
	private static final JobType[] JOBS = JobType.values();

	private Labor() {
	}

	/**
	 * One review of the job board.
	 *
	 * @param daytime nobody works at night
	 */
	public static void update(Settlement settlement, long gameTick, boolean daytime) {
		int[] open = new int[JOBS.length];
		for (WorkSite site : settlement.sites()) {
			if (!site.isExhausted()) {
				open[site.type().ordinal()] += slots(settlement, site.type());
			}
		}
		int projects = settlement.activeBuildings().size();
		// One builder more while there are lamps to put up: that is builders' work, with or without a building in hand.
		int lamps = settlement.hasLampWork() ? 1 : 0;
		open[JobType.BUILD.ordinal()] = projects * slots(settlement, JobType.BUILD) + lamps;

		// Lapsed leases and jobs whose site is gone go back to the board.
		int[] held = new int[JOBS.length];
		int workers = 0;
		for (Citizen citizen : settlement.members()) {
			if (!canWork(settlement, citizen)) {
				citizen.clearJob();
				continue;
			}
			workers++;
			if (citizen.job() != null) {
				if (gameTick > citizen.leaseUntil() || !placeExists(settlement, citizen.job(), citizen.jobSite())) {
					citizen.clearJob();
				} else {
					held[citizen.job().ordinal()]++;
				}
			}
		}

		// Levellers: one for every ten hands, in a settlement big enough to spare them, while there is ground to mend.
		open[JobType.LEVEL.ordinal()] = settlement.hasGroundWork() && workers >= MIN_WORKERS_FOR_LEVELLER
				? Math.max(1, workers / 10) : 0;
		// A workshop takes one of its trade; a town's is busy enough for two.
		for (JobType job : JOBS) {
			if (job.workshop() != null && settlement.workshop(job.workshop()) != null) {
				open[job.ordinal()] = settlement.stage().ordinal() >= SettlementStage.TOWN.ordinal() ? 2 : 1;
			}
		}
		int[] quota = quotas(settlement, workers, open, projects + lamps);
		rebalance(settlement, quota, held);
		// Those who know a trade get first pick of it; everyone else then fills what is left.
		for (int pass = 0; pass < 2; pass++) {
			for (Citizen citizen : settlement.members()) {
				if (citizen.job() != null || !canWork(settlement, citizen)) {
					continue;
				}
				JobType job = pass == 0 ? ownTrade(citizen, quota, held) : bestJob(citizen, quota, held);
				int place = job == null ? NO_PLACE : freePlace(settlement, job);
				if (place != NO_PLACE) {
					citizen.assignJob(job, place, gameTick + LEASE_TICKS);
					held[job.ordinal()]++;
				}
			}
		}

		if (daytime) {
			workUnseen(settlement, gameTick);
		}
	}

	/**
	 * Frees hands where a job has more workers than it should while another has too few: a new
	 * building project, or food running short, pulls people off what they were doing. Those least
	 * suited to the overstaffed job are the ones to go.
	 */
	private static void rebalance(Settlement settlement, int[] quota, int[] held) {
		int wanted = 0;
		for (JobType job : JOBS) {
			wanted += Math.max(0, quota[job.ordinal()] - held[job.ordinal()]);
		}
		for (int pass = 0; pass < 2 && wanted > 0; pass++) {
			for (Citizen citizen : settlement.members()) {
				JobType job = citizen.job();
				if (wanted <= 0) {
					break;
				}
				if (job == null || held[job.ordinal()] <= quota[job.ordinal()]) {
					continue;
				}
				// First pass spares those working in their own trade.
				if (pass == 0 && citizen.affinity() == job.affinity()) {
					continue;
				}
				citizen.clearJob();
				held[job.ordinal()]--;
				wanted--;
			}
		}
	}

	/** The Leader leads and the General guards; in a tiny group everybody has to work. */
	private static boolean canWork(Settlement settlement, Citizen citizen) {
		// A child does not work, and a guard posted to a tower keeps it all day.
		if (citizen.isChild() || citizen.post() != Citizen.NO_POST) {
			return false;
		}
		return settlement.size() <= 3 || (citizen.role() != Role.LEADER && citizen.role() != Role.GENERAL);
	}

	/** How many workers each job should have. Food comes first, and more so when it is short. */
	private static int[] quotas(Settlement settlement, int workers, int[] open, int projects) {
		int[] quota = new int[JOBS.length];
		// The stock statuses are the Resource Manager's word on what is short; a shortage moves hands.
		StockStatus food = settlement.status(Commodity.FOOD);
		double farmShare = food == StockStatus.CRITICAL ? 0.65 : food == StockStatus.LOW ? 0.5 : 0.25;
		if (settlement.hasPlan(Policy.AGRARIAN)) {
			farmShare += 0.1;
		}
		int farmers = (int) Math.ceil(workers * farmShare);
		quota[JobType.FARM.ordinal()] = Math.min(farmers, open[JobType.FARM.ordinal()]);
		int rest = workers - quota[JobType.FARM.ordinal()];
		// While something is being built, a third of the hands build; half of them when the
		// store wants for nothing and gathering more would only pile it higher.
		boolean stocked = food == StockStatus.OK && settlement.status(Commodity.WOOD) == StockStatus.OK
				&& settlement.status(Commodity.STONE) == StockStatus.OK;
		// With a wall going up beside a house, each site gets at least one builder.
		int builders = Math.max(projects, stocked ? (workers + 1) / 2 : workers / 3);
		quota[JobType.BUILD.ordinal()] = Math.min(Math.min(rest, builders), open[JobType.BUILD.ordinal()]);
		rest -= quota[JobType.BUILD.ordinal()];
		quota[JobType.LEVEL.ordinal()] = Math.min(rest, open[JobType.LEVEL.ordinal()]);
		rest -= quota[JobType.LEVEL.ordinal()];
		for (JobType job : JOBS) {
			if (job.workshop() != null) {
				quota[job.ordinal()] = Math.min(rest, open[job.ordinal()]);
				rest -= quota[job.ordinal()];
			}
		}
		// Whichever of wood and stone is short gets the larger share of who is left.
		boolean woodShort = settlement.status(Commodity.WOOD) != StockStatus.OK;
		boolean stoneShort = settlement.status(Commodity.STONE) != StockStatus.OK;
		int miners = woodShort && !stoneShort ? rest / 3 : stoneShort && !woodShort ? rest - rest / 3 : rest / 2;
		quota[JobType.MINE.ordinal()] = Math.min(miners, open[JobType.MINE.ordinal()]);
		rest -= quota[JobType.MINE.ordinal()];
		quota[JobType.LUMBER.ordinal()] = Math.min(rest, open[JobType.LUMBER.ordinal()]);
		rest -= quota[JobType.LUMBER.ordinal()];
		// Hands left over go wherever there is still room, food first.
		for (int i = 0; i < JOBS.length && rest > 0; i++) {
			int extra = Math.min(rest, open[i] - quota[i]);
			quota[i] += extra;
			rest -= extra;
		}
		return quota;
	}

	/** The job this citizen is suited to, if it still needs hands. */
	private static JobType ownTrade(Citizen citizen, int[] quota, int[] held) {
		for (JobType job : JOBS) {
			if (citizen.affinity() == job.affinity() && held[job.ordinal()] < quota[job.ordinal()]) {
				return job;
			}
		}
		return null;
	}

	/** The job furthest below its quota; among equals, the one that suits this citizen. */
	private static JobType bestJob(Citizen citizen, int[] quota, int[] held) {
		JobType best = null;
		int bestScore = 0;
		for (JobType job : JOBS) {
			int missing = quota[job.ordinal()] - held[job.ordinal()];
			if (missing <= 0) {
				continue;
			}
			int score = missing * 2 + (citizen.affinity() == job.affinity() ? 3 : 0);
			if (score > bestScore) {
				best = job;
				bestScore = score;
			}
		}
		return best;
	}

	/** Whether the place a job is held at (a work site, or the building under construction) is still there. */
	private static boolean placeExists(Settlement settlement, JobType job, int place) {
		if (job == JobType.LEVEL) {
			return settlement.hasGroundWork();
		}
		if (job.workshop() != null) {
			Building workshop = settlement.workshop(job.workshop());
			return workshop != null && workshop.id() == place;
		}
		if (!job.hasWorkSite()) {
			if (place == EVERYWHERE) {
				return settlement.hasLampWork();
			}
			Building project = settlement.building(place);
			return project != null && project.stage() == Building.Stage.UNDER_CONSTRUCTION;
		}
		WorkSite site = settlement.site(place);
		return site != null && site.type() == job && !site.isExhausted();
	}

	/** Id of a place with a free position for this job, or {@link #NO_PLACE}. */
	private static int freePlace(Settlement settlement, JobType job) {
		if (job == JobType.LEVEL) {
			return EVERYWHERE;
		}
		if (job.workshop() != null) {
			Building workshop = settlement.workshop(job.workshop());
			return workshop == null ? NO_PLACE : workshop.id();
		}
		if (!job.hasWorkSite()) {
			// Whichever project has fewer hands gets the next pair, so house and wall rise together.
			int place = NO_PLACE;
			int fewest = slots(settlement, job);
			for (Building project : settlement.activeBuildings()) {
				int holders = holders(settlement, job, project.id());
				if (holders < fewest) {
					fewest = holders;
					place = project.id();
				}
			}
			// With every building site manned, the one hand left over goes round with the lamps.
			if (place == NO_PLACE && settlement.hasLampWork() && holders(settlement, job, EVERYWHERE) == 0) {
				return EVERYWHERE;
			}
			return place;
		}
		for (WorkSite site : settlement.sites()) {
			if (site.type() == job && !site.isExhausted() && holders(settlement, job, site.id()) < slots(settlement, job)) {
				return site.id();
			}
		}
		return NO_PLACE;
	}

	/**
	 * How many can work at one place. A bigger settlement works its sites harder: every stage
	 * adds a pair of hands to each field, grove, mine and building site.
	 */
	public static int slots(Settlement settlement, JobType job) {
		return job.slotsPerSite() + settlement.stage().ordinal();
	}

	private static int holders(Settlement settlement, JobType job, int place) {
		int holders = 0;
		for (Citizen citizen : settlement.members()) {
			if (citizen.job() == job && citizen.jobSite() == place) {
				holders++;
			}
		}
		return holders;
	}

	/**
	 * Work nobody is watching. A citizen without a body does not walk or swing a tool: its work is
	 * accounted for at a flat rate, straight into the stockpile. Citizens with a body act their
	 * work out in the world instead and deliver through {@link #deliver}.
	 */
	private static void workUnseen(Settlement settlement, long gameTick) {
		for (Citizen citizen : settlement.members()) {
			JobType job = citizen.job();
			if (job == null || citizen.isEmbodied()) {
				continue;
			}
			int bank = citizen.workBank() + job.abstractRate() * efficiencyPercent(settlement, citizen, job) / 100;
			if (bank >= 1000) {
				if (job.product() != null) {
					settlement.ledger().add(job.product(), bank / 1000);
				} else {
					Building project = settlement.building(citizen.jobSite());
					if (project != null && project.stage() == Building.Stage.UNDER_CONSTRUCTION) {
						project.advance(bank / 1000);
					}
				}
				bank %= 1000;
			}
			citizen.setWorkBank(bank);
			citizen.renewLease(gameTick + LEASE_TICKS);
		}
	}

	/**
	 * How fast a citizen does a job, 100 being a suited worker with bare hands. Anyone can do any
	 * job, those it does not suit more slowly; the proper tool makes anyone faster.
	 */
	public static int efficiencyPercent(Settlement settlement, Citizen citizen, JobType job) {
		int percent = citizen.affinity() == job.affinity() ? 100 : 60;
		if (citizen.has(Trait.HARDWORKING)) {
			percent += 15;
		}
		ToolKind tool = ToolKind.of(job);
		if (tool != null && Tools.equipped(settlement, citizen, tool)) {
			percent = percent * Tools.speedPercent(settlement) / 100;
		}
		// Stone cut to size by a mason goes up faster than stone as it comes from the mine.
		if (job == JobType.BUILD && settlement.hasWorker(JobType.MASON)) {
			percent = percent * MASON_BUILD_PERCENT / 100;
		}
		percent += settlement.workBonusPercent(job);
		// People in good spirits work better: from four fifths of normal at rock bottom to a fifth more.
		return percent * (80 + settlement.morale() * 2 / 5) / 100;
	}

	/** A worker with a body made progress: its lease runs on. */
	public static void reportProgress(Citizen citizen, long gameTick) {
		citizen.renewLease(gameTick + LEASE_TICKS);
	}

	/** A worker with a body reached the stockpile: its load goes into the ledger. */
	public static void deliver(Settlement settlement, Citizen citizen, long gameTick) {
		if (citizen.carrying() != null && citizen.carryingAmount() > 0) {
			settlement.ledger().add(citizen.carrying(), citizen.carryingAmount());
		}
		citizen.dropLoad();
		citizen.renewLease(gameTick + LEASE_TICKS);
	}
}
