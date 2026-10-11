package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.api.CitizenId;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Citizen record: the citizen itself. An entity in the world is only its temporary body; if the
 * entity disappears, nothing about the citizen is lost.
 */
public final class Citizen {
	public static final float MAX_HEALTH = 20f;
	public static final int NO_HOME = -1;
	public static final int NO_POST = -1;

	private final CitizenId id;
	private final String name;
	private final long joinedTick;
	private final Role affinity;
	private final EnumSet<Trait> traits;
	private Role role;
	private float health = MAX_HEALTH;
	private int loyalty = 60;
	private double x;
	private double y;
	private double z;
	private JobType job;
	private int jobSite;
	private long leaseUntil;
	private int workBank;
	private Commodity carrying;
	private int carryingAmount;
	private long adultAtTick;
	private long lastChildTick = Long.MIN_VALUE / 2;
	private boolean guard;
	private boolean armored;
	private int post = NO_POST;
	private int postPlace;
	private int home = NO_HOME;
	private int bed;
	private SimRecord<?> owner;
	private boolean embodied;
	private Activity activity = Activity.IDLE;

	public Citizen(CitizenId id, String name, long joinedTick, Role affinity, Set<Trait> traits) {
		this.id = id;
		this.name = name;
		this.joinedTick = joinedTick;
		this.affinity = affinity;
		this.traits = traits.isEmpty() ? EnumSet.noneOf(Trait.class) : EnumSet.copyOf(traits);
		this.role = affinity;
	}

	public CitizenId id() {
		return id;
	}

	public String name() {
		return name;
	}

	public long joinedTick() {
		return joinedTick;
	}

	public Role affinity() {
		return affinity;
	}

	public Set<Trait> traits() {
		return Collections.unmodifiableSet(traits);
	}

	public boolean has(Trait trait) {
		return traits.contains(trait);
	}

	public Role role() {
		return role;
	}

	public void setRole(Role role) {
		if (this.role != role) {
			this.role = role;
			changed();
		}
	}

	public float health() {
		return health;
	}

	public void setHealth(float health) {
		if (this.health != health) {
			this.health = health;
			changed();
		}
	}

	/**
	 * Rest and a meal mend a citizen who has no body just now. One with a body heals there, in the
	 * world, and the record follows.
	 */
	void recover(float amount) {
		if (!embodied && health < MAX_HEALTH) {
			setHealth(Math.min(MAX_HEALTH, health + amount));
		}
	}

	/** 0–100. */
	public int loyalty() {
		return loyalty;
	}

	public void setLoyalty(int loyalty) {
		loyalty = Math.clamp(loyalty, 0, 100);
		if (this.loyalty != loyalty) {
			this.loyalty = loyalty;
			changed();
		}
	}

	public double x() {
		return x;
	}

	public double y() {
		return y;
	}

	public double z() {
		return z;
	}

	/** Last known position; kept current while embodied, and where the citizen "is" otherwise. */
	public void setPosition(double x, double y, double z) {
		if (this.x != x || this.y != y || this.z != z) {
			this.x = x;
			this.y = y;
			this.z = z;
			changed();
		}
	}

	/** The tick at which this citizen is grown up; 0 for those who joined as adults. */
	public long adultAtTick() {
		return adultAtTick;
	}

	public void setAdultAtTick(long adultAtTick) {
		this.adultAtTick = adultAtTick;
		changed();
	}

	public boolean isChild() {
		return role == Role.CHILD;
	}

	/** When this citizen last became a parent. */
	public long lastChildTick() {
		return lastChildTick;
	}

	public void setLastChildTick(long lastChildTick) {
		this.lastChildTick = lastChildTick;
		changed();
	}

	/** True for a member of the militia: works by day like anyone, and stands guard at night. */
	public boolean isGuard() {
		return guard;
	}

	public void setGuard(boolean guard) {
		if (this.guard != guard) {
			this.guard = guard;
			changed();
		}
	}

	/** Whether the citizen has a suit of armour, made for it from the settlement's ore. Kept for life. */
	public boolean isArmored() {
		return armored;
	}

	public void setArmored(boolean armored) {
		if (this.armored != armored) {
			this.armored = armored;
			changed();
		}
	}

	/**
	 * Id of the watchtower or gate this guard mans, or {@link #NO_POST}. A posted guard keeps its post
	 * day and night and holds no other job. Worked out afresh at every review; not saved.
	 */
	public int post() {
		return post;
	}

	/** Which of the places on its post this guard takes: 0 for the first to be posted there, and so on. */
	public int postPlace() {
		return postPlace;
	}

	public void setPost(int post, int place) {
		this.post = post;
		this.postPlace = place;
	}

	/** Whether this citizen fights: the General, and those on guard duty. */
	public boolean isDefender() {
		return role == Role.GENERAL || guard;
	}

	/** Id of the building this citizen sleeps in, or {@link #NO_HOME}. */
	public int home() {
		return home;
	}

	/** Which of the home's beds is this citizen's. */
	public int bed() {
		return bed;
	}

	public void setHome(int home, int bed) {
		if (this.home != home || this.bed != bed) {
			this.home = home;
			this.bed = bed;
			changed();
		}
	}

	/** The job this citizen currently holds, or null when idle. */
	public JobType job() {
		return job;
	}

	/** Id of the work site of the current job. */
	public int jobSite() {
		return jobSite;
	}

	/**
	 * A job is held on a lease: it lapses at this tick unless the citizen keeps showing progress,
	 * so work claimed by someone who is stuck or gone finds its way back to the job board.
	 */
	public long leaseUntil() {
		return leaseUntil;
	}

	public void assignJob(JobType job, int site, long leaseUntil) {
		this.job = job;
		this.jobSite = site;
		this.leaseUntil = leaseUntil;
		changed();
	}

	public void renewLease(long leaseUntil) {
		if (this.leaseUntil != leaseUntil) {
			this.leaseUntil = leaseUntil;
			changed();
		}
	}

	public void clearJob() {
		if (job != null) {
			job = null;
			workBank = 0;
			changed();
		}
	}

	/** Unfinished fraction of a unit of abstract work, in thousandths. */
	public int workBank() {
		return workBank;
	}

	public void setWorkBank(int workBank) {
		this.workBank = workBank;
		changed();
	}

	/** What the citizen is carrying back to the stockpile, or null. */
	public Commodity carrying() {
		return carrying;
	}

	public int carryingAmount() {
		return carryingAmount;
	}

	/** Adds to the load. A load is one commodity; a different one replaces what was carried. */
	public void carry(Commodity commodity, int amount) {
		if (carrying != commodity) {
			carrying = commodity;
			carryingAmount = 0;
		}
		carryingAmount += amount;
		changed();
	}

	public void dropLoad() {
		if (carryingAmount != 0 || carrying != null) {
			carrying = null;
			carryingAmount = 0;
			changed();
		}
	}

	/** What the citizen's body is doing right now. Not saved, and changes nothing. */
	public Activity activity() {
		return activity;
	}

	public void setActivity(Activity activity) {
		this.activity = activity;
	}

	/** True while an entity represents this citizen. Never saved: entities do not survive a restart. */
	public boolean isEmbodied() {
		return embodied;
	}

	public void setEmbodied(boolean embodied) {
		this.embodied = embodied;
	}

	void attach(SimRecord<?> owner) {
		this.owner = owner;
	}

	private void changed() {
		if (owner != null) {
			owner.markDirty();
		}
	}
}
