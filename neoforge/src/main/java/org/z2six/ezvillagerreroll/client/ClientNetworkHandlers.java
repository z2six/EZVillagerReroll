// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/client/ClientNetworkHandlers.java
package org.z2six.ezvillagerreroll.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.network.ClientSyncedConfig;
import org.z2six.ezvillagerreroll.network.ClientTooltipCache;
import org.z2six.ezvillagerreroll.network.ClientTradeLockCache;
import org.z2six.ezvillagerreroll.network.PacketAutoSearchDone;
import org.z2six.ezvillagerreroll.network.PacketAutoSearchSettlementCleared;
import org.z2six.ezvillagerreroll.network.PacketOpenAutoSearchPaymentScreen;
import org.z2six.ezvillagerreroll.network.PacketOpenBusyScreen;
import org.z2six.ezvillagerreroll.network.PacketRerollCooldownState;
import org.z2six.ezvillagerreroll.network.PacketSearchCatalogData;
import org.z2six.ezvillagerreroll.network.PacketSyncConfig;
import org.z2six.ezvillagerreroll.network.PacketTooltipData;
import org.z2six.ezvillagerreroll.network.PacketTradeLocks;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Client-side packet handlers.
 *
 * Network.java dispatches to this class via reflection, expecting static methods:
 * - onX(PacketType, IPayloadContext)
 * or fallback:
 * - onX(Object, IPayloadContext)
 *
 * We provide both for the newly-added packets so dispatch never fails.
 */
public final class ClientNetworkHandlers {

    private ClientNetworkHandlers() {}

    // -----------------------------------------------------------------------------------------
    // Existing handlers that Network.java already dispatches to (keep resilient).
    // -----------------------------------------------------------------------------------------

    public static void onTooltipData(PacketTooltipData msg, IPayloadContext ctx) {
        try {
            if (ctx == null) return;
            ctx.enqueueWork(() -> {
                try {
                    if (msg == null) return;
                    ClientTooltipCache.set(msg);
                    EZVillagerReroll.LOG().debug("[EZVR] Client received PacketTooltipData.");
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().error("[EZVR] Client onTooltipData failed", t);
                }
            });
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client onTooltipData enqueue failed", t);
        }
    }

    public static void onSyncConfig(PacketSyncConfig msg, IPayloadContext ctx) {
        try {
            if (ctx == null) return;
            ctx.enqueueWork(() -> {
                try {
                    if (msg == null) return;
                    ClientSyncedConfig.applyFromServer(msg);
                    EZVillagerReroll.LOG().debug("[EZVR] Client applied PacketSyncConfig.");
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().error("[EZVR] Client onSyncConfig failed", t);
                }
            });
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client onSyncConfig enqueue failed", t);
        }
    }

    public static void onTradeLocks(PacketTradeLocks msg, IPayloadContext ctx) {
        try {
            if (ctx == null) return;
            ctx.enqueueWork(() -> {
                try {
                    if (msg == null) return;
                    ClientTradeLockCache.set(msg);
                    EZVillagerReroll.LOG().debug("[EZVR] Client received PacketTradeLocks: containerId={} mask={}",
                            msg.containerId(), Long.toUnsignedString(msg.mask()));
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().error("[EZVR] Client onTradeLocks failed", t);
                }
            });
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client onTradeLocks enqueue failed", t);
        }
    }

    public static void onSearchCatalogData(PacketSearchCatalogData msg, IPayloadContext ctx) {
        try {
            if (ctx == null) return;
            ctx.enqueueWork(() -> {
                try {
                    if (msg == null) return;

                    Minecraft mc = Minecraft.getInstance();
                    if (mc == null) return;

                    Screen s = mc.screen;
                    if (s instanceof SearchCatalogScreen sc) {
                        sc.applyCatalogFromServer(msg);
                        EZVillagerReroll.LOG().debug("[EZVR] Client applied PacketSearchCatalogData to SearchCatalogScreen.");
                        return;
                    }

                    // Soft fallback: if no catalog screen is open, just log it.
                    EZVillagerReroll.LOG().debug("[EZVR] Client received PacketSearchCatalogData but no SearchCatalogScreen was open (current={}).",
                            s == null ? "null" : s.getClass().getName());

                } catch (Throwable t) {
                    EZVillagerReroll.LOG().error("[EZVR] Client onSearchCatalogData failed", t);
                }
            });
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client onSearchCatalogData enqueue failed", t);
        }
    }

