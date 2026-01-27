!!! note
    The Farmers pages are written at version 3.6B.1. It's possible that there are newer versions available that have made adjustments to these mechanics.

# Farmers

The Farming module is split into two parts:

- **Logistics**: move items to/from chests (deposit + withdraw) using per-item rules.
- **Manual farming**: a controllable “take-over” farming AI that plants/harvests/picks up drops inside a work area.

Both systems are designed to work with **modded crops and items** by using *player-configured rules* instead of hardcoding “only wheat/carrot/potato”.

---

## Requirements (important)

Most farming features require all of the following:

- The villager is **recruited** (owned by you).
- The villager has the **Farmer** profession.
- The villager has a **workstation** (job site) so it has a defined farming area.

If a villager has no workstation, farming automation won’t run and deposit/withdraw chests cannot be registered.

---

## Quick start

1. Recruit a **Farmer** villager.
2. Open **Farming Settings**.
3. In **Manual Farming** tab: register a workstation (or rely on the farmer’s vanilla job site), set range + shape.
4. Configure:
   - **Planting rules** (what to place)
   - **Harvest rules** (what to harvest)
5. (Optional) In **Logistics** tab: register **Deposit** / **Withdraw** chests and add rules.
6. Enable **Manual Farming** from the Farming commands (button `M`).

---

## How it behaves (high level)

During manual farming the villager follows a strict priority order:

1. **Withdraw / Deposit** (if any rule triggers)
2. **Use bonemeal** (if enabled and available)
3. **Pick up drops** (filtered by Pickup rules)
4. **Plant**
5. **Harvest**
6. **Till soil** (optional; lowest priority)

When it has nothing to do, it will **roam** inside its work area.

---

## XP + history tracking

The mod keeps farming history counters (visible in the villager **Info - History** tab), split by:

- **Neutral (vanilla)** farming
- **Manual farming** mode

Server owners can also enable villager XP gain from **planting** (configurable). This XP is awarded while the villager is farming in **Neutral** mode.

---

## Pages

- **Logistics (Chests)**: how deposit/withdraw works and how to configure per-item rules.
- **Manual Farming**: how the manual AI works, workstation/range, bonemeal, tilling, and modded crop support.
- **Farming Stats**: what the 4 farming stats do and how server config affects them.
