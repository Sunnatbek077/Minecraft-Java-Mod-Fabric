package com.sunnatbek.villagerciv.mod;

import com.sunnatbek.villagerciv.mod.entity.CitizenEntity;
import com.sunnatbek.villagerciv.sim.Policy;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import java.util.HashMap;
import java.util.Map;

/** Everything the mod adds to Minecraft's registries. */
public final class ModContent {
	private static final ResourceKey<Item> RECRUITMENT_SCROLL_KEY = ResourceKey.create(Registries.ITEM, id("recruitment_scroll"));
	private static final ResourceKey<Item> FOUNDING_CHARTER_KEY = ResourceKey.create(Registries.ITEM, id("founding_charter"));
	private static final Map<Item, Policy> PLANS = new HashMap<>();

	/** The sheet a Development Plan is drawn up on; it means nothing until a direction is added. */
	public static final Item BLANK_PLAN = item("blank_plan", 16);
	public static final Item AGRARIAN_PLAN = plan("agrarian_plan", Policy.AGRARIAN);
	public static final Item INDUSTRY_PLAN = plan("industry_plan", Policy.INDUSTRY);
	public static final Item MILITARY_PLAN = plan("military_plan", Policy.MILITARY);
	/** A town's leave to grow beyond its first wall: the second stage of a patron's plans for it. */
	public static final Item EXPANSION_CHARTER = item("expansion_charter", 1);
	/** The mark that makes a blank plan into an Expansion Charter. It has no use of its own. */
	public static final Item SURVEYORS_SEAL = item("surveyors_seal", 16);

	private static final ResourceKey<Block> TOWN_CENTER_KEY = ResourceKey.create(Registries.BLOCK, id("town_center"));
	private static final ResourceKey<EntityType<?>> CITIZEN_KEY = ResourceKey.create(Registries.ENTITY_TYPE, id("citizen"));

	/** One invitation for one villager. */
	public static final Item RECRUITMENT_SCROLL = Registry.register(BuiltInRegistries.ITEM, RECRUITMENT_SCROLL_KEY,
			new Item(new Item.Properties().setId(RECRUITMENT_SCROLL_KEY).stacksTo(16)));

	/** Proposes a place for a new settlement. */
	public static final Item FOUNDING_CHARTER = Registry.register(BuiltInRegistries.ITEM, FOUNDING_CHARTER_KEY,
			new FoundingCharterItem(new Item.Properties().setId(FOUNDING_CHARTER_KEY).stacksTo(1)));

	/**
	 * Marks the centre of a settlement. It is placed by founding only (there is no item for it),
	 * drops nothing, and gives light: the camp's first protection at night.
	 */
	public static final Block TOWN_CENTER = Registry.register(BuiltInRegistries.BLOCK, TOWN_CENTER_KEY,
			new TownCenterBlock(BlockBehaviour.Properties.of().setId(TOWN_CENTER_KEY).mapColor(MapColor.STONE)
					.sound(SoundType.STONE).strength(50.0f, 1200.0f).lightLevel(_ -> 14).noLootTable()));

	/**
	 * The body of a citizen. It is never written to chunks ({@code noSave}): the citizen record is
	 * the only source of truth, so a body cannot be duplicated by chunk reloads or crashes.
	 */
	public static final EntityType<CitizenEntity> CITIZEN = Registry.register(BuiltInRegistries.ENTITY_TYPE, CITIZEN_KEY,
			EntityType.Builder.of(CitizenEntity::new, MobCategory.MISC)
					.sized(0.6f, 1.95f)
					.noSave()
					.noSummon()
					.clientTrackingRange(10)
					.build(CITIZEN_KEY));

	/** The mod's page in the crafting table's recipe book, and the prefix of the group every one of its recipes names. */
	public static final RecipeBookCategory RECIPE_PAGE = Registry.register(BuiltInRegistries.RECIPE_BOOK_CATEGORY, id("civilization"),
			new RecipeBookCategory());
	public static final String RECIPE_GROUP = "villagerciv";

	private ModContent() {
	}

	private static Item item(String name, int stackSize) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(name));
		return Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key).stacksTo(stackSize)));
	}

	private static Item plan(String name, Policy policy) {
		Item item = item(name, 1);
		PLANS.put(item, policy);
		return item;
	}

	/** The direction a plan item stands for, or null if the item is not a Development Plan. */
	public static Policy planOf(ItemStack stack) {
		return PLANS.get(stack.getItem());
	}

	static void register() {
		FabricDefaultAttributeRegistry.register(CITIZEN, CitizenEntity.createAttributes());
		// A tab of the mod's own, so that its things are found in one place and not hunted for
		// among the game's.
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, ResourceKey.create(Registries.CREATIVE_MODE_TAB, id("items")),
				FabricCreativeModeTab.builder()
						.title(Component.translatable("itemGroup.villagerciv"))
						.icon(() -> new ItemStack(FOUNDING_CHARTER))
						.displayItems((_, output) -> {
							output.accept(RECRUITMENT_SCROLL);
							output.accept(FOUNDING_CHARTER);
							output.accept(BLANK_PLAN);
							output.accept(AGRARIAN_PLAN);
							output.accept(INDUSTRY_PLAN);
							output.accept(MILITARY_PLAN);
							output.accept(SURVEYORS_SEAL);
							output.accept(EXPANSION_CHARTER);
						})
						.build());
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(VillagerCivMod.MOD_ID, path);
	}
}
