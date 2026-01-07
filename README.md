# EZ Villager Reroll

EZ Villager Reroll lets you reroll villager trades on demand, with full server-side control and optional trade locking.

It is designed for modded servers where villager rerolling should be controlled, fair, and configurable.

---

## Features

- Reroll villager trades via a simple GUI button
- Lock individual trades so they are excluded from rerolls
- Server-authoritative logic (no client-side cheating)
- Fully configurable costs, cooldowns, and limits
- Disables daily reset of trades
- Optional integration with Lightman’s Currency

---

## How to Use

1. Open a villager trading screen
2. Click the reroll button to reroll trades
3. Right-click a trade to lock or unlock it  
   Locked trades will not change during rerolls
4. Reroll again to change only unlocked trades

---

## Configuration (Server-Side)

All configuration options are hot-reloadable and do not require a restart.

Available options include:
- Reroll cost (item or tag)
- Cooldowns per villager
- Daily reroll limits
- Allow or disallow rerolls after trades were used
- Prefer wallet or inventory when Lightman’s Currency is installed

---

## Multiplayer and Servers

- Safe for dedicated servers
- No client trust required
- Trade locks are stored directly on the villager
- Clients automatically stay in sync with the server

---

## Compatibility

- Minecraft 1.21.1
- NeoForge
- Optional: Lightman’s Currency

---

## License

All Rights Reserved unless otherwise stated.  
Do not redistribute without permission.

---

## Issues and Suggestions

Please report bugs or feature requests via the issue tracker or the mod page comments.
