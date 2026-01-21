// neoforge\src\main\java\org\z2six\villageroverhaul\mixin\MerchantScreenAutoTradeCtrlRightClickMixin.java
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
import org.z2six.villageroverhaul.client.AutoTradeService;

import java.lang.reflect.Field;
import java.util.List;

/**
 * CTRL + RMB on a trade offer button starts auto-trade (client-only QoL).
 *
 * Implemented as an AbstractContainerScreen mouseClicked HEAD injection, like existing RMB hooks.
 * Uses higher mixin priority so it can consume CTRL-RMB before the trade-lock RMB handler.
 */
@Mixin(value = AbstractContainerScreen.class, priority = 2000)
public abstract class MerchantScreenAutoTradeCtrlRightClickMixin {

    @Unique
    private static final String VillagerOverhaul_TRADE_BUTTON_CLASS =
            "net.minecraft.client.gui.screens.inventory.MerchantScreen$TradeOfferButton";

    @Inject(
            method = "mouseClicked(DDI)Z",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void ezvr$mouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        try {
            // CTRL + RMB only
            if (button != 1) return;
            if (!Screen.hasControlDown()) return;

            Minecraft mc = Minecraft.getInstance();
            Screen current = (mc == null) ? null : mc.screen;
            if (!(current instanceof MerchantScreen screen)) return;

            AbstractWidget hovered = ezvr$findHoveredTradeButton(screen, mouseX, mouseY);
            if (hovered == null) return;

            int rowIdx = ezvr$readTradeButtonRowIndexReflective(hovered);
            if (rowIdx < 0 || rowIdx > 63) return;

            int offerCount = ezvr$safeOfferCount(screen);
            int scrollOff = ezvr$getScrollOffsetSafe(screen, offerCount);
            int absoluteIdx = scrollOff + rowIdx;

            if (offerCount >= 0 && (absoluteIdx < 0 || absoluteIdx >= offerCount)) {
                return;
            }

            AutoTradeService.start(screen, absoluteIdx);

            // Consume so trade-lock RMB handler doesn't fire while CTRL is held.
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

    @Unique
    private static int ezvr$getScrollOffsetSafe(MerchantScreen screen, int offerCount) {
        try {
            int raw = ((MerchantScreenAccessor) screen).ezvr$getScrollOff();
            if (offerCount <= 0) return Math.max(0, raw);
            int maxScroll = Math.max(0, offerCount - 7);
            if (raw < 0) return 0;
            if (raw > maxScroll) return maxScroll;
            return raw;
        } catch (Throwable t) {
            return 0;
        }
    }

    @Unique
    private static AbstractWidget ezvr$findHoveredTradeButton(MerchantScreen screen, double mouseX, double mouseY) {
        try {
            AbstractWidget w = ezvr$scanChildrenForHoveredTradeButton(screen.children(), mouseX, mouseY);
            if (w != null) return w;
            return ezvr$scanViaReflection(screen, mouseX, mouseY);
        } catch (Throwable t) {
            return null;
        }
    }

    @Unique
    private static AbstractWidget ezvr$scanChildrenForHoveredTradeButton(List<? extends GuiEventListener> list, double mouseX, double mouseY) {
        try {
            if (list == null || list.isEmpty()) return null;
            for (GuiEventListener child : list) {
                if (!(child instanceof AbstractWidget w)) continue;
                if (!VillagerOverhaul_TRADE_BUTTON_CLASS.equals(w.getClass().getName())) continue;
                if (!w.visible) continue;
                if (!w.isMouseOver(mouseX, mouseY)) continue;
                return w;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Unique
    private static AbstractWidget ezvr$scanViaReflection(MerchantScreen screen, double mouseX, double mouseY) {
        try {
            Class<?> c = screen.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (!List.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object v = f.get(screen);
                    if (!(v instanceof List<?> list) || list.isEmpty()) continue;
                    for (Object o : list) {
                        if (!(o instanceof AbstractWidget w)) continue;
                        if (!VillagerOverhaul_TRADE_BUTTON_CLASS.equals(w.getClass().getName())) continue;
                        if (!w.visible) continue;
                        if (!w.isMouseOver(mouseX, mouseY)) continue;
                        return w;
                    }
                }
                c = c.getSuperclass();
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Unique
    private static int ezvr$readTradeButtonRowIndexReflective(Object tradeButtonWidget) {
        try {
            Class<?> c = tradeButtonWidget.getClass();

            Field f = null;
            try { f = c.getDeclaredField("index"); } catch (NoSuchFieldException ignored) {}

            if (f == null) {
                for (Field candidate : c.getDeclaredFields()) {
                    if (candidate.getType() != int.class) continue;
                    String n = candidate.getName();
                    if (n != null && (n.equals("index") || n.toLowerCase().contains("index"))) {
                        f = candidate;
                        break;
                    }
                }
            }

            if (f == null) return -1;
            f.setAccessible(true);
            return f.getInt(tradeButtonWidget);
        } catch (Throwable t) {
            return -1;
        }
    }
}

