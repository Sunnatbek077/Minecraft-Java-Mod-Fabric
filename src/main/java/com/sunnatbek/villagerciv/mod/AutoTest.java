package com.sunnatbek.villagerciv.mod;

import com.sunnatbek.villagerciv.build.Building;
import com.sunnatbek.villagerciv.sim.Citizen;
import com.sunnatbek.villagerciv.sim.Commodity;
import com.sunnatbek.villagerciv.sim.JobType;
import com.sunnatbek.villagerciv.sim.Settlement;
import com.sunnatbek.villagerciv.sim.Simulation;
import com.sunnatbek.villagerciv.sim.WorkSite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.TreeMap;

/**
 * An unattended run for checking the mod without a person at the keyboard. Started with
 * {@code -Dvillagerciv.autotest=<ticks>} (see the {@code autotest} run configuration): once a
 * player is in the world it sets the clock to morning, lets the game run as fast as it will for
 * that many ticks, writes a line-by-line account of every settlement to the log at intervals,
 * and stops the server. Everything it writes starts with {@code AUTOTEST}, so the account can be
 * pulled out of the log afterwards. It changes nothing unless the property is set.
 */
final class AutoTest {
	private static final long TICKS = Long.getLong("villagerciv.autotest", 0L);
	private static final long REPORT_EVERY = Long.getLong("villagerciv.autotest.every", 2_000L);

	private static long startedAt = -1;
	private static long nextReport;
	private static boolean done;

	private AutoTest() {
	}

