package org.z2six.villageroverhaul.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.z2six.villageroverhaul.server.MapTradeStructureCache;

@Mixin(targets = "net.minecraft.world.entity.npc.VillagerTrades$TreasureMapForEmeralds")
public abstract class TreasureMapForEmeraldsStructureCacheMixin {

    @Redirect(
            method = "getOffer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;findNearestMapStructure(Lnet/minecraft/tags/TagKey;Lnet/minecraft/core/BlockPos;IZ)Lnet/minecraft/core/BlockPos;"
            ),
            require = 0
    )
    private BlockPos ezvr$cacheMapStructureLookup(ServerLevel level, TagKey<Structure> destination, BlockPos origin, int radius, boolean skipKnown) {
        return MapTradeStructureCache.findNearestMapStructureCached(level, destination, origin, radius, skipKnown);
    }
}
