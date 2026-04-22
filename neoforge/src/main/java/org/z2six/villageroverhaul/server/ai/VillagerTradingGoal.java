package org.z2six.villageroverhaul.server.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.block.entity.TradingHallBlockEntity;
import org.z2six.villageroverhaul.server.FarmingSettingsService;
import org.z2six.villageroverhaul.server.TradingHallService;
import org.z2six.villageroverhaul.server.VillagerXpService;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

public final class VillagerTradingGoal extends Goal {

    private static final double WORKSTATION_SPEED = 0.45D;
    private static final double HALL_SPEED = 0.50D;
    private static final int HALL_WAIT_TICKS = 60;
    private static final int NAV_RECALC_TICKS = 10;
    private static final int HALL_ABORT_TICKS = 20 * 30;
    private static final int TRAVEL_UNREACHABLE_TIMEOUT_TICKS = 160;
    private static final int TRAVEL_STUCK_CHECK_EVERY_TICKS = 10;
    private static final double TRAVEL_STUCK_MOVE_EPS = 0.05D;
    private static final double TRAVEL_STUCK_MOVE_EPS_SQR = TRAVEL_STUCK_MOVE_EPS * TRAVEL_STUCK_MOVE_EPS;
    private static final double HALL_STAND_ARRIVAL_DISTANCE_SQR = 2.0D;
    private static final double RETURN_RESUME_ARRIVAL_DISTANCE_SQR = 0.25D;
    private static final double FINAL_APPROACH_ASSIST_RADIUS = 2.25D;
    private static final double FINAL_APPROACH_ASSIST_RADIUS_SQR = FINAL_APPROACH_ASSIST_RADIUS * FINAL_APPROACH_ASSIST_RADIUS;
    private static final double FINAL_APPROACH_ASSIST_SPEED = 0.65D;
    private static final double FINAL_APPROACH_ASSIST_PUSH_PER_TICK = 0.18D;
    private static final double FINAL_APPROACH_ASSIST_MIN_VEL_SQR = 0.0006D;
    private static final int IDLE_LOOK_INTERVAL_MIN = 20;
    private static final int IDLE_LOOK_INTERVAL_SPREAD = 40;
    private static final int IDLE_LOOK_HOLD_MIN = 15;
    private static final int IDLE_LOOK_HOLD_SPREAD = 20;
    private static final double WORKSTATION_STATION_RADIUS_SQR = 4.0D;
    private static final double PASSAGE_NEAR_DISTANCE_SQR = 4.0D;
    private static final int PASSAGE_SCAN_RADIUS_HORIZONTAL = 3;
    private static final int PASSAGE_SCAN_RADIUS_VERTICAL = 1;
    private static final int RESTOCK_WINDOW_START_TOD = 5000;
    private static final int RESTOCK_WINDOW_END_TOD = 8000;
    private static final int DAYTIME_ACTIVITY_START_TOD = 2000;
    private static final int DAYTIME_ACTIVITY_END_TOD = 12000;
    private static final int RANDOM_HALL_CHECK_INTERVAL_TICKS = 2200;

    private final Villager vill;

    private enum Phase {
        WORKSTATION,
        GO_TO_HALL,
        WAIT_AT_HALL,
        RETURN_FROM_HALL
    }

    private Phase phase = Phase.WORKSTATION;
    private int navCooldown;
    private int hallTimeoutTicks;
    private long hallWaitUntilGameTime;
    private Vec3 hallTripResumePos;
    private double travelLastTargetDistSqr;
    private int idleLookCooldown;
    private int idleLookHoldTicks;
    private Vec3 idleLookTarget;
    private final Set<BlockPos> openedPassages = new HashSet<>();
    private int travelStuckCheckCooldown;
    private int travelStuckTicks;

    public VillagerTradingGoal(Villager vill) {
        this.vill = vill;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (vill == null) return false;
        if (vill.level().isClientSide()) return false;
        if (!(vill.level() instanceof ServerLevel)) return false;
        if (VillagerBrain.getMode(vill) != VillagerBrain.Mode.TRADING) return false;
        if (VillagerBrain.isStorageActive(vill)) return false;
        if (VillagerBrain.isManualFarmingActive(vill)) return false;
        return !VillagerBrain.isCombatEngaged(vill);
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        phase = Phase.WORKSTATION;
        navCooldown = 0;
        hallTimeoutTicks = 0;
        hallWaitUntilGameTime = 0L;
        hallTripResumePos = null;
        idleLookCooldown = 0;
        idleLookHoldTicks = 0;
        idleLookTarget = null;
        openedPassages.clear();
        resetTravelProgress();
    }

