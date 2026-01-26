package org.z2six.villageroverhaul.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.z2six.villageroverhaul.server.HelpChatCommandService;

/**
 * Records the last entity that attacked a player for the "Help" chat command.
 */
@Mixin(LivingEntity.class)
public abstract class HelpChatRecordPlayerHurtMixin {

    @Inject(method = "hurt", at = @At("HEAD"))
    private void ezvr$recordHurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!((Object) this instanceof ServerPlayer sp)) return;
            if (source == null) return;
            Entity attacker = null;
            try { attacker = source.getEntity(); } catch (Throwable ignored) { attacker = null; }
            if (!(attacker instanceof LivingEntity le)) return;
            if (!le.isAlive()) return;
            // Don't self-target.
            if (attacker == (Object) this) return;
            HelpChatCommandService.recordOwnerAttackedBy(sp, le.getUUID());
        } catch (Throwable ignored) {}
    }
}

