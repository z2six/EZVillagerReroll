package org.z2six.villageroverhaul.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerConnectionNullGuardMixin {

    @Inject(method = "getPlayerInfo", at = @At("HEAD"), cancellable = true)
    private void villageroverhaul$returnNullWhenConnectionMissing(CallbackInfoReturnable<PlayerInfo> cir) {
        if (Minecraft.getInstance().getConnection() == null) {
            cir.setReturnValue(null);
        }
    }
}

