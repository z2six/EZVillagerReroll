// neoforge\src\main\java\org\z2six\villageroverhaul\mixin\MerchantScreenTradeLockRightClickMixin.java
package org.z2six.villageroverhaul.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.world.inventory.MerchantMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.client.MerchantTradeButtonResolver;
import org.z2six.villageroverhaul.network.ClientTradeLockCache;
import org.z2six.villageroverhaul.network.Network;
import org.z2six.villageroverhaul.network.trades.PacketToggleTradeLock;
import org.z2six.villageroverhaul.network.trades.PacketTradeLocks;

/**
 * Right-click on a trade offer button toggles lock.
 *
 * IMPORTANT:
 * The TradeOfferButton's 'index' is the visible row index (0..6). Absolute offer index is:
 *   absoluteIndex = scrollOffset + rowIndex
 *
 * We compute this consistently for both toggling and rendering.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class MerchantScreenTradeLockRightClickMixin {

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
            if (!(current instanceof MerchantScreen screen)) return;

            VillagerOverhaul.LOG().info("[VillagerOverhaul] trade-lock RMB detected at x={} y={}", mouseX, mouseY);

            MerchantTradeButtonResolver.TradeButtonRef hovered = MerchantTradeButtonResolver.findHoveredTradeButton(screen, mouseX, mouseY);
            if (hovered == null) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] trade-lock RMB: no hovered trade button found");
                return;
            }

            int rowIdx = hovered.rowIndex();
            if (rowIdx < 0 || rowIdx > 63) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] RMB MerchantScreen: could not read trade row index (rowIdx={})", rowIdx);
                return;
            }

            int offerCount = ezvr$safeOfferCount(screen);
            int scrollOff = MerchantTradeButtonResolver.getScrollOffset(screen, offerCount);
            int absoluteIdx = scrollOff + rowIdx;

            // Guard hard: only allow within actual offers
            if (offerCount >= 0 && (absoluteIdx < 0 || absoluteIdx >= offerCount)) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] RMB MerchantScreen: computed absoluteIdx out of range (rowIdx={}, scrollOff={}, absoluteIdx={}, offerCount={})",
                        rowIdx, scrollOff, absoluteIdx, offerCount);
                return;
            }

            if (absoluteIdx > 63) {
                VillagerOverhaul.LOG().warn("[VillagerOverhaul] RMB MerchantScreen: absolute trade index {} > 63; ignoring for safety.", absoluteIdx);
                return;
            }

            // Send server-authoritative toggle request (server will compute villager + persist mask)
            Network.sendToServer(new PacketToggleTradeLock(absoluteIdx));
            VillagerOverhaul.LOG().info("[VillagerOverhaul] trade-lock RMB: sending toggle packet absoluteIdx={} rowIdx={} scrollOff={} offerCount={}",
                    absoluteIdx, rowIdx, scrollOff, offerCount);

            // Optimistic local toggle (visuals) keyed by containerId
            int cid = ezvr$getContainerId(screen);
            if (cid >= 0) {
                long oldMask = ClientTradeLockCache.getMaskForContainer(cid);
                long nextMask = oldMask ^ (1L << absoluteIdx);

                ClientTradeLockCache.set(new PacketTradeLocks(cid, nextMask));

                VillagerOverhaul.LOG().info("[VillagerOverhaul] trade-lock RMB: optimistic mask containerId={} old={} next={} absoluteIdx={}",
                        cid, Long.toUnsignedString(oldMask), Long.toUnsignedString(nextMask), absoluteIdx);
            }

            // Consume click so vanilla doesn't treat RMB as something else
            cir.setReturnValue(true);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] MerchantScreenTradeLockRightClickMixin error", t);
        }
    }

    @Unique
    private static int ezvr$getContainerId(MerchantScreen screen) {
        try {
            if (screen == null) return -1;
            if (!(screen.getMenu() instanceof MerchantMenu menu)) return -1;
            return menu.containerId;
        } catch (Throwable t) {
            return -1;
        }
    }

    @Unique
    private static int ezvr$safeOfferCount(MerchantScreen screen) {
        try {
            if (screen == null) return -1;
            if (!(screen.getMenu() instanceof MerchantMenu menu)) return -1;
            var offers = menu.getOffers();
            return offers == null ? 0 : offers.size();
        } catch (Throwable t) {
            return -1;
        }
    }

    @Unique
    private static int ezvr$clampScroll(int scrollOff, int offerCount) {
        try {
            int maxScroll = Math.max(0, offerCount - 7);
            if (scrollOff < 0) return 0;
            if (scrollOff > maxScroll) return maxScroll;
            return scrollOff;
        } catch (Throwable t) {
            return 0;
        }
    }

}
