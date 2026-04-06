# Modes & Commands

Combat is controlled in two layers:

- Combat Mode (what role the villager takes): Off / Defend / Aggressive / Flee.
- Combat Settings (when it triggers + filters + AI toggles).

## Modes

### Off

- Villager will not run the Guard combat AI.
- You can still equip gear, but it won't be actively used for fighting.

### Defend

Defend reacts to recent damage events near the villager (and also if the villager itself was hit).

You can enable any of these triggers:

- Owner attacked: if you (the owner) were damaged, attack the attacker.
- Owner attacks: if you damage something, attack your target.
- Entity attacks: if any nearby entity attacks something, attack the attacker (filterable).
- Entity attacked: if any nearby entity is attacked, react (filterable).

Each trigger has its own Whitelist and Blacklist (entity type IDs like `minecraft:zombie`).

Notes:

- Defend only reacts to *recent* hurt events (roughly the last 2 seconds).
- Defend will never target the owner or other recruited villagers that share the same owner.

### Aggressive

Aggressive mode is proactive: the villager looks for nearby entities and attacks them.

- You can target by:
  - Aggressive whitelist / blacklist
  - `Target aggressive mobs` (monster-category mobs)
  - `Target passive mobs` (creature/ambient/water categories)
- If all aggressive filters are empty/off, the villager does nothing.

Notes:

- Blacklist always wins.
- If the whitelist has entries, only whitelisted entities are allowed after blacklist filtering.
- A blacklist-only setup means "attack everything in range except these entries".
- Category toggles are additive: they work even if the whitelist is empty.
- Aggressive will never target the owner or other recruited villagers that share the same owner.
- Aggressive scans a 3D box around the villager with `inflate(16.0)` and picks the nearest valid target.

### Flee

Flee makes the villager run away from threats.

- Threat detection uses the same trigger+filter system as Defend/Flee settings.
- If the villager gets hit (including a shield-blocked hit), it can immediately exit flee and fall back to its previous combat mode.

Notes:

- Flee is not meant to be an "eternal panic" mode. If a threat reaches the villager, it will often snap back into combat.

## Combat vs movement/manual farming

Villagers can have:

- A movement/manual-farming activity (Idle/Follow/Patrol/Manual Farming, etc.)
- A combat mode (Defend/Aggressive/Flee)

When a combat condition happens, combat temporarily takes over. When the fight ends, the villager returns to what it was doing.

## Combat settings (per-villager vs global)

You can configure combat behavior in two places:

- Per-villager Combat Settings: from that villager's UI (owner-only).
- Global Combat Settings template: a server-wide template you can sync into a villager.

In the Combat Settings screen:

- `Save` writes settings.
- `Sync` copies the global template into this villager.

!!! note
    Global settings updates require operator permissions.

??? tip "Where is the global Combat Settings screen?"
    Use the combat settings keybind (`K`) to open the global template screen.
    See: [Keybinds & Commands](../general/commands.md).

## Settings tabs

### Flee / Defend tabs

- Toggle triggers (Owner attacked/attacks, Entity attacks/attacked)
- Configure whitelist/blacklist per trigger

### Aggressive tab

- Configure aggressive whitelist/blacklist
- Toggle hostile/passive category targeting

### AI tab

High-level switches for behavior:

- Blocking (shield)
- Eating (auto-heal)
- Circling (strafing/spacing)
- Eating tuning (Force hits / Max resets)

See: [Combat AI Behavior](ai.md).
