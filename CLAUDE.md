# Villager Civilization

Fabric mod for Minecraft **26.3** (Fabric Loader 0.19.5, Fabric API 0.162.0+26.3, Loom 1.18.4,
**Java 25**). The player recruits villagers, founds a settlement and watches it grow by itself
into a walled city. The player speeds it up; they never give orders. Singleplayer only.

This file is the map of the project. Read it instead of searching; open a source file only for
the detail you are about to change. Keep it up to date when the structure changes.

## Working with the owner

- **Chat in Uzbek. Everything in the repository is English**: code, comments, commit messages,
  in-game text, this file.
- **Test in the game yourself** (`tools/autotest.sh`) and read the report before saying a fix
  works. Unit tests alone are not proof. Ask the owner only about looks.
- **A Gradle build while the owner's game is open breaks that running game** (class loading fails
  with `ZipException` until restart). If something "suddenly stopped working", look for
  `ZipException` in `run/logs/latest.log` first, and always tell the owner to restart the game
  after you build.
- Commit only when asked. Work happens on `stage-2-town-life`; `main` is the PR base.
- A settlement must meet its own needs. Never make it wait for the player to top up stock.

## Design rules (decided, do not reopen)

| Rule | Meaning |
| --- | --- |
| Simulation is the truth | `core/` holds every fact. Entities, chests and blocks are a projection near the player. Losing an entity loses nothing. |
| Flat settlements | One height (`Settlement.grade()`) for all buildings, walls and the ground between. Max 4 blocks of difference. Fields, groves and mine entrances are left alone. Water and lava on a walled settlement's ground are not: ponds are filled and pools drained. |
| Original buildings | Every building is designed here (Python generators), never a vanilla structure. Only the street layout follows vanilla villages. Each house design is unique; the order they are built in is random. |
| Growth stops at the wall | A walled settlement builds only inside its wall. More room comes from an Expansion Charter. |
| Plans stack | A settlement may follow all Development Plans at once. |
| Great storehouse | 25x25 lot by the Town Center, built in stages; its chests mirror the ledger. |
| End goal | Late-game capitals that look like grand castles, reached with modular generators, not hand-typed megabuilds. |

## Commands

```
./gradlew :core:test        # simulation tests, no Minecraft, seconds
./gradlew build             # compile everything + tests; jar in build/libs
./gradlew runClient         # the owner's game (run/, player "Dev")
tools/autotest.sh [ticks] [world]   # unattended in-game check, see below
```

Java 25 comes from Homebrew (`/opt/homebrew/opt/openjdk@25`, set in `gradle.properties`).
Versions live in `gradle.properties`.

### In-game test harness

`tools/autotest.sh [ticks=24000] [world="New World"]` copies `run/saves/<world>` to
`run-autotest/saves/AutoTest`, opens it in a second small Minecraft window, fast-forwards and
prints every `AUTOTEST` log line. 24000 ticks (one day) takes about 4.5 minutes. The owner's
world and options are never touched. Full log: `run-autotest/logs/latest.log`; Gradle output:
`run-autotest/gradle.out`.

| Env var | Effect |
| --- | --- |
| `SHOWCASE=true` | Sets every house design down in a row to look at |
| `EXPAND=true\|pentagon\|hexagon\|octagon\|round\|grand\|grand:<shape>` | Hands the town an Expansion / shaped / Grand Charter |
| `SHORTAGE=true` | Removes all stone at tick 3000 (tests job switching) |
| `ROAM=true` | Teleports the player round the wall every 1500 ticks (tests far sites) |

The driver is `src/main/java/.../mod/AutoTest.java` (enabled by `-Dvillagerciv.autotest=<ticks>`).
It puts the player in spectator above the town, summons 3 husks and a creeper at tick 1000,
stages a book purchase from a librarian, and reports at intervals: stock, jobs, shortages and
citizens called away, pens (kept / young / loose), libraries and enchantments, patrols and
intruders, overlapping citizens, wall rings (pieces / complete / in world / condemned),
construction, ground grade and uneven columns, designs built. Add a report line there when you
add a system.

`tools/worldscan.py <region dir> x0 z0 x1 z1 y0 y1 names...` counts blocks in a saved world
(region dir: `run-autotest/saves/AutoTest/dimensions/minecraft/overworld/region`).

### Minecraft API lookups