    public static void onOpenBusyScreen(PacketOpenBusyScreen msg, IPayloadContext ctx) {
        try {
            if (ctx == null) return;
            ctx.enqueueWork(() -> {
                try {
                    if (msg == null) return;

                    Minecraft mc = Minecraft.getInstance();
                    if (mc == null) return;

                    // We avoid hard dependencies on BusyVillagerScreen in case its signature differs.
                    // Try to construct it reflectively.
                    Screen screen = tryCreateBusyVillagerScreen(msg);
                    if (screen != null) {
                        mc.setScreen(screen);
                        EZVillagerReroll.LOG().info("[EZVR] Client opened BusyVillagerScreen for villagerEntityId={} (requested={}).",
                                msg.villagerEntityId(), msg.requested() == null ? -1 : msg.requested().size());
                        return;
                    }

                    EZVillagerReroll.LOG().warn("[EZVR] Client could not open BusyVillagerScreen (class/signature mismatch).");

                } catch (Throwable t) {
                    EZVillagerReroll.LOG().error("[EZVR] Client onOpenBusyScreen failed", t);
                }
            });
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client onOpenBusyScreen enqueue failed", t);
        }
    }

    public static void onAutoSearchDone(PacketAutoSearchDone msg, IPayloadContext ctx) {
        try {
            if (ctx == null) return;
            ctx.enqueueWork(() -> {
                try {
                    if (msg == null) return;

                    Minecraft mc = Minecraft.getInstance();
                    if (mc == null) return;

                    // Best-effort: close busy screen if it matches this villager.
                    if (isBusyScreenForVillager(mc.screen, msg.villagerEntityId())) {
                        mc.setScreen(null);
                        EZVillagerReroll.LOG().info("[EZVR] Client auto-closed BusyVillagerScreen (auto-search done) for villagerEntityId={}.",
                                msg.villagerEntityId());
                    } else {
                        EZVillagerReroll.LOG().debug("[EZVR] Client received PacketAutoSearchDone for villagerEntityId={} (screen={}, no close).",
                                msg.villagerEntityId(), mc.screen == null ? "null" : mc.screen.getClass().getName());
                    }

                } catch (Throwable t) {
                    EZVillagerReroll.LOG().error("[EZVR] Client onAutoSearchDone failed", t);
                }
            });
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client onAutoSearchDone enqueue failed", t);
        }
    }

    public static void onRerollCooldownState(PacketRerollCooldownState msg, IPayloadContext ctx) {
        try {
            if (ctx == null) return;
            ctx.enqueueWork(() -> {
                try {
                    if (msg == null) return;

                    // If you already have a client cache for this, we update it reflectively.
                    boolean applied = tryApplyCooldownToKnownCaches(msg);

                    if (!applied) {
                        // Still log it so you can confirm it’s arriving.
                        EZVillagerReroll.LOG().debug("[EZVR] Client received PacketRerollCooldownState: containerId={} remaining={} cfg={}",
                                msg.containerId(), msg.ticksRemaining(), msg.cooldownTicksConfigured());
                    }
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().error("[EZVR] Client onRerollCooldownState failed", t);
                }
            });
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client onRerollCooldownState enqueue failed", t);
        }
    }

    // -----------------------------------------------------------------------------------------
    // NEW handlers required for settlement/payment UI
    // -----------------------------------------------------------------------------------------

