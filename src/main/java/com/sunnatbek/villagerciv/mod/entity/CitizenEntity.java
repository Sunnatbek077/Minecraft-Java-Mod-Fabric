package com.sunnatbek.villagerciv.mod.entity;

import com.sunnatbek.villagerciv.api.CitizenId;
import com.sunnatbek.villagerciv.mod.CommodityValues;
import com.sunnatbek.villagerciv.mod.EmbodimentController;
import com.sunnatbek.villagerciv.mod.ModContent;
import com.sunnatbek.villagerciv.mod.VillagerCivMod;
import com.sunnatbek.villagerciv.mod.WorkDay;
import com.sunnatbek.villagerciv.sim.Activity;
import com.sunnatbek.villagerciv.sim.Citizen;
import com.sunnatbek.villagerciv.sim.Defense;
import com.sunnatbek.villagerciv.sim.CitizenGroup;
import com.sunnatbek.villagerciv.sim.Settlement;
import com.sunnatbek.villagerciv.sim.ToolKind;
import com.sunnatbek.villagerciv.sim.Tools;
import com.sunnatbek.villagerciv.sim.Expedition;
import com.sunnatbek.villagerciv.sim.Policy;
import com.sunnatbek.villagerciv.sim.Role;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The visible body of a citizen record. It holds only transient state (path, look direction);
 * everything that matters lives in the {@link Citizen} record and is copied back regularly.
 */
public class CitizenEntity extends PathfinderMob {
	private static final int SYNC_INTERVAL_TICKS = 20;
	private static final int HEAL_INTERVAL_TICKS = 60;
	private static final double DEFEND_RADIUS = 10.0;
	private static final double SETTLEMENT_DEFEND_RADIUS = 14.0;
	private static final double GUARD_LEASH = 20.0;
	private static final double SETTLEMENT_LEASH = 12.0;
	private static final float RETREAT_HEALTH = 8.0f;
	/** Padded jackets for whoever stands guard, mail for those the store can also give a sword. */
	private static final double GUARD_ARMOR = 8.0;
	private static final double ARMED_GUARD_ARMOR = 14.0;
	private static final double MILITARY_ARMOR_BONUS = 4.0;
	private static final double CHILD_SCALE = 0.55;
	private static final double UNARMED_DAMAGE = 4.0;
	private static final double SWORD_DAMAGE = 8.0;
	/** What an iron sword in the hand adds by itself; taken off the figure above so it is not counted twice. */
	private static final double IRON_SWORD_EDGE = 5.0;
	private static final double MILITARY_DAMAGE_BONUS = 3.0;

	private CitizenGroup group;
	private Citizen citizen;
	private final java.util.List<ItemStack> pack = new java.util.ArrayList<>();

