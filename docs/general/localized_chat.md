# Localized Chat

Villager Overhaul can optionally replace global chat with **localized chat**, and adds **Shout** + **Whisper**.

This is useful on servers because:

- it improves roleplay,
- it reduces “global spam”,
- and it keeps VillagerOverhaul’s chat-trigger features working reliably.

---

## Normal chat (localized)

When enabled, normal chat is only sent to players within a configured range:

- Range is **3D** (a sphere), not a flat radius.
- Players in other dimensions never hear it.

!!! note
    This feature is server-authoritative and hot-reloaded via `config/villageroverhaul-server.toml`.

---

## Shout

Shout is a server-wide broadcast.

- Use the server-configured **shout prefix** (default `!`)
  - Example: `!Open the gates`
- Costs hunger (server configurable)
- Shows in **orange**

If you don’t have enough hunger, the shout won’t send.

---

## Whisper

Whisper is extremely short-range.

- Use the server-configured **whisper prefix** (default `#`)
  - Example: `#psst`
- Only players within whisper range (3D sphere) see it
- Shows in **gray italics**

---

## Interaction with VillagerOverhaul chat triggers

VillagerOverhaul always strips the shout/whisper prefix before checking:

- Player Chat Commands (mode switching / help / equip / stash)
- Custom Commands teachings (macros)

So:

- `!help` behaves like `help`
- `#Open the gates` behaves like `Open the gates`

The prefix is also **not shown** to other players (they only see the message text).

---

## Server config keys

All of these are in `config/villageroverhaul-server.toml` under `[chat]`:

- `localizedChatEnabled`
- `localizedChatRange`
- `shoutEnabled`
- `shoutPrefix`
- `shoutHungerCost`
- `whisperEnabled`
- `whisperPrefix`
- `whisperRange`