    public static void onOpenAutoSearchPaymentScreen(PacketOpenAutoSearchPaymentScreen msg, IPayloadContext ctx) {
        try {
            if (ctx == null) return;
            ctx.enqueueWork(() -> {
                try {
                    if (msg == null) return;

                    Minecraft mc = Minecraft.getInstance();
                    if (mc == null) return;

                    // Open our payment UI.
                    AutoSearchPaymentScreen screen = new AutoSearchPaymentScreen(
                            msg.villagerEntityId(),
                            msg.hourlyCost(),
                            msg.finalCost(),
                            msg.elapsedTicks()
                    );

                    mc.setScreen(screen);

                    EZVillagerReroll.LOG().info("[EZVR] Client opened AutoSearchPaymentScreen villagerEntityId={} hourly={} final={} elapsedTicks={}",
                            msg.villagerEntityId(), msg.hourlyCost(), msg.finalCost(), msg.elapsedTicks());

                } catch (Throwable t) {
                    EZVillagerReroll.LOG().error("[EZVR] Client onOpenAutoSearchPaymentScreen failed", t);
                }
            });
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client onOpenAutoSearchPaymentScreen enqueue failed", t);
        }
    }

    /**
     * Fallback signature for Network.dispatchToClientHandler's "Object" path.
     * (Your error showed it tried Object+IPayloadContext and didn't find it.)
     */
    public static void onOpenAutoSearchPaymentScreen(Object msg, IPayloadContext ctx) {
        try {
            if (msg instanceof PacketOpenAutoSearchPaymentScreen p) {
                onOpenAutoSearchPaymentScreen(p, ctx);
                return;
            }
            EZVillagerReroll.LOG().warn("[EZVR] onOpenAutoSearchPaymentScreen(Object,ctx) got unexpected msg type: {}",
                    msg == null ? "null" : msg.getClass().getName());
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client onOpenAutoSearchPaymentScreen(Object) failed", t);
        }
    }

    public static void onAutoSearchSettlementCleared(PacketAutoSearchSettlementCleared msg, IPayloadContext ctx) {
        try {
            if (ctx == null) return;
            ctx.enqueueWork(() -> {
                try {
                    if (msg == null) return;

                    Minecraft mc = Minecraft.getInstance();
                    if (mc == null) return;

                    Screen s = mc.screen;
                    if (s instanceof AutoSearchPaymentScreen pay && pay.getVillagerEntityId() == msg.villagerEntityId()) {
                        mc.setScreen(null);
                        EZVillagerReroll.LOG().info("[EZVR] Client closed AutoSearchPaymentScreen (settlement cleared) villagerEntityId={}.",
                                msg.villagerEntityId());
                        return;
                    }

                    // Also best-effort close busy screen if it’s open and matches.
                    if (isBusyScreenForVillager(s, msg.villagerEntityId())) {
                        mc.setScreen(null);
                        EZVillagerReroll.LOG().info("[EZVR] Client closed BusyVillagerScreen (settlement cleared) villagerEntityId={}.",
                                msg.villagerEntityId());
                        return;
                    }

                    EZVillagerReroll.LOG().debug("[EZVR] Client received PacketAutoSearchSettlementCleared for villagerEntityId={} (screen={}, no close).",
                            msg.villagerEntityId(), s == null ? "null" : s.getClass().getName());

                } catch (Throwable t) {
                    EZVillagerReroll.LOG().error("[EZVR] Client onAutoSearchSettlementCleared failed", t);
                }
            });
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client onAutoSearchSettlementCleared enqueue failed", t);
        }
    }

