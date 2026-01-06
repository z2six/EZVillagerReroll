// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/client/ClientUI.java
package org.z2six.ezvillagerreroll.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.inventory.MerchantMenu;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.config.ClientConfig;
import org.z2six.ezvillagerreroll.mixin.MerchantMenuAccessor;
import org.z2six.ezvillagerreroll.network.ClientSyncedConfig;
import org.z2six.ezvillagerreroll.network.ClientTooltipCache;
import org.z2six.ezvillagerreroll.network.ClientTradeLockCache;
import org.z2six.ezvillagerreroll.network.Network;
import org.z2six.ezvillagerreroll.network.PacketRequestReroll;
import org.z2six.ezvillagerreroll.network.PacketTooltipData;
import org.z2six.ezvillagerreroll.network.PacketTooltipQuery;
import org.z2six.ezvillagerreroll.network.PacketTradeLocksQuery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class ClientUI {

    private static final long TOOLTIP_REFRESH_DEBOUNCE_MS = 750;
    private static final Map<Screen, Button> REROLL_BUTTONS = new WeakHashMap<>();

    public static void registerRuntimeClientEvents() {
        NeoForge.EVENT_BUS.addListener(ClientUI::onScreenInitPost);
        NeoForge.EVENT_BUS.addListener(ClientUI::onScreenRenderPost);
        NeoForge.EVENT_BUS.addListener(ClientUI::onScreenClosed);

        // IMPORTANT:
        // We DO NOT register any RMB handler here.
        // RMB trade locking is handled exclusively by MerchantScreenTradeLockRightClickMixin.
        // Registering both causes double-toggle (net no change).
        EZVillagerReroll.LOG().info("[EZVR] ClientUI.registerRuntimeClientEvents(): handlers added (RMB handled by mixin)");
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
                            Network.sendToServer(new PacketRequestReroll());
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

            EZVillagerReroll.LOG().info("[EZVR] Reroll button added to MerchantScreen at ({},{}), base=({},{}), offset=({},{}).",
                    x, y, baseX, baseY, ClientConfig.buttonOffsetX, ClientConfig.buttonOffsetY
            );

            // Request initial lock state for this trader so indicators show immediately
            trySendTradeLocksQuery(screen);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] onScreenInitPost exception", t);
        }
    }

    private static void onScreenRenderPost(final ScreenEvent.Render.Post e) {
        try {
            if (!(e.getScreen() instanceof MerchantScreen screen)) return;

            // Trade lock markers
            renderTradeLockIndicators(e, screen);

            // Existing reroll button tooltip rendering
            Button btn = REROLL_BUTTONS.get(screen);
            if (btn == null) return;

            if (btn.isMouseOver(e.getMouseX(), e.getMouseY())) {
                if (ClientTooltipCache.ageMs() > TOOLTIP_REFRESH_DEBOUNCE_MS) {
                    trySendTooltipQuery(screen);
                }

                List<Component> lines = buildTooltipLines(ClientTooltipCache.get());
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

            if (e.getScreen() instanceof MerchantScreen) {
                ClientTradeLockCache.clearAll();
                EZVillagerReroll.LOG().debug("[EZVR] Cleared ClientTradeLockCache (all) on MerchantScreen close");
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] onScreenClosed exception", t);
        }
    }

    private static void renderTradeLockIndicators(ScreenEvent.Render.Post e, MerchantScreen screen) {
        try {
            int traderId = resolveTraderEntityId(screen);
            if (traderId < 0) return;

            long mask = ClientTradeLockCache.getMask(traderId);
            if (mask == 0L) return;

            List<AbstractWidget> tradeButtons = findTradeOfferButtons(screen);
            if (tradeButtons.isEmpty()) return;

            GuiGraphics gg = e.getGuiGraphics();

            final int outlineColor = 0xFF66FF66;
            final int markerFill = 0xAA66FF66;

            for (int i = 0; i < tradeButtons.size(); i++) {
                if ((mask & (1L << i)) == 0L) continue;

                AbstractWidget w = tradeButtons.get(i);
                if (w == null || !w.visible) continue;

                int x = w.getX();
                int y = w.getY();
                int ww = w.getWidth();
                int hh = w.getHeight();

                try {
                    gg.renderOutline(x, y, ww, hh, outlineColor);
                } catch (Throwable t) {
                    gg.fill(x, y, x + ww, y + 1, outlineColor);
                    gg.fill(x, y + hh - 1, x + ww, y + hh, outlineColor);
                    gg.fill(x, y, x + 1, y + hh, outlineColor);
                    gg.fill(x + ww - 1, y, x + ww, y + hh, outlineColor);
                }

                int mSize = 6;
                int mx = x - (mSize + 2);
                int my = y + (hh - mSize) / 2;

                gg.fill(mx, my, mx + mSize, my + mSize, markerFill);
                try {
                    gg.renderOutline(mx, my, mSize, mSize, outlineColor);
                } catch (Throwable t) {
                    gg.fill(mx, my, mx + mSize, my + 1, outlineColor);
                    gg.fill(mx, my + mSize - 1, mx + mSize, my + mSize, outlineColor);
                    gg.fill(mx, my, mx + 1, my + mSize, outlineColor);
                    gg.fill(mx + mSize - 1, my, mx + mSize, my + mSize, outlineColor);
                }
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] renderTradeLockIndicators exception", t);
        }
    }

    /**
     * Finds the trade offer buttons without referencing MerchantScreen.TradeOfferButton at compile time.
     */
    private static List<AbstractWidget> findTradeOfferButtons(MerchantScreen screen) {
        List<AbstractWidget> out = new ArrayList<>();
        try {
            for (GuiEventListener child : screen.children()) {
                if (!(child instanceof AbstractWidget w)) continue;

                String cn = w.getClass().getName();
                if (cn == null) continue;

                if (cn.contains("MerchantScreen") && cn.contains("TradeOfferButton")) out.add(w);
            }

            out.sort(Comparator.comparingInt(AbstractWidget::getY).thenComparingInt(AbstractWidget::getX));

            if (!out.isEmpty()) {
                EZVillagerReroll.LOG().debug("[EZVR] Found {} trade offer button(s) via child scan.", out.size());
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] findTradeOfferButtons failed", t);
        }
        return out;
    }

    private static int resolveTraderEntityId(MerchantScreen screen) {
        try {
            if (!(screen.getMenu() instanceof MerchantMenu menu)) return -1;

            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (trader instanceof Entity ent) return ent.getId();
            if (trader instanceof AbstractVillager av) return av.getId();
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void trySendTooltipQuery(MerchantScreen screen) {
        try {
            if (screen.getMenu() instanceof MerchantMenu menu) {
                var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
                if (trader instanceof Entity ent) Network.sendToServer(new PacketTooltipQuery(ent.getId()));
                else if (trader instanceof AbstractVillager av) Network.sendToServer(new PacketTooltipQuery(av.getId()));
                else Network.sendToServer(new PacketTooltipQuery(-1));

                EZVillagerReroll.LOG().debug("[EZVR] Sent tooltip query for trader={}", trader == null ? "null" : trader.getClass().getName());
            }
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client send tooltip query failed", t);
        }
    }

    private static void trySendTradeLocksQuery(MerchantScreen screen) {
        try {
            int traderId = resolveTraderEntityId(screen);
            if (traderId < 0) {
                EZVillagerReroll.LOG().debug("[EZVR] TradeLocksQuery skipped: traderId unresolved on client");
                return;
            }

            Network.sendToServer(new PacketTradeLocksQuery(traderId));
            EZVillagerReroll.LOG().debug("[EZVR] Sent trade locks query for traderId={}", traderId);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client send trade locks query failed", t);
        }
    }

    private static List<Component> buildTooltipLines(PacketTooltipData d) {
        List<Component> lines = new ArrayList<>();
        if (d == null) return lines;

        lines.add(Component.translatable("ezvr.ui.reroll"));

        if (d.cost.scaledCost <= 0 || d.cfg.freeMode) {
            lines.add(Component.literal("Cost: Free"));
        } else {
            String itemName = "Unknown Item";
            try {
                if (d.cost.itemOrTag != null && d.cost.itemOrTag.startsWith("#")) {
                    itemName = d.cost.itemOrTag;
                } else if (d.cost.item != null) {
                    var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(d.cost.item);
                    if (item != null) itemName = new net.minecraft.world.item.ItemStack(item).getHoverName().getString();
                }
            } catch (Throwable ignored) {}

            String affordMark = d.afford.canAfford ? "✔" : "✖";
            String src = switch (d.afford.source == null ? "none" : d.afford.source) {
                case "wallet" -> " (from wallet)";
                case "inventory" -> " (from inventory)";
                case "both" -> " (wallet/inventory)";
                default -> "";
            };
            lines.add(Component.literal("Cost: " + d.cost.scaledCost + " × " + itemName + " " + affordMark + src));
        }

        lines.add(Component.literal("Villager: L" + d.villager.level + " (" + d.villager.xp + " XP)"));

        if (d.cost.nextCostIfUsed != null) lines.add(Component.literal("Next cost: " + d.cost.nextCostIfUsed));
        if (d.cost.maxCostPossible != null) lines.add(Component.literal("Max cost: " + d.cost.maxCostPossible));

        if (d.cfg.capEnabled && d.cap.enabled && d.cap.cap > 0) {
            if (d.cap.remaining >= 0) lines.add(Component.literal("Daily uses: " + d.cap.remaining + " / " + d.cap.cap));
            else lines.add(Component.literal("Daily uses: — / " + d.cap.cap));
        }

        if (d.cfg.version > 0) lines.add(Component.literal("Config: v" + d.cfg.version + " (hash " + d.cfg.hash + ")"));

        return lines;
    }

    private ClientUI() {}
}
