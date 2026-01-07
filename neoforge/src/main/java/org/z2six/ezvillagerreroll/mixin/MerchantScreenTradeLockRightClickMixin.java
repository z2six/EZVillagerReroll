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
 * IMPORTANT:
 * The TradeOfferButton's 'index' is the visible row index (0..6). Absolute offer index is:
 *   absoluteIndex = scrollOffset + rowIndex
 *
 * We compute this consistently for both toggling and rendering.
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

            AbstractWidget hovered = ezvr$findHoveredTradeButton(screen, mouseX, mouseY);
            if (hovered == null) {
                return;
            }

            int rowIdx = ezvr$readTradeButtonRowIndexReflective(hovered);
            if (rowIdx < 0 || rowIdx > 63) {
                EZVillagerReroll.LOG().debug("[EZVR] RMB MerchantScreen: could not read trade row index (rowIdx={})", rowIdx);
                return;
            }

            int offerCount = ezvr$safeOfferCount(screen);
            int scrollOff = ezvr$getScrollOffsetSafe(screen, offerCount);
            int absoluteIdx = scrollOff + rowIdx;

            // Guard hard: only allow within actual offers
            if (offerCount >= 0 && (absoluteIdx < 0 || absoluteIdx >= offerCount)) {
                EZVillagerReroll.LOG().debug("[EZVR] RMB MerchantScreen: computed absoluteIdx out of range (rowIdx={}, scrollOff={}, absoluteIdx={}, offerCount={})",
                        rowIdx, scrollOff, absoluteIdx, offerCount);
                return;
            }

            if (absoluteIdx > 63) {
                EZVillagerReroll.LOG().warn("[EZVR] RMB MerchantScreen: absolute trade index {} > 63; ignoring for safety.", absoluteIdx);
                return;
            }

            // Send server-authoritative toggle request (server will compute villager + persist mask)
            Network.sendToServer(new PacketToggleTradeLock(absoluteIdx));
            EZVillagerReroll.LOG().debug("[EZVR] Sent PacketToggleTradeLock(absoluteIdx={}) (rowIdx={}, scrollOff={})", absoluteIdx, rowIdx, scrollOff);

            // Optimistic local toggle (visuals) keyed by containerId
            int cid = ezvr$getContainerId(screen);
            if (cid >= 0) {
                long oldMask = ClientTradeLockCache.getMaskForContainer(cid);
                long nextMask = oldMask ^ (1L << absoluteIdx);

                ClientTradeLockCache.set(new PacketTradeLocks(cid, nextMask));

                EZVillagerReroll.LOG().debug("[EZVR] Optimistic mask: containerId={} old={} next={} (absoluteIdx={})",
                        cid, Long.toUnsignedString(oldMask), Long.toUnsignedString(nextMask), absoluteIdx);
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
    private static int ezvr$getScrollOffsetSafe(MerchantScreen screen, int offerCount) {
        try {
            // Prefer accessor (correct field) when available
            try {
                int raw = ((MerchantScreenAccessor) screen).ezvr$getScrollOff();
                return ezvr$clampScroll(raw, offerCount);
            } catch (Throwable ignored) {
                // fall through
            }

            // Fallback reflection: only accept fields with "scroll" in name AND value within max scroll range.
            int maxScroll = Math.max(0, offerCount - 7); // vanilla shows 7 rows
            Class<?> c = screen.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        if (f.getType() != int.class) continue;
                        String n = f.getName();
                        if (n == null) continue;
                        String lower = n.toLowerCase();
                        if (!lower.contains("scroll")) continue;

                        f.setAccessible(true);
                        int v = f.getInt(screen);
                        if (v >= 0 && v <= maxScroll) {
                            return v;
                        }
                    } catch (Throwable ignoredField) {}
                }
                c = c.getSuperclass();
            }

            return 0;
        } catch (Throwable t) {
            return 0;
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

    @Unique
    private static AbstractWidget ezvr$findHoveredTradeButton(MerchantScreen screen, double mouseX, double mouseY) {
        try {
            // First try the normal children list (most reliable)
            AbstractWidget w = ezvr$scanChildrenForHoveredTradeButton(screen.children(), mouseX, mouseY);
            if (w != null) return w;

            // Fallback: walk declared fields looking for widget lists
            return ezvr$scanViaReflection(screen, mouseX, mouseY);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ezvr$findHoveredTradeButton failed", t);
            return null;
        }
    }

    @Unique
    private static AbstractWidget ezvr$scanChildrenForHoveredTradeButton(List<? extends GuiEventListener> list, double mouseX, double mouseY) {
        try {
            if (list == null || list.isEmpty()) return null;

            for (GuiEventListener child : list) {
                if (!(child instanceof AbstractWidget w)) continue;
                String cn = w.getClass().getName();
                if (!EZVR_TRADE_BUTTON_CLASS.equals(cn)) continue;
                if (!w.visible) continue;
                if (!w.isMouseOver(mouseX, mouseY)) continue;
                return w;
            }

            return null;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] scanChildrenForHoveredTradeButton failed", t);
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
                        String cn = w.getClass().getName();
                        if (!EZVR_TRADE_BUTTON_CLASS.equals(cn)) continue;
                        if (!w.visible) continue;
                        if (!w.isMouseOver(mouseX, mouseY)) continue;
                        return w;
                    }
                }
                c = c.getSuperclass();
            }
            return null;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] scanViaReflection failed", t);
            return null;
        }
    }

    @Unique
    private static int ezvr$readTradeButtonRowIndexReflective(Object tradeButtonWidget) {
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

            if (f == null) return -1;

            f.setAccessible(true);
            return f.getInt(tradeButtonWidget);

        } catch (Throwable t) {
            return -1;
        }
    }
}