26.3 is unobfuscated. Do not guess a signature; check it:

```
javap -cp .gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-*/26.3/*.jar net.minecraft.world.entity.Entity
```

Things that differ from older versions and have already cost time:

- Animal entity types are in `EntityTypes`, not `EntityType`.
- `SwingAnimation` is `net.minecraft.world.item.component.SwingAnimation`.
- Wool is `Items.WOOL.white()` (a colour collection).
- Enchanted book: `EnchantmentHelper.createBook(new EnchantmentInstance(holder, level))`;
  list all: `level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElements()`;
  tags `EnchantmentTags.CURSE` / `TREASURE`; name `Enchantment.getFullname(holder, level)`.
- `Entity.spawnAtLocation(ServerLevel, ItemStack)`; `isInvulnerableTo(ServerLevel, DamageSource)`.
- `Inventory` has no `countItem`; count and remove with a loop.
- `level.isPositionEntityTicking(pos)` tells whether a spot is being simulated.

## Layout

```
core/                 pure Java, no Minecraft on the classpath (compiler-enforced)
  src/main/java/com/sunnatbek/villagerciv/
    api/              ids and events
    scheduler/        tick budget, priorities, throttling
    sim/              records and rules: citizens, labor, population, defence
    build/            blueprints, town plan, construction, walls
    persistence/      JSON save files
    content/          registry of data-driven definitions
  src/main/resources/villagerciv/blueprints/   66 JSON files + index.json
  src/test/java/...   JUnit 6; harness/HeadlessSimulation runs the sim without the game
src/main/java/com/sunnatbek/villagerciv/
    mod/              Fabric side: hooks, world edits, items, commands
    mod/entity/       CitizenEntity and its goals
    mod/net/          packet payloads
    mixin/            one recipe-book mixin
src/main/resources/   fabric.mod.json, assets/villagerciv, data/villagerciv
src/client/java/.../client/   renderer and screens
tools/                autotest, world scan, blueprint and texture generators
run/                  owner's game directory (gitignored)
run-autotest/         harness game directory (gitignored)
```

`core` is shipped inside the mod jar (jar-in-jar). About 25 000 lines in total.

## Architecture

```
server tick ─► VillagerCivMod.tick ─► Simulation.tick ─► SimulationScheduler (time budget)
                     │                      ├─ "events"       every tick   EventBus.drain
                     │                      ├─ "expeditions"  every 200    ExpeditionUpkeep
                     │                      └─ "labor"        every 200    the settlement review
                     └─► EmbodimentController  records ⇄ CitizenEntity bodies, world catch-up
```

- **Records** (`Citizen`, `Settlement`, `Expedition`, `Civilization`) are the state. They live in
  `SimulationState` inside `RecordTable`s and are saved as JSON.
- **The review** (`Labor.CYCLE_TICKS = 200`) is where a settlement thinks: stock and shortages,
  job quotas, construction, population, defence, tools, trade.
- **Embodiment**: `EmbodimentController` is the only class that creates a `CitizenEntity`. A
  record has at most one body. Bodies exist only near the player and within the entity budget
  (`QualityPreset`: LOW 240, MEDIUM 340, HIGH 500; LEVEL and BUILD workers get bodies first). A citizen past the budget is invisible, so a
  town larger than the budget looks as if people vanished.
- **Seen and unseen work**: with a body, a citizen acts the job out block by block (goals).
  Without one, the work is only accounted for (`JobType.abstractRate`, construction steps) and
  the world is brought up to date when the player comes near (`BuildSites` catch-up).
- **Ledger, not chests**: `Ledger` is what a settlement owns, in five `Commodity` classes (FOOD,
  WOOD, STONE, ORE, TOOLS). `CommodityValues` maps items to commodities. Storehouse chests
  (`Warehouse`) and bedside chests (`PersonalChests`) are views. `Ledger.add` throws on 0.
- **Events** go through the queued `EventBus`; a listener never runs inside `publish`.

### Lifecycle of a settlement

1. **Recruit**: Recruitment Scroll on a vanilla villager → `RecruitmentHandler` → offer screen →
   the villager joins an `Expedition` (always accepts) and follows the player.
2. **Found**: Founding Charter → `FoundingHandler` surveys the site (`TerrainSnapshot` →
   `SiteSurvey` on a worker thread → `SiteScore`), the player confirms, a `TownCenterBlock` is
   placed and the expedition becomes a `Settlement`.
