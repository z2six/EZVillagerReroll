package org.z2six.villageroverhaul.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.z2six.villageroverhaul.server.HelpChatCommandService;

/**
 * Records the last entity a player attacked for the "Help" chat command.
 */
@Mixin(Player.class)
public abstract class HelpChatRecordPlayerAttackMixin {

    @Inject(method = "attack", at = @At("HEAD"))
    private void ezvr$recordAttack(Entity target, CallbackInfo ci) {
        try {
            if (!((Object) this instanceof ServerPlayer sp)) return;
            if (!(target instanceof LivingEntity le)) return;
            if (!le.isAlive()) return;
            HelpChatCommandService.recordOwnerAttacked(sp, le.getUUID());
        } catch (Throwable ignored) {}
    }
}

