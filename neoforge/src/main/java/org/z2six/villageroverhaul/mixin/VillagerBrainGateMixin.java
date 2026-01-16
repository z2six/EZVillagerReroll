// neoforge\src\main\java\org\z2six\villageroverhaul\mixin\VillagerBrainGateMixin.java
package org.z2six.villageroverhaul.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.z2six.villageroverhaul.server.ai.VillagerBrain;

@Mixin(Villager.class)
public abstract class VillagerBrainGateMixin {

    /**
     * Some versions compile the generic as LivingEntity.
     * We set require=0 so if the descriptor differs, it won’t hard-fail.
     */
    @Redirect(
            method = "customServerAiStep",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/Brain;tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;)V"
            ),
            require = 0
    )
    private void ezvr$gateBrainTick_Living(Brain<LivingEntity> brain, ServerLevel level, LivingEntity entity) {
        if (entity instanceof Villager vill) {
            if (!VillagerBrain.shouldTickVanillaBrain(vill)) {
                return;
            }
        }
        brain.tick(level, entity);
    }

    /**
     * Some mappings/versions might have the param typed as Villager.
     * Keep a second redirect for that descriptor.
     */
    @Redirect(
            method = "customServerAiStep",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/Brain;tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/Villager;)V"
            ),
            require = 0
    )
    private void ezvr$gateBrainTick_Villager(Brain<Villager> brain, ServerLevel level, Villager vill) {
        if (!VillagerBrain.shouldTickVanillaBrain(vill)) {
            return;
        }
        brain.tick(level, vill);
    }
}
