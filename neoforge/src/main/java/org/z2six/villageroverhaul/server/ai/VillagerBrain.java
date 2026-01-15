// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/ai/VillagerBrain.java
package org.z2six.villageroverhaul.server.ai;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.render.VillagerRenderFlags;
import org.z2six.villageroverhaul.server.RecruitService;
import net.minecraft.world.InteractionHand;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class VillagerBrain {

    private VillagerBrain() {}

    // -------------------------
    // Persistent data keys
    // -------------------------
    private static final String TAG_ROOT = "ezvr_brain";
    private static final String K_MODE = "mode";

    // Follow target (existing FOLLOW)
    private static final String K_FOLLOW_PLAYER = "follow_player";

    // Patrol sub-root
    private static final String K_PATROL = "patrol";
    private static final String K_PATROL_OWNER = "owner";
    private static final String K_PATROL_FINALIZED = "finalized";
    private static final String K_PATROL_ROUTE = "route"; // circular/linear
    private static final String K_PATROL_WAYPOINTS = "waypoints"; // ListTag of CompoundTag {x,y,z}
    private static final String K_PATROL_INDEX = "idx";
    private static final String K_PATROL_DIR = "dir"; // +1 / -1
    private static final String K_PATROL_PAUSED = "paused";

    // waypoint tag keys
    private static final String K_WP_X = "x";
    private static final String K_WP_Y = "y";
    private static final String K_WP_Z = "z";

    // ============================================================
    // HAND PERSISTENCE (FIX: prevent AI from "deleting" held items)
    // ============================================================

    private static final String K_HANDS = "hands";
    private static final String K_HAND_MAIN = "main";
    private static final String K_HAND_OFF = "off";

    // Legacy bad fallback keys (from earlier implementation) — we auto-clean them.
    private static final String LEGACY_ID_FALLBACK = "id_fallback";
    private static final String LEGACY_COUNT_FALLBACK = "count_fallback";

    // log-throttle so we don't spam when something keeps clearing it
    private static final Map<UUID, Long> LAST_RESTORE_LOG_GAME_TIME = new HashMap<>();
    private static final Map<UUID, Long> LAST_CLEAN_LOG_GAME_TIME = new HashMap<>();

    // Allow-clears: lets our mixin permit intentional clears (menu/script) for a short window.
    // Stored in ezvr_brain root (NOT inside "hands").
    private static final String K_ALLOW_CLEAR_MAIN_UNTIL = "allow_clear_main_until";
    private static final String K_ALLOW_CLEAR_OFF_UNTIL  = "allow_clear_off_until";

    public enum Mode {
        NEUTRAL("neutral"),
        IDLE("idle"),
        FOLLOW("follow"),
        PATROL_SETUP("patrol_setup"),
        PATROL("patrol");

        public final String id;
        Mode(String id) { this.id = id; }

        public static Mode fromId(String s) {
            if (s == null) return NEUTRAL;
            for (Mode m : values()) if (m.id.equalsIgnoreCase(s)) return m;
            return NEUTRAL;
        }
    }

    public enum PatrolRouteType {
        CIRCULAR("circular"),
        LINEAR("linear");

        public final String id;
        PatrolRouteType(String id) { this.id = id; }

        public static PatrolRouteType fromId(String s) {
            if (s == null) return CIRCULAR;
            for (PatrolRouteType t : values()) if (t.id.equalsIgnoreCase(s)) return t;
            return CIRCULAR;
        }
    }

    // ============================================================
    // Public API (single-line calls from anywhere)
    // ============================================================

    public static boolean idle(Villager vill) {
        if (vill == null) return false;
        if (!isControllable(vill)) return false;

        ensureAttached(vill);

        prepareForManualControl(vill);
        setMode(vill, Mode.IDLE);

        try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
        return true;
    }

    public static boolean neutral(Villager vill) {
        if (vill == null) return false;

        ensureAttached(vill);
        setMode(vill, Mode.NEUTRAL);

        clearFollowPlayer(vill);
        return true;
    }

    public static boolean follow(Villager vill, ServerPlayer player) {
        if (vill == null || player == null) return false;
        if (!isControllable(vill)) return false;

        ensureAttached(vill);
        prepareForManualControl(vill);

        setFollowPlayer(vill, player.getUUID());
        setMode(vill, Mode.FOLLOW);

        return true;
    }

    // ============================================================
    // RENDER DECISIONS (SERVER AUTHORITY)
    // ============================================================

    /**
     * Called once per villager per server tick via VillagerRenderStateMixin.
     */
    public static void tickRenderDecisions(Villager vill) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;

            // Enforce held-item persistence (server authority)
            tickHeldItemPersistence(vill);

            byte flags = VillagerRenderFlags.computeFromEquipment(vill);

            if (vill instanceof VillagerOverhaulRenderAccess acc) {
                byte prev = acc.ezvr$getRenderFlags();
                if (prev != flags) {
                    acc.ezvr$setRenderFlags(flags);

                    // INFO so you see it by default
                    VillagerOverhaul.LOG().info("[VillagerOverhaul] RenderFlags updated (villager={}, {} -> {})",
                            vill.getUUID(), (int) prev, (int) flags);
                }
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] VillagerBrain.tickRenderDecisions failed (soft): {}", t.toString());
        }
    }

    /**
     * Public hook: call this whenever *we* intentionally set/clear a villager's hand item.
     * Also sets a short "allow-clear" window when clearing so our prevention mixin
     * does NOT cancel intentional clears coming from our menu/UI.
     */
    public static void notifyManualHandSet(Entity entity, EquipmentSlot slot, ItemStack newStack, String reason) {
        try {
            if (!(entity instanceof Villager vill)) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (slot != EquipmentSlot.MAINHAND && slot != EquipmentSlot.OFFHAND) return;
            if (!(vill.level() instanceof ServerLevel sl)) return;

            // Determine which hand this slot corresponds to
            InteractionHand hand = (slot == EquipmentSlot.MAINHAND) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;

            // If this is a CLEAR request, allow clears briefly so our mixin doesn't block it.
            if (newStack == null || newStack.isEmpty()) {
                allowClearFor(sl, vill, hand, 5); // 5 ticks is plenty for menu actions
            } else {
                clearAllowClearMarker(vill, hand);
            }

            // Update persistent snapshot
            CompoundTag hands = getOrCreateHands(vill);
            if (slot == EquipmentSlot.MAINHAND) {
                writeStackToHands(sl, hands, K_HAND_MAIN, safeCopy(newStack));
            } else {
                writeStackToHands(sl, hands, K_HAND_OFF, safeCopy(newStack));
            }

            if (reason == null) reason = "unknown";
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] Hand lock updated (villager={}, slot={}, reason={}, empty={})",
                    vill.getUUID(), slot, reason, (newStack == null || newStack.isEmpty()));
        } catch (Throwable ignored) {}
    }

    // ============================================================
