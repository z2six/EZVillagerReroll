package org.z2six.villageroverhaul.server.ai;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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

    public enum Mode {
        NATURAL("natural"),
        IDLE("idle");

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
        return true;
    }

    public static boolean natural(Villager vill) {
        if (vill == null) return false;
        ensureAttached(vill); // safe even if not controllable; but we can keep it consistent
        setMode(vill, Mode.NATURAL);
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
            // Priority 0 = very strong override for movement.
            vill.goalSelector.addGoal(0, new VillagerIdleGoal(vill));

            root.putBoolean(K_ATTACHED, true);

            VillagerOverhaul.LOG().info("[VillagerOverhaul] VillagerBrain attached goals (villager={})", vill.getUUID());
        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] VillagerBrain.ensureAttached failed (soft): {}", t.toString());
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