    @Override
    public void stop() {
        try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
        closeTrackedPassages();
        phase = Phase.WORKSTATION;
        hallTimeoutTicks = 0;
        hallWaitUntilGameTime = 0L;
        hallTripResumePos = null;
        idleLookCooldown = 0;
        idleLookHoldTicks = 0;
        idleLookTarget = null;
        openedPassages.clear();
        resetTravelProgress();
    }

    @Override
    public void tick() {
        try {
            if (!(vill.level() instanceof ServerLevel level)) return;
            if (VillagerBrain.isUiPaused(vill)) {
                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
                tickUiLookTarget();
                return;
            }

            if (!isTradingActiveTime(level)) {
                tickNightState(level);
                return;
            }

            maybeRunAmbientHallCheck(level);
            maybeRunDailyTradingCycle(level);

            TradingHallBlockEntity hall = TradingHallService.getResolvedHall(level, vill);
            if ((phase == Phase.GO_TO_HALL || phase == Phase.WAIT_AT_HALL) && hall == null) {
                finishHallTrip();
            }

            if (phase == Phase.GO_TO_HALL || phase == Phase.WAIT_AT_HALL) {
                tickHallPhase(level, hall);
                return;
            }

            if (phase == Phase.RETURN_FROM_HALL) {
                tickReturnPhase(level);
                return;
            }

            tickWorkstationPhase(level);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerTradingGoal tick failed (soft): {}", t.toString());
        }
    }

    private void maybeRunDailyTradingCycle(ServerLevel level) {
        try {
            long day = Math.max(0L, level.getDayTime() / 24000L);
            int timeOfDay = (int) Math.floorMod(level.getDayTime(), 24000L);
            long last = TradingHallService.getLastDailyCycleDay(vill);
            if (last >= day) return;

            int scheduledDayTime = ensureTodayRestockSchedule(day);
            if (timeOfDay < scheduledDayTime && timeOfDay <= RESTOCK_WINDOW_END_TOD) return;

            TradingHallService.setLastDailyCycleDay(vill, day);
            restockOffers();

            TradingHallBlockEntity hall = TradingHallService.getResolvedHall(level, vill);
            if (hall != null && hasAnyPurchasableHallTrade(hall)) {
                beginHallTrip();
            }
        } catch (Throwable ignored) {}
    }

    private int ensureTodayRestockSchedule(long day) {
        try {
            long scheduledDay = TradingHallService.getRestockScheduledDay(vill);
            int scheduledTod = TradingHallService.getRestockTimeOfDay(vill);
            if (scheduledDay == day && scheduledTod >= RESTOCK_WINDOW_START_TOD && scheduledTod <= RESTOCK_WINDOW_END_TOD) {
                return scheduledTod;
            }

            int span = Math.max(0, RESTOCK_WINDOW_END_TOD - RESTOCK_WINDOW_START_TOD);
            int next = RESTOCK_WINDOW_START_TOD + (span <= 0 ? 0 : vill.getRandom().nextInt(span + 1));
            TradingHallService.setRestockScheduledDay(vill, day);
            TradingHallService.setRestockTimeOfDay(vill, next);
            return next;
        } catch (Throwable ignored) {
            return RESTOCK_WINDOW_START_TOD;
        }
    }

    private void maybeRunAmbientHallCheck(ServerLevel level) {
        try {
            if (phase != Phase.WORKSTATION) return;
            TradingHallBlockEntity hall = TradingHallService.getResolvedHall(level, vill);
            if (hall == null) return;

            if (vill.getRandom().nextInt(RANDOM_HALL_CHECK_INTERVAL_TICKS) != 0) return;
            beginHallTrip();
        } catch (Throwable ignored) {}
    }

