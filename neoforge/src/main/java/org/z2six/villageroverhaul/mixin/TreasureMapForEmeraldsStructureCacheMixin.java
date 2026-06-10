package org.z2six.villageroverhaul.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.z2six.villageroverhaul.server.MapTradeOfferCache;
import org.z2six.villageroverhaul.server.MapTradeStructureCache;

@Mixin(targets = "net.minecraft.world.entity.npc.VillagerTrades$TreasureMapForEmeralds")
public abstract class TreasureMapForEmeraldsStructureCacheMixin {

    @Shadow @Final private int emeraldCost;
    @Shadow @Final private TagKey<Structure> destination;
    @Shadow @Final private String displayName;
    @Shadow @Final private Holder<MapDecorationType> destinationType;
    @Shadow @Final private int maxUses;
    @Shadow @Final private int villagerXp;

    @Inject(method = "getOffer", at = @At("HEAD"), cancellable = true, require = 0)
    private void ezvr$cachedTreasureMapOffer(Entity trader, RandomSource random, CallbackInfoReturnable<MerchantOffer> cir) {
        MerchantOffer offer = MapTradeOfferCache.createTreasureMapOffer(
                trader,
                emeraldCost,
                destination,
                displayName,
                destinationType,
                maxUses,
                villagerXp
        );
        cir.setReturnValue(offer);
    }

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
