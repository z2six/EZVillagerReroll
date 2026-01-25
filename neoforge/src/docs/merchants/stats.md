# Merchant Stats

Villagers spawn with Merchant stats (range `-100` to `+100`) that influence trade behavior.

## How stats work

- A stat is stored per villager.
- Server config defines the effect at `-100` and `+100` (a “min/max delta”).
- The game interpolates between these values based on the villager’s stat points.

See **General → Server Config** for how to tune stat clamps.