    private void tickHallPhase(ServerLevel level, TradingHallBlockEntity hall) {
        if (hall == null) {
            phase = Phase.WORKSTATION;
            return;
        }

        BlockPos standPos = findStandableAdjacent(level, hall.getBlockPos());
        if (standPos == null) standPos = hall.getBlockPos().relative(Direction.SOUTH);

        hallTimeoutTicks++;
        if (hallTimeoutTicks >= HALL_ABORT_TICKS) {
            recoverFromFailedHallTravel("hall_timeout");
            return;
        }

        Vec3 standCenter = Vec3.atBottomCenterOf(standPos);
        double distToStandSqr = vill.position().distanceToSqr(standCenter);

        if (phase == Phase.GO_TO_HALL) {
            boolean openedPassage = openNearbyWoodenPassages(level, standCenter);
            try {
                vill.getLookControl().setLookAt(hall.getBlockPos().getX() + 0.5D, hall.getBlockPos().getY() + 0.5D, hall.getBlockPos().getZ() + 0.5D, 30.0F, 30.0F);
            } catch (Throwable ignored) {}

            if (tryFinalizeArrival(standCenter, HALL_STAND_ARRIVAL_DISTANCE_SQR) || isInTargetBlock(standCenter) || distToStandSqr <= HALL_STAND_ARRIVAL_DISTANCE_SQR) {
                phase = Phase.WAIT_AT_HALL;
                hallWaitUntilGameTime = level.getGameTime() + HALL_WAIT_TICKS;
                resetTravelProgress();
                return;
            }

            if (tickTravelStuckRecovery(level, "go_to_hall")) return;

            tickTravelNavigation(standCenter, HALL_SPEED, HALL_STAND_ARRIVAL_DISTANCE_SQR, openedPassage);
            return;
        }

        try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
        try {
            vill.getLookControl().setLookAt(hall.getBlockPos().getX() + 0.5D, hall.getBlockPos().getY() + 0.5D, hall.getBlockPos().getZ() + 0.5D, 30.0F, 30.0F);
        } catch (Throwable ignored) {}

        if (level.getGameTime() < hallWaitUntilGameTime) return;

        processTradingHallPurchases(hall);
        phase = Phase.RETURN_FROM_HALL;
        hallTimeoutTicks = 0;
        hallWaitUntilGameTime = 0L;
        resetTravelProgress();
    }

    private void tickReturnPhase(ServerLevel level) {
        hallTimeoutTicks++;
        if (hallTimeoutTicks >= HALL_ABORT_TICKS) {
            recoverFromFailedHallTravel("return_timeout");
            return;
        }

        Vec3 returnDest = hallTripResumePos;
        if (returnDest == null) {
            FarmingSettingsService.RegisteredWorkstation ws = FarmingSettingsService.getVanillaJobSiteWorkstation(level, vill);
            if (ws != null) {
                returnDest = new Vec3(ws.x() + 0.5D, ws.y() + 0.5D, ws.z() + 0.5D);
            } else {
                finishHallTrip();
                return;
            }
        }

        boolean openedPassage = openNearbyWoodenPassages(level, returnDest);

        if (tryFinalizeArrival(returnDest, RETURN_RESUME_ARRIVAL_DISTANCE_SQR) || vill.position().distanceToSqr(returnDest) <= RETURN_RESUME_ARRIVAL_DISTANCE_SQR) {
            finishHallTrip();
            return;
        }

        if (tickTravelStuckRecovery(level, "return_from_hall")) return;

        tickTravelNavigation(returnDest, WORKSTATION_SPEED, RETURN_RESUME_ARRIVAL_DISTANCE_SQR, openedPassage);
        try {
            vill.getLookControl().setLookAt(returnDest.x, returnDest.y, returnDest.z, 30.0F, 30.0F);
        } catch (Throwable ignored) {}
    }

    private void tickWorkstationPhase(ServerLevel level) {
        FarmingSettingsService.RegisteredWorkstation ws = FarmingSettingsService.getVanillaJobSiteWorkstation(level, vill);
        if (ws == null) {
            try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
            tickIdleLook();
            return;
        }

        BlockPos workstationPos = new BlockPos(ws.x(), ws.y(), ws.z());
        BlockPos anchorPos = findStandableAdjacent(level, workstationPos);
        if (anchorPos == null) anchorPos = workstationPos.relative(Direction.SOUTH);
        Vec3 anchor = Vec3.atBottomCenterOf(anchorPos);

        if (vill.position().distanceToSqr(anchor) > WORKSTATION_STATION_RADIUS_SQR) {
            moveTo(anchorPos, WORKSTATION_SPEED);
            return;
        }

        try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
        tickIdleLook();
    }

