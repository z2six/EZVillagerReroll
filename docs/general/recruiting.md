# Recruiting & Ownership

Villager Overhaul uses recruiting to assign ownership. Most mod features are only available to the player who recruited the villager.

## How to recruit a villager

1. Find an **unemployed** villager (profession = none).
2. Right-click the villager to open the Recruit screen.
3. Pay the emerald cost and confirm.

!!! important
    Recruiting is only available for **unemployed** villagers. If the villager already has a profession (Farmer, Librarian, etc.), the recruit screen will not open.

## What ownership does

Most mod actions are enforced server-side via an access gate:

- Non-owners cannot use your villager's rerolls/settings/commands, even if they try to spoof packets.
- UI buttons may be hidden client-side, but the server check is the real authority.

## Recruit cost (high level)

Recruit cost is computed from the villager's stats and the server's configuration.

- Cost is paid in **emeralds**.
- Server owners can change min/max costs and can disable modules (which also affects which stats count toward recruit cost).

See: [Server Config](config.md)
