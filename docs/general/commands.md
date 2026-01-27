# Keybinds & Commands

## Keybinds

- `K`: open global combat settings (client-side keybind).
- *(Unbound by default)*: open **Player Chat Commands** (bind it in Controls under the Villager Overhaul category).

## Server commands (OP-only)

- `/vo_takeheld [radius]`:
  - Requires permission level 2 (OP).
  - Finds the nearest villager in radius (default 16).
  - If you have space, moves the villager's **main hand** and **offhand** item stacks into your inventory.
  - If your inventory is full, nothing is removed from the villager.

- `/vo_fixgenprices [radius]`:
  - Requires permission level 2 (OP).
  - Recalculates nearby villagers' trade prices from **Generosity only**.
  - This intentionally wipes other discount sources (like curing) as a one-time admin fix.
  - Default radius is 32.

## Client commands (debug/dev)

This mod includes a few client-side debug commands (model/part visibility, block test utilities, etc.).