    private void tickNightState(ServerLevel level) {
        if (phase == Phase.GO_TO_HALL || phase == Phase.WAIT_AT_HALL) {
            phase = Phase.RETURN_FROM_HALL;
            hallTimeoutTicks = 0;
            hallWaitUntilGameTime = 0L;
            resetTravelProgress();
        }

        if (phase == Phase.RETURN_FROM_HALL) {
            tickReturnPhase(level);
            return;
        }

        try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
        closeTrackedPassages();
    }

    private void moveTo(BlockPos pos, double speed) {
        if (pos == null) return;
        idleLookHoldTicks = 0;
        idleLookTarget = null;
        if (navCooldown-- <= 0) {
            navCooldown = NAV_RECALC_TICKS;
            Vec3 target = Vec3.atBottomCenterOf(pos);
            vill.getNavigation().moveTo(target.x, target.y, target.z, speed);
        }
        try {
            vill.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 30.0F, 30.0F);
        } catch (Throwable ignored) {}
    }

    private void tickIdleLook() {
        try {
            if (idleLookHoldTicks > 0 && idleLookTarget != null) {
                idleLookHoldTicks--;
                vill.getLookControl().setLookAt(idleLookTarget.x, idleLookTarget.y, idleLookTarget.z, 20.0F, 20.0F);
                return;
            }

            if (idleLookCooldown-- > 0) return;
            idleLookCooldown = IDLE_LOOK_INTERVAL_MIN + vill.getRandom().nextInt(IDLE_LOOK_INTERVAL_SPREAD);
            double yawRad = vill.getRandom().nextDouble() * (Math.PI * 2.0D);
            double dist = 2.0D + vill.getRandom().nextDouble() * 2.0D;
            double ox = Math.cos(yawRad) * dist;
            double oz = Math.sin(yawRad) * dist;
            double eyeY = vill.getEyeY() - 0.1D;
            idleLookTarget = new Vec3(vill.getX() + ox, eyeY, vill.getZ() + oz);
            idleLookHoldTicks = IDLE_LOOK_HOLD_MIN + vill.getRandom().nextInt(IDLE_LOOK_HOLD_SPREAD);
            vill.getLookControl().setLookAt(idleLookTarget.x, idleLookTarget.y, idleLookTarget.z, 20.0F, 20.0F);
        } catch (Throwable ignored) {}
    }

    private BlockPos findStandableAdjacent(ServerLevel level, BlockPos pos) {
        if (pos == null) return null;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = pos.relative(dir);
            BlockPos stand = findStandableSpot(level, candidate);
            if (stand != null) return stand;
        }
        return null;
    }

    private BlockPos findStandableSpot(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return null;
        BlockPos below = pos.below();
        BlockState state = level.getBlockState(pos);
        BlockState stateAbove = level.getBlockState(pos.above());
        BlockState belowState = level.getBlockState(below);
        boolean freeHere = state.isAir() || state.canBeReplaced();
        boolean freeAbove = stateAbove.isAir() || stateAbove.canBeReplaced();
        boolean solidBelow = belowState.isFaceSturdy(level, below, Direction.UP);
        return freeHere && freeAbove && solidBelow ? pos : null;
    }

    private void restockOffers() {
        try {
            if (vill.getOffers() == null) return;
            for (MerchantOffer offer : vill.getOffers()) {
                if (offer == null) continue;
                try { offer.resetUses(); } catch (Throwable ignored) {}
            }
            playRestockFx();
        } catch (Throwable ignored) {}
    }

    private void playRestockFx() {
        try {
            if (!(vill.level() instanceof ServerLevel level)) return;
            double x = vill.getX();
            double y = vill.getY() + 1.0D;
            double z = vill.getZ();
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, x, y, z, 10, 0.35D, 0.45D, 0.35D, 0.03D);
            level.playSound(null, x, y, z, SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.7F, 1.0F);
        } catch (Throwable ignored) {}
    }

    private boolean hasAnyPurchasableHallTrade(Container hall) {
        try {
            if (hall == null || vill.getOffers() == null) return false;
            for (MerchantOffer offer : vill.getOffers()) {
                if (computeMaxHallTrades(hall, offer) > 0) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void processTradingHallPurchases(Container hall) {
        try {
            if (hall == null || vill.getOffers() == null) return;
            int totalXp = 0;
            for (MerchantOffer offer : vill.getOffers()) {
                if (offer == null) continue;
                int trades = computeMaxHallTrades(hall, offer);
                if (trades <= 0) continue;

                ItemStack costA = safeCopy(offer.getCostA());
                ItemStack costB = safeCopy(offer.getCostB());
                ItemStack result = safeCopy(offer.getResult());
                if (result.isEmpty() || !result.is(Items.EMERALD)) continue;

                for (int i = 0; i < trades; i++) {
                    if (!removeItems(hall, costA)) break;
                    if (!costB.isEmpty() && !removeItems(hall, costB)) {
                        insertItem(hall, costA.copy());
                        break;
                    }
                    if (!insertItem(hall, result.copy())) {
                        insertItem(hall, costA.copy());
                        if (!costB.isEmpty()) insertItem(hall, costB.copy());
                        break;
                    }
                    try { offer.increaseUses(); } catch (Throwable ignored) {}
                    totalXp += safeOfferXp(offer);
                }
            }

            if (totalXp > 0) {
                VillagerXpService.grantXp(vill, totalXp, null);
                try { hall.setChanged(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] Trading hall purchase processing failed (soft): {}", t.toString());
        }
    }

    private void beginHallTrip() {
        phase = Phase.GO_TO_HALL;
        hallTimeoutTicks = 0;
        hallWaitUntilGameTime = 0L;
        hallTripResumePos = Vec3.atBottomCenterOf(vill.blockPosition());
        idleLookCooldown = 0;
        idleLookHoldTicks = 0;
        idleLookTarget = null;
        openedPassages.clear();
        resetTravelProgress();
    }

    private void finishHallTrip() {
        phase = Phase.WORKSTATION;
        hallTimeoutTicks = 0;
        hallWaitUntilGameTime = 0L;
        hallTripResumePos = null;
        idleLookCooldown = 0;
        idleLookHoldTicks = 0;
        idleLookTarget = null;
        closeTrackedPassages();
        openedPassages.clear();
        resetTravelProgress();
    }

    private void resetTravelProgress() {
        travelStuckCheckCooldown = 0;
        travelStuckTicks = 0;
        travelLastTargetDistSqr = Double.POSITIVE_INFINITY;
    }

    private boolean tickTravelStuckRecovery(ServerLevel level, String reason) {
        try {
            Vec3 target = getActiveTravelTarget(level);
            if (target == null) return false;

            if (travelStuckCheckCooldown > 0) {
                travelStuckCheckCooldown--;
                return false;
            }
            travelStuckCheckCooldown = TRAVEL_STUCK_CHECK_EVERY_TICKS;

            double curDistSqr = vill.position().distanceToSqr(target);
            if (!Double.isFinite(travelLastTargetDistSqr) || travelLastTargetDistSqr == Double.POSITIVE_INFINITY) {
                travelLastTargetDistSqr = curDistSqr;
                travelStuckTicks = 0;
                return false;
            }

            double improvement = travelLastTargetDistSqr - curDistSqr;
            travelLastTargetDistSqr = curDistSqr;
            if (improvement <= TRAVEL_STUCK_MOVE_EPS_SQR) travelStuckTicks += TRAVEL_STUCK_CHECK_EVERY_TICKS;
            else travelStuckTicks = 0;

            if (travelStuckTicks < TRAVEL_UNREACHABLE_TIMEOUT_TICKS) return false;

            recoverFromFailedHallTravel(reason);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Vec3 getActiveTravelTarget(ServerLevel level) {
        try {
            if (phase == Phase.GO_TO_HALL) {
                TradingHallBlockEntity hall = TradingHallService.getResolvedHall(level, vill);
                if (hall == null) return null;
                BlockPos standPos = findStandableAdjacent(level, hall.getBlockPos());
                if (standPos == null) standPos = hall.getBlockPos().relative(Direction.SOUTH);
                return Vec3.atBottomCenterOf(standPos);
            }
            if (phase == Phase.RETURN_FROM_HALL) {
                return hallTripResumePos;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void tickTravelNavigation(Vec3 target, double speed, double arrivalDistanceSqr, boolean openedPassage) {
        try {
            if (target == null) return;
            idleLookHoldTicks = 0;
            idleLookTarget = null;
            if (vill.position().distanceToSqr(target) <= FINAL_APPROACH_ASSIST_RADIUS_SQR) {
                applyFinalApproachAssist(target);
            }

            boolean forceRepath = openedPassage || shouldForceTravelRepath(target, arrivalDistanceSqr);
            if (!forceRepath && navCooldown-- > 0) return;

            navCooldown = NAV_RECALC_TICKS;
            try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
            vill.getNavigation().moveTo(target.x, target.y, target.z, speed);
        } catch (Throwable ignored) {}
    }

    private boolean shouldForceTravelRepath(Vec3 target, double arrivalDistanceSqr) {
        try {
            if (target == null) return false;
            if (vill.position().distanceToSqr(target) <= arrivalDistanceSqr) return false;

            PathNavigation nav = vill.getNavigation();
            if (nav == null) return false;
            if (nav.getPath() == null || nav.isDone()) return true;
            return isNearTrackedPassage();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void applyFinalApproachAssist(Vec3 target) {
        try {
            if (target == null) return;
            vill.getMoveControl().setWantedPosition(target.x, target.y, target.z, FINAL_APPROACH_ASSIST_SPEED);

            Vec3 pos = vill.position();
            Vec3 vel = vill.getDeltaMovement();
            double hv2 = vel.x * vel.x + vel.z * vel.z;
            if (hv2 >= FINAL_APPROACH_ASSIST_MIN_VEL_SQR) return;

            double dx = target.x - pos.x;
            double dz = target.z - pos.z;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1.0e-4D) return;

            double px = (dx / len) * FINAL_APPROACH_ASSIST_PUSH_PER_TICK;
            double pz = (dz / len) * FINAL_APPROACH_ASSIST_PUSH_PER_TICK;
            double nx = vel.x * 0.35D + px;
            double nz = vel.z * 0.35D + pz;
            vill.setDeltaMovement(nx, vel.y, nz);
        } catch (Throwable ignored) {}
    }

    private boolean isInTargetBlock(Vec3 target) {
        try {
            if (target == null) return false;
            BlockPos tp = BlockPos.containing(target.x, target.y, target.z);
            BlockPos vp = vill.blockPosition();
            return vp.getX() == tp.getX() && vp.getZ() == tp.getZ() && Math.abs(vp.getY() - tp.getY()) <= 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean tryFinalizeArrival(Vec3 target, double arrivalDistanceSqr) {
        try {
            if (target == null) return false;
            double distSqr = vill.position().distanceToSqr(target);
            if (distSqr <= arrivalDistanceSqr) {
                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
                return true;
            }

            if (!isInTargetBlock(target)) return false;

            applyFinalApproachAssist(target);
            if (distSqr > 0.64D) return false;

            try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
            try { vill.moveTo(target.x, target.y, target.z, vill.getYRot(), vill.getXRot()); } catch (Throwable ignored) {}
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isNearTrackedPassage() {
        try {
            for (BlockPos pos : openedPassages) {
                if (vill.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= PASSAGE_NEAR_DISTANCE_SQR) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void recoverFromFailedHallTravel(String reason) {
        try { vill.getNavigation().stop(); } catch (Throwable ignored) {}

        Vec3 resumeDest = hallTripResumePos;
        boolean hasPearl = false;
        boolean teleported = false;
        try {
            hasPearl = VillagerCombatDirector.hasResumeEnderPearl(vill);
        } catch (Throwable ignored) {
            hasPearl = false;
        }

        try {
            if (hasPearl) {
                teleported = VillagerCombatDirector.tryUseResumeEnderPearl(vill, resumeDest, "trading_" + reason);
            }
        } catch (Throwable ignored) {
            teleported = false;
        }

        finishHallTrip();

        if (!teleported) {
            VillagerBrain.idle(vill);
        }
    }

    private int computeMaxHallTrades(Container hall, MerchantOffer offer) {
        try {
            if (hall == null || offer == null) return 0;
            if (offer.isOutOfStock()) return 0;

            ItemStack costA = safeCopy(offer.getCostA());
            ItemStack costB = safeCopy(offer.getCostB());
            ItemStack result = safeCopy(offer.getResult());
            if (result.isEmpty() || !result.is(Items.EMERALD)) return 0;
            if (costA.isEmpty()) return 0;
            if (costA.is(Items.EMERALD) || (!costB.isEmpty() && costB.is(Items.EMERALD))) return 0;

            int remainingUses = Math.max(0, safeMaxUses(offer) - safeUses(offer));
            if (remainingUses <= 0) return 0;

            int byA = countItem(hall, costA) / Math.max(1, costA.getCount());
            int maxTrades = Math.min(remainingUses, byA);
            if (!costB.isEmpty()) {
                int byB = countItem(hall, costB) / Math.max(1, costB.getCount());
                maxTrades = Math.min(maxTrades, byB);
            }

            int emeraldCapacity = getInsertCapacity(hall, result);
            maxTrades = Math.min(maxTrades, emeraldCapacity / Math.max(1, result.getCount()));
            return Math.max(0, maxTrades);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private int countItem(Container hall, ItemStack template) {
        int count = 0;
        for (int i = 0; i < hall.getContainerSize(); i++) {
            ItemStack stack = hall.getItem(i);
            if (!ItemStack.isSameItemSameComponents(stack, template)) continue;
            count += stack.getCount();
        }
        return count;
    }

    private boolean removeItems(Container hall, ItemStack template) {
        int remaining = Math.max(1, template.getCount());
        for (int i = 0; i < hall.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = hall.getItem(i);
            if (!ItemStack.isSameItemSameComponents(stack, template)) continue;
            int taken = Math.min(remaining, stack.getCount());
            stack.shrink(taken);
            if (stack.isEmpty()) hall.setItem(i, ItemStack.EMPTY);
            else hall.setItem(i, stack);
            remaining -= taken;
        }
        hall.setChanged();
        return remaining <= 0;
    }

    private boolean insertItem(Container hall, ItemStack stack) {
        if (stack.isEmpty()) return true;

        for (int i = 0; i < hall.getContainerSize(); i++) {
            ItemStack cur = hall.getItem(i);
            if (cur.isEmpty()) continue;
            if (!ItemStack.isSameItemSameComponents(cur, stack)) continue;
            int limit = Math.min(hall.getMaxStackSize(), cur.getMaxStackSize());
            int space = limit - cur.getCount();
            if (space <= 0) continue;
            int move = Math.min(space, stack.getCount());
            cur.grow(move);
            stack.shrink(move);
            hall.setItem(i, cur);
            if (stack.isEmpty()) {
                hall.setChanged();
                return true;
            }
        }

        for (int i = 0; i < hall.getContainerSize(); i++) {
            ItemStack cur = hall.getItem(i);
            if (!cur.isEmpty()) continue;
            int limit = Math.min(hall.getMaxStackSize(), stack.getMaxStackSize());
            ItemStack placed = stack.copyWithCount(Math.min(limit, stack.getCount()));
            hall.setItem(i, placed);
            stack.shrink(placed.getCount());
            if (stack.isEmpty()) {
                hall.setChanged();
                return true;
            }
        }

        hall.setChanged();
        return stack.isEmpty();
    }

    private int getInsertCapacity(Container hall, ItemStack template) {
        int capacity = 0;
        for (int i = 0; i < hall.getContainerSize(); i++) {
            ItemStack cur = hall.getItem(i);
            if (cur.isEmpty()) {
                capacity += Math.min(hall.getMaxStackSize(), template.getMaxStackSize());
                continue;
            }
            if (!ItemStack.isSameItemSameComponents(cur, template)) continue;
            int limit = Math.min(hall.getMaxStackSize(), cur.getMaxStackSize());
            capacity += Math.max(0, limit - cur.getCount());
        }
        return capacity;
    }

    private boolean openNearbyWoodenPassages(ServerLevel level, Vec3 travelTarget) {
        try {
            if (level == null || travelTarget == null) return false;

            Vec3 villPos = vill.position();
            double dirX = travelTarget.x - villPos.x;
            double dirZ = travelTarget.z - villPos.z;
            double dirLen = Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (dirLen < 1.0e-4D) return false;
            dirX /= dirLen;
            dirZ /= dirLen;

            BlockPos base = vill.blockPosition();
            BlockPos bestPos = null;
            BlockState bestState = null;
            double bestScore = Double.POSITIVE_INFINITY;

            for (BlockPos pos : BlockPos.betweenClosed(
                    base.offset(-PASSAGE_SCAN_RADIUS_HORIZONTAL, -PASSAGE_SCAN_RADIUS_VERTICAL, -PASSAGE_SCAN_RADIUS_HORIZONTAL),
                    base.offset(PASSAGE_SCAN_RADIUS_HORIZONTAL, PASSAGE_SCAN_RADIUS_VERTICAL, PASSAGE_SCAN_RADIUS_HORIZONTAL))) {
                BlockState state = level.getBlockState(pos);
                if (!isOpenableWoodenPassage(state)) continue;
                if (state.hasProperty(BlockStateProperties.OPEN) && Boolean.TRUE.equals(state.getValue(BlockStateProperties.OPEN))) continue;

                Vec3 center = Vec3.atCenterOf(pos);
                double relX = center.x - villPos.x;
                double relZ = center.z - villPos.z;
                double forward = relX * dirX + relZ * dirZ;
                if (forward < 0.1D || forward > 2.6D) continue;

                double perpX = relX - (dirX * forward);
                double perpZ = relZ - (dirZ * forward);
                double lateral = Math.sqrt(perpX * perpX + perpZ * perpZ);
                if (lateral > 1.15D) continue;

                double score = (lateral * 4.0D) + forward + Math.abs(center.y - villPos.y);
                if (score < bestScore) {
                    bestScore = score;
                    bestPos = pos.immutable();
                    bestState = state;
                }
            }

            if (bestPos == null || bestState == null) return false;
            return tryOpen(level, bestPos, bestState);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isOpenableWoodenPassage(BlockState state) {
        try {
            if (state == null) return false;
            if (state.getBlock() instanceof FenceGateBlock) return true;
            return state.getBlock() instanceof DoorBlock && state.is(BlockTags.WOODEN_DOORS);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean tryOpen(ServerLevel level, BlockPos pos, BlockState state) {
        try {
            if (!state.hasProperty(BlockStateProperties.OPEN)) return false;
            if (Boolean.TRUE.equals(state.getValue(BlockStateProperties.OPEN))) return false;
            BlockPos key = pos.immutable();
            level.setBlock(key, state.setValue(BlockStateProperties.OPEN, true), 3);
            openedPassages.add(key);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isTradingActiveTime(ServerLevel level) {
        try {
            if (level == null) return false;
            try { if (vill.isSleeping()) return false; } catch (Throwable ignored) {}
            int tod = (int) Math.floorMod(level.getDayTime(), 24000L);
            return tod >= DAYTIME_ACTIVITY_START_TOD && tod < DAYTIME_ACTIVITY_END_TOD;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void closeTrackedPassages() {
        try {
            if (!(vill.level() instanceof ServerLevel level)) return;
            for (BlockPos pos : openedPassages) {
                closePassage(level, pos);
            }
        } catch (Throwable ignored) {}
    }

    private void closePassage(ServerLevel level, BlockPos pos) {
        try {
            BlockState state = level.getBlockState(pos);
            if (!state.hasProperty(BlockStateProperties.OPEN)) return;
            if (!Boolean.TRUE.equals(state.getValue(BlockStateProperties.OPEN))) return;
            level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, false), 3);
        } catch (Throwable ignored) {}
    }

    private void tickUiLookTarget() {
        try {
            if (vill.getTradingPlayer() != null) {
                vill.getLookControl().setLookAt(vill.getTradingPlayer(), 30.0F, 30.0F);
                return;
            }
        } catch (Throwable ignored) {}
    }

    private static ItemStack safeCopy(ItemStack stack) {
        return stack == null ? ItemStack.EMPTY : stack.copy();
    }

    private static int safeUses(MerchantOffer offer) {
        try { return offer == null ? 0 : Math.max(0, offer.getUses()); } catch (Throwable ignored) { return 0; }
    }

    private static int safeMaxUses(MerchantOffer offer) {
        try { return offer == null ? 0 : Math.max(0, offer.getMaxUses()); } catch (Throwable ignored) { return 0; }
    }

    private static int safeOfferXp(MerchantOffer offer) {
        try { return offer == null ? 0 : Math.max(0, offer.getXp()); } catch (Throwable ignored) { return 0; }
    }
}
