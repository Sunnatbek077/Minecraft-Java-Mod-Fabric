package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.build.Blueprint;
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
	/** The place of the builders of a castle: the castle as a whole, not a building on the list. */
	public static final int CASTLE = -2;
	private static final int MIN_WORKERS_FOR_LEVELLER = 6;
	public static final int MASON_BUILD_PERCENT = 130;
	/** How many mouths one farmer is counted on to feed: the floor under the number of farmers. */
	public static final int FED_BY_ONE_FARMER = 10;
	/** From this many workers up, a settlement always keeps at least one lumberjack and one miner. */
	private static final int MIN_WORKERS_FOR_EVERY_TRADE = 8;
	/** While a wall is still to be built, its material counts as wanted until this much of it lies ready. */
	private static final int WALL_RESERVE = 3_000;
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
		// A castle going up takes a brigade of its own.
		int brigade = Castle.isBuilding(settlement) ? Castle.BRIGADE : 0;
		open[JobType.BUILD.ordinal()] = projects * slots(settlement, JobType.BUILD) + lamps + brigade;

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
				// A librarian keeps its enchantment for good: its place at the lectern never lapses.
				boolean lapsed = gameTick > citizen.leaseUntil() && citizen.job() != JobType.STUDY;
				if (lapsed || !placeExists(settlement, citizen.job(), citizen.jobSite())) {
					citizen.clearJob();
				} else {
					held[citizen.job().ordinal()]++;
				}
			}
		}

		// Levellers: one for every eight hands, in a settlement big enough to spare them, while there is ground to mend.
		// When whole hillsides stand inside its bounds, levelling is the main work: two hands in five.
		open[JobType.LEVEL.ordinal()] = settlement.hasGroundWork() && workers >= MIN_WORKERS_FOR_LEVELLER
				? Math.max(1, settlement.hasHeavyGroundWork() ? workers * 2 / 5 : workers / 8) : 0;
		// A workshop takes one of its trade; a town's is busy enough for two.
		for (JobType job : JOBS) {
			if (job.workshop() != null && settlement.workshop(job.workshop()) != null) {
				open[job.ordinal()] = settlement.stage().ordinal() >= SettlementStage.TOWN.ordinal() ? 2 : 1;
			}
		}
		// A pen takes one herder.
		open[JobType.HERD.ordinal()] = settlement.pens().size();
		// A lectern takes one librarian, and there is no call for more of them than there are enchantments.
		open[JobType.STUDY.ordinal()] = Math.min(settlement.studies(), settlement.libraries().size() * Blueprint.LIBRARY_SEATS);
		settlement.watchStock(gameTick);
		int[] quota = quotas(settlement, workers, open, projects + lamps, held[JobType.STUDY.ordinal()]);
		// A shortage that is over lets its helpers go, all at once, so that each can have its
		// own place back: taken one at a time, they would find those places held by one another.
		for (Citizen citizen : settlement.members()) {
			if (citizen.isCalledAway() && (citizen.job().product() == null || settlement.shortage(citizen.job().product()) == 0)) {
				held[citizen.job().ordinal()]--;
				citizen.clearJob();
			}
		}
		rebalance(settlement, quota, held);
		// Hands that are free go first to whatever the settlement is short of. After that
		// everyone goes back to the work that is usually theirs, then those who know a trade get
		// first pick of it, and everyone else fills what is left.
		for (int pass = 0; pass < 4; pass++) {
			for (Citizen citizen : settlement.members()) {
				if (citizen.job() != null || !canWork(settlement, citizen)) {
					continue;
				}
				JobType job = switch (pass) {
					case 0 -> shortTrade(settlement, quota, held);
					case 1 -> citizen.usualJob() != null && held[citizen.usualJob().ordinal()] < quota[citizen.usualJob().ordinal()]
							? citizen.usualJob() : null;
					case 2 -> ownTrade(citizen, quota, held);
					default -> bestJob(citizen, quota, held);
				};
				int place = job == null ? NO_PLACE : freePlace(settlement, job);
				if (place != NO_PLACE) {
					citizen.assignJob(job, place, gameTick + LEASE_TICKS);
					held[job.ordinal()]++;
					// Work taken up to make good a shortage is a call away from one's own, not a
					// change of it: when the shortage is over, the old work is gone back to.
					boolean calledAway = job.product() != null && settlement.shortage(job.product()) > 0 && citizen.usualJob() != null;
					if (!calledAway) {
						citizen.setUsualJob(job);
					}
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
		// A child does not work, and a guard posted to a tower or walking the rounds does so all day.
		if (citizen.isChild() || citizen.isOnDuty()) {
			return false;
		}
		return settlement.size() <= 3 || (citizen.role() != Role.LEADER && citizen.role() != Role.GENERAL);
	}

	/** How many workers each job should have. Food comes first, and more so when it is short. */
	private static int[] quotas(Settlement settlement, int workers, int[] open, int projects, int librarians) {
		int[] quota = new int[JOBS.length];
		// Those who keep an enchantment are not hands to be moved about: whatever the settlement
		// is short of, they stay at their lecterns, and the rest is shared out among the others.
		int seated = Math.min(librarians, open[JobType.STUDY.ordinal()]);
		int rest = workers - seated;
		StockStatus food = settlement.status(Commodity.FOOD);

		// First, enough farmers that nobody goes hungry: one for every so many mouths, and a
		// great many more than that when the store is running out.
		int mouths = settlement.size() - settlement.children() / 2;
		int feeders = food == StockStatus.CRITICAL ? (int) Math.ceil(workers * 0.65)
				: food == StockStatus.LOW ? (int) Math.ceil(workers * 0.5)
				: Math.max(1, (mouths + FED_BY_ONE_FARMER - 1) / FED_BY_ONE_FARMER);
		// A granary that is still full but emptying fast is seen to before it is low.
		if (food == StockStatus.OK && settlement.shortage(Commodity.FOOD) > 0) {
			feeders = Math.max(feeders, (int) Math.ceil(workers * 0.3));
		}
		quota[JobType.FARM.ordinal()] = Math.min(Math.min(rest, feeders), open[JobType.FARM.ordinal()]);
		rest -= quota[JobType.FARM.ordinal()];

		// Next, whatever else is running out. Wood or stone that is low, or is being used faster
		// than it comes in, calls hands away from everything that can wait: the lecterns, the
		// levelling, the building itself. They go back when the store is made good.
		JobType[] gatherers = {JobType.MINE, JobType.LUMBER};
		boolean calling = false;
		for (JobType job : gatherers) {
			int shortage = settlement.shortage(job.product());
			int wanted = shortage == 2 ? (int) Math.ceil(workers * 0.3) : shortage == 1 ? (int) Math.ceil(workers * 0.15)
					// And never nobody at all: a settlement of any size keeps a hand at each.
					: workers >= MIN_WORKERS_FOR_EVERY_TRADE ? 1 : 0;
			calling |= shortage > 0;
			settlement.setSiteWish(job, shortage > 0 && wanted - open[job.ordinal()] >= 2);
			quota[job.ordinal()] = Math.min(Math.min(rest, wanted), open[job.ordinal()]);
			rest -= quota[job.ordinal()];
		}

		// While something is being built, a third of the hands build; half of them when the
		// store wants for nothing and gathering more would only pile it higher.
		boolean stocked = food == StockStatus.OK && settlement.status(Commodity.WOOD) == StockStatus.OK
				&& settlement.status(Commodity.STONE) == StockStatus.OK;
		// With a wall going up beside a house, each site gets at least one builder.
		// A big place has other things to do besides: there a quarter build, however many crews are out.
		int builders = Math.max(projects, workers >= 40 ? workers / 4 : stocked ? (workers + 1) / 2 : workers / 3);
		if (Castle.isBuilding(settlement)) {
			builders += Castle.BRIGADE;
		}
		// Levelling can wait while something is short: one keeps at it, the rest are called away.
		// But hillsides are not left standing because the builders are using wood quickly: with
		// that much ground to mend, only a store that is all but empty takes the levellers off it.
		boolean dire = settlement.shortage(Commodity.WOOD) == 2 || settlement.shortage(Commodity.STONE) == 2;
		int levellers = (settlement.hasHeavyGroundWork() ? dire : calling) ? Math.min(1, open[JobType.LEVEL.ordinal()]) : open[JobType.LEVEL.ordinal()];
		// Ground that is all hills comes before what is to be built on it; a patch here and there comes after.
		JobType[] order = settlement.hasHeavyGroundWork() ? new JobType[] {JobType.LEVEL, JobType.BUILD} : new JobType[] {JobType.BUILD, JobType.LEVEL};
		for (JobType job : order) {
			int wanted = job == JobType.BUILD ? Math.min(builders, open[JobType.BUILD.ordinal()]) : levellers;
			quota[job.ordinal()] = Math.min(rest, wanted);
			rest -= quota[job.ordinal()];
		}
		for (JobType job : JOBS) {
			if (job.workshop() != null || job == JobType.HERD || job == JobType.STUDY) {
				if (job == JobType.STUDY) {
					// Learning is for a place that can feed it: new librarians are taken on only up
					// to half of the hands (a quarter while the store is short of something). Those
					// already seated are kept whatever that count comes to.
					int most = Math.min(open[job.ordinal()], stocked ? workers / 2 : workers / 4);
					int more = Math.min(rest, Math.max(0, most - seated));
					quota[job.ordinal()] = seated + more;
					rest -= more;
					continue;
				}
				quota[job.ordinal()] = Math.min(rest, open[job.ordinal()]);
				rest -= quota[job.ordinal()];
			}
		}

		// Everyone else brings something in, and is put where it is needed: each of food, wood
		// and stone is weighed by how short the settlement is of it, and the hands are shared
		// out in that proportion. A full granary does not keep a third of the people in the
		// fields while the builders wait for stone.
		JobType[] producers = {JobType.FARM, JobType.LUMBER, JobType.MINE};
		double[] need = {needOfFood(settlement), needOf(settlement, Commodity.WOOD), needOf(settlement, Commodity.STONE)};
		double total = need[0] + need[1] + need[2];
		int pool = rest;
		for (int turn = 0; turn < producers.length; turn++) {
			// The most needed first, so that it is the least needed that goes short of hands.
			int most = -1;
			for (int i = 0; i < producers.length; i++) {
				if (need[i] >= 0 && (most < 0 || need[i] > need[most])) {
					most = i;
				}
			}
			int job = producers[most].ordinal();
			int wanted = (int) Math.round(pool * need[most] / total);
			int given = Math.min(Math.min(rest, wanted), open[job] - quota[job]);
			// More hands wanted here than there are places for, and not merely because the store
			// is full of everything: the land could do with another such place.
			settlement.setSiteWish(producers[most], (producers[most] != JobType.FARM && settlement.wishesSite(producers[most])) || (need[most] >= 1.0 && quota[job] + wanted - open[job] >= 2));
			quota[job] += given;
			rest -= given;
			need[most] = -1;
		}
		// Hands left over go wherever there is still room, food first.
		for (int i = 0; i < JOBS.length && rest > 0; i++) {
			int extra = Math.min(rest, open[i] - quota[i]);
			quota[i] += extra;
			rest -= extra;
		}
		return quota;
	}

	/** How much the settlement wants more food brought in: a great deal when the store is low, little when it would last for weeks. */
	private static double needOfFood(Settlement settlement) {
		StockStatus status = settlement.status(Commodity.FOOD);
		int days = settlement.foodDays();
		double need = status == StockStatus.CRITICAL ? 8.0 : status == StockStatus.LOW ? 5.0
				: days < 10 ? 3.0 : days < 20 ? 1.5 : days < 40 ? 0.8 : days < 100 ? 0.4 : 0.15;
		return settlement.hasPlan(Policy.AGRARIAN) ? need * 1.25 : need;
	}

	/** The same for wood or stone: by what the next building is waiting for, by what the wall will take, and by how much lies in store. */
	private static double needOf(Settlement settlement, Commodity commodity) {
		int stock = settlement.ledger().available(commodity);
		double need = settlement.status(commodity) != StockStatus.OK ? 4.0 : stock < 500 ? 2.0 : stock < 2_000 ? 1.0 : stock < 5_000 ? 0.5 : 0.25;
		// A wall still to be built is a long hunger for one material: stone for a rampart, wood for a palisade.
		if (!settlement.wallPlan().isEmpty() && stock < WALL_RESERVE) {
			boolean stoneWall = settlement.wallPlan().getFirst().blueprint().startsWith("rampart_")
					|| settlement.wallPlan().getFirst().blueprint().startsWith("stone_");
			if (stoneWall == (commodity == Commodity.STONE)) {
				need = Math.max(2.0, need * 2);
			}
		}
		return settlement.hasPlan(Policy.INDUSTRY) ? need * 1.25 : need;
	}

	/** The trade that brings in what the settlement is shortest of, if it still needs hands; null if nothing is short. */
	private static JobType shortTrade(Settlement settlement, int[] quota, int[] held) {
		JobType worst = null;
		int worstShortage = 0;
		for (JobType job : new JobType[] {JobType.FARM, JobType.MINE, JobType.LUMBER}) {
			int shortage = settlement.shortage(job.product());
			if (shortage > worstShortage && held[job.ordinal()] < quota[job.ordinal()]) {
				worst = job;
				worstShortage = shortage;
			}
		}
		return worst;
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
		if (job == JobType.HERD) {
			Building pen = settlement.building(place);
			return pen != null && Blueprint.isPen(pen.blueprint()) && pen.stage() == Building.Stage.COMPLETE;
		}
		if (job == JobType.STUDY) {
			Building library = settlement.building(place);
			return library != null && library.blueprint().equals(Blueprint.LIBRARY) && library.stage() == Building.Stage.COMPLETE;
		}
		if (!job.hasWorkSite()) {
			if (place == CASTLE) {
				return Castle.isBuilding(settlement);
			}
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
		if (job == JobType.STUDY) {
			for (Building library : settlement.libraries()) {
				if (holders(settlement, job, library.id()) < Blueprint.LIBRARY_SEATS) {
					return library.id();
				}
			}
			return NO_PLACE;
		}
		if (job == JobType.HERD) {
			for (Building pen : settlement.pens()) {
				if (holders(settlement, job, pen.id()) == 0) {
					return pen.id();
				}
			}
			return NO_PLACE;
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
			// The rest go to the castle's brigade.
			if (place == NO_PLACE && Castle.isBuilding(settlement) && holders(settlement, job, CASTLE) < Castle.BRIGADE) {
				return CASTLE;
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
			// A load that was in hand when the citizen's body went (the world was closed, or the
			// player walked off) is not left hanging: it counts as brought in.
			// Unless it is a pack of particular things: those wait for their bearer to walk them in.
			if (citizen.carryingAmount() > 0 && citizen.finds().isEmpty()) {
				deliver(settlement, citizen, gameTick);
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
