package org.z2six.villageroverhaul.server.ai;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import org.z2six.villageroverhaul.server.CustomCommandsService;
import org.z2six.villageroverhaul.server.RecruitService;
import org.z2six.villageroverhaul.server.ai.VillagerCombatLoadoutService;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Executes a taught Custom Command action (simple step runner).
 *
 * Combat goals have higher priority and will temporarily take over movement.
 */
public final class VillagerCustomCommandsExecuteGoal extends Goal {

    private final Villager vill;

    private int actionIndex = -1;
    private CustomCommandsService.TaughtActionMeta action;
    private int stepIndex = 0;
    private long stepStartGameTime = 0L;
    private int cooldownForStepIndex = -1;
    private long cooldownUntilGameTime = 0L;

    private boolean chestOpened = false;
    private BlockPos chestOpenedPos = null;
    private boolean chestOpenedEnder = false;
    private long chestOpenedAtGameTime = 0L;

    // LOOK smoothing:
    // Villagers can have pitch manipulated by vanilla look logic; keep our own "current" angles per LOOK step
    // and still drive LookControl with high speeds so we remain authoritative.
    private int lookSmoothForStepIndex = -1;
    private float lookSmoothYaw = 0.0f;
    private float lookSmoothPitch = 0.0f;
    private int lookHoldForStepIndex = -1;
    private long lookHoldStartGameTime = 0L;

    private static final double SPEED = 0.50;
    private static final double WAYPOINT_DONE_MAX_DY = 1.0;
    private static final double ASSIST_NEAR_DIST2 = 2.2 * 2.2;
    // Keep this close to SPEED so the "nudge" doesn't look like a sprint.
    private static final double ASSIST_SPEED = 0.55;
    private static final double ASSIST_MIN_VEL_SQR = 0.008 * 0.008;
    private static final double ASSIST_PUSH_PER_TICK = 0.035;

    // Natural-ish turn speeds (deg/tick @ 20 tps).
    private static final float LOOK_MAX_YAW_DEG_PER_TICK = 9.0f;
    private static final float LOOK_MAX_PITCH_DEG_PER_TICK = 8.0f;
    private static final float LOOK_ARRIVE_EPS_DEG = 1.0f;

