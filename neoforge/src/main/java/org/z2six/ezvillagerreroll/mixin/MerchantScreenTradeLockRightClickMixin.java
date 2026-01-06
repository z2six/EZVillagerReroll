// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/mixin/MerchantScreenTradeLockRightClickMixin.java
package org.z2six.ezvillagerreroll.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.inventory.MerchantMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.network.Network;
import org.z2six.ezvillagerreroll.network.PacketToggleTradeLock;

import java.lang.reflect.Field;
import java.util.List;

/**
 * RMB on a trade offer toggles lock.
 *
 * We inject into AbstractContainerScreen#mouseClicked because MerchantScreen may not override mouseClicked
 * (so a MerchantScreen-targeted inject can be a silent no-op).
 *
 * We do NOT reference MerchantScreen.TradeOfferButton (package-private).
 * We detect it by runtime class name and read its "index" field reflectively.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class MerchantScreenTradeLockRightClickMixin {

    @Unique
    private static final String EZVR_TRADE_BUTTON_CLASS =
            "net.minecraft.client.gui.screens.inventory.MerchantScreen$TradeOfferButton";

    @Inject(
            method = "mouseClicked(DDI)Z",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void ezvr$mouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        try {
            // Only RMB
            if (button != 1) return;

            Minecraft mc = Minecraft.getInstance();
            Screen current = mc == null ? null : mc.screen;
            if (!(current instanceof MerchantScreen screen)) return;

            // INFO (not debug) so you WILL see it in normal logs
            EZVillagerReroll.LOG().info("[EZVR] RMB on MerchantScreen at ({}, {})", mouseX, mouseY);

            int traderId = ezvr$resolveTraderEntityId(screen);
            if (traderId < 0) {
                EZVillagerReroll.LOG().info("[EZVR] RMB MerchantScreen: could not resolve trader entity id.");
                return;
            }

            int idx = ezvr$findHoveredTradeIndex(screen, mouseX, mouseY);
            if (idx < 0) {
                EZVillagerReroll.LOG().info("[EZVR] RMB MerchantScreen: not hovering a trade offer widget.");
                return;
            }

            if (idx > 63) {
                EZVillagerReroll.LOG().warn("[EZVR] RMB MerchantScreen: trade index {} > 63; ignoring for safety.", idx);
                return;
            }

            Network.sendToServer(new PacketToggleTradeLock(traderId, idx));
            EZVillagerReroll.LOG().info("[EZVR] Toggled trade lock: traderId={}, index={}", traderId, idx);

            // Consume RMB so vanilla doesn't handle it
            cir.setReturnValue(true);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] MerchantScreenTradeLockRightClickMixin error", t);
        }
    }

    @Unique
    private static int ezvr$resolveTraderEntityId(MerchantScreen screen) {
        try {
            if (!(screen.getMenu() instanceof MerchantMenu menu)) return -1;

            Object trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (trader instanceof Entity ent) return ent.getId();
            if (trader instanceof AbstractVillager av) return av.getId();
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    @Unique
    private static int ezvr$findHoveredTradeIndex(MerchantScreen screen, double mouseX, double mouseY) {
        try {
            // Primary: screen.children() is the supported API
            int idx = ezvr$scanChildrenForTradeIndex(screen.children(), mouseX, mouseY);
            if (idx >= 0) return idx;

            // Fallback: reflection scan for any list-like fields that may contain widgets/listeners
            idx = ezvr$scanViaReflection(screen, mouseX, mouseY);
            return idx;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ezvr$findHoveredTradeIndex failed", t);
            return -1;
        }
    }

    @Unique
    private static int ezvr$scanChildrenForTradeIndex(List<? extends GuiEventListener> list, double mouseX, double mouseY) {
        try {
            if (list == null || list.isEmpty()) return -1;

            for (GuiEventListener child : list) {
                if (!(child instanceof AbstractWidget w)) continue;

                String cn = w.getClass().getName();
                if (!EZVR_TRADE_BUTTON_CLASS.equals(cn)) continue;

                if (!w.isMouseOver(mouseX, mouseY)) continue;

                int index = ezvr$readTradeButtonIndexReflective(w);
                EZVillagerReroll.LOG().info("[EZVR] Hovered trade widget found via children() -> class={}, index={}", cn, index);
                return index;
            }

            return -1;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] scanChildrenForTradeIndex failed", t);
            return -1;
        }
    }

    @Unique
    private static int ezvr$scanViaReflection(MerchantScreen screen, double mouseX, double mouseY) {
        try {
            // Walk the class hierarchy and scan declared fields for List<?> that contains AbstractWidget instances.
            // This is intentionally defensive: Mojang can reshuffle widget storage.
            Class<?> c = screen.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (!List.class.isAssignableFrom(f.getType())) continue;

                    f.setAccessible(true);
                    Object v = f.get(screen);
                    if (!(v instanceof List<?> list) || list.isEmpty()) continue;

                    // Try scanning this list for the trade offer widgets
                    for (Object o : list) {
                        if (!(o instanceof AbstractWidget w)) continue;

                        String cn = w.getClass().getName();
                        if (!EZVR_TRADE_BUTTON_CLASS.equals(cn)) continue;

                        if (!w.isMouseOver(mouseX, mouseY)) continue;

                        int index = ezvr$readTradeButtonIndexReflective(w);
                        EZVillagerReroll.LOG().info("[EZVR] Hovered trade widget found via reflection -> field={}, class={}, index={}",
                                f.getName(), cn, index);
                        return index;
                    }
                }
                c = c.getSuperclass();
            }

            return -1;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] scanViaReflection failed", t);
            return -1;
        }
    }

    @Unique
    private static int ezvr$readTradeButtonIndexReflective(Object tradeButtonWidget) {
        try {
            Class<?> c = tradeButtonWidget.getClass();

            Field f = null;
            try {
                f = c.getDeclaredField("index");
            } catch (NoSuchFieldException ignored) {}

            if (f == null) {
                // Defensive scan for an int field that looks like index
                for (Field candidate : c.getDeclaredFields()) {
                    if (candidate.getType() != int.class) continue;
                    String n = candidate.getName();
                    if (n != null && (n.equals("index") || n.toLowerCase().contains("index"))) {
                        f = candidate;
                        break;
                    }
                }
            }

            if (f == null) {
                EZVillagerReroll.LOG().info("[EZVR] TradeOfferButton: no int index-like field found on {}", c.getName());
                return -1;
            }

            f.setAccessible(true);
            return f.getInt(tradeButtonWidget);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().info("[EZVR] TradeOfferButton: reflective index read failed: {}", t.toString());
            return -1;
        }
    }
}
