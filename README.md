# Villager Civilization

**Recruit villagers, found a settlement, and watch it grow on its own — from a camp of a few
followers into a walled city, and at the end a castle floating over it.**

Villager Civilization is a Fabric mod for Minecraft Java Edition. Your villagers are not workers
you command: they are a people who decide for themselves. They pick their own jobs, build their
own houses, mine, farm, guard their walls and raise their children. You are their **patron**: you
lead them to a good place, hand them charters and plans, and help with goods when you want to.
You never tell anyone what to do — and if you leave, the settlement goes on living without you.

---

## Contents

1. [Requirements and installation](#requirements-and-installation)
2. [Quick start](#quick-start)
3. [Items and recipes](#items-and-recipes)
4. [Your journey, step by step](#your-journey-step-by-step)
5. [How a settlement lives](#how-a-settlement-lives)
6. [Settlement stages](#settlement-stages)
7. [Walls and expansion](#walls-and-expansion)
8. [The castle](#the-castle)
9. [Commands and settings](#commands-and-settings)
10. [Good to know](#good-to-know)
11. [FAQ](#faq)

---

## Requirements and installation

| You need | Version |
| --- | --- |
| Minecraft Java Edition | **26.3** |
| Fabric Loader | **0.19.5** or newer |
| Fabric API | for 26.3 |
| Java | **25** |

1. Install Fabric Loader for Minecraft 26.3.
2. Put **Fabric API** and **`villagerciv-<version>.jar`** into your `.minecraft/mods` folder.
3. Start the game with the Fabric profile.

The mod is made for **single-player**.

> **Back up your worlds first.** The mod raises the height of the Overworld to **y = 607** (see
> [The castle](#the-castle)). This applies to every world you open while the mod is installed.

---

## Quick start

1. Craft a **Recruitment Scroll** and use it on a villager. Recruit at least **4** villagers.
2. Feed your followers as you travel (use food on a follower).
3. Craft a **Founding Charter** and use it on the ground where you want to live.
4. Read your Leader's report and confirm. A **Town Center** block appears: your settlement exists.
5. Give the settlement a **Development Plan** (use it on the Town Center).
6. From now on, watch. Give wood, stone and food to the Town Center if you want them to grow faster.

---

## Items and recipes

All recipes appear on the mod's own page in the crafting book.

| Item | Recipe | What it does |
| --- | --- | --- |
| **Recruitment Scroll** | 8 Emeralds around 1 Paper | Use on a villager to invite it to follow you. |
| **Founding Charter** | Paper + Feather + Ink Sac + Emerald (shapeless) | Use on a block to propose that spot for a new settlement. |
| **Blank Plan** | Book in the middle, 4 Paper on its sides | The sheet every plan and charter is made from. |
| **Development Plan: Agrarian** | Blank Plan + Hay Bale | Food and people first. |
| **Development Plan: Industry** | Blank Plan + Iron Pickaxe | Wood, stone and ore first. |
| **Development Plan: Military** | Blank Plan + Iron Sword | Strength first; walls without waiting to be attacked. |
| **Surveyor's Seal** | Gold Ingot top and bottom; Diamond, Compass, Emerald across the middle | Ingredient of the charters. |
| **Expansion Charter** | Blank Plan + Surveyor's Seal | Lets a Town grow beyond its first wall (square wall). |
| **Expansion Charter (Five-Sided Wall)** | Expansion Charter + 5 Stone Bricks | Same, with a five-sided wall. |
| **Expansion Charter (Six-Sided Wall)** | Expansion Charter + 6 Stone Bricks | Same, with a six-sided wall. |
| **Expansion Charter (Eight-Sided Wall)** | Expansion Charter + 8 Stone Bricks | Same, with an eight-sided wall. |
| **Expansion Charter (Round Wall)** | Expansion Charter + Snowball | Same, with a round wall. |
| **Grand Charter** | Blank Plan + 2 Surveyor's Seals + Diamond Block | Lets a City grow again, behind a great wall. |
| **Build Castle for Octagonal Place** | Grand Charter in the middle, Diamond Blocks in the corners, Gold Blocks on the sides | Raises a castle over an eight-sided City. |

```
Build Castle for Octagonal Place

  [Diamond Block] [ Gold Block  ] [Diamond Block]
  [ Gold Block  ] [Grand Charter] [ Gold Block  ]
  [Diamond Block] [ Gold Block  ] [Diamond Block]
```

---

## Your journey, step by step

### 1. Recruiting

Use a **Recruitment Scroll** on a villager. A screen shows who it is and what it would say;
confirm to make the offer. The villager joins your **expedition** and follows you.

- An expedition holds up to **8** villagers.
- Each villager takes a **role** of its own (Leader, General, Farmer, Builder, Miner and so on)
  based on its old profession. You choose who joins, never who does what.
- A villager asked on the land of your own settlement joins that settlement directly.

### 2. Travelling

Your followers walk with you in a loose group and catch up if they fall behind.

- **They eat.** Use food on a follower to feed the group (sneak to give the whole stack). Hungry
  followers lose trust in you, and a follower who has lost all trust leaves.
- **They camp** where you leave them when you log out, die or change dimension, and follow again
  when you come back.
- Use a follower with an empty hand to open the **expedition screen** (members, food, disband).
- The **General** fights monsters; the others keep out of the way.

### 3. Founding

You need at least **4** followers. Use the **Founding Charter** on the ground where you want the
settlement.

- Your **Leader surveys the place** and reports how good it is (land, water, wood, stone, danger).
  Some places are refused outright: water or anything that is not land, ground with no level spot
  even for a camp, or land that already belongs to another settlement.
- Confirm, and a **Town Center** block is set down. The land around it is claimed, and your
  followers become its citizens. The food you carried goes into its store.

### 4. Helping

The **Town Center** is how you deal with the settlement:

| You use on the Town Center | Result |
| --- | --- |
| Wood, stone, food, ore, tools | The whole stack is given to the settlement's store. |
| A Development Plan | The Leader considers it and takes it up. |
| A charter | The Leader considers it (see [Walls and expansion](#walls-and-expansion)). |
| An empty hand | Opens the **settlement screen**: store, tools, citizens and what each is doing. |
| An empty hand while sneaking | Followers still with you move into the settlement. |

Using an empty hand on a citizen also opens the settlement screen.

---

## How a settlement lives

The settlement runs itself. Nothing below needs your attention, but knowing it helps you help.

### The store

What a settlement owns is counted in five kinds: **food, wood, stone, ore and tools**. The chests of
its storehouse show the store. Workers carry what they bring in to the chest by their bed, and in
the evening to the storehouse. The store has no size limit.

### Jobs

Citizens choose their own work, by what is needed most and what they are good at:

| Job | Work |
| --- | --- |
| Farmer | Fields: tilling, sowing, harvesting. Farmers come first when food runs low. |
| Lumberjack | Groves: planting and felling trees. |
| Miner | Stair mines that follow ore veins. Brings home ore and rock. |
| Builder | Houses, workshops, walls; lamps in dark corners. |
| Leveller | Keeps the ground flat (see below). |
| Blacksmith | Works the Smithy (built at the Settlement stage): better, cheaper tools and armour. |
| Mason | Works the Mason's Yard (Village): builders work faster. |
| Merchant | Works the Market (Village): trades what is plentiful for what is short. |
| Herder | Keeps one animal pen: cattle, sheep, pigs or chickens. |
| Librarian | Keeps one enchantment at a lectern in the library. |
| Guard | Stands on the wall's towers and gates, or patrols the streets. |

When something runs short (food, wood or stone), citizens are **called away** from less urgent
work to bring it in, and go back to their own work when the store is full again. Librarians are
never called away.

### Houses and growth

- Builders build houses ahead of need. There are **81 house designs**, all different, from a
  bamboo hut to a quartz manor. The settlement builds every design once, in a random order, before
  it builds any design a second time.
- **Children** are born when there are spare beds, enough food, good morale and a Development Plan.
  They grow up in three days and take up a trade.
- **Newcomers** arrive by themselves to a settlement that has a plan, spare beds and full stores.
- Every citizen eats one meal a day. A settlement without food loses morale and, in the end, people.

### Flat ground

A settlement is built at one height. **Levellers** cut down hills and fill hollows across the whole
walled area, and fill ponds, flooded pits and lava pools inside the wall. Fields, groves and mine
entrances are left alone. After an expansion, when there is a lot of uneven ground, many more
citizens become levellers and most guards put down their arms to help, until the ground is level.

### Development Plans

A plan is a direction, not an order. A settlement needs **at least one plan** to grow past the
first stage. Plans stack: a settlement can follow all three. A plan followed alone has a small
cost (specialising); following several makes up for each other's costs.

| Plan | Leans towards |
| --- | --- |
| Agrarian | Better farming, more children |
| Industry | Better mining and felling |
| Military | A stronger General, steadier nerves, a wall without waiting to be attacked |

### Requests

Now and then a settlement **asks you** for something it is short of, with a reason. You may help in
part or not at all; a request simply expires. Helping when it is really needed raises your standing.
A settlement always tries to meet its own needs first.

### Libraries and enchanted books

A Town that has grown beyond its first wall builds a **library** (48 lecterns each, as many libraries
as it needs) and keeps **one librarian for every enchantment in the game** (curses excepted).

- Use a librarian with an empty hand: it tells you which enchantment it keeps and the price.
- Use it **holding a Book**, with the emeralds in your inventory: it writes its enchantment into
  the book **at the highest level**.
- Price: **8 emeralds per level**, double for treasure enchantments (those an enchanting table
  never gives, such as Mending).
- **Sneak** and use any librarian to list who keeps what.

### Animal pens

Pens come with the first expansion. A herder brings the first pair in, breeds them, fetches animals
that stray, and when the pen is full takes the surplus for **meat, leather and wool** for the store.

### Guards and danger

- By default monsters ignore citizens. Turn on real danger with `/villagerciv danger on`.
- When citizens are attacked, the settlement's **threat** rises. It fades over quiet days.
- Guards stand on the wall's towers and gates day and night, and as many again **patrol** inside the
  town with swords and crossbows. Patrols know of every monster on the settlement's land.
- Guards get armour made from the store's ore.

---

## Settlement stages

A settlement rises a stage when it meets the conditions below and **keeps meeting them for a quarter
of a day**, with a bed for everyone. A settlement that falls under half the size its stage required
falls back a stage after a day.

| Stage | Citizens | Also needs |
| --- | --- | --- |
| Founding Camp | — | (the start) |
| Settlement | 6 | a Development Plan, a storehouse, a field, 3 days of food |
| Village | 16 | 5 days of food, morale 60 or more |
| Town | 40 | the above, and a **blacksmith** and a **mason** at work |
| City | 100 | the above, and a **merchant** at work |

---

## Walls and expansion

### The first wall

A settlement builds a **wooden palisade** with gates and watchtowers once it has been attacked
enough, or at once if it follows the **Military** plan. A walled settlement builds only inside its
wall. When there is no room left, it stops growing and simply lives on — until you give it more land.

### Stage 2 — Expansion Charter

Use an **Expansion Charter** on the Town Center of a **Town** (or a Village that is out of room):

- The land is surveyed out to **96 blocks** and claimed.
- A **stone wall** is planned **88 blocks** out, with **16 towers**. The old wall comes down when
  the new one is finished, and its guards move to the new wall.
- Animal pens, a library and public buildings (well, fountain, tavern, chapel) follow.
- The plain charter gives a square wall. The shaped charters give a **five-, six- or eight-sided** or
  a **round** wall.

### Stage 3 — Grand Charter

Use a **Grand Charter** on the Town Center of a **City** whose stage-2 wall is finished:

- The land doubles, to **192 blocks**.
- A **great wall**, four blocks thick with a walk along its top, is built **176 blocks** out, with
  great towers and great gates.
- The Grand Charter keeps the shape of the town's wall. A **shaped charter** given to a ready City
  does the same and uses that shape. A shaped charter given to a Town that is not ready yet is
  **kept**, and the city grows on it by itself the day it is ready.

A settlement that has used its Grand Charter refuses another one: *"We have grown as far as any
charter can take us."*

---

## The castle

The last stage of a city's life.

**Requirements:** a **City** with an **eight-sided** great wall that is **finished**.

**How to start:** use **Build Castle for Octagonal Place** on the Town Center, or simply **throw it
onto the Town Center** (press Q while looking at it).

**What happens:**

1. A **brigade of 24 builders** is formed from the city's workers.
2. Hidden lights are placed over the whole town, so fields keep growing and no monster spawns in
   the shadow that is coming.
3. Every **great tower** of the wall is extended upwards into a **pillar** with a **spiral staircase**
   inside.
4. A **stone platform** is laid on the pillars, about **18 blocks above the tallest building** in
   the city.
5. The **castle** is built on the platform, course by course from the bottom up — about 2.7 million
   blocks: a dark gothic citadel with terraces, a town of its own, towers and a cathedral whose spire
   reaches past y = 470.

**How long:** the whole castle is finished in **7 in-game days** (about 2 hours 20 minutes of play).
It takes **1 stone for every 10 blocks** from the store while the store can spare it; if the store
runs dry, the brigade builds on anyway so the castle is finished in time.

**Getting up:** climb the spiral stairs inside any tower pillar; they come out on the platform.

**Notes:**

- Parts of the castle far from you are built when you come close; nothing is lost while you are away.
- The brigade works on the castle by day and comes down to the town at night.
- The castle needs the taller world (**y up to 607**) that the mod sets. If you see *"The sky is too
  low here"*, the mod's data pack is not active in that world.

---

## Commands and settings

All commands need operator rights (cheats on).

| Command | Effect |
| --- | --- |
| `/villagerciv status` | The mod's load on your game, and how many settlements and expeditions exist. |
| `/villagerciv preset <low\|medium\|high>` | How many citizens can be visible at once. |
| `/villagerciv danger <on\|off>` | Whether monsters hunt citizens (off by default). |
| `/villagerciv debug ...` | Tools for testing (`create`, `list`, `expeditions`, `settlements`, `export`, `showcase`). |

Settings are kept in `config/villagerciv.json`. The preset is chosen from your computer on first
launch:

| Preset | Citizens visible at once |
| --- | --- |
| low | 240 |
| medium | 340 |
| high | 500 |

---

## Good to know

- **Citizens live on when you cannot see them.** Away from you, their work is counted instead of
  acted out; when you return, the world catches up with what was done. A citizen beyond the visible
  limit is still there, only not shown.
- **Your view distance matters.** The mod builds and levels only where the game has loaded the world.
  A larger render distance lets more of a big city work in sight.
- **Pausing stops everything.** In single-player the game pauses when you open the menu or switch
  away from the window.
- **Citizens sleep at night.** Work is done by day. If you set the time to day, they start again.
- **Settlements keep to their wall.** A full walled settlement does not grow until you give it a
  charter.
- **One block, many uses.** Almost everything you do with a settlement goes through its Town Center.

---

## FAQ

**My villager refused or vanished after recruiting.**
Check that your expedition is not already full (8). A recruit far beyond the visible limit is not
shown; try `/villagerciv preset medium`.

**The levellers stopped working.**
They work only by day and only on loaded ground. Look in `logs/latest.log` for lines starting with
`status <town name>`: they show how many levellers there are, how many are at work and how much
ground is waiting.

**The Leader refuses my charter.**
Read the message: the town may be too small, not yet a City, its wall not finished, or it has
already used that charter.

**The castle does not start.**
It needs a City with a finished **eight-sided** great wall. Use the Eight-Sided Expansion Charter
(or a Grand Charter on an eight-sided city) first.

**Fields under the castle stopped growing.**
They should not: the platform comes with hidden lights. If a spot is dark, the light there may have
been taken by a building; the lamps builders put up cover such corners.

---

## Credits

Made by **Sunnatbek**. The castle design comes from the *CastleForge* generator.
All rights reserved.

Developer notes and the project's history are in [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md).
