# Chat Commands

Villager Overhaul uses chat in two ways:

1. **Teachings (macros)**: per-villager, owner-only, triggered by a phrase you set on the teaching.
2. **Player Chat Commands**: a player-wide panel where you can bind phrases to common villager modes and actions.

---

## Player Chat Commands (mode switching)

This is a **player setting**, not per-villager.

### Opening the screen

The keybind is **unbound by default**:

1. Open **Controls**
2. Find the Villager Overhaul category (`key.categories.ezvr`)
3. Bind **Chat Commands** (`key.ezvr.chat_commands`)

### What you can bind

You can bind phrases for:

- Help (combat assist)
- Equip / Stash (loadout visuals)
- Neutral, Idle, Follow, Patrol
- Manual Farming
- Flee, Defend, Aggressive

!!! note
    Mode-switch phrases always *turn the mode on*. If the villager is already in that mode, nothing changes.

### Range + chain

- **Range**: how far your villagers can “hear” your chat commands (clamped by server config).
- **Chain**: villagers can pass your command to other nearby villagers (a chain reaction).
- **Case sensitive**: toggles whether matching is exact-case or case-insensitive.

---

## Help (combat assist)

Help is meant to feel like: “Everyone, help me!”

When triggered:

- Nearby recruited villagers enter **HELP** combat mode.
- They attack targets related to what you recently attacked / what attacked you.
- When the fight ends, they return to what they were doing.

??? details "Technical notes"
    - Help uses a short-lived per-player target set (it is not permanent aggro).
    - Combat can override movement/manual farming temporarily, then villagers resume.

---

## Equip / Stash (roleplay)

These are one-time “visual swap” commands:

- **Equip**: force the villager’s registered loadout items into the real hands.
- **Stash**: return them back to the loadout slots.

Limits:

- Not allowed while the villager is in **Manual Farming** or **Neutral** mode.
- Other behaviors can overwrite it later (e.g., patrol equips, idle stashes) — that’s intended.

---

## Per-villager Custom Commands settings

Each villager has its own Custom Commands permissions:

- **Listen to chat commands**: whether this villager can trigger its teachings from chat.
- **Pass chat commands**: whether it can forward commands to nearby villagers (for long-distance chain propagation).
- **Pass range**: how far it can forward.

This is useful for “relay villagers” in big builds.

!!! note
    For Shout/Whisper/Localized chat settings, see: [Localized Chat](../general/localized_chat.md).