    public VillagerCustomCommandsExecuteGoal(Villager vill) {
        this.vill = vill;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        try {
            if (vill == null) return false;
            if (!(vill.level() instanceof ServerLevel)) return false;
            if (!RecruitService.isRecruited(vill)) return false;
            if (VillagerBrain.isUiPaused(vill)) return false;
            if (!CustomCommandsService.isExecuting(vill)) return false;

            int idx = CustomCommandsService.getExecutingIndex(vill);
            if (idx < 0) return false;
            CustomCommandsService.TaughtActionMeta a = CustomCommandsService.getActionMeta(vill, idx);
            if (a == null || a.steps() == null || a.steps().isEmpty()) {
                CustomCommandsService.stopExecution(vill);
                return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void start() {
        try {
            actionIndex = CustomCommandsService.getExecutingIndex(vill);
            action = CustomCommandsService.getActionMeta(vill, actionIndex);
            stepIndex = 0;
            stepStartGameTime = vill.level().getGameTime();
            cooldownForStepIndex = -1;
            cooldownUntilGameTime = 0L;
            chestOpened = false;
            chestOpenedPos = null;
            chestOpenedEnder = false;
            chestOpenedAtGameTime = 0L;
            lookSmoothForStepIndex = -1;
            lookHoldForStepIndex = -1;
            lookHoldStartGameTime = 0L;
            try { VillagerCombatLoadoutService.forceEquipBegin(vill, true, "cc_exec_begin"); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
            actionIndex = -1;
            action = null;
            stepIndex = 0;
        }
    }

    @Override
    public boolean canContinueToUse() {
        try {
            if (vill == null) return false;
            if (!(vill.level() instanceof ServerLevel)) return false;
            if (VillagerBrain.isUiPaused(vill)) {
                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
                return action != null && CustomCommandsService.isExecuting(vill);
            }
            if (!CustomCommandsService.isExecuting(vill)) return false;
            int idx = CustomCommandsService.getExecutingIndex(vill);
            if (idx != actionIndex) return false;
            return action != null && action.steps() != null && stepIndex < action.steps().size();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void stop() {
        try {
            boolean combat = false;
            try { combat = VillagerBrain.isCombatEngaged(vill); } catch (Throwable ignored) { combat = false; }
            boolean uiPaused = false;
            try { uiPaused = VillagerBrain.isUiPaused(vill); } catch (Throwable ignored) { uiPaused = false; }
            if (uiPaused) {
                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
                return;
            }
            if (!combat) {
                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
            }
            try { tryCloseChestIfOpen(); } catch (Throwable ignored) {}
            try { VillagerCombatLoadoutService.forceEquipEnd(vill, "cc_exec_end"); } catch (Throwable ignored) {}
            actionIndex = -1;
            action = null;
            stepIndex = 0;
        } catch (Throwable ignored) {}
    }

    @Override
    public void tick() {
        try {
            if (vill == null || action == null || action.steps() == null) return;

            long now = vill.level().getGameTime();

            if (VillagerBrain.isUiPaused(vill)) {
                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
                stepStartGameTime++;
                if (cooldownUntilGameTime > 0L) cooldownUntilGameTime++;
                if (chestOpenedAtGameTime > 0L) chestOpenedAtGameTime++;
                if (lookHoldStartGameTime > 0L) lookHoldStartGameTime++;
                return;
            }

            // Support delayed retries: stay idle until the delay elapses, then restart from step 0.
            long delayUntil = CustomCommandsService.getExecutionDelayUntil(vill);
            if (delayUntil > 0L) {
                if (now < delayUntil) {
                    vill.getNavigation().stop();
                    return;
                }
                CustomCommandsService.clearExecutionDelay(vill);
                actionIndex = CustomCommandsService.getExecutingIndex(vill);
                action = CustomCommandsService.getActionMeta(vill, actionIndex);
                stepIndex = 0;
                stepStartGameTime = now;
            }

            // If combat is engaged, pause and let combat goals drive movement.
            try {
                if (VillagerBrain.isCombatEngaged(vill)) {
                    if (action != null && action.combatOverride()) {
                        CustomCommandsService.stopExecution(vill);
                        return;
                    }
                    vill.getNavigation().stop();
                    return;
                }
            } catch (Throwable ignored) {}

            if (stepIndex < 0 || stepIndex >= action.steps().size()) {
                try { VillagerSeatService.dismountIfCurrentActivityRequiresIt(vill, "cc_complete_activity"); } catch (Throwable ignored) {}
                CustomCommandsService.stopExecution(vill);
                vill.getNavigation().stop();
                return;
            }

            CustomCommandsService.Step step = action.steps().get(stepIndex);
            if (step == null || step.type() == null) {
                stepIndex++;
                stepStartGameTime = now;
                return;
            }

            if (VillagerSeatService.isMacroSeated(vill) && VillagerSeatService.shouldDismountBeforeStep(step.type())) {
                VillagerSeatService.dismountIfSeated(vill, "cc_step_" + step.type().name().toLowerCase(java.util.Locale.ROOT));
            }

            // Timeout: do NOT count time spent in explicit WAIT/LOOK steps, otherwise long waits/looks will wrongly fail & retry.
            if (step.type() != CustomCommandsService.StepType.WAIT && step.type() != CustomCommandsService.StepType.LOOK) {
                int timeoutSeconds = 10;
                try { timeoutSeconds = Math.max(1, action.timeoutSeconds()); } catch (Throwable ignored) { timeoutSeconds = 10; }
                long timeoutTicks = (long) timeoutSeconds * 20L;
                if (now - stepStartGameTime > timeoutTicks) {
                    failAndRetry(now);
                    return;
                }
            }

            // Small realism delay before any non-waypoint step.
            if (step.type() != CustomCommandsService.StepType.WAYPOINT
                    && step.type() != CustomCommandsService.StepType.WAIT
                    && step.type() != CustomCommandsService.StepType.LOOK
                    && cooldownForStepIndex != stepIndex) {
                cooldownForStepIndex = stepIndex;
                cooldownUntilGameTime = now + 20L; // 1s
            }
            if (step.type() != CustomCommandsService.StepType.WAYPOINT
                    && step.type() != CustomCommandsService.StepType.WAIT
                    && step.type() != CustomCommandsService.StepType.LOOK
                    && now < cooldownUntilGameTime) {
                vill.getNavigation().stop();
                return;
            }

            switch (step.type()) {
                case WAYPOINT -> tickMoveTo(step);
                case WAIT -> tickWait(step);
                case LOOK -> tickLook(step);
                case INTERACT_BLOCK -> tickInteractBlock(step);
                case INTERACT_ENTITY -> tickInteractEntity(step);
                case WITHDRAW_CHEST -> tickWithdraw(step);
                case DEPOSIT_CHEST -> tickDeposit(step);
            }

        } catch (Throwable ignored) {}
    }

    private void failAndRetry(long now) {
        try {
            if (vill == null) return;
            try { tryCloseChestIfOpen(); } catch (Throwable ignored) {}
            try { CustomCommandsService.markActionFailed(vill, actionIndex, now); } catch (Throwable ignored) {}

            int failCount = 0;
            try { failCount = CustomCommandsService.getExecFailCount(vill); } catch (Throwable ignored) { failCount = 0; }
            failCount = Math.max(0, failCount) + 1;
            try { CustomCommandsService.setExecFailCount(vill, failCount); } catch (Throwable ignored) {}

            int stopAfter = 0;
            try { stopAfter = action == null ? 0 : Math.max(0, action.stopAfterRetries()); } catch (Throwable ignored) { stopAfter = 0; }
            if (stopAfter > 0 && failCount >= stopAfter) {
                CustomCommandsService.stopExecution(vill);
                vill.getNavigation().stop();
                return;
            }

            int retrySeconds = 10;
            try { retrySeconds = Math.max(0, action == null ? 10 : action.retryAfterSeconds()); } catch (Throwable ignored) { retrySeconds = 10; }
            long retryTicks = (long) retrySeconds * 20L;

            if (retryTicks <= 0L) {
                CustomCommandsService.stopExecution(vill);
                vill.getNavigation().stop();
                return;
            }

            CustomCommandsService.queueExecution(vill, actionIndex, now + retryTicks);
            vill.getNavigation().stop();
        } catch (Throwable ignored) {}
    }

    private void advance() {
        try { tryCloseChestIfOpen(); } catch (Throwable ignored) {}
        stepIndex++;
        stepStartGameTime = vill.level().getGameTime();
        cooldownForStepIndex = -1;
        cooldownUntilGameTime = 0L;
        lookSmoothForStepIndex = -1;
        lookHoldForStepIndex = -1;
        lookHoldStartGameTime = 0L;
        try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
        if (action == null || action.steps() == null || stepIndex >= action.steps().size()) {
            try { VillagerSeatService.dismountIfCurrentActivityRequiresIt(vill, "cc_complete_activity"); } catch (Throwable ignored) {}
            CustomCommandsService.stopExecution(vill);
        }
    }

    private void tickMoveTo(CustomCommandsService.Step step) {
        BlockPos pos = new BlockPos(step.x(), step.y(), step.z());
        Vec3 target = Vec3.atCenterOf(pos);
        if (isAtWaypoint(pos)) {
            advance();
            return;
        }
        // For waypoints, don't stare at the block on the ground; keep gaze level while moving.
        try {
            double lookY = vill.getEyeY();
            vill.getLookControl().setLookAt(target.x, lookY, target.z, 30.0F, 30.0F);
        } catch (Throwable ignored) {}
        vill.getNavigation().moveTo(target.x, target.y, target.z, SPEED);
        if (vill.position().distanceToSqr(target) <= ASSIST_NEAR_DIST2) applyFinalApproachAssist(target);
    }

    private void tickWait(CustomCommandsService.Step step) {
        try {
            long now = vill.level().getGameTime();
            int wt = 0;
            try { wt = Math.max(0, step.waitTicks()); } catch (Throwable ignored) { wt = 0; }
            if (wt <= 0) {
                advance();
                return;
            }
            vill.getNavigation().stop();
            if (now - stepStartGameTime >= (long) wt) {
                advance();
            }
        } catch (Throwable ignored) {}
    }

    private void tickLook(CustomCommandsService.Step step) {
        try {
            long now = vill.level().getGameTime();
            float yaw = 0.0f;
            float pitch = 0.0f;
            try { yaw = step.lookYaw(); } catch (Throwable ignored) { yaw = 0.0f; }
            try { pitch = step.lookPitch(); } catch (Throwable ignored) { pitch = 0.0f; }

            int lt = 0;
            try { lt = Math.max(0, step.lookTicks()); } catch (Throwable ignored) { lt = 0; }
            if (lt <= 0) {
                advance();
                return;
            }

            try { vill.getNavigation().stop(); } catch (Throwable ignored) {}

            // Use Minecraft's rotation math so yaw/pitch map to a stable world-space direction.
            // If we compute the wrong direction, LookControl will fight our forced rotations and cause head shaking.
            float targetYaw = yaw;
            float targetPitch = Mth.clamp(pitch, -90.0f, 90.0f);

            if (lookSmoothForStepIndex != stepIndex) {
                lookSmoothForStepIndex = stepIndex;
                try { lookSmoothYaw = vill.getYRot(); } catch (Throwable ignored) { lookSmoothYaw = targetYaw; }
                try { lookSmoothPitch = vill.getXRot(); } catch (Throwable ignored) { lookSmoothPitch = targetPitch; }
            }

            lookSmoothYaw = approachDegrees(lookSmoothYaw, targetYaw, LOOK_MAX_YAW_DEG_PER_TICK);
            lookSmoothPitch = approachDegrees(lookSmoothPitch, targetPitch, LOOK_MAX_PITCH_DEG_PER_TICK);

            Vec3 dir = Vec3.directionFromRotation(lookSmoothPitch, lookSmoothYaw);
            Vec3 eye = vill.getEyePosition();
            Vec3 target = eye.add(dir.scale(16.0));

            vill.setYRot(lookSmoothYaw);
            vill.setYHeadRot(lookSmoothYaw);
            vill.setYBodyRot(lookSmoothYaw);
            vill.setXRot(lookSmoothPitch);
            try { vill.getLookControl().setLookAt(target.x, target.y, target.z, 360.0F, 360.0F); } catch (Throwable ignored) {}

            float yawErr = Math.abs(Mth.wrapDegrees(targetYaw - lookSmoothYaw));
            float pitchErr = Math.abs(targetPitch - lookSmoothPitch);
            boolean arrived = yawErr <= LOOK_ARRIVE_EPS_DEG && pitchErr <= LOOK_ARRIVE_EPS_DEG;

            // The configured duration should be "hold once facing", not "time to turn + hold".
            if (!arrived) {
                if (lookHoldForStepIndex == stepIndex) {
                    lookHoldForStepIndex = -1;
                    lookHoldStartGameTime = 0L;
                }
                return;
            }

            if (lookHoldForStepIndex != stepIndex) {
                lookHoldForStepIndex = stepIndex;
                lookHoldStartGameTime = now;
            }
            if (now - lookHoldStartGameTime >= (long) lt) {
                advance();
            }
        } catch (Throwable ignored) {}
    }

    private static float approachDegrees(float current, float target, float maxDelta) {
        float delta = Mth.wrapDegrees(target - current);
        if (delta > maxDelta) delta = maxDelta;
        if (delta < -maxDelta) delta = -maxDelta;
        return current + delta;
    }

    private boolean isAtWaypoint(BlockPos pos) {
        try {
            if (pos == null) return false;
            BlockPos vp = vill.blockPosition();
            if (vp.getX() != pos.getX()) return false;
            if (vp.getZ() != pos.getZ()) return false;
            return Math.abs(vp.getY() - pos.getY()) <= WAYPOINT_DONE_MAX_DY;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void applyFinalApproachAssist(Vec3 target) {
        try {
            if (target == null) return;
            try { vill.getMoveControl().setWantedPosition(target.x, target.y, target.z, ASSIST_SPEED); } catch (Throwable ignored) {}

            Vec3 pos = vill.position();
            Vec3 vel = vill.getDeltaMovement();
            double hv2 = vel.x * vel.x + vel.z * vel.z;
            if (hv2 >= ASSIST_MIN_VEL_SQR) return;

            double dx = target.x - pos.x;
            double dz = target.z - pos.z;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1.0e-4) return;

            double px = (dx / len) * ASSIST_PUSH_PER_TICK;
            double pz = (dz / len) * ASSIST_PUSH_PER_TICK;

            double nx = vel.x * 0.35 + px;
            double nz = vel.z * 0.35 + pz;
            vill.setDeltaMovement(nx, vel.y, nz);
        } catch (Throwable ignored) {}
    }

    private void tickInteractBlock(CustomCommandsService.Step step) {
        if (!(vill.level() instanceof ServerLevel level)) {
            advance();
            return;
        }

        // Dim safety: if mismatch, skip.
        String dim = "";
        try { dim = String.valueOf(level.dimension().location()); } catch (Throwable ignored) { dim = ""; }
        if (step.dim() != null && !step.dim().isBlank() && !step.dim().equals(dim)) {
            advance();
            return;
        }

        BlockPos pos = new BlockPos(step.x(), step.y(), step.z());
        Vec3 center = Vec3.atCenterOf(pos);
        if (vill.position().distanceToSqr(center) > (2.2 * 2.2)) {
            vill.getLookControl().setLookAt(center.x, center.y, center.z, 30.0F, 30.0F);
            vill.getNavigation().moveTo(center.x, center.y, center.z, SPEED);
            return;
        }

        // Visual: mainhand swing via our player-arms render layer (server->client sync).
        try { swingMainhandOnce("cc_interact_block"); } catch (Throwable ignored) {}

        // Interact "as a player" without any held item.
        boolean ok = useBlockWithoutItem(level, pos);
        advance();
    }

    private boolean useBlockWithoutItem(ServerLevel level, BlockPos pos) {
        try {
            UUID fpUuid = UUID.nameUUIDFromBytes(
                    ("villageroverhaul:cc:" + vill.getUUID()).getBytes(StandardCharsets.UTF_8)
            );
            GameProfile profile = new GameProfile(fpUuid, "VO_CC");
            var fp = FakePlayerFactory.get(level, profile);

            try { VillagerSeatService.clearFakePlayerRiding(fp); } catch (Throwable ignored) {}
            try { fp.moveTo(vill.getX(), vill.getY(), vill.getZ(), vill.getYRot(), vill.getXRot()); } catch (Throwable ignored) {}
            ItemStack prev = ItemStack.EMPTY;
            try { prev = fp.getMainHandItem().copy(); } catch (Throwable ignored) { prev = ItemStack.EMPTY; }
            try { fp.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); } catch (Throwable ignored) {}

            Direction dir = chooseHitFace(pos);
            Vec3 hitLoc = Vec3.atCenterOf(pos);
            BlockHitResult hit = new BlockHitResult(hitLoc, dir, pos, false);

            InteractionResult res;
            try {
                res = level.getBlockState(pos).useWithoutItem(level, fp, hit);
            } catch (Throwable t) {
                // Fallback to "use item on" path
                res = InteractionResult.PASS;
                try {
                    res = fp.gameMode.useItemOn(fp, level, fp.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
                } catch (Throwable ignored) {}
            }

            boolean seated = false;
            try { seated = VillagerSeatService.tryTransferFakePlayerVehicleToVillager(fp, vill, pos); } catch (Throwable ignored) { seated = false; }
            if (!seated) {
                try { seated = VillagerSeatService.tryMountVillagerOnNearbySeatEntity(level, pos, vill, "after_fake_player_use"); } catch (Throwable ignored) { seated = false; }
            }
            if (!seated) {
                try { VillagerSeatService.trySeatVillagerViaEntityInside(level, pos, vill); } catch (Throwable ignored) {}
                try { VillagerSeatService.tryMountVillagerOnNearbySeatEntity(level, pos, vill, "after_entity_inside"); } catch (Throwable ignored) {}
            }
            try { fp.setItemInHand(InteractionHand.MAIN_HAND, prev); } catch (Throwable ignored) {}
            return res != null && res.consumesAction();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Direction chooseHitFace(BlockPos pos) {
        try {
            double cx = pos.getX() + 0.5;
            double cy = pos.getY() + 0.5;
            double cz = pos.getZ() + 0.5;
            double dx = vill.getX() - cx;
            double dy = (vill.getY() + 1.0) - cy;
            double dz = vill.getZ() - cz;

            double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
            if (ay >= ax && ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
            if (ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        } catch (Throwable ignored) {
            return Direction.UP;
        }
    }

    private void tickInteractEntity(CustomCommandsService.Step step) {
        if (!(vill.level() instanceof ServerLevel level)) {
            advance();
            return;
        }

        UUID u = step.entityUuid();
        if (u == null) {
            advance();
            return;
        }

        Entity target = level.getEntity(u);
        if (target == null) {
            advance();
            return;
        }

        if (vill.distanceToSqr(target) > (2.2 * 2.2)) {
            vill.getLookControl().setLookAt(target, 30.0F, 30.0F);
            vill.getNavigation().moveTo(target, SPEED);
            return;
        }

        // Visual: mainhand swing via our player-arms render layer (server->client sync).
        try { swingMainhandOnce("cc_interact_entity"); } catch (Throwable ignored) {}

        try {
            UUID fpUuid = UUID.nameUUIDFromBytes(
                    ("villageroverhaul:cc:" + vill.getUUID()).getBytes(StandardCharsets.UTF_8)
            );
            GameProfile profile = new GameProfile(fpUuid, "VO_CC");
            var fp = FakePlayerFactory.get(level, profile);
            try { VillagerSeatService.clearFakePlayerRiding(fp); } catch (Throwable ignored) {}
            try { fp.moveTo(vill.getX(), vill.getY(), vill.getZ(), vill.getYRot(), vill.getXRot()); } catch (Throwable ignored) {}
            try { fp.interactOn(target, InteractionHand.MAIN_HAND); } catch (Throwable ignored) {}
            boolean seated = false;
            try { seated = VillagerSeatService.tryTransferFakePlayerVehicleToVillager(fp, vill, target.blockPosition()); } catch (Throwable ignored) { seated = false; }
            if (!seated) {
                try { VillagerSeatService.tryMountVillagerOnNearbySeatEntity(level, target.blockPosition(), vill, "after_fake_player_entity_use"); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        advance();
    }

    private void tickWithdraw(CustomCommandsService.Step step) {
        tickChestTransfer(step, true);
    }

    private void tickDeposit(CustomCommandsService.Step step) {
        tickChestTransfer(step, false);
    }

    private void tickChestTransfer(CustomCommandsService.Step step, boolean withdraw) {
        if (!(vill.level() instanceof ServerLevel level)) {
            advance();
            return;
        }

        String dim = "";
        try { dim = String.valueOf(level.dimension().location()); } catch (Throwable ignored) { dim = ""; }
        if (step.dim() != null && !step.dim().isBlank() && !step.dim().equals(dim)) {
            advance();
            return;
        }

        BlockPos pos = new BlockPos(step.x(), step.y(), step.z());
        Vec3 center = Vec3.atCenterOf(pos);
        if (vill.position().distanceToSqr(center) > (2.2 * 2.2)) {
            vill.getLookControl().setLookAt(center.x, center.y, center.z, 30.0F, 30.0F);
            vill.getNavigation().moveTo(center.x, center.y, center.z, SPEED);
            return;
        }

        // Chest open visuals + 1s delay so it doesn't look instant.
        if (!chestOpened || chestOpenedPos == null || !chestOpenedPos.equals(pos) || chestOpenedEnder != step.isEnderChest()) {
            openChestVisuals(pos, step.isEnderChest());
            chestOpenedAtGameTime = level.getGameTime();
            vill.getNavigation().stop();
            return;
        }

        long now = level.getGameTime();
        if (now - chestOpenedAtGameTime < 20L) {
            vill.getNavigation().stop();
            return;
        }

        // Ender chests don't expose a container to villagers; treat as "visual interact" only.
        if (!step.isEnderChest()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof net.minecraft.world.Container cont) {
                if (withdraw) {
                    transferContainerToVillager(cont, step.rules());
                } else {
                    transferVillagerToContainer(cont, step.rules());
                }
            }
        }

        tryCloseChestIfOpen();
        advance();
    }

    private void openChestVisuals(BlockPos pos, boolean ender) {
        try {
            if (chestOpened) return;
            if (!(vill.level() instanceof ServerLevel level)) return;
            if (pos == null) return;

            var state = level.getBlockState(pos);
            if (state == null) return;

            // Trigger lid animation for nearby clients (no UI).
            try { level.blockEvent(pos, state.getBlock(), 1, 1); } catch (Throwable ignored) {}

            // Play open sound.
            try {
                if (ender) {
                    level.playSound(null, pos, SoundEvents.ENDER_CHEST_OPEN, SoundSource.BLOCKS, 0.5f, 1.0f);
                } else {
                    level.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5f, 1.0f);
                }
            } catch (Throwable ignored) {}

            // Hand swing synced with the chest opening animation (not with the actual item transfer).
            try { VillagerBrain.triggerManualPlantAnimation(vill, Items.CHEST.getDefaultInstance(), 10); } catch (Throwable ignored) {}

            chestOpened = true;
            chestOpenedPos = pos;
            chestOpenedEnder = ender;
        } catch (Throwable ignored) {}
    }

    private void swingMainhandOnce(String why) {
        try {
            ItemStack visual = VillagerInteractionVisuals.mainHandVisualForInteract(vill.getMainHandItem());
            if (visual.isEmpty()) {
                VillagerBrain.signalSwing(vill, InteractionHand.MAIN_HAND, why);
                return;
            }
            VillagerBrain.triggerManualPlantAnimation(vill, visual, 10);
        } catch (Throwable ignored) {}
    }

    private void tryCloseChestIfOpen() {
        try {
            if (!chestOpened) return;
            if (!(vill.level() instanceof ServerLevel level)) return;
            if (chestOpenedPos == null) return;

            var state = level.getBlockState(chestOpenedPos);
            if (state != null) {
                try { level.blockEvent(chestOpenedPos, state.getBlock(), 1, 0); } catch (Throwable ignored) {}
            }

            try {
                if (chestOpenedEnder) {
                    level.playSound(null, chestOpenedPos, SoundEvents.ENDER_CHEST_CLOSE, SoundSource.BLOCKS, 0.5f, 1.0f);
                } else {
                    level.playSound(null, chestOpenedPos, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5f, 1.0f);
                }
            } catch (Throwable ignored) {}

            chestOpened = false;
            chestOpenedPos = null;
            chestOpenedEnder = false;
            chestOpenedAtGameTime = 0L;
        } catch (Throwable ignored) {}
    }

    private void transferVillagerToContainer(net.minecraft.world.Container cont, java.util.List<CustomCommandsService.ItemCountRule> rules) {
        try {
            if (rules == null || rules.isEmpty()) return;
            var inv = vill.getInventory();
            for (CustomCommandsService.ItemCountRule r : rules) {
                if (r == null) continue;
                String id = r.itemId();
                if (id == null || id.isBlank()) continue;
                int remainingToMove = Math.max(0, r.count());
                if (remainingToMove <= 0) continue;

                for (int i = 0; i < inv.getContainerSize() && remainingToMove > 0; i++) {
                    ItemStack stack = inv.getItem(i);
                    if (stack == null || stack.isEmpty()) continue;
                    if (!id.equals(String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())))) continue;

                    int mv = Math.min(remainingToMove, stack.getCount());
                    ItemStack toMove = stack.copy();
                    toMove.setCount(mv);
                    ItemStack rem = moveIntoContainer(cont, toMove);
                    int moved = mv - (rem == null ? 0 : rem.getCount());
                    if (moved > 0) {
                        stack.shrink(moved);
                        inv.setItem(i, stack);
                        remainingToMove -= moved;
                    }
                }
            }
            cont.setChanged();
        } catch (Throwable ignored) {}
    }

    private void transferContainerToVillager(net.minecraft.world.Container cont, java.util.List<CustomCommandsService.ItemCountRule> rules) {
        try {
            if (rules == null || rules.isEmpty()) return;
            var inv = vill.getInventory();
            for (CustomCommandsService.ItemCountRule r : rules) {
                if (r == null) continue;
                String id = r.itemId();
                if (id == null || id.isBlank()) continue;
                int remainingToMove = Math.max(0, r.count());
                if (remainingToMove <= 0) continue;

                for (int i = 0; i < cont.getContainerSize() && remainingToMove > 0; i++) {
                    ItemStack stack = cont.getItem(i);
                    if (stack == null || stack.isEmpty()) continue;
                    if (!id.equals(String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())))) continue;

                    int mv = Math.min(remainingToMove, stack.getCount());
                    ItemStack toMove = stack.copy();
                    toMove.setCount(mv);
                    ItemStack rem = moveIntoVillager(inv, toMove);
                    int moved = mv - (rem == null ? 0 : rem.getCount());
                    if (moved > 0) {
                        stack.shrink(moved);
                        cont.setItem(i, stack);
                        remainingToMove -= moved;
                    }
                }
            }
            cont.setChanged();
        } catch (Throwable ignored) {}
    }

    private ItemStack moveIntoContainer(net.minecraft.world.Container cont, ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;

            // merge into existing stacks first
            for (int i = 0; i < cont.getContainerSize(); i++) {
                ItemStack cur = cont.getItem(i);
                if (cur == null || cur.isEmpty()) continue;
                if (!ItemStack.isSameItemSameComponents(cur, stack)) continue;
                int can = Math.min(cur.getMaxStackSize(), cont.getMaxStackSize()) - cur.getCount();
                if (can <= 0) continue;
                int mv = Math.min(can, stack.getCount());
                cur.grow(mv);
                stack.shrink(mv);
                cont.setItem(i, cur);
                if (stack.isEmpty()) return ItemStack.EMPTY;
            }

            // place into empty slots
            for (int i = 0; i < cont.getContainerSize(); i++) {
                ItemStack cur = cont.getItem(i);
                if (cur != null && !cur.isEmpty()) continue;
                int mv = Math.min(stack.getCount(), Math.min(stack.getMaxStackSize(), cont.getMaxStackSize()));
                ItemStack placed = stack.copy();
                placed.setCount(mv);
                cont.setItem(i, placed);
                stack.shrink(mv);
                if (stack.isEmpty()) return ItemStack.EMPTY;
            }
        } catch (Throwable ignored) {}
        return stack;
    }

    private ItemStack moveIntoVillager(net.minecraft.world.Container inv, ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;

            // merge into existing stacks first
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack cur = inv.getItem(i);
                if (cur == null || cur.isEmpty()) continue;
                if (!ItemStack.isSameItemSameComponents(cur, stack)) continue;
                int can = cur.getMaxStackSize() - cur.getCount();
                if (can <= 0) continue;
                int mv = Math.min(can, stack.getCount());
                cur.grow(mv);
                stack.shrink(mv);
                inv.setItem(i, cur);
                if (stack.isEmpty()) return ItemStack.EMPTY;
            }

            // place into empty slots
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack cur = inv.getItem(i);
                if (cur != null && !cur.isEmpty()) continue;
                int mv = Math.min(stack.getCount(), stack.getMaxStackSize());
                ItemStack placed = stack.copy();
                placed.setCount(mv);
                inv.setItem(i, placed);
                stack.shrink(mv);
                if (stack.isEmpty()) return ItemStack.EMPTY;
            }
        } catch (Throwable ignored) {}
        return stack;
    }
}
