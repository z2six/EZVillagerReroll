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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.network.patrol.PacketPatrolSetRouteType;
import org.z2six.villageroverhaul.render.VillagerRenderFlags;
import org.z2six.villageroverhaul.server.RecruitService;

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
    private static final String K_FLEE_THREAT = "flee_threat";

    private static final String K_UI_PAUSED_UNTIL = "ui_paused_until";
    private static final String K_FORCE_BLOCK_UNTIL = "force_block_until";

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
    // HAND CLEAR PREVENTION SUPPORT (NO RESTORE LOGIC ANYMORE)
    // ============================================================

    // Allow-clears: lets our mixin permit intentional clears (menu/script) for a short window.
    // Stored in ezvr_brain root.
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

    // ============================================================
    // Combat modes (NEW, parallel to movement)
    // ============================================================
    public enum CombatMode {
        OFF("off"),
        FLEE("flee"),
        DEFEND("defend"),
        AGGRESSIVE("aggressive");

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

            root.putString(K_COMBAT_MODE, mode.id);

            VillagerOverhaul.LOG().info("[VillagerOverhaul] CombatMode set (villager={}, mode={})", vill.getUUID(), mode.id);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] setCombatMode failed (soft): {}", t.toString());
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

    /**
     * Helper: combat AI should do nothing during FOLLOW.
     * (We still allow combat mode to be set while following; it just won't act.)
     */
    public static boolean shouldCombatActNow(Villager vill) {
        try {
            if (vill == null) return false;
            if (!isControllable(vill)) return false;
            if (getMode(vill) == Mode.FOLLOW) return false;
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
            try { VillagerCombatDirector.tickPassiveEat(vill); } catch (Throwable ignored) {}

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

            // Track for combat loadout enforcement.
            VillagerCombatLoadoutService.track(vill);

            // Combat has higher priority than movement (except FOLLOW which disables combat in canUse).
            if (!hasGoal(vill, VillagerCombatFleeGoal.class)) {
                vill.goalSelector.addGoal(0, new VillagerCombatFleeGoal(vill));
                VillagerOverhaul.LOG().info("[VillagerOverhaul] Attached VillagerCombatFleeGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerCombatDefendGoal.class)) {
                vill.goalSelector.addGoal(1, new VillagerCombatDefendGoal(vill));
                VillagerOverhaul.LOG().info("[VillagerOverhaul] Attached VillagerCombatDefendGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerCombatAggressiveGoal.class)) {
                vill.goalSelector.addGoal(2, new VillagerCombatAggressiveGoal(vill));
                VillagerOverhaul.LOG().info("[VillagerOverhaul] Attached VillagerCombatAggressiveGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerIdleGoal.class)) {
                vill.goalSelector.addGoal(3, new VillagerIdleGoal(vill));
                VillagerOverhaul.LOG().info("[VillagerOverhaul] Attached VillagerIdleGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerPatrolSetupFollowGoal.class)) {
                vill.goalSelector.addGoal(4, new VillagerPatrolSetupFollowGoal(vill));
                VillagerOverhaul.LOG().info("[VillagerOverhaul] Attached VillagerPatrolSetupFollowGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerFollowGoal.class)) {
                vill.goalSelector.addGoal(5, new VillagerFollowGoal(vill));
                VillagerOverhaul.LOG().info("[VillagerOverhaul] Attached VillagerFollowGoal (villager={})", vill.getUUID());
            }

            if (!hasGoal(vill, VillagerPatrolGoal.class)) {
                vill.goalSelector.addGoal(6, new VillagerPatrolGoal(vill));
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
            if (isUiPaused(vill)) return false;
            if (getMode(vill) != Mode.NEUTRAL) return false;
            return !isCombatEngaged(vill);
        } catch (Throwable t) {
            return true;
        }
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
