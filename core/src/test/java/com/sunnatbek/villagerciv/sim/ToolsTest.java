package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.harness.HeadlessSimulation;
import com.sunnatbek.villagerciv.persistence.SaveStore;
import com.sunnatbek.villagerciv.sim.TerrainSnapshot.Surface;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolsTest {
	private static final UUID PLAYER = new UUID(1, 1);

	private static Settlement settle(HeadlessSimulation headless, int members) {
		for (long v = 0; headless.simulation().expeditionOf(PLAYER).map(Expedition::size).orElse(0) < members; v++) {
			headless.simulation().recruit(PLAYER, new UUID(2, v), "none", null, false, 0, 64, 0);
		}
		TerrainSnapshot snapshot = new TerrainSnapshot(0, 0, Founding.SURVEY_RADIUS);
		for (int i = 0; i < snapshot.columnCount(); i++) {
			snapshot.set(i, 64, Surface.SOIL);
		}
		Settlement settlement = headless.simulation().found(PLAYER, 0, 64, 0, SiteSurvey.analyse(snapshot)).orElseThrow();
		headless.runTicks(Labor.CYCLE_TICKS * 3);
		return settlement;
	}

	@Test
	void needFollowsWhatPeopleActuallyDo() {
		Settlement settlement = settle(new HeadlessSimulation(9), 8);
		long farmers = settlement.members().stream().filter(c -> c.job() == JobType.FARM).count();
		long lumberjacks = settlement.members().stream().filter(c -> c.job() == JobType.LUMBER).count();

		assertEquals(farmers, Tools.needed(settlement, ToolKind.HOE));
		assertEquals(lumberjacks, Tools.needed(settlement, ToolKind.AXE));
		assertEquals(1, Tools.needed(settlement, ToolKind.PICKAXE));
		assertEquals(farmers + lumberjacks, Tools.needed(settlement, ToolKind.SHOVEL), "everyone who levels ground");
		assertEquals(1, Tools.needed(settlement, ToolKind.SWORD), "the General");
	}

	@Test
	void toolsGoToThoseWhoUseThemUntilTheyRunOut() {
		Settlement settlement = settle(new HeadlessSimulation(9), 8);
		List<Citizen> lumberjacks = settlement.members().stream().filter(c -> c.job() == JobType.LUMBER).toList();
		Citizen miner = settlement.members().stream().filter(c -> c.job() == JobType.MINE).findFirst().orElseThrow();
		assertEquals(2, lumberjacks.size());
		assertFalse(Tools.equipped(settlement, lumberjacks.get(0), ToolKind.AXE));

		settlement.contributeTools(PLAYER, ToolKind.AXE, 1);
		assertTrue(Tools.equipped(settlement, lumberjacks.get(0), ToolKind.AXE));
		assertFalse(Tools.equipped(settlement, lumberjacks.get(1), ToolKind.AXE), "one axe, two lumberjacks");
		assertFalse(Tools.equipped(settlement, miner, ToolKind.AXE), "an axe is no use to a miner");
		assertFalse(Tools.equipped(settlement, miner, ToolKind.PICKAXE));

		settlement.contributeTools(PLAYER, ToolKind.AXE, 5);
		assertTrue(Tools.equipped(settlement, lumberjacks.get(1), ToolKind.AXE));
		assertEquals(6, settlement.ledger().onHand(Commodity.TOOLS), "the ledger keeps the total");
		assertEquals(6, settlement.contributionOf(PLAYER));
	}

	@Test
	void theRightToolMakesWorkFaster() {
		Settlement settlement = settle(new HeadlessSimulation(9), 8);
		Citizen miner = settlement.members().stream().filter(c -> c.job() == JobType.MINE).findFirst().orElseThrow();
		int bare = Labor.efficiencyPercent(settlement, miner, JobType.MINE);

		settlement.contributeTools(PLAYER, ToolKind.HOE, 10);
		assertEquals(bare, Labor.efficiencyPercent(settlement, miner, JobType.MINE), "the wrong tool changes nothing");
		settlement.contributeTools(PLAYER, ToolKind.PICKAXE, 1);
		assertEquals(bare * Tools.TOOL_SPEED_PERCENT / 100, Labor.efficiencyPercent(settlement, miner, JobType.MINE));
	}

	@Test
	void equippedSettlementOutproducesABareHandedOne() {
		HeadlessSimulation bareHanded = new HeadlessSimulation(9);
		HeadlessSimulation equipped = new HeadlessSimulation(9);
		Settlement a = settle(bareHanded, 8);
		Settlement b = settle(equipped, 8);
		for (ToolKind kind : ToolKind.values()) {
			b.contributeTools(PLAYER, kind, 8);
		}

		bareHanded.runDays(3);
		equipped.runDays(3);
		for (Commodity commodity : List.of(Commodity.WOOD, Commodity.STONE)) {
			int without = produced(a, commodity);
			int with = produced(b, commodity);
			assertTrue(without > 0, "work goes on without tools: " + commodity);
			// The other settlement makes tools of its own before long, so the head start is modest.
			assertTrue(with * 10 >= without * 9, commodity + " with tools " + with + " vs without " + without);
		}
		assertTrue(a.tools(ToolKind.AXE) > 0 && a.tools(ToolKind.PICKAXE) > 0, "a settlement given no tools makes its own");
		assertEquals(0, a.contributionOf(PLAYER), "with nobody's help");
	}

	/** What a settlement has brought in: what is in store plus what its finished buildings used up. */
	private static int produced(Settlement settlement, Commodity commodity) {
		int total = settlement.ledger().onHand(commodity);
		for (com.sunnatbek.villagerciv.build.Building building : settlement.buildings()) {
			if (building.stage() == com.sunnatbek.villagerciv.build.Building.Stage.COMPLETE) {
				total += commodity == Commodity.WOOD ? building.costWood() : building.costStone();
			}
		}
		return total;
	}

	@Test
	void toolsSurviveSaveAndLoadAndOldSavesGetAnEvenMix(@TempDir Path dir) throws IOException {
		HeadlessSimulation headless = new HeadlessSimulation(9);
		Settlement settlement = settle(headless, 5);
		settlement.contributeTools(PLAYER, ToolKind.SWORD, 2);
		settlement.contributeTools(PLAYER, ToolKind.SHOVEL, 3);
		SaveStore store = new SaveStore(dir);
		store.save(headless.simulation().state());

		Settlement restored = store.load(0).state().settlements().get(settlement.id()).orElseThrow();
		assertEquals(2, restored.tools(ToolKind.SWORD));
		assertEquals(3, restored.tools(ToolKind.SHOVEL));
		assertEquals(0, restored.tools(ToolKind.AXE));

		// A save from before tools were kept by kind: only the ledger total exists.
		Path file;
		try (Stream<Path> files = Files.list(dir.resolve("settlements"))) {
			file = files.findFirst().orElseThrow();
		}
		String json = Files.readString(file);
		int start = json.indexOf("\"tools\": {");
		int end = json.indexOf("},", start) + 2;
		Files.writeString(file, json.substring(0, start) + json.substring(end));
		Settlement old = store.load(0).state().settlements().get(settlement.id()).orElseThrow();
		for (ToolKind kind : ToolKind.values()) {
			assertEquals(1, old.tools(kind), "5 unknown tools become one of each kind");
		}
	}
}
