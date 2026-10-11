package com.sunnatbek.villagerciv.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sunnatbek.villagerciv.api.CivilizationId;
import com.sunnatbek.villagerciv.api.ExpeditionId;
import com.sunnatbek.villagerciv.api.SettlementId;
import com.sunnatbek.villagerciv.sim.IdGenerator;
import com.sunnatbek.villagerciv.sim.RecordTable;
import com.sunnatbek.villagerciv.sim.SimRecord;
import com.sunnatbek.villagerciv.sim.SimulationState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * On-disk form of a {@link SimulationState}: a small index plus one file per top-level record.
 *
 * <ul>
 * <li>Only dirty records are rewritten, so save time follows the amount of change.</li>
 * <li>Every write goes to a temp file first and is then moved into place, so a crash never
 * leaves a half-written file.</li>
 * <li>An unreadable record is quarantined and reported; everything else still loads.</li>
 * </ul>
 */
public final class SaveStore {
	public static final int SCHEMA_VERSION = 1;

	private static final String INDEX_FILE = "index.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** Result of {@link #load}: the state, plus a note for every problem that was worked around. */
	public record LoadResult(SimulationState state, List<String> problems) {
	}

	/** How one kind of record is stored: its directory (also its key in the index) and codec. */
	private record Kind<I, R extends SimRecord<I>>(
			String name,
			Function<SimulationState, RecordTable<I, R>> table,
			Function<String, I> parseId,
			BiFunction<R, Integer, JsonObject> write,
			Function<JsonObject, R> read) {
	}

	private static final List<Kind<?, ?>> KINDS = List.of(
			new Kind<>("civilizations", SimulationState::civilizations, CivilizationId::parse, CivilizationCodec::write, CivilizationCodec::read),
			new Kind<>("expeditions", SimulationState::expeditions, ExpeditionId::parse, ExpeditionCodec::write, ExpeditionCodec::read),
			new Kind<>("settlements", SimulationState::settlements, SettlementId::parse, SettlementCodec::write, SettlementCodec::read));

	private final Path root;
	private final SchemaMigrations migrations;

	public SaveStore(Path root) {
		this(root, new SchemaMigrations(SCHEMA_VERSION));
	}

	public SaveStore(Path root, SchemaMigrations migrations) {
		this.root = root;
		this.migrations = migrations;
	}

