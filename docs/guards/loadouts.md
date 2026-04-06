# Loadouts & Equipment

The Guard module uses real equipment (armor/hands), but exposes it to players through safe loadout slots.

## Armor

Armor is normal Minecraft armor:

- Helmet / Chestplate / Leggings / Boots

## Combat loadout (main hand / offhand)

In the Villager Inventory screen you'll see two special slots:

- Combat Main Hand
- Combat Offhand

These slots are not the villager's real hand slots.

Instead:

- The mod stores your chosen items as a loadout.
- When the villager is actively fighting (or patrolling), the loadout is equipped into the villager's real hands.
- When combat ends, the villager's previous hand items are restored.

For ranged combat:

- Put a bow, crossbow, or another projectile weapon that uses Minecraft's normal projectile-weapon API in Combat Main Hand.
- Put valid ammo for that weapon in Combat Offhand.
- Offhand ammo is required. If the offhand is empty or holds the wrong ammo, the villager will not fire.
- The offhand ammo acts like a reusable template: shots use the offhand stack for validation/projectile data, but do not consume it.

This design avoids common issues:

- The player doesn't need to manage the villager's real hands directly.
- The server stays authoritative and swapping is safe.

## When loadouts are equipped

The mod equips the combat loadout automatically when needed, including:

- Combat modes: Flee / Defend / Aggressive
- Movement: Patrol

(Other modules may also temporarily equip loadout items when they need a real tool in-hand.)

## Shield blocking

If you want villagers to block:

- Put a shield (or any item with "block" use animation) in Combat Offhand.

Because ranged combat also uses Combat Offhand for ammo, villagers cannot block and use offhand ammo at the same time. Pick shield or ammo based on the loadout you want.

During combat, the AI will try to block between swings.

!!! info
    Villager shield blocking is implemented server-side (vanilla doesn't properly evaluate villager shield blocks in many situations).
    When blocking, damage is canceled, knockback is suppressed, shield durability is reduced, and a block sound is played.

??? details "Advanced: what counts as a block?"
    - Blocking is offhand-based (the AI uses offhand shields).
    - Attacks are only blocked if they come from roughly in front of the villager (a wide front cone).

## Eating (auto-heal)

Villagers can eat food from their inventory to heal.

- Food is temporarily moved into the villager's real main hand while eating.
- After eating finishes, the previous main hand item is restored.

See: [Combat AI Behavior](ai.md).

## Visuals (hands, holsters, animations)

The client renders villagers with more "player-like" equipment visuals:

- Held items in hands when equipped.
- Holstered loadout items when not currently equipped.
- Arm swing and eat poses for better readability in combat.
