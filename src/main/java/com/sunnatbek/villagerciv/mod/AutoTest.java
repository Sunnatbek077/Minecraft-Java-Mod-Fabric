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

	private static boolean bookBought;

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
			// STAY=true leaves the player where and as the world was saved, to see what its owner saw.
			for (Settlement settlement : "true".equals(System.getenv("STAY")) ? java.util.List.<Settlement>of() : simulation.state().settlements().all()) {
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
			String charter = System.getProperty("villagerciv.autotest.expand", "false").toLowerCase(java.util.Locale.ROOT);
			if (!charter.equals("false") && VillagerCivMod.founding() != null) {
				// As if the patron had handed every settlement a charter: "true" for the plain
				// one, the name of an outline for one of the others, "grand" for a city's.
				for (Settlement settlement : simulation.state().settlements().all()) {
					// "grand:round" and the like: a city's charter, with the outline changed for the test.
					if (charter.startsWith("grand:")) {
						settlement.setWallShape(com.sunnatbek.villagerciv.build.ShapedWall.Shape.valueOf(charter.substring(6).toUpperCase(java.util.Locale.ROOT)));
						charter = "grand";
					}
					com.sunnatbek.villagerciv.build.ShapedWall.Shape shape = charter.equals("grand") ? settlement.wallShape()
							: charter.equals("true") ? com.sunnatbek.villagerciv.build.ShapedWall.Shape.SQUARE
							: com.sunnatbek.villagerciv.build.ShapedWall.Shape.valueOf(charter.toUpperCase(java.util.Locale.ROOT));
					if (settlement.stage().ordinal() < com.sunnatbek.villagerciv.sim.SettlementStage.TOWN.ordinal()) {
						settlement.setStage(com.sunnatbek.villagerciv.sim.SettlementStage.TOWN);
					}
					if (charter.equals("grand")) {
						// The test does not wait for a city to finish its wall: it is taken as finished.
						settlement.setStage(com.sunnatbek.villagerciv.sim.SettlementStage.CITY);
						settlement.setWallPlan(java.util.List.of(), settlement.wallPieces());
						// Nor for its miners to cut the stone of a great wall.
						settlement.ledger().add(Commodity.STONE, 150_000);
						settlement.ledger().add(Commodity.WOOD, 20_000);
					}
					int before = settlement.wallPieces();
					VillagerCivMod.founding().expand(settlement, shape, () -> VillagerCivMod.LOGGER.info("AUTOTEST expanded {} to stage {} as {}: {} new wall pieces, plots {}",
							settlement.name(), settlement.expansion() + 1, settlement.wallShape(), settlement.wallPieces() - before, settlement.plots().buildableCount()));
				}
			}
			run(server, "tick sprint " + TICKS);
			return;
		}
		if (now - startedAt == 3_000 && Boolean.getBoolean("villagerciv.autotest.shortage")) {
			// The stone is gone, all at once: who is called away to make it good, and do they go back?
			for (Settlement settlement : simulation.state().settlements().all()) {
				int stone = settlement.ledger().available(Commodity.STONE);
				settlement.ledger().take(Commodity.STONE, stone);
				VillagerCivMod.LOGGER.info("AUTOTEST shortage staged: {} stone taken out of the store", stone);
			}
		}
		if (Boolean.getBoolean("villagerciv.autotest.roam") && (now - startedAt) % 1_500 == 700) {
			// A wall is only put into the world where a player is near: the watcher goes round it.
			for (Settlement settlement : simulation.state().settlements().all()) {
				int stop = (int) ((now - startedAt) / 1_500) % 8;
				double angle = Math.PI / 4 * stop;
				int reach = com.sunnatbek.villagerciv.build.ShapedWall.radius(Math.max(1, settlement.expansion())) - 16;
				run(server, "tp @p " + (settlement.anchorX() + (int) (Math.cos(angle) * reach)) + " " + (settlement.grade() + 45) + " "
						+ (settlement.anchorZ() + (int) (Math.sin(angle) * reach)));
				break;
			}
		}
		if (now - startedAt == 1_000) {
			// Something for the patrols to do: monsters set down inside the settlement, by day.
			for (Settlement settlement : simulation.state().settlements().all()) {
				int y = settlement.grade() + 1;
				run(server, "summon husk " + (settlement.anchorX() + 22) + " " + y + " " + (settlement.anchorZ() + 9));
				run(server, "summon husk " + (settlement.anchorX() - 30) + " " + y + " " + (settlement.anchorZ() - 25));
				run(server, "summon husk " + (settlement.anchorX() + 60) + " " + y + " " + (settlement.anchorZ() + 60));
				run(server, "summon creeper " + (settlement.anchorX() - 55) + " " + y + " " + (settlement.anchorZ() + 40));
				VillagerCivMod.LOGGER.info("AUTOTEST intruders set down: 3 husks and a creeper");
				break;
			}
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
				"surveyors_seal", "pentagon_charter", "hexagon_charter", "octagon_charter", "round_charter", "grand_charter", "expansion_charter"};
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
			VillagerCivMod.LOGGER.info("AUTOTEST   shortage food {} wood {} stone {} | called away from their own work: {}", settlement.shortage(Commodity.FOOD),
					settlement.shortage(Commodity.WOOD), settlement.shortage(Commodity.STONE), settlement.calledAway());

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
			{
				int swords = 0;
				int bows = 0;
				for (Citizen c : settlement.members()) {
					swords += c.patrol() == Citizen.Patrol.SWORD ? 1 : 0;
					bows += c.patrol() == Citizen.Patrol.CROSSBOW ? 1 : 0;
				}
				net.minecraft.world.entity.monster.Monster near = embodiment.intruder(level, settlement, server.getPlayerList().getPlayers().getFirst(), false);
				VillagerCivMod.LOGGER.info("AUTOTEST   citizens standing inside one another: {}", embodiment.overlapping());
				VillagerCivMod.LOGGER.info("AUTOTEST   patrol swords {} crossbows {} | a monster on the ground: {}", swords, bows,
						near == null ? "none" : near.getType().toShortString() + " #" + near.getId() + " at " + near.blockPosition().toShortString()
								+ " health " + (int) near.getHealth() + ", " + embodiment.intruderCount(settlement) + " in all");
			}
			{
				int[][] rings = new int[3][4];
				for (com.sunnatbek.villagerciv.build.Building b : settlement.buildings()) {
					if (b.category() == com.sunnatbek.villagerciv.build.Blueprint.Category.DEFENSE) {
						int[] ring = rings[com.sunnatbek.villagerciv.build.Fortification.ringOf(b.blueprint())];
						ring[0]++;
						ring[1] += b.stage() == com.sunnatbek.villagerciv.build.Building.Stage.COMPLETE ? 1 : 0;
						ring[2] += b.applied() >= b.totalSteps() ? 1 : 0;
						ring[3] += b.stage() == com.sunnatbek.villagerciv.build.Building.Stage.CONDEMNED ? 1 : 0;
					}
				}
				VillagerCivMod.LOGGER.info("AUTOTEST   rings (pieces/complete/in the world/condemned): wood {} stone {} great {} | still planned {} | shape {} stage {}",
						java.util.Arrays.toString(rings[0]), java.util.Arrays.toString(rings[1]), java.util.Arrays.toString(rings[2]), settlement.wallPlan().size(),
						settlement.wallShape(), settlement.expansion() + 1);
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
				pens.append(pen.blueprint()).append('=').append(kept.size()).append('(').append(kept.stream().filter(a -> a.isBaby()).count()).append(" young, ")
						.append(Pens.strays(level, settlement, pen).size()).append(" loose) ");
			}
			VillagerCivMod.LOGGER.info("AUTOTEST   pens {}", pens.toString().trim());
			{
				java.util.Set<String> kept = new java.util.TreeSet<>();
				int atLectern = 0;
				for (Citizen c : settlement.members()) {
					var e = Librarians.enchantmentOf(level, settlement, c);
					if (e != null) {
						kept.add(e.key().identifier().getPath());
						atLectern += c.activity() == com.sunnatbek.villagerciv.sim.Activity.WORKING ? 1 : 0;
					}
				}
				// Once, as soon as there is a librarian: its patron brings a book and emeralds.
				if (!bookBought && !kept.isEmpty()) {
					bookBought = true;
					net.minecraft.server.level.ServerPlayer patron = server.getPlayerList().getPlayers().getFirst();
					run(server, "gamemode survival @p");
					run(server, "item replace entity @p weapon.mainhand with book");
					run(server, "give @p emerald 128");
					int before = 0;
					for (int slot = 0; slot < patron.getInventory().getContainerSize(); slot++) {
						before += patron.getInventory().getItem(slot).is(net.minecraft.world.item.Items.EMERALD) ? patron.getInventory().getItem(slot).getCount() : 0;
					}
					VillagerCivMod.LOGGER.info("AUTOTEST   patron has {} emeralds and holds {}", before, patron.getMainHandItem());
					for (Citizen c : settlement.members()) {
						if (Librarians.enchantmentOf(level, settlement, c) != null) {
							Librarians.speak(patron, level, settlement, c, patron.getMainHandItem());
							break;
						}
					}
					int emeralds = 0;
					String got = "nothing";
					for (int slot = 0; slot < patron.getInventory().getContainerSize(); slot++) {
						net.minecraft.world.item.ItemStack stack = patron.getInventory().getItem(slot);
						emeralds += stack.is(net.minecraft.world.item.Items.EMERALD) ? stack.getCount() : 0;
						if (stack.is(net.minecraft.world.item.Items.ENCHANTED_BOOK)) {
							got = stack.getOrDefault(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS,
									net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY).toString();
						}
					}
					VillagerCivMod.LOGGER.info("AUTOTEST   bought from a librarian: {} | emeralds left {} of 128", got, emeralds);
					run(server, "gamemode spectator @p");
				}
				VillagerCivMod.LOGGER.info("AUTOTEST   libraries {} librarians {} at their lecterns {} | {} of {} enchantments kept {}", settlement.libraries().size(),
						settlement.members().stream().filter(c -> c.job() == JobType.STUDY).count(), atLectern, kept.size(), Librarians.all(level).size(), kept);
			}
			int[] land = embodiment.landscaper().survey(level, settlement);
			java.util.Map<Integer, Integer> houses = new java.util.TreeMap<>();
			java.util.Map<Integer, Integer> walls = new java.util.TreeMap<>();
			for (com.sunnatbek.villagerciv.build.Building b : settlement.buildings()) {
				if (b.y() != com.sunnatbek.villagerciv.build.Building.Y_UNKNOWN) {
					(b.category() == com.sunnatbek.villagerciv.build.Blueprint.Category.DEFENSE ? walls : houses).merge(b.y() - settlement.grade(), 1, Integer::sum);
				}
			}
			VillagerCivMod.LOGGER.info("AUTOTEST   ground grade {} (centre {}) columns {} uneven {} lowest {} highest {} pending {} heavy {} | houses by height {} walls by height {}",
					settlement.grade(), settlement.anchorY() - 1, land[0], land[1], land[2] - settlement.grade(), land[3] - settlement.grade(),
					embodiment.landscaper().pending(settlement) + " " + embodiment.landscaper().pendingSpread(settlement, server.getPlayerList().getPlayers().getFirst().blockPosition()) + " stuck " + embodiment.landscaper().stuck(settlement) + " " + embodiment.landscaper().unevenReasons(level, settlement), settlement.hasHeavyGroundWork(), houses, walls);
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