    /**
     * Fallback signature for Network.dispatchToClientHandler's "Object" path.
     */
    public static void onAutoSearchSettlementCleared(Object msg, IPayloadContext ctx) {
        try {
            if (msg instanceof PacketAutoSearchSettlementCleared p) {
                onAutoSearchSettlementCleared(p, ctx);
                return;
            }
            EZVillagerReroll.LOG().warn("[EZVR] onAutoSearchSettlementCleared(Object,ctx) got unexpected msg type: {}",
                    msg == null ? "null" : msg.getClass().getName());
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client onAutoSearchSettlementCleared(Object) failed", t);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Helpers (reflection-based to avoid coupling to screens/caches you may have renamed).
    // -----------------------------------------------------------------------------------------

    private static Screen tryCreateBusyVillagerScreen(PacketOpenBusyScreen msg) {
        try {
            // Common name we used earlier:
            String cn = "org.z2six.ezvillagerreroll.client.BusyVillagerScreen";
            Class<?> clz = Class.forName(cn);

            // Try (PacketOpenBusyScreen) ctor
            try {
                Constructor<?> c = clz.getConstructor(PacketOpenBusyScreen.class);
                Object inst = c.newInstance(msg);
                if (inst instanceof Screen sc) return sc;
            } catch (Throwable ignored) {}

            // Try (int, List<ItemStack>) ctor
            try {
                Constructor<?> c = clz.getConstructor(int.class, java.util.List.class);
                Object inst = c.newInstance(msg.villagerEntityId(), msg.requested());
                if (inst instanceof Screen sc) return sc;
            } catch (Throwable ignored) {}

            // Try (int) ctor
            try {
                Constructor<?> c = clz.getConstructor(int.class);
                Object inst = c.newInstance(msg.villagerEntityId());
                if (inst instanceof Screen sc) return sc;
            } catch (Throwable ignored) {}

            return null;

        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isBusyScreenForVillager(Screen screen, int villagerEntityId) {
        try {
            if (screen == null) return false;
            String name = screen.getClass().getName();
            if (!name.endsWith("BusyVillagerScreen")) return false;

            // Try method getVillagerEntityId()
            try {
                Method m = screen.getClass().getMethod("getVillagerEntityId");
                Object v = m.invoke(screen);
                if (v instanceof Integer i) return i == villagerEntityId;
            } catch (Throwable ignored) {}

            // Try method villagerEntityId()
            try {
                Method m = screen.getClass().getMethod("villagerEntityId");
                Object v = m.invoke(screen);
                if (v instanceof Integer i) return i == villagerEntityId;
            } catch (Throwable ignored) {}

            // Try field villagerEntityId
            try {
                Field f = screen.getClass().getDeclaredField("villagerEntityId");
                f.setAccessible(true);
                Object v = f.get(screen);
                if (v instanceof Integer i) return i == villagerEntityId;
            } catch (Throwable ignored) {}

            // If we can’t prove it, don’t close.
            return false;

        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean tryApplyCooldownToKnownCaches(PacketRerollCooldownState msg) {
        // We intentionally keep this reflective so you don’t have to rename files to match.
        try {
            // Candidate classes (if they exist in your project).
            String[] candidates = new String[] {
                    "org.z2six.ezvillagerreroll.client.ClientRerollCooldownCache",
                    "org.z2six.ezvillagerreroll.network.ClientRerollCooldownCache",
                    "org.z2six.ezvillagerreroll.client.RerollCooldownClientCache",
                    "org.z2six.ezvillagerreroll.network.RerollCooldownClientCache"
            };

            for (String cn : candidates) {
                try {
                    Class<?> c = Class.forName(cn);

                    // Common API: set(PacketRerollCooldownState)
                    try {
                        Method m = c.getMethod("set", PacketRerollCooldownState.class);
                        m.invoke(null, msg);
                        EZVillagerReroll.LOG().debug("[EZVR] Applied cooldown state via {}.set(PacketRerollCooldownState).", cn);
                        return true;
                    } catch (Throwable ignored) {}

                    // Alternate API: apply(PacketRerollCooldownState)
                    try {
                        Method m = c.getMethod("apply", PacketRerollCooldownState.class);
                        m.invoke(null, msg);
                        EZVillagerReroll.LOG().debug("[EZVR] Applied cooldown state via {}.apply(PacketRerollCooldownState).", cn);
                        return true;
                    } catch (Throwable ignored) {}

                } catch (Throwable ignored) {
                    // class not present, continue
                }
            }

            return false;

        } catch (Throwable t) {
            return false;
        }
    }
}
