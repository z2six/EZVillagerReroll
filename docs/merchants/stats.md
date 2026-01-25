# Merchant Stats

Each villager spawns with 4 Merchant stats (range **-100 to +100**).

Server owners control how strong these stats are via `config/villageroverhaul-server.toml`.

---

## Generosity

Generosity affects *emerald economics*:

- Reroll costs (manual + auto-search)
- Emerald prices on trade offers (discount/surcharge applied cleanly without compounding)

Higher Generosity usually means cheaper rerolls and cheaper emerald costs on offers.

---

## Timeliness

Timeliness affects how fast rerolls recharge:

- manual reroll cooldown
- auto-search reroll cooldown

Higher Timeliness usually means shorter cooldowns.

---

## Intellect

Intellect affects villager XP gained from rerolls (when enabled on the server).

Higher Intellect usually means faster leveling from reroll activity.

---

## Hoarder

Hoarder affects how many offers the villager has available.

- Higher Hoarder can add extra offers.
- Lower Hoarder can reduce offers.

Locked offers are protected and won’t be removed.

??? note "Technical details"

    Hoarder uses a “baseline + delta” model with drift correction so that if vanilla or other mods change the offer list,
    the villager adapts without permanently inflating/deflating offers.