// TRUE PREVENTION SUPPORT (used by LivingEntityPreventVillagerHandClearMixin)
// ============================================================

    /**
     * Returns true if we should BLOCK attempts to clear the villager's hand (set EMPTY).
     * The mixin calls this when stack is EMPTY and a villager is being modified.
     */
    public static boolean shouldBlockHandClear(Villager vill, InteractionHand hand) {
        try {
            if (vill == null || hand == null) return false;
            if (vill.level() == null || vill.level().isClientSide()) return false;
            if (!(vill.level() instanceof ServerLevel sl)) return false;

            // If we explicitly allowed a clear (menu/script), do NOT block.
            if (isClearAllowed(sl, vill, hand)) return false;

            // Block only if the villager is currently holding something in that hand.
            ItemStack cur = (hand == InteractionHand.MAIN_HAND) ? vill.getMainHandItem() : vill.getOffhandItem();
            return cur != null && !cur.isEmpty();

        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Checks if a clear is temporarily allowed for this hand. */
    private static boolean isClearAllowed(ServerLevel sl, Villager vill, InteractionHand hand) {
        try {
            if (sl == null || vill == null || hand == null) return false;

            CompoundTag root = getOrCreateRoot(vill);
            long now = sl.getGameTime();

            if (hand == InteractionHand.MAIN_HAND) {
                long until = root.getLong(K_ALLOW_CLEAR_MAIN_UNTIL);
                return until > now;
            } else {
                long until = root.getLong(K_ALLOW_CLEAR_OFF_UNTIL);
                return until > now;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Allows a clear for N ticks so our mixin won't cancel intentional menu removals, etc. */
    private static void allowClearFor(ServerLevel sl, Villager vill, InteractionHand hand, int ticks) {
        try {
            if (sl == null || vill == null || hand == null) return;

            CompoundTag root = getOrCreateRoot(vill);
            long until = sl.getGameTime() + Math.max(1, ticks);

            if (hand == InteractionHand.MAIN_HAND) {
                root.putLong(K_ALLOW_CLEAR_MAIN_UNTIL, until);
            } else {
                root.putLong(K_ALLOW_CLEAR_OFF_UNTIL, until);
            }
        } catch (Throwable ignored) {}
    }

    /** Clears the allow-clear marker when we set a non-empty item. */
    private static void clearAllowClearMarker(Villager vill, InteractionHand hand) {
        try {
            if (vill == null || hand == null) return;

            CompoundTag root = getOrCreateRoot(vill);
            if (hand == InteractionHand.MAIN_HAND) root.remove(K_ALLOW_CLEAR_MAIN_UNTIL);
            else root.remove(K_ALLOW_CLEAR_OFF_UNTIL);
        } catch (Throwable ignored) {}
    }

    /**
     * Core fix: keep main/offhand from being "cleared" by villager AI.
     */
    private static void tickHeldItemPersistence(Villager vill) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (!(vill.level() instanceof ServerLevel sl)) return;

            CompoundTag hands = getOrCreateHands(vill);

            // Current
            ItemStack curMain = safeCopy(vill.getMainHandItem());
            ItemStack curOff  = safeCopy(vill.getOffhandItem());

            // Desired (snapshot)
            ItemStack wantMain = readStackFromHands(sl, hands, K_HAND_MAIN, K_HAND_MAIN);
            ItemStack wantOff  = readStackFromHands(sl, hands, K_HAND_OFF, K_HAND_OFF);

            // MAINHAND
            wantMain = syncOneHand(sl, vill, hands, K_HAND_MAIN, EquipmentSlot.MAINHAND, curMain, wantMain);

            // OFFHAND
            wantOff = syncOneHand(sl, vill, hands, K_HAND_OFF, EquipmentSlot.OFFHAND, curOff, wantOff);

            // If both are empty, keep the compound lightweight
            if (!hands.contains(K_HAND_MAIN) && !hands.contains(K_HAND_OFF)) {
                CompoundTag root = getOrCreateRoot(vill);
                root.remove(K_HANDS);
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] tickHeldItemPersistence failed (soft): {}", t.toString());
        }
    }

    private static ItemStack syncOneHand(ServerLevel sl,
                                         Villager vill,
                                         CompoundTag hands,
                                         String key,
                                         EquipmentSlot slot,
                                         ItemStack cur,
                                         ItemStack want) {
        try {
            if (vill == null || sl == null || slot == null || key == null) return want;

            // If we haven't tracked anything yet, and something is in hand, start tracking it.
            if ((want == null || want.isEmpty()) && (cur != null && !cur.isEmpty())) {
                writeStackToHands(sl, hands, key, safeCopy(cur));
                return safeCopy(cur);
            }

            // If we *think* something should be in hand, but it got cleared, restore it.
            if (want != null && !want.isEmpty() && (cur == null || cur.isEmpty())) {
                // Try to pull it from villager pickup inventory first to avoid duplication.
                ItemStack recovered = tryTakeFromVillagerPickupInventory(sl, vill, want);

                if (recovered == null || recovered.isEmpty()) {
                    recovered = safeCopy(want);
                } else {
                    // If we recovered a partial stack, update want to match recovered (conservation).
                    want = safeCopy(recovered);
                }

                // Restore to hand
                try {
                    if (slot == EquipmentSlot.MAINHAND) {
                        vill.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, recovered);
                    } else {
                        vill.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, recovered);
                    }
                } catch (Throwable ignoredSet) {}

                // Persist snapshot to whatever we actually put back
                writeStackToHands(sl, hands, key, safeCopy(recovered));

                throttledRestoreLog(sl, vill, slot, recovered);
                return safeCopy(recovered);
            }

            // If the held item changed (legit), update snapshot.
            if (cur != null && !cur.isEmpty()) {
                if (want == null || want.isEmpty() || !stacksExactlyEqual(want, cur)) {
                    writeStackToHands(sl, hands, key, safeCopy(cur));
                    return safeCopy(cur);
                }
            }

            // If both empty, snapshot can be cleared (but ONLY if someone did it intentionally via notifyManualHandSet)
            // We do not auto-clear here; leaving want as-is prevents accidental loss.
            return want;

        } catch (Throwable ignored) {
            return want;
        }
    }

    private static void throttledRestoreLog(ServerLevel sl, Villager vill, EquipmentSlot slot, ItemStack restored) {
        try {
            if (sl == null || vill == null || slot == null) return;

            long now = sl.getGameTime();
            UUID id = vill.getUUID();

            Long last = LAST_RESTORE_LOG_GAME_TIME.get(id);
            if (last != null && (now - last) < 40L) return; // once per ~2s per villager

            LAST_RESTORE_LOG_GAME_TIME.put(id, now);

            VillagerOverhaul.LOG().info("[VillagerOverhaul] Prevented AI hand clear: restored {}x {} to {} (villager={})",
                    (restored == null ? 0 : restored.getCount()),
                    (restored == null || restored.isEmpty()) ? "EMPTY" : restored.getItem().toString(),
                    slot,
                    vill.getUUID());

        } catch (Throwable ignored) {}
    }

    private static void throttledCleanLog(ServerLevel sl, Villager vill, String whichKey) {
        try {
            if (sl == null || vill == null) return;

            long now = sl.getGameTime();
            UUID id = vill.getUUID();

            Long last = LAST_CLEAN_LOG_GAME_TIME.get(id);
            if (last != null && (now - last) < 100L) return; // once per ~5s per villager

            LAST_CLEAN_LOG_GAME_TIME.put(id, now);

            VillagerOverhaul.LOG().info("[VillagerOverhaul] Cleaned legacy invalid hand snapshot '{}' (villager={})",
                    whichKey, vill.getUUID());
        } catch (Throwable ignored) {}
    }

    private static ItemStack tryTakeFromVillagerPickupInventory(ServerLevel sl, Villager vill, ItemStack desired) {
        try {
            if (vill == null || desired == null || desired.isEmpty()) return ItemStack.EMPTY;

            Container inv = tryGetVillagerPickupInventory(vill);
            if (inv == null) return ItemStack.EMPTY;

            int need = Math.max(1, desired.getCount());

            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack inSlot = inv.getItem(i);
                if (inSlot == null || inSlot.isEmpty()) continue;

                if (!stacksSameType(desired, inSlot)) continue;

                int take = Math.min(need, inSlot.getCount());
                ItemStack removed = inv.removeItem(i, take);

                try { inv.setChanged(); } catch (Throwable ignored) {}

                if (removed != null && !removed.isEmpty()) return removed;
            }

            return ItemStack.EMPTY;

        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    private static boolean stacksSameType(ItemStack a, ItemStack b) {
        try {
            if (a == null || b == null) return false;
            if (a.isEmpty() || b.isEmpty()) return false;

            // ignore count but include components
            ItemStack ac = a.copy();
            ItemStack bc = b.copy();
            try { ac.setCount(1); } catch (Throwable ignored) {}
            try { bc.setCount(1); } catch (Throwable ignored) {}

            return ItemStack.isSameItemSameComponents(ac, bc);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean stacksExactlyEqual(ItemStack a, ItemStack b) {
        try {
            if (a == b) return true;
            if (a == null || b == null) return false;
            if (a.isEmpty() && b.isEmpty()) return true;
            if (a.isEmpty() || b.isEmpty()) return false;
            if (a.getCount() != b.getCount()) return false;
            return ItemStack.isSameItemSameComponents(a, b);
        } catch (Throwable t) {
            return false;
        }
    }

    private static ItemStack safeCopy(ItemStack st) {
        try {
            if (st == null || st.isEmpty()) return ItemStack.EMPTY;
            return st.copy();
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    private static CompoundTag getOrCreateHands(Villager vill) {
        CompoundTag root = getOrCreateRoot(vill);
        if (!root.contains(K_HANDS, Tag.TAG_COMPOUND)) {
            root.put(K_HANDS, new CompoundTag());
        }
        return root.getCompound(K_HANDS);
    }

    /**
     * Reads a stored snapshot from hands.
     * - Uses ItemStack.parse(registryAccess, Tag) (1.21+ correct way)
     * - Detects & removes legacy invalid fallback tags to stop Minecraft error spam
     */
    private static ItemStack readStackFromHands(ServerLevel sl, CompoundTag hands, String key, String whichKeyForLog) {
        try {
            if (sl == null || hands == null || key == null) return ItemStack.EMPTY;
            if (!hands.contains(key)) return ItemStack.EMPTY;

            Tag raw = hands.get(key);
            if (raw == null) {
                hands.remove(key);
                return ItemStack.EMPTY;
            }

            // Legacy cleanup: if the stored tag is a CompoundTag with our old fallback keys, remove it.
            if (raw instanceof CompoundTag ct) {
                if (ct.contains(LEGACY_ID_FALLBACK) || ct.contains(LEGACY_COUNT_FALLBACK)) {
                    hands.remove(key);
                    throttledCleanLog(sl, nullSafeVillagerFromHandsOwner(sl, hands), whichKeyForLog);
                    return ItemStack.EMPTY;
                }
            }

            Optional<ItemStack> parsed;
            try {
                parsed = ItemStack.parse(sl.registryAccess(), raw);
            } catch (Throwable parseFail) {
                // If Minecraft can't parse it, drop it so we don't re-trigger internal error logs forever.
                hands.remove(key);
                return ItemStack.EMPTY;
            }

            if (parsed == null || parsed.isEmpty()) {
                hands.remove(key);
                return ItemStack.EMPTY;
            }

            ItemStack st = parsed.get();
            return (st == null) ? ItemStack.EMPTY : st;

        } catch (Throwable ignored) {
            try { if (hands != null && key != null) hands.remove(key); } catch (Throwable ignored2) {}
            return ItemStack.EMPTY;
        }
    }

    // We can't reliably know the villager from just the hands tag, so this is best-effort and safe.
    private static Villager nullSafeVillagerFromHandsOwner(ServerLevel sl, CompoundTag hands) {
        return null;
    }

    /**
     * Stores snapshot using ItemStack.save(registryAccess) -> Tag (1.21+ correct).
     * No fallback format, because it causes parse spam and cannot be restored.
     */
    private static void writeStackToHands(ServerLevel sl, CompoundTag hands, String key, ItemStack stack) {
        try {
            if (sl == null || hands == null || key == null) return;

            if (stack == null || stack.isEmpty()) {
                hands.remove(key);
                return;
            }

            Tag encoded;
            try {
                encoded = stack.save(sl.registryAccess());
            } catch (Throwable t) {
                // If encoding fails, do NOT store junk — just remove snapshot.
                hands.remove(key);
                VillagerOverhaul.LOG().error("[VillagerOverhaul] Failed to encode hand snapshot (key={}): {}", key, t.toString());
                return;
            }

            if (encoded == null) {
                hands.remove(key);
                return;
            }

            hands.put(key, encoded);

        } catch (Throwable ignored) {}
    }

    private static Container tryGetVillagerPickupInventory(Villager vill) {
        try {
            if (vill == null) return null;

            Method m = null;
            Class<?> c = vill.getClass();
            while (c != null && c != Object.class) {
                for (Method mm : c.getDeclaredMethods()) {
                    if (mm == null) continue;
                    if (!"getInventory".equals(mm.getName())) continue;
                    if (mm.getParameterCount() != 0) continue;
                    mm.setAccessible(true);
                    m = mm;
                    break;
                }
                if (m != null) break;
                c = c.getSuperclass();
            }
            if (m == null) return null;

            Object out = m.invoke(vill);
            if (out instanceof Container cont) return cont;

            return null;

        } catch (Throwable ignored) {
            return null;
        }
    }

    // ============================================================
    // PATROL API (unchanged)
    // ============================================================

    public static boolean isPatrolPaused(Villager vill) {
        try {
            if (vill == null) return false;

            try {
                if (vill.getTradingPlayer() != null) return true;
            } catch (Throwable ignored) {}

            CompoundTag patrol = getOrCreatePatrol(vill);
            return patrol.getBoolean(K_PATROL_PAUSED);

        } catch (Throwable t) {
            return false;
        }
    }

    public static void setPatrolPaused(Villager vill, boolean paused) {
        try {
            if (vill == null) return;
            CompoundTag patrol = getOrCreatePatrol(vill);
            patrol.putBoolean(K_PATROL_PAUSED, paused);

            if (paused) {
                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    public static boolean beginPatrolSetup(Villager vill, ServerPlayer owner, boolean reset) {
        try {
            if (vill == null || owner == null) return false;
            if (!isControllable(vill)) return false;

            ensureAttached(vill);
            prepareForManualControl(vill);

            CompoundTag patrol = getOrCreatePatrol(vill);
            if (reset) {
                patrol.remove(K_PATROL_WAYPOINTS);
                patrol.putBoolean(K_PATROL_FINALIZED, false);
                patrol.putString(K_PATROL_ROUTE, PatrolRouteType.CIRCULAR.id);
                patrol.putInt(K_PATROL_INDEX, 0);
                patrol.putInt(K_PATROL_DIR, 1);
            }

            patrol.putUUID(K_PATROL_OWNER, owner.getUUID());
            patrol.putBoolean(K_PATROL_FINALIZED, false);

            setMode(vill, Mode.PATROL_SETUP);

            return true;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] beginPatrolSetup failed", t);
            return false;
        }
    }

    public static boolean startPatrolExisting(Villager vill) {
        try {
            if (vill == null) return false;
            if (!isControllable(vill)) return false;

            ensureAttached(vill);
            prepareForManualControl(vill);

            if (!hasFinalizedPatrol(vill) || getPatrolWaypointCount(vill) < 2) {
                setMode(vill, Mode.NEUTRAL);
                return false;
            }

            setPatrolIndex(vill, 0);
            setPatrolDir(vill, 1);

            setMode(vill, Mode.PATROL);
            return true;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] startPatrolExisting failed", t);
            return false;
        }
    }

    public static void addPatrolWaypointAtCurrentPos(Villager vill) {
        try {
            if (vill == null) return;
            if (getMode(vill) != Mode.PATROL_SETUP) return;

            addWaypointInternal(vill, vill.position());
        } catch (Throwable ignored) {}
    }

    public static void markPatrolFinalized(Villager vill) {
        try {
            if (vill == null) return;
            CompoundTag patrol = getOrCreatePatrol(vill);
            patrol.putBoolean(K_PATROL_FINALIZED, true);
        } catch (Throwable ignored) {}
    }

    public static void setPatrolRouteTypeAndStart(Villager vill, org.z2six.villageroverhaul.network.PacketPatrolSetRouteType.RouteType type) {
        try {
            if (vill == null || type == null) return;

            CompoundTag patrol = getOrCreatePatrol(vill);

            PatrolRouteType rt = (type == org.z2six.villageroverhaul.network.PacketPatrolSetRouteType.RouteType.LINEAR)
                    ? PatrolRouteType.LINEAR
                    : PatrolRouteType.CIRCULAR;

            patrol.putString(K_PATROL_ROUTE, rt.id);
            patrol.putBoolean(K_PATROL_FINALIZED, true);

            if (getPatrolWaypointCount(vill) < 2) {
                setMode(vill, Mode.NEUTRAL);
                return;
            }

            patrol.putInt(K_PATROL_INDEX, 0);
            patrol.putInt(K_PATROL_DIR, 1);

            prepareForManualControl(vill);
            setMode(vill, Mode.PATROL);

        } catch (Throwable ignored) {}
    }

    public static void cancelAndClearPatrol(Villager vill) {
        try {
            if (vill == null) return;

            CompoundTag root = getOrCreateRoot(vill);
            root.remove(K_PATROL);

            setMode(vill, Mode.NEUTRAL);
            clearFollowPlayer(vill);

        } catch (Throwable ignored) {}
    }

    public static boolean hasAnyPatrolData(Villager vill) {
        try {
            if (vill == null) return false;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_PATROL, Tag.TAG_COMPOUND)) return false;
            CompoundTag patrol = root.getCompound(K_PATROL);
            return patrol.contains(K_PATROL_WAYPOINTS, Tag.TAG_LIST) && patrol.getList(K_PATROL_WAYPOINTS, Tag.TAG_COMPOUND).size() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasFinalizedPatrol(Villager vill) {
        try {
            if (vill == null) return false;
            CompoundTag patrol = getOrCreatePatrol(vill);
            return patrol.getBoolean(K_PATROL_FINALIZED) && getPatrolWaypointCount(vill) >= 2;
        } catch (Throwable t) {
            return false;
        }
    }

    public static UUID getPatrolSetupOwner(Villager vill) {
        try {
            if (vill == null) return null;
            CompoundTag patrol = getOrCreatePatrol(vill);
            if (!patrol.hasUUID(K_PATROL_OWNER)) return null;
            return patrol.getUUID(K_PATROL_OWNER);
        } catch (Throwable t) {
            return null;
        }
    }

    public static int getPatrolWaypointCount(Villager vill) {
        try {
            if (vill == null) return 0;
            CompoundTag patrol = getOrCreatePatrol(vill);
            ListTag list = patrol.getList(K_PATROL_WAYPOINTS, Tag.TAG_COMPOUND);
            return list == null ? 0 : list.size();
        } catch (Throwable t) {
            return 0;
        }
    }

    public static List<Vec3> getPatrolWaypoints(Villager vill) {
        try {
            if (vill == null) return List.of();
            CompoundTag patrol = getOrCreatePatrol(vill);
            ListTag list = patrol.getList(K_PATROL_WAYPOINTS, Tag.TAG_COMPOUND);
            if (list == null || list.isEmpty()) return List.of();

            List<Vec3> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                Tag tag = list.get(i);
                if (!(tag instanceof CompoundTag ct)) continue;
                double x = ct.getDouble(K_WP_X);
                double y = ct.getDouble(K_WP_Y);
                double z = ct.getDouble(K_WP_Z);
                out.add(new Vec3(x, y, z));
            }
            return out;

        } catch (Throwable t) {
            return List.of();
        }
    }

    public static PatrolRouteType getPatrolRouteType(Villager vill) {
        try {
            if (vill == null) return PatrolRouteType.CIRCULAR;
            CompoundTag patrol = getOrCreatePatrol(vill);
            return PatrolRouteType.fromId(patrol.getString(K_PATROL_ROUTE));
        } catch (Throwable t) {
            return PatrolRouteType.CIRCULAR;
        }
    }

    public static int getPatrolIndex(Villager vill) {
        try {
            if (vill == null) return 0;
            CompoundTag patrol = getOrCreatePatrol(vill);
            return patrol.getInt(K_PATROL_INDEX);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void setPatrolIndex(Villager vill, int idx) {
        try {
            if (vill == null) return;
            CompoundTag patrol = getOrCreatePatrol(vill);
            patrol.putInt(K_PATROL_INDEX, Math.max(0, idx));
        } catch (Throwable ignored) {}
    }

    public static int getPatrolDir(Villager vill) {
        try {
            if (vill == null) return 1;
            CompoundTag patrol = getOrCreatePatrol(vill);
            int d = patrol.getInt(K_PATROL_DIR);
            if (d == 0) d = 1;
            return d;
        } catch (Throwable t) {
            return 1;
        }
    }

    public static void setPatrolDir(Villager vill, int dir) {
        try {
            if (vill == null) return;
            CompoundTag patrol = getOrCreatePatrol(vill);
            patrol.putInt(K_PATROL_DIR, dir >= 0 ? 1 : -1);
        } catch (Throwable ignored) {}
    }

    private static void addWaypointInternal(Villager vill, Vec3 pos) {
        try {
            if (vill == null || pos == null) return;

            double sx = Math.floor(pos.x) + 0.5;
            double sy = Math.floor(pos.y);
            double sz = Math.floor(pos.z) + 0.5;

            CompoundTag patrol = getOrCreatePatrol(vill);

            ListTag list;
            if (patrol.contains(K_PATROL_WAYPOINTS, Tag.TAG_LIST)) {
                list = patrol.getList(K_PATROL_WAYPOINTS, Tag.TAG_COMPOUND);
            } else {
                list = new ListTag();
                patrol.put(K_PATROL_WAYPOINTS, list);
            }

            CompoundTag wp = new CompoundTag();
            wp.putDouble(K_WP_X, sx);
            wp.putDouble(K_WP_Y, sy);
            wp.putDouble(K_WP_Z, sz);

            list.add(wp);

        } catch (Throwable ignored) {}
    }

    public static void addPatrolWaypointFromClientPos(Villager vill, Vec3 clientPos) {
        try {
            if (vill == null || clientPos == null) return;
            if (getMode(vill) != Mode.PATROL_SETUP) return;

            Vec3 serverPos = vill.position();
            double dx = clientPos.x - serverPos.x;
            double dy = clientPos.y - serverPos.y;
            double dz = clientPos.z - serverPos.z;

            double dist2 = dx * dx + dy * dy + dz * dz;

            Vec3 finalPos = (dist2 <= 16.0) ? clientPos : serverPos;

            addWaypointInternal(vill, finalPos);
        } catch (Throwable ignored) {}
    }

    // ============================================================
    // Mode getters
    // ============================================================

    public static Mode getMode(Villager vill) {
        try {
            if (vill == null) return Mode.NEUTRAL;
            CompoundTag root = getOrCreateRoot(vill);
            return Mode.fromId(root.getString(K_MODE));
        } catch (Throwable t) {
            return Mode.NEUTRAL;
        }
    }

    public static void setMode(Villager vill, Mode mode) {
        try {
            if (vill == null || mode == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            root.putString(K_MODE, mode.id);
        } catch (Throwable ignored) {}
    }

    // ============================================================
    // Follow target getters
    // ============================================================

    public static UUID getFollowPlayer(Villager vill) {
        try {
            if (vill == null) return null;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.hasUUID(K_FOLLOW_PLAYER)) return null;
            return root.getUUID(K_FOLLOW_PLAYER);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setFollowPlayer(Villager vill, UUID playerUuid) {
        try {
            if (vill == null || playerUuid == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            root.putUUID(K_FOLLOW_PLAYER, playerUuid);
        } catch (Throwable ignored) {}
    }

    private static void clearFollowPlayer(Villager vill) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            root.remove(K_FOLLOW_PLAYER);
        } catch (Throwable ignored) {}
    }

    // ============================================================
    // Attach modules (goals) once
    // ============================================================

    public static void onEntityJoinLevel(EntityJoinLevelEvent e) {
        try {
            if (e == null) return;
            if (!(e.getEntity() instanceof Villager vill)) return;
            if (vill.level().isClientSide()) return;

            ensureAttached(vill);
        } catch (Throwable ignored) {}
    }

    public static void ensureAttached(Villager vill) {
        try {
            if (vill == null) return;
            if (vill.level().isClientSide()) return;

            if (!hasGoal(vill, VillagerIdleGoal.class)) {
                vill.goalSelector.addGoal(0, new VillagerIdleGoal(vill));
                VillagerOverhaul.LOG().info("[VillagerOverhaul] Attached VillagerIdleGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerPatrolSetupFollowGoal.class)) {
                vill.goalSelector.addGoal(1, new VillagerPatrolSetupFollowGoal(vill));
                VillagerOverhaul.LOG().info("[VillagerOverhaul] Attached VillagerPatrolSetupFollowGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerFollowGoal.class)) {
                vill.goalSelector.addGoal(2, new VillagerFollowGoal(vill));
                VillagerOverhaul.LOG().info("[VillagerOverhaul] Attached VillagerFollowGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerPatrolGoal.class)) {
                vill.goalSelector.addGoal(3, new VillagerPatrolGoal(vill));
                VillagerOverhaul.LOG().info("[VillagerOverhaul] Attached VillagerPatrolGoal (villager={})", vill.getUUID());
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] VillagerBrain.ensureAttached failed (soft): {}", t.toString());
        }
    }

    private static boolean hasGoal(Villager vill, Class<?> goalClazz) {
        try {
            if (vill == null || goalClazz == null) return false;

            var selector = vill.goalSelector;

            for (var f : selector.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(selector);

                if (!(v instanceof Iterable<?> it)) continue;

                for (Object wrapped : it) {
                    if (wrapped == null) continue;

                    for (var wf : wrapped.getClass().getDeclaredFields()) {
                        wf.setAccessible(true);
                        Object g = wf.get(wrapped);
                        if (g != null && goalClazz.isInstance(g)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        return false;
    }

    public static boolean shouldTickVanillaBrain(Villager vill) {
        try {
            if (vill == null) return true;
            if (!RecruitService.isRecruited(vill)) return true;
            return getMode(vill) == Mode.NEUTRAL;
        } catch (Throwable t) {
            return true;
        }
    }

    // ------------------------------------------------------------
    // Manual-control prep
    // ------------------------------------------------------------

    private static final Set<MemoryModuleType<?>> KEEP_MEMORIES = buildKeepMemories();

    private static Set<MemoryModuleType<?>> buildKeepMemories() {
        Set<MemoryModuleType<?>> keep = new HashSet<>();
        try { keep.add(MemoryModuleType.HOME); } catch (Throwable ignored) {}
        try { keep.add(MemoryModuleType.JOB_SITE); } catch (Throwable ignored) {}
        try { keep.add(MemoryModuleType.POTENTIAL_JOB_SITE); } catch (Throwable ignored) {}
        try { keep.add(MemoryModuleType.MEETING_POINT); } catch (Throwable ignored) {}
        return keep;
    }

    private static void prepareForManualControl(Villager vill) {
        if (vill == null) return;

        tryClearTradingPlayer(vill);

        try { vill.getNavigation().stop(); } catch (Throwable ignored) {}

        wipeBrainMemoriesExcept(vill, KEEP_MEMORIES);
    }

    private static void tryClearTradingPlayer(Villager vill) {
        try {
            vill.setTradingPlayer(null);
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static void wipeBrainMemoriesExcept(Villager vill, Set<MemoryModuleType<?>> keep) {
        try {
            Brain<?> brain = vill.getBrain();
            if (brain == null) return;

            Map<MemoryModuleType<?>, ?> memories = null;

            for (var f : brain.getClass().getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(brain);
                    if (!(v instanceof Map<?, ?> m)) continue;

                    Object anyKey = m.keySet().stream().findFirst().orElse(null);
                    if (anyKey instanceof MemoryModuleType<?>) {
                        memories = (Map<MemoryModuleType<?>, ?>) m;
                        break;
                    }
                } catch (Throwable ignoredField) {}
            }

            if (memories == null || memories.isEmpty()) return;

            List<MemoryModuleType<?>> keys = new ArrayList<>(memories.keySet());

            for (MemoryModuleType<?> k : keys) {
                if (k == null) continue;
                if (keep != null && keep.contains(k)) continue;

                try {
                    brain.eraseMemory((MemoryModuleType<Object>) k);
                } catch (Throwable ignoredErase) {}
            }

        } catch (Throwable ignored) {}
    }

    // ============================================================
    // Internals
    // ============================================================

    private static boolean isControllable(Villager vill) {
        try {
            return RecruitService.isRecruited(vill);
        } catch (Throwable t) {
            return false;
        }
    }

    private static CompoundTag getOrCreateRoot(Villager vill) {
        CompoundTag pd = vill.getPersistentData();
        if (!pd.contains(TAG_ROOT, Tag.TAG_COMPOUND)) {
            CompoundTag root = new CompoundTag();
            root.putString(K_MODE, Mode.NEUTRAL.id);
            pd.put(TAG_ROOT, root);
        }
        return pd.getCompound(TAG_ROOT);
    }

    private static CompoundTag getOrCreatePatrol(Villager vill) {
        CompoundTag root = getOrCreateRoot(vill);
        if (!root.contains(K_PATROL, Tag.TAG_COMPOUND)) {
            CompoundTag patrol = new CompoundTag();
            patrol.putBoolean(K_PATROL_FINALIZED, false);
            patrol.putString(K_PATROL_ROUTE, PatrolRouteType.CIRCULAR.id);
            patrol.putInt(K_PATROL_INDEX, 0);
            patrol.putInt(K_PATROL_DIR, 1);
            root.put(K_PATROL, patrol);
        }
        return root.getCompound(K_PATROL);
    }

    private static Villager findVillager(MinecraftServer server, UUID uuid) {
        try {
            if (server == null || uuid == null) return null;

            for (ServerLevel lvl : server.getAllLevels()) {
                Entity e = lvl.getEntity(uuid);
                if (e instanceof Villager v) return v;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
}
