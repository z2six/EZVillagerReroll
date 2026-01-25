# Combat Stats

Villagers spawn with combat stat points (range `-100` to `+100`).

These points are converted into real gameplay values using the server config, then applied as server-side attribute modifiers.

## Stats overview

| Stat | What it affects | Config keys | Unit |
|---|---|---|---|
| Vitality | Max health | `combat.vitalityMinHealth` / `combat.vitalityMaxHealth` | Health points (2 = 1 heart) |
| Agility | Movement speed | `combat.agilityMinSpeed` / `combat.agilityMaxSpeed` | Raw `movement_speed` add |
| Strength | Melee damage | `combat.strengthMinDamage` / `combat.strengthMaxDamage` | Damage points |
| Armor | Armor value | `combat.armorMin` / `combat.armorMax` | Armor points |

## How points map to values

For each stat:

- `-100` points maps to the min config value
- `+100` points maps to the max config value
- values in-between are linearly interpolated

In short: the server config defines how strong -100 and +100 are.

??? example "Example (defaults)"
    Default server config values (if your server owner didn't change them):

    - Vitality: `-6.0` to `+10.0` max health
    - Agility: `-0.1` to `+0.1` movement speed
    - Strength: `-3.0` to `+3.0` attack damage
    - Armor: `-5.0` to `+5.0` armor

## Important notes

- These are additive deltas applied on top of the villager's normal stats.
- Gear matters: weapons/armor still apply normally.
- Server owners can disable the combat module; if disabled, combat stats may exist but are not used.