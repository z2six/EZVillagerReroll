// neoforge\src\main\java\org\z2six\villageroverhaul\mixin\MerchantScreenAutoTradeEscStopMixin.java
package org.z2six.villageroverhaul.mixin;

import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.z2six.villageroverhaul.client.AutoTradeService;

/**
 * While auto-trade is active, pressing ESC stops it (and keeps the screen open).
 */
@Mixin(value = MerchantScreen.class, priority = 2000)
public abstract class MerchantScreenAutoTradeEscStopMixin {

    private static final int KEY_ESCAPE = 256; // GLFW.GLFW_KEY_ESCAPE

    @Inject(
            method = "keyPressed(III)Z",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void ezvr$keyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (keyCode != KEY_ESCAPE) return;
            if (!AutoTradeService.isActive()) return;

            AutoTradeService.stop("esc");
            cir.setReturnValue(true);
        } catch (Throwable ignored) {}
    }
}

