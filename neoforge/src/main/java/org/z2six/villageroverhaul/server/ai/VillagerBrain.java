// neoforge/src/main/java/org/z2six/villageroverhaul/server/ai/VillagerBrain.java
package org.z2six.villageroverhaul.server.ai;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.server.RecruitService;

import java.util.UUID;

/**
 * Brain / single source of truth for villager control.
 *
 * Commands are expressed as Mode in persistent data, and executed by AI Goals that watch Mode.
 * Everywhere else should call VillagerBrain.<command>(...) in one line.
 */
public final class VillagerBrain {

    private VillagerBrain() {}

    // -------------------------
    // Persistent data keys
    // -------------------------
    private static final String TAG_ROOT = "ezvr_brain";
    private static final String K_MODE = "mode";
    private static final String K_ATTACHED = "attached";

    // Follow target
    private static final String K_FOLLOW_PLAYER = "follow_player";

    public enum Mode {
        NATURAL("natural"),
        IDLE("idle"),
        FOLLOW("follow");

        public final String id;
        Mode(String id) { this.id = id; }

        public static Mode fromId(String s) {
            if (s == null) return NATURAL;
            for (Mode m : values()) if (m.id.equalsIgnoreCase(s)) return m;
            return NATURAL;
        }
    }

    // ============================================================
    // Public API (single-line calls from anywhere)
    // ============================================================

    public static boolean idle(Villager vill) {
        if (vill == null) return false;
        if (!isControllable(vill)) return false;

        ensureAttached(vill);
        setMode(vill, Mode.IDLE);

        try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
        return true;
    }

    public static boolean natural(Villager vill) {
        if (vill == null) return false;

        // Natural is safe to allow even if not recruited; but your vanilla tick gate
        // already fail-opens for non-recruited anyway.
        ensureAttached(vill);
        setMode(vill, Mode.NATURAL);

        // Clear follow target so we don't resume follow if someone flips modes back/forth.
        clearFollowPlayer(vill);

        return true;
    }

    /**
     * FOLLOW: follow the given player until another movement command is issued.
     */
    public static boolean follow(Villager vill, ServerPlayer player) {
        if (vill == null || player == null) return false;
        if (!isControllable(vill)) return false;

        ensureAttached(vill);

        setFollowPlayer(vill, player.getUUID());
        setMode(vill, Mode.FOLLOW);

        return true;
    }

    /** Convenience for code that only has UUID. */
    public static boolean idle(MinecraftServer server, UUID villagerUuid) {
        Villager v = findVillager(server, villagerUuid);
        return idle(v);
    }

    public static boolean natural(MinecraftServer server, UUID villagerUuid) {
        Villager v = findVillager(server, villagerUuid);
        return natural(v);
    }

    // ============================================================
    // Mode getters (used by goals/modules)
    // ============================================================

    public static Mode getMode(Villager vill) {
        try {
            if (vill == null) return Mode.NATURAL;
            CompoundTag root = getOrCreateRoot(vill);
            return Mode.fromId(root.getString(K_MODE));
        } catch (Throwable t) {
            return Mode.NATURAL;
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
    // Follow target getters (used by follow goal)
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

    /** Hook this from ServerEvents to attach our modules once when a villager enters the world. */
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

            CompoundTag root = getOrCreateRoot(vill);
            if (root.getBoolean(K_ATTACHED)) return;

            // Attach modules (Goals)
            // Priority 0 = strongest movement override
            vill.goalSelector.addGoal(0, new VillagerIdleGoal(vill));
            vill.goalSelector.addGoal(1, new VillagerFollowGoal(vill));

            root.putBoolean(K_ATTACHED, true);

            VillagerOverhaul.LOG().info("[VillagerOverhaul] VillagerBrain attached goals (villager={})", vill.getUUID());
        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] VillagerBrain.ensureAttached failed (soft): {}", t.toString());
        }
    }

    public static boolean shouldTickVanillaBrain(Villager vill) {
        try {
            if (vill == null) return true;

            // Non-recruited villagers should always run vanilla.
            if (!RecruitService.isRecruited(vill)) return true;

            // Recruited villagers: only allow vanilla brain in NATURAL mode.
            return getMode(vill) == Mode.NATURAL;
        } catch (Throwable t) {
            return true; // fail-open to avoid breaking villagers
        }
    }

    // ============================================================
    // Internals
    // ============================================================

    private static boolean isControllable(Villager vill) {
        try {
            // hard gate: only recruited villagers can be commanded
            return RecruitService.isRecruited(vill);
        } catch (Throwable t) {
            return false;
        }
    }

    private static CompoundTag getOrCreateRoot(Villager vill) {
        CompoundTag pd = vill.getPersistentData();
        if (!pd.contains(TAG_ROOT, CompoundTag.TAG_COMPOUND)) {
            CompoundTag root = new CompoundTag();
            root.putString(K_MODE, Mode.NATURAL.id); // default
            root.putBoolean(K_ATTACHED, false);
            pd.put(TAG_ROOT, root);
        }
        return pd.getCompound(TAG_ROOT);
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
