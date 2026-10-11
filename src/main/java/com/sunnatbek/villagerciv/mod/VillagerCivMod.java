package com.sunnatbek.villagerciv.mod;

import com.sunnatbek.villagerciv.api.CitizenBornEvent;
import com.sunnatbek.villagerciv.api.CitizenLeftEvent;
import com.sunnatbek.villagerciv.api.RequestEvent;
import com.sunnatbek.villagerciv.api.SettlementStageEvent;
import com.sunnatbek.villagerciv.api.ExpeditionHungryEvent;
import com.sunnatbek.villagerciv.mod.net.ExpeditionDisbandPayload;
import com.sunnatbek.villagerciv.mod.net.ExpeditionInfoPayload;
import com.sunnatbek.villagerciv.mod.net.FoundConfirmPayload;
import com.sunnatbek.villagerciv.mod.net.SettlementInfoPayload;
import com.sunnatbek.villagerciv.mod.net.SiteReportPayload;
import com.sunnatbek.villagerciv.mod.net.RecruitConfirmPayload;
import com.sunnatbek.villagerciv.mod.net.RecruitOfferPayload;
import com.sunnatbek.villagerciv.persistence.SaveStore;
import com.sunnatbek.villagerciv.scheduler.QualityPreset;
import com.sunnatbek.villagerciv.scheduler.TaskPriority;
import com.sunnatbek.villagerciv.scheduler.TaskResult;
import com.sunnatbek.villagerciv.sim.Simulation;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Platform integration: the only place where the simulation meets Minecraft. It loads the
 * simulation with the world, feeds it server ticks and saves it alongside the world.
 */
public final class VillagerCivMod implements ModInitializer {
	public static final String MOD_ID = "villagerciv";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static ModConfig config;
	private static SaveStore store;
	private static Simulation simulation;
	private static EmbodimentController embodiment;
	private static FoundingHandler founding;
	private static long lastGameTick = Long.MIN_VALUE;

