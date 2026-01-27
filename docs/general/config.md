# Server Config

Server config lives at:

- `config/villageroverhaul-server.toml`

## Hot reload

Server config is hot-loaded (no server restart required).

Notes:

- Changes apply live and are also synced to clients where needed (for UI tooltips and stat deltas).
- If you are editing the file while the server is running, save it normally and the mod will pick it up.

## Module toggles

Servers can disable major modules:

- Merchant
- Combat
- Farming

When a module is disabled, related UI elements and server handlers are gated off.

## Common settings players care about

### Recruiting

- Min/max recruit cost (in emeralds)
- Respawn cost multiplier
- Whether respawned villagers keep inventory/equipment

See: [Recruiting & Ownership](recruiting.md) and [Respawning](respawning.md)

### Merchants

- Manual reroll costs, cooldowns, and free/locked offer rules
- Auto-search behavior and cost scaling

See: [Merchants](../merchants/index.md)

### Guards (combat)

- Combat stat deltas (Vitality/Agility/Strength/Armor) and how strong -100/+100 are

See: [Guards](../guards/index.md)

### Farmers

- Farming XP from planting (server-side)
- Manual farming work window (time-of-day)
- Farming stat deltas (Motivation/Efficiency/Plant Whisperer/Ranger)

See: [Farmers](../farmers/index.md)

### Chat / Custom Commands

- Localized chat (range-limited messages)
- Shout / Whisper (prefix-based variants)
- Custom Commands chat radius (how far villagers can hear you for chat triggers)

See: [Custom Commands](../custom_commands/index.md)

??? tip "Where are the time-of-day ticks?"
    Minecraft day time is 24000 ticks:

    - 0 = sunrise
    - 6000 = noon
    - 12000 = sunset
    - 18000 = midnight
