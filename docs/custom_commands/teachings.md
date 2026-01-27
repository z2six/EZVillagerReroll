# Teachings (Macros)

“Teachings” are recorded action sequences stored on a villager. A teaching can be triggered by a **chat phrase**.

---

## Teaching a villager

1. Open the villager UI and press **Custom Commands → Teach**.
2. The UI closes and you’ll see an on-screen prompt: “Teach the Villager what to do…”.
3. The villager starts following you while you record.
4. Right-click the villager to open the **Teach menu** and add actions.

!!! tip
    Keep your route simple at first: a few waypoints + one interaction, then expand.

---

## Actions you can record

### Add waypoint

Records the **block you are standing on** (centered).

Use waypoints to:

- define a patrol-like route,
- guide the villager around walls/doors,
- ensure it stands in the right place before interacting.

### Interact

Records a right-click interaction you perform while recording:

- **Interact with block**: buttons, levers, doors, etc.
- **Interact with entity**: (for mods/blocks that use entities, or future expansions)

When you choose **Interact**, you’ll see a prompt to “show the villager what to interact with”. Right-click the target and it is recorded.

??? note "Why block vs entity matters"
    The mod records whether you clicked a **block** or an **entity**, so the villager replays the correct interaction type later.

### Withdraw item

Records “open this chest and take items out of it”.

1. Click **Withdraw item**.
2. Right-click a chest/barrel/trapped chest/ender chest.
3. An item rules screen opens to choose **what items** and **how many** to withdraw.

### Deposit item

Records “open this chest and put items into it”.

1. Click **Deposit item**.
2. Right-click a chest/barrel/trapped chest/ender chest.
3. An item rules screen opens to choose **what items** and **how many** to deposit.

### Wait

Adds a timed pause between actions.

- You can enter a time in seconds (supports decimals like `0.5`).

This is useful to make the teaching feel more natural, or to wait for another redstone component.

---

## Finish teaching (Save)

When you press **Finish teaching**, you get a summary screen where you can set:

- **Title**: friendly name shown in the list.
- **Description**: longer tooltip shown on hover (up to 256 chars).
- **Chat Command**: the phrase to trigger it from chat.
- **Case sensitive**: whether the phrase must match exact casing.
- **Chain**: whether this teaching can be triggered through chain propagation.
- **Timeout / Retry / Stop after retries**:
  - Timeout: how long a step can take before it counts as failed
  - Retry after: delay before trying again
  - Stop after retries: how many failures before giving up

The screen also shows the **Recorded actions** list.

!!! note
    The chat phrase is separate from the title on purpose: title is for humans, phrase is for triggers.

---

## Viewing, editing, and forgetting teachings

Open **Custom Commands → List** to see all teachings on that villager.

- Click a teaching to view its details (steps list + meta settings).
- **Save**: updates title/description/chat phrase + timing settings.
- **Forget**: deletes the teaching.
- **Reteach**: starts teaching again using the existing title/description as defaults and overwrites the teaching.

### Editing steps after saving

Some steps can be edited after saving:

- **Wait**: change the wait duration.
- **Withdraw/Deposit item rules**: change item list and counts.

---

## What the villager does when executing

- Uses a slower, “villager-like” movement speed while executing.
- Uses a “final approach nudge” near waypoints to prevent the classic Minecraft slowdown-stall.
- Adds a short realism delay between non-waypoint steps.
- For chest steps: plays chest open animation + villager arm swing, waits ~1 second, then closes.

??? details "Technical: how failures are handled"
    - Each step has a timeout window.
    - On timeout/failure: the teaching either retries after the configured delay or stops after the configured retry limit.
    - Combat can temporarily interrupt execution; the teaching resumes afterwards.
