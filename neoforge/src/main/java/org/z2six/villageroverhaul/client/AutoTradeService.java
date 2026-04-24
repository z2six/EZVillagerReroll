// neoforge\src\main\java\org\z2six\villageroverhaul\client\AutoTradeService.java
package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.client.ClientNetwork;
import org.z2six.villageroverhaul.network.autotrade.PacketAutoTradeStart;
import org.z2six.villageroverhaul.network.autotrade.PacketAutoTradeStop;

/**
 * Client-only merchant auto-trade controller.
 *
 * Triggered via CTRL+LMB on a trade button (see mixin).
 * Server performs the actual MerchantMenu actions to avoid client-side desync.
 */
public final class AutoTradeService {

    private static final int MAX_RUNTIME_TICKS = 20 * 30; // safety: 30s

    private static State ACTIVE;

    private static final class State {
        final int containerId;
        final int offerIndex;
        final long startedAtMs;

        int ticks;
        String lastReason = "";

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

            ACTIVE = new State(cid, absoluteOfferIndex);
            ACTIVE.lastReason = "start_client";

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [autotrade] client_start containerId={} offerIdx={}", cid, absoluteOfferIndex);

            ClientNetwork.sendToServer(new PacketAutoTradeStart(cid, absoluteOfferIndex));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] AutoTrade start failed", t);
            ACTIVE = null;
        }
    }

    public static void stop(String reason) {
        try {
            if (ACTIVE == null) return;
            String r = reason == null ? "" : reason;
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [autotrade] client_stop_req containerId={} reason={}", ACTIVE.containerId, r);

            try {
                ClientNetwork.sendToServer(new PacketAutoTradeStop(ACTIVE.containerId));
            } catch (Throwable ignored) {}
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

    private static int ezvr$readContainerScreenInt(Object screen, String... names) {
        try {
            if (screen == null) return 0;

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
                    }
                    c = c.getSuperclass();
                }
            }

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

    public static void acceptServerState(int containerId, boolean active, String reason) {
        try {
            String r = reason == null ? "" : reason;

            if (!active) {
                if (ACTIVE != null && ACTIVE.containerId == containerId) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] [autotrade] client_stop containerId={} reason={}", containerId, r);
                    ACTIVE = null;
                }
                return;
            }

            if (ACTIVE == null || ACTIVE.containerId != containerId) {
                ACTIVE = new State(containerId, -1);
            }
            ACTIVE.lastReason = r;
        } catch (Throwable ignored) {}
    }
}
