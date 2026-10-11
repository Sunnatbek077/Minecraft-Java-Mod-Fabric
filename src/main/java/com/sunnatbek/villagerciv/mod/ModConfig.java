package com.sunnatbek.villagerciv.mod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sunnatbek.villagerciv.scheduler.QualityPreset;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** {@code config/villagerciv.json}. On first launch a preset is suggested from the hardware. */
final class ModConfig {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("villagerciv.json");

	private QualityPreset preset;
	private boolean monstersHuntCitizens;

	private ModConfig(QualityPreset preset, boolean monstersHuntCitizens) {
		this.preset = preset;
		this.monstersHuntCitizens = monstersHuntCitizens;
	}

	static ModConfig load() {
		if (Files.exists(FILE)) {
			try {
				JsonObject json = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
				return new ModConfig(QualityPreset.valueOf(json.get("preset").getAsString().toUpperCase(Locale.ROOT)),
						json.has("monstersHuntCitizens") && json.get("monstersHuntCitizens").getAsBoolean());
			} catch (IOException | RuntimeException e) {
				VillagerCivMod.LOGGER.warn("Could not read {}, using a suggested preset", FILE, e);
			}
		}
		Runtime runtime = Runtime.getRuntime();
		ModConfig config = new ModConfig(QualityPreset.suggest(runtime.availableProcessors(), runtime.maxMemory()), false);
		VillagerCivMod.LOGGER.info("Suggested quality preset: {}", config.preset);
		config.save();
		return config;
	}

	QualityPreset preset() {
		return preset;
	}

	/**
	 * Whether zombies and skeletons go for citizens. Off unless the player turns it on: losing
	 * people to the night sets a settlement back by days, and not everyone wants that game.
	 */
	boolean monstersHuntCitizens() {
		return monstersHuntCitizens;
	}

	void setMonstersHuntCitizens(boolean monstersHuntCitizens) {
		this.monstersHuntCitizens = monstersHuntCitizens;
		save();
	}

	void setPreset(QualityPreset preset) {
		this.preset = preset;
		save();
	}

	private void save() {
		JsonObject json = new JsonObject();
		json.addProperty("preset", preset.name().toLowerCase(Locale.ROOT));
		json.addProperty("monstersHuntCitizens", monstersHuntCitizens);
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, json + System.lineSeparator());
		} catch (IOException e) {
			VillagerCivMod.LOGGER.warn("Could not write {}", FILE, e);
		}
	}
}
