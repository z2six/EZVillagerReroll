// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/client/ClientUI.java
package org.z2six.ezvillagerreroll.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MerchantMenu;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.config.ClientConfig;
import org.z2six.ezvillagerreroll.mixin.MerchantScreenAccessor;
import org.z2six.ezvillagerreroll.network.ClientSyncedConfig;
import org.z2six.ezvillagerreroll.network.ClientTooltipCache;
import org.z2six.ezvillagerreroll.network.ClientTradeLockCache;
import org.z2six.ezvillagerreroll.network.PacketRequestReroll;
import org.z2six.ezvillagerreroll.network.PacketRerollCooldownQuery;
import org.z2six.ezvillagerreroll.network.PacketTooltipData;
import org.z2six.ezvillagerreroll.network.PacketTooltipQuery;
import org.z2six.ezvillagerreroll.network.PacketTradeLocksQuery;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class ClientUI {

    private static final long TOOLTIP_REFRESH_DEBOUNCE_MS = 750;

    private static final Map<Screen, Button> REROLL_BUTTONS = new WeakHashMap<>();
    private static final Map<Screen, CooldownOverlayWidget> COOLDOWN_OVERLAYS = new WeakHashMap<>();

    private static final ResourceLocation CHAIN_TEX =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/block/chain.png");

    private static final String EZVR_TRADE_BUTTON_CLASS =
            "net.minecraft.client.gui.screens.inventory.MerchantScreen$TradeOfferButton";

    public static void registerRuntimeClientEvents() {
        NeoForge.EVENT_BUS.addListener(ClientUI::onScreenInitPost);
        NeoForge.EVENT_BUS.addListener(ClientUI::onScreenRenderPost);
        NeoForge.EVENT_BUS.addListener(ClientUI::onScreenClosed);

        EZVillagerReroll.LOG().info("[EZVR] ClientUI.registerRuntimeClientEvents(): handlers added");
    }

    public static Button getRerollButtonFor(Screen screen) {
        try {
            if (screen == null) return null;
            return REROLL_BUTTONS.get(screen);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] ClientUI.getRerollButtonFor failed (soft): {}", t.toString());
            return null;
        }
    }

    public static void openSearchCatalogScreen(MerchantScreen parent, int villagerEntityId) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            if (parent == null) return;

            EZVillagerReroll.LOG().info("[EZVR] Opening search catalog UI (villagerEntityId={})", villagerEntityId);

            ClientNetwork.sendToServer(new org.z2six.ezvillagerreroll.network.PacketSearchCatalogQuery(villagerEntityId));
            mc.setScreen(new SearchCatalogScreen(parent));
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] openSearchCatalogScreen failed", t);
        }
    }

    private static void onScreenInitPost(final ScreenEvent.Init.Post e) {
        try {
            if (!(e.getScreen() instanceof MerchantScreen screen)) return;

            ClientConfig.bake();

            int left = (screen.width - 276) / 2;
            int top = (screen.height - 166) / 2;

            int baseX = left + 276 - 22;
            int baseY = top + 6;

            int x = baseX + ClientConfig.buttonOffsetX;
            int y = baseY + ClientConfig.buttonOffsetY;

            int w = 18, h = 18;

            Button reroll = Button.builder(Component.literal("↻"), btn -> {
                        try {
                            int cid = resolveContainerId(screen);
                            if (cid >= 0 && ClientRerollCooldownCache.isCoolingDown(cid)) {
                                EZVillagerReroll.LOG().debug("[EZVR] Client reroll click ignored: cooling down (containerId={})", cid);
                                return;
                            }

                            int optimisticTicks = 0;
                            try {
                                // Prefer config snapshot if present, but this may be stale/0 on some setups.
                                ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
                                if (cfg != null) optimisticTicks = Math.max(0, cfg.cooldownTicks);
                            } catch (Throwable ignored) {}

                            if (cid >= 0 && optimisticTicks > 0) {
                                ClientRerollCooldownCache.setOptimisticCooldown(cid, optimisticTicks);
                            }

                            ClientNetwork.sendToServer(new PacketRequestReroll());
                            EZVillagerReroll.LOG().debug("[EZVR] Client clicked reroll button; sent PacketRequestReroll");
                        } catch (Throwable t) {
                            EZVillagerReroll.LOG().error("[EZVR] Client send reroll packet failed", t);
                        }

                        try {
                            btn.setFocused(false);
                            Screen scr = Minecraft.getInstance().screen;
                            if (scr != null && scr.getFocused() == btn) scr.setFocused(null);
                        } catch (Throwable ignored) {}
                    })
                    .pos(x, y).size(w, h)
                    .createNarration(s -> Component.translatable("ezvr.ui.reroll"))
                    .build();

            e.addListener(reroll);
            REROLL_BUTTONS.put(screen, reroll);

            CooldownOverlayWidget overlay = new CooldownOverlayWidget(x, y, w, h);
            overlay.active = false;
            overlay.visible = true;
            e.addListener(overlay);
            COOLDOWN_OVERLAYS.put(screen, overlay);

            EZVillagerReroll.LOG().info("[EZVR] Reroll button added to MerchantScreen at ({},{}), base=({},{}), offset=({},{}).",
                    x, y, baseX, baseY, ClientConfig.buttonOffsetX, ClientConfig.buttonOffsetY
            );

            trySendTradeLocksQuery();
            trySendCooldownQuery();

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] onScreenInitPost exception", t);
        }
    }

    private static void onScreenRenderPost(final ScreenEvent.Render.Post e) {
        try {
            if (!(e.getScreen() instanceof MerchantScreen screen)) return;

            renderTradeLockIndicators(e, screen);

            Button btn = REROLL_BUTTONS.get(screen);
            CooldownOverlayWidget overlay = COOLDOWN_OVERLAYS.get(screen);
            if (btn == null) return;

            int cid = resolveContainerId(screen);
            boolean cooling = (cid >= 0) && ClientRerollCooldownCache.isCoolingDown(cid);

            if (btn.active == cooling) btn.active = !cooling;

            if (overlay != null) {
                overlay.active = cooling;
            }

            boolean hoverOverlay = overlay != null && overlay.active && overlay.isMouseOver(e.getMouseX(), e.getMouseY());
            boolean hoverButton = btn.isMouseOver(e.getMouseX(), e.getMouseY());

            if (hoverOverlay || hoverButton) {
                if (ClientTooltipCache.ageMs() > TOOLTIP_REFRESH_DEBOUNCE_MS) {
                    trySendTooltipQuery(screen);
                }

                PacketTooltipData snap = ClientTooltipCache.get();
                List<Component> lines = buildTooltipLinesWithCooldown(snap, screen);

                if (lines.isEmpty()) {
                    ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
                    if (cfg != null) lines = List.of(Component.literal("Syncing… (cfg v" + cfg.version + ")"));
                    else lines = List.of(Component.literal("Syncing…"));
                }

                GuiGraphics gg = e.getGuiGraphics();
                gg.renderComponentTooltip(Minecraft.getInstance().font, lines, e.getMouseX(), e.getMouseY());
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] onScreenRenderPost exception", t);
        }
    }

    private static void onScreenClosed(final ScreenEvent.Closing e) {
        try {
            REROLL_BUTTONS.remove(e.getScreen());
            COOLDOWN_OVERLAYS.remove(e.getScreen());

            if (e.getScreen() instanceof MerchantScreen ms) {
                int cid = resolveContainerId(ms);
                if (cid >= 0) {
                    ClientTradeLockCache.clearContainer(cid);
                    ClientRerollCooldownCache.clearContainer(cid);
                } else {
                    ClientTradeLockCache.clearAll();
                    ClientRerollCooldownCache.clearAll();
                }
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] onScreenClosed exception", t);
        }
    }

    private static void renderTradeLockIndicators(ScreenEvent.Render.Post e, MerchantScreen screen) {
        try {
            int cid = resolveContainerId(screen);
            if (cid < 0) return;

            long mask = ClientTradeLockCache.getMaskForContainer(cid);
            if (mask == 0L) return;

            int offerCount = safeOfferCount(screen);
            if (offerCount <= 0) return;

            int scrollOff = readScrollOffset(screen, offerCount);

            List<AbstractWidget> tradeButtons = findTradeOfferButtons(screen);
            if (tradeButtons.isEmpty()) return;

            GuiGraphics gg = e.getGuiGraphics();
            final int outlineColor = 0xFF66FF66;

            for (AbstractWidget w : tradeButtons) {
                if (w == null || !w.visible) continue;

                int rowIdx = readTradeButtonRowIndex(w);
                if (rowIdx < 0 || rowIdx > 63) continue;

                int absoluteIdx = scrollOff + rowIdx;
                if (absoluteIdx < 0 || absoluteIdx >= offerCount) continue;
                if ((mask & (1L << absoluteIdx)) == 0L) continue;

                int x = w.getX();
                int y = w.getY();
                int ww = w.getWidth();
                int hh = w.getHeight();
                if (ww <= 0 || hh <= 0) continue;

                try {
                    gg.renderOutline(x, y, ww, hh, outlineColor);
                } catch (Throwable t) {
                    gg.fill(x, y, x + ww, y + 1, outlineColor);
                    gg.fill(x, y + hh - 1, x + ww, y + hh, outlineColor);
                    gg.fill(x, y, x + 1, y + hh, outlineColor);
                    gg.fill(x + ww - 1, y, x + ww, y + hh, outlineColor);
                }
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] renderTradeLockIndicators exception", t);
        }
    }

    private static List<AbstractWidget> findTradeOfferButtons(MerchantScreen screen) {
        List<AbstractWidget> out = new ArrayList<>();
        try {
            for (GuiEventListener child : screen.children()) {
                if (!(child instanceof AbstractWidget w)) continue;
                String cn = w.getClass().getName();
                if (EZVR_TRADE_BUTTON_CLASS.equals(cn)) out.add(w);
            }
            out.sort(Comparator.comparingInt(AbstractWidget::getY).thenComparingInt(AbstractWidget::getX));
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] findTradeOfferButtons failed", t);
        }
        return out;
    }

    private static int readTradeButtonRowIndex(AbstractWidget w) {
        try {
            Field f = null;
            Class<?> c = w.getClass();

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
            return f.getInt(w);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int readScrollOffset(MerchantScreen screen, int offerCount) {
        try {
            int maxScroll = Math.max(0, offerCount - 7);

            try {
                int raw = ((MerchantScreenAccessor) screen).ezvr$getScrollOff();
                return clamp(raw, 0, maxScroll);
            } catch (Throwable ignored) {}

            Class<?> c = screen.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        if (f.getType() != int.class) continue;
                        String n = f.getName();
                        if (n == null || !n.toLowerCase().contains("scroll")) continue;

                        f.setAccessible(true);
                        int v = f.getInt(screen);
                        if (v >= 0 && v <= maxScroll) return v;
                    } catch (Throwable ignoredField) {}
                }
                c = c.getSuperclass();
            }

            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static int resolveContainerId(MerchantScreen screen) {
        try {
            if (!(screen.getMenu() instanceof MerchantMenu menu)) return -1;
            return menu.containerId;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int safeOfferCount(MerchantScreen screen) {
        try {
            if (!(screen.getMenu() instanceof MerchantMenu menu)) return -1;
            var offers = menu.getOffers();
            return offers == null ? 0 : offers.size();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void trySendTooltipQuery(MerchantScreen screen) {
        try {
            int traderId = resolveTraderEntityId(screen);
            ClientNetwork.sendToServer(new PacketTooltipQuery(traderId));
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client send tooltip query failed", t);
        }
    }

    private static void trySendTradeLocksQuery() {
        try {
            ClientNetwork.sendToServer(new PacketTradeLocksQuery());
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client send trade locks query failed", t);
        }
    }

    private static void trySendCooldownQuery() {
        try {
            ClientNetwork.sendToServer(new PacketRerollCooldownQuery());
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client send cooldown query failed", t);
        }
    }

    /**
     * Final desired:
     * - Active button:   "Cooldown: 5s"
     * - Inactive button: "Cooldown: 4.3s"
     *
     * Exactly ONE cooldown line.
     */
    private static List<Component> buildTooltipLinesWithCooldown(PacketTooltipData d, MerchantScreen screen) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("ezvr.ui.reroll"));

        int cid = resolveContainerId(screen);

        int remainingTicks = 0;
        boolean cooling = false;
        try {
            if (cid >= 0) {
                remainingTicks = Math.max(0, ClientRerollCooldownCache.getRemainingTicks(cid));
                cooling = remainingTicks > 0;
            }
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] Tooltip cooldown remaining read failed (soft): {}", t.toString());
        }

        if (cooling) {
            double sec = remainingTicks / 20.0;
            lines.add(Component.literal(String.format("Cooldown: %.1fs", sec)));
        } else {
            int cfgTicks = 0;

            // Primary: last known cfg ticks from PacketRerollCooldownState (authoritative, always relevant)
            if (cid >= 0) cfgTicks = ClientRerollCooldownCache.getLastKnownTotalCooldownTicks(cid);

            // Secondary: if still unknown, fall back to synced config snapshot (if it exists)
            if (cfgTicks <= 0) {
                try {
                    ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
                    if (cfg != null) cfgTicks = Math.max(0, cfg.cooldownTicks);
                } catch (Throwable ignored) {}
            }

            if (cfgTicks > 0) {
                int secs = (int) Math.ceil(cfgTicks / 20.0);
                if (secs < 0) secs = 0;
                lines.add(Component.literal("Cooldown: " + secs + "s"));
            } else {
                lines.add(Component.literal("Cooldown: ?"));
            }

            EZVillagerReroll.LOG().debug("[EZVR] Tooltip cooldown (active): cfgTicks={} (containerId={})", cfgTicks, cid);
        }

        if (d == null) return lines;

        try {
            final int cost = d.cost != null ? d.cost.scaledCost : 0;
            final Integer next = (d.cost != null) ? d.cost.nextCostIfUsed : null;

            final String spec = (d.cost != null) ? d.cost.itemOrTag : null;
            final String pretty = prettyCostSpec(spec);

            if (cost <= 0) lines.add(Component.literal("Cost: Free"));
            else lines.add(Component.literal("Cost: " + cost + " × " + pretty));

            if (next != null && next > 0) lines.add(Component.literal("Next level: " + next + " × " + pretty));

            if (d.afford != null) {
                String src = d.afford.source == null ? "none" : d.afford.source;
                if (cost > 0) lines.add(Component.literal(d.afford.canAfford ? "Affordable (" + src + ")" : "Not affordable (" + src + ")"));
            }

            if (d.villager != null && d.villager.level > 0) {
                lines.add(Component.literal("Villager level: " + d.villager.level));
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] buildTooltipLinesWithCooldown failed (soft): {}", t.toString());
        }

        return lines;
    }

    private static String prettyCostSpec(String spec) {
        try {
            if (spec == null || spec.isBlank()) return "unknown";

            String s = spec.trim();
            if (s.startsWith("#")) {
                String tag = s.substring(1);
                int colon = tag.indexOf(':');
                if (colon >= 0 && colon + 1 < tag.length()) tag = tag.substring(colon + 1);
                return "#" + tag;
            }

            int colon = s.indexOf(':');
            if (colon >= 0 && colon + 1 < s.length()) return s.substring(colon + 1);
            return s;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    public static int resolveTraderEntityId(MerchantScreen screen) {
        try {
            if (screen == null) return -1;

            try {
                MerchantMenu menu = (screen.getMenu() instanceof MerchantMenu mm) ? mm : null;
                if (menu != null) {
                    Integer id = reflectFindEntityId(menu);
                    if (id != null) return id;
                }
            } catch (Throwable ignored) {}

            try {
                Integer id = reflectFindEntityId(screen);
                if (id != null) return id;
            } catch (Throwable ignored) {}

            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Integer reflectFindEntityId(Object holder) {
        try {
            if (holder == null) return null;

            Class<?> c = holder.getClass();
            while (c != null && c != Object.class) {
                java.lang.reflect.Field[] fields = c.getDeclaredFields();
                for (java.lang.reflect.Field f : fields) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(holder);
                        if (v == null) continue;

                        if (v instanceof net.minecraft.world.entity.Entity ent) return ent.getId();

                        if (!(v instanceof Number) && !(v instanceof String) && !(v.getClass().isPrimitive())) {
                            Integer nested = reflectFindEntityIdShallow(v);
                            if (nested != null) return nested;
                        }
                    } catch (Throwable ignoredField) {}
                }
                c = c.getSuperclass();
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Integer reflectFindEntityIdShallow(Object holder) {
        try {
            if (holder == null) return null;

            Class<?> c = holder.getClass();
            java.lang.reflect.Field[] fields = c.getDeclaredFields();
            for (java.lang.reflect.Field f : fields) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(holder);
                    if (v instanceof net.minecraft.world.entity.Entity ent) return ent.getId();
                } catch (Throwable ignored) {}
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class CooldownOverlayWidget extends AbstractWidget {

        CooldownOverlayWidget(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty());
        }

        @Override
        protected void renderWidget(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
            // intentionally empty
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput out) {
            // no narration
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            try {
                if (!this.active) return false;
                if (!this.isMouseOver(mouseX, mouseY)) return false;

                EZVillagerReroll.LOG().debug("[EZVR] CooldownOverlayWidget consumed click (button={})", button);
                return true;
            } catch (Throwable t) {
                EZVillagerReroll.LOG().debug("[EZVR] CooldownOverlayWidget.mouseClicked failed (soft): {}", t.toString());
                return false;
            }
        }
    }

    private ClientUI() {}
}
