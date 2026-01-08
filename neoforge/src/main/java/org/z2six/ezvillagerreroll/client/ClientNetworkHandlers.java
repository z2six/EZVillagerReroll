// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/client/ClientNetworkHandlers.java
package org.z2six.ezvillagerreroll.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.network.*;

public final class ClientNetworkHandlers {

    private ClientNetworkHandlers() {}

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

    public static void onAutoSearchDone(Object msg, IPayloadContext ctx) {
        try {
            if (msg instanceof PacketAutoSearchDone m) onAutoSearchDone(m, ctx);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client handler onAutoSearchDone(Object) failed", t);
        }
    }

    // NEW
    public static void onRerollCooldownState(Object msg, IPayloadContext ctx) {
        try {
            if (msg instanceof PacketRerollCooldownState m) onRerollCooldownState(m, ctx);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client handler onRerollCooldownState(Object) failed", t);
        }
    }

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
                        sc.applyCatalogFromServer(msg);
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

    public static void onAutoSearchDone(PacketAutoSearchDone msg, IPayloadContext ctx) {
        try {
            ctx.enqueueWork(() -> {
                try {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc == null) return;

                    if (mc.screen instanceof BusyVillagerScreen bs) {
                        int currentVill = bs.getVillagerEntityIdSafe();
                        if (currentVill == msg.villagerEntityId()) {
                            EZVillagerReroll.LOG().info("[EZVR] Auto-search done -> closing BusyVillagerScreen (villagerEntityId={})", currentVill);
                            mc.setScreen(null);
                        } else {
                            EZVillagerReroll.LOG().debug("[EZVR] Auto-search done for villagerEntityId={} but busy screen is for {} -> no action",
                                    msg.villagerEntityId(), currentVill);
                        }
                    }
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().error("[EZVR] Client handler onAutoSearchDone failed", t);
                }
            });
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client handler onAutoSearchDone enqueue failed", t);
        }
    }

    // NEW
    public static void onRerollCooldownState(PacketRerollCooldownState msg, IPayloadContext ctx) {
        try {
            ctx.enqueueWork(() -> {
                try {
                    ClientRerollCooldownCache.apply(msg);
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().error("[EZVR] Client handler onRerollCooldownState failed", t);
                }
            });
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client handler onRerollCooldownState enqueue failed", t);
        }
    }
}
