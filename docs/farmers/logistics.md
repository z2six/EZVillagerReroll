# Logistics (Chests)

Logistics moves items between the villager inventory and one (or two) registered chests.

- **Deposit chest**: villager *puts items in*.
- **Withdraw chest**: villager *takes items out*.

You can register chests from:

- The **Farming commands** row (Deposit/Withdraw buttons), and/or
- The **Farming Settings - Logistics** tab.

---

## Registering chests

When you start registration, you’ll see an on-screen prompt. Then:

- Right-click a chest (normal chest, trapped chest, or ender chest).
- The mod stores that chest position on the villager.

### Limits

- **Same dimension**: villagers can only walk to chests in their current dimension.
- **Inside work area**: deposit/withdraw chests must be inside the villager’s farming area (workstation + range).
- **Workstation required**: without a workstation there is no farming area, so registration is blocked.

---

## Deposit rules

Deposit rules are configured per item.

Each rule has two numbers:

- **Trigger (stacks)**: when the villager has at least this many “stacks worth” of the item, it will deposit.
- **Keep (stacks)**: how many “stacks worth” the villager should keep in its inventory.

### Example

**Deposit: Wheat Seeds**

- Trigger = `2`
- Keep = `1`

Meaning:

- When the villager reaches **2 stacks** of seeds, it deposits.
- It deposits **only the excess**, so it ends with **1 stack** remaining.

---

## Withdraw rules

Withdraw rules are also per item, with the same two fields:

- **Trigger (stacks)**: when the chest contains at least this many stacks worth, the villager will withdraw.
- **Keep (stacks)**: how many stacks worth the chest should keep.

### Example

**Withdraw: Wheat Seeds**

- Trigger = `3`
- Keep = `1`

Meaning:

- When the withdraw chest has **3 stacks** of seeds, the villager will withdraw.
- It withdraws **only the excess**, so the chest ends with **1 stack** remaining.

---

## What does “stacks” mean?

“Stacks” are calculated using the item’s *actual* max stack size.

- For normal items (max stack 64): `1 stack = 64`.
- For items that stack to 16: `1 stack = 16`.
- For unstackable items: `1 stack = 1`.

This means the system works for:

- unstackable tools,
- potions,
- modded items with custom stack sizes.

---

## Timeout + retry

Logistics is designed to not get “stuck”.

- **Timeout (seconds)**: if the villager can’t complete a storage run within this time, it gives up and resumes other behavior.
- **Retry after (seconds)**: after a failure/timeout, it waits this long before trying again.

---

## Chest animation

When the villager reaches the chest:

1. It plays the chest opening animation.
2. It waits ~1 real second.
3. It plays the closing animation.

This is visible to other players.

---

??? note "Technical details"

    - Deposit/withdraw runs only when a rule triggers.
    - Trigger/keep are computed as: `ruleValue * itemMaxStackSize`.
    - Deposit/withdraw also enforces that the chest is inside the villager’s work area.
    - Ender chest access uses the recruiting player’s ender chest inventory (requires that player to be online).
