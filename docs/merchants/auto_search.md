# Auto-Search

Auto-search lets you request items and have the villager reroll in the background until it finds one.

## Opening the catalog

From the villager trade screen:

- **Right-click the reroll button** to open the catalog.

The catalog shows “possible outputs” for that villager.

---

## Starting an auto-search

1. Select one or more target items in the catalog.
2. Start auto-search.

While searching:

- The villager becomes **busy**.
- You can’t open the normal merchant screen (the mod shows a Busy screen instead).

---

## Cost model (hourly + final)

Auto-search cost is computed server-side.

It is intentionally designed so that:

- auto-search is never “free forever”,
- but it remains fair across villagers with different numbers of offers.

### Inputs

From server config:

- `freeOffers`
- `costPerOffer`
- `autoHourlyThreshold`
- `autoHourlyDiscountOrIncreasePct`

From the villager:

- `offersTotal` (current number of offers)
- `offersLocked` (locked offers)

### Step 1: compute “paid offers”

- `unlockedOffers = max(0, offersTotal - offersLocked)`
- `paidOffers = max(0, unlockedOffers - freeOffers)`

### Step 2: manual-cost baseline

- `manualCost = paidOffers * costPerOffer`

(You’ll see this as “Manual cost” in the catalog UI.)

### Step 3: hourly cost scaling (threshold balancing)

Auto-search has an hourly rate derived from the manual baseline:

- `hourlyBase = manualCost * 10`

Then it applies a threshold-based factor:

- `steps = paidOffers - autoHourlyThreshold`
- `factor = max(0, 1 - steps * (autoHourlyDiscountOrIncreasePct/100))`
- `hourlyCost = ceil(hourlyBase * factor)`

What this means:

- If `paidOffers` is **below** the threshold, `steps` becomes negative -> the factor increases -> hourly cost becomes **more expensive**.
- If `paidOffers` is **above** the threshold, `steps` becomes positive -> the factor decreases -> hourly cost becomes **cheaper**.

Finally, **Generosity** applies as a discount/surcharge.

(You’ll see “Hourly cost” in the catalog UI.)

### Step 4: final cost

When the search finishes, the server charges based on how long it ran:

- `finalCost = ceil(hourlyCost * (elapsedTicks / 72000))`

Where `72000 ticks = 1 real hour` at 20 TPS.

---

## Reroll “memory” (anti-abuse)

Auto-search remembers how much work you’ve asked villagers to do **per requested item** (per player).

In tooltips you’ll see something like:

- `72 Rerolls (128 emeralds)`

This is not refunded if you cancel or decline payment.

What it means:

- Each auto-search attempt adds “reroll units” (1 unit = 1 offer rerolled).
- That value is tracked per-player per-item and shown in tooltips.
- The tooltip also shows a live conversion into currency based on current server config.

??? note "Technical details (V units)"

    - Each attempt contributes `V = offersRerolledPerRerollAtStart`.
    - `offersRerolledPerRerollAtStart = offersAtStart - lockedAtStart`.
    - That `V` is distributed evenly across all requested targets (searching for multiple items shares the work).

---

## Payment currency

Servers can change what item (or item tag) is used to pay rerolls.

Examples:

- `minecraft:emerald`
- `lightmanscurrency:coin_emerald`
- `#minecraft:logs`

If Lightman’s Currency is installed and the server enables it, costs may be paid from a wallet.

---

## XP gain

Servers can optionally grant villager XP for successful auto-search rerolls.

- Base XP is configured per rerolled offer.
- Your villager’s **Intellect** stat modifies XP gained.

??? note "Technical details (XP formula)"

    XP (before Intellect) is computed roughly as:

    `autoSearchXpPerOffer * offersRerolledPerRerollAtStart * successfulRerollCount`
