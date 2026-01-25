# Rerolling & Locking

## Manual reroll

On the villager trade screen, the reroll button behaves like this:

- **Left-click**: perform a manual reroll.
- **Right-click**: open the Auto-search catalog (see **Auto-Search**).

A manual reroll replaces only the villager’s **unlocked** offers.

---

## Cost model (the important part)

Manual reroll cost is computed from how many offers are **actually rerolled**.

### Definitions

- `offersTotal` = total number of offers the villager currently has
- `offersLocked` = how many of those offers are locked
- `offersRerolled = max(0, offersTotal - offersLocked)`

Server config values:

- `freeOffers` (default `2`)
- `costPerOffer` (default `8`)

### Base cost (before stats)

- `paidOffers = max(0, offersRerolled - freeOffers)`
- `baseCost = paidOffers * costPerOffer`

### Generosity (discount / surcharge)

Your villager’s **Generosity** stat modifies the cost.

- Positive Generosity = cheaper
- Negative Generosity = more expensive

(Exact min/max percent values are server-configurable.)

### Why `freeOffers` exists

With the default `freeOffers=2`, a level-1 villager that has only **2 offers** can be rerolled for **0 cost**.

That’s intentional: it keeps rerolling competitive with “break/replace workstation until you get it”, while still letting servers charge for larger offer lists.

### Examples

**Example A (free rerolls early-game)**

- `offersTotal=2`, `offersLocked=0`, `freeOffers=2`
- `offersRerolled=2`
- `paidOffers=max(0, 2-2)=0`
- `baseCost=0`

**Example B (bigger villager, no locks)**

- `offersTotal=4`, `offersLocked=0`, `freeOffers=2`, `costPerOffer=8`
- `offersRerolled=4`
- `paidOffers=max(0, 4-2)=2`
- `baseCost=16` (then Generosity applies)

!!! tip

    Locking trades is both a safety feature *and* a cost-reduction strategy.

---

## Cooldown + daily cap

Servers can enable two limits:

- **Cooldown**: prevents reroll spam.
- **Daily cap** (per villager): limits how many manual rerolls a villager can do per Minecraft day.

Your villager’s **Timeliness** stat modifies reroll cooldown:

- higher Timeliness = faster cooldown
- lower Timeliness = slower cooldown

---

## “After used” rule

Some servers disable rerolling after the villager has been traded with (vanilla XP gained).

If enabled on the server, you can only reroll “fresh” villagers that haven’t had trades completed yet.

---

## Locking trades

To lock an offer:

- **Right-click the offer row** in the merchant screen.

A locked offer is shown with a **green outline**.

Locked offers:

- are preserved during manual rerolls,
- are preserved during auto-search,
- persist across world saves.

??? note "Technical details (how locks stay reliable)"

    Locked trades are stored as both:

    - a lock mask (which indices are locked), and
    - a snapshot of the exact offer data.

    The snapshot makes locks reliable even if vanilla (or other mods) mutate offers later.
