package com.sunnatbek.villagerciv.mod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.sunnatbek.villagerciv.scheduler.QualityPreset;
import com.sunnatbek.villagerciv.scheduler.Telemetry;
import com.sunnatbek.villagerciv.build.Building;
import com.sunnatbek.villagerciv.sim.Citizen;
import com.sunnatbek.villagerciv.sim.Civilization;
import com.sunnatbek.villagerciv.sim.Commodity;
import com.sunnatbek.villagerciv.sim.Settlement;
import com.sunnatbek.villagerciv.sim.WorkSite;
import com.sunnatbek.villagerciv.sim.Expedition;
import com.sunnatbek.villagerciv.sim.Simulation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** {@code /villagerciv}: telemetry and debug tools for operators. */
final class ModCommands {
	private ModCommands() {
	}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		var root = Commands.literal("villagerciv")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("status").executes(ModCommands::status));

		var preset = Commands.literal("preset");
		for (QualityPreset value : QualityPreset.values()) {
			preset.then(Commands.literal(value.name().toLowerCase(Locale.ROOT)).executes(context -> {
				VillagerCivMod.setPreset(value);
				return reply(context, "Quality preset set to " + value);
			}));
		}

		var danger = Commands.literal("danger")
				.executes(context -> reply(context, "Monsters hunt citizens: " + (VillagerCivMod.monstersHuntCitizens() ? "on" : "off")))
				.then(Commands.literal("on").executes(context -> {
					VillagerCivMod.setMonstersHuntCitizens(true);
					return reply(context, "Monsters now hunt citizens. Settlements will need guards and walls.");
				}))
				.then(Commands.literal("off").executes(context -> {
					VillagerCivMod.setMonstersHuntCitizens(false);
					return reply(context, "Monsters leave citizens alone.");
				}));

		var debug = Commands.literal("debug")
				.then(Commands.literal("create")
						.then(Commands.argument("name", StringArgumentType.greedyString()).executes(ModCommands::create)))
				.then(Commands.literal("list").executes(ModCommands::list))
				.then(Commands.literal("expeditions").executes(ModCommands::expeditions))
				.then(Commands.literal("settlements").executes(ModCommands::settlements))
				.then(Commands.literal("export").executes(ModCommands::export))
				// Sets down every house design in a row to the east of the player, to be looked at.
				.then(Commands.literal("showcase").executes(context -> showcase(context, null))
						.then(Commands.argument("design", StringArgumentType.word()).executes(context ->
								showcase(context, StringArgumentType.getString(context, "design")))));

		dispatcher.register(root.then(preset).then(danger).then(debug));
	}

	private static int status(CommandContext<CommandSourceStack> context) {
		Simulation simulation = simulation(context);
		if (simulation == null) {
			return 0;
		}
		Telemetry telemetry = simulation.scheduler().telemetry();
		QualityPreset preset = simulation.scheduler().preset();
		StringBuilder text = new StringBuilder();
		text.append(String.format(Locale.ROOT,
				"Villager Civilization — preset %s (budget %.1f ms/tick, %d entities)\n",
				preset, preset.tickBudgetNanos() / 1e6, preset.entityBudget()));
		text.append(String.format(Locale.ROOT,
				"Tick cost: avg %.3f ms, last %.3f ms, worst %.3f ms | throttle %s | queued tasks %d\n",
				telemetry.averageTickNanos() / 1e6, telemetry.lastTickNanos() / 1e6, telemetry.worstTickNanos() / 1e6,
				telemetry.throttle(), telemetry.queuedTasks()));
		text.append("Civilizations: ").append(simulation.state().civilizations().size())
				.append(" | Expeditions: ").append(simulation.state().expeditions().size())
				.append(" | Settlements: ").append(simulation.state().settlements().size());
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		if (embodiment != null) {
			text.append(" | Citizen entities: ").append(embodiment.bodyCount()).append('/').append(preset.entityBudget());
		}
		int unreadable = simulation.state().civilizations().quarantined().size() + simulation.state().expeditions().quarantined().size()
				+ simulation.state().settlements().quarantined().size();
		if (unreadable > 0) {
			text.append("\nUnreadable records: ").append(unreadable).append(" (see log)");
		}
		for (Telemetry.SystemStats stats : telemetry.systems()) {
			text.append(String.format(Locale.ROOT, "\n  %s: %d runs, avg %.1f µs, max %.3f ms, %d breaker trips",
					stats.system(), stats.runs(), stats.averageMicros(), stats.maxNanos() / 1e6, stats.breakerTrips()));
		}
		return reply(context, text.toString());
	}

	private static int create(CommandContext<CommandSourceStack> context) {
		Simulation simulation = simulation(context);
		if (simulation == null) {
			return 0;
		}
		Civilization civilization = simulation.createCivilization(StringArgumentType.getString(context, "name"));
		return reply(context, "Created civilization '" + civilization.name() + "' (" + civilization.id() + ")");
	}

	private static int list(CommandContext<CommandSourceStack> context) {
		Simulation simulation = simulation(context);
		if (simulation == null) {
			return 0;
		}
		StringBuilder text = new StringBuilder("Civilizations: " + simulation.state().civilizations().size());
		for (Civilization civilization : simulation.state().civilizations().all()) {
			text.append("\n  ").append(civilization.name()).append(" — ").append(civilization.tier())
					.append(", founded at tick ").append(civilization.foundedTick())
					.append(" (").append(civilization.id()).append(")");
		}
		reply(context, text.toString());
		return simulation.state().civilizations().size();
	}

	private static int expeditions(CommandContext<CommandSourceStack> context) {
		Simulation simulation = simulation(context);
		if (simulation == null) {
			return 0;
		}
		StringBuilder text = new StringBuilder("Expeditions: " + simulation.state().expeditions().size());
		for (Expedition expedition : simulation.state().expeditions().all()) {
			text.append("\n").append(expedition.state()).append(", player ").append(expedition.player());
			for (Citizen citizen : expedition.members()) {
				text.append(String.format(Locale.ROOT, "\n  %s — %s (affinity %s) %s, health %.1f, %s at %.0f %.0f %.0f",
						citizen.name(), citizen.role(), citizen.affinity(), citizen.traits(), citizen.health(),
						citizen.isEmbodied() ? "entity" : "record only", citizen.x(), citizen.y(), citizen.z()));
			}
		}
		reply(context, text.toString());
		return simulation.state().expeditions().size();
	}

	private static int settlements(CommandContext<CommandSourceStack> context) {
		Simulation simulation = simulation(context);
		if (simulation == null) {
			return 0;
		}
		StringBuilder text = new StringBuilder("Settlements: " + simulation.state().settlements().size());
		for (Settlement settlement : simulation.state().settlements().all()) {
			text.append("\n").append(settlement.name()).append(" — ").append(settlement.stage())
					.append(settlement.isStarving() ? ", STARVING" : "").append(", stock:");
			for (Commodity commodity : Commodity.values()) {
				text.append(' ').append(commodity).append(' ').append(settlement.ledger().onHand(commodity));
			}
			for (WorkSite site : settlement.sites()) {
				text.append(String.format(Locale.ROOT, "\n  site %d %s at %d %s %d, progress %d%s", site.id(), site.type(), site.x(),
						site.y() == WorkSite.Y_UNKNOWN ? "?" : Integer.toString(site.y()), site.z(), site.progress(),
						site.isExhausted() ? ", exhausted" : ""));
			}
			for (Building building : settlement.buildings()) {
				text.append(String.format(Locale.ROOT, "\n  building %d %s at %d %s %d, %s, %d/%d done, %d in the world", building.id(),
						building.blueprint(), building.x(), building.y() == Building.Y_UNKNOWN ? "?" : Integer.toString(building.y()),
						building.z(), building.stage(), building.progress(), building.totalSteps(), building.applied()));
			}
			for (Citizen citizen : settlement.members()) {
				text.append(String.format(Locale.ROOT, "\n  %s — %s, job %s, carrying %d %s, loyalty %d, %s", citizen.name(), citizen.role(),
						citizen.job() == null ? "none" : citizen.job() + "@" + citizen.jobSite(), citizen.carryingAmount(),
						citizen.carrying() == null ? "" : citizen.carrying().toString(), citizen.loyalty(),
						citizen.isEmbodied() ? "entity" : "record only"));
			}
		}
		reply(context, text.toString());
		return simulation.state().settlements().size();
	}

	private static int showcase(CommandContext<CommandSourceStack> context, String only) {
		Simulation simulation = simulation(context);
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		if (simulation == null || embodiment == null) {
			return 0;
		}
		net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(context.getSource().getPosition()).below().east(3);
		int built = 0;
		for (com.sunnatbek.villagerciv.build.Blueprint blueprint : simulation.blueprints().all()) {
			boolean wanted = only == null ? blueprint.category() == com.sunnatbek.villagerciv.build.Blueprint.Category.HOUSING
					: blueprint.id().equals(only);
			if (wanted) {
				embodiment.buildSites().showcase(context.getSource().getLevel(), blueprint, at);
				at = at.east(blueprint.width() + 2);
				built++;
			}
		}
		return reply(context, built == 0 ? "No such design." : "Set down " + built + " design(s) to the east.");
	}

	private static int export(CommandContext<CommandSourceStack> context) {
		Simulation simulation = simulation(context);
		if (simulation == null) {
			return 0;
		}
		Path file = VillagerCivMod.saveDirectory(context.getSource().getServer()).resolve("export.json");
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, VillagerCivMod.store().export(simulation.state()));
		} catch (IOException e) {
			context.getSource().sendFailure(Component.literal("Export failed: " + e));
			return 0;
		}
		return reply(context, "Exported to " + file);
	}

	private static Simulation simulation(CommandContext<CommandSourceStack> context) {
		Simulation simulation = VillagerCivMod.simulation();
		if (simulation == null) {
			context.getSource().sendFailure(Component.literal("Civilization data failed to load for this world; see the log."));
		}
		return simulation;
	}

	private static int reply(CommandContext<CommandSourceStack> context, String message) {
		context.getSource().sendSuccess(() -> Component.literal(message), false);
		return 1;
	}
}
