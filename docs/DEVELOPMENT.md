# Villager Civilization — development notes

A Fabric mod for Minecraft 26.3 (Fabric Loader 0.19.5, Java 25). Recruit villagers, found a
settlement, and watch it grow into a civilization that lives on its own — the player speeds it
up but does not rule it. Singleplayer only.

The core idea: the civilization lives in an abstract **simulation model**; Minecraft entities
are only a temporary projection of it near the player.

## Layout

| Path | What | Minecraft on classpath |
| --- | --- | --- |
| `core/` | Simulation core: scheduler, event bus, records, persistence, content registries | No |
| `src/main/` | Platform integration: Fabric hooks, commands, config | Yes |
| `src/client/` | Client-only code (screens, overlays) — from P1 on | Yes |

`core` is a plain Java project, so the rule "the simulation never imports Minecraft" is
enforced by the compiler, and its tests run in seconds without the game. It is shipped inside
the mod jar.

## Commands

```
./gradlew :core:test   # simulation tests, no Minecraft
./gradlew build        # mod jar in build/libs
./gradlew runClient    # launch the game with the mod
```

Items: Recruitment Scroll, Founding Charter, Blank Plan and the three Development Plans (all craftable).

In game (operators): `/villagerciv status`, `/villagerciv preset <low|medium|high>`, `/villagerciv danger <on|off>`
(whether monsters hunt citizens; off by default),
`/villagerciv debug create <name> | list | expeditions | settlements | export`.

## Testing

- `./gradlew test` runs the core's headless tests (no Minecraft involved).
- `tools/autotest.sh [ticks] [world]` checks the mod in the real game without anyone at the keyboard: it copies a
  saved world into `run-autotest/`, opens it, fast-forwards the given number of ticks (a 24000-tick day takes about
  four and a half minutes), and prints a report of every settlement at intervals: stock, jobs, what each citizen
  is doing, the state of the mines, and what is in the storehouse and the home chests. See `AutoTest.java`.

## Roadmap

MVP = P0–P6: one settlement, 6 founders, up to ~30 citizens, alive without the player.

- [x] **P0 Foundations** — scheduler with tick budget, adaptive throttling and circuit breaker;
  queued event bus; dirty-only atomic saves with schema migrations; content registry; telemetry;
  quality presets; headless harness. *Still open: on-screen debug overlay, GameTest setup, CI.*
- [x] **P1 Citizen & Recruitment** — citizen and expedition records, Recruitment Scroll (8 emeralds
  around paper), offer screen, refusal with cooldown, self-assigned roles, Citizen entity that is
  never saved with chunks, formation follow with catch-up teleport, camp on logout / death /
  dimension change, shared food supply, loyalty and desertion, the General fights while the others
  flee, expedition screen with disband. *Still open:* entity budget (comes with P5), refusal
  cooldown is not saved across restarts.
- [x] **P2 Founding & Settlement Core** — Founding Charter, hard checks, site survey (snapshot over
  ticks + worker-thread analysis) with the Leader's report, Town Center block, settlement record,
  territory claim with a spatial index, plot map, ledger with reservations, expedition supplies
  moved into the ledger, giving goods through the Town Center (credited to the player), settlement
  screen. *Still open:* initial zoning (comes with the street layout in P4), stockpile capacity (comes with the Storage building in P4).
- [ ] **P3 Labor & Production** — *done:* work sites picked from the plot map (field, grove, mine),
  job board with quotas, trade affinity and leases, work acted out in the world (level the
  ground, dig a well, till, sow, harvest; plant and fell trees; cut a stair mine with galleries off each level, following exposed ore veins), hauling to the chest by one's own bed (cottages have one per bed) or to the
  Town Center, flat-rate production while nobody is near, settlement hunger, night rest, rationed
  path searches. *Still open:* path cache, per-citizen hunger and rest meters, in-game 3-day run.
