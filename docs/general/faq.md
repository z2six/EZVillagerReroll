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

## Can villagers block with shields?

Yes. Put a shield (or any item with block use animation) in the Combat Offhand loadout slot and enable blocking in Combat Settings.

## Can villagers heal themselves?

Yes. If Eating is enabled in Combat Settings and the villager has edible food in its inventory, it can eat to heal (in combat and also passively in some non-neutral activities).

## Why is my villager still dumb?
Some AI behaviors are kept by design. For example: not pathing around trapdoors or not knowing how to compute slightly complex paths. It would be possible to create custom AI for, but as I just mentioned: this is on purpose to still make them feel like a "villager".