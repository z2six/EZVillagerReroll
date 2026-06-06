package org.z2six.villageroverhaul.server;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.server.ai.VillagerSeatService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores per-villager "Custom Commands" (taught scripts) and tracks per-player teaching sessions.
 *
 * Persistence:
 * - Villager persistent data root: ezvr_cc
 * - Action list: ezvr_cc.actions (ListTag of CompoundTag)
 */
public final class CustomCommandsService {

    private CustomCommandsService() {}

    private static final String TAG_ROOT = "ezvr_cc";
    private static final String K_ACTIONS = "actions";

    private static final String K_TITLE = "title";
    private static final String K_DESC = "desc";
    private static final String K_CMD = "cmd";
    private static final String K_CASE = "case";
    private static final String K_CHAIN = "chain";
    private static final String K_ANYONE = "anyone";
    private static final String K_COMBAT_OVERRIDE = "combat_override";
    private static final String K_TIMEOUT = "timeout";
    private static final String K_RETRY = "retry";
    private static final String K_LAST_FAIL = "last_fail";
    private static final String K_STEPS = "steps";

    private static final String K_STEP_TYPE = "t";
    private static final String K_STEP_DIM = "dim";
    private static final String K_STEP_X = "x";
    private static final String K_STEP_Y = "y";
    private static final String K_STEP_Z = "z";
    private static final String K_STEP_ENTITY = "e";
    private static final String K_STEP_ENDER = "ender";
    private static final String K_STEP_WAIT_TICKS = "wt";
    private static final String K_STEP_LOOK_YAW = "ly";
    private static final String K_STEP_LOOK_PITCH = "lp";
    private static final String K_STEP_LOOK_TICKS = "lt";
    private static final String K_STEP_RULES = "rules";
    private static final String K_STEP_RULE_ID = "id";
    private static final String K_STEP_RULE_COUNT = "n";

    // Temporary state written on the villager while teaching/executing.
    private static final String K_STATE = "state";
    private static final String K_TEACHING = "teaching";
    private static final String K_TEACHER = "teacher";
    private static final String K_EXEC_ACTIVE = "exec_active";
    private static final String K_EXEC_INDEX = "exec_index";
    private static final String K_EXEC_DELAY_UNTIL = "exec_delay_until";
    private static final String K_EXEC_FAIL_COUNT = "exec_fail_count";

    private static final String K_STOP_AFTER = "stop_after_retries";
    private static final String K_LISTEN_CHAT = "listen_chat";
    private static final String K_PASS_CHAT = "pass_chat";
    private static final String K_PASS_RANGE = "pass_range";

    private static final int MAX_ACTIONS = 64;
    private static final int MAX_STEPS_PER_ACTION = 128;

    public enum StepType {
        WAYPOINT(0),
        INTERACT_BLOCK(1),
        INTERACT_ENTITY(2),
        WITHDRAW_CHEST(3),
        DEPOSIT_CHEST(4),
        WAIT(5),
        LOOK(6);

        public final int id;
        StepType(int id) { this.id = id; }

        public static StepType fromId(int id) {
            for (StepType t : values()) if (t.id == id) return t;
            return WAYPOINT;
        }
    }

    public record ItemCountRule(String itemId, int count) {}

    public record Step(StepType type, String dim, int x, int y, int z, UUID entityUuid, boolean isEnderChest, List<ItemCountRule> rules, int waitTicks, int lookTicks, float lookYaw, float lookPitch) {
        public static Step waypoint(String dim, BlockPos p) {
            return new Step(StepType.WAYPOINT, dim, p.getX(), p.getY(), p.getZ(), null, false, List.of(), 0, 0, 0.0f, 0.0f);
        }
        public static Step interactBlock(String dim, BlockPos p) {
            return new Step(StepType.INTERACT_BLOCK, dim, p.getX(), p.getY(), p.getZ(), null, false, List.of(), 0, 0, 0.0f, 0.0f);
        }
        public static Step interactEntity(UUID uuid) {
            return new Step(StepType.INTERACT_ENTITY, "", 0, 0, 0, uuid, false, List.of(), 0, 0, 0.0f, 0.0f);
        }
        public static Step withdrawChest(String dim, BlockPos p, boolean ender) {
            return new Step(StepType.WITHDRAW_CHEST, dim, p.getX(), p.getY(), p.getZ(), null, ender, List.of(), 0, 0, 0.0f, 0.0f);
        }
        public static Step depositChest(String dim, BlockPos p, boolean ender) {
            return new Step(StepType.DEPOSIT_CHEST, dim, p.getX(), p.getY(), p.getZ(), null, ender, List.of(), 0, 0, 0.0f, 0.0f);
        }
        public static Step waitTicks(int waitTicks) {
            return new Step(StepType.WAIT, "", 0, 0, 0, null, false, List.of(), Math.max(0, waitTicks), 0, 0.0f, 0.0f);
        }
        public static Step look(float yaw, float pitch, int lookTicks) {
            if (Float.isNaN(yaw) || Float.isInfinite(yaw)) yaw = 0.0f;
            if (Float.isNaN(pitch) || Float.isInfinite(pitch)) pitch = 0.0f;
            if (pitch < -90.0f) pitch = -90.0f;
            if (pitch > 90.0f) pitch = 90.0f;
            int lt = Math.max(0, lookTicks);
            return new Step(StepType.LOOK, "", 0, 0, 0, null, false, List.of(), 0, lt, yaw, pitch);
        }

        public static Step withRules(Step s, List<ItemCountRule> rules) {
            if (s == null) return null;
            List<ItemCountRule> rr = rules == null ? List.of() : rules;
            return new Step(s.type(), s.dim(), s.x(), s.y(), s.z(), s.entityUuid(), s.isEnderChest(), rr, s.waitTicks(), s.lookTicks(), s.lookYaw(), s.lookPitch());
        }

        public static Step withLookTicks(Step s, int lookTicks) {
            if (s == null) return null;
            return new Step(s.type(), s.dim(), s.x(), s.y(), s.z(), s.entityUuid(), s.isEnderChest(), s.rules(), s.waitTicks(), Math.max(0, lookTicks), s.lookYaw(), s.lookPitch());
        }
    }

    public record TaughtActionMeta(
            String title,
            String command,
            boolean caseSensitive,
            boolean chain,
            boolean anyone,
            boolean combatOverride,
            String description,
            int timeoutSeconds,
            int retryAfterSeconds,
            int stopAfterRetries,
            long lastFailGameTime,
            List<Step> steps
    ) {}

