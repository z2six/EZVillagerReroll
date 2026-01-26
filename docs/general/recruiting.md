!!! note
The Merchants pages are written at version 3.6B.1. It's possible that there are newer versions available that have made adjustments to these mechanics.

# Recruiting & Ownership

Villager Overhaul uses recruiting to assign ownership. Most mod features are only available to the player who recruited the villager.

## How to recruit a villager

1. Find a villager.
2. Right-click the villager to open the Recruit screen.
3. Pay the emerald cost and confirm.

!!! note
    Recruit cost is based on all of its stats

## What ownership does

Most mod actions are enforced server-side via an access gate:

- Non-owners cannot use your villager's rerolls/settings/commands, even if they try to spoof packets.
- UI buttons may be hidden client-side, but the server check is the real authority.

## Recruit cost (high level)

Recruit cost is computed from the villager's stats and the server's configuration.

- Cost is paid in **emeralds**.
- Server owners can change min/max costs and can disable modules (which also affects which stats count toward recruit cost).

See: [Server Config](config.md)
