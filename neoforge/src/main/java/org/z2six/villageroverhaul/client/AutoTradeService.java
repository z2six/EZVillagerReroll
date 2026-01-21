// neoforge\src\main\java\org\z2six\villageroverhaul\client\AutoTradeService.java
package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.lang.reflect.Method;

/**
 * Client-only “spam sell” automation for MerchantScreen.
 *
 * Triggered via CTRL+RMB on a trade button (see mixin).
 * Performs normal vanilla UI actions:
 * - selects the offer via gameMode.handleInventoryButtonClick(containerId, offerIndex)
 * - shift-clicks result slot to execute the trade
 *
 * No server simulation / no direct inventory mutations.
 */
public final class AutoTradeService {

    private static final int RESULT_SLOT_INDEX = 2; // MerchantMenu output slot
    private static final int CLICK_EVERY_TICKS = 1; // 20 TPS => 20 trades/sec
    private static final int STALL_TICKS = 40; // stop after 2s with no progress
    private static final int MAX_RUNTIME_TICKS = 20 * 30; // safety: 30s

    private static State ACTIVE;

    private static Method HANDLE_BUTTON_CLICK;
    private static Method MENU_SET_SELECTION_HINT;
    private static Method MENU_TRY_MOVE_ITEMS;

    private static final class State {
        final int containerId;
        final int offerIndex;
        final long startedAtMs;

        int ticks;
        int ticksSinceProgress;
        int tradesObserved;
        int lastOfferUses = -1;
        int lastInvHash = 0;
        String stopReason = "";

        private State(int containerId, int offerIndex) {
            this.containerId = containerId;
            this.offerIndex = offerIndex;
            this.startedAtMs = System.currentTimeMillis();
        }
    }

    private AutoTradeService() {}

    public static boolean isActive() {
        return ACTIVE != null;
    }

