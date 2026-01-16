// neoforge\src\main\java\org\z2six\villageroverhaul\client\ClientNetworkHandlers.java
package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.ClientSyncedConfig;
import org.z2six.villageroverhaul.network.tooltip.ClientTooltipCache;
import org.z2six.villageroverhaul.network.ClientTradeLockCache;
import org.z2six.villageroverhaul.network.autoReroll.PacketAutoSearchDone;
import org.z2six.villageroverhaul.network.autoReroll.PacketAutoSearchSettlementCleared;
import org.z2six.villageroverhaul.network.autoReroll.PacketOpenAutoSearchPaymentScreen;
import org.z2six.villageroverhaul.network.autoReroll.PacketOpenBusyScreen;
import org.z2six.villageroverhaul.network.recruit.PacketOpenRecruitScreen;
import org.z2six.villageroverhaul.network.recruit.PacketRecruitCostData;
import org.z2six.villageroverhaul.network.recruit.PacketRecruitResult;
import org.z2six.villageroverhaul.network.autoReroll.PacketRerollCooldownState;
import org.z2six.villageroverhaul.network.autoReroll.PacketSearchCatalogData;
import org.z2six.villageroverhaul.network.PacketSyncConfig;
import org.z2six.villageroverhaul.network.tooltip.PacketTooltipData;
import org.z2six.villageroverhaul.network.trades.PacketTradeLocks;
import org.z2six.villageroverhaul.network.modes.PacketVillagerModeData;
import org.z2six.villageroverhaul.network.recruit.PacketRecruitGateData;

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
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] Client received PacketTooltipData.");
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onTooltipData failed", t);
                }
            });
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onTooltipData enqueue failed", t);
        }
    }

    public static void onSyncConfig(PacketSyncConfig msg, IPayloadContext ctx) {
        try {
            if (ctx == null) return;
            ctx.enqueueWork(() -> {
                try {
                    if (msg == null) return;
                    ClientSyncedConfig.applyFromServer(msg);
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] Client applied PacketSyncConfig.");
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onSyncConfig failed", t);
                }
            });
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onSyncConfig enqueue failed", t);
        }
    }

    public static void onTradeLocks(PacketTradeLocks msg, IPayloadContext ctx) {
        try {
            if (ctx == null) return;
            ctx.enqueueWork(() -> {
                try {
                    if (msg == null) return;
                    ClientTradeLockCache.set(msg);
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] Client received PacketTradeLocks: containerId={} mask={}",
                            msg.containerId(), Long.toUnsignedString(msg.mask()));
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onTradeLocks failed", t);
                }
            });
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onTradeLocks enqueue failed", t);
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
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] Client applied PacketSearchCatalogData to SearchCatalogScreen.");
                        return;
                    }

                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] Client received PacketSearchCatalogData but no SearchCatalogScreen was open (current={}).",
                            s == null ? "null" : s.getClass().getName());

                } catch (Throwable t) {
                    VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onSearchCatalogData failed", t);
                }
            });
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onSearchCatalogData enqueue failed", t);
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

                    Screen screen = tryCreateBusyVillagerScreen(msg);
                    if (screen != null) {
                        mc.setScreen(screen);
                        VillagerOverhaul.LOG().info("[VillagerOverhaul] Client opened BusyVillagerScreen for villagerEntityId={} (requested={}).",
                                msg.villagerEntityId(), msg.requested() == null ? -1 : msg.requested().size());
                        return;
                    }

                    VillagerOverhaul.LOG().warn("[VillagerOverhaul] Client could not open BusyVillagerScreen (class/signature mismatch).");

                } catch (Throwable t) {
                    VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onOpenBusyScreen failed", t);
                }
            });
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onOpenBusyScreen enqueue failed", t);
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

                    if (isBusyScreenForVillager(mc.screen, msg.villagerEntityId())) {
                        mc.setScreen(null);
                        VillagerOverhaul.LOG().info("[VillagerOverhaul] Client auto-closed BusyVillagerScreen (auto-search done) for villagerEntityId={}.",
                                msg.villagerEntityId());
                    } else {
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] Client received PacketAutoSearchDone for villagerEntityId={} (screen={}, no close).",
                                msg.villagerEntityId(), mc.screen == null ? "null" : mc.screen.getClass().getName());
                    }

                } catch (Throwable t) {
                    VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onAutoSearchDone failed", t);
                }
            });
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onAutoSearchDone enqueue failed", t);
        }
    }

    public static void onRerollCooldownState(PacketRerollCooldownState msg, IPayloadContext ctx) {
        try {
            if (ctx == null) return;
            ctx.enqueueWork(() -> {
                try {
                    if (msg == null) return;

                    boolean applied = tryApplyCooldownToKnownCaches(msg);

                    if (!applied) {
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] Client received PacketRerollCooldownState: containerId={} remaining={} cfg={}",
                                msg.containerId(), msg.ticksRemaining(), msg.cooldownTicksConfigured());
                    }
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onRerollCooldownState failed", t);
                }
            });
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onRerollCooldownState enqueue failed", t);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Payment screen open/close
    // -----------------------------------------------------------------------------------------

    public static void onOpenAutoSearchPaymentScreen(Object msg, IPayloadContext ctx) {
        try {
            if (msg instanceof PacketOpenAutoSearchPaymentScreen p) {
                onOpenAutoSearchPaymentScreen(p, ctx);
                return;
            }
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] onOpenAutoSearchPaymentScreen(Object,ctx) got unexpected msg type: {}",
                    msg == null ? "null" : msg.getClass().getName());
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onOpenAutoSearchPaymentScreen(Object) failed", t);
        }
    }

    public static void onOpenAutoSearchPaymentScreen(PacketOpenAutoSearchPaymentScreen msg, IPayloadContext ctx) {
        try {
            if (ctx == null) return;
            ctx.enqueueWork(() -> {
                try {
                    if (msg == null) return;

                    Minecraft mc = Minecraft.getInstance();
                    if (mc == null) return;

                    long lockMask = 0L;
                    try {
                        lockMask = msg.lockMaskBefore();
                    } catch (Throwable t) {
                        lockMask = 0L;
                    }

                    java.util.List<String> requested = java.util.List.of();
                    try {
                        requested = (msg.requestedTargets() == null) ? java.util.List.of() : msg.requestedTargets();
                    } catch (Throwable t) {
                        requested = java.util.List.of();
                    }

                    if (requested.size() > 256) {
                        requested = requested.subList(0, 256);
                    }

                    AutoSearchPaymentScreen screen = new AutoSearchPaymentScreen(
                            msg.villagerEntityId(),
                            msg.hourlyCost(),
                            msg.finalCost(),
                            msg.elapsedTicks(),
                            msg.offersIfPay(),
                            msg.offersIfDecline(),
                            lockMask,
                            requested
                    );

                    mc.setScreen(screen);

                    if (VillagerOverhaul.LOG().isInfoEnabled()) {
                        VillagerOverhaul.LOG().info(
                                "[VillagerOverhaul] Client opened AutoSearchPaymentScreen villagerEntityId={} hourly={} final={} elapsedTicks={} payOffers={} declineOffers={} lockMask={} requestedTargets={}",
                                msg.villagerEntityId(),
                                msg.hourlyCost(),
                                msg.finalCost(),
                                msg.elapsedTicks(),
                                msg.offersIfPay() == null ? -1 : msg.offersIfPay().size(),
                                msg.offersIfDecline() == null ? -1 : msg.offersIfDecline().size(),
                                Long.toUnsignedString(lockMask),
                                requested.size()
                        );
                    }

                    if (VillagerOverhaul.LOG().isDebugEnabled()) {
                        int shown = Math.min(8, requested.size());
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] PaymentScreen requestedTargets (first {}): {}", shown,
                                requested.isEmpty() ? "[]" : requested.subList(0, shown));
                    }

                } catch (Throwable t) {
                    VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onOpenAutoSearchPaymentScreen failed", t);
                }
            });
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onOpenAutoSearchPaymentScreen enqueue failed", t);
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
                        VillagerOverhaul.LOG().info("[VillagerOverhaul] Client closed AutoSearchPaymentScreen (settlement cleared) villagerEntityId={}.",
                                msg.villagerEntityId());
                        return;
                    }

                    if (isBusyScreenForVillager(s, msg.villagerEntityId())) {
                        mc.setScreen(null);
                        VillagerOverhaul.LOG().info("[VillagerOverhaul] Client closed BusyVillagerScreen (settlement cleared) villagerEntityId={}.",
                                msg.villagerEntityId());
                        return;
                    }

                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] Client received PacketAutoSearchSettlementCleared for villagerEntityId={} (screen={}, no close).",
                            msg.villagerEntityId(), s == null ? "null" : s.getClass().getName());

                } catch (Throwable t) {
                    VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onAutoSearchSettlementCleared failed", t);
                }
            });
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onAutoSearchSettlementCleared enqueue failed", t);
        }
    }

    public static void onAutoSearchSettlementCleared(Object msg, IPayloadContext ctx) {
        try {
            if (msg instanceof PacketAutoSearchSettlementCleared p) {
                onAutoSearchSettlementCleared(p, ctx);
                return;
            }
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] onAutoSearchSettlementCleared(Object,ctx) got unexpected msg type: {}",
                    msg == null ? "null" : msg.getClass().getName());
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onAutoSearchSettlementCleared(Object) failed", t);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Recruit screens + packets
    // -----------------------------------------------------------------------------------------

    public static void onOpenRecruitScreen(Object msg, IPayloadContext ctx) {
        try {
            if (msg instanceof PacketOpenRecruitScreen p) {
                onOpenRecruitScreen(p, ctx);
                return;
            }
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] onOpenRecruitScreen(Object,ctx) got unexpected msg type: {}",
                    msg == null ? "null" : msg.getClass().getName());
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onOpenRecruitScreen(Object) failed", t);
        }
    }

    public static void onOpenRecruitScreen(PacketOpenRecruitScreen msg, IPayloadContext ctx) {
        try {
            if (ctx == null) return;
            ctx.enqueueWork(() -> {
                try {
                    if (msg == null) return;

                    Minecraft mc = Minecraft.getInstance();
                    if (mc == null) return;

                    // If already open for same villager, just update.
                    Screen current = mc.screen;
                    if (current instanceof RecruitVillagerScreen rvs && rvs.getVillagerEntityId() == msg.villagerEntityId()) {
                        rvs.applyCostUpdate(true, msg.eligible(), msg.alreadyRecruited(), msg.cost(), msg.message());
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] Updated existing RecruitVillagerScreen for villagerEntityId={}.",
                                msg.villagerEntityId());
                        return;
                    }

                    RecruitVillagerScreen screen = new RecruitVillagerScreen(
                            msg.villagerEntityId(),
                            msg.cost(),
                            msg.eligible(),
                            msg.alreadyRecruited(),
                            msg.message()
                    );

                    mc.setScreen(screen);

                    VillagerOverhaul.LOG().info("[VillagerOverhaul] Client opened RecruitVillagerScreen villagerEntityId={} eligible={} alreadyRecruited={} cost={}",
                            msg.villagerEntityId(), msg.eligible(), msg.alreadyRecruited(), msg.cost());

                } catch (Throwable t) {
                    VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onOpenRecruitScreen failed", t);
                }
            });
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onOpenRecruitScreen enqueue failed", t);
        }
    }

    public static void onRecruitCostData(Object msg, IPayloadContext ctx) {
        try {
            if (msg instanceof PacketRecruitCostData p) {
                onRecruitCostData(p, ctx);
                return;
            }
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] onRecruitCostData(Object,ctx) got unexpected msg type: {}",
                    msg == null ? "null" : msg.getClass().getName());
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onRecruitCostData(Object) failed", t);
        }
    }

    public static void onRecruitCostData(PacketRecruitCostData msg, IPayloadContext ctx) {
        try {
            if (ctx == null) return;
            ctx.enqueueWork(() -> {
                try {
                    if (msg == null) return;

                    // IMPORTANT: Always feed the ClientUI recruited-cache,
                    // even when the Recruit screen is NOT open.
                    try {
                        ClientUI.acceptRecruitCostData(msg);
                    } catch (Throwable ignored) {}

                    Minecraft mc = Minecraft.getInstance();
                    if (mc == null) return;

                    Screen s = mc.screen;
                    if (s instanceof RecruitVillagerScreen rvs && rvs.getVillagerEntityId() == msg.villagerEntityId()) {
                        rvs.applyCostUpdate(msg.ok(), msg.eligible(), msg.alreadyRecruited(), msg.cost(), msg.message());
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] Client applied PacketRecruitCostData to RecruitVillagerScreen villagerEntityId={}.",
                                msg.villagerEntityId());
                        return;
                    }

                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] Client received PacketRecruitCostData (cached) but no RecruitVillagerScreen was open (current={}).",
                            s == null ? "null" : s.getClass().getName());

                } catch (Throwable t) {
                    VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onRecruitCostData failed", t);
                }
            });
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onRecruitCostData enqueue failed", t);
        }
    }

    public static void onRecruitResult(Object msg, IPayloadContext ctx) {
        try {
            if (msg instanceof PacketRecruitResult p) {
                onRecruitResult(p, ctx);
                return;
            }
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] onRecruitResult(Object,ctx) got unexpected msg type: {}",
                    msg == null ? "null" : msg.getClass().getName());
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onRecruitResult(Object) failed", t);
        }
    }

    public static void onRecruitResult(PacketRecruitResult msg, IPayloadContext ctx) {
        try {
            if (ctx == null) return;
            ctx.enqueueWork(() -> {
                try {
                    if (msg == null) return;

                    Minecraft mc = Minecraft.getInstance();
                    if (mc == null) return;

                    Screen s = mc.screen;
                    if (s instanceof RecruitVillagerScreen rvs && rvs.getVillagerEntityId() == msg.villagerEntityId()) {
                        rvs.applyResult(msg.success(), msg.nowRecruited(), msg.costPaid(), msg.message());
                        VillagerOverhaul.LOG().info("[VillagerOverhaul] Client applied PacketRecruitResult villagerEntityId={} success={} nowRecruited={} costPaid={}",
                                msg.villagerEntityId(), msg.success(), msg.nowRecruited(), msg.costPaid());
                        return;
                    }

                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] Client received PacketRecruitResult but no RecruitVillagerScreen was open (current={}).",
                            s == null ? "null" : s.getClass().getName());

                } catch (Throwable t) {
                    VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onRecruitResult failed", t);
                }
            });
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onRecruitResult enqueue failed", t);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    public static void onVillagerModeData(PacketVillagerModeData msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                org.z2six.villageroverhaul.client.ClientUI.acceptVillagerModeData(msg);
            } catch (Throwable ignored) {}
        });
    }

    private static Screen tryCreateBusyVillagerScreen(PacketOpenBusyScreen msg) {
        try {
            String cn = "org.z2six.villageroverhaul.client.BusyVillagerScreen";
            Class<?> clz = Class.forName(cn);

            try {
                Constructor<?> c = clz.getConstructor(PacketOpenBusyScreen.class);
                Object inst = c.newInstance(msg);
                if (inst instanceof Screen sc) return sc;
            } catch (Throwable ignored) {}

            try {
                Constructor<?> c = clz.getConstructor(int.class, java.util.List.class);
                Object inst = c.newInstance(msg.villagerEntityId(), msg.requested());
                if (inst instanceof Screen sc) return sc;
            } catch (Throwable ignored) {}

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

            try {
                Method m = screen.getClass().getMethod("getVillagerEntityId");
                Object v = m.invoke(screen);
                if (v instanceof Integer i) return i == villagerEntityId;
            } catch (Throwable ignored) {}

            try {
                Method m = screen.getClass().getMethod("villagerEntityId");
                Object v = m.invoke(screen);
                if (v instanceof Integer i) return i == villagerEntityId;
            } catch (Throwable ignored) {}

            try {
                Field f = screen.getClass().getDeclaredField("villagerEntityId");
                f.setAccessible(true);
                Object v = f.get(screen);
                if (v instanceof Integer i) return i == villagerEntityId;
            } catch (Throwable ignored) {}

            return false;

        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean tryApplyCooldownToKnownCaches(PacketRerollCooldownState msg) {
        try {
            String[] candidates = new String[] {
                    "org.z2six.villageroverhaul.client.ClientRerollCooldownCache",
                    "org.z2six.villageroverhaul.network.ClientRerollCooldownCache",
                    "org.z2six.villageroverhaul.client.RerollCooldownClientCache",
                    "org.z2six.villageroverhaul.network.RerollCooldownClientCache"
            };

            for (String cn : candidates) {
                try {
                    Class<?> c = Class.forName(cn);

                    try {
                        Method m = c.getMethod("set", PacketRerollCooldownState.class);
                        m.invoke(null, msg);
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] Applied cooldown state via {}.set(PacketRerollCooldownState).", cn);
                        return true;
                    } catch (Throwable ignored) {}

                    try {
                        Method m = c.getMethod("apply", PacketRerollCooldownState.class);
                        m.invoke(null, msg);
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] Applied cooldown state via {}.apply(PacketRerollCooldownState).", cn);
                        return true;
                    } catch (Throwable ignored) {}

                } catch (Throwable ignored) {}
            }

            return false;

        } catch (Throwable t) {
            return false;
        }
    }

    // ====================
    // Feature GATE
    // ====================

    public static void onRecruitGateData(Object msg, IPayloadContext ctx) {
        try {
            if (msg instanceof PacketRecruitGateData p) {
                onRecruitGateData(p, ctx);
                return;
            }
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] onRecruitGateData(Object,ctx) got unexpected msg type: {}",
                    msg == null ? "null" : msg.getClass().getName());
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onRecruitGateData(Object) failed", t);
        }
    }

    public static void onRecruitGateData(PacketRecruitGateData msg, IPayloadContext ctx) {
        try {
            if (ctx == null) return;
            ctx.enqueueWork(() -> {
                try {
                    if (msg == null) return;
                    org.z2six.villageroverhaul.client.ClientUI.acceptRecruitGateData(msg);
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onRecruitGateData failed", t);
                }
            });
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client onRecruitGateData enqueue failed", t);
        }
    }

}