    public static final class TeachSession {
        public final UUID playerUuid;
        public final int villagerEntityId;
        public final UUID villagerUuid;
        public final int editIndex;

        public String titleDraft = "";
        public String descDraft = "";
        public String commandDraft = "";
        public boolean caseSensitive = true;
        public boolean chain = false;
        public boolean anyone = false;
        public boolean combatOverride = true;
        public int timeoutSeconds = 10;
        public int retryAfterSeconds = 10;
        public int stopAfterRetries = 0;
        public final List<Step> steps = new ArrayList<>();

        // -1 = not waiting, else see PacketCcBeginRecord kind values.
        public int waitingKind = -1;
        public int pendingLookDurationStepIndex = -1;

        TeachSession(UUID playerUuid, int villagerEntityId, UUID villagerUuid, int editIndex) {
            this.playerUuid = playerUuid;
            this.villagerEntityId = villagerEntityId;
            this.villagerUuid = villagerUuid;
            this.editIndex = editIndex;
        }
    }

    private static final Map<UUID, TeachSession> SESSIONS = new ConcurrentHashMap<>();

    public static TeachSession getSession(ServerPlayer sp) {
        if (sp == null) return null;
        return SESSIONS.get(sp.getUUID());
    }

    public static TeachSession getSessionFor(ServerPlayer sp, Villager vill) {
        try {
            if (sp == null || vill == null) return null;
            TeachSession s = SESSIONS.get(sp.getUUID());
            if (s == null) return null;
            if (s.villagerUuid == null) return null;
            if (!s.villagerUuid.equals(vill.getUUID())) return null;
            return s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isTeaching(ServerPlayer sp, Villager vill) {
        return getSessionFor(sp, vill) != null;
    }

    public static void clearSession(ServerPlayer sp) {
        try {
            if (sp == null) return;
            SESSIONS.remove(sp.getUUID());
        } catch (Throwable ignored) {}
    }

    public static void beginTeaching(ServerPlayer sp, Villager vill, int editIndex) {
        try {
            if (sp == null || vill == null) return;
            TeachSession s = new TeachSession(sp.getUUID(), vill.getId(), vill.getUUID(), editIndex);

            if (editIndex >= 0) {
                TaughtActionMeta existing = getActionMeta(vill, editIndex);
                if (existing != null) {
                    s.titleDraft = existing.title();
                    s.descDraft = existing.description();
                    s.commandDraft = existing.command();
                    s.caseSensitive = existing.caseSensitive();
                    s.chain = existing.chain();
                    s.anyone = existing.anyone();
                    s.combatOverride = existing.combatOverride();
                    s.timeoutSeconds = existing.timeoutSeconds();
                    s.retryAfterSeconds = existing.retryAfterSeconds();
                    s.stopAfterRetries = existing.stopAfterRetries();
                }
            }

            SESSIONS.put(sp.getUUID(), s);

            try {
                CompoundTag root = getOrCreateRoot(vill);
                CompoundTag st = root.contains(K_STATE, Tag.TAG_COMPOUND) ? root.getCompound(K_STATE) : new CompoundTag();
                st.putBoolean(K_TEACHING, true);
                st.putUUID(K_TEACHER, sp.getUUID());
                root.put(K_STATE, st);
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] beginTeaching failed", t);
        }
    }

    public static void stopTeaching(ServerPlayer sp, Villager vill) {
        try {
            if (sp == null) return;
            TeachSession s = (vill == null) ? getSession(sp) : getSessionFor(sp, vill);
            if (s != null) {
                SESSIONS.remove(sp.getUUID());
            }
            if (vill != null) {
                try {
                    CompoundTag root = getOrCreateRoot(vill);
                    if (root.contains(K_STATE, Tag.TAG_COMPOUND)) {
                        CompoundTag st = root.getCompound(K_STATE);
                        st.putBoolean(K_TEACHING, false);
                        st.remove(K_TEACHER);
                        root.put(K_STATE, st);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    public static void stopTeaching(ServerPlayer sp) {
        try {
            if (sp == null) return;
            TeachSession s = getSession(sp);
            if (s == null) return;

            Villager vill = null;
            try {
                if (sp.server != null) {
                    for (ServerLevel lvl : sp.server.getAllLevels()) {
                        try {
                            Entity ent = lvl.getEntity(s.villagerUuid);
                            if (ent instanceof Villager v) { vill = v; break; }
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}

            stopTeaching(sp, vill);
            if (vill != null) {
                try { clearTeachingState(vill); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    public static void clearTeachingState(Villager vill) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_STATE, Tag.TAG_COMPOUND)) return;
            CompoundTag st = root.getCompound(K_STATE);
            st.putBoolean(K_TEACHING, false);
            st.remove(K_TEACHER);
            root.put(K_STATE, st);
        } catch (Throwable ignored) {}
    }

    public static boolean isVillagerTeaching(Villager vill) {
        try {
            if (vill == null) return false;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_STATE, Tag.TAG_COMPOUND)) return false;
            CompoundTag st = root.getCompound(K_STATE);
            return st.getBoolean(K_TEACHING);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static UUID getTeachingPlayer(Villager vill) {
        try {
            if (vill == null) return null;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_STATE, Tag.TAG_COMPOUND)) return null;
            CompoundTag st = root.getCompound(K_STATE);
            if (!st.contains(K_TEACHER)) return null;
            return st.getUUID(K_TEACHER);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void setWaiting(ServerPlayer sp, Villager vill, int waitingKind) {
        try {
            TeachSession s = getSessionFor(sp, vill);
            if (s == null) return;
            s.waitingKind = waitingKind;
        } catch (Throwable ignored) {}
    }

    public static boolean isWaiting(ServerPlayer sp, Villager vill) {
        try {
            TeachSession s = getSessionFor(sp, vill);
            return s != null && s.waitingKind >= 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void addWaypointStep(ServerPlayer sp, Villager vill) {
        try {
            if (sp == null || vill == null) return;
            TeachSession s = getSessionFor(sp, vill);
            if (s == null) return;
            if (s.steps.size() >= MAX_STEPS_PER_ACTION) return;

            BlockPos bp = sp.blockPosition();
            String dim = "";
            try { dim = String.valueOf(sp.serverLevel().dimension().location()); } catch (Throwable ignored) { dim = ""; }
            s.steps.add(Step.waypoint(dim, bp));
        } catch (Throwable ignored) {}
    }

    public static void addWaitStep(ServerPlayer sp, Villager vill, float seconds) {
        try {
            if (sp == null || vill == null) return;
            TeachSession s = getSessionFor(sp, vill);
            if (s == null) return;
            if (s.steps.size() >= MAX_STEPS_PER_ACTION) return;
            if (Float.isNaN(seconds) || Float.isInfinite(seconds)) seconds = 0.0f;
            if (seconds < 0.0f) seconds = 0.0f;
            if (seconds > 3600.0f) seconds = 3600.0f;
            int ticks = Math.max(0, Math.round(seconds * 20.0f));
            s.steps.add(Step.waitTicks(ticks));
        } catch (Throwable ignored) {}
    }

    public static boolean recordLook(ServerPlayer sp, Villager vill, float yaw, float pitch) {
        try {
            if (sp == null || vill == null) return false;
            TeachSession s = getSessionFor(sp, vill);
            if (s == null) return false;
            if (s.waitingKind != 3) return false;
            if (s.steps.size() >= MAX_STEPS_PER_ACTION) return false;
            s.steps.add(Step.look(yaw, pitch, 0));
            s.waitingKind = -1;
            s.pendingLookDurationStepIndex = s.steps.size() - 1;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean setSessionLookDuration(ServerPlayer sp, Villager vill, int stepIndex, float seconds) {
        try {
            if (sp == null || vill == null) return false;
            TeachSession s = getSessionFor(sp, vill);
            if (s == null) return false;
            if (stepIndex < 0 || stepIndex >= s.steps.size()) return false;

            Step cur = s.steps.get(stepIndex);
            if (cur == null || cur.type() != StepType.LOOK) return false;

            float sec = seconds;
            if (Float.isNaN(sec) || Float.isInfinite(sec)) sec = 0.0f;
            if (sec < 0.0f) sec = 0.0f;
            if (sec > 3600.0f) sec = 3600.0f;
            int lt = Math.max(0, Math.round(sec * 20.0f));
            s.steps.set(stepIndex, Step.withLookTicks(cur, lt));

            if (s.pendingLookDurationStepIndex == stepIndex) s.pendingLookDurationStepIndex = -1;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean recordInteractBlock(ServerPlayer sp, Villager vill, ServerLevel level, BlockPos pos) {
        try {
            if (sp == null || vill == null || level == null || pos == null) return false;
            TeachSession s = getSessionFor(sp, vill);
            if (s == null) return false;
            if (s.waitingKind != 0) return false;
            if (s.steps.size() >= MAX_STEPS_PER_ACTION) return false;

            String dim = "";
            try { dim = String.valueOf(level.dimension().location()); } catch (Throwable ignored) { dim = ""; }
            s.steps.add(Step.interactBlock(dim, pos));
            s.waitingKind = -1;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean recordInteractEntity(ServerPlayer sp, Villager vill, Entity target) {
        try {
            if (sp == null || vill == null || target == null) return false;
            TeachSession s = getSessionFor(sp, vill);
            if (s == null) return false;
            if (s.waitingKind != 0) return false;
            if (s.steps.size() >= MAX_STEPS_PER_ACTION) return false;
            s.steps.add(Step.interactEntity(target.getUUID()));
            s.waitingKind = -1;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isChestLike(BlockState st) {
        try {
            if (st == null) return false;
            var b = st.getBlock();
            return b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL || b == Blocks.ENDER_CHEST;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean recordWithdrawChest(ServerPlayer sp, Villager vill, ServerLevel level, BlockPos pos) {
        try {
            if (sp == null || vill == null || level == null || pos == null) return false;
            TeachSession s = getSessionFor(sp, vill);
            if (s == null) return false;
            if (s.waitingKind != 1) return false;
            if (s.steps.size() >= MAX_STEPS_PER_ACTION) return false;
            BlockState st = level.getBlockState(pos);
            if (!isChestLike(st)) return false;

            boolean ender = false;
            try { ender = st.getBlock() == Blocks.ENDER_CHEST; } catch (Throwable ignored) { ender = false; }
            String dim = "";
            try { dim = String.valueOf(level.dimension().location()); } catch (Throwable ignored) { dim = ""; }
            s.steps.add(Step.withdrawChest(dim, pos, ender));
            s.waitingKind = -1;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean recordDepositChest(ServerPlayer sp, Villager vill, ServerLevel level, BlockPos pos) {
        try {
            if (sp == null || vill == null || level == null || pos == null) return false;
            TeachSession s = getSessionFor(sp, vill);
            if (s == null) return false;
            if (s.waitingKind != 2) return false;
            if (s.steps.size() >= MAX_STEPS_PER_ACTION) return false;
            BlockState st = level.getBlockState(pos);
            if (!isChestLike(st)) return false;

            boolean ender = false;
            try { ender = st.getBlock() == Blocks.ENDER_CHEST; } catch (Throwable ignored) { ender = false; }
            String dim = "";
            try { dim = String.valueOf(level.dimension().location()); } catch (Throwable ignored) { dim = ""; }
            s.steps.add(Step.depositChest(dim, pos, ender));
            s.waitingKind = -1;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int getSessionLastStepIndex(ServerPlayer sp, Villager vill) {
        try {
            TeachSession s = getSessionFor(sp, vill);
            if (s == null) return -1;
            return s.steps.size() - 1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public static void setSessionChestRules(ServerPlayer sp, Villager vill, int stepIndex, List<ItemCountRule> rules) {
        try {
            TeachSession s = getSessionFor(sp, vill);
            if (s == null) return;
            if (stepIndex < 0 || stepIndex >= s.steps.size()) return;
            Step cur = s.steps.get(stepIndex);
            if (cur == null) return;
            if (cur.type() != StepType.WITHDRAW_CHEST && cur.type() != StepType.DEPOSIT_CHEST) return;
            s.steps.set(stepIndex, Step.withRules(cur, rules));
        } catch (Throwable ignored) {}
    }

    public record TaughtAction(String title, String description, List<Step> steps) {}

    public static List<TaughtActionMeta> getActionsMeta(Villager vill) {
        try {
            if (vill == null) return List.of();
            CompoundTag root = getOrCreateRoot(vill);
            ListTag list = root.contains(K_ACTIONS, Tag.TAG_LIST) ? root.getList(K_ACTIONS, Tag.TAG_COMPOUND) : new ListTag();
            List<TaughtActionMeta> out = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                out.add(decodeActionMeta(list.getCompound(i)));
            }
            return out;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    public static List<TaughtAction> getActions(Villager vill) {
        try {
            if (vill == null) return List.of();
            CompoundTag root = getOrCreateRoot(vill);
            ListTag list = root.contains(K_ACTIONS, Tag.TAG_LIST) ? root.getList(K_ACTIONS, Tag.TAG_COMPOUND) : new ListTag();
            List<TaughtAction> out = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag a = list.getCompound(i);
                out.add(decodeAction(a));
            }
            return out;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    public static TaughtAction getAction(Villager vill, int index) {
        try {
            if (vill == null) return null;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_ACTIONS, Tag.TAG_LIST)) return null;
            ListTag list = root.getList(K_ACTIONS, Tag.TAG_COMPOUND);
            if (index < 0 || index >= list.size()) return null;
            return decodeAction(list.getCompound(index));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void saveFromSession(ServerPlayer sp, Villager vill, String title, String desc, int editIndex) {
        saveFromSession(sp, vill, title, "", true, false, false, desc, 10, 10, 0, editIndex);
    }

    public static void saveFromSession(ServerPlayer sp, Villager vill, String title, String command, boolean caseSensitive,
                                       boolean chain, boolean anyone, String desc, int timeoutSeconds, int retryAfterSeconds, int stopAfterRetries, int editIndex) {
        saveFromSession(sp, vill, title, command, caseSensitive, chain, anyone, true, desc, timeoutSeconds, retryAfterSeconds, stopAfterRetries, editIndex);
    }

    public static void saveFromSession(ServerPlayer sp, Villager vill, String title, String command, boolean caseSensitive,
                                       boolean chain, boolean anyone, boolean combatOverride, String desc, int timeoutSeconds, int retryAfterSeconds, int stopAfterRetries, int editIndex) {
        try {
            if (sp == null || vill == null) return;
            TeachSession s = getSessionFor(sp, vill);
            if (s == null) return;

            String t = title == null ? "" : title.trim();
            String c = command == null ? "" : command.trim();
            String d = desc == null ? "" : desc.trim();
            if (t.length() > 64) t = t.substring(0, 64);
            if (c.length() > 64) c = c.substring(0, 64);
            if (d.length() > 256) d = d.substring(0, 256);

            int to = Math.max(1, Math.min(3600, timeoutSeconds));
            int ra = Math.max(0, Math.min(3600, retryAfterSeconds));
            int sa = Math.max(0, Math.min(1000, stopAfterRetries));

            CompoundTag root = getOrCreateRoot(vill);
            ListTag list = root.contains(K_ACTIONS, Tag.TAG_LIST) ? root.getList(K_ACTIONS, Tag.TAG_COMPOUND) : new ListTag();

            s.combatOverride = combatOverride;
            int idx = editIndex;

            CompoundTag action = new CompoundTag();
            action.putString(K_TITLE, t);
            action.putString(K_CMD, c);
            action.putBoolean(K_CASE, caseSensitive);
            action.putBoolean(K_CHAIN, chain);
            action.putBoolean(K_ANYONE, anyone);
            action.putBoolean(K_COMBAT_OVERRIDE, s.combatOverride);
            action.putString(K_DESC, d);
            action.putInt(K_TIMEOUT, to);
            action.putInt(K_RETRY, ra);
            action.putInt(K_STOP_AFTER, sa);
            action.put(K_STEPS, encodeSteps(s.steps));
            if (idx < 0 || idx >= list.size()) {
                if (list.size() >= MAX_ACTIONS) return;
                list.add(action);
            } else {
                list.set(idx, action);
            }
            root.put(K_ACTIONS, list);

            // Keep drafts for reteach convenience.
            s.titleDraft = t;
            s.descDraft = d;
            s.commandDraft = c;
            s.caseSensitive = caseSensitive;
            s.chain = chain;
            s.anyone = anyone;
            s.timeoutSeconds = to;
            s.retryAfterSeconds = ra;
            s.stopAfterRetries = sa;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] saveFromSession failed", t);
        }
    }

    public static void updateActionMeta(Villager vill, int index, String title, String command, boolean caseSensitive,
                                        boolean chain, boolean anyone, boolean combatOverride, String desc, int timeoutSeconds, int retryAfterSeconds, int stopAfterRetries) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_ACTIONS, Tag.TAG_LIST)) return;

            ListTag list = root.getList(K_ACTIONS, Tag.TAG_COMPOUND);
            if (index < 0 || index >= list.size()) return;

            String t = title == null ? "" : title.trim();
            String c = command == null ? "" : command.trim();
            String d = desc == null ? "" : desc.trim();
            if (t.length() > 64) t = t.substring(0, 64);
            if (c.length() > 64) c = c.substring(0, 64);
            if (d.length() > 256) d = d.substring(0, 256);

            int to = Math.max(1, Math.min(3600, timeoutSeconds));
            int ra = Math.max(0, Math.min(3600, retryAfterSeconds));
            int sa = Math.max(0, Math.min(1000, stopAfterRetries));

            CompoundTag action = list.getCompound(index);
            action.putString(K_TITLE, t);
            action.putString(K_CMD, c);
            action.putBoolean(K_CASE, caseSensitive);
            action.putBoolean(K_CHAIN, chain);
            action.putBoolean(K_ANYONE, anyone);
            action.putBoolean(K_COMBAT_OVERRIDE, combatOverride);
            action.putString(K_DESC, d);
            action.putInt(K_TIMEOUT, to);
            action.putInt(K_RETRY, ra);
            action.putInt(K_STOP_AFTER, sa);
            list.set(index, action);
            root.put(K_ACTIONS, list);
        } catch (Throwable ignored) {}
    }

    public static void setCombatOverride(Villager vill, int actionIndex, boolean enabled) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_ACTIONS, Tag.TAG_LIST)) return;

            ListTag list = root.getList(K_ACTIONS, Tag.TAG_COMPOUND);
            if (actionIndex < 0 || actionIndex >= list.size()) return;

            CompoundTag action = list.getCompound(actionIndex);
            action.putBoolean(K_COMBAT_OVERRIDE, enabled);
            list.set(actionIndex, action);
            root.put(K_ACTIONS, list);
        } catch (Throwable ignored) {}
    }

    public static void deleteAction(Villager vill, int index) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_ACTIONS, Tag.TAG_LIST)) return;

            ListTag list = root.getList(K_ACTIONS, Tag.TAG_COMPOUND);
            if (index < 0 || index >= list.size()) return;

            // Adjust execution state to avoid referencing the wrong index after shifting.
            try {
                if (root.contains(K_STATE, Tag.TAG_COMPOUND)) {
                    CompoundTag st = root.getCompound(K_STATE);
                    if (st.getBoolean(K_EXEC_ACTIVE)) {
                        int execIdx = st.getInt(K_EXEC_INDEX);
                        if (execIdx == index) {
                            // Currently executing the deleted action -> stop.
                            st.putBoolean(K_EXEC_ACTIVE, false);
                            st.remove(K_EXEC_INDEX);
                            st.remove(K_EXEC_DELAY_UNTIL);
                            st.remove(K_EXEC_FAIL_COUNT);
                        } else if (execIdx > index) {
                            // Shift down by one.
                            st.putInt(K_EXEC_INDEX, Math.max(0, execIdx - 1));
                        }
                        root.put(K_STATE, st);
                    }
                }
            } catch (Throwable ignored) {}

            list.remove(index);
            root.put(K_ACTIONS, list);
        } catch (Throwable ignored) {}
    }

    public static void updateActionStepWait(Villager vill, int actionIndex, int stepIndex, float seconds) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_ACTIONS, Tag.TAG_LIST)) return;

            ListTag actions = root.getList(K_ACTIONS, Tag.TAG_COMPOUND);
            if (actionIndex < 0 || actionIndex >= actions.size()) return;
            CompoundTag a = actions.getCompound(actionIndex);
            if (!a.contains(K_STEPS, Tag.TAG_LIST)) return;
            ListTag steps = a.getList(K_STEPS, Tag.TAG_COMPOUND);
            if (stepIndex < 0 || stepIndex >= steps.size()) return;

            CompoundTag st = steps.getCompound(stepIndex);
            int type = st.getInt(K_STEP_TYPE);
            if (type != StepType.WAIT.id) return;

            float sec = seconds;
            if (Float.isNaN(sec) || Float.isInfinite(sec)) sec = 0.0f;
            if (sec < 0.0f) sec = 0.0f;
            if (sec > 3600.0f) sec = 3600.0f;
            int wt = Math.max(0, Math.round(sec * 20.0f));
            st.putInt(K_STEP_WAIT_TICKS, wt);
            steps.set(stepIndex, st);
            a.put(K_STEPS, steps);
            actions.set(actionIndex, a);
            root.put(K_ACTIONS, actions);

            // If this action is currently executing, stop so it reloads the updated steps next time.
            try {
                if (isExecuting(vill) && getExecutingIndex(vill) == actionIndex) stopExecution(vill);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public static void updateActionStepLookDuration(Villager vill, int actionIndex, int stepIndex, float seconds) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_ACTIONS, Tag.TAG_LIST)) return;

            ListTag actions = root.getList(K_ACTIONS, Tag.TAG_COMPOUND);
            if (actionIndex < 0 || actionIndex >= actions.size()) return;
            CompoundTag a = actions.getCompound(actionIndex);
            if (!a.contains(K_STEPS, Tag.TAG_LIST)) return;
            ListTag steps = a.getList(K_STEPS, Tag.TAG_COMPOUND);
            if (stepIndex < 0 || stepIndex >= steps.size()) return;

            CompoundTag st = steps.getCompound(stepIndex);
            int type = st.getInt(K_STEP_TYPE);
            if (type != StepType.LOOK.id) return;

            float sec = seconds;
            if (Float.isNaN(sec) || Float.isInfinite(sec)) sec = 0.0f;
            if (sec < 0.0f) sec = 0.0f;
            if (sec > 3600.0f) sec = 3600.0f;
            int lt = Math.max(0, Math.round(sec * 20.0f));
            st.putInt(K_STEP_LOOK_TICKS, lt);
            steps.set(stepIndex, st);
            a.put(K_STEPS, steps);
            actions.set(actionIndex, a);
            root.put(K_ACTIONS, actions);

            try {
                if (isExecuting(vill) && getExecutingIndex(vill) == actionIndex) stopExecution(vill);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public static void updateActionStepRules(Villager vill, int actionIndex, int stepIndex, List<ItemCountRule> rules) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_ACTIONS, Tag.TAG_LIST)) return;

            ListTag actions = root.getList(K_ACTIONS, Tag.TAG_COMPOUND);
            if (actionIndex < 0 || actionIndex >= actions.size()) return;
            CompoundTag a = actions.getCompound(actionIndex);
            if (!a.contains(K_STEPS, Tag.TAG_LIST)) return;
            ListTag steps = a.getList(K_STEPS, Tag.TAG_COMPOUND);
            if (stepIndex < 0 || stepIndex >= steps.size()) return;

            CompoundTag st = steps.getCompound(stepIndex);
            int type = st.getInt(K_STEP_TYPE);
            if (type != StepType.WITHDRAW_CHEST.id && type != StepType.DEPOSIT_CHEST.id) return;

            ListTag rl = new ListTag();
            if (rules != null) {
                int n = Math.min(256, rules.size());
                for (int i = 0; i < n; i++) {
                    ItemCountRule r = rules.get(i);
                    if (r == null) continue;
                    String id = r.itemId() == null ? "" : r.itemId().trim();
                    if (id.isBlank()) continue;
                    int c = Math.max(0, r.count());
                    CompoundTag rt = new CompoundTag();
                    rt.putString(K_STEP_RULE_ID, id);
                    rt.putInt(K_STEP_RULE_COUNT, c);
                    rl.add(rt);
                }
            }
            st.put(K_STEP_RULES, rl);

            steps.set(stepIndex, st);
            a.put(K_STEPS, steps);
            actions.set(actionIndex, a);
            root.put(K_ACTIONS, actions);

            try {
                if (isExecuting(vill) && getExecutingIndex(vill) == actionIndex) stopExecution(vill);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public static CompoundTag buildTeachSessionData(ServerPlayer sp, Villager vill) {
        CompoundTag out = new CompoundTag();
        try {
            TeachSession s = getSessionFor(sp, vill);
            if (s == null) return out;
            out.putInt("villagerEntityId", s.villagerEntityId);
            out.putInt("editIndex", s.editIndex);
            out.putString("title", s.titleDraft == null ? "" : s.titleDraft);
            out.putString("desc", s.descDraft == null ? "" : s.descDraft);
            out.putString("cmd", s.commandDraft == null ? "" : s.commandDraft);
            out.putBoolean("case", s.caseSensitive);
            out.putBoolean("chain", s.chain);
            out.putBoolean("anyone", s.anyone);
            out.putBoolean("co", s.combatOverride);
            out.putInt("timeout", s.timeoutSeconds);
            out.putInt("retry", s.retryAfterSeconds);
            out.putInt("stop", s.stopAfterRetries);
            out.putInt("waitingKind", s.waitingKind);
            out.put("steps", encodeSteps(s.steps));
        } catch (Throwable ignored) {}
        return out;
    }

    public static CompoundTag buildListData(Villager vill) {
        CompoundTag out = new CompoundTag();
        try {
            ListTag list = new ListTag();
            List<TaughtActionMeta> actions = getActionsMeta(vill);
            for (int i = 0; i < actions.size(); i++) {
                TaughtActionMeta a = actions.get(i);
                CompoundTag e = new CompoundTag();
                e.putInt("i", i);
                e.putString("t", a.title() == null ? "" : a.title());
                e.putString("c", a.command() == null ? "" : a.command());
                e.putBoolean("case", a.caseSensitive());
                e.putBoolean("chain", a.chain());
                e.putBoolean("anyone", a.anyone());
                e.putBoolean("co", a.combatOverride());
                e.putString("d", a.description() == null ? "" : a.description());
                e.putInt("to", a.timeoutSeconds());
                e.putInt("ra", a.retryAfterSeconds());
                e.putInt("stop", a.stopAfterRetries());
                e.putInt("n", a.steps() == null ? 0 : a.steps().size());
                list.add(e);
            }
            out.put("list", list);
        } catch (Throwable ignored) {}
        return out;
    }

    public static CompoundTag buildActionDetailData(Villager vill, int index) {
        CompoundTag out = new CompoundTag();
        try {
            TaughtActionMeta a = getActionMeta(vill, index);
            if (a == null) return out;
            out.putInt("i", index);
            out.putString("t", a.title() == null ? "" : a.title());
            out.putString("c", a.command() == null ? "" : a.command());
            out.putBoolean("case", a.caseSensitive());
            out.putBoolean("chain", a.chain());
            out.putBoolean("anyone", a.anyone());
            out.putBoolean("co", a.combatOverride());
            out.putString("d", a.description() == null ? "" : a.description());
            out.putInt("to", a.timeoutSeconds());
            out.putInt("ra", a.retryAfterSeconds());
            out.putInt("stop", a.stopAfterRetries());
            out.put("steps", encodeSteps(a.steps()));
        } catch (Throwable ignored) {}
        return out;
    }

    public static TaughtActionMeta getActionMeta(Villager vill, int index) {
        try {
            if (vill == null) return null;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_ACTIONS, Tag.TAG_LIST)) return null;
            ListTag list = root.getList(K_ACTIONS, Tag.TAG_COMPOUND);
            if (index < 0 || index >= list.size()) return null;
            return decodeActionMeta(list.getCompound(index));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean matchesCommand(TaughtActionMeta a, String chatMessage) {
        try {
            return a != null && ChatCommandMatcher.matches(a.command(), chatMessage, a.caseSensitive());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean canStartAction(Villager vill, int actionIndex, long gameTimeNow) {
        try {
            TaughtActionMeta a = getActionMeta(vill, actionIndex);
            if (a == null) return false;
            int retry = Math.max(0, a.retryAfterSeconds());
            long lastFail = Math.max(0L, a.lastFailGameTime());
            if (retry <= 0) return true;
            long retryUntil = lastFail + (long) retry * 20L;
            return gameTimeNow >= retryUntil;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void markActionFailed(Villager vill, int actionIndex, long gameTimeNow) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_ACTIONS, Tag.TAG_LIST)) return;
            ListTag list = root.getList(K_ACTIONS, Tag.TAG_COMPOUND);
            if (actionIndex < 0 || actionIndex >= list.size()) return;
            CompoundTag a = list.getCompound(actionIndex);
            a.putLong(K_LAST_FAIL, Math.max(0L, gameTimeNow));
            list.set(actionIndex, a);
            root.put(K_ACTIONS, list);
        } catch (Throwable ignored) {}
    }

    private static ListTag encodeSteps(List<Step> steps) {
        ListTag out = new ListTag();
        try {
            if (steps == null) return out;
            int n = Math.min(MAX_STEPS_PER_ACTION, steps.size());
            for (int i = 0; i < n; i++) {
                Step s = steps.get(i);
                if (s == null || s.type() == null) continue;
                CompoundTag t = new CompoundTag();
                t.putInt(K_STEP_TYPE, s.type().id);
                if (s.type() == StepType.INTERACT_ENTITY) {
                    if (s.entityUuid() != null) t.putUUID(K_STEP_ENTITY, s.entityUuid());
                } else if (s.type() == StepType.WAIT) {
                    t.putInt(K_STEP_WAIT_TICKS, Math.max(0, s.waitTicks()));
                } else if (s.type() == StepType.LOOK) {
                    t.putFloat(K_STEP_LOOK_YAW, s.lookYaw());
                    t.putFloat(K_STEP_LOOK_PITCH, s.lookPitch());
                    t.putInt(K_STEP_LOOK_TICKS, Math.max(0, s.lookTicks()));
                } else {
                    t.putString(K_STEP_DIM, s.dim() == null ? "" : s.dim());
                    t.putInt(K_STEP_X, s.x());
                    t.putInt(K_STEP_Y, s.y());
                    t.putInt(K_STEP_Z, s.z());
                    if (s.type() == StepType.WITHDRAW_CHEST || s.type() == StepType.DEPOSIT_CHEST) {
                        t.putBoolean(K_STEP_ENDER, s.isEnderChest());
                        if (s.rules() != null && !s.rules().isEmpty()) {
                            ListTag rl = new ListTag();
                            for (ItemCountRule r : s.rules()) {
                                if (r == null) continue;
                                String id = r.itemId() == null ? "" : r.itemId().trim();
                                if (id.isBlank()) continue;
                                int cnt = Math.max(0, r.count());
                                CompoundTag rt = new CompoundTag();
                                rt.putString(K_STEP_RULE_ID, id);
                                rt.putInt(K_STEP_RULE_COUNT, cnt);
                                rl.add(rt);
                            }
                            t.put(K_STEP_RULES, rl);
                        }
                    }
                }
                out.add(t);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static List<Step> decodeSteps(ListTag list) {
        List<Step> out = new ArrayList<>();
        try {
            if (list == null) return out;
            for (int i = 0; i < list.size(); i++) {
                CompoundTag t = list.getCompound(i);
                StepType type = StepType.fromId(t.getInt(K_STEP_TYPE));
                if (type == StepType.INTERACT_ENTITY) {
                    UUID u = null;
                    try { if (t.contains(K_STEP_ENTITY)) u = t.getUUID(K_STEP_ENTITY); } catch (Throwable ignored) { u = null; }
                    if (u != null) out.add(Step.interactEntity(u));
                    continue;
                }
                if (type == StepType.WAIT) {
                    out.add(Step.waitTicks(Math.max(0, t.getInt(K_STEP_WAIT_TICKS))));
                    continue;
                }
                if (type == StepType.LOOK) {
                    float yaw = 0.0f;
                    float pitch = 0.0f;
                    int lt = 0;
                    try { yaw = t.getFloat(K_STEP_LOOK_YAW); } catch (Throwable ignored) { yaw = 0.0f; }
                    try { pitch = t.getFloat(K_STEP_LOOK_PITCH); } catch (Throwable ignored) { pitch = 0.0f; }
                    try { lt = Math.max(0, t.getInt(K_STEP_LOOK_TICKS)); } catch (Throwable ignored) { lt = 0; }
                    out.add(Step.look(yaw, pitch, lt));
                    continue;
                }
                String dim = t.getString(K_STEP_DIM);
                int x = t.getInt(K_STEP_X);
                int y = t.getInt(K_STEP_Y);
                int z = t.getInt(K_STEP_Z);
                boolean ender = t.getBoolean(K_STEP_ENDER);
                List<ItemCountRule> rules = List.of();
                if (type == StepType.WITHDRAW_CHEST || type == StepType.DEPOSIT_CHEST) {
                    List<ItemCountRule> rr = new ArrayList<>();
                    if (t.contains(K_STEP_RULES, Tag.TAG_LIST)) {
                        ListTag rl = t.getList(K_STEP_RULES, Tag.TAG_COMPOUND);
                        for (int j = 0; j < rl.size(); j++) {
                            CompoundTag rt = rl.getCompound(j);
                            String id = rt.getString(K_STEP_RULE_ID);
                            int cnt = rt.getInt(K_STEP_RULE_COUNT);
                            if (id != null && !id.isBlank()) rr.add(new ItemCountRule(id, Math.max(0, cnt)));
                        }
                    }
                    rules = rr;
                }
                if (type == StepType.WAYPOINT) out.add(new Step(type, dim, x, y, z, null, false, List.of(), 0, 0, 0.0f, 0.0f));
                else if (type == StepType.INTERACT_BLOCK) out.add(new Step(type, dim, x, y, z, null, false, List.of(), 0, 0, 0.0f, 0.0f));
                else if (type == StepType.WITHDRAW_CHEST) out.add(new Step(type, dim, x, y, z, null, ender, rules, 0, 0, 0.0f, 0.0f));
                else if (type == StepType.DEPOSIT_CHEST) out.add(new Step(type, dim, x, y, z, null, ender, rules, 0, 0, 0.0f, 0.0f));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static TaughtAction decodeAction(CompoundTag a) {
        try {
            if (a == null) return new TaughtAction("", "", List.of());
            String t = a.getString(K_TITLE);
            String d = a.getString(K_DESC);
            ListTag st = a.contains(K_STEPS, Tag.TAG_LIST) ? a.getList(K_STEPS, Tag.TAG_COMPOUND) : new ListTag();
            return new TaughtAction(t, d, decodeSteps(st));
        } catch (Throwable ignored) {
            return new TaughtAction("", "", List.of());
        }
    }

    private static TaughtActionMeta decodeActionMeta(CompoundTag a) {
        try {
            if (a == null) return new TaughtActionMeta("", "", true, false, false, true, "", 10, 10, 0, 0L, List.of());
            String t = a.getString(K_TITLE);
            String c = a.getString(K_CMD);
            if (c == null || c.isBlank()) c = t;
            boolean cs = a.contains(K_CASE) ? a.getBoolean(K_CASE) : true;
            boolean ch = a.getBoolean(K_CHAIN);
            boolean anyone = a.getBoolean(K_ANYONE);
            boolean co = !a.contains(K_COMBAT_OVERRIDE) || a.getBoolean(K_COMBAT_OVERRIDE);
            String d = a.getString(K_DESC);
            int to = a.contains(K_TIMEOUT) ? a.getInt(K_TIMEOUT) : 10;
            int ra = a.contains(K_RETRY) ? a.getInt(K_RETRY) : 10;
            int sa = a.contains(K_STOP_AFTER) ? a.getInt(K_STOP_AFTER) : 0;
            long lf = a.contains(K_LAST_FAIL) ? a.getLong(K_LAST_FAIL) : 0L;
            if (to < 1) to = 1;
            if (to > 3600) to = 3600;
            if (ra < 0) ra = 0;
            if (ra > 3600) ra = 3600;
            if (sa < 0) sa = 0;
            if (sa > 1000) sa = 1000;
            ListTag st = a.contains(K_STEPS, Tag.TAG_LIST) ? a.getList(K_STEPS, Tag.TAG_COMPOUND) : new ListTag();
            return new TaughtActionMeta(t, c, cs, ch, anyone, co, d, to, ra, sa, lf, decodeSteps(st));
        } catch (Throwable ignored) {
            return new TaughtActionMeta("", "", true, false, false, true, "", 10, 10, 0, 0L, List.of());
        }
    }

    private static CompoundTag getOrCreateRoot(Villager vill) {
        CompoundTag pd = vill.getPersistentData();
        if (pd.contains(TAG_ROOT, Tag.TAG_COMPOUND)) return pd.getCompound(TAG_ROOT);
        CompoundTag root = new CompoundTag();
        pd.put(TAG_ROOT, root);
        return root;
    }

    public static boolean isChatListening(Villager vill) {
        try {
            if (vill == null) return false;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_LISTEN_CHAT, Tag.TAG_BYTE)) {
                root.putBoolean(K_LISTEN_CHAT, true);
                return true;
            }
            return root.getBoolean(K_LISTEN_CHAT);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void setChatListening(Villager vill, boolean listen) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            root.putBoolean(K_LISTEN_CHAT, listen);
        } catch (Throwable ignored) {}
    }

    public static boolean isChatPassing(Villager vill) {
        try {
            if (vill == null) return false;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_PASS_CHAT, Tag.TAG_BYTE)) {
                root.putBoolean(K_PASS_CHAT, false);
                return false;
            }
            return root.getBoolean(K_PASS_CHAT);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int getChatPassRange(Villager vill) {
        try {
            if (vill == null) return 6;
            CompoundTag root = getOrCreateRoot(vill);
            int v = root.contains(K_PASS_RANGE, Tag.TAG_INT) ? root.getInt(K_PASS_RANGE) : 6;
            if (v < 1) v = 1;
            int max = Math.max(1, org.z2six.villageroverhaul.config.ServerConfig.customCommandsChatRadius);
            if (v > max) v = max;
            if (!root.contains(K_PASS_RANGE, Tag.TAG_INT)) root.putInt(K_PASS_RANGE, v);
            return v;
        } catch (Throwable ignored) {
            return 6;
        }
    }

    public static void setChatPassing(Villager vill, boolean pass, int range) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            root.putBoolean(K_PASS_CHAT, pass);
            int v = range;
            if (v < 1) v = 1;
            int max = Math.max(1, org.z2six.villageroverhaul.config.ServerConfig.customCommandsChatRadius);
            if (v > max) v = max;
            root.putInt(K_PASS_RANGE, v);
        } catch (Throwable ignored) {}
    }

    public static boolean isExecuting(Villager vill) {
        try {
            if (vill == null) return false;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_STATE, Tag.TAG_COMPOUND)) return false;
            CompoundTag st = root.getCompound(K_STATE);
            return st.getBoolean(K_EXEC_ACTIVE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void startExecution(Villager vill, int actionIndex) {
        try {
            if (vill == null) return;
            try { VillagerSeatService.dismountIfSeated(vill, "cc_start"); } catch (Throwable ignored) {}
            CompoundTag root = getOrCreateRoot(vill);
            CompoundTag st = root.contains(K_STATE, Tag.TAG_COMPOUND) ? root.getCompound(K_STATE) : new CompoundTag();
            st.putBoolean(K_EXEC_ACTIVE, true);
            st.putInt(K_EXEC_INDEX, actionIndex);
            st.remove(K_EXEC_DELAY_UNTIL);
            st.putInt(K_EXEC_FAIL_COUNT, 0);
            root.put(K_STATE, st);
        } catch (Throwable ignored) {}
    }

    public static void queueExecution(Villager vill, int actionIndex, long delayUntilGameTime) {
        try {
            if (vill == null) return;
            try { VillagerSeatService.dismountIfSeated(vill, "cc_queue"); } catch (Throwable ignored) {}
            CompoundTag root = getOrCreateRoot(vill);
            CompoundTag st = root.contains(K_STATE, Tag.TAG_COMPOUND) ? root.getCompound(K_STATE) : new CompoundTag();
            st.putBoolean(K_EXEC_ACTIVE, true);
            st.putInt(K_EXEC_INDEX, actionIndex);
            st.putLong(K_EXEC_DELAY_UNTIL, Math.max(0L, delayUntilGameTime));
            root.put(K_STATE, st);
        } catch (Throwable ignored) {}
    }

    public static void stopExecution(Villager vill) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_STATE, Tag.TAG_COMPOUND)) return;
            CompoundTag st = root.getCompound(K_STATE);
            st.putBoolean(K_EXEC_ACTIVE, false);
            st.remove(K_EXEC_INDEX);
            st.remove(K_EXEC_DELAY_UNTIL);
            st.remove(K_EXEC_FAIL_COUNT);
            root.put(K_STATE, st);
        } catch (Throwable ignored) {}
    }

    public static int getExecutingIndex(Villager vill) {
        try {
            if (vill == null) return -1;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_STATE, Tag.TAG_COMPOUND)) return -1;
            CompoundTag st = root.getCompound(K_STATE);
            if (!st.getBoolean(K_EXEC_ACTIVE)) return -1;
            return st.getInt(K_EXEC_INDEX);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public static long getExecutionDelayUntil(Villager vill) {
        try {
            if (vill == null) return 0L;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_STATE, Tag.TAG_COMPOUND)) return 0L;
            CompoundTag st = root.getCompound(K_STATE);
            if (!st.getBoolean(K_EXEC_ACTIVE)) return 0L;
            return st.getLong(K_EXEC_DELAY_UNTIL);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static void clearExecutionDelay(Villager vill) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_STATE, Tag.TAG_COMPOUND)) return;
            CompoundTag st = root.getCompound(K_STATE);
            st.remove(K_EXEC_DELAY_UNTIL);
            root.put(K_STATE, st);
        } catch (Throwable ignored) {}
    }

    public static int getExecFailCount(Villager vill) {
        try {
            if (vill == null) return 0;
            CompoundTag root = getOrCreateRoot(vill);
            if (!root.contains(K_STATE, Tag.TAG_COMPOUND)) return 0;
            CompoundTag st = root.getCompound(K_STATE);
            return Math.max(0, st.getInt(K_EXEC_FAIL_COUNT));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static void setExecFailCount(Villager vill, int n) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            CompoundTag st = root.contains(K_STATE, Tag.TAG_COMPOUND) ? root.getCompound(K_STATE) : new CompoundTag();
            st.putInt(K_EXEC_FAIL_COUNT, Math.max(0, n));
            root.put(K_STATE, st);
        } catch (Throwable ignored) {}
    }

    public static Level resolveLevelFor(ServerPlayer sp, String dimId) {
        try {
            if (sp == null || sp.server == null) return null;
            if (dimId == null || dimId.isBlank()) return sp.serverLevel();
            for (ServerLevel lvl : sp.server.getAllLevels()) {
                try {
                    if (String.valueOf(lvl.dimension().location()).equals(dimId)) return lvl;
                } catch (Throwable ignored) {}
            }
            return sp.serverLevel();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
