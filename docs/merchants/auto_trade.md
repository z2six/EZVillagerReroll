# Auto-Trade

Auto-trade repeats a trade automatically to save clicking.

## How to use

On the merchant screen:

- **CTRL + click** a trade that gives **emeralds**.

You’ll get a one-time confirmation screen.

Auto-trade is server-driven (no client-only “fake trades”), so what you see is what actually happened.

## When it stops

Auto-trade will stop automatically if:

- you close the merchant screen,
- the offer changes / becomes unavailable,
- your inventory can’t continue the trades,
- it makes no progress for a short time.

??? note "Technical details"

    Currently, auto-trade is restricted to offers that output emeralds.
