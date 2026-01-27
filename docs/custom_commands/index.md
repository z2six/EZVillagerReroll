!!! note
    The Custom Commands pages are written at version 3.7B5.0. It's possible that there are newer versions available that have made adjustments to these mechanics.

# Custom Commands

Custom Commands let you **teach a recruited villager a sequence of actions** (a “teaching”), then **trigger it from chat**.

Use it for things like:

- “Open the gates” → villager walks a route and presses buttons/levers.
- “Lock the castle” → close doors, flip switches, interact with blocks/entities.
- Move items: withdraw from a chest and deposit into another chest.

---

## Where to find it

In the villager UI, look for the **Custom Commands** row (redstone dust texture).

- **Teach**: start recording a teaching.
- **List**: view, edit, re-teach, or forget teachings.

---

## Important rules

- **Owner-only**: only the recruiting owner can teach and run a villager’s teachings.
- **Villager must be nearby**: teaching/editing expects the villager to be close (like other control features).
- **Combat can interrupt**: if a fight happens, combat takes priority; the villager resumes afterwards.

??? note "Loaded chunks"
    Teachings can only run on villagers that are currently loaded by the server.
    Chain propagation can only “spread” through loaded villagers as well.

---

## Next pages

- [Teachings (Macros)](teachings.md)
- [Chat Commands](chat.md)
- [Localized Chat](../general/localized_chat.md)
