package org.z2six.villageroverhaul.server.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.z2six.villageroverhaul.config.ServerConfig;
import org.z2six.villageroverhaul.server.FarmingSettingsService;
import org.z2six.villageroverhaul.server.RecruitService;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * "Storage" module:
 * - triggered automatically by per-villager farming settings (stacks threshold + item list)
 * - navigates to the registered chest and deposits matching items.
 */
public final class VillagerStorageGoal extends Goal {

    private final Villager vill;

    private static final double SPEED = 0.5;

    private enum Phase {
        MOVING,
        WAITING
    }

    private enum Action {
        DEPOSIT,
        WITHDRAW
    }

    private BlockPos targetPos = null;
    private boolean targetIsEnder = false;
    private String targetDim = "";
    private Phase phase = Phase.MOVING;
    private Action action = Action.DEPOSIT;

    private int tickCooldown = 0;
    private long startedAtMs = 0L;
    private long timeoutMs = 60_000L;
    private long retryAfterMs = 60_000L;
    private long lastFailureAtMs = 0L;

    private long waitUntilMs = 0L;
    private boolean chestOpened = false;

    public VillagerStorageGoal(Villager vill) {
        this.vill = vill;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        try {
            if (vill == null) return false;
            if (vill.level().isClientSide()) return false;
            if (VillagerBrain.isUiPaused(vill)) return false;
            if (VillagerBrain.isCombatEngaged(vill)) return false;

            if (tickCooldown-- > 0) return false;
            tickCooldown = 10; // cheap debounce

            // Cooldown after failure so we don't immediately re-enter the same stuck route.
            long nowMs = System.currentTimeMillis();
            if (lastFailureAtMs > 0L && retryAfterMs > 0L && (nowMs - lastFailureAtMs) < retryAfterMs) {
                return false;
            }

            var settings = FarmingSettingsService.getSettings(vill);
            if (settings == null) return false;
            timeoutMs = (long) Math.max(1, settings.timeoutSeconds) * 1000L;
            retryAfterMs = (long) Math.max(1, settings.retryAfterSeconds) * 1000L;

            // Must be same dimension as villager (we walk there).
            if (!(vill.level() instanceof ServerLevel sl)) return false;
            String curDim = "";
            try { curDim = String.valueOf(sl.dimension().location()); } catch (Throwable ignored) { curDim = ""; }

            // Storage rules require a workstation so the villager has a defined farming area.
            if (FarmingSettingsService.getEffectiveWorkstation(sl, vill) == null) return false;

            // Prefer WITHDRAW if it triggers; otherwise DEPOSIT.
            if (tryTriggerWithdraw(sl, curDim, settings)) return true;
            if (tryTriggerDeposit(sl, curDim, settings)) return true;

            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean tryTriggerDeposit(ServerLevel sl, String curDim, org.z2six.villageroverhaul.farming.FarmingSettings settings) {
        try {
            if (settings == null || settings.depositRules == null || settings.depositRules.isEmpty()) return false;

            FarmingSettingsService.RegisteredChest chest = FarmingSettingsService.getRegisteredChest(vill);
            if (chest == null) return false;

            String dim = chest.dimId();
            if (dim == null || dim.isBlank()) return false;
            if (!dim.equals(curDim)) return false;

            // Enforce chest within the villager's farming area.
            BlockPos chestPos = new BlockPos(chest.x(), chest.y(), chest.z());
            if (!FarmingSettingsService.isWithinManualFarmingArea(sl, vill, chestPos, settings.manualRangeCircular)) return false;

            Container inv = getVillagerInventory();
            if (inv == null) return false;

            for (var rule : settings.depositRules) {
                if (rule == null || rule.itemId == null) continue;
                Item item = resolveItem(rule.itemId);
                if (item == null) continue;
                int count = countItem(inv, item);
                if (count <= 0) continue;

                int maxStack = Math.max(1, item.getDefaultInstance().getMaxStackSize());
                int threshold = (rule.stacksThreshold <= 0) ? 1 : Math.max(1, rule.stacksThreshold) * maxStack;
                int keep = Math.max(0, rule.keepStacks) * maxStack;

                if (count >= threshold && count > keep) {
                    action = Action.DEPOSIT;
                    targetDim = dim;
                    targetIsEnder = chest.isEnderChest();
                    targetPos = chestPos;
                    return true;
                }
            }

            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean tryTriggerWithdraw(ServerLevel sl, String curDim, org.z2six.villageroverhaul.farming.FarmingSettings settings) {
        try {
            if (settings == null || settings.withdrawRules == null || settings.withdrawRules.isEmpty()) return false;

            FarmingSettingsService.RegisteredChest chest = FarmingSettingsService.getRegisteredWithdrawChest(vill);
            if (chest == null) return false;

            String dim = chest.dimId();
            if (dim == null || dim.isBlank()) return false;
            if (!dim.equals(curDim)) return false;

            // Temporarily set target so resolveTargetContainer can work.
            targetDim = dim;
            targetIsEnder = chest.isEnderChest();
            targetPos = new BlockPos(chest.x(), chest.y(), chest.z());

            // Enforce chest within the villager's farming area.
            if (!FarmingSettingsService.isWithinManualFarmingArea(sl, vill, targetPos, settings.manualRangeCircular)) {
                targetPos = null;
                targetDim = "";
                targetIsEnder = false;
                return false;
            }

            Container chestInv = resolveTargetContainer(sl);
            if (chestInv == null) return false;

            for (var rule : settings.withdrawRules) {
                if (rule == null || rule.itemId == null) continue;
                Item item = resolveItem(rule.itemId);
                if (item == null) continue;
                int maxStack = Math.max(1, item.getDefaultInstance().getMaxStackSize());
                int threshold = (rule.stacksThreshold <= 0) ? 1 : Math.max(1, rule.stacksThreshold) * maxStack;
                int keepInChest = Math.max(0, rule.keepStacks) * maxStack;

                int chestCount = countItem(chestInv, item);
                if (chestCount < threshold) continue;
                if (chestCount <= keepInChest) continue;

                if (chestCount >= threshold) {
                    action = Action.WITHDRAW;
                    return true;
                }
            }

            // Not triggered; clear target (so canContinueToUse doesn't keep it alive).
            targetPos = null;
            targetDim = "";
            targetIsEnder = false;
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean canContinueToUse() {
        try {
            if (vill == null) return false;
            if (vill.level().isClientSide()) return false;
            return targetPos != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void start() {
        try {
            VillagerBrain.setStorageActive(vill, true);
            startedAtMs = System.currentTimeMillis();
            phase = Phase.MOVING;
            chestOpened = false;
            waitUntilMs = 0L;
            moveTowardTarget();
        } catch (Throwable ignored) {}
    }

    @Override
    public void tick() {
        try {
            if (vill == null) return;
            if (targetPos == null) return;

            // Timeout (real-world seconds): give up and let other behavior resume.
            long now = System.currentTimeMillis();
            if (timeoutMs > 0L && startedAtMs > 0L && (now - startedAtMs) > timeoutMs) {
                lastFailureAtMs = now;
                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
                tryCloseChestIfOpen();
                targetPos = null;
                return;
            }

            if (phase == Phase.WAITING) {
                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
                if (waitUntilMs > 0L && now < waitUntilMs) {
                    return;
                }

                boolean ok = depositNow();
                tryCloseChestIfOpen();

                if (!ok) lastFailureAtMs = now;

                // Done; next tick will re-trigger if still above threshold and retryAfter window passed.
                targetPos = null;
                return;
            }

            moveTowardTarget();

            if (vill.distanceToSqr(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5) <= 3.0) {
                openChestVisuals();
                phase = Phase.WAITING;
                waitUntilMs = now + 1000L; // 1 real-life second
                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void stop() {
        try {
            VillagerBrain.setStorageActive(vill, false);
            try { vill.getNavigation().stop(); } catch (Throwable ignored2) {}
            tryCloseChestIfOpen();
            targetPos = null;
            startedAtMs = 0L;
            phase = Phase.MOVING;
            waitUntilMs = 0L;
        } catch (Throwable ignored) {}
    }

    private void moveTowardTarget() {
        try {
            if (vill == null || targetPos == null) return;
            // Requirement: move at 0.5x normal "actual" movement speed (navigation speed modifier).
            vill.getNavigation().moveTo(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5, SPEED);
        } catch (Throwable ignored) {}
    }

    /**
     * @return true if all matching items were deposited (or none were present), false if items remained due to missing/filled chest etc.
     */
    private boolean depositNow() {
        try {
            if (vill == null) return false;
            if (targetPos == null) return false;
            if (!(vill.level() instanceof ServerLevel level)) return false;

            if (action == Action.WITHDRAW) {
                return withdrawNow(level);
            }

            Container target = resolveTargetContainer(level);
            if (target == null) return false;

            var settings = FarmingSettingsService.getSettings(vill);
            if (settings == null || settings.depositRules == null || settings.depositRules.isEmpty()) return true;

            Container inv = getVillagerInventory();
            if (inv == null) return false;

            // Compute per-item remaining-to-move (deposit only the excess above keep).
            java.util.Map<Item, Integer> remainingToMove = new java.util.HashMap<>();
            for (var rule : settings.depositRules) {
                if (rule == null || rule.itemId == null) continue;
                Item item = resolveItem(rule.itemId);
                if (item == null) continue;

                int cur = countItem(inv, item);
                if (cur <= 0) continue;

                int maxStack = Math.max(1, item.getDefaultInstance().getMaxStackSize());
                int trigger = (rule.stacksThreshold <= 0) ? 1 : Math.max(1, rule.stacksThreshold) * maxStack;
                int keep = Math.max(0, rule.keepStacks) * maxStack;

                if (cur < trigger) continue;
                int excess = cur - keep;
                if (excess <= 0) continue;
                remainingToMove.put(item, excess);
            }
            if (remainingToMove.isEmpty()) return true;

            int movedTotal = 0;
            int size = inv.getContainerSize();
            for (int slot = 0; slot < size; slot++) {
                ItemStack s = inv.getItem(slot);
                if (s == null || s.isEmpty()) continue;

                Integer need = remainingToMove.get(s.getItem());
                if (need == null || need <= 0) continue;

                int want = Math.min(need, s.getCount());
                if (want <= 0) continue;

                ItemStack moving = s.copy();
                moving.setCount(want);

                ItemStack remaining = insertInto(target, moving);
                int moved = want - (remaining == null ? 0 : remaining.getCount());
                if (moved <= 0) continue;

                movedTotal += moved;
                remainingToMove.put(s.getItem(), Math.max(0, need - moved));

                s.shrink(moved);
                if (s.isEmpty()) inv.setItem(slot, ItemStack.EMPTY);
            }

            try { inv.setChanged(); } catch (Throwable ignored) {}
            try { target.setChanged(); } catch (Throwable ignored) {}

            // XP is no longer granted for storage deposits (too abusable).
            try {
                if (movedTotal > 0) {
                    boolean manual = VillagerBrain.isManualFarmingActive(vill);
                    org.z2six.villageroverhaul.server.VillagerHistoryService.addFarmingDepositedItems(vill, movedTotal, manual);
                }
            } catch (Throwable ignored) {}

            // If we couldn't move all excess, treat it as failure (likely full chest).
            for (int rem : remainingToMove.values()) {
                if (rem > 0) return false;
            }
            return true;

        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean withdrawNow(ServerLevel level) {
        try {
            Container chestInv = resolveTargetContainer(level);
            if (chestInv == null) return false;

            var settings = FarmingSettingsService.getSettings(vill);
            if (settings == null) return true;

            Container inv = getVillagerInventory();
            if (inv == null) return false;

            if (settings.withdrawRules == null || settings.withdrawRules.isEmpty()) return true;

            // Remaining-to-take: withdraw excess so the chest ends at keep.
            java.util.Map<Item, Integer> remainingToTake = new java.util.HashMap<>();
            for (var rule : settings.withdrawRules) {
                if (rule == null || rule.itemId == null) continue;
                Item item = resolveItem(rule.itemId);
                if (item == null) continue;

                int maxStack = Math.max(1, item.getDefaultInstance().getMaxStackSize());
                int trigger = (rule.stacksThreshold <= 0) ? 1 : Math.max(1, rule.stacksThreshold) * maxStack;
                int keepInChest = Math.max(0, rule.keepStacks) * maxStack;

                int chestCount = countItem(chestInv, item);
                if (chestCount < trigger) continue;
                int excess = chestCount - keepInChest;
                if (excess <= 0) continue;

                remainingToTake.put(item, excess);
            }

            if (remainingToTake.isEmpty()) return true;

            int movedTotal = 0;
            int size = chestInv.getContainerSize();
            for (int slot = 0; slot < size; slot++) {
                ItemStack s = chestInv.getItem(slot);
                if (s == null || s.isEmpty()) continue;

                Integer need = remainingToTake.get(s.getItem());
                if (need == null || need <= 0) continue;

                int want = Math.min(need, s.getCount());
                if (want <= 0) continue;

                ItemStack moving = s.copy();
                moving.setCount(want);

                ItemStack remaining = insertInto(inv, moving);
                int moved = want - (remaining == null ? 0 : remaining.getCount());
                if (moved <= 0) continue;

                movedTotal += moved;
                remainingToTake.put(s.getItem(), Math.max(0, need - moved));

                s.shrink(moved);
                if (s.isEmpty()) chestInv.setItem(slot, ItemStack.EMPTY);
            }

            try { inv.setChanged(); } catch (Throwable ignored) {}
            try { chestInv.setChanged(); } catch (Throwable ignored) {}

            try {
                if (movedTotal > 0) {
                    boolean manual = VillagerBrain.isManualFarmingActive(vill);
                    org.z2six.villageroverhaul.server.VillagerHistoryService.addFarmingWithdrawnItems(vill, movedTotal, manual);
                }
            } catch (Throwable ignored) {}

            // If we couldn't take enough to reach "keep", treat as failure (likely full villager inventory).
            for (int rem : remainingToTake.values()) {
                if (rem > 0) return false;
            }

            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void openChestVisuals() {
        try {
            if (chestOpened) return;
            if (!(vill.level() instanceof ServerLevel level)) return;
            if (targetPos == null) return;

            var state = level.getBlockState(targetPos);
            if (state == null) return;

            // Trigger lid animation for nearby clients (no UI).
            try { level.blockEvent(targetPos, state.getBlock(), 1, 1); } catch (Throwable ignored) {}

            // Play open sound.
            try {
                if (targetIsEnder) {
                    level.playSound(null, targetPos, SoundEvents.ENDER_CHEST_OPEN, SoundSource.BLOCKS, 0.5f, 1.0f);
                } else {
                    level.playSound(null, targetPos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5f, 1.0f);
                }
            } catch (Throwable ignored) {}

            // Hand swing synced with the chest opening animation (not with the actual item transfer).
            try { VillagerBrain.triggerManualPlantAnimation(vill, Items.CHEST.getDefaultInstance(), 10); } catch (Throwable ignored) {}

            chestOpened = true;
        } catch (Throwable ignored) {}
    }

    private void tryCloseChestIfOpen() {
        try {
            if (!chestOpened) return;
            if (!(vill.level() instanceof ServerLevel level)) return;
            if (targetPos == null) return;

            var state = level.getBlockState(targetPos);
            if (state != null) {
                try { level.blockEvent(targetPos, state.getBlock(), 1, 0); } catch (Throwable ignored) {}
            }

            try {
                if (targetIsEnder) {
                    level.playSound(null, targetPos, SoundEvents.ENDER_CHEST_CLOSE, SoundSource.BLOCKS, 0.5f, 1.0f);
                } else {
                    level.playSound(null, targetPos, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5f, 1.0f);
                }
            } catch (Throwable ignored) {}

            chestOpened = false;
        } catch (Throwable ignored) {}
    }

    private Container resolveTargetContainer(ServerLevel level) {
        try {
            if (level == null) return null;
            if (targetPos == null) return null;

            if (targetIsEnder) {
                ServerPlayer owner = resolveRecruitOwner(level.getServer());
                if (owner == null) return null;
                try {
                    return owner.getEnderChestInventory();
                } catch (Throwable ignored) {
                    return null;
                }
            }

            BlockEntity be = level.getBlockEntity(targetPos);
            if (be instanceof Container c) return c;
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ServerPlayer resolveRecruitOwner(MinecraftServer server) {
        try {
            if (server == null) return null;
            UUID ownerUuid = RecruitService.getRecruiterUuid(vill);
            if (ownerUuid == null) return null;
            return server.getPlayerList().getPlayer(ownerUuid);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Container getVillagerInventory() {
        try {
            return vill.getInventory();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Item resolveItem(String id) {
        try {
            if (id == null) return null;
            String norm = id.trim().toLowerCase(Locale.ROOT);
            if (norm.isEmpty()) return null;
            ResourceLocation rl = ResourceLocation.parse(norm);
            return BuiltInRegistries.ITEM.get(rl);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int countItem(Container inv, Item item) {
        try {
            if (inv == null || item == null) return 0;
            int count = 0;
            int size = inv.getContainerSize();
            for (int i = 0; i < size; i++) {
                ItemStack s = inv.getItem(i);
                if (s != null && !s.isEmpty() && s.is(item)) count += s.getCount();
            }
            return count;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static ItemStack insertInto(Container inv, ItemStack stack) {
        try {
            if (inv == null) return stack;
            if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;

            ItemStack remaining = stack;
            int size = inv.getContainerSize();

            // First pass: merge into existing stacks
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

            // Second pass: empty slots
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

            return remaining;
        } catch (Throwable ignored) {
            return stack;
        }
    }
}
