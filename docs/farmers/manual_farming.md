# Manual Farming

Manual Farming is an AI mode that takes over the villager’s movement to farm in a defined area.

It is intended to solve common vanilla/modded issues (for example modded crops not being picked up reliably) by:

- scanning for work to do,
- moving precisely to targets,
- and following player-configured rules.

---

## Enabling manual farming

1. Recruit the villager.
2. Ensure it is a **Farmer**.
3. Ensure it has a **workstation**.
4. Configure the rules in **Farming Settings → Manual Farming**.
5. Toggle **Manual Farming** from the Farming command buttons (button `M`).

Manual farming is exclusive with movement modes like idle/patrol/follow: enabling it will replace the active movement mode, and disabling it restores the previous mode.

---

## Work area

Manual farming works around a workstation position.

- By default, the mod uses the villager’s **vanilla job site** if it has one.
- You can also register a workstation manually (any block) from the Manual Farming settings tab.

### Range + shape

- The villager operates inside a **range** around the workstation.
- Shape can be **circular** or **square**.

The server also enforces a **maximum** range based on the villager’s **Ranger** stat.

---

## Rules (what to plant/harvest)

Manual farming uses **item IDs** (not block IDs) so it works for modded items.

- **Planting rules**: items the villager is allowed to place.
- **Harvest rules**: items that identify crops the villager should harvest.

### Nether wart support

- Nether wart is planted on **soul sand**.
- Most other crops are planted on **farmland**.

---

## Pickup rules

Pickup rules control which dropped items the villager will pick up while manual farming.

- If Pickup rules are **empty**: it picks up **all items**.
- If Pickup rules contain items: it picks up **only those items**.

Pickup is always limited to the villager’s work area.

---

## Optional features

### Use bonemeal

If enabled, the villager will use bonemeal on crops inside the work area.

Important: the villager must actually have bonemeal available (typically via your withdraw rules).

### Toss other items

If enabled, the villager will periodically drop items that are not part of its configured farming + logistics lists.

### Till soil

If enabled, the villager will convert **dirt/grass → farmland** when it can.

- Requires a **hoe** equipped.
- This is the **lowest priority** action, and it respects timeout/retry so it won’t get stuck.

---

## Time of day

Manual farming only actively works during a configurable daytime window, similar to vanilla behavior.

The **Motivation** stat can expand or shrink this work window.

---

## XP + leveling

Server owners can configure villager XP gain from **planting**.

- XP from planting is awarded while the villager is farming in **Neutral** mode (vanilla AI).
- Manual farming still counts actions in the villager history, but planting XP is intended as a vanilla-farming reward.

---

## Modded tool compatibility (Fortune hoes, etc)

When manual farming harvests a crop, it breaks the block using a server-side “fake player” action.

This is done so that:

- Fortune / special harvest logic can apply,
- modded tools that rely on player harvest hooks still work.

The fake player is not a real visible player entity (it is not added to the player list).

---

??? note "Technical details"

    - Manual farming scans every ~10 ticks (about twice per second).
    - Action priority inside manual farming is:
      bonemeal → pickup → plant → harvest → till → roam.
    - The overall system prioritizes storage runs first when deposit/withdraw rules trigger.
