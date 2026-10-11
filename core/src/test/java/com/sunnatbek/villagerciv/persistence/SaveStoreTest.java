package com.sunnatbek.villagerciv.persistence;

import com.sunnatbek.villagerciv.api.CivilizationId;
import com.sunnatbek.villagerciv.sim.Civilization;
import com.sunnatbek.villagerciv.sim.CivilizationTier;
import com.sunnatbek.villagerciv.sim.SimulationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveStoreTest {
	@TempDir
	Path dir;

	private Path file(CivilizationId id) {
		return dir.resolve("civilizations").resolve(id + ".json");
	}

	@Test
	void nothingSavedYetGivesFreshState() throws IOException {
		SaveStore.LoadResult result = new SaveStore(dir).load(5);
		assertEquals(0, result.state().civilizations().size());
		assertEquals(5, result.state().ids().seed());
		assertEquals(List.of(), result.problems());
	}

	@Test
	void saveThenLoadRestoresTheExactState() throws IOException {
		SaveStore store = new SaveStore(dir);
		SimulationState state = SimulationState.fresh(99);
		state.createCivilization("Riverfolk", 1200);
		state.createCivilization("Hill \"Clan\" ✦", 0).setTier(CivilizationTier.KINGDOM);
		store.save(state);

		SaveStore.LoadResult loaded = store.load(0);
		assertEquals(List.of(), loaded.problems());
		assertEquals(store.export(state), store.export(loaded.state()));

		// The id generator continues where it left off instead of repeating ids.
		CivilizationId next = loaded.state().createCivilization("Third", 0).id();
		assertEquals(state.createCivilization("Third", 0).id(), next);
	}

	@Test
	void onlyChangedRecordsAreWritten() throws IOException {
		SaveStore store = new SaveStore(dir);
		SimulationState state = SimulationState.fresh(1);
		Civilization a = state.createCivilization("A", 0);
		Civilization b = state.createCivilization("B", 0);
		assertEquals(3, store.save(state)); // two records + index
		assertEquals(0, store.save(state));

		long untouched = Files.getLastModifiedTime(file(a.id())).toMillis();
		b.setName("B2");
		assertEquals(1, store.save(state));
		assertEquals(untouched, Files.getLastModifiedTime(file(a.id())).toMillis());
		assertTrue(Files.readString(file(b.id())).contains("B2"));
	}

	@Test
	void removedRecordLosesItsFile() throws IOException {
		SaveStore store = new SaveStore(dir);
		SimulationState state = SimulationState.fresh(1);
		Civilization a = state.createCivilization("A", 0);
		store.save(state);

		state.civilizations().remove(a.id());
		store.save(state);
		assertFalse(Files.exists(file(a.id())));
		assertEquals(0, store.load(0).state().civilizations().size());
	}

	@Test
	void noTempFilesAreLeftBehind() throws IOException {
		SimulationState state = SimulationState.fresh(1);
		state.createCivilization("A", 0);
		new SaveStore(dir).save(state);

		try (Stream<Path> files = Files.walk(dir)) {
			assertTrue(files.noneMatch(p -> p.toString().endsWith(".tmp")));
		}
	}

	@Test
	void corruptRecordIsQuarantinedAndTheRestLoads() throws IOException {
		SaveStore store = new SaveStore(dir);
		SimulationState state = SimulationState.fresh(1);
		Civilization good = state.createCivilization("Good", 0);
		Civilization bad = state.createCivilization("Bad", 0);
		store.save(state);
		Files.writeString(file(bad.id()), "{ \"schema\": 1, \"id\": ");

		SaveStore.LoadResult loaded = store.load(0);
		assertEquals(1, loaded.problems().size());
		assertTrue(loaded.state().civilizations().get(good.id()).isPresent());
		assertTrue(loaded.state().civilizations().quarantined().contains(bad.id()));

		// Saving again must neither delete the broken file nor forget the record.
		loaded.state().createCivilization("New", 0);
		store.save(loaded.state());
		assertTrue(Files.exists(file(bad.id())));
		assertTrue(store.load(0).state().civilizations().quarantined().contains(bad.id()));
	}

	@Test
	void corruptIndexIsRebuiltFromRecordFiles() throws IOException {
		SaveStore store = new SaveStore(dir);
		SimulationState state = SimulationState.fresh(1);
		Civilization a = state.createCivilization("A", 0);
		store.save(state);
		Files.writeString(dir.resolve("index.json"), "garbage");

		SaveStore.LoadResult loaded = store.load(1);
		assertEquals(1, loaded.problems().size());
		assertTrue(loaded.state().civilizations().get(a.id()).isPresent());
		assertTrue(Files.exists(dir.resolve("index.json.corrupt")));
		assertNotEquals(a.id(), loaded.state().createCivilization("B", 0).id());
	}

	@Test
	void olderSchemaIsMigratedStepByStep() throws IOException {
		SimulationState state = SimulationState.fresh(1);
		Civilization a = state.createCivilization("A", 0);
		new SaveStore(dir).save(state); // written as schema 1

		SchemaMigrations v3 = new SchemaMigrations(3)
				.register(1, json -> {
					if (json.has("name")) {
						json.addProperty("name", json.get("name").getAsString() + "+v2");
					}
					return json;
				})
				.register(2, json -> {
					if (json.has("name")) {
						json.addProperty("name", json.get("name").getAsString() + "+v3");
					}
					return json;
				});
		SaveStore.LoadResult loaded = new SaveStore(dir, v3).load(0);
		assertEquals(List.of(), loaded.problems());
		assertEquals("A+v2+v3", loaded.state().civilizations().get(a.id()).orElseThrow().name());
	}

	@Test
	void recordFromNewerModVersionIsNotTouched() throws IOException {
		SimulationState state = SimulationState.fresh(1);
		Civilization a = state.createCivilization("A", 0);
		new SaveStore(dir, new SchemaMigrations(2)).save(state);

		SaveStore.LoadResult loaded = new SaveStore(dir).load(0);
		assertFalse(loaded.problems().isEmpty());
		assertTrue(loaded.state().civilizations().get(a.id()).isEmpty());
	}
}
