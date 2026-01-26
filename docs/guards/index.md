!!! note
    The Guards pages are written at version 3.6B.1. It's possible that there are newer versions available that have made adjustments to these mechanics.

# Guards (Combat Module)

The Guard module lets recruited villagers fight using real gear (armor, weapons, shields) and a configurable combat AI.

## Quick start

1. Recruit a villager (ownership is required for controls): [Recruiting & Ownership](../general/recruiting.md).
2. Open the villager inventory screen and equip:
   - Armor in the armor slots.
   - A weapon/tool in the Combat Main Hand loadout slot.
   - A shield in the Combat Offhand loadout slot (optional, for blocking).
3. Pick a combat mode in the `Commands` panel:
   - Defend: reacts to triggers like "owner attacked/attacks".
   - Aggressive: attacks entities based on a whitelist/blacklist.
   - Flee: runs away when threatened (and can fall back to combat if hit).
4. Configure behavior in Combat Settings (per-villager or global): [Modes & Commands](modes.md).

## What the module includes

- Combat modes (Off / Defend / Aggressive / Flee) with triggers and filters.
- Combat AI: approach, spacing, melee swings, circling/strafe, shield blocking, and eating.
- Equipment & loadouts: player-friendly UI slots that are server-authoritative: [Loadouts & Equipment](loadouts.md).
- Combat stats that modify health/speed/damage/armor: [Combat Stats](stats.md).

!!! note
    Server owners can disable the entire combat module via server config. If disabled, combat modes/settings won't do anything even if the UI exists.