3. **Grow**: `SettlementStage` FOUNDING_CAMP → SETTLEMENT (6 citizens, needs a plan) → VILLAGE
   (16) → TOWN (40, smith and mason at work) → CITY (100, merchant at work). A settlement under
   half the size its stage took falls back a stage.
4. **Wall**: `Defense` decides when; `Fortification` plans a square palisade ring (gates on the
   four streets, towers), built as a second project beside ordinary building.
5. **Expand**: charters given at the Town Center of a settlement that `mayExpand()`:

   | Stage | Charter | Radius | Wall |
   | --- | --- | --- | --- |
   | 1 | none | at least 44 | palisade, square |
   | 2 | Expansion (square) or Pentagon / Hexagon / Octagon / Round Charter | land 96, wall 88 | stone rampart, 1 thick, 16 towers |
   | 3 | Grand Charter, or a shaped charter given to a City whose stage-2 wall is done | land 192, wall 176 | great wall, 4 thick with a walk on top, great towers and gates |

   A charter given too early is kept (`Settlement.plannedShape`) and used by itself when the town
   is ready (`FoundingHandler.growOnKeptCharter`). When a newer ring is complete the older one is
   condemned and demolished, and its guards move to the new wall (`Defense.outgrowFirstWall`).

## Core packages

### `sim/` — records and rules

| File | Role |
| --- | --- |
| `Simulation` | Owns state, scheduler and services; `tick`, `found`, `expand(settlement, plots, shape)` |
| `SimulationState`, `RecordTable`, `SimRecord`, `IdGenerator` | Tables of records; deterministic ids and random rolls |
| `Settlement` (1100 lines) | The settlement record: citizens, ledger, buildings, wall plan, plots, work sites, stage, plans. Key queries: `grade()`, `groundBounds()`, `withinWalls()`, `mayExpand()`, `mayExpandAgain()`, `wallShape` / `plannedShape`, `hasShapedWall()`, `pens()`, `libraries()`, `activeBuildings()`, `activeWallPieces()`, `wallCrews()`, `watchStock()` / `shortage(commodity)` (0–2), `calledAway()`, `strike(building)` |
| `Citizen` | Citizen record: role, job, `usualJob`, home, tool, loyalty, `Patrol {NONE, SWORD, CROSSBOW}`, `isOnDuty()`, `isCalledAway()` |
| `Role` | Affinity, not a lock: LEADER, GENERAL, FARMER, LUMBERJACK, MINER, BUILDER, LABORER, CHILD, LEVELLER, BLACKSMITH, MASON, MERCHANT, HERDER, LIBRARIAN |
| `JobType` | FARM, LUMBER, MINE (have a `WorkSite`), BUILD, LEVEL, SMITH, MASON, TRADE (workshops), HERD (one per pen), STUDY (one per lectern) |
| `Labor` | The job board. `quotas()` fills in this order: farm feeders → gatherers for whatever is short → BUILD → LEVEL → workshops, HERD, STUDY → need-weighted producers → leftovers. Citizens moved for a shortage are "called away" and go back to `usualJob` when it ends. Levellers are 1 in 8 workers; while `Settlement.hasHeavyGroundWork()` (hundreds of uneven spots pending, as after an expansion) they are 2 in 5 and come before builders |
| `Population` | Births, newcomers (`1 + size/25` at a time), morale, stage promotion and decline |
| `Defense` | Threat level, when to wall, `manPosts` (guards on towers and gates), `patrolsWanted` (as many patrols as post guards, from idle and overstaffed citizens, never more than leaves half the adults working), `outgrowFirstWall`. Under heavy ground work the militia stands down to one guard per tower and gate and a quarter of the patrols |
| `ResourceManager`, `StockStatus`, `Request` | Shortage ladder: move workers, open sites, and only last ask the patron |
| `Tools`, `ToolKind`, `Trade` | Tool making and hand-out; the market's exchange |
| `SettlementUpkeep`, `ExpeditionUpkeep` | Eating, hunger, loyalty |
| `Founding`, `SiteSurvey`, `SiteScore`, `TerrainSnapshot` | Founding checks and the site report; `Founding.expandedRadius(n) = 96 × n` |
| `SitePicker`, `WorkSite` | Where fields, groves and mines go on the plot map (off the four street lines, from the edge inwards) |
| `Recruitment`, `RoleAssignment`, `CitizenNames`, `Expedition`, `Civilization`, `Policy`, `Housing`* | Smaller rules (*`Housing` is in `build/`) |