	public boolean exists() {
		if (Files.exists(root.resolve(INDEX_FILE))) {
			return true;
		}
		for (Kind<?, ?> kind : KINDS) {
			if (Files.isDirectory(root.resolve(kind.name()))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Loads the saved state, or creates a fresh one seeded with {@code freshSeed} when nothing
	 * was saved yet.
	 */
	public LoadResult load(long freshSeed) throws IOException {
		List<String> problems = new ArrayList<>();
		if (!exists()) {
			return new LoadResult(SimulationState.fresh(freshSeed), problems);
		}

		SimulationState state;
		JsonObject index = null;
		try {
			index = migrations.upgrade(readJson(root.resolve(INDEX_FILE)));
			state = new SimulationState(new IdGenerator(index.get("idSeed").getAsLong(), index.get("idCounter").getAsLong()));
		} catch (IOException | RuntimeException e) {
			// Rebuild from the record files; the broken index is kept aside for inspection.
			problems.add("index unreadable (" + e + "); rebuilt from record files");
			setAside(root.resolve(INDEX_FILE));
			index = null;
			// Not freshSeed itself: the lost id counter would restart and repeat existing ids.
			state = SimulationState.fresh(freshSeed ^ System.nanoTime());
		}
		for (Kind<?, ?> kind : KINDS) {
			loadKind(kind, state, index, problems);
		}
		return new LoadResult(state, problems);
	}

	private <I, R extends SimRecord<I>> void loadKind(Kind<I, R> kind, SimulationState state, JsonObject index, List<String> problems)
			throws IOException {
		List<String> listed = new ArrayList<>();
		if (index == null) {
			listed.addAll(scanRecordFiles(kind.name()));
		} else if (index.has(kind.name())) {
			// A kind added by a later mod version is simply absent from an older index.
			for (JsonElement element : index.getAsJsonArray(kind.name())) {
				listed.add(element.getAsString());
			}
		}
		RecordTable<I, R> table = kind.table().apply(state);
		for (String text : listed) {
			I id;
			try {
				id = kind.parseId().apply(text);
			} catch (IllegalArgumentException e) {
				problems.add(kind.name() + " index entry '" + text + "' is not an id; ignored");
				continue;
			}
			try {
				R record = kind.read().apply(migrations.upgrade(readJson(recordFile(kind.name(), id))));
				if (!record.id().equals(id)) {
					throw new SaveFormatException("file holds record " + record.id());
				}
				table.adopt(record);
			} catch (IOException | RuntimeException e) {
				problems.add(kind.name() + " record " + id + " unreadable (" + e + "); quarantined");
				table.quarantine(id);
			}
		}
	}

	/**
	 * Writes everything that changed since the last save.
	 *
	 * @return number of files written or deleted
	 */
	public int save(SimulationState state) throws IOException {
		int changes = 0;
		for (Kind<?, ?> kind : KINDS) {
			changes += saveKind(kind, state);
		}
		if (state.isIndexDirty()) {
			writeJson(root.resolve(INDEX_FILE), indexJson(state));
			state.markIndexClean();
			changes++;
		}
		return changes;
	}

	private <I, R extends SimRecord<I>> int saveKind(Kind<I, R> kind, SimulationState state) throws IOException {
		int changes = 0;
		RecordTable<I, R> table = kind.table().apply(state);
		for (R record : table.all()) {
			if (record.isDirty()) {
				writeJson(recordFile(kind.name(), record.id()), kind.write().apply(record, migrations.currentVersion()));
				record.markClean();
				changes++;
			}
		}
		for (Iterator<I> it = table.removed().iterator(); it.hasNext(); ) {
			Files.deleteIfExists(recordFile(kind.name(), it.next()));
			it.remove();
			changes++;
		}
		return changes;
	}

	/** Whole state as one readable JSON document, for bug reports and test fixtures. */
	public String export(SimulationState state) {
		JsonObject json = indexJson(state);
		for (Kind<?, ?> kind : KINDS) {
			json.add(kind.name(), exportKind(kind, state));
		}
		return GSON.toJson(json);
	}

	private <I, R extends SimRecord<I>> JsonArray exportKind(Kind<I, R> kind, SimulationState state) {
		JsonArray records = new JsonArray();
		for (R record : kind.table().apply(state).all()) {
			records.add(kind.write().apply(record, migrations.currentVersion()));
		}
		return records;
	}

	private JsonObject indexJson(SimulationState state) {
		JsonObject json = new JsonObject();
		json.addProperty(SchemaMigrations.VERSION_KEY, migrations.currentVersion());
		json.addProperty("idSeed", state.ids().seed());
		json.addProperty("idCounter", state.ids().counter());
		for (Kind<?, ?> kind : KINDS) {
			json.add(kind.name(), indexIds(kind, state));
		}
		return json;
	}

	private static <I, R extends SimRecord<I>> JsonArray indexIds(Kind<I, R> kind, SimulationState state) {
		RecordTable<I, R> table = kind.table().apply(state);
		JsonArray ids = new JsonArray();
		for (R record : table.all()) {
			ids.add(record.id().toString());
		}
		for (I id : table.quarantined()) {
			ids.add(id.toString());
		}
		return ids;
	}

	private Path recordFile(String kind, Object id) {
		return root.resolve(kind).resolve(id + ".json");
	}

	private List<String> scanRecordFiles(String kind) throws IOException {
		List<String> ids = new ArrayList<>();
		Path dir = root.resolve(kind);
		if (!Files.isDirectory(dir)) {
			return ids;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
			for (Path file : files) {
				String name = file.getFileName().toString();
				ids.add(name.substring(0, name.length() - ".json".length()));
			}
		}
		ids.sort(null);
		return ids;
	}

	private static JsonObject readJson(Path file) throws IOException {
		try {
			return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (JsonParseException | IllegalStateException e) {
			throw new SaveFormatException("not valid JSON: " + e.getMessage());
		}
	}

	private static void writeJson(Path file, JsonObject json) throws IOException {
		Files.createDirectories(file.getParent());
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temp, GSON.toJson(json), StandardCharsets.UTF_8);
		Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void setAside(Path file) throws IOException {
		if (Files.exists(file)) {
			Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
