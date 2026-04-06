package org.z2six.villageroverhaul.mixin;

import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.VillagerMakeLove;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import org.z2six.villageroverhaul.server.VillagerFamilyNames;

@Mixin(VillagerMakeLove.class)
abstract class VillagerMakeLoveNamingMixin {
    @Inject(
            method = "breed",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)V"
            ),
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void villageroverhaul$inheritParentLineage(
            ServerLevel level,
            Villager parent,
            Villager partner,
            CallbackInfoReturnable<Optional<Villager>> cir,
            Villager child
    ) {
        VillagerFamilyNames.inheritSurnameFromParents(child, parent, partner);
    }
}
