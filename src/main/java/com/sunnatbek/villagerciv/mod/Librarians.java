package com.sunnatbek.villagerciv.mod;

import com.sunnatbek.villagerciv.build.Blueprint;
import com.sunnatbek.villagerciv.build.Building;
import com.sunnatbek.villagerciv.sim.Citizen;
import com.sunnatbek.villagerciv.sim.JobType;
import com.sunnatbek.villagerciv.sim.Settlement;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The enchantments a settlement's librarians keep. There is one librarian to each enchantment the
 * game has: the first lectern of the first library keeps the first of them, and so on down the
 * list. A librarian writes its enchantment, at its highest level, into a book its patron brings,
 * for emeralds.
 */
public final class Librarians {
	/** Emeralds for each level of an enchantment; one that cannot be had from an enchanting table costs double. */
	private static final int EMERALDS_PER_LEVEL = 8;

	private Librarians() {
	}

	/** Every enchantment there is, in an order that does not change from one day to the next. Curses are not kept. */
	public static List<Holder.Reference<Enchantment>> all(ServerLevel level) {
		List<Holder.Reference<Enchantment>> all = new ArrayList<>(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElements()
				.filter(enchantment -> !enchantment.is(EnchantmentTags.CURSE)).toList());
		all.sort(Comparator.comparing(enchantment -> enchantment.key().identifier().toString()));
		return all;
	}

	/** Which lectern in the settlement is this librarian's, counting through its libraries in the order they were built; -1 if it has none. */
	public static int lecternOf(Settlement settlement, Citizen librarian) {
		if (librarian.job() != JobType.STUDY) {
			return -1;
		}
		List<Building> libraries = settlement.libraries();
		for (int i = 0; i < libraries.size(); i++) {
			if (libraries.get(i).id() == librarian.jobSite()) {
				// Its seat among those who share the building: by name, so it stays put while they do.
				int seat = 0;
				for (Citizen other : settlement.members()) {
					if (other != librarian && other.job() == JobType.STUDY && other.jobSite() == librarian.jobSite()
							&& other.id().toString().compareTo(librarian.id().toString()) < 0) {
						seat++;
					}
				}
				return seat >= Blueprint.LIBRARY_SEATS ? -1 : i * Blueprint.LIBRARY_SEATS + seat;
			}
		}
		return -1;
	}

	/** The enchantment this librarian keeps, or null if it keeps none (there are more librarians than enchantments, or it has no lectern). */
	public static Holder.Reference<Enchantment> enchantmentOf(ServerLevel level, Settlement settlement, Citizen librarian) {
		int lectern = lecternOf(settlement, librarian);
		List<Holder.Reference<Enchantment>> all = lectern < 0 ? List.of() : all(level);
		return lectern < all.size() && lectern >= 0 ? all.get(lectern) : null;
	}

	static int price(Holder<Enchantment> enchantment) {
		int price = EMERALDS_PER_LEVEL * enchantment.value().getMaxLevel();
		return enchantment.is(EnchantmentTags.TREASURE) ? price * 2 : price;
	}

	/**
	 * A patron speaks to a librarian. With a book in hand and the emeralds for it, the book is
	 * enchanted; otherwise the librarian says what it keeps and what that costs. Sneaking, it
	 * says who in the settlement keeps what.
	 */
	public static void speak(ServerPlayer player, ServerLevel level, Settlement settlement, Citizen librarian, ItemStack inHand) {
		if (player.isShiftKeyDown()) {
			list(player, level, settlement);
			return;
		}
		Holder.Reference<Enchantment> enchantment = enchantmentOf(level, settlement, librarian);
		if (enchantment == null) {
			player.sendSystemMessage(Component.translatable("message.villagerciv.librarian.none", librarian.name()), true);
			return;
		}
		int levelOf = enchantment.value().getMaxLevel();
		Component name = Enchantment.getFullname(enchantment, levelOf);
		int price = price(enchantment);
		if (!inHand.is(Items.BOOK)) {
			player.sendSystemMessage(Component.translatable("message.villagerciv.librarian.keeps", librarian.name(), name, price));
			return;
		}
		int emeralds = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			emeralds += stack.is(Items.EMERALD) ? stack.getCount() : 0;
		}
		if (emeralds < price && !player.isCreative()) {
			player.sendSystemMessage(Component.translatable("message.villagerciv.librarian.short", librarian.name(), name, price, emeralds), true);
			return;
		}
		if (!player.isCreative()) {
			int owed = price;
			for (int slot = 0; slot < player.getInventory().getContainerSize() && owed > 0; slot++) {
				ItemStack stack = player.getInventory().getItem(slot);
				if (stack.is(Items.EMERALD)) {
					int paid = Math.min(owed, stack.getCount());
					stack.shrink(paid);
					owed -= paid;
				}
			}
			inHand.consume(1, player);
		}
		ItemStack book = EnchantmentHelper.createBook(new EnchantmentInstance(enchantment, levelOf));
		if (!player.getInventory().add(book)) {
			player.spawnAtLocation(level, book);
		}
		player.sendSystemMessage(Component.translatable("message.villagerciv.librarian.written", librarian.name(), name));
	}

	/** Tells a patron who keeps which enchantment, and which are not kept by anyone yet. */
	private static void list(ServerPlayer player, ServerLevel level, Settlement settlement) {
		List<Holder.Reference<Enchantment>> all = all(level);
		String[] keeper = new String[all.size()];
		for (Citizen citizen : settlement.members()) {
			int lectern = lecternOf(settlement, citizen);
			if (lectern >= 0 && lectern < keeper.length) {
				keeper[lectern] = citizen.name();
			}
		}
		int kept = 0;
		for (int i = 0; i < keeper.length; i++) {
			if (keeper[i] != null) {
				kept++;
				player.sendSystemMessage(Component.translatable("message.villagerciv.librarian.line", keeper[i],
						Enchantment.getFullname(all.get(i), all.get(i).value().getMaxLevel()), price(all.get(i))));
			}
		}
		player.sendSystemMessage(Component.translatable("message.villagerciv.librarian.total", kept, keeper.length));
	}
}
