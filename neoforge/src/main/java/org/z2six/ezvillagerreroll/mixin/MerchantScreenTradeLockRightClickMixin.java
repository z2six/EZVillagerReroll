// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/mixin/MerchantScreenTradeLockRightClickMixin.java
package org.z2six.ezvillagerreroll.mixin;

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
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.network.ClientTradeLockCache;
import org.z2six.ezvillagerreroll.network.Network;
import org.z2six.ezvillagerreroll.network.PacketToggleTradeLock;
import org.z2six.ezvillagerreroll.network.PacketTradeLocks;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Right-click on a trade offer button toggles lock.
 *
 * Note: client-side MerchantMenu.trader may not be an Entity/Villager, so we key visuals by containerId (menu syncId).
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
            // RMB only
            if (button != 1) return;

            Minecraft mc = Minecraft.getInstance();
            Screen current = (mc == null) ? null : mc.screen;
            if (!(current instanceof MerchantScreen screen)) return;

            EZVillagerReroll.LOG().info("[EZVR] RMB on MerchantScreen at ({}, {})", mouseX, mouseY);

            int idx = ezvr$findHoveredTradeIndex(screen, mouseX, mouseY);
            if (idx < 0) {
                EZVillagerReroll.LOG().info("[EZVR] RMB MerchantScreen: not hovering a trade offer widget.");
                return;
            }

            if (idx > 63) {
                EZVillagerReroll.LOG().warn("[EZVR] RMB MerchantScreen: trade index {} > 63; ignoring for safety.", idx);
                return;
            }

            // Send server-authoritative toggle request (server will compute villager + persist mask)
            Network.sendToServer(new PacketToggleTradeLock(idx));
            EZVillagerReroll.LOG().info("[EZVR] Sent PacketToggleTradeLock(idx={})", idx);

            // Optimistic local toggle (visuals) keyed by containerId
            int cid = ezvr$getContainerId(screen);
            if (cid >= 0) {
                long oldMask = ClientTradeLockCache.getMaskForContainer(cid);
                long nextMask = oldMask ^ (1L << idx);

                ClientTradeLockCache.set(new PacketTradeLocks(cid, nextMask));

                EZVillagerReroll.LOG().info("[EZVR] Optimistic mask: containerId={} old={} next={}",
                        cid, Long.toUnsignedString(oldMask), Long.toUnsignedString(nextMask));
            } else {
                EZVillagerReroll.LOG().warn("[EZVR] Optimistic mask skipped: could not resolve containerId");
            }

            // Consume click so vanilla doesn't treat RMB as something else
            cir.setReturnValue(true);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] MerchantScreenTradeLockRightClickMixin error", t);
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
    private static int ezvr$findHoveredTradeIndex(MerchantScreen screen, double mouseX, double mouseY) {
        try {
            int idx = ezvr$scanChildrenForTradeIndex(screen.children(), mouseX, mouseY);
            if (idx >= 0) return idx;

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
                EZVillagerReroll.LOG().info("[EZVR] Hovered trade widget via children() -> class={}, index={}", cn, index);
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
            Class<?> c = screen.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (!List.class.isAssignableFrom(f.getType())) continue;

                    f.setAccessible(true);
                    Object v = f.get(screen);
                    if (!(v instanceof List<?> list) || list.isEmpty()) continue;

                    for (Object o : list) {
                        if (!(o instanceof AbstractWidget w)) continue;

                        String cn = w.getClass().getName();
                        if (!EZVR_TRADE_BUTTON_CLASS.equals(cn)) continue;

                        if (!w.isMouseOver(mouseX, mouseY)) continue;
                        int index = ezvr$readTradeButtonIndexReflective(w);
                        EZVillagerReroll.LOG().info("[EZVR] Hovered trade widget via reflection -> field={}, index={}", f.getName(), index);
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