	@Override
	public void onInitialize() {
		config = ModConfig.load();
		ModContent.register();
		MonsterAggression.register();
		ServerLifecycleEvents.SERVER_STARTED.register(VillagerCivMod::load);
		ServerTickEvents.END_SERVER_TICK.register(VillagerCivMod::tick);
		ServerLifecycleEvents.AFTER_SAVE.register((_, _, _) -> save());
		ServerLifecycleEvents.SERVER_STOPPING.register(_ -> {
			// Bodies are not saved: write their state back to the records before the final save.
			if (embodiment != null) {
				embodiment.dematerializeAll();
			}
			save();
		});
		ServerPlayerEvents.LEAVE.register(player -> {
			if (embodiment != null) {
				embodiment.onPlayerLeave(player);
			}
		});
		UseEntityCallback.EVENT.register(RecruitmentHandler::onUseEntity);
		PlayerBlockBreakEvents.AFTER.register((level, _, pos, _, _) -> {
			if (embodiment != null && level.dimension() == Level.OVERWORLD) {
				embodiment.onBlockBroken(pos);
			}
		});

		PayloadTypeRegistry.clientboundPlay().register(RecruitOfferPayload.TYPE, RecruitOfferPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ExpeditionInfoPayload.TYPE, ExpeditionInfoPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SiteReportPayload.TYPE, SiteReportPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SettlementInfoPayload.TYPE, SettlementInfoPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(FoundConfirmPayload.TYPE, FoundConfirmPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(FoundConfirmPayload.TYPE, (payload, context) -> {
			if (founding != null) {
				founding.confirm(context.player(), payload.site());
			}
		});
		PayloadTypeRegistry.serverboundPlay().register(RecruitConfirmPayload.TYPE, RecruitConfirmPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ExpeditionDisbandPayload.TYPE, ExpeditionDisbandPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(RecruitConfirmPayload.TYPE,
				(payload, context) -> RecruitmentHandler.confirm(context.player(), payload.villagerEntityId()));
		ServerPlayNetworking.registerGlobalReceiver(ExpeditionDisbandPayload.TYPE, (_, context) -> {
			if (embodiment != null) {
				embodiment.disband(context.player());
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(_ -> unload());
		CommandRegistrationCallback.EVENT.register((dispatcher, _, _) -> ModCommands.register(dispatcher));
	}

	/** The simulation of the running world, or null while no world is loaded. */
	public static Simulation simulation() {
		return simulation;
	}

	/** The embodiment controller of the running world, or null while no world is loaded. */
	public static EmbodimentController embodiment() {
		return embodiment;
	}

	static FoundingHandler founding() {
		return founding;
	}

	static SaveStore store() {
		return store;
	}

	static Path saveDirectory(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(MOD_ID).normalize();
	}

	static boolean monstersHuntCitizens() {
		return config != null && config.monstersHuntCitizens();
	}

	static void setMonstersHuntCitizens(boolean hunt) {
		config.setMonstersHuntCitizens(hunt);
	}

	static void setPreset(QualityPreset preset) {
		config.setPreset(preset);
		if (simulation != null) {
			simulation.scheduler().setPreset(preset);
		}
	}

	private static void load(MinecraftServer server) {
		store = new SaveStore(saveDirectory(server));
		try {
			SaveStore.LoadResult result = store.load(server.overworld().getSeed());
			result.problems().forEach(problem -> LOGGER.error("Save problem: {}", problem));
			simulation = new Simulation(result.state(), config.preset(), System::nanoTime);
			EmbodimentController controller = new EmbodimentController(server, simulation);
			embodiment = controller;
			FoundingHandler foundingHandler = new FoundingHandler(server, simulation, controller);
			founding = foundingHandler;
			simulation.scheduler().scheduleRecurring("town-centers", TaskPriority.BACKGROUND, 100, 7, _ -> {
				foundingHandler.maintainTownCenters();
				return TaskResult.DONE;
			});
			simulation.scheduler().scheduleRecurring("world-edits", TaskPriority.VIRTUAL_BATCH, 2, 1, context -> {
				controller.applyDeferredEdits(context);
				return TaskResult.DONE;
			});
			simulation.scheduler().scheduleRecurring("ground", TaskPriority.BACKGROUND, 20, 13, _ -> {
				controller.scanGround();
				return TaskResult.DONE;
			});
			simulation.scheduler().scheduleRecurring("warehouses", TaskPriority.BACKGROUND, 20, 11, _ -> {
				controller.syncWarehouses();
				return TaskResult.DONE;
			});
			simulation.events().subscribe(RequestEvent.class, controller::onRequest);
			simulation.events().subscribe(CitizenBornEvent.class, controller::onBorn);
			simulation.events().subscribe(SettlementStageEvent.class, controller::onStage);
			simulation.events().subscribe(ExpeditionHungryEvent.class, controller::onHungry);
			simulation.events().subscribe(CitizenLeftEvent.class, controller::onLeft);
			simulation.scheduler().scheduleRecurring("embodiment", TaskPriority.EMBODIED, EmbodimentController.UPDATE_INTERVAL_TICKS, 0, _ -> {
				controller.update();
				return TaskResult.DONE;
			});
			lastGameTick = Long.MIN_VALUE;
			LOGGER.info("Loaded {} civilization(s), preset {}", result.state().civilizations().size(), config.preset());
		} catch (IOException | RuntimeException e) {
			// Stay disabled for this session rather than run on, and later overwrite, a save we could not read.
			LOGGER.error("Could not load civilization data; the mod is inactive for this world session", e);
			simulation = null;
			embodiment = null;
			founding = null;
		}
	}

	private static void tick(MinecraftServer server) {
		AutoTest.tick(server, simulation, embodiment);
		if (simulation == null) {
			return;
		}
		// Game time stands still while the game is paused or ticking is frozen: then we do nothing.
		long gameTick = server.overworld().getGameTime();
		if (gameTick == lastGameTick) {
			return;
		}
		lastGameTick = gameTick;
		simulation.setDaytime(WorkDay.isDay(server.overworld()));
		simulation.tick(gameTick, server.getCurrentSmoothedTickTime());
	}

	private static void save() {
		if (simulation == null) {
			return;
		}
		try {
			store.save(simulation.state());
		} catch (IOException | RuntimeException e) {
			LOGGER.error("Could not save civilization data", e);
		}
	}

	private static void unload() {
		if (founding != null) {
			founding.shutdown();
		}
		simulation = null;
		embodiment = null;
		founding = null;
		store = null;
	}
}
