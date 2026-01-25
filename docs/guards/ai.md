# Combat AI Behavior

This page describes what the Guard AI actually does in a fight.

## High-level behavior

When the villager is in combat (Defend/Aggressive), it:

- Moves into melee range (using a reach calculation).
- Tries to keep a small spacing buffer.
- Swings in timed intervals.
- Blocks with a shield between swings (if enabled and equipped).
- Can strafe/circle around the target (if enabled).
- Can eat food to heal when low HP (if enabled and food available).

## Attacking (melee)

- Attacks are melee.
- The villager uses its real main-hand item when swinging.

Reach:

- If the NeoForge reach attribute exists, it's used.
- Otherwise reach is estimated from entity sizes.

## Blocking (shield)

If Blocking is enabled and the villager has a shield in offhand:

- The AI will try to keep blocking between swings.
- Incoming hits from the front are treated as proper shield blocks (damage and knockback suppressed, shield durability used).

??? details "Advanced: front cone"
    Shield blocks only apply if the attacker is roughly in front of the villager (a ~160 degree front cone).

## Circling / spacing

If Circling is enabled:

- When in range, the villager tries to avoid standing still "face-tanking" at point-blank range.
- It will strafe/circle to keep more natural spacing.

## Eating (auto-heal)

If Eating is enabled:

- When HP drops below a threshold (about 65% HP), the villager can start a "fight while retreating" loop.
- Food is taken from the villager's inventory (must be edible items).

### Out-of-combat passive eating

Even when not currently fighting, villagers can passively eat (for certain non-neutral activities).

This is intended as "maintenance healing" so guards don't stay low forever.

By default, passive eating starts at a higher threshold (about 80% HP).

??? details "Advanced: how eating stays stable"
    Combat can interrupt item-use (swinging, blocking, taking damage). The mod includes logic to keep the eating timer stable so villagers don't get stuck restarting eating over and over.

## AI settings you can tweak

In the AI tab of Combat Settings:

- Blocking: toggles shield usage.
- Eating: toggles auto-heal.
- Circling: toggles strafe/spacing.
- Force hits: after taking this many hits while trying to backpedal, the villager commits to eating.
- Max resets: how many times getting hit is allowed to reset the "start eating" process.

If your villager never seems to eat in combat, try lowering Force hits and/or Max resets.
