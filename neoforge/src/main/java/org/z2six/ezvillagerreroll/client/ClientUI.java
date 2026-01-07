// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/client/ClientUI.java
package org.z2six.ezvillagerreroll.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MerchantMenu;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.z2six.ezvillagerreroll.Constants;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.config.ClientConfig;
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

    /**
     * Vanilla chain block texture (16x16).
     * Used as a UI overlay decal to represent "locked".
     */
    private static final ResourceLocation CHAIN_TEX =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/block/chain.png");

    public static void registerRuntimeClientEvents() {
        NeoForge.EVENT_BUS.addListener(ClientUI::onScreenInitPost);
        NeoForge.EVENT_BUS.addListener(ClientUI::onScreenRenderPost);
        NeoForge.EVENT_BUS.addListener(ClientUI::onScreenClosed);

        // IMPORTANT: RMB toggling is handled via mixin now. Do NOT also do it here (double toggles).
        EZVillagerReroll.LOG().info("[EZVR] ClientUI.registerRuntimeClientEvents(): handlers added");
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

            // Request initial lock state for this currently-open merchant menu
            trySendTradeLocksQuery();

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] onScreenInitPost exception", t);
        }
    }

    private static void onScreenRenderPost(final ScreenEvent.Render.Post e) {
        try {
            if (!(e.getScreen() instanceof MerchantScreen screen)) return;

            renderTradeLockIndicators(e, screen);

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

            if (e.getScreen() instanceof MerchantScreen ms) {
                int cid = resolveContainerId(ms);
                if (cid >= 0) {
                    ClientTradeLockCache.clearContainer(cid);
                    EZVillagerReroll.LOG().debug("[EZVR] Cleared ClientTradeLockCache for containerId={}", cid);
                } else {
                    ClientTradeLockCache.clearAll();
                    EZVillagerReroll.LOG().debug("[EZVR] Cleared ClientTradeLockCache (all) on MerchantScreen close");
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

            List<AbstractWidget> tradeButtons = findTradeOfferButtons(screen);
            if (tradeButtons.isEmpty()) return;

            GuiGraphics gg = e.getGuiGraphics();

            final int outlineColor = 0xFF66FF66;

            for (int i = 0; i < tradeButtons.size(); i++) {
                if ((mask & (1L << i)) == 0L) continue;

                AbstractWidget w = tradeButtons.get(i);
                if (w == null || !w.visible) continue;

                int x = w.getX();
                int y = w.getY();
                int ww = w.getWidth();
                int hh = w.getHeight();

                // Green outline (keep)
                try {
                    gg.renderOutline(x, y, ww, hh, outlineColor);
                } catch (Throwable t) {
                    // Fallback outline
                    gg.fill(x, y, x + ww, y + 1, outlineColor);
                    gg.fill(x, y + hh - 1, x + ww, y + hh, outlineColor);
                    gg.fill(x, y, x + 1, y + hh, outlineColor);
                    gg.fill(x + ww - 1, y, x + ww, y + hh, outlineColor);
                }

                // Replace the previous left-side square marker with a chain "X" overlay inside the button.
                try {
                    // renderChainX(gg, x, y, ww, hh);
                } catch (Throwable t) {
                    // If rendering fails for any reason, do nothing; outline still indicates locked.
                    EZVillagerReroll.LOG().debug("[EZVR] renderChainX failed (soft): {}", t.toString());
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
                if (cn == null) continue;

                if (cn.contains("MerchantScreen") && cn.contains("TradeOfferButton")) out.add(w);
            }

            out.sort(Comparator.comparingInt(AbstractWidget::getY).thenComparingInt(AbstractWidget::getX));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] findTradeOfferButtons failed", t);
        }
        return out;
    }

    private static int resolveContainerId(MerchantScreen screen) {
        try {
            if (!(screen.getMenu() instanceof MerchantMenu menu)) return -1;
            return menu.containerId;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void trySendTooltipQuery(MerchantScreen screen) {
        try {
            int traderId = resolveTraderEntityId(screen);

            Network.sendToServer(new PacketTooltipQuery(traderId));

            EZVillagerReroll.LOG().debug("[EZVR] Sent tooltip query (traderEntityId={})", traderId);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client send tooltip query failed", t);
        }
    }

    private static void trySendTradeLocksQuery() {
        try {
            Network.sendToServer(new PacketTradeLocksQuery());
            EZVillagerReroll.LOG().debug("[EZVR] Sent trade locks query (current menu)");
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client send trade locks query failed", t);
        }
    }

    private static List<Component> buildTooltipLines(PacketTooltipData d) {
        List<Component> lines = new ArrayList<>();
        if (d == null) return lines;

        // Title
        lines.add(Component.translatable("ezvr.ui.reroll"));

        try {
            final int cost = d.cost != null ? d.cost.scaledCost : 0;
            final Integer next = (d.cost != null) ? d.cost.nextCostIfUsed : null;

            final String spec = (d.cost != null) ? d.cost.itemOrTag : null;
            final String pretty = prettyCostSpec(spec);

            // Current cost
            if (cost <= 0) {
                lines.add(Component.literal("Cost: Free"));
            } else {
                lines.add(Component.literal("Cost: " + cost + " × " + pretty));
            }

            // Next level cost (only when present)
            if (next != null && next > 0) {
                lines.add(Component.literal("Next level: " + next + " × " + pretty));
            }

            // (Optional but useful) affordability hint
            if (d.afford != null) {
                String src = d.afford.source == null ? "none" : d.afford.source;
                if (cost > 0) {
                    lines.add(Component.literal(d.afford.canAfford ? "Affordable (" + src + ")" : "Not affordable (" + src + ")"));
                }
            }

            // (Optional) villager info (kept subtle)
            if (d.villager != null && d.villager.level > 0) {
                lines.add(Component.literal("Villager level: " + d.villager.level));
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] buildTooltipLines failed (soft): {}", t.toString());
        }

        return lines;
    }
    
    private static String prettyCostSpec(String spec) {
        try {
            if (spec == null || spec.isBlank()) return "unknown";

            String s = spec.trim();
            if (s.startsWith("#")) {
                // tag
                String tag = s.substring(1);
                int colon = tag.indexOf(':');
                if (colon >= 0 && colon + 1 < tag.length()) tag = tag.substring(colon + 1);
                return "#" + tag;
            }

            // item id
            int colon = s.indexOf(':');
            if (colon >= 0 && colon + 1 < s.length()) return s.substring(colon + 1);
            return s;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    // It tries hard to find an Entity (Villager) reference from the screen/menu.
    // If it can't, it returns -1 (server will fall back to level 1 behavior).
    private static int resolveTraderEntityId(MerchantScreen screen) {
        try {
            if (screen == null) return -1;

            // 1) Try menu fields first
            try {
                MerchantMenu menu = (screen.getMenu() instanceof MerchantMenu mm) ? mm : null;
                if (menu != null) {
                    Integer id = reflectFindEntityId(menu);
                    if (id != null) return id;
                }
            } catch (Throwable ignored) {}

            // 2) Try screen fields
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

                        // Direct entity reference?
                        if (v instanceof net.minecraft.world.entity.Entity ent) {
                            return ent.getId();
                        }

                        // Sometimes stored as merchant/trader object that may itself be an entity
                        // or may contain an entity field; do a shallow one-level dive for common cases.
                        if (!(v instanceof Number) && !(v instanceof String) && !(v.getClass().isPrimitive())) {
                            Integer nested = reflectFindEntityIdShallow(v);
                            if (nested != null) return nested;
                        }
                    } catch (Throwable ignoredField) {
                        // Keep scanning
                    }
                }
                c = c.getSuperclass();
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // Shallow scan only (prevents runaway recursion).
    private static Integer reflectFindEntityIdShallow(Object holder) {
        try {
            if (holder == null) return null;

            Class<?> c = holder.getClass();
            java.lang.reflect.Field[] fields = c.getDeclaredFields();
            for (java.lang.reflect.Field f : fields) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(holder);
                    if (v instanceof net.minecraft.world.entity.Entity ent) {
                        return ent.getId();
                    }
                } catch (Throwable ignored) {}
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private ClientUI() {}
}
