package com.sunnatbek.villagerciv.sim;

import com.sunnatbek.villagerciv.api.CitizenDiedEvent;
import com.sunnatbek.villagerciv.harness.HeadlessSimulation;
import com.sunnatbek.villagerciv.persistence.SaveStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecruitmentTest {
	private static final UUID PLAYER = new UUID(1, 1);

	private final HeadlessSimulation headless = new HeadlessSimulation(2026);
	private final Simulation simulation = headless.simulation();
	private long villagers;

	/** Keeps asking fresh villagers until one accepts. */
	private Citizen recruit(String profession) {
		for (int attempt = 0; attempt < 200; attempt++) {
			Recruitment.Result result = simulation.recruit(PLAYER, new UUID(2, villagers++), profession, null, false, 1, 64, 1);
			if (result instanceof Recruitment.Accepted accepted) {
				return accepted.citizen();
			}
		}
		throw new AssertionError("nobody accepted");
	}

	private static Citizen citizen(long n, Role affinity, int loyalty, Trait... traits) {
		Citizen citizen = new Citizen(new com.sunnatbek.villagerciv.api.CitizenId(new UUID(9, n)), "C" + n, 0, affinity,
				traits.length == 0 ? Set.of() : EnumSet.copyOf(List.of(traits)));
		citizen.setLoyalty(loyalty);
		return citizen;
	}

	@Test
	void aVillagerShownTheScrollAlwaysComes() {
		assertEquals(1.0, Recruitment.acceptChance(false, true));
		for (int i = 0; i < Recruitment.MAX_EXPEDITION_SIZE; i++) {
			// Employed, and asked at night: the worst case there used to be.
			assertInstanceOf(Recruitment.Accepted.class, simulation.recruit(PLAYER, new UUID(9, i), "farmer", null, true, 0, 0, 0));
		}
	}

	@Test
	void firstRecruitCreatesTheExpeditionAndLaterOnesJoinIt() {
		Citizen first = recruit("farmer");
		Citizen second = recruit("none");

		Expedition expedition = simulation.expeditionOf(PLAYER).orElseThrow();
		assertEquals(1, simulation.state().expeditions().size());
		assertEquals(2, expedition.size());
		assertEquals(Role.FARMER, first.affinity());
		assertEquals(Role.LABORER, second.affinity());
		assertNotEquals(first.id(), second.id());
	}

	@Test
	void expeditionHasAMaximumSize() {
		for (int i = 0; i < Recruitment.MAX_EXPEDITION_SIZE; i++) {
			recruit("none");
		}
		assertInstanceOf(Recruitment.ExpeditionFull.class,
				simulation.recruit(PLAYER, new UUID(4, 4), "none", null, false, 0, 0, 0));
	}

	@Test
	void rolesAreAssignedByTheGroupItself() {
		Citizen loyal = citizen(1, Role.FARMER, 90);
		Citizen charismatic = citizen(2, Role.MINER, 60, Trait.CHARISMATIC);
		Citizen brave = citizen(3, Role.LABORER, 50, Trait.BRAVE);
		Citizen smith = citizen(4, Role.GENERAL, 50);
		List<Citizen> group = List.of(loyal, charismatic, brave, smith);

		RoleAssignment.assign(group);
		assertEquals(Role.LEADER, charismatic.role());
		assertEquals(Role.GENERAL, brave.role());
		assertEquals(Role.FARMER, loyal.role());
		assertEquals(Role.LABORER, smith.role());

		// Exactly one leader, whatever the group looks like.
		assertEquals(1, group.stream().filter(c -> c.role() == Role.LEADER).count());
	}

	@Test
	void deadLeaderIsReplacedAndEmptyExpeditionEnds() {
		List<CitizenDiedEvent> deaths = new ArrayList<>();
		simulation.events().subscribe(CitizenDiedEvent.class, deaths::add);
		recruit("none");
		recruit("farmer");
		recruit("mason");
		Expedition expedition = simulation.expeditionOf(PLAYER).orElseThrow();
		Citizen leader = expedition.members().stream().filter(c -> c.role() == Role.LEADER).findFirst().orElseThrow();

		simulation.citizenDied(expedition.id(), leader.id());
		assertEquals(2, expedition.size());
		assertEquals(1, expedition.members().stream().filter(c -> c.role() == Role.LEADER).count());

		for (Citizen citizen : List.copyOf(expedition.members())) {
			simulation.citizenDied(expedition.id(), citizen.id());
		}
		assertTrue(simulation.expeditionOf(PLAYER).isEmpty());
		headless.runTicks(1);
		assertEquals(3, deaths.size());
	}

	@Test
	void expeditionSurvivesSaveAndLoadExactly(@TempDir Path dir) throws IOException {
		recruit("farmer").setPosition(10.5, 70, -3.25);
		Citizen hurt = recruit("weaponsmith");
		hurt.setHealth(7.5f);
		hurt.setEmbodied(true);
		simulation.expeditionOf(PLAYER).orElseThrow().setState(Expedition.State.CAMPED);

		SaveStore store = new SaveStore(dir);
		store.save(simulation.state());
		SaveStore.LoadResult loaded = store.load(0);

		assertEquals(List.of(), loaded.problems());
		assertEquals(store.export(simulation.state()), store.export(loaded.state()));
		Expedition expedition = loaded.state().expeditions().all().iterator().next();
		assertEquals(PLAYER, expedition.player());
		// Nothing is dirty right after loading, and no citizen claims to have a body.
		assertEquals(0, store.save(loaded.state()));
		assertTrue(expedition.members().stream().noneMatch(Citizen::isEmbodied));

		// A change to a citizen marks its expedition for saving.
		expedition.members().iterator().next().setHealth(1f);
		assertEquals(1, store.save(loaded.state()));
	}

	@Test
	void indexFromBeforeExpeditionsExistedStillLoads(@TempDir Path dir) throws IOException {
		java.nio.file.Files.writeString(dir.resolve("index.json"),
				"{\"schema\":1,\"idSeed\":5,\"idCounter\":0,\"civilizations\":[]}");
		SaveStore.LoadResult loaded = new SaveStore(dir).load(0);
		assertEquals(List.of(), loaded.problems());
		assertEquals(0, loaded.state().expeditions().size());
	}
}
