// neoforge\src\main\java\org\z2six\villageroverhaul\server\AutoTradeServerService.java
package org.z2six.villageroverhaul.server;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.autotrade.PacketAutoTradeState;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side auto-trade executor (uses vanilla MerchantMenu click paths).
 *
 * This is intentionally server-driven to avoid client-only desync where trades appear to happen but rollback.
 */
public final class AutoTradeServerService {

    private static final int INPUT_A = 0;
    private static final int INPUT_B = 1;
    private static final int OUTPUT = 2;

    private static final int MAX_TRADES_PER_TICK = 5;
    private static final int NO_PROGRESS_TICKS_LIMIT = 40;
    private static final int MAX_SESSION_TICKS = 20 * 60; // 60s

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private static final class Session {
        final UUID playerId;
        final int containerId;
        final int offerIndex;

        int ticks;
        int noProgressTicks;
        int totalTrades;
        int lastUses = -1;

        private Session(ServerPlayer sp, int containerId, int offerIndex) {
            this.playerId = sp.getUUID();
            this.containerId = containerId;
            this.offerIndex = offerIndex;
        }
    }

    private AutoTradeServerService() {}

    public static void start(ServerPlayer sp, int containerId, int offerIndex) {
        try {
            if (sp == null) return;
            if (!(sp.containerMenu instanceof MerchantMenu menu)) {
                sendState(sp, containerId, false, "no_merchant_menu");
                return;
            }
            if (menu.containerId != containerId) {
                sendState(sp, containerId, false, "container_mismatch");
                return;
            }

            MerchantOffer offer = safeOffer(menu, offerIndex);
            if (offer == null) {
                sendState(sp, containerId, false, "offer_missing");
                return;
            }

            // Clear stale inputs first.
            tryClearInputs(sp, menu);

            Session s = new Session(sp, containerId, offerIndex);
            s.lastUses = safeUses(offer);
            SESSIONS.put(sp.getUUID(), s);

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [autotrade] start player={} containerId={} offerIdx={}",
                    sp.getGameProfile().getName(), containerId, offerIndex);

            sendState(sp, containerId, true, "started");
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] AutoTradeServerService.start failed", t);
            try { sendState(sp, containerId, false, "error"); } catch (Throwable ignored) {}
        }
    }

    public static void stop(ServerPlayer sp, int containerId, String reason) {
        try {
            if (sp == null) return;
            SESSIONS.remove(sp.getUUID());

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [autotrade] stop player={} containerId={} reason={}",
                    sp.getGameProfile().getName(), containerId, reason == null ? "" : reason);

            sendState(sp, containerId, false, reason == null ? "" : reason);
        } catch (Throwable ignored) {}
    }

    public static void tick(MinecraftServer server) {
        try {
            if (server == null) return;
            if (SESSIONS.isEmpty()) return;

            // Iterate players so we can resolve to ServerPlayer reliably.
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                if (sp == null) continue;
                Session s = SESSIONS.get(sp.getUUID());
                if (s == null) continue;

                s.ticks++;
                if (s.ticks > MAX_SESSION_TICKS) {
                    stop(sp, s.containerId, "timeout");
                    continue;
                }

                if (!(sp.containerMenu instanceof MerchantMenu menu) || menu.containerId != s.containerId) {
                    stop(sp, s.containerId, "menu_closed");
                    continue;
                }

                MerchantOffer offer = safeOffer(menu, s.offerIndex);
                if (offer == null) {
                    stop(sp, s.containerId, "offer_missing");
                    continue;
                }

                boolean outOfStock = false;
                try { outOfStock = offer.isOutOfStock(); } catch (Throwable ignored) {}
                if (outOfStock) {
                    stop(sp, s.containerId, "out_of_stock");
                    continue;
                }

                int beforeUses = safeUses(offer);
                if (s.lastUses < 0) s.lastUses = beforeUses;

                int tradesThisTick = 0;
                boolean progressed = false;

                while (tradesThisTick < MAX_TRADES_PER_TICK) {
                    // Ensure correct selection and input movement (vanilla flow).
                    tryInvokeMenuInt(menu, "setSelectionHint", s.offerIndex);
                    tryInvokeMenuInt(menu, "tryMoveItems", s.offerIndex);

                    ItemStack outputNow = ItemStack.EMPTY;
                    try { outputNow = menu.getSlot(OUTPUT).getItem(); } catch (Throwable ignored) {}
                    if (outputNow == null) outputNow = ItemStack.EMPTY;

                    // No output => no valid input items, stop.
                    if (outputNow.isEmpty()) {
                        stop(sp, s.containerId, "no_output");
                        progressed = true; // handled
                        break;
                    }

                    // Execute trade using vanilla server click path.
                    try {
                        menu.clicked(OUTPUT, 0, ClickType.QUICK_MOVE, sp);
                    } catch (Throwable t) {
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] [autotrade] click_failed player={} containerId={} err={}",
                                sp.getGameProfile().getName(), s.containerId, t.toString());
                        stop(sp, s.containerId, "click_failed");
                        progressed = true;
                        break;
                    }

                    int afterUses = safeUses(offer);
                    if (beforeUses >= 0 && afterUses >= 0 && afterUses != beforeUses) {
                        progressed = true;
                        s.totalTrades++;
                        s.lastUses = afterUses;
                        tradesThisTick++;
                        beforeUses = afterUses;

                        if (s.totalTrades % 10 == 0) {
                            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [autotrade] progress player={} containerId={} trades={}",
                                    sp.getGameProfile().getName(), s.containerId, s.totalTrades);
                        }

                        continue;
                    }

                    // No uses change -> likely inventory full or missing inputs. Try one more time next tick.
                    break;
                }

                if (!progressed) {
                    s.noProgressTicks++;
                    if (s.noProgressTicks % 10 == 0) {
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] [autotrade] no_progress player={} containerId={} ticks={}",
                                sp.getGameProfile().getName(), s.containerId, s.noProgressTicks);
                    }
                    if (s.noProgressTicks > NO_PROGRESS_TICKS_LIMIT) {
                        stop(sp, s.containerId, "no_progress");
                    }
                } else {
                    s.noProgressTicks = 0;
                }
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] AutoTradeServerService.tick failed", t);
        }
    }

    private static void tryClearInputs(ServerPlayer sp, MerchantMenu menu) {
        try {
            if (sp == null || menu == null) return;
            try { menu.clicked(INPUT_A, 0, ClickType.QUICK_MOVE, sp); } catch (Throwable ignored) {}
            try { menu.clicked(INPUT_B, 0, ClickType.QUICK_MOVE, sp); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private static MerchantOffer safeOffer(MerchantMenu menu, int idx) {
        try {
            if (menu == null) return null;
            var offers = menu.getOffers();
            if (offers == null) return null;
            if (idx < 0 || idx >= offers.size()) return null;
            return offers.get(idx);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int safeUses(MerchantOffer offer) {
        try {
            if (offer == null) return -1;
            return offer.getUses();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void sendState(ServerPlayer sp, int containerId, boolean active, String reason) {
        try {
            if (sp == null) return;
            sp.connection.send(new ClientboundCustomPayloadPacket(new PacketAutoTradeState(containerId, active, reason == null ? "" : reason)));
        } catch (Throwable ignored) {}
    }

    private static void tryInvokeMenuInt(MerchantMenu menu, String methodName, int arg) {
        try {
            if (menu == null || methodName == null) return;

            // First: public method by name/signature.
            try {
                Method m = menu.getClass().getMethod(methodName, int.class);
                m.setAccessible(true);
                m.invoke(menu, arg);
                return;
            } catch (Throwable ignored) {}

            // Second: declared method by name/signature (covers private/protected).
            try {
                Method m = menu.getClass().getDeclaredMethod(methodName, int.class);
                m.setAccessible(true);
                m.invoke(menu, arg);
                return;
            } catch (Throwable ignored) {}

            // Fallback: find any method with (int) params and void/boolean return (name may be mapped).
            for (Method m : menu.getClass().getDeclaredMethods()) {
                try {
                    if (m.getParameterCount() != 1) continue;
                    if (m.getParameterTypes()[0] != int.class) continue;
                    Class<?> rt = m.getReturnType();
                    if (!(rt == void.class || rt == boolean.class || rt == Boolean.class)) continue;

                    m.setAccessible(true);
                    m.invoke(menu, arg);
                    return;
                } catch (Throwable ignored) {}
            }

        } catch (Throwable ignored) {}
    }
}
