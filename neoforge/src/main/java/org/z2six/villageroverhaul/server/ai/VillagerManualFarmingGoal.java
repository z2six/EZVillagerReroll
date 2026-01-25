package org.z2six.villageroverhaul.server.ai;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.config.ServerConfig;
import org.z2six.villageroverhaul.farming.FarmingSettings;
import org.z2six.villageroverhaul.server.FarmingSettingsService;
import org.z2six.villageroverhaul.server.RecruitService;
import org.z2six.villageroverhaul.logic.VillagerTraitEffects;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Manual farming module:
 * - pickup drops
 * - plant configured seeds/crops
 * - harvest mature crops whose drops include configured items
 *
 * Combat goals (higher priority) can preempt this module.
 */
public final class VillagerManualFarmingGoal extends Goal {

    private static final int SCAN_EVERY_TICKS = 10;

    private static final double SPEED_MULT = 0.5; // 0.5x actual movement speed

    // Patrol-style final approach assist
    private static final double ASSIST_SPEED = 1.0;
    private static final double ASSIST_MIN_VEL_SQR = 0.0025;
    private static final double ASSIST_PUSH_PER_TICK = 0.04;

    private static final String PD_OFFHAND_VIS_UNTIL = "ezvr_manual_farm_offhand_vis_until";
    private static final String PD_OFFHAND_VIS_ITEM = "ezvr_manual_farm_offhand_vis_item";

    private enum Action {
        NONE,
        PICKUP,
        PLANT,
        BONEMEAL,
        HARVEST,
        ROAM
    }

    private final Villager vill;
    private final RandomSource rng;

    private int scanCooldown = 0;
    private int bonemealUseCooldown = 0;
    private long actionStartGameTime = 0L;
    private long lastFailureGameTime = Long.MIN_VALUE;

    private Action action = Action.NONE;

    private int targetItemEntityId = -1;
    private BlockPos targetPos = null;
    private String targetPlantItemId = null;

    private long nextRoamAt = 0L;
    private long nextPlantWhisperAt = 0L;

