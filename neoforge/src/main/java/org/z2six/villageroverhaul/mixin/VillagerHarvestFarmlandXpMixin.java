package org.z2six.villageroverhaul.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.z2six.villageroverhaul.server.VillagerHarvestXpService;

/**
 * Awards villager XP for planted items by intercepting vanilla HarvestFarmland goal block placements.
 * We intentionally use a string target to avoid hard compile dependency on the class existing in every mapping.
 */
@Mixin(targets = "net.minecraft.world.entity.ai.behavior.HarvestFarmland")
public abstract class VillagerHarvestFarmlandXpMixin {

    @Unique private Villager ezvr$tickVillager = null;

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void ezvr$onTickHead(ServerLevel level, Villager villager, long gameTime, CallbackInfo ci) {
        ezvr$tickVillager = villager;
    }

    @Inject(method = "tick", at = @At("RETURN"), require = 0)
    private void ezvr$onTickReturn(ServerLevel level, Villager villager, long gameTime, CallbackInfo ci) {
        ezvr$tickVillager = null;
    }

    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"
            ),
            require = 0
    )
    private boolean ezvr$onSetBlock(ServerLevel level, BlockPos pos, BlockState state, int flags) {
        try {
            BlockState before = null;
            try { before = level.getBlockState(pos); } catch (Throwable ignored) { before = null; }

            boolean ok = level.setBlock(pos, state, flags);

            // Count "planted" as placing a non-air block into an air block.
            try {
                if (ok
                        && before != null
                        && before.isAir()
                        && state != null
                        && !state.isAir()) {
                    Villager v = ezvr$tickVillager;
                    if (v != null) {
                        VillagerHarvestXpService.onPlanted(v, 1);
                    }
                }
            } catch (Throwable ignored) {}

            // Count "harvested" as turning a non-air block into air (inside HarvestFarmland behavior tick).
            try {
                if (ok
                        && before != null
                        && !before.isAir()
                        && state != null
                        && state.isAir()) {
                    Villager v = ezvr$tickVillager;
                    if (v != null) {
                        org.z2six.villageroverhaul.server.VillagerHistoryService.addFarmingHarvested(v, 1, false);
                    }
                }
            } catch (Throwable ignored) {}

            return ok;
        } catch (Throwable ignored) {
            return level.setBlock(pos, state, flags);
        }
    }

    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"
            ),
            require = 0
    )
    private boolean ezvr$onSetBlockAndUpdate(ServerLevel level, BlockPos pos, BlockState state) {
        try {
            BlockState before = null;
            try { before = level.getBlockState(pos); } catch (Throwable ignored) { before = null; }

            boolean ok = level.setBlockAndUpdate(pos, state);

            try {
                if (ok
                        && before != null
                        && before.isAir()
                        && state != null
                        && !state.isAir()) {
                    Villager v = ezvr$tickVillager;
                    if (v != null) {
                        VillagerHarvestXpService.onPlanted(v, 1);
                    }
                }
            } catch (Throwable ignored) {}

            try {
                if (ok
                        && before != null
                        && !before.isAir()
                        && state != null
                        && state.isAir()) {
                    Villager v = ezvr$tickVillager;
                    if (v != null) {
                        org.z2six.villageroverhaul.server.VillagerHistoryService.addFarmingHarvested(v, 1, false);
                    }
                }
            } catch (Throwable ignored) {}

            return ok;
        } catch (Throwable ignored) {
            return level.setBlockAndUpdate(pos, state);
        }
    }

    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"
            ),
            require = 0
    )
    private boolean ezvr$onSetBlockLevel(Level level, BlockPos pos, BlockState state, int flags) {
        try {
            BlockState before = null;
            try { before = level.getBlockState(pos); } catch (Throwable ignored) { before = null; }

            boolean ok = level.setBlock(pos, state, flags);

            try {
                if (ok
                        && before != null
                        && before.isAir()
                        && state != null
                        && !state.isAir()) {
                    Villager v = ezvr$tickVillager;
                    if (v != null) {
                        VillagerHarvestXpService.onPlanted(v, 1);
                    }
                }
            } catch (Throwable ignored) {}

            try {
                if (ok
                        && before != null
                        && !before.isAir()
                        && state != null
                        && state.isAir()) {
                    Villager v = ezvr$tickVillager;
                    if (v != null) {
                        org.z2six.villageroverhaul.server.VillagerHistoryService.addFarmingHarvested(v, 1, false);
                    }
                }
            } catch (Throwable ignored) {}

            return ok;
        } catch (Throwable ignored) {
            return level.setBlock(pos, state, flags);
        }
    }

    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"
            ),
            require = 0
    )
    private boolean ezvr$onSetBlockAndUpdateLevel(Level level, BlockPos pos, BlockState state) {
        try {
            BlockState before = null;
            try { before = level.getBlockState(pos); } catch (Throwable ignored) { before = null; }

            boolean ok = level.setBlockAndUpdate(pos, state);

            try {
                if (ok
                        && before != null
                        && before.isAir()
                        && state != null
                        && !state.isAir()) {
                    Villager v = ezvr$tickVillager;
                    if (v != null) {
                        VillagerHarvestXpService.onPlanted(v, 1);
                    }
                }
            } catch (Throwable ignored) {}

            try {
                if (ok
                        && before != null
                        && !before.isAir()
                        && state != null
                        && state.isAir()) {
                    Villager v = ezvr$tickVillager;
                    if (v != null) {
                        org.z2six.villageroverhaul.server.VillagerHistoryService.addFarmingHarvested(v, 1, false);
                    }
                }
            } catch (Throwable ignored) {}

            return ok;
        } catch (Throwable ignored) {
            return level.setBlockAndUpdate(pos, state);
        }
    }

    // No villager field lookup needed: tick() provides the villager parameter.
}
