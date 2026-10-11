package com.sunnatbek.villagerciv.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sunnatbek.villagerciv.sim.Commodity;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** All known building designs. The built-in ones ship as text files inside the mod. */
public final class BlueprintLibrary {
	private static final String ROOT = "/villagerciv/blueprints/";

	private final Map<String, Blueprint> blueprints = new LinkedHashMap<>();
	private final Map<String, Blueprint> generated = new LinkedHashMap<>();

	public static BlueprintLibrary builtIn() {
		BlueprintLibrary library = new BlueprintLibrary();
		JsonObject index = readResource(ROOT + "index.json");
		for (JsonElement name : index.getAsJsonArray("blueprints")) {
			library.add(parse(readResource(ROOT + name.getAsString() + ".json")));
		}
		return library;
	}

	public void add(Blueprint blueprint) {
		blueprints.put(blueprint.id(), blueprint);
	}

	public Optional<Blueprint> get(String id) {
		Blueprint blueprint = blueprints.get(id);
		if (blueprint == null) {
			// Made-to-measure designs are generated on first use and then kept.
			blueprint = generated.get(id);
			if (blueprint == null) {
				blueprint = WallDesigns.forId(id);
				if (blueprint != null) {
					generated.put(id, blueprint);
				}
			}
		}
		return Optional.ofNullable(blueprint);
	}

	public Collection<Blueprint> all() {
		return Collections.unmodifiableCollection(blueprints.values());
	}

	/** Every design of a category, in library order. */
	public List<Blueprint> ofCategory(Blueprint.Category category) {
		List<Blueprint> result = new ArrayList<>();
		for (Blueprint blueprint : blueprints.values()) {
			if (blueprint.category() == category) {
				result.add(blueprint);
			}
		}
		return result;
	}

	private static JsonObject readResource(String path) {
		try (InputStream stream = BlueprintLibrary.class.getResourceAsStream(path)) {
			if (stream == null) {
				throw new IllegalStateException("missing blueprint resource " + path);
			}
			try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				return JsonParser.parseReader(reader).getAsJsonObject();
			}
		} catch (IOException e) {
			throw new IllegalStateException("cannot read blueprint resource " + path, e);
		}
	}

	/**
	 * Reads and checks a blueprint. A design that is not a clean box of cells, or uses a
	 * character its palette does not know, is rejected here rather than half-built in the world.
	 */
	public static Blueprint parse(JsonObject json) {
		String id = json.get("id").getAsString();
		Map<Character, String> palette = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("palette").entrySet()) {
			if (entry.getKey().length() != 1) {
				throw new IllegalArgumentException(id + ": palette key '" + entry.getKey() + "' must be one character");
			}
			char key = entry.getKey().charAt(0);
			if (key == Blueprint.LEAVE || key == Blueprint.CLEAR) {
				throw new IllegalArgumentException(id + ": '" + key + "' is reserved");
			}
			palette.put(key, entry.getValue().getAsString());
		}

		List<List<String>> layers = new ArrayList<>();
		int width = -1;
		int depth = -1;
		for (JsonElement layerJson : json.getAsJsonArray("layers")) {
			JsonArray rows = layerJson.getAsJsonArray();
			List<String> layer = new ArrayList<>(rows.size());
			for (JsonElement row : rows) {
				String cells = row.getAsString();
				if (width < 0) {
					width = cells.length();
				} else if (cells.length() != width) {
					throw new IllegalArgumentException(id + ": layer " + layers.size() + " has a row of width " + cells.length() + ", expected " + width);
				}
				for (int i = 0; i < cells.length(); i++) {
					char c = cells.charAt(i);
					if (c != Blueprint.LEAVE && c != Blueprint.CLEAR && !palette.containsKey(c)) {
						throw new IllegalArgumentException(id + ": layer " + layers.size() + " uses '" + c + "', which is not in the palette");
					}
				}
				layer.add(cells);
			}
			if (depth < 0) {
				depth = layer.size();
			} else if (layer.size() != depth) {
				throw new IllegalArgumentException(id + ": layer " + layers.size() + " has " + layer.size() + " rows, expected " + depth);
			}
			layers.add(List.copyOf(layer));
		}
		if (layers.isEmpty()) {
			throw new IllegalArgumentException(id + ": no layers");
		}

		String late = json.has("late") ? json.get("late").getAsString() : "";
		for (int i = 0; i < late.length(); i++) {
			if (!palette.containsKey(late.charAt(i))) {
				throw new IllegalArgumentException(id + ": late key '" + late.charAt(i) + "' is not in the palette");
			}
		}
		Map<Commodity, Integer> cost = new EnumMap<>(Commodity.class);
		for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("cost").entrySet()) {
			cost.put(Commodity.valueOf(entry.getKey().toUpperCase(Locale.ROOT)), entry.getValue().getAsInt());
		}
		Map<Character, Commodity> storage = new LinkedHashMap<>();
		if (json.has("storage")) {
			for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("storage").entrySet()) {
				if (entry.getKey().length() != 1 || !palette.containsKey(entry.getKey().charAt(0))) {
					throw new IllegalArgumentException(id + ": storage key '" + entry.getKey() + "' is not in the palette");
				}
				storage.put(entry.getKey().charAt(0), Commodity.valueOf(entry.getValue().getAsString().toUpperCase(Locale.ROOT)));
			}
		}
		return new Blueprint(id, json.get("name").getAsString(),
				Blueprint.Category.valueOf(json.get("category").getAsString().toUpperCase(Locale.ROOT)),
				width, layers.size(), depth, List.copyOf(layers), Map.copyOf(palette), late,
				json.has("beds") ? json.get("beds").getAsInt() : 0, Map.copyOf(cost), Map.copyOf(storage));
	}
}