- [ ] **P4 Blueprint Construction** — *done:* text blueprints (`core/src/main/resources/villagerciv/
  blueprints`), a street-and-lot town plan (streets grow from the Town Center and branch;
  buildings take random free lots along them, facing the street), construction as one ordered
  list of steps (pave, level the footprint, blocks bottom-up, fittings last), materials reserved
  in the ledger, builders who raise it block by block, building on while nobody is near with the
  world caught up afterwards (the deferred edit queue), and repair of broken blocks. Citizens
  are given beds, go home at night through doors that close behind them, and sleep in their own beds. First
  designs: hut, cottage, storehouse, and ten more houses of different sizes and materials (birch cabin,
  stone croft, longhouse, timber house, tower house, brick house, mossy lodge, weaver's house, manor, deepslate
  hall; drawn by `tools/gen_houses.py`, each with a chest per bed). Ten finer houses for villages and up
  (`tools/gen_fine_houses.py`, `min_stage` 2): timber-framed, with a porch, window boxes with flowers, a hedge, rugs,
  a kitchen corner and a table, and for six of them an upper storey jettied over the door, reached by a stair,
  with books, a lectern and decorated pots. A settlement only picks designs it can pay for. `/villagerciv debug
  showcase [design]` sets the house designs down in a row for a look. The wall is a second project built alongside
  the ordinary one. Main storehouse: a 25x25 lot is set aside beside the Town Center at founding and the
  building grows on it with the stage (hall, wings, walled yard; `tools/gen_store.py`). Its chests mirror the
  ledger both ways while a player is near; workers fill the chest by their bed and carry it over in the evening. Where no street lot
  fits it, the storehouse takes the free square nearest the Town Center (an eighth of it may be rough ground). Lots are
  levelled whatever it takes (banks cut 10 high, pits filled 16 deep, trees felled), and citizens with spare time fill
  holes in the settlement's ground layer by layer, leaving mine entrances, fields, groves and streets alone. A settlement with no such
  square left builds the hall alone (`great_store_0`, 13x15), which is never enlarged. Levellers are a job of their own
  (one per ten workers while there are holes to fill); anyone else helps in spare time. A walled settlement keeps to the ground inside its wall: lots, streets and work sites
  stop there, and when no lot is left it builds nothing more, gains no more people, and simply works on (the
  settlement screen says so). The ring is drawn at least 44 blocks out, since it is all the room there will be. A stretch of wall is
  built from the highest ground beside it and stands on a stone footing, so it runs level across a hollow (sunken
  stretches of an older wall are raised). Each finished watchtower is manned by a guard with a crossbow; defenders
  have a suit of iron made from 24 ore each. Miners bring home what they actually found (coal, raw iron, copper,
  gold, diamonds, emeralds, redstone, lapis), and it is kept as such in chests and storehouse. Citizens are drawn as villagers with a player's
  build (own skin, `tools/GenCitizenTexture.java`), so the iron armour, swords, crossbows and work tools they carry
  show on them as they would on a player. A settlement meets its own needs: it makes the
  tools its workers lack (2 wood and 3 ore each, or stone for want of ore) and, from the Settlement stage on, opens
  another field, grove or mine for whatever runs short once the existing ones are fully manned. Builders light the
  place: wherever the ground inside the settlement gets less than 9 block light, one of them puts up a lamp (a post
  with a lantern, in three makes; 2 wood and 1 ore), and anyone idle helps. A miner brings home everything dug, not
  only ore: cobble, deepslate, andesite and the other rocks, gravel and flint, earth, sand, clay. *Still open:* more designs (the catalogue is meant to
  reach about 200: types × tiers × biome palettes), levelling streets, upgrades, a design
  validator. Buildings are original designs, not vanilla village pieces; only their arrangement
  follows vanilla villages.
- [ ] **P5 Population & Settlement AI** — *done:* children (born where there is a spare bed, food
  and morale; half rations, no work, grown in three days), housing built ahead of growth, morale
  that feeds back into work speed, soft cap per stage, promotion from Founding Camp to Settlement
  after holding its conditions for a day, entity budget, workers rebalanced when needs change.
  *Still open:* utility-scored priorities in place of the fixed build order and labor fractions,
  apprentices, immigration.
- [ ] **P6 Resource Manager & Player Relations** — *done:* stock statuses with hysteresis, shortages
  that first move workers and open more sites and only then become requests to the patron
  (reasoned, partial, expiring, at most three), standing earned by how much a gift was wanted,
  Development Plans (Agrarian, Industry, Military) that can be followed side by side, a plan's
  cost applying only while it is the only one; promotion needing a plan. Newcomers: a settlement with
  a plan, spare beds and full stores draws grown citizens, paid for from the store; promotion comes at 6 and 16
  citizens, below the stage caps, so growth never waits at a full cap. Recruiting into a settlement: a villager asked on the
  land of the player's own settlement joins it directly; followers move in by sneak-using the Town Center with empty hands. *Still open:* substitution and postponement steps of the response ladder,
  rewards beyond standing, "finished N days early" feedback.
- [ ] **P8 Defense & Military** — *done:* zombies and skeletons can hunt citizens (off by default, `/villagerciv danger on`), a threat level that
  rises with attacks and fades in quiet days, a part-time militia sized by stage and threat,
  guards and the General on night watch, a square palisade ring planned around everything the
  settlement has (made-to-measure wall pieces, a gate with a door on each side, a watchtower on
  each corner), built through the normal construction queue after beds and before growth; a
  Military plan fortifies without waiting to be hurt. *Still open:* wall shapes other than a
  square, using natural barriers, stone walls and upgrades, raids, guard posts on the towers.
- [ ] **P9 Progression & Technology** — *done:* three trades with workshops of their own (`tools/gen_workshops.py`): a
  Blacksmith in the Smithy (built at the Settlement stage; forged tools make work 1.75x instead of 1.5x faster, tools
  and armour take a third less ore and are made three times as fast), a Mason in the Mason's Yard (Village; builders
  work 1.3x faster) and a Merchant in the Market (Village; while something is short, sells 12 of what is plentiful
  for 8 of it each review). All three are acted out at their bench, tool in hand. Village to Town at 40 citizens with
  smith and mason at work; Town to City at 100 with a merchant. A settlement under half the size its stage took falls
  back a stage after a day. A child is apprenticed to the trade with the most untaught hands in it. *Still open:*
  blueprint upgrades, road network.
- [ ] **P10 Expansion & Multi-Settlement** — *done:* the Expansion Charter (blank plan + Surveyor's Seal; the seal is
  two gold ingots, a diamond, a compass and an emerald), given at
  the Town Center of a Town: the land is surveyed out to 96 blocks and claimed, streets leave through the gates of
  the first wall, fields, groves and mines may lie further out, and a walled settlement plans a second ring 88 blocks
  out in stone (`rampart_N`, `stone_gate`, `stone_tower`), the first staying inside it. Public buildings follow (well,
  tavern, chapel, fountain; `tools/gen_stage2.py`), each lifting morale. Builders reckon work on sites the game is not
  running rather than walking into them. *Still open:* colonies (a town founding a second settlement), logistics
  between settlements, districts, the Civilization AI.
- [ ] P7 LOD & scale · P11 Trade · P12 Rival civilizations
