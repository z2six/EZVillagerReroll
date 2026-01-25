# FAQ

## Does the mod support modded items/crops?

Yes. Farmer logic is designed around configured rules, and harvesting uses player-like logic to support modded tools where possible.

## Why can’t my friend use my villager controls?

Villagers are owned by the player who recruited them. Server-side access checks prevent non-owners from using controls.

## Why don't I see any mod UI / buttons?

Common reasons:

- The villager is **not recruited** yet.
- You are not the **owner** (someone else recruited it).
- The server has disabled the relevant module (Merchant/Combat/Farming) in server config.

## Why can't I recruit this villager?

Recruiting only opens for **unemployed** villagers (profession = none) and not for babies.

## Can villagers block with shields?

Yes. Put a shield (or any item with block use animation) in the Combat Offhand loadout slot and enable blocking in Combat Settings.

## Can villagers heal themselves?

Yes. If Eating is enabled in Combat Settings and the villager has edible food in its inventory, it can eat to heal (in combat and also passively in some non-neutral activities).