	public CitizenEntity(EntityType<? extends CitizenEntity> type, Level level) {
		super(type, level);
		// Citizens use doors; monsters cannot, which is what makes a house a refuge.
		getNavigation().setCanOpenDoors(true);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, Citizen.MAX_HEALTH)
				.add(Attributes.MOVEMENT_SPEED, 0.5)
				.add(Attributes.ATTACK_DAMAGE, UNARMED_DAMAGE)
				.add(Attributes.FOLLOW_RANGE, 48.0);
	}

	@Override
	protected void registerGoals() {
		goalSelector.addGoal(0, new FloatGoal(this));
		// Roles are positions and can change hands, so every body carries both behaviours and the
		// current role decides which one may run: the General fights, everyone else gets away.
		goalSelector.addGoal(1, new MeleeAttackGoal(this, 0.9, true) {
			@Override
			public boolean canUse() {
				return fights() && !posted() && !archer() && super.canUse();
			}

			@Override
			public boolean canContinueToUse() {
				return fights() && !posted() && !archer() && super.canContinueToUse();
			}
		});
		goalSelector.addGoal(1, new PanicGoal(this, 0.9) {
			@Override
			public boolean canUse() {
				return !fights() && !posted() && super.canUse();
			}
		});
		goalSelector.addGoal(1, new AvoidEntityGoal<>(this, Monster.class, 6.0f, 0.7, 0.9) {
			@Override
			public boolean canUse() {
				return !fights() && !posted() && super.canUse();
			}
		});
		// A guard with a tower to man goes there before anything else, and stays.
		goalSelector.addGoal(2, new TowerPostGoal(this));
		goalSelector.addGoal(2, new PatrolGoal(this));
		goalSelector.addGoal(2, new FollowExpeditionGoal(this));
		goalSelector.addGoal(1, new OpenDoorGoal(this, true));
		// Before going home for the night: of two goals of one rank, the first listed starts first.
		goalSelector.addGoal(2, new HaulGoal(this));
		goalSelector.addGoal(2, new GoHomeGoal(this));
		goalSelector.addGoal(2, new StayInSettlementGoal(this));
		goalSelector.addGoal(3, new WorkGoal(this));
		goalSelector.addGoal(3, new BuildGoal(this));
		goalSelector.addGoal(3, new LampGoal(this, true));
		goalSelector.addGoal(3, new WorkshopGoal(this));
		goalSelector.addGoal(3, new HerdGoal(this));
		goalSelector.addGoal(3, new StudyGoal(this));
		goalSelector.addGoal(3, new LandscapeGoal(this, true));
		goalSelector.addGoal(4, new LandscapeGoal(this, false));
		goalSelector.addGoal(4, new LampGoal(this, false));
		goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 0.5) {
			@Override
			public boolean canUse() {
				// By day only: at night a stroll would lead straight back out of the door. Those on
				// watch are the exception; walking about at night is their job.
				return settlement() != null && (WorkDay.isDay(level()) || isDefender()) && super.canUse();
			}
		});
		goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 8.0f));
		goalSelector.addGoal(6, new RandomLookAroundGoal(this));

		targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, Monster.class, true,
				(target, _) -> !(target instanceof Creeper)) {
			@Override
			public boolean canUse() {
				return fights() && super.canUse();
			}

			@Override
			public boolean canContinueToUse() {
				return fights() && isNearPlayer() && super.canContinueToUse();
			}

			@Override
			protected double getFollowDistance() {
				// At home a defender answers for the whole place, on the road only for those close by.
				return settlement() != null ? SETTLEMENT_DEFEND_RADIUS : DEFEND_RADIUS;
			}
		});
	}

	/** A guard whose place is on a watchtower: it shoots from there and does not come down to fight or flee. */
	private boolean posted() {
		return citizen != null && citizen.post() != Citizen.NO_POST;
	}

	/** A guard who patrols with a crossbow: it keeps its distance and shoots, where a sword closes in. */
	private boolean archer() {
		return citizen != null && citizen.patrol() == Citizen.Patrol.CROSSBOW;
	}

	/** Bundles of ore and the like picked up at work, on the way to the citizen's chest. Not kept across a restart. */
	public java.util.List<ItemStack> pack() {
		return pack;
	}

	/** The General and the guards are the settlement's defenders. */
	private boolean isDefender() {
		return citizen != null && citizen.isDefender();
	}

	/**
	 * Whether this citizen stands and fights right now. Everyone else gets out of the way, and so
	 * does a defender who is badly hurt: it falls back, mends, and comes out again, rather than
	 * fight to the death.
	 */
	private boolean fights() {
		// A guard on patrol does not fall back: seeing a monster off is what it is there for.
		return isDefender() && (getHealth() > RETREAT_HEALTH || (citizen != null && citizen.patrol() != Citizen.Patrol.NONE));
	}

	/**
	 * Defenders guard their people, not the countryside: no chasing far from the player an
	 * expedition travels with, or from the Town Center of a settlement.
	 */
	private boolean isNearPlayer() {
		Settlement settlement = settlement();
		if (settlement != null) {
			// Anywhere on the settlement's own ground, however far that has spread from its Town
			// Center, and a little way out from it.
			int[] bounds = settlement.groundBounds();
			return (getX() >= bounds[0] - SETTLEMENT_LEASH && getX() <= bounds[2] + SETTLEMENT_LEASH
					&& getZ() >= bounds[1] - SETTLEMENT_LEASH && getZ() <= bounds[3] + SETTLEMENT_LEASH);
		}
		ServerPlayer player = followTarget();
		return player == null || distanceToSqr(player) < GUARD_LEASH * GUARD_LEASH;
	}

	/**
	 * Ties this body to its record. Called before the entity enters the world, and again when the
	 * citizen's group changes (an expedition settling down).
	 */
	public void bind(CitizenGroup group, Citizen citizen) {
		this.group = group;
		this.citizen = citizen;
		setHealth(Math.clamp(citizen.health(), 1f, getMaxHealth()));
		setCustomName(Component.literal(citizen.name()));
		// Its pack is as the record has it: what it was carrying when it last had a body.
		pack.clear();
		citizen.finds().forEach((id, count) -> {
			net.minecraft.resources.Identifier key = net.minecraft.resources.Identifier.tryParse(id);
			net.minecraft.world.item.Item item = key == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(key).orElse(null);
			for (int left = count; item != null && left > 0; left -= 64) {
				pack.add(new ItemStack(item, Math.min(64, left)));
			}
		});
	}

	public Citizen citizen() {
		return citizen;
	}

	public CitizenId citizenId() {
		return citizen == null ? null : citizen.id();
	}

	public CitizenGroup group() {
		return group;
	}

	/** The expedition this citizen travels with, or null if it has settled. */
	public Expedition expedition() {
		return group instanceof Expedition expedition ? expedition : null;
	}

	/** The settlement this citizen lives in, or null while it is still travelling. */
	public Settlement settlement() {
		return group instanceof Settlement settlement ? settlement : null;
	}

	/** The player to follow right now, or null when the expedition is not travelling. */
	ServerPlayer followTarget() {
		Expedition expedition = expedition();
		if (expedition == null || expedition.state() != Expedition.State.FOLLOWING || !(level() instanceof ServerLevel level)) {
			return null;
		}
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(expedition.player());
		return player != null && player.isAlive() && player.level() == level ? player : null;
	}

	/** Copies the state that must survive this body into the record. */
	public void syncToRecord() {
		if (citizen != null && isAlive()) {
			citizen.setPosition(getX(), getY(), getZ());
			citizen.setHealth(getHealth());
			// The pack goes into the record as well: a body is not saved, and what it carried
			// would otherwise be gone the next time the world is opened.
			java.util.Map<String, Integer> finds = new java.util.LinkedHashMap<>();
			for (ItemStack stack : pack) {
				if (!stack.isEmpty()) {
					finds.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
				}
			}
			citizen.setFinds(finds);
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide() || !isAlive() || tickCount % SYNC_INTERVAL_TICKS != 0) {
			return;
		}
		// A sleeper runs no goals, so waking up has to happen here: at daybreak, or if the
		// citizen is no longer one who sleeps (it left its settlement, or became the General).
		if (isSleeping() && (WorkDay.isDay(level()) || settlement() == null || citizen.isDefender())) {
			stopSleeping();
		}
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		if (embodiment == null || !embodiment.isBody(this)) {
			// A stray body (not the one created for its record) must not exist.
			discard();
			return;
		}
		// A fed citizen mends: one point every few seconds, so a wounded General is whole again in
		// a couple of minutes. A starving group does not heal.
		if (tickCount % HEAL_INTERVAL_TICKS == 0 && getHealth() < getMaxHealth() && !group.isStarving()) {
			heal(1.0f);
		}
		syncToRecord();
		// What an onlooker would say it is doing, where no goal has said so already.
		if (isSleeping()) {
			citizen.setActivity(Activity.SLEEPING);
		} else if (expedition() != null) {
			citizen.setActivity(Activity.FOLLOWING);
		} else if (!WorkDay.isDay(level())) {
			if (citizen.isDefender()) {
				citizen.setActivity(Activity.ON_WATCH);
			} else if (citizen.activity() != Activity.GOING_HOME) {
				citizen.setActivity(Activity.RESTING);
			}
		} else if (citizen.activity() == Activity.SLEEPING || citizen.activity() == Activity.RESTING
				|| citizen.activity() == Activity.ON_WATCH || citizen.activity() == Activity.FOLLOWING) {
			citizen.setActivity(Activity.IDLE);
		}
		// Children are small.
		getAttribute(Attributes.SCALE).setBaseValue(citizen.isChild() ? CHILD_SCALE : 1.0);
		// A General with a sword from the settlement's store hits twice as hard.
		Settlement settlement = settlement();
		// On the road nobody has a store to arm from: an expedition's General counts as armed.
		boolean armed = settlement != null ? Tools.equipped(settlement, citizen, ToolKind.SWORD) : isDefender();
		// A settlement that set its mind on defence trains its General harder.
		double drill = settlement != null && settlement.hasPlan(Policy.MILITARY) ? MILITARY_DAMAGE_BONUS : 0.0;
		// The sword is in its hand for all to see, and the blade itself adds its edge to the blow.
		boolean sword = armed && isDefender() && !posted() && !archer();
		getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue((armed ? SWORD_DAMAGE - (sword ? IRON_SWORD_EDGE : 0.0) : UNARMED_DAMAGE) + drill);
		// Defenders are protected as well as armed; a civilian's best defence stays a closed door.
		// One who wears a suit of iron is protected by the suit itself; the figures here stand in for it until then.
		double armor = !isDefender() ? 0.0
				: (citizen.isArmored() ? 0.0 : armed ? ARMED_GUARD_ARMOR : GUARD_ARMOR) + (drill > 0 ? MILITARY_ARMOR_BONUS : 0.0);
		getAttribute(Attributes.ARMOR).setBaseValue(armor);
		// Armour made from the settlement's ore is real armour, worn; and a tower guard carries a crossbow.
		wear(EquipmentSlot.HEAD, citizen.isArmored() ? Items.IRON_HELMET : null);
		wear(EquipmentSlot.CHEST, citizen.isArmored() ? Items.IRON_CHESTPLATE : null);
		wear(EquipmentSlot.LEGS, citizen.isArmored() ? Items.IRON_LEGGINGS : null);
		wear(EquipmentSlot.FEET, citizen.isArmored() ? Items.IRON_BOOTS : null);
		// A librarian is known by what it keeps: its enchantment stands beside its name.
		net.minecraft.core.Holder.Reference<net.minecraft.world.item.enchantment.Enchantment> kept =
				settlement != null && level() instanceof net.minecraft.server.level.ServerLevel serverLevel
						? com.sunnatbek.villagerciv.mod.Librarians.enchantmentOf(serverLevel, settlement, citizen) : null;
		Component shown = kept == null ? Component.literal(citizen.name())
				: Component.literal(citizen.name() + " · ").append(kept.value().description());
		if (!shown.equals(getCustomName())) {
			setCustomName(shown);
		}
		wear(EquipmentSlot.MAINHAND, posted() || archer() ? Items.CROSSBOW : sword ? Items.IRON_SWORD : toolInHand(settlement));
	}

	/** The tool of a worker's trade, if the settlement's store has one for it; shown in its hand. */
	private net.minecraft.world.item.Item toolInHand(Settlement settlement) {
		if (settlement == null || citizen.job() == null || isDefender()) {
			return null;
		}
		// A tradesman's tool is part of the workshop, not something the store hands out.
		switch (citizen.job()) {
			case SMITH -> {
				return Items.MACE; // the nearest thing the game has to a smith's hammer
			}
			case MASON -> {
				return Items.IRON_PICKAXE;
			}
			case TRADE -> {
				return Items.EMERALD;
			}
			case HERD -> {
				return Items.WHEAT;
			}
			case STUDY -> {
				return Items.BOOK;
			}
			default -> {
			}
		}
		ToolKind kind = ToolKind.of(citizen.job());
		if (kind == null || !Tools.equipped(settlement, citizen, kind)) {
			return null;
		}
		return switch (kind) {
			case HOE -> Items.IRON_HOE;
			case AXE -> Items.IRON_AXE;
			case PICKAXE -> Items.IRON_PICKAXE;
			case SHOVEL -> Items.IRON_SHOVEL;
			case SWORD -> null;
		};
	}

	/** Puts an item into an equipment slot, or empties the slot; nothing a citizen wears is ever dropped. */
	private void wear(EquipmentSlot slot, net.minecraft.world.item.Item item) {
		ItemStack worn = getItemBySlot(slot);
		if (item == null ? !worn.isEmpty() : !worn.is(item)) {
			setItemSlot(slot, item == null ? ItemStack.EMPTY : new ItemStack(item));
			setDropChance(slot, 0.0f);
		}
	}

	/** An attack by a monster is what a settlement's sense of danger is made of. */
	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		// Being walled in is never the citizen's doing: a tree grew, or a builder set a block,
		// where it stood. It climbs out on top instead of dying there.
		if (source.is(DamageTypes.IN_WALL)) {
			int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, getBlockX(), getBlockZ());
			getNavigation().stop();
			teleportTo(getBlockX() + 0.5, top, getBlockZ() + 0.5);
			return false;
		}
		// A guard's bolt that goes astray does not wound its own people.
		if (source.getEntity() instanceof CitizenEntity) {
			return false;
		}
		boolean hurt = super.hurtServer(level, source, amount);
		if (hurt && source.getEntity() instanceof Monster && settlement() != null) {
			Defense.incident(settlement(), isDeadOrDying() ? Defense.KILLED : Defense.HURT);
		}
		return hurt;
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		if (!level().isClientSide() && embodiment != null) {
			embodiment.onDeath(this, source);
		}
	}

	@Override
	public void remove(RemovalReason reason) {
		// Give the bed back: a body that vanishes asleep would leave it marked as taken for good.
		if (!level().isClientSide() && isSleeping()) {
			stopSleeping();
		}
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		if (!level().isClientSide() && embodiment != null) {
			embodiment.onBodyRemoved(this);
		}
		super.remove(reason);
	}

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		if (citizen == null || embodiment == null || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.SUCCESS;
		}
		boolean ownPlayer = player.getUUID().equals(group.player());
		ItemStack stack = player.getItemInHand(hand);
		Policy plan = ModContent.planOf(stack);
		if (plan != null && group instanceof Settlement settlement) {
			// A plan is handed to the Leader (or left at the Town Center for the Leader to read).
			embodiment.proposePlan(serverPlayer, settlement, stack, plan);
			return InteractionResult.SUCCESS;
		}
		if (citizen.job() == com.sunnatbek.villagerciv.sim.JobType.STUDY && group instanceof Settlement settlement
				&& level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
			// A librarian is spoken to about its enchantment; the settlement's own screen is at the Town Center.
			if (hand == InteractionHand.MAIN_HAND) {
				com.sunnatbek.villagerciv.mod.Librarians.speak(serverPlayer, serverLevel, settlement, citizen, stack);
			}
			return InteractionResult.SUCCESS;
		}
		FoodProperties food = stack.get(DataComponents.FOOD);
		if (ownPlayer && food != null) {
			// Sneaking hands over the whole stack.
			int items = player.isShiftKeyDown() ? stack.getCount() : 1;
			int meals = CommodityValues.mealsOf(food) * items;
			stack.consume(items, player);
			embodiment.feed(serverPlayer, this, meals);
		} else if (hand == InteractionHand.MAIN_HAND) {
			if (ownPlayer && group instanceof Expedition expedition) {
				embodiment.showExpedition(serverPlayer, expedition);
			} else if (group instanceof Settlement settlement) {
				embodiment.showSettlement(serverPlayer, settlement);
			} else {
				serverPlayer.sendSystemMessage(Component.translatable("message.villagerciv.citizen.info",
						citizen.name(), EmbodimentController.roleName(citizen.role())), true);
			}
		}
		return InteractionResult.SUCCESS;
	}

	/**
	 * A sleeper lies still. The game itself no longer holds a sleeping mob in place: left to it,
	 * the citizen's goals keep running and it wanders off in its sleep, still lying down.
	 */
	@Override
	protected boolean isImmobile() {
		return super.isImmobile() || isSleeping();
	}

	// Citizens never despawn and never leave the Overworld; the record decides where they are.

	@Override
	public boolean removeWhenFarAway(double distanceSqr) {
		return false;
	}

	@Override
	public boolean canUsePortal(boolean allowVehicles) {
		return false;
	}

	// Travelling with a player must not be deadly because of the route the player picks.

	@Override
	public boolean causeFallDamage(double fallDistance, float damageMultiplier, DamageSource source) {
		return expedition() == null && super.causeFallDamage(fallDistance, damageMultiplier, source);
	}

	// No entity-to-entity pushing: in a crowded town it is pure cost.

	/**
	 * Citizens make room for one another, so that a dozen of them heading for the same spot end
	 * up standing round it and not inside each other. Only one who is on the move or standing
	 * about gives way: nobody is shouldered off a tower, out of bed, or away from the bench, the
	 * wall or the field it is working at.
	 */
	@Override
	public boolean isPushable() {
		if (citizen == null || posted() || isSleeping()) {
			return false;
		}
		Activity activity = citizen.activity();
		return activity != Activity.WORKING && activity != Activity.BUILDING && activity != Activity.SLEEPING && activity != Activity.AT_WORK;
	}

	/**
	 * A crowd never crushes a citizen. The game hurts a creature that stands in a press of too
	 * many others; citizens are often set down in one spot together (newcomers by the Town
	 * Center, a crew called back from a far wall), and making room for one another must not turn
	 * into dying of it.
	 */
	@Override
	public boolean isInvulnerableTo(net.minecraft.server.level.ServerLevel level, DamageSource source) {
		return source.is(net.minecraft.world.damagesource.DamageTypes.CRAMMING) || super.isInvulnerableTo(level, source);
	}

	/** And only one another: animals, monsters and players do not push a citizen about, as they never did. */
	@Override
	public void push(Entity other) {
		if (other instanceof CitizenEntity) {
			super.push(other);
		}
	}

	@Override
	protected void doPush(Entity other) {
		if (other instanceof CitizenEntity) {
			super.doPush(other);
		}
	}

	@Override
	protected SoundEvent getAmbientSound() {
		return SoundEvents.VILLAGER_AMBIENT;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return SoundEvents.VILLAGER_HURT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return SoundEvents.VILLAGER_DEATH;
	}
}
