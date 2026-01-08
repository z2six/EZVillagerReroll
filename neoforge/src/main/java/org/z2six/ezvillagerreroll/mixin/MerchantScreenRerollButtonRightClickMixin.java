// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/mixin/MerchantScreenRerollButtonRightClickMixin.java
package org.z2six.ezvillagerreroll.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MerchantMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.client.ClientRerollCooldownCache;
import org.z2six.ezvillagerreroll.client.ClientUI;

/**
 * RMB on our reroll button opens the auto-search catalog UI.
 * But: block while server cooldown is active.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class MerchantScreenRerollButtonRightClickMixin {

    @Inject(
            method = "mouseClicked(DDI)Z",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void ezvr$mouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        try {
            // RMB only
            if (button != 1) return;

            Minecraft mc = Minecraft.getInstance();
            Screen current = (mc == null) ? null : mc.screen;
            if (!(current instanceof MerchantScreen ms)) return;

            Button reroll = ClientUI.getRerollButtonFor(ms);
            if (reroll == null) return;

            if (!reroll.visible) return;
            if (!reroll.isMouseOver(mouseX, mouseY)) return;

            // NEW: cooldown gate
            int containerId = -1;
            try {
                if (ms.getMenu() instanceof MerchantMenu menu) containerId = menu.containerId;
            } catch (Throwable ignored) {}

            if (containerId >= 0 && ClientRerollCooldownCache.isCoolingDown(containerId)) {
                int rem = ClientRerollCooldownCache.getRemainingTicks(containerId);
                double sec = rem / 20.0;

                EZVillagerReroll.LOG().debug("[EZVR] RMB blocked: cooling down (containerId={} remTicks={})", containerId, rem);

                try {
                    if (mc != null && mc.player != null) {
                        mc.player.displayClientMessage(Component.literal(String.format("Reroll cooldown: %.1fs", sec)), true);
                    }
                } catch (Throwable ignoredMsg) {}

                cir.setReturnValue(true); // consume
                return;
            }

            int villagerEntityId = ClientUI.resolveTraderEntityId(ms);
            EZVillagerReroll.LOG().info("[EZVR] RMB on reroll button -> open catalog (villagerEntityId={})", villagerEntityId);

            ClientUI.openSearchCatalogScreen(ms, villagerEntityId);

            cir.setReturnValue(true); // consume
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] MerchantScreenRerollButtonRightClickMixin failed", t);
        }
    }
}
