## When are Villager Trades backed up?
_It's important to know that we do "manual saving and restoring" to world data of all trade offers a merchant has._ 

**We do this when:**
- After doing a manual reroll = store
- Right before we start auto-rerolling = store
- When auto rerolling is done, in Settlement screen, if player pays = set new offers & store
- When auto rerolling is done, in Settlement screen, if player refuses to pay = restore to offers from before auto-rerolling

## Villager stats

### Generosity
_Generosity can increase or decrease the cost of rerolling, both Manual as well as Auto-Rerolling. It is applied as a final modifier to the total cost already calculated._

### Timeliness
_Timeliness can increase or decrease the time needed for both Manual as well as Auto-Rerolling. In essence, both are the same, as Auto-Rerolling uses the same logic as Manual Rerolling._

### Intellect
_Intellect can increase or decrease the experience gained from both Manual as well as Auto-Rerolling. It does not affect experience gained from completing a trade with that villager._

### Hoarder
_Hoarder adjusts offer slots by an integer delta derived from points and config bounds (e.g. -10..+10). Final offers are clamped to at least 1, and also never reduced below the number of locked offers._

**We maintain a per-merchant snapshot in persistent data containing:**
- baselineCount (game/modpack baseline)
- appliedDelta (what hoarder delta is currently applied)
- lastSeenCount (+ optional fingerprint)
- guardUntilTick to ignore our own immediate mutations

**We normalize hoarder when:**
- snapshot missing (initialize + normalize)
- after our own operations that change offers (reroll, restore, settlement pay/decline, manual level-up)
- when a player opens the merchant UI (start tracking)
- while a merchant is actively being interacted with (tracked watcher detects external offer changes; debounced)
- while auto-search is active for that merchant (same tracking)

**External changes are detected by “drift”:**
- if currentCount (or fingerprint) differs from what baseline + appliedDelta predicts, we treat it as baseline changed and recompute baseline as currentCount - appliedDelta, then re-apply the desired delta.

## "Catalog" builder

## Auto rerolling flow

## Stats & multipliers

## Recruiting Villagers
_Only Recruited Villagers can use all of our mod's features. Just RMB on a Villager without a profession and Recruit it (for Emeralds). The min/max cost can be configured on the server and the final cost is based on the total value of all stats combined, normalized to the min/max range the server config has._