### `build/` — what gets built and where

| File | Role |
| --- | --- |
| `Blueprint`, `BlueprintLibrary` | A design and the loader. Ids of special designs are constants in `Blueprint` (`SMITHY`, `MASONS_YARD`, `MARKET`, `LIBRARY`, `PENS`, `MAIN_STORE`, `LIBRARY_SEATS = 48`) |
| `Building` | One blueprint at one spot plus construction progress; `Stage` includes `CONDEMNED` |
| `ConstructionPlan` | Ordered steps (pave, level, blocks bottom-up, fittings last); a pure function, never saved |
| `Construction` | What to build next: beds first, workshops, pens, library, public buildings, housing ahead of growth; picks the least-built design that fits and can be paid for; runs wall crews in parallel |
| `TownPlan`, `Street` | Streets out from the Town Center, branching; lots along them |
| `Fortification`, `WallPiece`, `WallDesigns` | Square rings: `palisade_N` and `rampart_N` runs made to measure, gates, towers |
| `ShapedWall` | Polygon and round rings and the great wall. `Shape {SQUARE, PENTAGON, HEXAGON, OCTAGON, ROUND}`, `Style {RAMPART, GREAT}`. The ring is cut into 12x12 tiles generated on demand with ids `shapedwall.<style>.<shape>.<radius>.<ox>.<oz>`; `radius(stage) = 88 × stage` |
| `Housing` | Who sleeps where |

### Others

- `scheduler/`: `SimulationScheduler` serves queues by `TaskPriority` inside a per-tick time
  budget, with `ThrottleLevel`, `CircuitBreaker` and `Telemetry`. Long tasks return
  `TaskResult.CONTINUE`.
- `persistence/`: `SaveStore` writes `<world>/villagerciv/index.json` plus one file per record
  under `civilizations/`, `expeditions/`, `settlements/`; only dirty records, atomically.
  `SCHEMA_VERSION = 1`; `SchemaMigrations` upgrades one version at a time. **A new field on a
  record must be added to its codec (`SettlementCodec`, `ExpeditionCodec`) and read with a
  default**, or old saves break.
- `api/`: typed ids (`CitizenId`, `SettlementId`, ...) and `SimEvent` records.

## Mod side (`src/main`)

| File | Role |
| --- | --- |
| `VillagerCivMod` | Entry point and the only bridge: load, tick, save, hooks, payload registration. Static access: `simulation()`, `embodiment()` |
| `ModContent` | Items, the Town Center block, the citizen entity type, creative tab, recipe page; `isCharter`, `shapeOf` |
| `ModCommands`, `ModConfig` | `/villagerciv ...`; `config/villagerciv.json` (preset, monsters hunt citizens) |
| `EmbodimentController` (930 lines) | Bodies for records, the entity budget, record ⇄ entity sync, ground scans, monsters on settlement land (`intruder`, `answer`, `outOfReach`), `releaseStranded` |
| `FoundingHandler` | Founding flow, expansion flow (`proposeExpansion`, `expand`, `resurvey`, `growOnKeptCharter`) |
| `RecruitmentHandler` | The Recruitment Scroll |
| `TownCenterBlock` | Gifts to the ledger, charters and plans, opening the settlement screen |
| `BuildSites` | Places construction steps in the world at `settlement.grade()`, catch-up, repair, `demolish`, `lecterns` |
| `WorkSites` (900 lines) | What field, grove and mine work looks like, one block change at a time |
| `Landscaper` | Brings the ground inside the settlement to the grade; `keptOpen` / `keptClear` protect walls, fields, mines; `survey` counts uneven columns |
| `Lamplighter` | Finds dark spots for builders to light |
| `Warehouse`, `PersonalChests` | Storehouse chests and bedside chests |
| `Pens` | Animals of each pen: stocking, breeding, strays, slaughter of the surplus |
| `Librarians` | One enchantment per lectern; books cost 8 emeralds per level, double for treasure |
| `CommodityValues` | Item → commodity and amount |
| `MonsterAggression`, `WorkDay` | Monsters hunting citizens (optional); the working hours |

