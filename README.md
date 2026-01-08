# EZ Villager Reroll

EZ Villager Reroll lets you reroll villager trades on demand, with full server-side control and optional trade locking.

It is designed for modded servers where villager rerolling should be controlled, fair, and configurable.

***

## Features

*   Reroll villager trades via a simple GUI button
*   Lock individual trades so they are excluded from rerolls
*   Rerolling gives Villager EXP
*   Request a specific item from a merchant (takes time, vanilla friendly)
*   Server-authoritative logic (no client-side cheating)
*   Fully configurable costs, cooldowns, and limits
*   Optional integration with Lightman’s Currency
*   Works on NeoForge 1.21.1

***

## How to Use

1.  Open a villager trading screen
2.  Click the reroll button to reroll trades
3.  Right-click a trade to lock or unlock it  
    Locked trades will not change during rerolls
4.  Reroll again to change only unlocked trades
5.  Right-click on the reroll button to ask for one (or more) specific item(s)

***

## Details

### The auto-rerolling (request item) feature:

In the server config there is a "reroll cooldown". If you use the Item Request feature, the server legitimately rerolls that villager's trade using the cooldown config. The villager will be unable to trade during this time and show an outline around its model (if any player is within 6 blocks). Any player can right-click the villager again to view the request and cancel it.

### Auto-reroll cost
Similar as manual reroll cost, but made more expensive for traders with fewer trade offers and made cheaper for traders with a lot of trade offers (to make leveling a Villager make more sense than breeding new ones)

### Trades never reset

Vanilla MC resets trades unless a villager has been traded with. This is counterintuitive to this mod, so we instead store the trades of a villager at the right moments, and ensure we always repopulate the trades with the stored trade offers whenever a player interacts with that villager (more reliable than a mixin).

***

## Configuration (Server-Side)

All configuration options are hot-reloadable and do not require a restart.

Available options include:

*   Reroll cost (item or tag)
*   Cooldowns per villager
*   Daily reroll limits
*   Allow or disallow rerolls after trades were used
*   Prefer wallet or inventory when Lightman’s Currency is installed

***

## Multiplayer and Servers

*   Safe for dedicated servers
*   No client trust required
*   Trade locks are stored directly on the villager
*   Clients automatically stay in sync with the server

***

## Compatibility

*   Minecraft 1.21.1
*   NeoForge
*   Optional: Lightman’s Currency

***

## License

All Rights Reserved unless otherwise stated.  
Do not redistribute without permission.

***

## Issues and Suggestions

Please report bugs or feature requests via the issue tracker or the mod page comments.