    public static boolean isActiveFor(MerchantScreen screen) {
        try {
            if (ACTIVE == null || screen == null) return false;
            if (!(screen.getMenu() instanceof MerchantMenu mm)) return false;
            return mm.containerId == ACTIVE.containerId;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void start(MerchantScreen screen, int absoluteOfferIndex) {
        try {
            if (screen == null) return;
            if (!(screen.getMenu() instanceof MerchantMenu menu)) return;

            int cid = menu.containerId;
            if (cid < 0) return;

            MerchantOffer offer = safeGetOffer(menu.getOffers(), absoluteOfferIndex);
            if (offer == null) return;

            ItemStack out = ItemStack.EMPTY;
            try { out = offer.getResult(); } catch (Throwable ignored) {}
            if (out == null) out = ItemStack.EMPTY;

            // Only “sell” offers: output is emeralds.
            if (!out.is(Items.EMERALD)) {
                return;
            }

            ACTIVE = new State(cid, absoluteOfferIndex);

            // Try selecting once immediately.
            ensureOfferSelected(menu, absoluteOfferIndex);

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoTrade started (containerId={} offerIdx={})", cid, absoluteOfferIndex);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] AutoTrade start failed", t);
            ACTIVE = null;
        }
    }

    public static void stop(String reason) {
        try {
            if (ACTIVE == null) return;
            ACTIVE.stopReason = reason == null ? "" : reason;
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoTrade stopped (reason={})", ACTIVE.stopReason);
        } catch (Throwable ignored) {
        } finally {
            ACTIVE = null;
        }
    }

    public static void clientTick() {
        try {
            State st = ACTIVE;
            if (st == null) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.gameMode == null) {
                stop("no_client");
                return;
            }

            if (!(mc.screen instanceof MerchantScreen screen)) {
                stop("screen_closed");
                return;
            }

            if (!(screen.getMenu() instanceof MerchantMenu menu)) {
                stop("no_menu");
                return;
            }

            if (menu.containerId != st.containerId) {
                stop("container_changed");
                return;
            }

            st.ticks++;

            if (st.ticks > MAX_RUNTIME_TICKS) {
                stop("timeout");
                return;
            }

            MerchantOffer offer = safeGetOffer(menu.getOffers(), st.offerIndex);
            if (offer == null) {
                stop("offer_missing");
                return;
            }

            ItemStack out = ItemStack.EMPTY;
            try { out = offer.getResult(); } catch (Throwable ignored) {}
            if (out == null) out = ItemStack.EMPTY;
            if (!out.is(Items.EMERALD)) {
                stop("not_sell_offer");
                return;
            }

            // Stop if offer cannot be used anymore.
            try {
                Boolean outOfStock = ezvr$invokeBoolean(offer, "isOutOfStock");
                if (Boolean.TRUE.equals(outOfStock)) {
                    stop("out_of_stock");
                    return;
                }
            } catch (Throwable ignored) {}

            // Progress detection (best-effort): offer uses or inventory hash changes.
            boolean progressed = false;
            int usesNow = -1;
            try { usesNow = offer.getUses(); } catch (Throwable ignored) {}
            if (usesNow >= 0 && st.lastOfferUses >= 0 && usesNow != st.lastOfferUses) {
                progressed = true;
                st.tradesObserved++;
            }
            st.lastOfferUses = usesNow;

            int invHashNow = computeMenuInvHash(menu);
            if (st.lastInvHash != 0 && invHashNow != st.lastInvHash) {
                progressed = true;
            }
            st.lastInvHash = invHashNow;

            if (progressed) {
                st.ticksSinceProgress = 0;
            } else {
                st.ticksSinceProgress++;
            }

            if (st.ticksSinceProgress > STALL_TICKS) {
                stop("stalled");
                return;
            }

            if ((st.ticks % CLICK_EVERY_TICKS) != 0) return;

            // Ensure offer selection + inputs.
            ensureOfferSelected(menu, st.offerIndex);

            // Execute trade by shift-clicking output slot.
            mc.gameMode.handleInventoryMouseClick(menu.containerId, RESULT_SLOT_INDEX, 0, ClickType.QUICK_MOVE, mc.player);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] AutoTrade tick failed", t);
            stop("error");
        }
    }

    public static void renderStatus(GuiGraphics gg, MerchantScreen screen) {
        try {
            if (!isActiveFor(screen)) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            Font font = mc.font;

            String text = "Auto-trade running (ESC to stop)";
            int color = 0xFFCCCCCC;

            int left = ezvr$readContainerScreenInt(screen, "leftPos", "left_pos", "guiLeft", "left");
            int top = ezvr$readContainerScreenInt(screen, "topPos", "top_pos", "guiTop", "top");
            int imageH = ezvr$readContainerScreenInt(screen, "imageHeight", "image_h", "imageH", "height");

            int x = left;
            int y = top + imageH + 6;

            gg.drawString(font, Component.literal(text), x, y, color, false);
        } catch (Throwable ignored) {}
    }

    private static MerchantOffer safeGetOffer(MerchantOffers offers, int idx) {
        try {
            if (offers == null) return null;
            if (idx < 0 || idx >= offers.size()) return null;
            return offers.get(idx);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void ensureOfferSelected(MerchantMenu menu, int offerIdx) {
        try {
            if (menu == null) return;

            // Client-side selection hint so UI highlights correct offer.
            tryInvoke(menu, "setSelectionHint", new Class<?>[]{int.class}, new Object[]{offerIdx}, /*cache=*/1);

            // Move inputs from inventory into input slots (vanilla behavior).
            tryInvoke(menu, "tryMoveItems", new Class<?>[]{int.class}, new Object[]{offerIdx}, /*cache=*/2);

            // Notify server using the vanilla container button click packet.
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.gameMode == null) return;
            tryInvoke(mc.gameMode, "handleInventoryButtonClick", new Class<?>[]{int.class, int.class}, new Object[]{menu.containerId, offerIdx}, /*cache=*/0);
        } catch (Throwable ignored) {}
    }

    /**
     * cacheId: 0=gameMode.handleInventoryButtonClick, 1=menu.setSelectionHint, 2=menu.tryMoveItems
     */
    private static void tryInvoke(Object target, String name, Class<?>[] paramTypes, Object[] args, int cacheId) {
        try {
            if (target == null) return;

            Method m;
            if (cacheId == 0) m = HANDLE_BUTTON_CLICK;
            else if (cacheId == 1) m = MENU_SET_SELECTION_HINT;
            else m = MENU_TRY_MOVE_ITEMS;

            if (m == null || m.getDeclaringClass() != target.getClass()) {
                m = target.getClass().getMethod(name, paramTypes);
                m.setAccessible(true);
                if (cacheId == 0) HANDLE_BUTTON_CLICK = m;
                else if (cacheId == 1) MENU_SET_SELECTION_HINT = m;
                else MENU_TRY_MOVE_ITEMS = m;
            }

            m.invoke(target, args);
        } catch (Throwable ignored) {}
    }

    private static int computeMenuInvHash(MerchantMenu menu) {
        try {
            // Hash of input slots (0,1) + result slot (2)
            ItemStack a = ItemStack.EMPTY, b = ItemStack.EMPTY, c = ItemStack.EMPTY;
            try { a = menu.getSlot(0).getItem(); } catch (Throwable ignored) {}
            try { b = menu.getSlot(1).getItem(); } catch (Throwable ignored) {}
            try { c = menu.getSlot(2).getItem(); } catch (Throwable ignored) {}

            int h = 17;
            h = 31 * h + itemHash(a);
            h = 31 * h + itemHash(b);
            h = 31 * h + itemHash(c);
            return h;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int itemHash(ItemStack s) {
        try {
            if (s == null || s.isEmpty()) return 0;
            return (System.identityHashCode(s.getItem()) * 31) ^ s.getCount();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Boolean ezvr$invokeBoolean(Object target, String methodName) {
        try {
            if (target == null || methodName == null) return null;
            Method m = target.getClass().getMethod(methodName);
            m.setAccessible(true);
            Object r = m.invoke(target);
            return (r instanceof Boolean b) ? b : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int ezvr$readContainerScreenInt(Object screen, String... names) {
        try {
            if (screen == null) return 0;

            // Prefer name matches.
            for (String name : names) {
                if (name == null || name.isEmpty()) continue;
                Class<?> c = screen.getClass();
                while (c != null && c != Object.class) {
                    try {
                        var f = c.getDeclaredField(name);
                        if (f.getType() != int.class) throw new NoSuchFieldException();
                        f.setAccessible(true);
                        return f.getInt(screen);
                    } catch (NoSuchFieldException ignored) {
                        // keep walking
                    }
                    c = c.getSuperclass();
                }
            }

            // Fallback: heuristics
            String want = (names != null && names.length > 0 && names[0] != null) ? names[0].toLowerCase() : "";
            Class<?> c = screen.getClass();
            while (c != null && c != Object.class) {
                for (var f : c.getDeclaredFields()) {
                    try {
                        if (f.getType() != int.class) continue;
                        String n = f.getName();
                        if (n == null) continue;
                        String lower = n.toLowerCase();
                        if (!want.isEmpty() && !lower.contains(want.replace("pos", ""))) continue;
                        f.setAccessible(true);
                        return f.getInt(screen);
                    } catch (Throwable ignoredField) {}
                }
                c = c.getSuperclass();
            }

            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }
}