`mod/entity/`: `CitizenEntity` (the body: armour, weapon, name tag, interaction, leash to the
settlement, immune to cramming) and one goal per behaviour: `WorkGoal` (field, grove, mine),
`BuildGoal`, `LandscapeGoal`, `LampGoal`, `WorkshopGoal`, `HerdGoal`, `StudyGoal`, `HaulGoal`,
`GoHomeGoal`, `TowerPostGoal` (wall guards), `PatrolGoal` (town guards, never retreat),
`FollowExpeditionGoal`, `StayInSettlementGoal`.

`mod/net/`: server → client `RecruitOfferPayload`, `ExpeditionInfoPayload`,
`SiteReportPayload`, `SettlementInfoPayload`; client → server `RecruitConfirmPayload`,
`FoundConfirmPayload`, `ExpeditionDisbandPayload`.

Client (`src/client`): `VillagerCivClient`, `CitizenRenderer` (a villager with a player's build,
so armour and held items show), `PanelScreen` base and four screens (`RecruitOffer`,
`Expedition`, `SiteReport`, `Settlement`). Screens are read-only.

## Content

**Items** (`ModContent`): `recruitment_scroll`, `founding_charter`, `blank_plan`,
`agrarian_plan`, `industry_plan`, `military_plan`, `surveyors_seal`, `expansion_charter`,
`pentagon_charter`, `hexagon_charter`, `octagon_charter`, `round_charter`, `grand_charter`.
Block: `town_center`. Entity: `citizen`.

**A new item needs** all of: a field in `ModContent`, `assets/villagerciv/items/<id>.json`,
`models/item/<id>.json`, a texture in `textures/item/`, a recipe in
`data/villagerciv/recipe/<id>.json` with `"group": "villagerciv"`, its unlock advancement in
`data/villagerciv/advancement/recipes/misc/<id>.json`, and a name in `lang/en_us.json`.
The four shaped charters currently reuse the Expansion Charter texture.

**Commands** (operators): `/villagerciv status`, `preset <low|medium|high>`, `danger <on|off>`,
`debug create <name> | list | expeditions | settlements | export | showcase [design]`.

### Blueprints

`core/src/main/resources/villagerciv/blueprints/<id>.json`, listed in `index.json` (a design
not in the index does not exist).

```json
{
  "id": "cottage", "name": "Cottage", "category": "housing", "beds": 2,
  "cost": { "wood": 140, "stone": 60 },
  "min_stage": 0,
  "late": "DdBb",
  "palette": { "C": "minecraft:cobblestone", "D": "minecraft:spruce_door[half=lower,facing=north,hinge=left]" },
  "layers": [ [ "  CCC  ", "..." ], [ "..." ] ]
}
```

`category` is one of `shelter`, `housing`, `storage`, `public`, `workshop` or the defence
category used by gates and towers (`Blueprint.Category`). `layers` go bottom-up, rows north to
south, one character per block; space is "leave alone" (`Blueprint.LEAVE`) and `.` is "clear to
air" (`Blueprint.CLEAR`). `late` lists palette keys placed last (doors, beds, lanterns, chests). Optional
`storage` gives stockpile capacity. Designs are drawn with the door facing north and turned to
face their street.

Never write a blueprint by hand. Use or extend a generator and run it from the repo root:

| Generator | Produces |
| --- | --- |
| `gen_houses.py` | hut-era houses (10) |
| `gen_fine_houses.py` | timber-framed village houses (10, `min_stage` 2) |
| `gen_unique_houses.py` | 20 unique houses; class `G` is the grid toolkit (`put`, `box`, `body`, `door`, `beds`, `roof_z`, `roof_x`, `hip`, `result`, `check`) and the best starting point for new designs |
| `gen_store.py` | `storehouse`, `great_store_0..3` |
| `gen_workshops.py` | `smithy`, `masons_yard`, `market` |
| `gen_stage2.py` | `well`, `tavern`, `chapel`, `fountain`, `stone_gate`, `stone_tower` |
| `gen_pens.py` | `cattle_pen`, `sheep_pen`, `pig_sty`, `chicken_coop` |
| `gen_library.py` | `library` (48 lecterns) |
| `gen_stage3.py` | `great_tower` (7x7x14), `great_gate` (14x7x13) |
| `GenCitizenTexture.java`, `GenSealTexture.java` | textures |

