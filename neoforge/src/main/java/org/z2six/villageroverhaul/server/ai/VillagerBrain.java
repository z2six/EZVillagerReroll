// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/ai/VillagerBrain.java
package org.z2six.villageroverhaul.server.ai;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.z2six.villageroverhaul.Constants;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.api.VillagerOverhaulSwingAccess;
import org.z2six.villageroverhaul.network.patrol.PacketPatrolSetRouteType;
import org.z2six.villageroverhaul.render.VillagerRenderFlags;
import org.z2six.villageroverhaul.server.FarmingSettingsService;
import org.z2six.villageroverhaul.server.RecruitService;
import org.z2six.villageroverhaul.server.CustomCommandsService;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
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

    // -------------------------
    // Combat state (NEW)
    // -------------------------
    private static final String K_COMBAT_MODE = "combat_mode";
    private static final String K_COMBAT_PRE_FLEE = "combat_pre_flee";
    private static final String K_COMBAT_PRE_HELP = "combat_pre_help";
    private static final String K_FLEE_THREAT = "flee_threat";

    // Help return snapshot (return to previous position when HELP ends)
    private static final String K_HELP_SNAP_MODE = "help_snap_mode";
    private static final String K_HELP_SNAP_DIM = "help_snap_dim";
    private static final String K_HELP_SNAP_X = "help_snap_x";
    private static final String K_HELP_SNAP_Y = "help_snap_y";
    private static final String K_HELP_SNAP_Z = "help_snap_z";
    private static final String K_HELP_RETURN_ACTIVE = "help_return_active";
    private static final String K_HELP_RETURN_SINCE = "help_return_since"; // long gameTime

    private static final String K_UI_PAUSED_UNTIL = "ui_paused_until";
    private static final String K_FORCE_BLOCK_UNTIL = "force_block_until";
    private static final String K_STORAGE_ACTIVE = "storage_active";
    private static final String K_MANUAL_FARMING_ACTIVE = "manual_farming_active";
    private static final String K_MANUAL_FARMING_PREV_MODE = "manual_farming_prev_mode";

    // Manual farming planting animation (server-side temporary hand override)
    private static final java.util.Set<Villager> MANUAL_PLANT_ANIM_VILLS =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<Villager, net.minecraft.world.item.ItemStack> MANUAL_PLANT_ANIM_PREV_MAIN =
            new java.util.WeakHashMap<>();
    private static final java.util.Map<Villager, Long> MANUAL_PLANT_ANIM_UNTIL =
            new java.util.WeakHashMap<>();
    private static final java.util.Map<Villager, net.minecraft.world.item.ItemStack> MANUAL_PLANT_ANIM_VISUAL_MAIN =
            new java.util.WeakHashMap<>();

    // Patrol sub-root
    private static final String K_PATROL = "patrol";
    private static final String K_PATROL_OWNER = "owner";
    private static final String K_PATROL_FINALIZED = "finalized";
    private static final String K_PATROL_ROUTE = "route"; // circular/linear
    private static final String K_PATROL_WAYPOINTS = "waypoints"; // ListTag of CompoundTag {x,y,z}
    private static final String K_PATROL_INDEX = "idx";
    private static final String K_PATROL_DIR = "dir"; // +1 / -1
    private static final String K_PATROL_PAUSED = "paused";

    // Patrol setup snapshot (restore on cancel)
    private static final String K_PATROL_PREV_MODE = "prev_mode"; // String (Mode.id)
    private static final String K_PATROL_PREV_FOLLOW = "prev_follow"; // UUID

    // Saved patrol routes (multi-route support)
    private static final String K_PATROL_ROUTES = "routes"; // ListTag of CompoundTag
    private static final String K_PATROL_ACTIVE_ROUTE = "active_route"; // UUID

    // Per-route keys
    private static final String K_ROUTE_ID = "id"; // UUID
    private static final String K_ROUTE_NAME = "name"; // String
    private static final String K_ROUTE_TYPE = "type"; // String (PatrolRouteType.id)
    private static final String K_ROUTE_WAYPOINTS = "waypoints"; // ListTag of {x,y,z}

    // waypoint tag keys
    private static final String K_WP_X = "x";
    private static final String K_WP_Y = "y";
    private static final String K_WP_Z = "z";

    // ============================================================
    // HAND CLEAR PREVENTION SUPPORT (NO RESTORE LOGIC ANYMORE)
    // ============================================================

    // Allow-clears: lets our mixin permit intentional clears (menu/script) for a short window.
    // Stored in ezvr_brain root.
    private static final String K_ALLOW_CLEAR_MAIN_UNTIL = "allow_clear_main_until";
    private static final String K_ALLOW_CLEAR_OFF_UNTIL  = "allow_clear_off_until";
    private static final String K_ALLOW_SET_MAIN_UNTIL = "allow_set_main_until";
    private static final String K_ALLOW_SET_OFF_UNTIL  = "allow_set_off_until";
    private static final String K_ALLOW_SET_MAIN_ITEM  = "allow_set_main_item";
    private static final String K_ALLOW_SET_OFF_ITEM   = "allow_set_off_item";

    public enum Mode {
        NEUTRAL("neutral"),
        TRADING("trading"),
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

    // ============================================================
    // Combat modes (NEW, parallel to movement)
    // ============================================================
    public enum CombatMode {
        OFF("off"),
        FLEE("flee"),
        DEFEND("defend"),
        AGGRESSIVE("aggressive"),
        HELP("help");

        public final String id;
        CombatMode(String id) { this.id = id; }

        public static CombatMode fromId(String s) {
            if (s == null) return OFF;
            for (CombatMode m : values()) if (m.id.equalsIgnoreCase(s)) return m;
            return OFF;
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

    public static boolean trading(Villager vill) {
        if (vill == null) return false;
        if (!isControllable(vill)) return false;

        ensureAttached(vill);
        prepareForManualControl(vill);
        setMode(vill, Mode.TRADING);

        clearFollowPlayer(vill);
        try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
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
    // Combat API (NEW) - activation only, AI later
    // ============================================================

    public static CombatMode getCombatMode(Villager vill) {
        try {
            if (vill == null) return CombatMode.OFF;
            CompoundTag root = getOrCreateRoot(vill);
            return CombatMode.fromId(root.getString(K_COMBAT_MODE));
        } catch (Throwable t) {
            return CombatMode.OFF;
        }
    }

    public static void setCombatMode(Villager vill, CombatMode mode) {
        try {
            if (vill == null || mode == null) return;
            CompoundTag root = getOrCreateRoot(vill);

            CombatMode prev = CombatMode.fromId(root.getString(K_COMBAT_MODE));
            if (mode == CombatMode.FLEE && prev != CombatMode.FLEE) {
                root.putString(K_COMBAT_PRE_FLEE, prev.id);
            } else if (mode != CombatMode.FLEE) {
                root.remove(K_COMBAT_PRE_FLEE);
            }

            if (mode == CombatMode.HELP && prev != CombatMode.HELP) {
                root.putString(K_COMBAT_PRE_HELP, prev.id);
            } else if (mode != CombatMode.HELP) {
                root.remove(K_COMBAT_PRE_HELP);
            }

            root.putString(K_COMBAT_MODE, mode.id);

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] CombatMode set (villager={}, mode={})", vill.getUUID(), mode.id);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] setCombatMode failed (soft): {}", t.toString());
        }
    }

    /**
     * Used when fleeing is interrupted (e.g. got hit): switch to previous combat mode so
     * the villager immediately resumes normal combat behavior (block/swing/retarget).
     */
    public static boolean exitFleeToPreviousCombatMode(Villager vill) {
        try {
            if (vill == null) return false;
            if (getCombatMode(vill) != CombatMode.FLEE) return false;

            CompoundTag root = getOrCreateRoot(vill);
            CombatMode prev = CombatMode.fromId(root.getString(K_COMBAT_PRE_FLEE));

            // If there was no previous mode (or it was OFF), fall back to DEFEND so we still fight back.
            if (prev == CombatMode.FLEE || prev == CombatMode.OFF) prev = CombatMode.DEFEND;

            setCombatMode(vill, prev);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean exitHelpToPreviousCombatMode(Villager vill) {
        try {
            if (vill == null) return false;
            if (getCombatMode(vill) != CombatMode.HELP) return false;

            CompoundTag root = getOrCreateRoot(vill);
            CombatMode prev = CombatMode.fromId(root.getString(K_COMBAT_PRE_HELP));
            if (prev == CombatMode.HELP) prev = CombatMode.OFF;
            setCombatMode(vill, prev);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void setFleeThreat(Villager vill, UUID threat) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            if (threat == null) root.remove(K_FLEE_THREAT);
            else root.putUUID(K_FLEE_THREAT, threat);
        } catch (Throwable ignored) {}
    }

    public static UUID getFleeThreat(Villager vill) {
        try {
            if (vill == null) return null;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.hasUUID(K_FLEE_THREAT)) return null;
            return root.getUUID(K_FLEE_THREAT);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean combatOff(Villager vill) {
        if (vill == null) return false;
        if (!isControllable(vill)) return false;
        ensureAttached(vill);
        setCombatMode(vill, CombatMode.OFF);
        return true;
    }

    public static boolean combatFlee(Villager vill) {
        if (vill == null) return false;
        if (!isControllable(vill)) return false;
        ensureAttached(vill);
        setCombatMode(vill, CombatMode.FLEE);
        return true;
    }

    public static boolean combatDefend(Villager vill) {
        if (vill == null) return false;
        if (!isControllable(vill)) return false;
        ensureAttached(vill);
        setCombatMode(vill, CombatMode.DEFEND);
        return true;
    }

    public static boolean combatAggressive(Villager vill) {
        if (vill == null) return false;
        if (!isControllable(vill)) return false;
        ensureAttached(vill);
        setCombatMode(vill, CombatMode.AGGRESSIVE);
        return true;
    }

    public static boolean combatHelp(Villager vill) {
        if (vill == null) return false;
        if (!isControllable(vill)) return false;
        ensureAttached(vill);
        try {
            // Snapshot what the villager was doing so we can return when HELP finishes.
            // Skip if already in HELP mode.
            if (getCombatMode(vill) != CombatMode.HELP) {
                snapshotHelpReturn(vill);
            }
        } catch (Throwable ignored) {}
        setCombatMode(vill, CombatMode.HELP);
        return true;
    }

    private static void snapshotHelpReturn(Villager vill) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            CompoundTag root = getOrCreateRoot(vill);
            Mode m = getMode(vill);
            root.putString(K_HELP_SNAP_MODE, m == null ? Mode.NEUTRAL.id : m.id);
            try { root.putString(K_HELP_SNAP_DIM, String.valueOf(vill.level().dimension().location())); } catch (Throwable ignored) { root.putString(K_HELP_SNAP_DIM, ""); }
            try {
                Vec3 p = vill.position();
                root.putDouble(K_HELP_SNAP_X, p.x);
                root.putDouble(K_HELP_SNAP_Y, p.y);
                root.putDouble(K_HELP_SNAP_Z, p.z);
            } catch (Throwable ignored) {}
            root.putBoolean(K_HELP_RETURN_ACTIVE, false);
            root.remove(K_HELP_RETURN_SINCE);
        } catch (Throwable ignored) {}
    }

    public static boolean isHelpReturnActive(Villager vill) {
        try {
            if (vill == null) return false;
            CompoundTag root = getOrCreateRoot(vill);
            return root.getBoolean(K_HELP_RETURN_ACTIVE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void scheduleHelpReturnIfNeeded(Villager vill) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            CompoundTag root = getOrCreateRoot(vill);

            // If the villager was following the player, do not return to a fixed point.
            Mode snapMode = Mode.fromId(root.getString(K_HELP_SNAP_MODE));
            if (snapMode == Mode.FOLLOW) {
                root.putBoolean(K_HELP_RETURN_ACTIVE, false);
                return;
            }

            // Only schedule if we have a snapshot.
            if (!root.contains(K_HELP_SNAP_X, Tag.TAG_DOUBLE)) return;
            root.putBoolean(K_HELP_RETURN_ACTIVE, true);
            try { root.putLong(K_HELP_RETURN_SINCE, vill.level().getGameTime()); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public static void clearHelpReturn(Villager vill) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            root.putBoolean(K_HELP_RETURN_ACTIVE, false);
            root.remove(K_HELP_RETURN_SINCE);
        } catch (Throwable ignored) {}
    }

    public static long getHelpReturnSince(Villager vill) {
        try {
            if (vill == null) return 0L;
            CompoundTag root = getOrCreateRoot(vill);
            return root.contains(K_HELP_RETURN_SINCE, Tag.TAG_LONG) ? root.getLong(K_HELP_RETURN_SINCE) : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static void ensureHelpReturnSince(Villager vill, long nowGameTime) {
        try {
            if (vill == null) return;
            if (nowGameTime <= 0L) return;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_HELP_RETURN_SINCE, Tag.TAG_LONG)) {
                root.putLong(K_HELP_RETURN_SINCE, nowGameTime);
            }
        } catch (Throwable ignored) {}
    }

    public static Vec3 getHelpReturnPos(Villager vill) {
        try {
            if (vill == null) return null;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_HELP_SNAP_X, Tag.TAG_DOUBLE)) return null;
            return new Vec3(root.getDouble(K_HELP_SNAP_X), root.getDouble(K_HELP_SNAP_Y), root.getDouble(K_HELP_SNAP_Z));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String getHelpReturnDim(Villager vill) {
        try {
            if (vill == null) return "";
            CompoundTag root = getOrCreateRoot(vill);
            return root.getString(K_HELP_SNAP_DIM);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static Mode getHelpReturnMode(Villager vill) {
        try {
            if (vill == null) return Mode.NEUTRAL;
            CompoundTag root = getOrCreateRoot(vill);
            return Mode.fromId(root.getString(K_HELP_SNAP_MODE));
        } catch (Throwable ignored) {
            return Mode.NEUTRAL;
        }
    }

    /**
     * Helper: whether combat AI should run this tick.
     */
    public static boolean shouldCombatActNow(Villager vill) {
        try {
            if (vill == null) return false;
            if (!isControllable(vill)) return false;
            return getCombatMode(vill) != CombatMode.OFF;
        } catch (Throwable t) {
            return false;
        }
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

            // Passive out-of-combat healing: IDLE/FOLLOW/PATROL only, 80% threshold.
            // Kept here because this runs once per villager per server tick via VillagerRenderStateMixin.
            if (org.z2six.villageroverhaul.config.ServerConfig.enableCombatModule) {
                try { VillagerCombatDirector.tickPassiveEat(vill); } catch (Throwable ignored) {}
            }

            // IMPORTANT: restore-on-clear logic removed. We only compute render flags here now.
            byte flags = VillagerRenderFlags.computeFromEquipment(vill);

            // Force eating pose if server says we are in an eating window.
            try {
                long until = vill.getPersistentData().getLong("ezvr_eat_pose_until");
                long now = vill.level().getGameTime();
                if (until > now) {
                    flags |= VillagerRenderFlags.FLAG_EATING_POSE;
                }
            } catch (Throwable ignored) {}

            if (vill instanceof VillagerOverhaulRenderAccess acc) {
                byte prev = acc.ezvr$getRenderFlags();
                if (prev != flags) {
                    acc.ezvr$setRenderFlags(flags);

                    // INFO so you see it by default
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] RenderFlags updated (villager={}, {} -> {})",
                            vill.getUUID(), (int) prev, (int) flags);
                }
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerBrain.tickRenderDecisions failed (soft): {}", t.toString());
        }
    }

    /**
     * Public hook: call this whenever *we* intentionally set/clear a villager's hand item.
     * We keep ONLY the allow-clear window behavior (no snapshot storage, no restoring).
     */
    public static void notifyManualHandSet(Entity entity, EquipmentSlot slot, ItemStack newStack, String reason) {
        try {
            if (!(entity instanceof Villager vill)) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (slot != EquipmentSlot.MAINHAND && slot != EquipmentSlot.OFFHAND) return;
            if (!(vill.level() instanceof ServerLevel sl)) return;

            InteractionHand hand = (slot == EquipmentSlot.MAINHAND) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;

            // If this is a CLEAR request, allow clears briefly so our mixin doesn't block it.
            if (newStack == null || newStack.isEmpty()) {
                allowClearFor(sl, vill, hand, 5);
            } else {
                // Allow ONLY this specific non-empty set for a very short window.
                allowSetFor(sl, vill, hand, newStack, 1);
                clearAllowClearMarker(vill, hand);
            }

            if (VillagerOverhaul.LOG().isDebugEnabled()) {
                if (reason == null) reason = "unknown";
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Hand set/clear noted (villager={}, slot={}, reason={}, empty={})",
                        vill.getUUID(), slot, reason, (newStack == null || newStack.isEmpty()));
            }

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

    /**
     * Returns true if we should BLOCK attempts to set a NON-EMPTY item into the villager's hand.
     * This prevents vanilla AI "visual" equips (e.g. bonemeal/emerald) from overriding loadouts.
     */
    public static boolean shouldBlockHandSet(Villager vill, InteractionHand hand, ItemStack stack) {
        try {
            if (vill == null || hand == null) return false;
            if (stack == null || stack.isEmpty()) return false;
            if (vill.level() == null || vill.level().isClientSide()) return false;
            if (!(vill.level() instanceof ServerLevel sl)) return false;

            // Only allow non-empty sets if explicitly permitted for this hand and matches expected item id.
            if (isSetAllowed(sl, vill, hand, stack)) return false;
            return true;
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

    private static boolean isSetAllowed(ServerLevel sl, Villager vill, InteractionHand hand, ItemStack stack) {
        try {
            if (sl == null || vill == null || hand == null) return false;
            CompoundTag root = getOrCreateRoot(vill);
            long now = sl.getGameTime();

            if (hand == InteractionHand.MAIN_HAND) {
                long until = root.getLong(K_ALLOW_SET_MAIN_UNTIL);
                if (until <= now) return false;
                String expect = root.getString(K_ALLOW_SET_MAIN_ITEM);
                return expect != null && !expect.isBlank() && expect.equalsIgnoreCase(safeItemId(stack));
            } else {
                long until = root.getLong(K_ALLOW_SET_OFF_UNTIL);
                if (until <= now) return false;
                String expect = root.getString(K_ALLOW_SET_OFF_ITEM);
                return expect != null && !expect.isBlank() && expect.equalsIgnoreCase(safeItemId(stack));
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

    /** Allows setting a specific non-empty item for N ticks so our mixin won't cancel our own equip calls. */
    private static void allowSetFor(ServerLevel sl, Villager vill, InteractionHand hand, ItemStack stack, int ticks) {
        try {
            if (sl == null || vill == null || hand == null) return;
            if (stack == null || stack.isEmpty()) return;

            CompoundTag root = getOrCreateRoot(vill);
            long until = sl.getGameTime() + Math.max(1, ticks);
            String id = safeItemId(stack);
            if (id.isBlank()) return;

            if (hand == InteractionHand.MAIN_HAND) {
                root.putLong(K_ALLOW_SET_MAIN_UNTIL, until);
                root.putString(K_ALLOW_SET_MAIN_ITEM, id);
            } else {
                root.putLong(K_ALLOW_SET_OFF_UNTIL, until);
                root.putString(K_ALLOW_SET_OFF_ITEM, id);
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

    private static String safeItemId(ItemStack st) {
        try {
            if (st == null || st.isEmpty()) return "";
            var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem());
            return key == null ? "" : key.toString();
        } catch (Throwable ignored) {
            return "";
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

            // Snapshot current state so Cancel can return to it.
            try {
                CompoundTag patrolSnap = getOrCreatePatrol(vill);
                Mode prevMode = getMode(vill);
                patrolSnap.putString(K_PATROL_PREV_MODE, prevMode == null ? Mode.NEUTRAL.id : prevMode.id);

                UUID prevFollow = null;
                try { prevFollow = getFollowPlayer(vill); } catch (Throwable ignored) { prevFollow = null; }
                if (prevFollow != null) patrolSnap.putUUID(K_PATROL_PREV_FOLLOW, prevFollow);
                else patrolSnap.remove(K_PATROL_PREV_FOLLOW);
            } catch (Throwable ignored) {}
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

    public static void addPatrolWaypointAtPos(Villager vill, Vec3 pos) {
        try {
            if (vill == null || pos == null) return;
            if (getMode(vill) != Mode.PATROL_SETUP) return;
            addWaypointInternal(vill, pos);
        } catch (Throwable ignored) {}
    }

    public static void markPatrolFinalized(Villager vill) {
        try {
            if (vill == null) return;
            CompoundTag patrol = getOrCreatePatrol(vill);
            patrol.putBoolean(K_PATROL_FINALIZED, true);
        } catch (Throwable ignored) {}
    }

    public static void setPatrolRouteTypeAndStart(Villager vill, PacketPatrolSetRouteType.RouteType type) {
        try {
            if (vill == null || type == null) return;

            CompoundTag patrol = getOrCreatePatrol(vill);

            PatrolRouteType rt = (type == PacketPatrolSetRouteType.RouteType.LINEAR)
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

            // We have committed to patrol; drop any cancel-restore snapshot.
            try { patrol.remove(K_PATROL_PREV_MODE); } catch (Throwable ignored) {}
            try { patrol.remove(K_PATROL_PREV_FOLLOW); } catch (Throwable ignored) {}

        } catch (Throwable ignored) {}
    }

    public static void cancelAndClearPatrol(Villager vill) {
        try {
            if (vill == null) return;
            // Important: do NOT delete saved routes when canceling setup.
            CompoundTag patrol = getOrCreatePatrol(vill);

            String prevModeId = Mode.NEUTRAL.id;
            UUID prevFollow = null;
            try { if (patrol.contains(K_PATROL_PREV_MODE, Tag.TAG_STRING)) prevModeId = patrol.getString(K_PATROL_PREV_MODE); } catch (Throwable ignored) { prevModeId = Mode.NEUTRAL.id; }
            try { if (patrol.hasUUID(K_PATROL_PREV_FOLLOW)) prevFollow = patrol.getUUID(K_PATROL_PREV_FOLLOW); } catch (Throwable ignored) { prevFollow = null; }

            patrol.remove(K_PATROL_OWNER);
            patrol.remove(K_PATROL_WAYPOINTS);
            patrol.putBoolean(K_PATROL_FINALIZED, false);
            patrol.putString(K_PATROL_ROUTE, PatrolRouteType.CIRCULAR.id);
            patrol.putInt(K_PATROL_INDEX, 0);
            patrol.putInt(K_PATROL_DIR, 1);
            patrol.putBoolean(K_PATROL_PAUSED, false);
            patrol.remove(K_PATROL_PREV_MODE);
            patrol.remove(K_PATROL_PREV_FOLLOW);

            Mode restore = Mode.fromId(prevModeId);
            if (restore == Mode.PATROL_SETUP) restore = Mode.NEUTRAL;
            setMode(vill, restore);

            if (restore == Mode.FOLLOW && prevFollow != null) {
                try { setFollowPlayer(vill, prevFollow); } catch (Throwable ignored) {}
            } else if (restore != Mode.FOLLOW) {
                clearFollowPlayer(vill);
            }

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

    public static boolean hasAnySavedPatrolRoutes(Villager vill) {
        try {
            if (vill == null) return false;
            CompoundTag patrol = getOrCreatePatrol(vill);
            migrateSinglePatrolToSavedRoutesIfNeeded(patrol);
            ListTag list = patrol.getList(K_PATROL_ROUTES, Tag.TAG_COMPOUND);
            return list != null && !list.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public record SavedPatrolRoute(UUID id, String name, PatrolRouteType type, int waypointCount) {}

    public static java.util.List<SavedPatrolRoute> listSavedPatrolRoutes(Villager vill) {
        try {
            if (vill == null) return java.util.List.of();
            CompoundTag patrol = getOrCreatePatrol(vill);
            migrateSinglePatrolToSavedRoutesIfNeeded(patrol);

            ListTag list = patrol.getList(K_PATROL_ROUTES, Tag.TAG_COMPOUND);
            if (list == null || list.isEmpty()) return java.util.List.of();

            java.util.ArrayList<SavedPatrolRoute> out = new java.util.ArrayList<>();
            for (int i = 0; i < list.size() && i < 64; i++) {
                CompoundTag rt = list.getCompound(i);
                if (rt == null) continue;
                if (!rt.hasUUID(K_ROUTE_ID)) continue;
                UUID id = rt.getUUID(K_ROUTE_ID);

                String name = rt.getString(K_ROUTE_NAME);
                if (name == null || name.isBlank()) name = "Route " + (i + 1);

                PatrolRouteType type = PatrolRouteType.fromId(rt.getString(K_ROUTE_TYPE));
                int wc = 0;
                try {
                    ListTag wp = rt.getList(K_ROUTE_WAYPOINTS, Tag.TAG_COMPOUND);
                    wc = wp == null ? 0 : wp.size();
                } catch (Throwable ignored) { wc = 0; }

                out.add(new SavedPatrolRoute(id, name, type, wc));
            }
            return out;
        } catch (Throwable ignored) {
            return java.util.List.of();
        }
    }

    public static boolean startPatrolRoute(Villager vill, UUID routeId) {
        try {
            if (vill == null || routeId == null) return false;
            if (!isControllable(vill)) return false;

            ensureAttached(vill);
            prepareForManualControl(vill);

            CompoundTag patrol = getOrCreatePatrol(vill);
            migrateSinglePatrolToSavedRoutesIfNeeded(patrol);

            CompoundTag route = findRouteById(patrol, routeId);
            if (route == null) return false;

            // Load route -> active patrol fields used by the patrol goal.
            patrol.putUUID(K_PATROL_ACTIVE_ROUTE, routeId);
            patrol.putString(K_PATROL_ROUTE, PatrolRouteType.fromId(route.getString(K_ROUTE_TYPE)).id);
            patrol.put(K_PATROL_WAYPOINTS, route.getList(K_ROUTE_WAYPOINTS, Tag.TAG_COMPOUND).copy());
            patrol.putBoolean(K_PATROL_FINALIZED, true);
            patrol.putInt(K_PATROL_INDEX, 0);
            patrol.putInt(K_PATROL_DIR, 1);

            if (getPatrolWaypointCount(vill) < 2) {
                setMode(vill, Mode.NEUTRAL);
                return false;
            }

            setMode(vill, Mode.PATROL);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean deletePatrolRoute(Villager vill, UUID routeId) {
        try {
            if (vill == null || routeId == null) return false;
            CompoundTag patrol = getOrCreatePatrol(vill);
            migrateSinglePatrolToSavedRoutesIfNeeded(patrol);

            ListTag list = patrol.getList(K_PATROL_ROUTES, Tag.TAG_COMPOUND);
            if (list == null || list.isEmpty()) return false;

            boolean removed = false;
            for (int i = 0; i < list.size(); i++) {
                CompoundTag rt = list.getCompound(i);
                if (rt == null) continue;
                if (!rt.hasUUID(K_ROUTE_ID)) continue;
                if (routeId.equals(rt.getUUID(K_ROUTE_ID))) {
                    list.remove(i);
                    removed = true;
                    break;
                }
            }
            if (!removed) return false;

            // If this was the active route, clear active marker and stop patrolling.
            try {
                if (patrol.hasUUID(K_PATROL_ACTIVE_ROUTE) && routeId.equals(patrol.getUUID(K_PATROL_ACTIVE_ROUTE))) {
                    patrol.remove(K_PATROL_ACTIVE_ROUTE);
                    patrol.putBoolean(K_PATROL_FINALIZED, false);
                    patrol.remove(K_PATROL_WAYPOINTS);
                    setMode(vill, Mode.NEUTRAL);
                }
            } catch (Throwable ignored) {}

            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean renamePatrolRoute(Villager vill, UUID routeId, String newName) {
        try {
            if (vill == null || routeId == null) return false;
            CompoundTag patrol = getOrCreatePatrol(vill);
            migrateSinglePatrolToSavedRoutesIfNeeded(patrol);
            CompoundTag route = findRouteById(patrol, routeId);
            if (route == null) return false;

            String n = sanitizeRouteName(newName);
            route.putString(K_ROUTE_NAME, n);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean saveCurrentPatrolAsNewRouteAndStart(Villager vill, String name, PatrolRouteType type) {
        try {
            if (vill == null || type == null) return false;
            if (!isControllable(vill)) return false;

            ensureAttached(vill);
            prepareForManualControl(vill);

            CompoundTag patrol = getOrCreatePatrol(vill);
            migrateSinglePatrolToSavedRoutesIfNeeded(patrol);

            ListTag current = patrol.getList(K_PATROL_WAYPOINTS, Tag.TAG_COMPOUND);
            if (current == null || current.size() < 2) return false;

            UUID id = java.util.UUID.randomUUID();
            String n = sanitizeRouteName(name);

            CompoundTag rt = new CompoundTag();
            rt.putUUID(K_ROUTE_ID, id);
            rt.putString(K_ROUTE_NAME, n);
            rt.putString(K_ROUTE_TYPE, type.id);
            rt.put(K_ROUTE_WAYPOINTS, current.copy());

            ListTag list = patrol.getList(K_PATROL_ROUTES, Tag.TAG_COMPOUND);
            if (list == null) list = new ListTag();
            if (list.size() >= 64) {
                // Drop oldest
                try { list.remove(0); } catch (Throwable ignored) {}
            }
            list.add(rt);
            patrol.put(K_PATROL_ROUTES, list);

            // Load route into active fields and start.
            patrol.putUUID(K_PATROL_ACTIVE_ROUTE, id);
            patrol.putString(K_PATROL_ROUTE, type.id);
            patrol.putBoolean(K_PATROL_FINALIZED, true);
            patrol.putInt(K_PATROL_INDEX, 0);
            patrol.putInt(K_PATROL_DIR, 1);

            setMode(vill, Mode.PATROL);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static CompoundTag findRouteById(CompoundTag patrol, UUID id) {
        try {
            if (patrol == null || id == null) return null;
            ListTag list = patrol.getList(K_PATROL_ROUTES, Tag.TAG_COMPOUND);
            if (list == null) return null;
            for (int i = 0; i < list.size(); i++) {
                CompoundTag rt = list.getCompound(i);
                if (rt != null && rt.hasUUID(K_ROUTE_ID) && id.equals(rt.getUUID(K_ROUTE_ID))) return rt;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void migrateSinglePatrolToSavedRoutesIfNeeded(CompoundTag patrol) {
        try {
            if (patrol == null) return;
            if (patrol.contains(K_PATROL_ROUTES, Tag.TAG_LIST)) return;

            // If we have a finalized legacy route, convert it to a saved route list so the UI can manage it.
            ListTag wps = patrol.getList(K_PATROL_WAYPOINTS, Tag.TAG_COMPOUND);
            if (wps == null || wps.size() < 2) return;
            if (!patrol.getBoolean(K_PATROL_FINALIZED)) return;

            PatrolRouteType type = PatrolRouteType.fromId(patrol.getString(K_PATROL_ROUTE));

            CompoundTag rt = new CompoundTag();
            UUID id = java.util.UUID.randomUUID();
            rt.putUUID(K_ROUTE_ID, id);
            rt.putString(K_ROUTE_NAME, "Route 1");
            rt.putString(K_ROUTE_TYPE, type.id);
            rt.put(K_ROUTE_WAYPOINTS, wps.copy());

            ListTag list = new ListTag();
            list.add(rt);
            patrol.put(K_PATROL_ROUTES, list);
            patrol.putUUID(K_PATROL_ACTIVE_ROUTE, id);
        } catch (Throwable ignored) {}
    }

    private static String sanitizeRouteName(String name) {
        try {
            if (name == null) return "Route";
            String s = name.strip();
            if (s.isEmpty()) s = "Route";
            if (s.length() > 32) s = s.substring(0, 32);
            return s;
        } catch (Throwable ignored) {
            return "Route";
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

            // Track for combat loadout enforcement.
            VillagerCombatLoadoutService.track(vill);

            // Track history counters.
            org.z2six.villageroverhaul.server.VillagerHistoryService.track(vill);

            // Combat has higher priority than movement.
            if (!hasGoal(vill, VillagerCombatFleeGoal.class)) {
                vill.goalSelector.addGoal(0, new VillagerCombatFleeGoal(vill));
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Attached VillagerCombatFleeGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerSwimAssistGoal.class)) {
                vill.goalSelector.addGoal(0, new VillagerSwimAssistGoal(vill));
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Attached VillagerSwimAssistGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerCombatHelpGoal.class)) {
                vill.goalSelector.addGoal(1, new VillagerCombatHelpGoal(vill));
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Attached VillagerCombatHelpGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerCombatDefendGoal.class)) {
                vill.goalSelector.addGoal(1, new VillagerCombatDefendGoal(vill));
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Attached VillagerCombatDefendGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerCombatAggressiveGoal.class)) {
                vill.goalSelector.addGoal(2, new VillagerCombatAggressiveGoal(vill));
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Attached VillagerCombatAggressiveGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerIdleGoal.class)) {
                // Keep idle lower priority than storage/manual/commands.
                vill.goalSelector.addGoal(9, new VillagerIdleGoal(vill));
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Attached VillagerIdleGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerStorageGoal.class)) {
                vill.goalSelector.addGoal(3, new VillagerStorageGoal(vill));
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Attached VillagerStorageGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerCustomCommandsExecuteGoal.class)) {
                vill.goalSelector.addGoal(4, new VillagerCustomCommandsExecuteGoal(vill));
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Attached VillagerCustomCommandsExecuteGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerCustomCommandsTeachFollowGoal.class)) {
                vill.goalSelector.addGoal(4, new VillagerCustomCommandsTeachFollowGoal(vill));
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Attached VillagerCustomCommandsTeachFollowGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerPatrolSetupFollowGoal.class)) {
                vill.goalSelector.addGoal(5, new VillagerPatrolSetupFollowGoal(vill));
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Attached VillagerPatrolSetupFollowGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerHelpReturnGoal.class)) {
                // Help return should run before normal movement goals, but never during combat.
                vill.goalSelector.addGoal(5, new VillagerHelpReturnGoal(vill));
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Attached VillagerHelpReturnGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerTradingGoal.class)) {
                vill.goalSelector.addGoal(8, new VillagerTradingGoal(vill));
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Attached VillagerTradingGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerManualFarmingGoal.class)) {
                // Keep below combat/movement goals; when enabled it explicitly blocks those goals in canUse().
                vill.goalSelector.addGoal(10, new VillagerManualFarmingGoal(vill));
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Attached VillagerManualFarmingGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerFollowGoal.class)) {
                vill.goalSelector.addGoal(6, new VillagerFollowGoal(vill));
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Attached VillagerFollowGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerPatrolGoal.class)) {
                vill.goalSelector.addGoal(7, new VillagerPatrolGoal(vill));
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Attached VillagerPatrolGoal (villager={})", vill.getUUID());
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerBrain.ensureAttached failed (soft): {}", t.toString());
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
            if (isUiPaused(vill)) return false;
            if (isStorageActive(vill)) return false;
            if (CustomCommandsService.isExecuting(vill)) return false;
            if (CustomCommandsService.isVillagerTeaching(vill)) return false;
            if (isManualFarmingControlling(vill)) return false;
            if (getMode(vill) != Mode.NEUTRAL) return false;
            return !isCombatEngaged(vill);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Manual farming replaces vanilla brain ticking only when it is eligible to perform work.
     * This prevents villagers from becoming "frozen" if manual farming is enabled but the villager
     * has no workstation or is not a Farmer.
     */
    public static boolean isManualFarmingControlling(Villager vill) {
        try {
            if (vill == null) return false;
            if (!org.z2six.villageroverhaul.config.ServerConfig.enableFarmingModule) return false;
            if (!isManualFarmingActive(vill)) return false;
            if (!(vill.level() instanceof ServerLevel sl)) return false;

            try {
                if (vill.getVillagerData() == null || vill.getVillagerData().getProfession() != VillagerProfession.FARMER) return false;
            } catch (Throwable ignored) {
                return false;
            }

            return FarmingSettingsService.getEffectiveWorkstation(sl, vill) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void setStorageActive(Villager vill, boolean active) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            if (active) root.putBoolean(K_STORAGE_ACTIVE, true);
            else root.remove(K_STORAGE_ACTIVE);
        } catch (Throwable ignored) {}
    }

    public static boolean isStorageActive(Villager vill) {
        try {
            if (vill == null) return false;
            if (!org.z2six.villageroverhaul.config.ServerConfig.enableFarmingModule) return false;
            CompoundTag root = getOrCreateRoot(vill);
            return root.getBoolean(K_STORAGE_ACTIVE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void setManualFarmingActive(Villager vill, boolean active) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            if (active) {
                root.putBoolean(K_MANUAL_FARMING_ACTIVE, true);
            } else {
                root.remove(K_MANUAL_FARMING_ACTIVE);
                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    public static boolean isManualFarmingActive(Villager vill) {
        try {
            if (vill == null) return false;
            CompoundTag root = getOrCreateRoot(vill);
            return root.getBoolean(K_MANUAL_FARMING_ACTIVE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void rememberPrevModeForManualFarming(Villager vill) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            if (root.contains(K_MANUAL_FARMING_PREV_MODE, Tag.TAG_STRING)) return;

            Mode cur = getMode(vill);
            root.putString(K_MANUAL_FARMING_PREV_MODE, cur == null ? Mode.NEUTRAL.id : cur.id);
        } catch (Throwable ignored) {}
    }

    public static void restorePrevModeAfterManualFarming(Villager vill) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_MANUAL_FARMING_PREV_MODE, Tag.TAG_STRING)) return;
            String prev = root.getString(K_MANUAL_FARMING_PREV_MODE);
            root.remove(K_MANUAL_FARMING_PREV_MODE);
            setMode(vill, Mode.fromId(prev));
        } catch (Throwable ignored) {}
    }

    public static void clearPrevModeForManualFarming(Villager vill) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            root.remove(K_MANUAL_FARMING_PREV_MODE);
        } catch (Throwable ignored) {}
    }

    public static void setUiPaused(Villager vill, boolean paused) {
        try {
            if (vill == null || !(vill.level() instanceof ServerLevel sl)) return;
            CompoundTag root = getOrCreateRoot(vill);
            if (paused) {
                root.putLong(K_UI_PAUSED_UNTIL, sl.getGameTime() + 40L);
                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
            } else {
                root.putLong(K_UI_PAUSED_UNTIL, 0L);
            }
        } catch (Throwable ignored) {}
    }

    public static boolean isUiPaused(Villager vill) {
        try {
            if (vill == null || !(vill.level() instanceof ServerLevel sl)) return false;
            try { if (vill.getTradingPlayer() != null) return true; } catch (Throwable ignored) {}
            CompoundTag root = getOrCreateRoot(vill);
            long until = root.getLong(K_UI_PAUSED_UNTIL);
            return until > sl.getGameTime();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setCombatEngaged(Villager vill, boolean engaged) {
        try {
            if (vill == null) return;
            if (engaged) vill.getPersistentData().putBoolean("ezvr_combat_engaged", true);
            else vill.getPersistentData().remove("ezvr_combat_engaged");
        } catch (Throwable ignored) {}
    }

    public static boolean isCombatEngaged(Villager vill) {
        try {
            if (vill == null) return false;
            return vill.getPersistentData().getBoolean("ezvr_combat_engaged");
        } catch (Throwable t) {
            return false;
        }
    }

    private static final java.util.Set<Villager> FORCE_BLOCK_VILLS =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    public static void forceBlockFor(Villager vill, int ticks) {
        try {
            if (vill == null || !(vill.level() instanceof ServerLevel sl)) return;
            CompoundTag root = getOrCreateRoot(vill);
            long until = sl.getGameTime() + Math.max(1, ticks);
            root.putLong(K_FORCE_BLOCK_UNTIL, until);
            forceBlockStart(vill);
            FORCE_BLOCK_VILLS.add(vill);
        } catch (Throwable ignored) {}
    }

    public static void tickForceBlocks() {
        try {
            if (FORCE_BLOCK_VILLS.isEmpty()) return;
            java.util.Iterator<Villager> it = FORCE_BLOCK_VILLS.iterator();
            while (it.hasNext()) {
                Villager vill = it.next();
                if (vill == null || vill.level() == null) {
                    it.remove();
                    continue;
                }
                boolean active = tickForceBlockForVillager(vill);
                if (!active) it.remove();
            }
        } catch (Throwable ignored) {}
    }

    public static boolean tickForceBlockForVillager(Villager vill) {
        try {
            if (vill == null || !(vill.level() instanceof ServerLevel sl)) return false;
            CompoundTag root = getOrCreateRoot(vill);
            long until = root.getLong(K_FORCE_BLOCK_UNTIL);
            if (until <= 0L) return false;

            long now = sl.getGameTime();
            if (now >= until) {
                root.putLong(K_FORCE_BLOCK_UNTIL, 0L);
                try { vill.stopUsingItem(); } catch (Throwable ignored) {}
                return false;
            }

            forceBlockStart(vill);
            return true;
        } catch (Throwable ignored) {}
        return false;
    }

    public static void triggerManualPlantAnimation(Villager vill, ItemStack visualMainHand, int ticks) {
        try {
            if (vill == null) return;
            if (!(vill.level() instanceof ServerLevel sl)) return;

            // If villager already has something in-hand, arms already render; just swing.
            try {
                ItemStack main = vill.getMainHandItem();
                ItemStack off = vill.getOffhandItem();
                boolean hasHands = (main != null && !main.isEmpty()) || (off != null && !off.isEmpty());
                if (hasHands) {
                    signalSwing(vill, InteractionHand.MAIN_HAND, "manual_plant_anim_swing_only");
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] [manual_farm] plant_anim swing_only villager={} entityId={} (hands already non-empty)",
                            vill.getUUID(), vill.getId());
                    return;
                }
            } catch (Throwable ignored) {}

            ItemStack visual = (visualMainHand == null) ? ItemStack.EMPTY : visualMainHand.copy();
            if (visual.isEmpty()) return;
            visual.setCount(1);

            // Save previous mainhand once for this animation session.
            if (!MANUAL_PLANT_ANIM_PREV_MAIN.containsKey(vill)) {
                ItemStack prev = vill.getMainHandItem();
                MANUAL_PLANT_ANIM_PREV_MAIN.put(vill, prev == null ? ItemStack.EMPTY : prev.copy());
            }

            // Apply visual item so custom arms layer is enabled client-side.
            notifyManualHandSet(vill, EquipmentSlot.MAINHAND, visual, "manual_plant_anim_visual_set");
            vill.setItemInHand(InteractionHand.MAIN_HAND, visual);
            MANUAL_PLANT_ANIM_VISUAL_MAIN.put(vill, visual.copy());

            long until = sl.getGameTime() + Math.max(1, ticks);
            MANUAL_PLANT_ANIM_UNTIL.put(vill, until);
            MANUAL_PLANT_ANIM_VILLS.add(vill);

            // Force an immediate render-flag update so the very next frame can show arms (otherwise it waits for next tickRenderDecisions pass).
            try { tickRenderDecisions(vill); } catch (Throwable ignored) {}

            // Trigger the custom swing anim (client uses our synced swing-seq, not vanilla swing state).
            signalSwing(vill, InteractionHand.MAIN_HAND, "manual_plant_anim");

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [manual_farm] plant_anim start villager={} entityId={} item={} ticks={} until={}",
                    vill.getUUID(), vill.getId(), String.valueOf(visual.getItem()), ticks, until);

        } catch (Throwable ignored) {}
    }

    public static void signalSwing(Villager vill, InteractionHand hand, String reason) {
        try {
            if (!(vill instanceof VillagerOverhaulSwingAccess acc)) return;
            if (hand == null) hand = InteractionHand.MAIN_HAND;
            int prev = acc.ezvr$getSwingSeq();
            int next = prev + 1;
            acc.ezvr$setSwingSeq(next);
            byte h = (hand == InteractionHand.OFF_HAND) ? (byte) 1 : (byte) 0;
            acc.ezvr$setSwingHand(h);
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [swing] villager={} entityId={} {}->{} hand={} reason={}",
                    vill.getUUID(), vill.getId(), prev, next, (h == 1 ? "off" : "main"), (reason == null ? "unknown" : reason));
        } catch (Throwable ignored) {}
    }

    public static void tickManualPlantAnimations() {
        try {
            if (MANUAL_PLANT_ANIM_VILLS.isEmpty()) return;

            java.util.Iterator<Villager> it = MANUAL_PLANT_ANIM_VILLS.iterator();
            while (it.hasNext()) {
                Villager vill = it.next();
                if (vill == null || !(vill.level() instanceof ServerLevel sl)) {
                    it.remove();
                    MANUAL_PLANT_ANIM_PREV_MAIN.remove(vill);
                    MANUAL_PLANT_ANIM_UNTIL.remove(vill);
                    MANUAL_PLANT_ANIM_VISUAL_MAIN.remove(vill);
                    continue;
                }

                Long until = MANUAL_PLANT_ANIM_UNTIL.get(vill);
                if (until == null || until <= 0L) {
                    it.remove();
                    MANUAL_PLANT_ANIM_PREV_MAIN.remove(vill);
                    MANUAL_PLANT_ANIM_UNTIL.remove(vill);
                    MANUAL_PLANT_ANIM_VISUAL_MAIN.remove(vill);
                    continue;
                }

                long now = sl.getGameTime();
                if (now < until) continue;

                // Only restore if mainhand is still the visual stack we set (don't fight combat/other modules).
                ItemStack visual = MANUAL_PLANT_ANIM_VISUAL_MAIN.get(vill);
                ItemStack cur = vill.getMainHandItem();
                boolean stillVisual = false;
                try {
                    stillVisual = visual != null
                            && !visual.isEmpty()
                            && cur != null
                            && !cur.isEmpty()
                            && ItemStack.isSameItemSameComponents(cur, visual);
                } catch (Throwable ignored) { stillVisual = false; }

                if (stillVisual) {
                    ItemStack prev = MANUAL_PLANT_ANIM_PREV_MAIN.get(vill);
                    notifyManualHandSet(vill, EquipmentSlot.MAINHAND, prev == null ? ItemStack.EMPTY : prev, "manual_plant_anim_restore");
                    vill.setItemInHand(InteractionHand.MAIN_HAND, prev == null ? ItemStack.EMPTY : prev);
                    try { tickRenderDecisions(vill); } catch (Throwable ignored) {}
                }

                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [manual_farm] plant_anim end villager={} entityId={} restored={}",
                        vill.getUUID(), vill.getId(), stillVisual);

                it.remove();
                MANUAL_PLANT_ANIM_PREV_MAIN.remove(vill);
                MANUAL_PLANT_ANIM_UNTIL.remove(vill);
                MANUAL_PLANT_ANIM_VISUAL_MAIN.remove(vill);
            }
        } catch (Throwable ignored) {}
    }

    private static void forceBlockStart(Villager vill) {
        try {
            if (vill == null) return;
            if (vill.isUsingItem()) return;

            ItemStack off = vill.getOffhandItem();
            if (isShieldItem(off)) {
                vill.startUsingItem(InteractionHand.OFF_HAND);
                return;
            }

            ItemStack main = vill.getMainHandItem();
            if (isShieldItem(main)) {
                vill.startUsingItem(InteractionHand.MAIN_HAND);
            }
        } catch (Throwable ignored) {}
    }

    private static boolean isShieldItem(ItemStack st) {
        try {
            if (st == null || st.isEmpty()) return false;
            return st.getUseAnimation() == net.minecraft.world.item.UseAnim.BLOCK;
        } catch (Throwable t) {
            return false;
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
                    if (!(v instanceof java.util.Map<?, ?> m)) continue;

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
            root.putString(K_COMBAT_MODE, CombatMode.OFF.id);
            pd.put(TAG_ROOT, root);
        } else {
            // Ensure new keys exist for older villagers
            try {
                CompoundTag root = pd.getCompound(TAG_ROOT);
                if (!root.contains(K_COMBAT_MODE, Tag.TAG_STRING)) {
                    root.putString(K_COMBAT_MODE, CombatMode.OFF.id);
                }
            } catch (Throwable ignored) {}
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
