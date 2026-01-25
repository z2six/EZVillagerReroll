# Farming Stats

Every villager has 4 Farming stats (range **-100 to +100**) that influence farming behavior.

Server owners control how strong these stats are via `config/villageroverhaul-server.toml`.

---

## Motivation

Affects how long the villager will work during the day while in Manual Farming mode.

- Higher motivation = longer work window.
- Lower motivation = shorter work window.

Default server clamps: **-20% .. +20%** (at -100 and +100 stat points).

---

## Efficiency

Efficiency affects seed/bonemeal consumption:

- Positive efficiency = chance to **not consume** a seed/bonemeal.
- Negative efficiency = chance to **consume an extra** seed/bonemeal.

Default clamps: **-20% .. +20%**.

!!! warning

    Avoid configuring very high positive efficiency.

    A true 100% “save chance” can make seeds effectively infinite. Villager Overhaul hard-caps the save chance to **95%** as a safety measure.

---

## Plant Whisperer

Every X seconds (server config), the villager may “free-bonemeal” a nearby crop during manual farming.

- Default interval: **60 seconds**.
- Default base chance: **50%** at 0 stat points.

The Plant Whisperer stat modifies that chance.

---

## Ranger

Ranger affects the villager’s **maximum** allowed farming range around its workstation.

- Default base range: **10 blocks**.
- Default clamps: **-20% .. +20%**.

Players can still choose a smaller range in the Manual Farming settings, but can’t exceed the max.

---

??? note "Technical details"

    - All stats are stored per villager and survive world restarts.
    - Server config defines “min/max percent” at -100/+100 and the mod interpolates linearly between them.