Wall runs (`palisade_N`, `rampart_N`, `shapedwall.*`) are generated in Java at run time and have
no file.

## Recipes for common changes

- **New building type**: generator → JSON + `index.json` → constant in `Blueprint` → when to
  build it in `Construction` → if it is a workplace, a `JobType`, its quota in `Labor.quotas()`
  and a goal in `mod/entity/` registered in `CitizenEntity`.
- **New job**: `JobType` (+ `Role` if it has an affinity) → `Labor` (open positions, quota,
  order) → goal → `Activity` for the screen → `LaborTest` → a report line in `AutoTest`.
- **New saved field**: record → codec with a default → `SaveStoreTest`.
- **Balance change**: change the rule in `core`, cover it in the matching test
  (`LaborTest`, `PopulationTest`, `DefenseTest`, `ConstructionTest`, `GrowthPaceTest`), then run
  the autotest for a day or more.
- **Anything a citizen does in the world**: change one block per action, ration path searches,
  and make sure the same result is reached when nobody is near.

## Pitfalls already paid for

- Never put several citizens on one block. Entity cramming killed 21 citizens once; bodies are
  now immune and `releaseStranded` spreads them 4–14 blocks round the Town Center.
- Work sites on a street line block the town plan; `SitePicker` keeps them off.
- A "no room" result must not be cached for good; retry every 12 reviews.
- Levellers work from the rim and never walk into deep holes or caves.
- A monster nobody can reach must be blacklisted (`outOfReach`) or it holds every patrol.
- Pens leak animals through anything climbable next to the fence; keep fences clear.
- A body is only useful where the game ticks entities (`level.isPositionEntityTicking`). A city is
  wider than the simulation distance, so citizens asleep on the far side lose their bodies; by
  day `embodyNearPlayers` sets them down round the Town Center (or near the player). Never
  re-embody a record at a position that is loaded but not ticking. Levelling has no unseen rate,
  so bodiless levellers mean no levelling at all.
- Always run the autotest with the owner's settings too: `STAY=true RD=32 SD=12 tools/autotest.sh`
  (player left where the world was saved, render distance 32, simulation distance 12). With the
  default small view only the town centre is loaded, and bugs that depend on a wide loaded area
  do not show: levellers once worked in the test and stood idle in the owner's game.
- `Landscaper.scan` takes columns in a scattered order (prime stride), never row by row: a
  pending list filled from one edge sends every leveller to that edge. Ground that is loaded but
  not entity-ticking is levelled from afar (`LandscapeGoal` does not walk to it). Columns that
  stay uneven after being worked on go to `Ground.stuck` and are retried every few rounds.
  On a walled settlement's kept ground (bounds + 12 verge) water and lava are filled like any
  hollow, flooded verge columns are filled right up to the grade, and a sheet of fluid lying on
  level ground is removed whole with `drainPool` (one block at a time it closes over again).
  Only citizens, players and land animals stop a fill; fish do not.
- "They stopped working" is answered from the owner's own log: `EmbodimentController.logStatus`
  writes a `status <town>: ...` line every 1200 ticks (levellers, bodies, ground waiting, day,
  throttle). `AutoTest` prints `Landscaper.unevenReasons` (why each uneven column is still so).
  The owner's game and the autotest are separate processes with separate worlds but share
  `build/`: never build or run the autotest while the owner is playing without saying so.
- `Settlement.hasHeavyGroundWork()` is set from a count over a full scan round (saved), not from
  the length of the pending list. A mild, trend-only shortage does not take levellers off heavy
  ground work; only a store that is all but empty does.
- The stock has no capacity: nothing ever stops because "the store is full".
- Builders on a far, unloaded site freeze; unseen sites are reckoned, not walked to.
- Gate tiles and wall cells are excluded from levelling; only the wall cells of a tile, not the
  whole tile.

## Known gaps

- Stage-2 shaped walls and the demolition of a stage-2 ring after stage 3 are covered by unit
  tests only, not yet watched in the game.
- Levelling uses the bounding box of polygon and round walls, so corners outside the ring are
  graded too.
- Some crowding at shared spots remains.
- Towns above the entity budget show only part of their people.
- Not built yet: colonies and a second settlement, logistics between settlements, raids, the
  Civilization AI, blueprint upgrades. Roadmap and history per phase are in `README.md`.
