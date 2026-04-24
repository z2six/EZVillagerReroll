// neoforge\src\main\java\org\z2six\villageroverhaul\mixin\MerchantScreenAutoTradeCtrlRightClickMixin.java
package org.z2six.villageroverhaul.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.client.AutoTradeService;
import org.z2six.villageroverhaul.client.AutoTradeConfirmScreen;
import org.z2six.villageroverhaul.client.AutoTradeConsentStore;
import org.z2six.villageroverhaul.client.MerchantTradeButtonResolver;

/**
 * CTRL + RMB on a trade offer button starts auto-trade (client-only QoL).
 *
 * Implemented as an AbstractContainerScreen mouseClicked HEAD injection, like existing RMB hooks.
 * Uses higher mixin priority so it can consume CTRL-RMB before the trade-lock RMB handler.
 */
@Mixin(value = AbstractContainerScreen.class, priority = 2000)
public abstract class MerchantScreenAutoTradeCtrlRightClickMixin {

    @Inject(
            method = "mouseClicked(DDI)Z",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void ezvr$mouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        try {
            // CTRL + LMB only
            if (button != 0) return;
            if (!Screen.hasControlDown()) return;

            Minecraft mc = Minecraft.getInstance();
            Screen current = (mc == null) ? null : mc.screen;
            if (!(current instanceof MerchantScreen screen)) return;

            MerchantTradeButtonResolver.TradeButtonRef hovered = MerchantTradeButtonResolver.findHoveredTradeButton(screen, mouseX, mouseY);
            if (hovered == null) return;

            int rowIdx = hovered.rowIndex();
            if (rowIdx < 0 || rowIdx > 63) return;

            int offerCount = ezvr$safeOfferCount(screen);
            int scrollOff = MerchantTradeButtonResolver.getScrollOffset(screen, offerCount);
            int absoluteIdx = scrollOff + rowIdx;

            if (offerCount >= 0 && (absoluteIdx < 0 || absoluteIdx >= offerCount)) {
                return;
            }

            // Offer details for prompt + persistence (ignore counts/discounts).
            ItemStack buyA = ItemStack.EMPTY;
            ItemStack buyB = ItemStack.EMPTY;
            ItemStack sell = ItemStack.EMPTY;
            try {
                var offer = screen.getMenu().getOffers().get(absoluteIdx);
                buyA = offer.getBaseCostA();
                buyB = offer.getCostB();
                sell = offer.getResult();
            } catch (Throwable ignored) {}

            if (sell == null) sell = ItemStack.EMPTY;

            String key = AutoTradeConsentStore.keyFor(buyA, buyB, sell);
            AutoTradeConsentStore.Decision d = AutoTradeConsentStore.getDecision(key);
            if (d == AutoTradeConsentStore.Decision.ALLOW) {
                AutoTradeService.start(screen, absoluteIdx);
            } else if (d == AutoTradeConsentStore.Decision.DENY) {
                // Do nothing (user opted out).
                return;
            } else {
                // Ask once.
                if (mc != null) {
                    mc.setScreen(new AutoTradeConfirmScreen(screen, absoluteIdx, key, buyA, buyB, sell));
                }
            }

            // Consume so vanilla doesn't treat this click as a normal trade selection/click.
            cir.setReturnValue(true);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] MerchantScreenAutoTradeCtrlRightClickMixin error", t);
        }
    }

    @Unique
    private static int ezvr$safeOfferCount(MerchantScreen screen) {
        try {
            if (screen == null) return -1;
            if (!(screen.getMenu() instanceof MerchantMenu mm)) return -1;
            var offers = mm.getOffers();
            return offers == null ? -1 : offers.size();
        } catch (Throwable t) {
            return -1;
        }
    }

}