    public VillagerManualFarmingGoal(Villager vill) {
        this.vill = vill;
        this.rng = vill == null ? RandomSource.create() : vill.getRandom();
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        try {
            if (vill == null) return false;
            if (!(vill.level() instanceof ServerLevel)) return false;
            if (!RecruitService.isRecruited(vill)) return false;
            if (VillagerBrain.isUiPaused(vill)) return false;
            if (VillagerBrain.isStorageActive(vill)) return false;
            return VillagerBrain.isManualFarmingControlling(vill);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        try {
            scanCooldown = 0;
            bonemealUseCooldown = 0;
            action = Action.NONE;
            actionStartGameTime = 0L;
            targetItemEntityId = -1;
            targetPos = null;
            targetPlantItemId = null;
            nextRoamAt = 0L;
            nextPlantWhisperAt = 0L;
        } catch (Throwable ignored) {}
    }

    @Override
    public void stop() {
        try {
            vill.getNavigation().stop();
        } catch (Throwable ignored) {}

        action = Action.NONE;
        bonemealUseCooldown = 0;
        actionStartGameTime = 0L;
        targetItemEntityId = -1;
        targetPos = null;
        targetPlantItemId = null;
    }

    @Override
    public void tick() {
        try {
            if (!(vill.level() instanceof ServerLevel level)) return;

            // Mimic vanilla: do active farming only during the "work window" (configurable),
            // modulated by Motivation.
            boolean doWork = isWorkTime(level);

            FarmingSettings settings = FarmingSettingsService.getSettings(vill);
            int range = FarmingSettingsService.getEffectiveManualFarmingRange(vill);
            boolean circular = settings.manualRangeCircular;
            net.minecraft.world.phys.Vec3 center = resolveWorkCenter(level);

            int timeoutTicks = Math.max(20, Math.max(1, settings.manualTimeoutSeconds) * 20);
            int retryTicks = Math.max(20, Math.max(1, settings.manualRetryAfterSeconds) * 20);

            long now = level.getGameTime();

            tickOffhandVisualRestore(now);
            try { VillagerCombatLoadoutService.enforceNow(vill, "manual_farm_tick"); } catch (Throwable ignored) {}

            // Timeout handling
            if (action != Action.NONE && actionStartGameTime > 0L && (now - actionStartGameTime) > timeoutTicks) {
                markFailure(now);
                clearAction();
            }

            // Failure retry gate
            if (lastFailureGameTime != Long.MIN_VALUE && (now - lastFailureGameTime) < retryTicks) {
                tickRoam(level, center, range, circular);
                return;
            }

            // If it's not work time, just roam around naturally.
            if (!doWork) {
                clearAction();
                tickRoam(level, center, range, circular);
                return;
            }

            // Plant Whisperer: periodic "free bonemeal" nearby (no item consumption).
            tickPlantWhisperer(level, now);

            // Refresh target selection periodically or if current target is invalid.
            if (scanCooldown > 0) scanCooldown--;
            if (scanCooldown <= 0 || !isCurrentTargetStillValid(level, settings)) {
                scanCooldown = SCAN_EVERY_TICKS;
                chooseNextAction(level, settings, center, range, circular);
            }

            switch (action) {
                case PICKUP -> tickPickup(level, settings);
                case PLANT -> tickPlant(level, settings);
                case BONEMEAL -> tickBonemeal(level, settings);
                case HARVEST -> tickHarvest(level, settings);
                case ROAM, NONE -> tickRoam(level, center, range, circular);
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerManualFarmingGoal.tick failed (soft): {}", t.toString());
        }
    }

    private void tickOffhandVisualRestore(long now) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            long until = vill.getPersistentData().getLong(PD_OFFHAND_VIS_UNTIL);
            if (until <= 0L) return;
            if (now < until) return;

            String id = "";
            try { id = vill.getPersistentData().getString(PD_OFFHAND_VIS_ITEM); } catch (Throwable ignored) { id = ""; }

            ItemStack curOff = vill.getOffhandItem();
            boolean stillExpected = false;
            try {
                if (curOff == null || curOff.isEmpty()) {
                    stillExpected = true;
                } else if (id != null && !id.isBlank()) {
                    String curId = "";
                    try { curId = String.valueOf(BuiltInRegistries.ITEM.getKey(curOff.getItem())); } catch (Throwable ignored) { curId = ""; }
                    stillExpected = id.equals(curId);
                }
            } catch (Throwable ignored) { stillExpected = false; }

            if (stillExpected) {
                try { VillagerBrain.notifyManualHandSet(vill, net.minecraft.world.entity.EquipmentSlot.OFFHAND, ItemStack.EMPTY, "manual_farm_offhand_visual_restore"); } catch (Throwable ignored) {}
                vill.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, ItemStack.EMPTY);
            }

            vill.getPersistentData().remove(PD_OFFHAND_VIS_UNTIL);
            vill.getPersistentData().remove(PD_OFFHAND_VIS_ITEM);
        } catch (Throwable ignored) {}
    }

    private void ensureLoadoutMainhandEquipped(String why) {
        try {
            VillagerCombatLoadoutService.enforceNow(vill, why == null ? "manual_farm" : why);
        } catch (Throwable ignored) {}
    }

    private static int clampRange(int r) {
        int v = r;
        if (v <= 0) v = 10;
        if (v < 1) v = 1;
        if (v > 64) v = 64;
        return v;
    }

    private net.minecraft.world.phys.Vec3 resolveWorkCenter(ServerLevel level) {
        try {
            if (level == null) return vill.position();
            var ws = FarmingSettingsService.getEffectiveWorkstation(level, vill);
            if (ws == null) return vill.position();

            String dim = "";
            try { dim = String.valueOf(level.dimension().location()); } catch (Throwable ignored) { dim = ""; }
            if (ws.dimId() == null || ws.dimId().isBlank() || !ws.dimId().equals(dim)) return vill.position();

            return new net.minecraft.world.phys.Vec3(ws.x() + 0.5, ws.y() + 0.5, ws.z() + 0.5);
        } catch (Throwable ignored) {
            return vill.position();
        }
    }

    private boolean isWorkTime(ServerLevel level) {
        try {
            if (level == null) return false;
            try { if (vill.isSleeping()) return false; } catch (Throwable ignored) {}

            int start = ServerConfig.manualFarmWorkStartTick;
            int end = ServerConfig.manualFarmWorkEndTick;

            // Clamp to day range
            if (start < 0) start = 0;
            if (start > 23999) start = 23999;
            if (end < 0) end = 0;
            if (end > 23999) end = 23999;

            int t = 0;
            try {
                long dayTime = level.getDayTime();
                t = (int) (dayTime % 24000L);
                if (t < 0) t += 24000;
            } catch (Throwable ignored) {
                t = 0;
            }

            // Base window length (wrap-safe)
            int len = end >= start ? (end - start) : (24000 - start + end);
            if (len <= 0) return false;
            if (len >= 24000) return true;

            // Motivation expands/contracts the window around midpoint.
            double pct = 0.0;
            try { pct = VillagerTraitEffects.motivationPct(vill); } catch (Throwable ignored) { pct = 0.0; }
            double mult = 1.0 + (pct / 100.0);
            if (Double.isNaN(mult) || Double.isInfinite(mult)) mult = 1.0;
            if (mult < 0.0) mult = 0.0;

            double startD = start;
            double endD = startD + len;
            double mid = (startD + endD) / 2.0;
            double newLen = len * mult;
            if (newLen < 1.0) newLen = 1.0;
            if (newLen > 24000.0) newLen = 24000.0;

            double newStart = mid - newLen / 2.0;
            double newEnd = mid + newLen / 2.0;

            return isTimeInWindow(t, newStart, newEnd);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isTimeInWindow(int timeOfDay, double start, double end) {
        // start/end are in "unwrapped" ticks; window is [start,end) modulo 24000.
        if (timeOfDay < 0) timeOfDay = 0;
        if (timeOfDay > 23999) timeOfDay = 23999;

        double s = start % 24000.0;
        double e = end % 24000.0;
        if (s < 0.0) s += 24000.0;
        if (e < 0.0) e += 24000.0;

        // If window length spans full day, always true.
        double len = end - start;
        if (len >= 24000.0) return true;

        if (s <= e) {
            return timeOfDay >= s && timeOfDay < e;
        } else {
            // Wrapped across 0
            return timeOfDay >= s || timeOfDay < e;
        }
    }

    private void tickPlantWhisperer(ServerLevel level, long nowGameTime) {
        try {
            if (level == null) return;

            int intervalS = Math.max(1, ServerConfig.plantWhispererIntervalSeconds);
            long intervalTicks = (long) intervalS * 20L;
            if (intervalTicks < 1L) intervalTicks = 1L;

            if (nextPlantWhisperAt <= 0L) nextPlantWhisperAt = nowGameTime + intervalTicks;
            if (nowGameTime < nextPlantWhisperAt) return;
            nextPlantWhisperAt = nowGameTime + intervalTicks;

            double baseChance = ServerConfig.plantWhispererBaseChancePct;
            if (Double.isNaN(baseChance) || Double.isInfinite(baseChance)) baseChance = 0.0;
            if (baseChance < 0.0) baseChance = 0.0;
            if (baseChance > 100.0) baseChance = 100.0;

            double pct = 0.0;
            try { pct = VillagerTraitEffects.plantWhispererPct(vill); } catch (Throwable ignored) { pct = 0.0; }
            double mult = 1.0 + (pct / 100.0);
            if (Double.isNaN(mult) || Double.isInfinite(mult)) mult = 1.0;
            if (mult < 0.0) mult = 0.0;

            double chance = baseChance * mult;
            if (chance <= 0.0) return;
            if (chance > 100.0) chance = 100.0;

            if (rng.nextDouble() * 100.0 >= chance) return;

            BlockPos target = findNearbyBonemealable(level, vill.blockPosition(), 3);
            if (target == null) return;

            BlockState st = level.getBlockState(target);
            if (st == null || st.isAir() || !(st.getBlock() instanceof BonemealableBlock bb)) return;
            if (!bb.isValidBonemealTarget(level, target, st)) return;
            if (!bb.isBonemealSuccess(level, level.getRandom(), target, st)) return;

            bb.performBonemeal(level, level.getRandom(), target, st);
            try { level.levelEvent(2005, target, 0); } catch (Throwable ignored) {}
            try {
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                        target.getX() + 0.5, target.getY() + 0.7, target.getZ() + 0.5,
                        6, 0.35, 0.35, 0.35, 0.0);
            } catch (Throwable ignored) {}

            try { org.z2six.villageroverhaul.server.VillagerHistoryService.addFarmingBonemealed(vill, 1, true); } catch (Throwable ignored) {}

        } catch (Throwable ignored) {}
    }

    private static BlockPos findNearbyBonemealable(ServerLevel level, BlockPos origin, int r) {
        try {
            if (level == null || origin == null) return null;
            int rr = Math.max(1, r);
            BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
            BlockPos best = null;
            int bestDist = Integer.MAX_VALUE;

            for (int dx = -rr; dx <= rr; dx++) {
                for (int dz = -rr; dz <= rr; dz++) {
                    for (int dy = -1; dy <= 2; dy++) {
                        mp.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                        BlockState st = level.getBlockState(mp);
                        if (st == null || st.isAir()) continue;
                        if (!(st.getBlock() instanceof BonemealableBlock bb)) continue;
                        try {
                            if (!bb.isValidBonemealTarget(level, mp, st)) continue;
                        } catch (Throwable ignored) { continue; }
                        if (isMature(st)) continue;
                        int d = dx * dx + dz * dz + dy * dy;
                        if (d < bestDist) {
                            bestDist = d;
                            best = mp.immutable();
                        }
                    }
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void markFailure(long now) {
        lastFailureGameTime = now;
    }

    private void clearAction() {
        action = Action.NONE;
        actionStartGameTime = 0L;
        targetItemEntityId = -1;
        targetPos = null;
        targetPlantItemId = null;
        bonemealUseCooldown = 0;
        try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
    }

    private boolean isCurrentTargetStillValid(ServerLevel level, FarmingSettings settings) {
        try {
            if (action == Action.PICKUP) {
                if (targetItemEntityId <= 0) return false;
                var e = level.getEntity(targetItemEntityId);
                return e instanceof ItemEntity ie && ie.isAlive() && !ie.getItem().isEmpty();
            }
            if (action == Action.PLANT) {
                if (targetPos == null || targetPlantItemId == null) return false;
                BlockState below = level.getBlockState(targetPos);
                if (below == null) return false;

                Item plantItem = resolveItem(targetPlantItemId);
                if (!(plantItem instanceof BlockItem bi)) return false;
                Block base = getPlantingBaseBlock(bi.getBlock());
                if (below.getBlock() != base) return false;

                BlockState above = level.getBlockState(targetPos.above());
                return above != null && above.isAir() && hasPlantItemInInv(targetPlantItemId);
            }
            if (action == Action.BONEMEAL) {
                if (targetPos == null) return false;
                BlockState st = level.getBlockState(targetPos);
                if (st == null || st.isAir()) return false;
                if (isMature(st)) return false;
                try {
                    BlockState below = level.getBlockState(targetPos.below());
                    if (below == null || below.getBlock() != Blocks.FARMLAND) return false;
                } catch (Throwable ignored) {}
                return hasBonemealInInv() && (st.getBlock() instanceof BonemealableBlock);
            }
            if (action == Action.HARVEST) {
                if (targetPos == null) return false;
                BlockState st = level.getBlockState(targetPos);
                return st != null && !st.isAir() && isMature(st);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void chooseNextAction(ServerLevel level, FarmingSettings settings, net.minecraft.world.phys.Vec3 center, int range, boolean circular) {
        try {
            clearAction();

            Set<Item> harvestItems = resolveItemSet(settings.manualHarvestItemIds);
            Set<Item> plantItems = resolveItemSet(settings.manualPlantItemIds);
            Set<Item> pickupItems = resolveItemSet(settings.pickupItemIds);
            boolean pickupAll = pickupItems.isEmpty();

            // 1) Bonemeal (optional, highest priority inside manual farming)
            if (settings.manualUseBonemeal && hasBonemealInInv()) {
                Set<Block> plantBlocks = resolvePlantBlockSet(settings.manualPlantItemIds);
                BlockPos bm = findNearestBonemealTarget(level, center, range, circular, plantBlocks);
                if (bm != null) {
                    action = Action.BONEMEAL;
                    actionStartGameTime = level.getGameTime();
                    targetPos = bm;
                    return;
                }
            }

            // 2) Pickup
            ItemEntity nearest = findNearestItem(level, center, range, circular, pickupAll ? null : pickupItems);
            if (nearest != null) {
                action = Action.PICKUP;
                actionStartGameTime = level.getGameTime();
                targetItemEntityId = nearest.getId();
                return;
            }

            // 3) Plant
            Pair<String, Integer> plantSlot = findFirstPlantItemSlot(settings.manualPlantItemIds);
            if (plantSlot != null) {
                String id = plantSlot.getFirst();
                Item plantItem = resolveItem(id);
                if (plantItem instanceof BlockItem bi) {
                    Block base = getPlantingBaseBlock(bi.getBlock());
                    BlockPos soil = findNearestEmptyPlantingBase(level, center, range, circular, base);
                    if (soil != null) {
                        action = Action.PLANT;
                        actionStartGameTime = level.getGameTime();
                        targetPos = soil;
                        targetPlantItemId = id;
                        return;
                    }
                }
            }

            // 4) Harvest
            if (!harvestItems.isEmpty()) {
                BlockPos harvest = findNearestMatureHarvestable(level, center, range, circular, harvestItems);
                if (harvest != null) {
                    action = Action.HARVEST;
                    actionStartGameTime = level.getGameTime();
                    targetPos = harvest;
                    return;
                }
            }

            // 5) Roam
            action = Action.ROAM;
            actionStartGameTime = 0L;
        } catch (Throwable ignored) {}
    }

    private void tickPickup(ServerLevel level, FarmingSettings settings) {
        try {
            if (targetItemEntityId <= 0) {
                action = Action.NONE;
                return;
            }

            var e = level.getEntity(targetItemEntityId);
            if (!(e instanceof ItemEntity ie) || !ie.isAlive()) {
                action = Action.NONE;
                return;
            }

            // Keep eyes on target while approaching/picking up.
            try { vill.getLookControl().setLookAt(ie, 30.0f, 30.0f); } catch (Throwable ignored) {}

            var stack = ie.getItem();
            if (stack == null || stack.isEmpty()) {
                action = Action.NONE;
                return;
            }

            double distSqr = vill.distanceToSqr(ie);
            if (distSqr <= 1.6) {
                Container inv = vill.getInventory();
                ItemStack remaining = insertInto(inv, stack.copy());
                int moved = stack.getCount() - (remaining == null ? 0 : remaining.getCount());

                if (moved > 0) {
                    if (remaining == null || remaining.isEmpty()) {
                        ie.discard();
                    } else {
                        ie.setItem(remaining);
                    }
                } else {
                    // Couldn't pick up (full inventory) -> fail + retry later
                    markFailure(level.getGameTime());
                }

                if (settings.manualDropOtherItems) dropOtherInventoryItems(settings);

                action = Action.NONE;
                targetItemEntityId = -1;
                return;
            }

            // Move toward item
            vill.getNavigation().moveTo(ie, SPEED_MULT);
            applyFinalApproachAssist(ie.position());

        } catch (Throwable ignored) {
            action = Action.NONE;
        }
    }

    private void tickPlant(ServerLevel level, FarmingSettings settings) {
        try {
            if (targetPos == null || targetPlantItemId == null) {
                action = Action.NONE;
                return;
            }

            BlockPos placePos = targetPos.above();

            // If someone else planted already, we're done.
            if (!level.getBlockState(placePos).isAir()) {
                action = Action.NONE;
                targetPos = null;
                targetPlantItemId = null;
                return;
            }

            double tx = targetPos.getX() + 0.5;
            double ty = targetPos.getY() + 1.0;
            double tz = targetPos.getZ() + 0.5;

            // Keep eyes on target while approaching/planting.
            try { vill.getLookControl().setLookAt(tx, ty, tz, 30.0f, 30.0f); } catch (Throwable ignored) {}

            double distSqr = vill.distanceToSqr(tx, ty, tz);
            if (distSqr <= 3.0) {
                if (tryPlacePlant(level, targetPos, targetPlantItemId)) {
                    action = Action.NONE;
                    targetPos = null;
                    targetPlantItemId = null;
                    return;
                } else {
                    markFailure(level.getGameTime());
                    action = Action.NONE;
                    targetPos = null;
                    targetPlantItemId = null;
                    return;
                }
            }

            vill.getNavigation().moveTo(tx, ty, tz, SPEED_MULT);
            applyFinalApproachAssist(new net.minecraft.world.phys.Vec3(tx, ty, tz));

        } catch (Throwable ignored) {
            action = Action.NONE;
        }
    }

    private void tickBonemeal(ServerLevel level, FarmingSettings settings) {
        try {
            if (targetPos == null) {
                action = Action.NONE;
                return;
            }

            BlockState st = level.getBlockState(targetPos);
            if (st == null || st.isAir()) {
                action = Action.NONE;
                targetPos = null;
                return;
            }

            if (isMature(st) || !(st.getBlock() instanceof BonemealableBlock)) {
                action = Action.NONE;
                targetPos = null;
                return;
            }

            double tx = targetPos.getX() + 0.5;
            double ty = targetPos.getY() + 0.5;
            double tz = targetPos.getZ() + 0.5;

            // Keep eyes on target while approaching/bonemealing.
            try { vill.getLookControl().setLookAt(tx, ty, tz, 30.0f, 30.0f); } catch (Throwable ignored) {}

            double distSqr = vill.distanceToSqr(tx, ty, tz);
            if (distSqr <= 4.0) {
                if (bonemealUseCooldown > 0) {
                    bonemealUseCooldown--;
                    return;
                }

                if (!hasBonemealInInv()) {
                    action = Action.NONE;
                    targetPos = null;
                    return;
                }

                if (tryApplyBonemeal(level, targetPos)) {
                    bonemealUseCooldown = 5;
                    BlockState after = level.getBlockState(targetPos);
                    if (after != null && isMature(after)) {
                        action = Action.NONE;
                        targetPos = null;
                    }
                    return;
                }

                // Couldn't bonemeal: don't lock into retry-roam; just move on and let other work happen.
                action = Action.NONE;
                targetPos = null;
                return;
            }

            vill.getNavigation().moveTo(tx, ty, tz, SPEED_MULT);
            applyFinalApproachAssist(new net.minecraft.world.phys.Vec3(tx, ty, tz));

        } catch (Throwable ignored) {
            action = Action.NONE;
        }
    }

    private void tickHarvest(ServerLevel level, FarmingSettings settings) {
        try {
            if (targetPos == null) {
                action = Action.NONE;
                return;
            }

            BlockState st = level.getBlockState(targetPos);
            if (st == null || st.isAir()) {
                action = Action.NONE;
                targetPos = null;
                return;
            }

            if (!isMature(st)) {
                action = Action.NONE;
                targetPos = null;
                return;
            }

            double tx = targetPos.getX() + 0.5;
            double ty = targetPos.getY() + 0.5;
            double tz = targetPos.getZ() + 0.5;

            // Keep eyes on target while approaching/harvesting.
            try { vill.getLookControl().setLookAt(tx, ty, tz, 30.0f, 30.0f); } catch (Throwable ignored) {}

            double distSqr = vill.distanceToSqr(tx, ty, tz);
            if (distSqr <= 4.0) {
                try {
                    try { ensureLoadoutMainhandEquipped("manual_farm_harvest"); } catch (Throwable ignored) {}
                    try { vill.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true); } catch (Throwable ignored) {
                        try { vill.swing(net.minecraft.world.InteractionHand.MAIN_HAND); } catch (Throwable ignored2) {}
                    }
                } catch (Throwable ignored) {}

                boolean ok = false;
                try { ok = level.destroyBlock(targetPos, true, vill); } catch (Throwable ignored) { ok = false; }
                if (ok) {
                    try { org.z2six.villageroverhaul.server.VillagerHistoryService.addFarmingHarvested(vill, 1, true); } catch (Throwable ignored) {}
                }
                action = Action.NONE;
                targetPos = null;
                return;
            }

            vill.getNavigation().moveTo(tx, ty, tz, SPEED_MULT);
            applyFinalApproachAssist(new net.minecraft.world.phys.Vec3(tx, ty, tz));

        } catch (Throwable ignored) {
            action = Action.NONE;
        }
    }

    private void tickRoam(ServerLevel level, net.minecraft.world.phys.Vec3 center, int range, boolean circular) {
        try {
            if (level == null) return;
            long now = level.getGameTime();

            // Occasional natural head movement
            if (rng.nextInt(30) == 0) {
                double yaw = vill.getYRot() + (rng.nextBoolean() ? 35.0 : -35.0);
                vill.setYRot((float) yaw);
            }

            if (nextRoamAt == 0L) nextRoamAt = now + 40 + rng.nextInt(60);
            if (now < nextRoamAt) return;

            nextRoamAt = now + 40 + rng.nextInt(80);

            BlockPos base = BlockPos.containing(center.x, center.y, center.z);
            int dx;
            int dz;
            if (circular) {
                // rejection sample for circle
                int tries = 0;
                do {
                    dx = rng.nextInt(range * 2 + 1) - range;
                    dz = rng.nextInt(range * 2 + 1) - range;
                    tries++;
                } while (tries < 16 && (dx * dx + dz * dz) > (range * range));
            } else {
                dx = rng.nextInt(range * 2 + 1) - range;
                dz = rng.nextInt(range * 2 + 1) - range;
            }

            BlockPos target = base.offset(dx, 0, dz);
            // Find a reasonable top position
            BlockPos top = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, target);
            vill.getNavigation().moveTo(top.getX() + 0.5, top.getY(), top.getZ() + 0.5, SPEED_MULT);
        } catch (Throwable ignored) {}
    }

    private void applyFinalApproachAssist(net.minecraft.world.phys.Vec3 target) {
        try {
            if (target == null) return;

            try {
                vill.getMoveControl().setWantedPosition(target.x, target.y, target.z, ASSIST_SPEED);
            } catch (Throwable ignored) {}

            var vel = vill.getDeltaMovement();
            double hv2 = vel.x * vel.x + vel.z * vel.z;
            if (hv2 >= ASSIST_MIN_VEL_SQR) return;

            var pos = vill.position();
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

    private ItemEntity findNearestItem(ServerLevel level, net.minecraft.world.phys.Vec3 center, int range, boolean circular, Set<Item> filter) {
        try {
            if (level == null) return null;

            var aabb = new net.minecraft.world.phys.AABB(center, center).inflate(range, range, range);
            List<ItemEntity> items = level.getEntitiesOfClass(
                    ItemEntity.class,
                    aabb,
                    ie -> ie != null && ie.isAlive() && ie.getItem() != null && !ie.getItem().isEmpty()
            );

            ItemEntity best = null;
            double bestDist = Double.MAX_VALUE;

            for (ItemEntity ie : items) {
                if (ie == null) continue;
                ItemStack st = ie.getItem();
                if (st == null || st.isEmpty()) continue;
                if (filter != null && !filter.isEmpty() && !filter.contains(st.getItem())) continue;
                if (!isWithinArea(ie.position(), center, range, circular)) continue;

                double d = vill.distanceToSqr(ie);
                if (d < bestDist) {
                    bestDist = d;
                    best = ie;
                }
            }

            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private BlockPos findNearestEmptyFarmland(ServerLevel level, net.minecraft.world.phys.Vec3 center, int range, boolean circular) {
        try {
            BlockPos base = BlockPos.containing(center.x, center.y, center.z);
            int r = Math.max(1, range);

            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;

            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (circular && (dx * dx + dz * dz) > (r * r)) continue;
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos p = base.offset(dx, dy, dz);
                        BlockState st = level.getBlockState(p);
                        if (st == null || st.getBlock() != Blocks.FARMLAND) continue;
                        BlockPos above = p.above();
                        if (!level.getBlockState(above).isAir()) continue;

                        double d = vill.distanceToSqr(p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5);
                        if (d < bestDist) {
                            bestDist = d;
                            best = p;
                        }
                    }
                }
            }

            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private BlockPos findNearestMatureHarvestable(ServerLevel level, net.minecraft.world.phys.Vec3 center, int range, boolean circular, Set<Item> harvestItems) {
        try {
            BlockPos base = BlockPos.containing(center.x, center.y, center.z);
            int r = Math.max(1, range);

            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;

            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (circular && (dx * dx + dz * dz) > (r * r)) continue;
                    for (int dy = -1; dy <= 2; dy++) {
                        BlockPos p = base.offset(dx, dy, dz);
                        BlockState st = level.getBlockState(p);
                        if (st == null || st.isAir()) continue;
                        if (!isMature(st)) continue;

                        // Most farm crops sit on farmland; Nether Wart sits on Soul Sand.
                        try {
                            BlockState below = level.getBlockState(p.below());
                            if (below == null) continue;
                            if (st.getBlock() == Blocks.NETHER_WART) {
                                if (below.getBlock() != Blocks.SOUL_SAND) continue;
                            } else {
                                if (below.getBlock() != Blocks.FARMLAND) continue;
                            }
                        } catch (Throwable ignored) {}

                        if (!dropsContainAny(level, p, st, harvestItems)) continue;

                        double d = vill.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
                        if (d < bestDist) {
                            bestDist = d;
                            best = p;
                        }
                    }
                }
            }

            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private BlockPos findNearestBonemealTarget(ServerLevel level, net.minecraft.world.phys.Vec3 center, int range, boolean circular, Set<Block> allowedBlocks) {
        try {
            BlockPos base = BlockPos.containing(center.x, center.y, center.z);
            int r = Math.max(1, range);

            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;

            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (circular && (dx * dx + dz * dz) > (r * r)) continue;
                    for (int dy = -1; dy <= 2; dy++) {
                        BlockPos p = base.offset(dx, dy, dz);
                        BlockState st = level.getBlockState(p);
                        if (st == null || st.isAir()) continue;
                        if (!(st.getBlock() instanceof BonemealableBlock bb)) continue;
                        if (allowedBlocks != null && !allowedBlocks.isEmpty() && !allowedBlocks.contains(st.getBlock())) continue;
                        if (isMature(st)) continue;

                        // Keep it farmland-style: only bonemeal crops above farmland.
                        try {
                            BlockState below = level.getBlockState(p.below());
                            if (below == null || below.getBlock() != Blocks.FARMLAND) continue;
                        } catch (Throwable ignored) {}

                        boolean valid = false;
                        try { valid = bb.isValidBonemealTarget(level, p, st); } catch (Throwable ignored) { valid = false; }
                        if (!valid) continue;

                        double d = vill.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
                        if (d < bestDist) {
                            bestDist = d;
                            best = p;
                        }
                    }
                }
            }

            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Set<Block> resolvePlantBlockSet(List<String> itemIds) {
        Set<Block> out = new HashSet<>();
        if (itemIds == null) return out;
        int n = Math.min(512, itemIds.size());
        for (int i = 0; i < n; i++) {
            Item it = resolveItem(itemIds.get(i));
            if (!(it instanceof BlockItem bi)) continue;
            Block b = bi.getBlock();
            if (b != null && b != Blocks.AIR) out.add(b);
        }
        return out;
    }

    private static boolean isWithinArea(net.minecraft.world.phys.Vec3 pos, net.minecraft.world.phys.Vec3 center, int range, boolean circular) {
        try {
            if (pos == null || center == null) return false;
            double dx = pos.x - center.x;
            double dz = pos.z - center.z;
            if (!circular) {
                return Math.abs(dx) <= range && Math.abs(dz) <= range;
            }
            return (dx * dx + dz * dz) <= (double) (range * range);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean dropsContainAny(ServerLevel level, BlockPos pos, BlockState st, Set<Item> harvestItems) {
        try {
            if (harvestItems == null || harvestItems.isEmpty()) return false;

            BlockEntity be = null;
            try { be = level.getBlockEntity(pos); } catch (Throwable ignored) {}

            List<ItemStack> drops = Block.getDrops(st, level, pos, be, vill, ItemStack.EMPTY);
            for (ItemStack d : drops) {
                if (d == null || d.isEmpty()) continue;
                if (harvestItems.contains(d.getItem())) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isMature(BlockState st) {
        try {
            if (st == null) return false;
            for (var prop : st.getProperties()) {
                if (!(prop instanceof IntegerProperty ip)) continue;
                String name = prop.getName();
                if (name == null) continue;
                String ln = name.toLowerCase(Locale.ROOT);
                if (!ln.contains("age")) continue;

                int cur = st.getValue(ip);
                int max = Integer.MIN_VALUE;
                for (Integer v : ip.getPossibleValues()) {
                    if (v != null && v > max) max = v;
                }
                return max != Integer.MIN_VALUE && cur >= max;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean hasPlantItemInInv(String itemId) {
        try {
            if (itemId == null || itemId.isBlank()) return false;
            Item item = resolveItem(itemId);
            if (item == null) return false;
            Container inv = vill.getInventory();
            if (inv == null) return false;
            int sz = inv.getContainerSize();
            for (int i = 0; i < sz; i++) {
                ItemStack s = inv.getItem(i);
                if (s != null && !s.isEmpty() && s.is(item)) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Pair<String, Integer> findFirstPlantItemSlot(List<String> itemIds) {
        try {
            if (itemIds == null || itemIds.isEmpty()) return null;
            Container inv = vill.getInventory();
            if (inv == null) return null;

            int sz = inv.getContainerSize();
            for (String id : itemIds) {
                Item item = resolveItem(id);
                if (item == null) continue;
                for (int i = 0; i < sz; i++) {
                    ItemStack s = inv.getItem(i);
                    if (s != null && !s.isEmpty() && s.is(item)) return Pair.of(id, i);
                }
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean tryPlacePlant(ServerLevel level, BlockPos farmlandPos, String itemId) {
        try {
            if (level == null || farmlandPos == null || itemId == null) return false;

            BlockState below = level.getBlockState(farmlandPos);
            if (below == null) return false;

            BlockPos placePos = farmlandPos.above();
            if (!level.getBlockState(placePos).isAir()) return false;

            Item item = resolveItem(itemId);
            if (item == null) return false;

            Container inv = vill.getInventory();
            if (inv == null) return false;

            int sz = inv.getContainerSize();
            for (int i = 0; i < sz; i++) {
                ItemStack s = inv.getItem(i);
                if (s == null || s.isEmpty() || !s.is(item)) continue;

                if (!(s.getItem() instanceof BlockItem bi)) return false;

                Block block = bi.getBlock();
                if (block == null || block == Blocks.AIR) return false;

                Block base = getPlantingBaseBlock(block);
                if (below.getBlock() != base) return false;

                BlockState placeState = block.defaultBlockState();
                try {
                    if (!placeState.canSurvive(level, placePos)) return false;
                } catch (Throwable ignored) {}

                // Always keep the configured loadout item in mainhand (e.g. hoe), and show the seed in offhand.
                try { ensureLoadoutMainhandEquipped("manual_farm_plant"); } catch (Throwable ignored) {}
                try { setOffhandVisualFor(item, level.getGameTime(), 10, "manual_farm_seed"); } catch (Throwable ignored) {}

                level.setBlock(placePos, placeState, 3);
                int wantConsume = computeEfficiencyAdjustedConsume(1);
                if (wantConsume > 0) {
                    // Consume across inventory so negative Efficiency can take an "extra" from another stack.
                    int consumed = consumeFromInventory(item, wantConsume);
                    if (consumed <= 0) {
                        try {
                            long nowGt = level.getGameTime();
                            long last = vill.getPersistentData().getLong("ezvr_manual_farm_last_consume_warn");
                            if (last <= 0L || (nowGt - last) > 100L) {
                                vill.getPersistentData().putLong("ezvr_manual_farm_last_consume_warn", nowGt);
                                VillagerOverhaul.LOG().info("[VillagerOverhaul] [manual_farm] WARNING consumed=0 for plant villager={} item={} wantConsume={}",
                                    vill.getUUID(), String.valueOf(BuiltInRegistries.ITEM.getKey(item)), wantConsume);
                            }
                        } catch (Throwable ignored) {}
                    }
                }

                // Occasional info log for debugging seed consumption behavior.
                try {
                    long nowGt = level.getGameTime();
                    long last = vill.getPersistentData().getLong("ezvr_manual_farm_last_plant_log");
                    if (last <= 0L || (nowGt - last) >= 40L) { // ~2s
                        vill.getPersistentData().putLong("ezvr_manual_farm_last_plant_log", nowGt);
                        int invCount = 0;
                        try {
                            Container inv2 = vill.getInventory();
                            if (inv2 != null) {
                                int sz2 = inv2.getContainerSize();
                                for (int ii = 0; ii < sz2; ii++) {
                                    ItemStack st2 = inv2.getItem(ii);
                                    if (st2 != null && !st2.isEmpty() && st2.is(item)) invCount += st2.getCount();
                                }
                            }
                        } catch (Throwable ignored) { invCount = -1; }
                        double eff = 0.0;
                        try { eff = VillagerTraitEffects.efficiencyPct(vill); } catch (Throwable ignored) { eff = 0.0; }
                        VillagerOverhaul.LOG().info("[VillagerOverhaul] [manual_farm] plant villager={} item={} wantConsume={} invCountAfter={} efficiencyPct={}",
                                vill.getUUID(), String.valueOf(BuiltInRegistries.ITEM.getKey(item)), wantConsume, invCount, eff);
                    }
                } catch (Throwable ignored) {}

                try { VillagerBrain.signalSwing(vill, net.minecraft.world.InteractionHand.OFF_HAND, "manual_farm_plant"); } catch (Throwable ignored) {}
                try { vill.swing(net.minecraft.world.InteractionHand.OFF_HAND, true); } catch (Throwable ignored) {
                    try { vill.swing(net.minecraft.world.InteractionHand.OFF_HAND); } catch (Throwable ignored2) {}
                }

                try { org.z2six.villageroverhaul.server.VillagerHistoryService.addFarmingPlanted(vill, 1, true); } catch (Throwable ignored) {}

                return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void setOffhandVisualFor(Item item, long now, int ticks, String why) {
        try {
            if (vill == null || item == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;

            clearRealOffhandForManualAction(why + "_clear_offhand");

            ItemStack one = new ItemStack(item);
            if (!one.isEmpty() && one.getCount() > 1) one.setCount(1);
            try { VillagerBrain.notifyManualHandSet(vill, net.minecraft.world.entity.EquipmentSlot.OFFHAND, one, why + "_set"); } catch (Throwable ignored) {}
            vill.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, one);

            vill.getPersistentData().putLong(PD_OFFHAND_VIS_UNTIL, now + Math.max(1, ticks));
            try { vill.getPersistentData().putString(PD_OFFHAND_VIS_ITEM, String.valueOf(BuiltInRegistries.ITEM.getKey(item))); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private void clearRealOffhandForManualAction(String why) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;

            ItemStack curOff = vill.getOffhandItem();
            if (curOff == null || curOff.isEmpty()) return;

            // If this is OUR temporary visual offhand (seed/bonemeal), never stash it into GUI/offhand loadout.
            // Just clear it so we don't duplicate items via the loadout UI.
            try {
                long until = vill.getPersistentData().getLong(PD_OFFHAND_VIS_UNTIL);
                if (until > 0L && vill.level() != null) {
                    long now = vill.level().getGameTime();
                    if (now <= until) {
                        String expect = vill.getPersistentData().getString(PD_OFFHAND_VIS_ITEM);
                        String curId = "";
                        try { curId = String.valueOf(BuiltInRegistries.ITEM.getKey(curOff.getItem())); } catch (Throwable ignored) { curId = ""; }
                        if (expect != null && !expect.isBlank() && expect.equals(curId)) {
                            try { VillagerBrain.notifyManualHandSet(vill, net.minecraft.world.entity.EquipmentSlot.OFFHAND, ItemStack.EMPTY, why + "_clear_temp_visual"); } catch (Throwable ignored) {}
                            vill.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, ItemStack.EMPTY);
                            vill.getPersistentData().remove(PD_OFFHAND_VIS_UNTIL);
                            vill.getPersistentData().remove(PD_OFFHAND_VIS_ITEM);
                            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [manual_farm] offhand_clear temp_visual villager={} why={} item={}",
                                    vill.getUUID(), String.valueOf(why), curId);
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // If the player registered an offhand loadout item, never overwrite it: stash to inventory/drop.
            boolean offhandRegistered = VillagerCombatLoadoutService.isOffhandRegistered(vill);
            if (!offhandRegistered) {
                // Only safe to stow single-count stacks, since loadout UI normalizes to count=1.
                boolean stowed = VillagerCombatLoadoutService.tryStowInUnregisteredGuiOffhand(vill, curOff, why);
                if (!stowed) {
                    storeOrDropToVillager(curOff.copy());
                }
            } else {
                storeOrDropToVillager(curOff.copy());
            }

            try { VillagerBrain.notifyManualHandSet(vill, net.minecraft.world.entity.EquipmentSlot.OFFHAND, ItemStack.EMPTY, why + "_clear"); } catch (Throwable ignored) {}
            vill.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, ItemStack.EMPTY);
        } catch (Throwable ignored) {}
    }

    private void storeOrDropToVillager(ItemStack stack) {
        try {
            if (vill == null) return;
            if (stack == null || stack.isEmpty()) return;

            Container inv = vill.getInventory();
            if (inv != null) {
                ItemStack remaining = insertInto(inv, stack.copy());
                if (remaining == null || remaining.isEmpty()) return;
                stack = remaining;
            }

            try { vill.spawnAtLocation(stack.copy()); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private static Block getPlantingBaseBlock(Block plantBlock) {
        try {
            if (plantBlock == null) return Blocks.FARMLAND;
            // Special-case: Nether Wart grows on Soul Sand.
            if (plantBlock == Blocks.NETHER_WART) return Blocks.SOUL_SAND;
            return Blocks.FARMLAND;
        } catch (Throwable ignored) {
            return Blocks.FARMLAND;
        }
    }

    private BlockPos findNearestEmptyPlantingBase(ServerLevel level, net.minecraft.world.phys.Vec3 center, int range, boolean circular, Block baseBlock) {
        try {
            if (level == null || center == null || baseBlock == null) return null;
            int r = Math.max(1, range);

            int cx = (int) Math.floor(center.x);
            int cy = (int) Math.floor(center.y);
            int cz = (int) Math.floor(center.z);

            BlockPos best = null;
            double bestDistSqr = Double.MAX_VALUE;

            BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (circular) {
                        long d2 = (long) dx * dx + (long) dz * dz;
                        long rr = (long) r * r;
                        if (d2 > rr) continue;
                    }

                    // Try a small vertical window around the center.
                    for (int dy = -3; dy <= 3; dy++) {
                        mp.set(cx + dx, cy + dy, cz + dz);
                        BlockState below = level.getBlockState(mp);
                        if (below == null || below.getBlock() != baseBlock) continue;
                        BlockState above = level.getBlockState(mp.above());
                        if (above == null || !above.isAir()) continue;

                        double tx = mp.getX() + 0.5;
                        double ty = mp.getY() + 0.5;
                        double tz = mp.getZ() + 0.5;
                        double dist = center.distanceToSqr(tx, ty, tz);
                        if (dist < bestDistSqr) {
                            bestDistSqr = dist;
                            best = mp.immutable();
                        }
                    }
                }
            }

            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean hasBonemealInInv() {
        try {
            Container inv = vill.getInventory();
            if (inv == null) return false;
            int sz = inv.getContainerSize();
            for (int i = 0; i < sz; i++) {
                ItemStack s = inv.getItem(i);
                if (s != null && !s.isEmpty() && s.is(Items.BONE_MEAL)) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean tryApplyBonemeal(ServerLevel level, BlockPos pos) {
        try {
            if (level == null || pos == null) return false;

            BlockState st = level.getBlockState(pos);
            if (st == null || st.isAir()) return false;
            if (!(st.getBlock() instanceof BonemealableBlock bb)) return false;

            boolean valid = false;
            try { valid = bb.isValidBonemealTarget(level, pos, st); } catch (Throwable ignored) { valid = false; }
            if (!valid) return false;

            Container inv = vill.getInventory();
            if (inv == null) return false;

            int slot = -1;
            int sz = inv.getContainerSize();
            for (int i = 0; i < sz; i++) {
                ItemStack s = inv.getItem(i);
                if (s != null && !s.isEmpty() && s.is(Items.BONE_MEAL)) { slot = i; break; }
            }
            if (slot < 0) return false;

            boolean success = true;
            try { success = bb.isBonemealSuccess(level, level.getRandom(), pos, st); } catch (Throwable ignored) { success = true; }
            if (!success) return false;

            try { bb.performBonemeal(level, level.getRandom(), pos, st); } catch (Throwable ignored) { return false; }

            // Particle feedback (green plus signs / happy-ish): send both vanilla bonemeal event and explicit HAPPY particles.
            try { level.levelEvent(2005, pos, 0); } catch (Throwable ignored) {}
            try {
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                        pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5,
                        8,
                        0.35, 0.35, 0.35,
                        0.0);
            } catch (Throwable ignored) {}

            int wantConsume = computeEfficiencyAdjustedConsume(1);
            if (wantConsume > 0) consumeFromInventory(Items.BONE_MEAL, wantConsume);

            try { vill.swing(net.minecraft.world.InteractionHand.OFF_HAND, true); } catch (Throwable ignored) {
                try { vill.swing(net.minecraft.world.InteractionHand.OFF_HAND); } catch (Throwable ignored2) {}
            }

            try { org.z2six.villageroverhaul.server.VillagerHistoryService.addFarmingBonemealed(vill, 1, true); } catch (Throwable ignored) {}

            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int computeEfficiencyAdjustedConsume(int base) {
        int b = Math.max(0, base);
        if (b == 0) return 0;
        try {
            double pct = 0.0;
            try { pct = VillagerTraitEffects.efficiencyPct(vill); } catch (Throwable ignored) { pct = 0.0; }
            if (Double.isNaN(pct) || Double.isInfinite(pct)) pct = 0.0;

            if (pct > 0.0) {
                // Never allow a true 100% save chance: that makes seeds/bonemeal effectively infinite.
                // Keep it "very strong" but not absolute.
                double p = pct / 100.0;
                if (p > 1.0) p = 1.0;
                if (p > 0.95) p = 0.95;
                if (rng.nextDouble() < p) return 0; // save item
                return b;
            }

            if (pct < 0.0) {
                double p = (-pct) / 100.0;
                if (p > 1.0) p = 1.0;
                if (rng.nextDouble() < p) return b + 1; // consume extra
                return b;
            }

            return b;
        } catch (Throwable ignored) {
            return b;
        }
    }

    private int consumeFromInventory(Item item, int amount) {
        try {
            if (item == null || amount <= 0) return 0;
            Container inv = vill.getInventory();
            if (inv == null) return 0;

            int remaining = amount;
            int sz = inv.getContainerSize();
            for (int i = 0; i < sz && remaining > 0; i++) {
                ItemStack s = inv.getItem(i);
                if (s == null || s.isEmpty() || !s.is(item)) continue;
                int take = Math.min(remaining, s.getCount());
                if (take <= 0) continue;
                s.shrink(take);
                remaining -= take;
                // Always write back: some Container implementations may return a copy from getItem().
                if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                else inv.setItem(i, s);
            }
            inv.setChanged();
            return amount - remaining;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void dropOtherInventoryItems(FarmingSettings settings) {
        try {
            if (settings == null || !settings.manualDropOtherItems) return;

            Set<Item> keep = union(resolveItemSet(settings.manualHarvestItemIds), resolveItemSet(settings.manualPlantItemIds));
            // Also keep anything that the player configured for deposit/withdraw, so storage rules can still apply.
            try {
                if (settings.depositRules != null) {
                    for (var rule : settings.depositRules) {
                        if (rule == null || rule.itemId == null) continue;
                        Item it = resolveItem(rule.itemId);
                        if (it != null && it != net.minecraft.world.item.Items.AIR) keep.add(it);
                    }
                }
                if (settings.withdrawRules != null) {
                    for (var rule : settings.withdrawRules) {
                        if (rule == null || rule.itemId == null) continue;
                        Item it = resolveItem(rule.itemId);
                        if (it != null && it != net.minecraft.world.item.Items.AIR) keep.add(it);
                    }
                }
            } catch (Throwable ignored) {}

            Container inv = vill.getInventory();
            if (inv == null) return;

            int sz = inv.getContainerSize();
            for (int i = 0; i < sz; i++) {
                ItemStack s = inv.getItem(i);
                if (s == null || s.isEmpty()) continue;
                if (keep.contains(s.getItem())) continue;
                try { if (s.is(net.minecraft.world.item.Items.EMERALD)) continue; } catch (Throwable ignored) {}

                // Drop and clear
                vill.spawnAtLocation(s.copy());
                inv.setItem(i, ItemStack.EMPTY);
            }
            inv.setChanged();
        } catch (Throwable ignored) {}
    }

    private static Item resolveItem(String id) {
        try {
            if (id == null) return null;
            String norm = id.trim().toLowerCase(Locale.ROOT);
            if (norm.isEmpty()) return null;
            return BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(norm));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Set<Item> resolveItemSet(List<String> ids) {
        Set<Item> out = new HashSet<>();
        if (ids == null) return out;
        int n = Math.min(512, ids.size());
        for (int i = 0; i < n; i++) {
            Item it = resolveItem(ids.get(i));
            if (it != null && it != net.minecraft.world.item.Items.AIR) out.add(it);
        }
        return out;
    }

    private static Set<Item> union(Set<Item> a, Set<Item> b) {
        Set<Item> out = new HashSet<>();
        if (a != null) out.addAll(a);
        if (b != null) out.addAll(b);
        return out;
    }

    // Inventory insertion (minimal)
    private static ItemStack insertInto(Container inv, ItemStack stack) {
        try {
            if (inv == null) return stack;
            if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;

            ItemStack remaining = stack;
            int size = inv.getContainerSize();

            // merge
            for (int slot = 0; slot < size; slot++) {
                if (remaining.isEmpty()) break;
                ItemStack cur = inv.getItem(slot);
                if (cur == null || cur.isEmpty()) continue;
                if (!ItemStack.isSameItemSameComponents(cur, remaining)) continue;
                int max = Math.min(cur.getMaxStackSize(), inv.getMaxStackSize());
                int space = max - cur.getCount();
                if (space <= 0) continue;
                int move = Math.min(space, remaining.getCount());
                cur.grow(move);
                remaining.shrink(move);
                inv.setItem(slot, cur);
            }

            // empty slots
            for (int slot = 0; slot < size; slot++) {
                if (remaining.isEmpty()) break;
                ItemStack cur = inv.getItem(slot);
                if (cur != null && !cur.isEmpty()) continue;

                int max = Math.min(remaining.getMaxStackSize(), inv.getMaxStackSize());
                int move = Math.min(max, remaining.getCount());
                ItemStack placed = remaining.copy();
                placed.setCount(move);
                inv.setItem(slot, placed);
                remaining.shrink(move);
            }

            inv.setChanged();
            return remaining;
        } catch (Throwable ignored) {
            return stack;
        }
    }
}