	static void tick(MinecraftServer server, Simulation simulation, EmbodimentController embodiment) {
		if (TICKS <= 0 || done || simulation == null || embodiment == null || server.getPlayerList().getPlayerCount() == 0) {
			return;
		}
		long now = server.getTickCount();
		if (startedAt < 0) {
			startedAt = now;
			nextReport = now + 200;
			VillagerCivMod.LOGGER.info("AUTOTEST start: {} ticks", TICKS);
			checkContent(server);
			run(server, "time set day");
			// Wherever the world was saved from, the run is watched from above the first settlement:
			// citizens have bodies, and the world is changed, only where a player is near.
			for (Settlement settlement : simulation.state().settlements().all()) {
				run(server, "gamemode spectator @p");
				run(server, "tp @p " + settlement.anchorX() + " " + (settlement.anchorY() + 30) + " " + settlement.anchorZ());
				break;
			}
			if (Boolean.getBoolean("villagerciv.autotest.showcase")) {
				// Every house design is set down beside the player: one that names a block the
				// game does not have, or will not stand, shows up in the log here.
				run(server, "execute as @p at @p run villagerciv debug showcase");
				VillagerCivMod.LOGGER.info("AUTOTEST showcase placed");
			}
			if (Boolean.getBoolean("villagerciv.autotest.expand") && VillagerCivMod.founding() != null) {
				// As if the patron had handed every settlement its Expansion Charter.
				for (Settlement settlement : simulation.state().settlements().all()) {
					settlement.setStage(com.sunnatbek.villagerciv.sim.SettlementStage.TOWN);
					VillagerCivMod.founding().expand(settlement, () -> VillagerCivMod.LOGGER.info("AUTOTEST expanded {}: wall pieces {}, plots {}",
							settlement.name(), settlement.wallPieces(), settlement.plots().size()));
				}
			}
			run(server, "tick sprint " + TICKS);
			return;
		}
		if (now >= nextReport) {
			nextReport = now + REPORT_EVERY;
			report(server, simulation, embodiment, now - startedAt);
		}
		if (now - startedAt >= TICKS) {
			done = true;
			report(server, simulation, embodiment, now - startedAt);
			checkContent(server);
			VillagerCivMod.LOGGER.info("AUTOTEST DONE");
			server.halt(false);
			// The game itself would sit at its title screen for ever; give the world a moment
			// to be written out, then end the process.
			Thread exit = new Thread(() -> {
				try {
					Thread.sleep(15_000);
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
				Runtime.getRuntime().halt(0);
			}, "villagerciv-autotest-exit");
			exit.setDaemon(true);
			exit.start();
		}
	}

	/** Whether the mod's recipes, the advancements that teach them, and its creative tab all loaded. */
	private static void checkContent(MinecraftServer server) {
		String[] recipes = {"recruitment_scroll", "founding_charter", "blank_plan", "agrarian_plan", "industry_plan", "military_plan",
				"surveyors_seal", "expansion_charter"};
		StringBuilder missing = new StringBuilder();
		for (String name : recipes) {
			var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE, ModContent.id(name));
			var recipe = server.getRecipeManager().byKey(key);
			if (recipe.isEmpty()) {
				missing.append(" recipe:").append(name);
			} else if (recipe.get().value().recipeBookCategory() != ModContent.RECIPE_PAGE) {
				missing.append(" page:").append(name);
			}
			if (server.getAdvancements().get(ModContent.id("recipes/misc/" + name)) == null) {
				missing.append(" unlock:").append(name);
			}
		}
		boolean tab = BuiltInRegistries.CREATIVE_MODE_TAB.containsKey(ModContent.id("items"));
		// The tabs of the crafting table's recipe book, counted the roundabout way: the class is the client's.
		String bookTabs;
		try {
			var field = Class.forName("net.minecraft.client.gui.screens.recipebook.CraftingRecipeBookComponent").getDeclaredField("TABS");
			field.setAccessible(true);
			bookTabs = String.valueOf(((java.util.List<?>) field.get(null)).size());
		} catch (ReflectiveOperationException | RuntimeException e) {
			bookTabs = "unknown (" + e + ")";
		}
		int known = 0;
		for (var player : server.getPlayerList().getPlayers()) {
			for (String name : recipes) {
				if (player.getRecipeBook().contains(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE, ModContent.id(name)))) {
					known++;
				}
			}
		}
		VillagerCivMod.LOGGER.info("AUTOTEST recipe book: {} tabs, player knows {} of {} recipes", bookTabs, known, recipes.length);
		VillagerCivMod.LOGGER.info("AUTOTEST content: {} recipes checked, missing [{}], creative tab {}", recipes.length, missing.toString().trim(), tab);
	}

	private static void run(MinecraftServer server, String command) {
		try {
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
		} catch (RuntimeException e) {
			VillagerCivMod.LOGGER.error("AUTOTEST command failed: {}", command, e);
		}
	}

	private static void report(MinecraftServer server, Simulation simulation, EmbodimentController embodiment, long elapsed) {
		ServerLevel level = server.overworld();
		long clock = Math.floorMod(level.getOverworldClockTime(), 24_000L);
		for (Settlement settlement : simulation.state().settlements().all()) {
			StringBuilder stock = new StringBuilder();
			for (Commodity commodity : Commodity.values()) {
				stock.append(commodity).append('=').append(settlement.ledger().onHand(commodity)).append(' ');
			}
			VillagerCivMod.LOGGER.info("AUTOTEST t={} clock={} {} {} size={} beds={} bodies={} | {}", elapsed, clock, settlement.name(),
					settlement.stage(), settlement.size(), settlement.housingCapacity(), embodiment.bodyCount(), stock.toString().trim());

			Map<String, Integer> jobs = new TreeMap<>();
			Map<String, Integer> doing = new TreeMap<>();
			for (Citizen citizen : settlement.members()) {
				jobs.merge(citizen.isChild() ? "child" : citizen.post() != Citizen.NO_POST ? "post" : citizen.job() == null ? "none" : citizen.job().name(), 1, Integer::sum);
				doing.merge((citizen.isEmbodied() ? "" : "nobody:") + citizen.activity(), 1, Integer::sum);
			}
			VillagerCivMod.LOGGER.info("AUTOTEST   jobs {} | doing {}", jobs, doing);

			for (WorkSite site : settlement.sites()) {
				VillagerCivMod.LOGGER.info("AUTOTEST   site {} {} at {},{},{} progress={} exhausted={}", site.id(), site.type(), site.x(), site.y(), site.z(),
						site.progress(), site.isExhausted());
				if (site.type() == JobType.MINE) {
					// What the mine has for its next miner, and for the one after: the plainest sign of whether it is stuck.
					java.util.Set<Long> claimed = new java.util.HashSet<>();
					for (int i = 0; i < 3; i++) {
						WorkSites.Action action = WorkSites.next(level, site, claimed, i * 7);
						VillagerCivMod.LOGGER.info("AUTOTEST     next {}", action == null ? "nothing" : action.kind() + " at " + action.pos().toShortString()
								+ " from " + action.stand().toShortString());
						if (action != null) {
							claimed.add(action.pos().asLong());
						}
					}
				}
			}
			for (Citizen citizen : settlement.members()) {
				boolean notable = citizen.job() == JobType.MINE || citizen.post() != Citizen.NO_POST || citizen.job() == JobType.SMITH
						|| citizen.job() == JobType.MASON || citizen.job() == JobType.TRADE || citizen.job() == JobType.LEVEL || citizen.job() == JobType.HERD
						|| citizen.job() == JobType.BUILD;
				if (notable) {
					VillagerCivMod.LOGGER.info("AUTOTEST   {} job={} site={} post={} body={} doing={} at {},{},{} carrying={}x{} finds={} home={}",
							citizen.name(), citizen.job(), citizen.jobSite(), citizen.post(), citizen.isEmbodied(), citizen.activity(),
							Math.round(citizen.x()), Math.round(citizen.y()), Math.round(citizen.z()), citizen.carrying(), citizen.carryingAmount(),
							citizen.finds(), citizen.home());
				}
			}
			Map<String, Integer> store = new TreeMap<>();
			for (Warehouse.StoreChest chest : embodiment.buildSites().storeChests(level, settlement)) {
				count(level, chest.pos(), store);
			}
			Map<String, Integer> homes = new TreeMap<>();
			for (Citizen citizen : settlement.members()) {
				BlockPos chest = citizen.home() == Citizen.NO_HOME ? null : embodiment.buildSites().chestOf(level, settlement, citizen.home(), citizen.bed());
				if (chest != null) {
					count(level, chest, homes);
				}
			}
			VillagerCivMod.LOGGER.info("AUTOTEST   storehouse {}", store);
			VillagerCivMod.LOGGER.info("AUTOTEST   home chests {}", homes);
			int building = 0;
			for (Building b : settlement.buildings()) {
				if (b.stage() != Building.Stage.COMPLETE) {
					building++;
				}
			}
			VillagerCivMod.LOGGER.info("AUTOTEST   construction {} out-of-room {} planned-beds {}", com.sunnatbek.villagerciv.build.Construction.status(settlement, simulation.blueprints()), settlement.isOutOfRoom(), settlement.plannedBeds());
			VillagerCivMod.LOGGER.info("AUTOTEST   buildings {} unfinished {} guards {} threat {}", settlement.buildings().size(), building, settlement.guards(),
					settlement.threat());
			for (Building b : settlement.buildings()) {
				if (b.stage() != Building.Stage.COMPLETE && b.stage() != Building.Stage.PLANNED) {
					int hands = 0;
					for (Citizen citizen : settlement.members()) {
						if (citizen.job() == JobType.BUILD && citizen.jobSite() == b.id()) {
							hands++;
						}
					}
					VillagerCivMod.LOGGER.info("AUTOTEST   unfinished {} #{} {} at {},{},{} rot {} steps {}/{} applied {} hands {} loaded {} next {}", b.blueprint(), b.id(),
							b.stage(), b.x(), b.y() == Building.Y_UNKNOWN ? "?" : String.valueOf(b.y()), b.z(), b.rotation(), b.progress(), b.totalSteps(),
							b.applied(), hands, embodiment.buildSites().isLoaded(level, b), embodiment.buildSites().nextKind(settlement, b));
				}
			}
			Map<String, Integer> designs = new TreeMap<>();
			int beyond = 0;
			for (Building b : settlement.buildings()) {
				designs.merge(b.blueprint().replaceAll("_[0-9]+$", "") + (b.stage() == Building.Stage.COMPLETE ? "" : "*"), 1, Integer::sum);
				if (Math.max(Math.abs(b.x() - settlement.anchorX()), Math.abs(b.z() - settlement.anchorZ())) > 50) {
					beyond++;
				}
			}
			StringBuilder pens = new StringBuilder();
			for (com.sunnatbek.villagerciv.build.Building pen : settlement.pens()) {
				java.util.List<net.minecraft.world.entity.animal.Animal> kept = Pens.animals(level, pen);
				pens.append(pen.blueprint()).append('=').append(kept.size()).append('(').append(kept.stream().filter(a -> a.isBaby()).count()).append(" young) ");
			}
			VillagerCivMod.LOGGER.info("AUTOTEST   pens {}", pens.toString().trim());
			int[] land = embodiment.landscaper().survey(level, settlement);
			java.util.Map<Integer, Integer> houses = new java.util.TreeMap<>();
			java.util.Map<Integer, Integer> walls = new java.util.TreeMap<>();
			for (com.sunnatbek.villagerciv.build.Building b : settlement.buildings()) {
				if (b.y() != com.sunnatbek.villagerciv.build.Building.Y_UNKNOWN) {
					(b.category() == com.sunnatbek.villagerciv.build.Blueprint.Category.DEFENSE ? walls : houses).merge(b.y() - settlement.grade(), 1, Integer::sum);
				}
			}
			VillagerCivMod.LOGGER.info("AUTOTEST   ground grade {} (centre {}) columns {} uneven {} lowest {} highest {} pending {} | houses by height {} walls by height {}",
					settlement.grade(), settlement.anchorY() - 1, land[0], land[1], land[2] - settlement.grade(), land[3] - settlement.grade(),
					embodiment.landscaper().pending(settlement), houses, walls);
			VillagerCivMod.LOGGER.info("AUTOTEST   expansion {} streets {} wall {}/{} beyond-first-wall {} designs {}", settlement.expansion(),
					settlement.streets().size(), settlement.wallPieces() - settlement.wallPlan().size(), settlement.wallPieces(), beyond, designs);
		}
	}

	private static void count(ServerLevel level, BlockPos pos, Map<String, Integer> into) {
		if (level.getBlockEntity(pos) instanceof Container chest) {
			for (int slot = 0; slot < chest.getContainerSize(); slot++) {
				ItemStack stack = chest.getItem(slot);
				if (!stack.isEmpty()) {
					into.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(), stack.getCount(), Integer::sum);
				}
			}
		}
	}
}
