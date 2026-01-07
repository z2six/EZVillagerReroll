// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/client/ClientNetworkHandlers.java
package org.z2six.ezvillagerreroll.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.network.ClientSyncedConfig;
import org.z2six.ezvillagerreroll.network.ClientTooltipCache;
import org.z2six.ezvillagerreroll.network.ClientTradeLockCache;
import org.z2six.ezvillagerreroll.network.PacketOpenBusyScreen;
import org.z2six.ezvillagerreroll.network.PacketSearchCatalogData;
import org.z2six.ezvillagerreroll.network.PacketSyncConfig;
import org.z2six.ezvillagerreroll.network.PacketTooltipData;
import org.z2six.ezvillagerreroll.network.PacketTradeLocks;

/**
 * CLIENT-ONLY packet handlers.
 *
 * Network.java calls these via reflection, so signatures must remain stable.
 */
public final class ClientNetworkHandlers {

    private ClientNetworkHandlers() {}

    // ---------------------------------------------------------------------
    // Reflection-friendly overloads (Object, IPayloadContext)
    // ---------------------------------------------------------------------

    public static void onTooltipData(Object msg, IPayloadContext ctx) {
        try {
            if (msg instanceof PacketTooltipData m) onTooltipData(m, ctx);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client handler onTooltipData(Object) failed", t);
        }
    }

    public static void onSyncConfig(Object msg, IPayloadContext ctx) {
        try {
            if (msg instanceof PacketSyncConfig m) onSyncConfig(m, ctx);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client handler onSyncConfig(Object) failed", t);
        }
    }

    public static void onTradeLocks(Object msg, IPayloadContext ctx) {
        try {
            if (msg instanceof PacketTradeLocks m) onTradeLocks(m, ctx);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client handler onTradeLocks(Object) failed", t);
        }
    }

    public static void onSearchCatalogData(Object msg, IPayloadContext ctx) {
        try {
            if (msg instanceof PacketSearchCatalogData m) onSearchCatalogData(m, ctx);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client handler onSearchCatalogData(Object) failed", t);
        }
    }

    public static void onOpenBusyScreen(Object msg, IPayloadContext ctx) {
        try {
            if (msg instanceof PacketOpenBusyScreen m) onOpenBusyScreen(m, ctx);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client handler onOpenBusyScreen(Object) failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Concrete signatures (preferred)
    // ---------------------------------------------------------------------

    public static void onTooltipData(PacketTooltipData msg, IPayloadContext ctx) {
        try {
            ctx.enqueueWork(() -> {
                try {
                    ClientTooltipCache.set(msg);
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().error("[EZVR] Client handler onTooltipData failed", t);
                }
            });
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client handler onTooltipData enqueue failed", t);
        }
    }

    public static void onTradeLocks(PacketTradeLocks msg, IPayloadContext ctx) {
        try {
            ctx.enqueueWork(() -> {
                try {
                    ClientTradeLockCache.set(msg);
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().error("[EZVR] Client handler onTradeLocks failed", t);
                }
            });
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client handler onTradeLocks enqueue failed", t);
        }
    }

    public static void onSyncConfig(PacketSyncConfig msg, IPayloadContext ctx) {
        try {
            ctx.enqueueWork(() -> {
                try {
                    ClientSyncedConfig.applyFromServer(msg);
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().error("[EZVR] Client handler onSyncConfig failed", t);
                }
            });
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client handler onSyncConfig enqueue failed", t);
        }
    }

    public static void onSearchCatalogData(PacketSearchCatalogData msg, IPayloadContext ctx) {
        try {
            ctx.enqueueWork(() -> {
                try {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc == null) return;

                    if (mc.screen instanceof SearchCatalogScreen sc) {
                        // Your screen expects (villagerEntityId, catalog)
                        int vid;
                        try {
                            vid = msg.villagerEntityId();
                        } catch (Throwable t) {
                            EZVillagerReroll.LOG().warn("[EZVR] PacketSearchCatalogData missing villagerEntityId(); defaulting -1");
                            vid = -1;
                        }

                        sc.applyCatalogFromServer(vid, msg.catalog());
                    } else {
                        EZVillagerReroll.LOG().debug(
                                "[EZVR] CatalogData received but current screen is not SearchCatalogScreen (screen={})",
                                mc.screen == null ? "null" : mc.screen.getClass().getName()
                        );
                    }
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().error("[EZVR] Client handler onSearchCatalogData failed", t);
                }
            });
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client handler onSearchCatalogData enqueue failed", t);
        }
    }

    public static void onOpenBusyScreen(PacketOpenBusyScreen msg, IPayloadContext ctx) {
        try {
            ctx.enqueueWork(() -> {
                try {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc == null) return;

                    mc.setScreen(new BusyVillagerScreen(msg.villagerEntityId(), msg.requested()));
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().error("[EZVR] Client handler onOpenBusyScreen failed", t);
                }
            });
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client handler onOpenBusyScreen enqueue failed", t);
        }
    }
}
