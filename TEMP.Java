---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\EZVillagerReroll.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/EZVillagerReroll.java
        package org.z2six.ezvillagerreroll;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.z2six.ezvillagerreroll.client.ClientUI;
import org.z2six.ezvillagerreroll.config.ClientConfig;
import org.z2six.ezvillagerreroll.config.ServerConfig;
import org.z2six.ezvillagerreroll.network.Network;
import org.z2six.ezvillagerreroll.server.BusyVillagerBlocker;
import org.z2six.ezvillagerreroll.server.ServerEvents;

@Mod(Constants.MOD_ID)
public final class EZVillagerReroll {

    private static final Logger LOG = LogUtils.getLogger();

    public EZVillagerReroll(final IEventBus modBus, final ModContainer modContainer) {
        LOG.info("[EZVR] Initializing EZVillagerReroll (modid={})", Constants.MOD_ID);

        // --- Register configs (these are MOD lifecycle, not gameplay) ---
        try {
            modContainer.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
            LOG.info("[EZVR] Registered SERVER config spec.");
        } catch (Throwable t) {
            LOG.error("[EZVR] Failed to register SERVER config spec (continuing).", t);
        }

        try {
            modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
            LOG.info("[EZVR] Registered CLIENT config spec.");
        } catch (Throwable t) {
            LOG.error("[EZVR] Failed to register CLIENT config spec (continuing).", t);
        }

        // --- MOD BUS listeners (IModBusEvent + lifecycle) ---
        // IMPORTANT: do NOT register these on NeoForge.EVENT_BUS
        try {
            modBus.addListener(Network::onRegisterPayloadHandlers);
            LOG.info("[EZVR] Registered Network payload handler registration on MOD bus.");
        } catch (Throwable t) {
            LOG.error("[EZVR] Failed to register Network payload handlers listener.", t);
        }

        try {
            modBus.addListener(ServerConfig::onConfigLoading);
            modBus.addListener(ServerConfig::onConfigReloading);
            LOG.info("[EZVR] Registered ServerConfig load/reload listeners on MOD bus.");
        } catch (Throwable t) {
            LOG.error("[EZVR] Failed to register ServerConfig listeners.", t);
        }

        try {
            modBus.addListener(this::commonSetup);
            modBus.addListener(this::clientSetup);
            LOG.info("[EZVR] Registered common/client setup listeners on MOD bus.");
        } catch (Throwable t) {
            LOG.error("[EZVR] Failed to register setup listeners.", t);
        }

        // --- GAMEPLAY BUS listeners (NeoForge bus) ---
        // IMPORTANT: gameplay/runtime events => NeoForge.EVENT_BUS
        try {
            // Tick + server start/stop (persistence)
            ServerEvents.register(NeoForge.EVENT_BUS);
            LOG.info("[EZVR] Registered ServerEvents on NeoForge EVENT bus.");
        } catch (Throwable t) {
            LOG.error("[EZVR] Failed to register ServerEvents (continuing).", t);
        }

        try {
            // RMB interception for busy villagers (this is the logic you said got "destroyed" because it wasn't wired)
            BusyVillagerBlocker.register(NeoForge.EVENT_BUS);
            LOG.info("[EZVR] Registered BusyVillagerBlocker on NeoForge EVENT bus.");
        } catch (Throwable t) {
            LOG.error("[EZVR] Failed to register BusyVillagerBlocker (continuing).", t);
        }
    }

    private void commonSetup(final FMLCommonSetupEvent e) {
        LOG.info("[EZVR] Common setup.");
        // Nothing required here for payload registration anymore (that happens in RegisterPayloadHandlersEvent).
        // Keep this method for future safe-init work.
    }

    private void clientSetup(final FMLClientSetupEvent e) {
        LOG.info("[EZVR] Client setup.");
        try {
            ClientUI.registerRuntimeClientEvents();
            LOG.info("[EZVR] ClientUI runtime events registered.");
        } catch (Throwable t) {
            LOG.error("[EZVR] ClientUI registration failed (client features may be limited).", t);
        }
    }

    public static Logger LOG() {
        return LOG;
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\EZVillagerReroll.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\client\AutoSearchPaymentScreen.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/client/AutoSearchPaymentScreen.java
        package org.z2six.ezvillagerreroll.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.network.Network;
import org.z2six.ezvillagerreroll.network.PacketDeclineAutoSearchSettlement;
import org.z2six.ezvillagerreroll.network.PacketPayAutoSearchSettlement;

/**
 * Simple settlement/payment UI for auto-search.
 *
 * Server sends PacketOpenAutoSearchPaymentScreen to open this.
 * Client sends:
 * - PacketPayAutoSearchSettlement(villagerEntityId)
 * - PacketDeclineAutoSearchSettlement(villagerEntityId)
 *
 * Server responds with PacketAutoSearchSettlementCleared which closes this screen via ClientNetworkHandlers.
 */
public final class AutoSearchPaymentScreen extends Screen {

    private final int villagerEntityId;
    private final int hourlyCost;
    private final int finalCost;
    private final int elapsedTicks;

    private Button btnPay;
    private Button btnDecline;

    private boolean sentAction = false;

    public AutoSearchPaymentScreen(int villagerEntityId, int hourlyCost, int finalCost, int elapsedTicks) {
        super(Component.translatable("ezvr.auto_search.payment.title"));
        this.villagerEntityId = villagerEntityId;
        this.hourlyCost = Math.max(0, hourlyCost);
        this.finalCost = Math.max(0, finalCost);
        this.elapsedTicks = Math.max(0, elapsedTicks);
    }

    public int getVillagerEntityId() {
        return villagerEntityId;
    }

    @Override
    protected void init() {
        try {
            super.init();

            int cx = this.width / 2;
            int y = this.height / 2 + 30;

            btnPay = Button.builder(Component.literal("Pay").withStyle(ChatFormatting.GREEN), b -> onPay())
                    .pos(cx - 110, y)
                    .size(100, 20)
                    .build();

            btnDecline = Button.builder(Component.literal("Decline").withStyle(ChatFormatting.RED), b -> onDecline())
                    .pos(cx + 10, y)
                    .size(100, 20)
                    .build();

            addRenderableWidget(btnPay);
            addRenderableWidget(btnDecline);

            EZVillagerReroll.LOG().info("[EZVR] AutoSearchPaymentScreen opened: villagerEntityId={} hourlyCost={} finalCost={} elapsedTicks={}",
                    villagerEntityId, hourlyCost, finalCost, elapsedTicks);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] AutoSearchPaymentScreen.init failed", t);
        }
    }

    private void onPay() {
        try {
            if (sentAction) {
                EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen: Pay ignored (already sent action).");
                return;
            }
            if (villagerEntityId < 0) {
                EZVillagerReroll.LOG().warn("[EZVR] AutoSearchPaymentScreen: Pay ignored (villagerEntityId<0).");
                return;
            }

            sentAction = true;
            setButtonsActive(false);

            EZVillagerReroll.LOG().info("[EZVR] AutoSearchPaymentScreen: sending PacketPayAutoSearchSettlement(villagerEntityId={})",
                    villagerEntityId);
            Network.sendToServer(new PacketPayAutoSearchSettlement(villagerEntityId));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] AutoSearchPaymentScreen.onPay failed", t);
            sentAction = false;
            setButtonsActive(true);
        }
    }

    private void onDecline() {
        try {
            if (sentAction) {
                EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen: Decline ignored (already sent action).");
                return;
            }
            if (villagerEntityId < 0) {
                EZVillagerReroll.LOG().warn("[EZVR] AutoSearchPaymentScreen: Decline ignored (villagerEntityId<0).");
                return;
            }

            sentAction = true;
            setButtonsActive(false);

            EZVillagerReroll.LOG().info("[EZVR] AutoSearchPaymentScreen: sending PacketDeclineAutoSearchSettlement(villagerEntityId={})",
                    villagerEntityId);
            Network.sendToServer(new PacketDeclineAutoSearchSettlement(villagerEntityId));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] AutoSearchPaymentScreen.onDecline failed", t);
            sentAction = false;
            setButtonsActive(true);
        }
    }

    private void setButtonsActive(boolean active) {
        try {
            if (btnPay != null) btnPay.active = active;
            if (btnDecline != null) btnDecline.active = active;
        } catch (Throwable ignored) {}
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        try {
            this.renderBackground(gg, mouseX, mouseY, partialTick);
            super.render(gg, mouseX, mouseY, partialTick);

            int cx = this.width / 2;
            int y = this.height / 2 - 40;

            gg.drawCenteredString(this.font,
                    Component.literal("Auto-search complete").withStyle(ChatFormatting.GOLD),
                    cx, y, 0xFFFFFF);

            y += 16;

            // elapsed seconds
            int seconds = elapsedTicks / 20;
            gg.drawCenteredString(this.font,
                    Component.literal("Time: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(seconds + "s").withStyle(ChatFormatting.WHITE)),
                    cx, y, 0xFFFFFF);

            y += 14;

            gg.drawCenteredString(this.font,
                    Component.literal("Hourly cost: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(String.valueOf(hourlyCost)).withStyle(ChatFormatting.WHITE)),
                    cx, y, 0xFFFFFF);

            y += 14;

            gg.drawCenteredString(this.font,
                    Component.literal("Final cost: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(String.valueOf(finalCost)).withStyle(ChatFormatting.WHITE)),
                    cx, y, 0xFFFFFF);

            // Tiny emerald icon next to final cost line (purely cosmetic).
            try {
                ItemStack em = new ItemStack(Items.EMERALD);
                int iconX = cx + this.font.width("Final cost: " + finalCost) / 2 + 6;
                int iconY = (this.height / 2 - 40) + 16 + 14 + 14 + 14 - 2;
                gg.renderItem(em, iconX, iconY);
                gg.renderItemDecorations(this.font, em, iconX, iconY);
            } catch (Throwable ignored) {}

            y += 18;

            if (sentAction) {
                gg.drawCenteredString(this.font,
                        Component.literal("Waiting for server…").withStyle(ChatFormatting.DARK_GRAY),
                        cx, y, 0xFFFFFF);
            } else {
                gg.drawCenteredString(this.font,
                        Component.literal("Pay to keep the offers, or decline to revert.").withStyle(ChatFormatting.DARK_GRAY),
                        cx, y, 0xFFFFFF);
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] AutoSearchPaymentScreen.render failed", t);
        }
    }

    /**
     * ESC should just close the screen locally (does not decline).
     * Settlement remains pending server-side, and RMB can reopen it.
     */
    @Override
    public void onClose() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.setScreen(null);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen.onClose failed (soft): {}", t.toString());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\client\AutoSearchPaymentScreen.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\client\BusyVillagerScreen.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/client/BusyVillagerScreen.java
        package org.z2six.ezvillagerreroll.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.network.PacketCancelAutoSearch;
import org.z2six.ezvillagerreroll.network.PacketContinueAutoSearch;

import java.util.ArrayList;
import java.util.List;

public final class BusyVillagerScreen extends Screen {

    private final int villagerEntityId;
    private final List<ItemStack> requested;

    private Button btnCancel;
    private Button btnContinue;

    // Grid/scroll
    private int scrollRow = 0;

    private static final int ITEM_SIZE = 18;
    private static final int PAD = 4;
    private static final int SCROLLBAR_W = 8;

    private SimpleScrollBar scrollBar;

    public BusyVillagerScreen(int villagerEntityId, List<ItemStack> requested) {
        super(Component.translatable("ezvr.busy.title"));
        this.villagerEntityId = villagerEntityId;
        this.requested = requested == null ? List.of() : new ArrayList<>(requested);
    }

    public int getVillagerEntityIdSafe() {
        return villagerEntityId;
    }

    @Override
    protected void init() {
        try {
            super.init();

            int cx = this.width / 2;
            int y = this.height - 28;

            btnCancel = Button.builder(Component.translatable("ezvr.busy.cancel"), b -> {
                        try {
                            ClientNetwork.sendToServer(new PacketCancelAutoSearch(villagerEntityId));
                            EZVillagerReroll.LOG().info("[EZVR] BusyVillagerScreen: sent cancel request (villagerEntityId={})", villagerEntityId);
                        } catch (Throwable t) {
                            EZVillagerReroll.LOG().error("[EZVR] BusyVillagerScreen: cancel send failed", t);
                        }
                        tryClose();
                    })
                    .pos(cx - 110, y)
                    .size(100, 20)
                    .build();

            btnContinue = Button.builder(Component.translatable("ezvr.busy.continue"), b -> {
                        try {
                            ClientNetwork.sendToServer(new PacketContinueAutoSearch(villagerEntityId));
                            EZVillagerReroll.LOG().info("[EZVR] BusyVillagerScreen: continue pressed (villagerEntityId={})", villagerEntityId);
                        } catch (Throwable t) {
                            EZVillagerReroll.LOG().error("[EZVR] BusyVillagerScreen: continue send failed", t);
                        }
                        tryClose();
                    })
                    .pos(cx + 10, y)
                    .size(100, 20)
                    .build();

            addRenderableWidget(btnCancel);
            addRenderableWidget(btnContinue);

            // Scrollbar
            int gridTop = 20 + 18 + 18 + 6; // header + subheader + padding
            int gridBottom = this.height - 70;
            int gridH = Math.max(1, gridBottom - gridTop);

            scrollBar = new SimpleScrollBar(
                    this.width - 20 - SCROLLBAR_W,
                    gridTop,
                    SCROLLBAR_W,
                    gridH
            );

            clampScroll();

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] BusyVillagerScreen.init failed", t);
        }
    }

    private void clampScroll() {
        try {
            int totalRows = computeTotalRows();
            int visibleRows = computeVisibleRows();
            int maxRow = Math.max(0, totalRows - visibleRows);
            if (scrollRow < 0) scrollRow = 0;
            if (scrollRow > maxRow) scrollRow = maxRow;
        } catch (Throwable ignored) {}
    }

    private int computeCols() {
        try {
            int usableW = (this.width - 40 - SCROLLBAR_W - 2);
            return Math.max(1, usableW / (ITEM_SIZE + PAD));
        } catch (Throwable t) {
            return 1;
        }
    }

    private int computeVisibleRows() {
        try {
            int gridTop = 20 + 18 + 18 + 6;
            int gridBottom = this.height - 70;
            int gridH = Math.max(1, gridBottom - gridTop);
            return Math.max(1, gridH / (ITEM_SIZE + PAD));
        } catch (Throwable t) {
            return 1;
        }
    }

    private int computeTotalRows() {
        try {
            int cols = computeCols();
            int count = 0;
            for (ItemStack s : requested) {
                if (s == null || s.isEmpty()) continue;
                count++;
            }
            return (count + cols - 1) / cols;
        } catch (Throwable t) {
            return 0;
        }
    }

    private void tryClose() {
        try {
            if (this.minecraft != null) this.minecraft.setScreen(null);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] BusyVillagerScreen.tryClose failed (soft): {}", t.toString());
        }
    }

    /**
     * MC 1.21.1 ContainerEventHandler signature uses 4 params:
     * mouseScrolled(mouseX, mouseY, scrollX, scrollY)
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        try {
            if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);

            int step = (scrollY > 0) ? -1 : 1;
            scrollRow += step;
            clampScroll();

            EZVillagerReroll.LOG().debug("[EZVR] BusyVillagerScreen mouseScrolled scrollY={} -> scrollRow={}", scrollY, scrollRow);
            return true;
        } catch (Throwable t) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        try {
            if (scrollBar != null && scrollBar.mouseClicked(mouseX, mouseY, button)) return true;
        } catch (Throwable ignored) {}
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        try {
            if (scrollBar != null && scrollBar.mouseReleased(mouseX, mouseY, button)) return true;
        } catch (Throwable ignored) {}
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        try {
            if (scrollBar != null && scrollBar.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
                int maxRow = Math.max(0, computeTotalRows() - computeVisibleRows());
                scrollRow = scrollBar.getScrollRowFromThumb(scrollRow, maxRow);
                clampScroll();
                return true;
            }
        } catch (Throwable ignored) {}
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        try {
            this.renderBackground(gg, mouseX, mouseY, partialTick);
            super.render(gg, mouseX, mouseY, partialTick);

            int cx = this.width / 2;
            int y = 20;

            gg.drawCenteredString(this.font, Component.translatable("ezvr.busy.header"), cx, y, 0xFFFFFF);
            y += 18;
            gg.drawCenteredString(this.font, Component.translatable("ezvr.busy.subheader"), cx, y, 0xB0B0B0);

            // Grid bounds
            int gridTop = 20 + 18 + 18 + 6;
            int gridBottom = this.height - 70;
            int gridLeftMin = 20;

            int cols = computeCols();
            int visibleRows = computeVisibleRows();

            // Flatten non-empty
            List<ItemStack> flat = new ArrayList<>();
            for (ItemStack s : requested) {
                if (s == null || s.isEmpty()) continue;
                flat.add(s);
            }

            int startIndex = scrollRow * cols;
            int endIndex = Math.min(flat.size(), startIndex + visibleRows * cols);

            // Track hovered stack while we render; then render tooltip once at end (vanilla-style)
            ItemStack hovered = ItemStack.EMPTY;

            // Render centered PER ROW
            int rowY = gridTop;
            int idx = startIndex;

            for (int row = 0; row < visibleRows && idx < endIndex; row++) {
                int remaining = endIndex - idx;
                int countThisRow = Math.min(cols, remaining);

                int rowWidth = countThisRow * ITEM_SIZE + Math.max(0, countThisRow - 1) * PAD;

                // Center relative to content area; keep space for scrollbar at right
                int contentAreaW = this.width - (gridLeftMin * 2) - SCROLLBAR_W - 2;
                if (contentAreaW < ITEM_SIZE) contentAreaW = ITEM_SIZE;

                int startX = gridLeftMin + Math.max(0, (contentAreaW - rowWidth) / 2);

                int x = startX;

                for (int c = 0; c < countThisRow && idx < endIndex; c++, idx++) {
                    ItemStack s = flat.get(idx);

                    // Render
                    gg.renderItem(s, x, rowY);
                    gg.renderItemDecorations(this.font, s, x, rowY);

                    // Hover detection
                    if (!s.isEmpty()
                            && mouseX >= x && mouseX < (x + ITEM_SIZE)
                            && mouseY >= rowY && mouseY < (rowY + ITEM_SIZE)) {
                        hovered = s;
                    }

                    x += ITEM_SIZE + PAD;
                }

                rowY += ITEM_SIZE + PAD;
                if (rowY > gridBottom - ITEM_SIZE) break;
            }

            // Tooltip (vanilla look): same path as inventory hover
            if (hovered != null && !hovered.isEmpty()) {
                try {
                    gg.renderTooltip(this.font, hovered, mouseX, mouseY);
                } catch (Throwable t) {
                    // Soft fallback: at least show item name (avoid crash)
                    try {
                        gg.renderTooltip(
                                this.font,
                                java.util.List.of(hovered.getHoverName()),
                                java.util.Optional.empty(),
                                mouseX,
                                mouseY
                        );
                    } catch (Throwable ignored) {}
                    EZVillagerReroll.LOG().debug("[EZVR] BusyVillagerScreen tooltip render failed (soft): {}", t.toString());
                }
            }

            // Scrollbar
            int totalRows = computeTotalRows();
            int maxRow = Math.max(0, totalRows - visibleRows);
            if (scrollBar != null) {
                scrollBar.render(gg, scrollRow, maxRow, totalRows, visibleRows);
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] BusyVillagerScreen.render failed", t);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------
    // Minimal draggable scrollbar helper (no dependencies)
    // ------------------------------------------------------------
    private static final class SimpleScrollBar {
        private final int x, y, w, h;

        private boolean dragging = false;
        private double lastMouseY = 0;

        SimpleScrollBar(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        void render(GuiGraphics gg, int scrollRow, int maxRow, int totalRows, int visibleRows) {
            try {
                // Track
                gg.fill(x, y, x + w, y + h, 0x80000000);

                if (totalRows <= visibleRows) {
                    // Full thumb
                    gg.fill(x, y, x + w, y + h, 0xA0FFFFFF);
                    return;
                }

                int thumbH = Math.max(12, (int) Math.round((h * (visibleRows / (double) totalRows))));
                int travel = Math.max(1, h - thumbH);

                double t = (maxRow <= 0) ? 0.0 : (scrollRow / (double) maxRow);
                int thumbY = y + (int) Math.round(travel * t);

                gg.fill(x, thumbY, x + w, thumbY + thumbH, dragging ? 0xE0FFFFFF : 0xC0FFFFFF);
            } catch (Throwable ignored) {}
        }

        private boolean isOver(double mx, double my) {
            return mx >= x && mx < (x + w) && my >= y && my < (y + h);
        }

        boolean mouseClicked(double mx, double my, int button) {
            try {
                if (button != 0) return false;
                if (!isOver(mx, my)) return false;

                dragging = true;
                lastMouseY = my;
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        boolean mouseReleased(double mx, double my, int button) {
            if (button != 0) return false;
            if (!dragging) return false;
            dragging = false;
            return true;
        }

        boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
            if (!dragging || button != 0) return false;
            lastMouseY = my;
            return true;
        }

        int getScrollRowFromThumb(int currentRow, int maxRow) {
            try {
                if (!dragging) return currentRow;
                if (maxRow <= 0) return 0;

                double rel = (lastMouseY - y) / (double) h;
                if (rel < 0) rel = 0;
                if (rel > 1) rel = 1;

                int next = (int) Math.round(maxRow * rel);
                if (next < 0) next = 0;
                if (next > maxRow) next = maxRow;
                return next;
            } catch (Throwable ignored) {
                return currentRow;
            }
        }
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\client\BusyVillagerScreen.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\client\ClientNetwork.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/client/ClientNetwork.java
        package org.z2six.ezvillagerreroll.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

/**
 * CLIENT-ONLY helper for sending custom payloads to the server.
 *
 * Kept separate so common code can reflect into it without loading client classes on a dedicated server.
 */
public final class ClientNetwork {

    private ClientNetwork() {}

    public static void sendToServer(CustomPacketPayload payload) {
        try {
            if (payload == null) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getConnection() == null) {
                EZVillagerReroll.LOG().warn("[EZVR] ClientNetwork.sendToServer: no client connection; dropping {}", payload.type());
                return;
            }

            mc.getConnection().send(new ServerboundCustomPayloadPacket(payload));
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ClientNetwork.sendToServer failed for {}", payload == null ? "null" : payload.getClass().getName(), t);
        }
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\client\ClientNetwork.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\client\ClientNetworkHandlers.java ----
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

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\client\ClientNetworkHandlers.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\client\ClientRerollCooldownCache.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/client/ClientRerollCooldownCache.java
        package org.z2six.ezvillagerreroll.client;

import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.network.PacketRerollCooldownState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cooldown cache keyed by merchant containerId.
 *
 * We store:
 * - cooldown end timestamp (ms) for remaining calculations
 * - last known server-configured cooldown ticks (informational, for tooltip when not cooling down)
 *
 * Server remains authoritative; client uses this for UI responsiveness and display.
 */
public final class ClientRerollCooldownCache {

    // containerId -> cooldownEndMs (only present while cooling down)
    private static final Map<Integer, Long> COOLDOWN_END_MS = new ConcurrentHashMap<>();

    // containerId -> last known configured cooldown ticks (keep even when not cooling down)
    private static final Map<Integer, Integer> LAST_CFG_TICKS = new ConcurrentHashMap<>();

    private ClientRerollCooldownCache() {}

    public static void apply(PacketRerollCooldownState msg) {
        try {
            if (msg == null) return;

            int cid = Math.max(0, msg.containerId());
            int remTicks = Math.max(0, msg.ticksRemaining());
            int cfgTicks = Math.max(0, msg.cooldownTicksConfigured());

            // Always remember cfg ticks if provided (even if rem=0).
            // This fixes "Cooldown: ?" while active.
            if (cfgTicks > 0) {
                LAST_CFG_TICKS.put(cid, cfgTicks);
            }

            if (remTicks <= 0) {
                COOLDOWN_END_MS.remove(cid);
                EZVillagerReroll.LOG().debug("[EZVR] CooldownCache: clear end (containerId={}, cfgTicks={})", cid, cfgTicks);
                return;
            }

            long now = System.currentTimeMillis();
            long end = now + (remTicks * 50L);

            COOLDOWN_END_MS.put(cid, end);

            EZVillagerReroll.LOG().debug(
                    "[EZVR] CooldownCache: set (containerId={} ticksRemaining={} cfgTicks={} endMs={})",
                    cid, remTicks, cfgTicks, end
            );
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] CooldownCache.apply failed (soft): {}", t.toString());
        }
    }

    public static void clearContainer(int containerId) {
        try {
            COOLDOWN_END_MS.remove(containerId);
            LAST_CFG_TICKS.remove(containerId);
        } catch (Throwable ignored) {}
    }

    public static void clearAll() {
        try {
            COOLDOWN_END_MS.clear();
            LAST_CFG_TICKS.clear();
        } catch (Throwable ignored) {}
    }

    /** Returns 0 if no cooldown or expired. */
    public static int getRemainingTicks(int containerId) {
        try {
            Long end = COOLDOWN_END_MS.get(containerId);
            if (end == null) return 0;

            long now = System.currentTimeMillis();
            long diff = end - now;
            if (diff <= 0) {
                COOLDOWN_END_MS.remove(containerId);
                return 0;
            }

            // ceil(diff / 50)
            long ticks = (diff + 49L) / 50L;
            if (ticks < 0) ticks = 0;
            if (ticks > Integer.MAX_VALUE) ticks = Integer.MAX_VALUE;
            return (int) ticks;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static boolean isCoolingDown(int containerId) {
        return getRemainingTicks(containerId) > 0;
    }

    /**
     * Returns last known server-configured cooldown ticks for this container.
     * 0 means "unknown".
     */
    public static int getLastKnownTotalCooldownTicks(int containerId) {
        try {
            Integer v = LAST_CFG_TICKS.get(containerId);
            return v == null ? 0 : Math.max(0, v);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Optimistic local block: used immediately after a click so the UI disables instantly even before server reply.
     * This never grants permission; it only temporarily disables.
     */
    public static void setOptimisticCooldown(int containerId, int ticks) {
        try {
            int t = Math.max(0, ticks);
            if (t <= 0) return;

            long now = System.currentTimeMillis();
            long end = now + (t * 50L);

            Long prev = COOLDOWN_END_MS.get(containerId);
            if (prev == null || end > prev) {
                COOLDOWN_END_MS.put(containerId, end);
            }

            // Also store as last-known cfg ticks so active tooltip can show it even before server responds.
            Integer prevCfg = LAST_CFG_TICKS.get(containerId);
            if (prevCfg == null || t > prevCfg) {
                LAST_CFG_TICKS.put(containerId, t);
            }

            EZVillagerReroll.LOG().debug("[EZVR] CooldownCache: optimistic set (containerId={} ticks={})", containerId, t);
        } catch (Throwable ignored) {}
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\client\ClientRerollCooldownCache.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\client\ClientUI.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/client/ClientUI.java
        package org.z2six.ezvillagerreroll.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
import java.lang.reflect.Method;
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

    // Tooltip rendering tuning
    private static final int TIP_PAD_X = 6;
    private static final int TIP_PAD_Y = 6;
    private static final int TIP_LINE_GAP = 2;
    private static final int TIP_ICON_SIZE = 9;      // close to vanilla "small"
    private static final int TIP_ICON_GAP = 3;       // space between text and icon
    private static final int TIP_Z = 400;            // background depth; we render text above this

    // IMPORTANT: ARGB (AARRGGBB). 0xFFFFFF is transparent in ARGB contexts.
    private static final int COLOR_WHITE_OPAQUE = 0xFFFFFFFF;

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

            if (overlay != null) overlay.active = cooling;

            boolean hoverOverlay = overlay != null && overlay.active && overlay.isMouseOver(e.getMouseX(), e.getMouseY());
            boolean hoverButton = btn.isMouseOver(e.getMouseX(), e.getMouseY());

            if (hoverOverlay || hoverButton) {
                if (ClientTooltipCache.ageMs() > TOOLTIP_REFRESH_DEBOUNCE_MS) {
                    trySendTooltipQuery(screen);
                }

                PacketTooltipData snap = ClientTooltipCache.get();

                if (snap == null) {
                    List<Component> syncing = new ArrayList<>();
                    ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
                    if (cfg != null) syncing.add(Component.literal("Syncing… (cfg v" + cfg.version + ")"));
                    else syncing.add(Component.literal("Syncing…"));
                    e.getGuiGraphics().renderComponentTooltip(Minecraft.getInstance().font, syncing, e.getMouseX(), e.getMouseY());
                    return;
                }

                TooltipRenderPlan plan = buildPrettyTooltipPlan(snap, screen);
                if (plan == null || plan.lines.isEmpty()) {
                    EZVillagerReroll.LOG().warn("[EZVR] Tooltip render plan produced no lines (snap={}, screen={})",
                            snap, screen.getClass().getName());
                    List<Component> fallback = List.of(Component.literal("Tooltip error (see log)"));
                    e.getGuiGraphics().renderComponentTooltip(Minecraft.getInstance().font, fallback, e.getMouseX(), e.getMouseY());
                    return;
                }

                renderPrettyTooltip(
                        e.getGuiGraphics(),
                        Minecraft.getInstance().font,
                        plan,
                        e.getMouseX(), e.getMouseY(),
                        screen.width, screen.height
                );
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

    // ---------------------------------------------------------------------
    // Pretty tooltip (colors + emerald icons)
    // ---------------------------------------------------------------------

    private static final class TooltipIcon {
        final int lineIndex;
        final ItemStack stack;

        TooltipIcon(int lineIndex, ItemStack stack) {
            this.lineIndex = lineIndex;
            this.stack = stack;
        }
    }

    private static final class TooltipRenderPlan {
        final List<Component> lines = new ArrayList<>();
        final List<TooltipIcon> icons = new ArrayList<>();
    }

    private static TooltipRenderPlan buildPrettyTooltipPlan(PacketTooltipData d, MerchantScreen screen) {
        TooltipRenderPlan plan = new TooltipRenderPlan();

        // 1) Title (green)
        plan.lines.add(Component.translatable("ezvr.ui.reroll").withStyle(ChatFormatting.GREEN));

        int cid = resolveContainerId(screen);

        // Cooldown calculation
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

        // 2) Cooldown line: "Cooldown:" orange
        Component cooldownLine;
        if (cooling) {
            double sec = remainingTicks / 20.0;
            cooldownLine = Component.empty()
                    .append(Component.literal("Cooldown: ").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(String.format("%.1fs", sec)));
        } else {
            int cfgTicks = 0;

            if (cid >= 0) cfgTicks = ClientRerollCooldownCache.getLastKnownTotalCooldownTicks(cid);

            if (cfgTicks <= 0) {
                try {
                    ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
                    if (cfg != null) cfgTicks = Math.max(0, cfg.cooldownTicks);
                } catch (Throwable ignored) {}
            }

            if (cfgTicks > 0) {
                int secs = (int) Math.ceil(cfgTicks / 20.0);
                if (secs < 0) secs = 0;
                cooldownLine = Component.empty()
                        .append(Component.literal("Cooldown: ").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal(secs + "s"));
            } else {
                cooldownLine = Component.empty()
                        .append(Component.literal("Cooldown: ").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal("?"));
            }
        }
        plan.lines.add(cooldownLine);

        if (d == null || d.cost == null) {
            plan.lines.add(Component.literal("Syncing…"));
            return plan;
        }

        int cost = Math.max(0, d.cost.scaledCost);

        int totalOffers = safeIntField(d.cost, "totalOffers");
        int lockedOffers = safeIntField(d.cost, "lockedOffers");
        int deductedLocks = safeIntField(d.cost, "deductibleLockedOffers");
        int freeOffers = safeIntField(d.cost, "freeOffers");
        int paidOffers = safeIntField(d.cost, "paidOffers");
        int costPerOffer = safeIntField(d.cost, "costPerOffer");

        // 3) Cost line: emerald ICON instead of word
        if (cost <= 0) {
            plan.lines.add(Component.empty()
                    .append(Component.literal("Cost: ").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal("Free").withStyle(ChatFormatting.GREEN)));
        } else {
            int lineIdx = plan.lines.size();
            plan.lines.add(Component.empty()
                    .append(Component.literal("Cost: ").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(String.valueOf(cost)))
                    .append(Component.literal(" × ")));
            plan.icons.add(new TooltipIcon(lineIdx, new ItemStack(Items.EMERALD)));
        }

        // 4) Offers/Locked/Deducted
        plan.lines.add(Component.empty()
                .append(Component.literal("Offers: ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(String.valueOf(Math.max(0, totalOffers))).withStyle(ChatFormatting.WHITE))
                .append(Component.literal("   "))
                .append(Component.literal("Locked: ").withStyle(ChatFormatting.RED))
                .append(Component.literal(String.valueOf(Math.max(0, lockedOffers))).withStyle(ChatFormatting.WHITE))
                .append(Component.literal("   "))
                .append(Component.literal("Deducted: ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(String.valueOf(Math.max(0, deductedLocks))).withStyle(ChatFormatting.WHITE))
        );

        // 5) Free/Paid + xN with emerald icon appended
        int lineIdxFreePaid = plan.lines.size();
        plan.lines.add(Component.empty()
                .append(Component.literal("Free: ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(String.valueOf(Math.max(0, freeOffers))).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("   "))
                .append(Component.literal("Paid: ").withStyle(ChatFormatting.RED))
                .append(Component.literal(String.valueOf(Math.max(0, paidOffers))).withStyle(ChatFormatting.RED))
                .append(Component.literal("   "))
                .append(Component.literal("x" + Math.max(0, costPerOffer)).withStyle(ChatFormatting.WHITE))
        );
        if (paidOffers > 0 && costPerOffer > 0) {
            plan.icons.add(new TooltipIcon(lineIdxFreePaid, new ItemStack(Items.EMERALD)));
        }

        // 6) Affordable line green/red
        if (d.afford != null && cost > 0) {
            boolean can = d.afford.canAfford;
            String src = d.afford.source == null ? "none" : d.afford.source;
            Component aff = Component.literal(can ? ("Affordable (" + src + ")") : ("Not affordable (" + src + ")"))
                    .withStyle(can ? ChatFormatting.GREEN : ChatFormatting.RED);
            plan.lines.add(aff);
        }

        // 7) Villager level label orange
        if (d.villager != null && d.villager.level > 0) {
            plan.lines.add(Component.empty()
                    .append(Component.literal("Villager level: ").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(String.valueOf(d.villager.level)).withStyle(ChatFormatting.WHITE))
            );
        }

        return plan;
    }

    private static int safeIntField(Object obj, String fieldName) {
        try {
            if (obj == null || fieldName == null) return 0;
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(obj);
            if (v instanceof Number n) return n.intValue();
            return 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void renderPrettyTooltip(GuiGraphics gg, Font font, TooltipRenderPlan plan, int mouseX, int mouseY, int screenW, int screenH) {
        try {
            if (gg == null || font == null || plan == null || plan.lines.isEmpty()) return;

            int lineHeight = Math.max(9, font.lineHeight);
            int totalTextWidth = 0;

            boolean[] hasIcon = new boolean[plan.lines.size()];
            for (TooltipIcon ic : plan.icons) {
                if (ic == null) continue;
                if (ic.lineIndex >= 0 && ic.lineIndex < hasIcon.length) hasIcon[ic.lineIndex] = true;
            }

            for (int i = 0; i < plan.lines.size(); i++) {
                Component c = plan.lines.get(i);
                int w = font.width(c);
                if (hasIcon[i]) w += TIP_ICON_GAP + TIP_ICON_SIZE;
                if (w > totalTextWidth) totalTextWidth = w;
            }

            int tooltipW = TIP_PAD_X * 2 + totalTextWidth;
            int tooltipH = TIP_PAD_Y * 2 + (plan.lines.size() * lineHeight) + ((plan.lines.size() - 1) * TIP_LINE_GAP);

            int x = mouseX + 12;
            int y = mouseY - 12;

            if (x + tooltipW > screenW) x = mouseX - 12 - tooltipW;
            if (x < 4) x = 4;

            if (y + tooltipH > screenH) y = screenH - tooltipH - 6;
            if (y < 4) y = 4;

            // Background at TIP_Z
            renderTooltipBackgroundCompat(gg, x, y, tooltipW, tooltipH, TIP_Z);

            // Text/icons above background
            gg.pose().pushPose();
            gg.pose().translate(0.0D, 0.0D, (double) (TIP_Z + 5));

            int textX = x + TIP_PAD_X;
            int textY = y + TIP_PAD_Y;

            for (int i = 0; i < plan.lines.size(); i++) {
                int yy = textY + i * (lineHeight + TIP_LINE_GAP);
                Component line = plan.lines.get(i);

                // CRITICAL FIX: use opaque ARGB (0xFFFFFFFF), not 0xFFFFFF.
                gg.drawString(font, line, textX, yy, COLOR_WHITE_OPAQUE, true);

                if (hasIcon[i]) {
                    int textW = font.width(line);
                    // pull icon 1px closer to text
                    int iconX = textX + textW + Math.max(0, TIP_ICON_GAP - 3);
                    // vertically center a TIP_ICON_SIZE icon within the current line box
                    int iconY = yy + Math.max(0, (lineHeight - TIP_ICON_SIZE) / 2) - 4;

                    for (TooltipIcon ic : plan.icons) {
                        if (ic == null) continue;
                        if (ic.lineIndex != i) continue;

                        ItemStack stack = ic.stack;
                        if (stack == null || stack.isEmpty()) continue;

                        try {
                            gg.renderItem(stack, iconX, iconY);
                            gg.renderItemDecorations(font, stack, iconX, iconY);
                        } catch (Throwable t) {
                            EZVillagerReroll.LOG().debug("[EZVR] renderPrettyTooltip icon draw failed (soft): {}", t.toString());
                        }

                        iconX += TIP_ICON_SIZE + 2;
                    }
                }
            }

            gg.pose().popPose();

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] renderPrettyTooltip failed", t);
        }
    }

    private static void renderTooltipBackgroundCompat(GuiGraphics gg, int x, int y, int w, int h, int z) {
        try {
            Class<?> util = Class.forName("net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil");
            Method[] methods = util.getDeclaredMethods();

            for (Method m : methods) {
                if (!m.getName().toLowerCase().contains("rendertooltipbackground")) continue;
                m.setAccessible(true);
                Class<?>[] p = m.getParameterTypes();

                if (p.length == 6 && p[0] == GuiGraphics.class
                        && p[1] == int.class && p[2] == int.class && p[3] == int.class && p[4] == int.class && p[5] == int.class) {
                    m.invoke(null, gg, x, y, w, h, z);
                    return;
                }
                if (p.length == 5 && p[0] == GuiGraphics.class
                        && p[1] == int.class && p[2] == int.class && p[3] == int.class && p[4] == int.class) {
                    m.invoke(null, gg, x, y, w, h);
                    return;
                }
            }

            fallbackTooltipBox(gg, x, y, w, h);

        } catch (Throwable t) {
            fallbackTooltipBox(gg, x, y, w, h);
        }
    }

    private static void fallbackTooltipBox(GuiGraphics gg, int x, int y, int w, int h) {
        try {
            int bg = 0xF0100010;
            int border1 = 0x505000FF;
            int border2 = 0x5028007F;

            gg.fill(x, y, x + w, y + h, bg);

            gg.fill(x, y, x + w, y + 1, border1);
            gg.fill(x, y + h - 1, x + w, y + h, border1);
            gg.fill(x, y, x + 1, y + h, border1);
            gg.fill(x + w - 1, y, x + w, y + h, border1);

            gg.fill(x + 1, y + 1, x + w - 1, y + 2, border2);
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------------
    // Trader entity id resolver (unchanged)
    // ---------------------------------------------------------------------

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

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\client\ClientUI.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\client\SearchCatalogScreen.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/client/SearchCatalogScreen.java
        package org.z2six.ezvillagerreroll.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.network.PacketSearchCatalogData;
import org.z2six.ezvillagerreroll.network.PacketSearchCatalogQuery;
import org.z2six.ezvillagerreroll.network.PacketStartAutoSearch;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;
        import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SearchCatalogScreen extends Screen {

    private final MerchantScreen parent;
    private int villagerEntityId;

    private final List<ItemStack> catalogAll = new ArrayList<>();
    private final List<ItemStack> catalogFiltered = new ArrayList<>();

    // selection by stable-ish key (item + components patch)
    private final Set<String> selectedKeys = new HashSet<>();

    private EditBox searchBox;
    private Button btnCancel;
    private Button btnRequest;

    private boolean awaitingServerData = true;

    // Hourly cost preview (server-auth computed & sent in PacketSearchCatalogData)
    private int offerCount = -1;
    private int lockedCount = -1;
    private int effectivePaidOffers = -1;
    private int manualCost = -1;
    private int hourlyCost = -1;

    // Grid/scroll
    private int scrollRow = 0;

    private static final int ITEM_SIZE = 18;
    private static final int PAD = 4;
    private static final int SCROLLBAR_W = 8;
    private static final int GRID_LEFT = 20;
    private static final int GRID_TOP = 90;
    private static final int GRID_BOTTOM_PAD = 40;   // bottom UI area
    private static final int GRID_RIGHT_PAD = 20;    // right margin
    private static final int SCROLLBAR_GAP = 2;

    // Inline icon (emerald) rendering
    private static final int INLINE_ICON_SIZE = 16;
    private static final int INLINE_ICON_GAP = 4;
    private static final int INLINE_Z = 200; // above background; below tooltips
    private static final int COLOR_WHITE_OPAQUE = 0xFFFFFFFF;

    private SimpleScrollBar scrollBar;

    // For “name + level” parsing (Bane of Arthropods III, Impaling V, Protection 4, etc.)
    private static final Pattern TRAILING_LEVEL = Pattern.compile("^(.*?)(?:\\s+([0-9]+|[IVXLCDM]+))\\s*$", Pattern.CASE_INSENSITIVE);

    public SearchCatalogScreen(MerchantScreen parent) {
        super(Component.translatable("ezvr.catalog.title"));
        this.parent = parent;
        this.villagerEntityId = -1;
    }

    public SearchCatalogScreen(int villagerEntityId, List<ItemStack> catalog) {
        super(Component.translatable("ezvr.catalog.title"));
        this.parent = null;
        this.villagerEntityId = villagerEntityId;
        if (catalog != null) this.catalogAll.addAll(catalog);
        this.awaitingServerData = false;
        rebuildFilteredAndSorted();
    }

    public void applyCatalogFromServer(PacketSearchCatalogData msg) {
        try {
            if (msg == null) return;
            this.villagerEntityId = msg.villagerEntityId();

            this.catalogAll.clear();
            List<ItemStack> items = msg.catalog();
            if (items != null) {
                for (ItemStack s : items) {
                    if (s == null || s.isEmpty()) continue;
                    this.catalogAll.add(s.copy());
                }
            }

            this.offerCount = msg.offerCount();
            this.lockedCount = msg.lockedCount();
            this.effectivePaidOffers = msg.effectivePaidOffers();
            this.manualCost = msg.manualCost();
            this.hourlyCost = msg.hourlyCost();

            this.awaitingServerData = false;

            EZVillagerReroll.LOG().info(
                    "[EZVR] SearchCatalogScreen received catalog: villagerEntityId={} items={} offerCount={} lockedCount={} effectivePaidOffers={} manualCost={} hourlyCost={}",
                    villagerEntityId, this.catalogAll.size(), offerCount, lockedCount, effectivePaidOffers, manualCost, hourlyCost
            );

            rebuildFilteredAndSorted();
            scrollRow = 0;
            clampScroll();

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] applyCatalogFromServer(PacketSearchCatalogData) failed", t);
        }
    }

    // Backwards-compatible old signature (kept so other call-sites won't crash)
    public void applyCatalogFromServer(int villagerEntityId, List<ItemStack> items) {
        try {
            applyCatalogFromServer(PacketSearchCatalogData.minimal(villagerEntityId, items));
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] applyCatalogFromServer(int,List) wrapper failed", t);
        }
    }

    @Override
    protected void init() {
        try {
            super.init();

            int cx = this.width / 2;

            searchBox = new EditBox(this.font, cx - 110, 18, 220, 18, Component.translatable("ezvr.catalog.search"));
            searchBox.setMaxLength(64);
            searchBox.setResponder(s -> {
                try {
                    EZVillagerReroll.LOG().debug("[EZVR] SearchCatalogScreen search changed: '{}'", s);
                    rebuildFilteredAndSorted();
                    scrollRow = 0;
                    clampScroll();
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().debug("[EZVR] search responder failed (soft): {}", t.toString());
                }
            });
            addRenderableWidget(searchBox);

            btnCancel = Button.builder(Component.translatable("ezvr.catalog.cancel"), b -> closeToParentOrNull())
                    .pos(cx - 110, this.height - 28)
                    .size(100, 20)
                    .build();

            btnRequest = Button.builder(Component.translatable("ezvr.catalog.request"), b -> {
                        try {
                            if (villagerEntityId < 0) {
                                EZVillagerReroll.LOG().warn("[EZVR] Request clicked but villagerEntityId not set yet; ignoring.");
                                return;
                            }

                            List<ItemStack> req = new ArrayList<>();
                            for (ItemStack s : catalogAll) {
                                if (s == null || s.isEmpty()) continue;
                                String k = keyOf(s);
                                if (!selectedKeys.contains(k)) continue;
                                req.add(s.copy());
                            }

                            if (req.isEmpty()) {
                                EZVillagerReroll.LOG().warn("[EZVR] Request clicked with empty selection; ignoring.");
                                return;
                            }

                            ClientNetwork.sendToServer(new PacketStartAutoSearch(villagerEntityId, req));
                            EZVillagerReroll.LOG().info("[EZVR] SearchCatalogScreen: sent PacketStartAutoSearch villagerEntityId={} items={}",
                                    villagerEntityId, req.size());

                            // Request means: close this UI AND close the merchant container.
                            closeAllAndCloseContainer();

                        } catch (Throwable t) {
                            EZVillagerReroll.LOG().error("[EZVR] SearchCatalogScreen: request send failed", t);
                        }
                    })
                    .pos(cx + 10, this.height - 28)
                    .size(100, 20)
                    .build();

            addRenderableWidget(btnCancel);
            addRenderableWidget(btnRequest);

            // Create scrollbar once we know screen dims
            int gridH = Math.max(1, computeGridBottom() - GRID_TOP);
            scrollBar = new SimpleScrollBar(computeScrollbarX(), GRID_TOP, SCROLLBAR_W, gridH);

            // Redundant query (safe)
            if (awaitingServerData && villagerEntityId >= 0) {
                try {
                    ClientNetwork.sendToServer(new PacketSearchCatalogQuery(villagerEntityId));
                    EZVillagerReroll.LOG().debug("[EZVR] SearchCatalogScreen.init: sent redundant PacketSearchCatalogQuery(villagerEntityId={})", villagerEntityId);
                } catch (Throwable ignored) {}
            }

            rebuildFilteredAndSorted();
            clampScroll();

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchCatalogScreen.init failed", t);
        }
    }

    /**
     * Cancel / ESC should not set screen null while keeping the trade container open.
     * That combination can leave the client stuck “in a trade session” and unable to reopen the villager.
     *
     * So:
     * - if we have a parent MerchantScreen, restore it
     * - otherwise fall back to null
     */
    private void closeToParentOrNull() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            if (this.parent != null) {
                mc.setScreen(this.parent);
            } else {
                mc.setScreen(null);
            }
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] SearchCatalogScreen.closeToParentOrNull failed (soft): {}", t.toString());
        }
    }

    private void closeAllAndCloseContainer() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.setScreen(null);
        } catch (Throwable ignored) {}

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            if (mc.player != null) mc.player.closeContainer();
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] SearchCatalogScreen.closeAllAndCloseContainer failed (soft): {}", t.toString());
        }
    }

    @Override
    public void onClose() {
        // ESC should behave like Cancel (restore parent), not “close to null”
        closeToParentOrNull();
    }

    private int computeGridBottom() {
        try {
            return this.height - GRID_BOTTOM_PAD;
        } catch (Throwable t) {
            return this.height - 40;
        }
    }

    private int computeScrollbarX() {
        try {
            // Right edge: width - right pad - scrollbar width
            return this.width - GRID_RIGHT_PAD - SCROLLBAR_W;
        } catch (Throwable t) {
            return this.width - 20 - SCROLLBAR_W;
        }
    }

    private int computeGridRight() {
        try {
            // Grid area ends before scrollbar + gap
            int right = computeScrollbarX() - SCROLLBAR_GAP;
            return Math.max(GRID_LEFT + ITEM_SIZE, right);
        } catch (Throwable t) {
            return this.width - GRID_RIGHT_PAD - SCROLLBAR_W - SCROLLBAR_GAP;
        }
    }

    /**
     * Render and hit-testing MUST share the exact same column math.
     * We compute cols based strictly on the actual grid pixel width.
     */
    private int computeCols() {
        try {
            int gridRight = computeGridRight();
            int gridW = Math.max(1, gridRight - GRID_LEFT);
            // If we place N items: x = left + col*(ITEM_SIZE+PAD).
            // Total width occupied = N*ITEM_SIZE + (N-1)*PAD.
            // This equals N*(ITEM_SIZE+PAD) - PAD.
            // So we solve for N: N*(ITEM_SIZE+PAD) - PAD <= gridW  =>  N <= (gridW + PAD)/(ITEM_SIZE+PAD)
            int cols = (gridW + PAD) / (ITEM_SIZE + PAD);
            return Math.max(1, cols);
        } catch (Throwable t) {
            return 1;
        }
    }

    private int computeVisibleRows() {
        try {
            int gridH = Math.max(1, computeGridBottom() - GRID_TOP);
            int rows = gridH / (ITEM_SIZE + PAD);
            return Math.max(1, rows);
        } catch (Throwable t) {
            return 1;
        }
    }

    private int computeTotalRows() {
        try {
            int cols = computeCols();
            int count = catalogFiltered.size();
            return (count + cols - 1) / cols;
        } catch (Throwable t) {
            return 0;
        }
    }

    private void clampScroll() {
        try {
            int totalRows = computeTotalRows();
            int visibleRows = computeVisibleRows();
            int maxRow = Math.max(0, totalRows - visibleRows);
            if (scrollRow < 0) scrollRow = 0;
            if (scrollRow > maxRow) scrollRow = maxRow;
        } catch (Throwable ignored) {}
    }

    private void rebuildFilteredAndSorted() {
        try {
            catalogFiltered.clear();

            String q = "";
            try {
                q = (searchBox == null) ? "" : String.valueOf(searchBox.getValue());
            } catch (Throwable ignored) {}

            String query = (q == null) ? "" : q.trim().toLowerCase(Locale.ROOT);

            if (catalogAll.isEmpty()) return;

            List<Entry> tmp = new ArrayList<>(catalogAll.size());
            for (ItemStack s : catalogAll) {
                if (s == null || s.isEmpty()) continue;

                Entry e = buildEntry(s);
                if (e == null) continue;

                if (!query.isEmpty() && !matchesQuery(e.searchBlobLower, query)) continue;
                tmp.add(e);
            }

            // Sort by:
            // 1) Item name A-Z (primaryName)
            // 2) "Detail base" A-Z (e.g., "Bane of Arthropods")
            // 3) Parsed numeric level ascending (I, II, III, 4, V, X, etc.)
            // 4) Raw detail tie-breaker
            // 5) Blob tie-breaker
            tmp.sort((a, b) -> {
                int c;

                c = a.primaryName.compareToIgnoreCase(b.primaryName);
                if (c != 0) return c;

                c = a.detailBase.compareToIgnoreCase(b.detailBase);
                if (c != 0) return c;

                c = Integer.compare(a.detailLevel, b.detailLevel);
                if (c != 0) return c;

                c = a.secondaryDetail.compareToIgnoreCase(b.secondaryDetail);
                if (c != 0) return c;

                return a.searchBlobLower.compareTo(b.searchBlobLower);
            });

            for (Entry e : tmp) catalogFiltered.add(e.stack);

            EZVillagerReroll.LOG().debug("[EZVR] rebuildFilteredAndSorted: all={} filtered={} query='{}'",
                    catalogAll.size(), catalogFiltered.size(), query);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] rebuildFilteredAndSorted failed", t);
        }
    }

    private static boolean matchesQuery(String haystackLower, String queryLower) {
        try {
            if (haystackLower == null) return false;
            if (queryLower == null || queryLower.isBlank()) return true;

            String[] parts = queryLower.split("\\s+");
            for (String p : parts) {
                if (p.isBlank()) continue;
                if (!haystackLower.contains(p)) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private Entry buildEntry(ItemStack s) {
        try {
            if (s == null || s.isEmpty()) return null;

            Minecraft mc = Minecraft.getInstance();
            Player player = (mc == null) ? null : mc.player;

            String name = safeString(() -> s.getHoverName().getString());

            List<Component> lines = List.of();
            try {
                if (player != null) {
                    lines = TooltipCompat.getTooltipLines(s, player, TooltipFlag.Default.NORMAL);
                }
            } catch (Throwable ignored) {}

            // Build search blob and pick a “best detail line” for sorting.
            String bestDetailLine = "";
            StringBuilder blob = new StringBuilder(256);
            if (!name.isBlank()) blob.append(name);

            if (lines != null && !lines.isEmpty()) {
                for (int i = 0; i < lines.size(); i++) {
                    String line = "";
                    try { line = lines.get(i).getString(); } catch (Throwable ignored) {}
                    if (line == null || line.isBlank()) continue;

                    blob.append('\n').append(line);

                    // For sorting: prefer the first meaningful line AFTER the title line.
                    // For Enchanted Books, this is typically the enchant line.
                    if (i > 0 && bestDetailLine.isBlank()) {
                        bestDetailLine = line.trim();
                    }
                }
            }

            // Add components patch to blob for broader “modded details” matching.
            try {
                Object patch = s.getComponentsPatch();
                if (patch != null) blob.append("\n").append(patch.toString());
            } catch (Throwable ignored) {}

            String blobLower = blob.toString().toLowerCase(Locale.ROOT);

            // Parse detail into base + level for stable grouping (Bane of Arthropods + 3)
            ParsedDetail pd = parseDetail(bestDetailLine);

            Entry e = new Entry();
            e.stack = s;
            e.primaryName = (name == null) ? "" : name;
            e.secondaryDetail = (bestDetailLine == null) ? "" : bestDetailLine;
            e.detailBase = pd.base;
            e.detailLevel = pd.level;
            e.searchBlobLower = blobLower;
            return e;

        } catch (Throwable t) {
            return null;
        }
    }

    private static final class ParsedDetail {
        final String base;
        final int level;

        ParsedDetail(String base, int level) {
            this.base = (base == null) ? "" : base;
            this.level = Math.max(0, level);
        }
    }

    private static ParsedDetail parseDetail(String detail) {
        try {
            if (detail == null) return new ParsedDetail("", 0);
            String d = detail.trim();
            if (d.isEmpty()) return new ParsedDetail("", 0);

            Matcher m = TRAILING_LEVEL.matcher(d);
            if (!m.matches()) {
                return new ParsedDetail(d, 0);
            }

            String base = (m.group(1) == null) ? d : m.group(1).trim();
            String lvl = (m.group(2) == null) ? "" : m.group(2).trim();

            int level = 0;
            if (!lvl.isEmpty()) {
                if (isAllDigits(lvl)) {
                    try { level = Integer.parseInt(lvl); } catch (Throwable ignored) { level = 0; }
                } else {
                    level = romanToInt(lvl);
                }
            }

            return new ParsedDetail(base, level);

        } catch (Throwable t) {
            return new ParsedDetail(detail, 0);
        }
    }

    private static boolean isAllDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static int romanToInt(String roman) {
        try {
            if (roman == null) return 0;
            String r = roman.trim().toUpperCase(Locale.ROOT);
            if (r.isEmpty()) return 0;

            int sum = 0;
            int prev = 0;
            for (int i = r.length() - 1; i >= 0; i--) {
                int v = switch (r.charAt(i)) {
                    case 'I' -> 1;
                    case 'V' -> 5;
                    case 'X' -> 10;
                    case 'L' -> 50;
                    case 'C' -> 100;
                    case 'D' -> 500;
                    case 'M' -> 1000;
                    default -> 0;
                };
                if (v == 0) return 0;
                if (v < prev) sum -= v;
                else {
                    sum += v;
                    prev = v;
                }
            }
            return Math.max(0, sum);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String safeString(SupplierThrows<String> sup) {
        try {
            String s = sup.get();
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    @FunctionalInterface
    private interface SupplierThrows<T> { T get() throws Throwable; }

    private static final class Entry {
        ItemStack stack;
        String primaryName;
        String secondaryDetail;

        // new sort fields
        String detailBase;
        int detailLevel;

        String searchBlobLower;
    }

    private static String keyOf(ItemStack s) {
        try {
            if (s == null || s.isEmpty()) return "empty";
            String itemPart = String.valueOf(s.getItem());

            String compPart;
            try {
                Object patch = s.getComponentsPatch();
                compPart = (patch == null) ? "noComponents" : patch.toString();
            } catch (Throwable ignored) {
                compPart = s.toString();
            }

            return itemPart + "|" + compPart;
        } catch (Throwable t) {
            return "err|" + Objects.hashCode(s);
        }
    }

    /**
     * MC 1.21.1 ContainerEventHandler signature uses 4 params:
     * mouseScrolled(mouseX, mouseY, scrollX, scrollY)
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        try {
            if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);

            int step = (scrollY > 0) ? -1 : 1;
            scrollRow += step;
            clampScroll();

            return true;
        } catch (Throwable t) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        try {
            if (scrollBar != null && scrollBar.mouseClicked(mouseX, mouseY, button)) return true;

            if (!awaitingServerData) {
                int gridBottom = computeGridBottom();
                int gridRight = computeGridRight();

                if (mouseX >= GRID_LEFT && mouseX < gridRight && mouseY >= GRID_TOP && mouseY < gridBottom) {
                    int cols = computeCols();
                    int visibleRows = computeVisibleRows();

                    int relX = (int) mouseX - GRID_LEFT;
                    int relY = (int) mouseY - GRID_TOP;

                    int col = relX / (ITEM_SIZE + PAD);
                    int row = relY / (ITEM_SIZE + PAD);

                    if (col >= 0 && col < cols && row >= 0 && row < visibleRows) {
                        int index = (scrollRow + row) * cols + col;
                        if (index >= 0 && index < catalogFiltered.size()) {
                            ItemStack clicked = catalogFiltered.get(index);
                            String k = keyOf(clicked);

                            if (button == 0) {
                                if (selectedKeys.contains(k)) selectedKeys.remove(k);
                                else selectedKeys.add(k);

                                EZVillagerReroll.LOG().debug("[EZVR] Catalog selection toggled: key={} selectedCount={}", k, selectedKeys.size());
                                return true;
                            }
                        }
                    }
                }
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] SearchCatalogScreen.mouseClicked failed (soft): {}", t.toString());
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        try {
            if (scrollBar != null && scrollBar.mouseReleased(mouseX, mouseY, button)) return true;
        } catch (Throwable ignored) {}
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        try {
            if (scrollBar != null && scrollBar.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
                int maxRow = Math.max(0, computeTotalRows() - computeVisibleRows());
                scrollRow = scrollBar.getScrollRowFromThumb(scrollRow, maxRow);
                clampScroll();
                return true;
            }
        } catch (Throwable ignored) {}
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        try {
            // Recompute scrollbar on resize dynamically (prevents stale x/h causing drift after resizing).
            try {
                int gridH = Math.max(1, computeGridBottom() - GRID_TOP);
                if (scrollBar != null) scrollBar.setBounds(computeScrollbarX(), GRID_TOP, SCROLLBAR_W, gridH);
            } catch (Throwable ignored) {}

            this.renderBackground(gg, mouseX, mouseY, partialTick);
            super.render(gg, mouseX, mouseY, partialTick);

            gg.drawCenteredString(this.font, Component.translatable("ezvr.catalog.header"), this.width / 2, 44, 0xFFFFFF);

            if (awaitingServerData) {
                gg.drawCenteredString(this.font, Component.literal("Loading…").withStyle(ChatFormatting.GRAY), this.width / 2, 66, 0xFFFFFF);
                return;
            }

            // Line 1: counts
            gg.drawCenteredString(this.font,
                    Component.literal("Items available: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(String.valueOf(catalogFiltered.size())).withStyle(ChatFormatting.WHITE))
                            .append(Component.literal("   Selected: ").withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(String.valueOf(selectedKeys.size())).withStyle(ChatFormatting.WHITE)),
                    this.width / 2, 66, 0xFFFFFF);

            // Line 2: hourly cost preview (colored + emerald icons inline)
            renderHourlyPreviewLine(gg, 78);

            int gridBottom = computeGridBottom();
            int gridRight = computeGridRight();

            int cols = computeCols();
            int visibleRows = computeVisibleRows();

            int startIndex = scrollRow * cols;
            int endIndex = Math.min(catalogFiltered.size(), startIndex + visibleRows * cols);

            int idx = startIndex;

            for (int row = 0; row < visibleRows && idx < endIndex; row++) {
                int y = GRID_TOP + row * (ITEM_SIZE + PAD);

                for (int col = 0; col < cols && idx < endIndex; col++, idx++) {
                    int x = GRID_LEFT + col * (ITEM_SIZE + PAD);

                    // Safety: don't render outside gridRight (should be consistent with cols math, but keep it soft)
                    if (x + ITEM_SIZE > gridRight) continue;

                    ItemStack s = catalogFiltered.get(idx);
                    if (s == null) continue;

                    String k = keyOf(s);
                    boolean sel = selectedKeys.contains(k);
                    if (sel) {
                        int outline = 0xFF66FF66;
                        gg.renderOutline(x - 1, y - 1, ITEM_SIZE + 2, ITEM_SIZE + 2, outline);
                    }

                    gg.renderItem(s, x, y);
                    gg.renderItemDecorations(this.font, s, x, y);
                }

                if (y + ITEM_SIZE > gridBottom) break;
            }

            // Tooltip on hover (vanilla stack tooltip)
            if (mouseX >= GRID_LEFT && mouseX < gridRight && mouseY >= GRID_TOP && mouseY < gridBottom) {
                int relX = (int) mouseX - GRID_LEFT;
                int relY = (int) mouseY - GRID_TOP;

                int col = relX / (ITEM_SIZE + PAD);
                int row = relY / (ITEM_SIZE + PAD);

                if (col >= 0 && col < cols && row >= 0 && row < visibleRows) {
                    int hoverIndex = (scrollRow + row) * cols + col;
                    if (hoverIndex >= 0 && hoverIndex < catalogFiltered.size()) {
                        ItemStack hover = catalogFiltered.get(hoverIndex);
                        if (hover != null && !hover.isEmpty()) {
                            ezvr$renderVanillaItemTooltip(gg, hover, mouseX, mouseY);
                        }
                    }
                }
            }

            int totalRows = computeTotalRows();
            int maxRow = Math.max(0, totalRows - visibleRows);
            if (scrollBar != null) {
                scrollBar.render(gg, scrollRow, maxRow, totalRows, visibleRows);
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchCatalogScreen.render failed", t);
        }
    }

    private void renderHourlyPreviewLine(GuiGraphics gg, int y) {
        try {
            if (gg == null || this.font == null) return;
            if (hourlyCost < 0) return;

            InlineLinePlan plan = buildHourlyInlinePlan();
            if (plan == null || plan.parts.isEmpty()) return;

            int totalW = 0;
            for (InlinePart p : plan.parts) {
                if (p == null) continue;
                int w = this.font.width(p.text == null ? Component.empty() : p.text);
                if (p.hasEmeraldAfter) w += INLINE_ICON_GAP + INLINE_ICON_SIZE;
                totalW += w;
            }

            int startX = (this.width / 2) - (totalW / 2);

            gg.pose().pushPose();
            gg.pose().translate(0.0D, 0.0D, (double) INLINE_Z);

            int x = startX;
            for (InlinePart p : plan.parts) {
                if (p == null) continue;

                Component c = p.text == null ? Component.empty() : p.text;
                gg.drawString(this.font, c, x, y, COLOR_WHITE_OPAQUE, true);

                int w = this.font.width(c);
                x += w;

                if (p.hasEmeraldAfter) {
                    int iconX = x + INLINE_ICON_GAP;
                    int iconY = y + Math.max(0, (this.font.lineHeight - INLINE_ICON_SIZE) / 2) - 3;

                    try {
                        ItemStack em = new ItemStack(Items.EMERALD);
                        gg.renderItem(em, iconX, iconY);
                        gg.renderItemDecorations(this.font, em, iconX, iconY);
                    } catch (Throwable t) {
                        EZVillagerReroll.LOG().debug("[EZVR] renderHourlyPreviewLine emerald draw failed (soft): {}", t.toString());
                    }

                    x = iconX + INLINE_ICON_SIZE;
                }
            }

            gg.pose().popPose();

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] renderHourlyPreviewLine failed (soft): {}", t.toString());
        }
    }

    private InlineLinePlan buildHourlyInlinePlan() {
        try {
            InlineLinePlan plan = new InlineLinePlan();

            // "Hourly cost: <n> [emerald]"
            plan.parts.add(new InlinePart(
                    Component.literal("Hourly cost: ").withStyle(ChatFormatting.GOLD)
                            .append(Component.literal(String.valueOf(Math.max(0, hourlyCost))).withStyle(ChatFormatting.WHITE)),
                    true
            ));

            // spacing
            plan.parts.add(new InlinePart(Component.literal("   "), false));

            // "Manual: <n> [emerald]"
            if (manualCost >= 0) {
                plan.parts.add(new InlinePart(
                        Component.literal("Manual: ").withStyle(ChatFormatting.AQUA)
                                .append(Component.literal(String.valueOf(Math.max(0, manualCost))).withStyle(ChatFormatting.WHITE)),
                        true
                ));
                plan.parts.add(new InlinePart(Component.literal("   "), false));
            }

            // "Paid offers: <n>"
            if (effectivePaidOffers >= 0) {
                plan.parts.add(new InlinePart(
                        Component.literal("Paid offers: ").withStyle(ChatFormatting.RED)
                                .append(Component.literal(String.valueOf(Math.max(0, effectivePaidOffers))).withStyle(ChatFormatting.WHITE)),
                        false
                ));
                plan.parts.add(new InlinePart(Component.literal("   "), false));
            }

            // "Locked: a/b"
            if (lockedCount >= 0 || offerCount >= 0) {
                String a = (lockedCount >= 0) ? String.valueOf(Math.max(0, lockedCount)) : "?";
                String b = (offerCount >= 0) ? String.valueOf(Math.max(0, offerCount)) : "?";

                plan.parts.add(new InlinePart(
                        Component.literal("Locked: ").withStyle(ChatFormatting.DARK_RED)
                                .append(Component.literal(a).withStyle(ChatFormatting.WHITE))
                                .append(Component.literal("/").withStyle(ChatFormatting.GRAY))
                                .append(Component.literal(b).withStyle(ChatFormatting.WHITE)),
                        false
                ));
            }

            // Trim trailing spacing parts (avoid centering drift if last is spaces)
            while (!plan.parts.isEmpty()) {
                InlinePart last = plan.parts.get(plan.parts.size() - 1);
                if (last != null && last.text != null && "   ".equals(last.text.getString())) {
                    plan.parts.remove(plan.parts.size() - 1);
                } else break;
            }

            return plan;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] buildHourlyInlinePlan failed (soft): {}", t.toString());
            return null;
        }
    }

    private static final class InlineLinePlan {
        final List<InlinePart> parts = new ArrayList<>();
    }

    private static final class InlinePart {
        final Component text;
        final boolean hasEmeraldAfter;

        InlinePart(Component text, boolean hasEmeraldAfter) {
            this.text = text;
            this.hasEmeraldAfter = hasEmeraldAfter;
        }
    }

    private void ezvr$renderVanillaItemTooltip(GuiGraphics gg, ItemStack stack, double mouseX, double mouseY) {
        try {
            if (gg == null || this.font == null) return;
            if (stack == null || stack.isEmpty()) return;

            gg.renderTooltip(this.font, stack, (int) mouseX, (int) mouseY);
        } catch (Throwable t) {
            try {
                gg.renderTooltip(
                        this.font,
                        java.util.List.of(stack.getHoverName()),
                        java.util.Optional.empty(),
                        (int) mouseX,
                        (int) mouseY
                );
            } catch (Throwable ignored) {}

            EZVillagerReroll.LOG().debug("[EZVR] ezvr$renderVanillaItemTooltip failed (soft): {}", t.toString());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------
    // Tooltip compat (handles 1.21.1 signature differences safely)
    // ------------------------------------------------------------
    private static final class TooltipCompat {
        private static Method mGetTooltipLines_New; // (TooltipContext, Player, TooltipFlag)
        private static Method mGetTooltipLines_Old; // (Player, TooltipFlag)
        private static boolean lookedUp = false;

        static List<Component> getTooltipLines(ItemStack stack, Player player, TooltipFlag flag) {
            try {
                if (!lookedUp) lookupMethods();
                if (stack == null) return List.of();
                if (player == null) return List.of(stack.getHoverName());

                if (mGetTooltipLines_New != null) {
                    Object ctx = createTooltipContext(player);
                    if (ctx != null) {
                        @SuppressWarnings("unchecked")
                        List<Component> res = (List<Component>) mGetTooltipLines_New.invoke(stack, ctx, player, flag);
                        return res == null ? List.of(stack.getHoverName()) : res;
                    }
                }

                if (mGetTooltipLines_Old != null) {
                    @SuppressWarnings("unchecked")
                    List<Component> res = (List<Component>) mGetTooltipLines_Old.invoke(stack, player, flag);
                    return res == null ? List.of(stack.getHoverName()) : res;
                }

                return List.of(stack.getHoverName());

            } catch (Throwable t) {
                return List.of(stack == null ? Component.literal("") : stack.getHoverName());
            }
        }

        private static void lookupMethods() {
            lookedUp = true;
            try {
                Class<?> tooltipContextClz = Class.forName("net.minecraft.world.item.TooltipContext");
                try {
                    mGetTooltipLines_New = ItemStack.class.getMethod("getTooltipLines", tooltipContextClz, Player.class, TooltipFlag.class);
                    EZVillagerReroll.LOG().debug("[EZVR] TooltipCompat: found ItemStack.getTooltipLines(TooltipContext, Player, TooltipFlag)");
                } catch (Throwable ignored) {
                    mGetTooltipLines_New = null;
                }
            } catch (Throwable ignored) {
                mGetTooltipLines_New = null;
            }

            try {
                mGetTooltipLines_Old = ItemStack.class.getMethod("getTooltipLines", Player.class, TooltipFlag.class);
                EZVillagerReroll.LOG().debug("[EZVR] TooltipCompat: found ItemStack.getTooltipLines(Player, TooltipFlag)");
            } catch (Throwable ignored) {
                mGetTooltipLines_Old = null;
            }
        }

        private static Object createTooltipContext(Player player) {
            try {
                Class<?> tooltipContextClz = Class.forName("net.minecraft.world.item.TooltipContext");
                Object level = player.level();

                try {
                    Method of = tooltipContextClz.getMethod("of", level.getClass().getInterfaces().length > 0 ? level.getClass().getInterfaces()[0] : level.getClass());
                    return of.invoke(null, level);
                } catch (Throwable ignored) {}

                try {
                    Class<?> levelClz = Class.forName("net.minecraft.world.level.Level");
                    Method of = tooltipContextClz.getMethod("of", levelClz);
                    return of.invoke(null, level);
                } catch (Throwable ignored) {}

                try {
                    Class<?> levelClz = Class.forName("net.minecraft.world.level.Level");
                    Constructor<?> c = tooltipContextClz.getConstructor(levelClz);
                    return c.newInstance(level);
                } catch (Throwable ignored) {}

                try {
                    Class<?> levelClz = Class.forName("net.minecraft.world.level.Level");
                    Constructor<?> c = tooltipContextClz.getConstructor(levelClz, boolean.class);
                    return c.newInstance(level, false);
                } catch (Throwable ignored) {}

                return null;

            } catch (Throwable t) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------
    // Minimal draggable scrollbar helper
    // ------------------------------------------------------------
    private static final class SimpleScrollBar {
        private int x, y, w, h;

        private boolean dragging = false;
        private double lastMouseY = 0;

        SimpleScrollBar(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        void setBounds(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            if (this.h < 1) this.h = 1;
            if (this.w < 1) this.w = 1;
        }

        void render(GuiGraphics gg, int scrollRow, int maxRow, int totalRows, int visibleRows) {
            try {
                gg.fill(x, y, x + w, y + h, 0x80000000);

                if (totalRows <= visibleRows) {
                    gg.fill(x, y, x + w, y + h, 0xA0FFFFFF);
                    return;
                }

                int thumbH = Math.max(12, (int) Math.round((h * (visibleRows / (double) totalRows))));
                int travel = Math.max(1, h - thumbH);

                double t = (maxRow <= 0) ? 0.0 : (scrollRow / (double) maxRow);
                int thumbY = y + (int) Math.round(travel * t);

                gg.fill(x, thumbY, x + w, thumbY + thumbH, dragging ? 0xE0FFFFFF : 0xC0FFFFFF);
            } catch (Throwable ignored) {}
        }

        private boolean isOver(double mx, double my) {
            return mx >= x && mx < (x + w) && my >= y && my < (y + h);
        }

        boolean mouseClicked(double mx, double my, int button) {
            try {
                if (button != 0) return false;
                if (!isOver(mx, my)) return false;
                dragging = true;
                lastMouseY = my;
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        boolean mouseReleased(double mx, double my, int button) {
            if (button != 0) return false;
            if (!dragging) return false;
            dragging = false;
            return true;
        }

        boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
            if (!dragging || button != 0) return false;
            lastMouseY = my;
            return true;
        }

        int getScrollRowFromThumb(int currentRow, int maxRow) {
            try {
                if (!dragging) return currentRow;
                if (maxRow <= 0) return 0;

                double rel = (lastMouseY - y) / (double) h;
                if (rel < 0) rel = 0;
                if (rel > 1) rel = 1;

                int next = (int) Math.round(maxRow * rel);
                if (next < 0) next = 0;
                if (next > maxRow) next = maxRow;
                return next;
            } catch (Throwable ignored) {
                return currentRow;
            }
        }
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\client\SearchCatalogScreen.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\config\ClientConfig.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/config/ClientConfig.java
        package org.z2six.ezvillagerreroll.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

public final class ClientConfig {

    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue BUTTON_OFFSET_X =
            B.comment("""
                    Client-only UI: X offset applied to the reroll button relative to the base position.
                    Base position is the vanilla MerchantScreen anchor used by EZVR.
                    """).defineInRange("ui.buttonOffsetX", 0, -5000, 5000);

    public static final ModConfigSpec.IntValue BUTTON_OFFSET_Y =
            B.comment("""
                    Client-only UI: Y offset applied to the reroll button relative to the base position.
                    Base position is the vanilla MerchantScreen anchor used by EZVR.
                    """).defineInRange("ui.buttonOffsetY", 0, -5000, 5000);

    public static final ModConfigSpec SPEC = B.build();

    public static int buttonOffsetX = 0;
    public static int buttonOffsetY = 0;

    public static void bake() {
        try {
            buttonOffsetX = BUTTON_OFFSET_X.get();
            buttonOffsetY = BUTTON_OFFSET_Y.get();
            EZVillagerReroll.LOG().info("[EZVR] ClientConfig baked: offsetX={}, offsetY={}", buttonOffsetX, buttonOffsetY);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ClientConfig bake error", t);
        }
    }

    private ClientConfig() {}
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\config\ClientConfig.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\config\ServerConfig.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/config/ServerConfig.java
        package org.z2six.ezvillagerreroll.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.util.List;
import java.util.Objects;

public final class ServerConfig {

    // ---------------------------------------------------------------------
    // Spec builder
    // ---------------------------------------------------------------------

    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    // ---------------------------------------------------------------------
    // COST
    // ---------------------------------------------------------------------

    public static final ModConfigSpec.ConfigValue<String> COST_ITEM_OR_TAG;
    public static final ModConfigSpec.BooleanValue PREFER_WALLET;
    public static final ModConfigSpec.BooleanValue AUTO_DEFAULT_LC_IF_PRESENT;

    /**
     * NEW cost model:
     * - First FREE_OFFERS are free.
     * - Then each paid offer costs COST_PER_OFFER.
     * - Trade locks can reduce offer count for cost computation, but only up to MAX_DEDUCTIBLE_LOCKED_OFFERS.
     */
    public static final ModConfigSpec.IntValue FREE_OFFERS;
    public static final ModConfigSpec.IntValue COST_PER_OFFER;
    public static final ModConfigSpec.IntValue MAX_DEDUCTIBLE_LOCKED_OFFERS;

    /**
     * Auto-search hourly preview/settlement scaling:
     * We compute manual-cost-equivalent per reroll, then extrapolate to 1000 ticks (1 in-game hour).
     * We then apply a scaling factor based on "effectivePaidOffers" vs threshold.
     */
    public static final ModConfigSpec.IntValue AUTO_HOURLY_THRESHOLD;
    public static final ModConfigSpec.DoubleValue AUTO_HOURLY_DISCOUNT_OR_INCREASE_PCT;

    /**
     * LEGACY (kept so old configs / code references do not explode).
     * Not used by our current reroll cost logic anymore.
     */
    public static final ModConfigSpec.ConfigValue<List<? extends Number>> LEVEL_COSTS;

    // ---------------------------------------------------------------------
    // LIMITS
    // ---------------------------------------------------------------------

    public static final ModConfigSpec.IntValue COOLDOWN_TICKS;
    public static final ModConfigSpec.IntValue PER_VILLAGER_DAILY;

    // ---------------------------------------------------------------------
    // BEHAVIOR
    // ---------------------------------------------------------------------

    public static final ModConfigSpec.BooleanValue ALLOW_AFTER_TRADE_USED;

    // ---------------------------------------------------------------------
    // SPEC DEFINITION (push/pop – REQUIRED)
    // ---------------------------------------------------------------------

    static {
        B.push("cost");

        COST_ITEM_OR_TAG =
                B.comment("""
                        Item or tag to consume per reroll:
                          - "minecraft:emerald"
                          - "lightmanscurrency:coin_emerald"
                          - "#minecraft:logs"
                        """)
                        .define("itemOrTag", "minecraft:emerald");

        PREFER_WALLET =
                B.comment("Prefer Lightman's Currency wallet when available.")
                        .define("preferWallet", true);

        AUTO_DEFAULT_LC_IF_PRESENT =
                B.comment("Auto-switch emerald to LC coin if LC is installed.")
                        .define("autoPreferLCIfPresent", true);

        FREE_OFFERS =
                B.comment("""
                        Number of trade offers that are free (no cost).
                        Example:
                          - freeOffers=2 means a villager with 2 offers costs 0.
                          - A villager with 4 offers would pay for 2 offers (before lock deductions).
                        """)
                        .defineInRange("freeOffers", 2, 0, 64);

        COST_PER_OFFER =
                B.comment("""
                        Cost per paid offer (after freeOffers and lock deduction logic).
                        Total cost = paidOffers * costPerOffer
                        """)
                        .defineInRange("costPerOffer", 8, 0, 640);

        MAX_DEDUCTIBLE_LOCKED_OFFERS =
                B.comment("""
                        Maximum number of locked offers that can reduce the cost computation.
                        Example:
                          - maxDeductibleLockedOffers=1 means locking 10 offers only deducts 1 from the cost calculation.
                        """)
                        .defineInRange("maxDeductibleLockedOffers", 1, 0, 63);

        AUTO_HOURLY_THRESHOLD =
                B.comment("""
                        Auto-search hourly price scaling threshold based on "effective paid offers":
                          effectivePaidOffers = max(0, offers - freeOffers - min(lockedOffers, maxDeductibleLockedOffers))
                        
                        If effectivePaidOffers is BELOW this threshold, auto-search becomes more expensive.
                        If effectivePaidOffers is ABOVE this threshold, auto-search becomes cheaper.
                        
                        The per-step percent is configured via autoHourlyDiscountOrIncreasePct.
                        """)
                        .defineInRange("autoHourlyThreshold", 6, 0, 64);

        AUTO_HOURLY_DISCOUNT_OR_INCREASE_PCT =
                B.comment("""
                        Percent per step (difference between effectivePaidOffers and autoHourlyThreshold).
                        
                        Examples (threshold=6, pct=5):
                          - effectivePaidOffers=2 => below by 4 steps => +20%
                          - effectivePaidOffers=8 => above by 2 steps => -10%
                        
                        This affects the HOURLY cost preview and later settlement.
                        """)
                        .defineInRange("autoHourlyDiscountOrIncreasePct", 5.0, 0.0, 100.0);

        LEVEL_COSTS =
                B.comment("""
                        LEGACY (not used by current cost model).
                        Cost per villager level (1–5).
                        Example: [0, 16, 52, 64, 96]
                        """)
                        .defineListAllowEmpty(
                                "levelCosts",
                                () -> List.of(0, 16, 52, 64, 96),
                                o -> o instanceof Number n
                                        && n.longValue() >= 0
                                        && n.longValue() <= 640
                        );

        B.pop();

        B.push("limits");

        COOLDOWN_TICKS =
                B.comment("Cooldown in ticks per villager (0 = disabled)")
                        .defineInRange("cooldownTicks", 200, 0, 20_000);

        PER_VILLAGER_DAILY =
                B.comment("Max rerolls per villager per day (0 = disabled)")
                        .defineInRange("perVillagerDaily", 0, 0, 100);

        B.pop();

        B.push("behavior");

        ALLOW_AFTER_TRADE_USED =
                B.comment("Allow reroll after villager has XP / trades used.")
                        .define("allowAfterTradeUsed", true);

        B.pop();
    }

    public static final ModConfigSpec SPEC = B.build();

    // ---------------------------------------------------------------------
    // Runtime values
    // ---------------------------------------------------------------------

    public static String costSpec = "minecraft:emerald";
    public static boolean preferWallet = true;
    public static boolean autoPreferLCIfPresent = true;

    public static int freeOffers = 2;
    public static int costPerOffer = 8;
    public static int maxDeductibleLockedOffers = 1;

    public static int autoHourlyThreshold = 6;
    public static double autoHourlyDiscountOrIncreasePct = 5.0;

    public static int cooldownTicks = 20;
    public static int perVillagerDaily = 0;
    public static boolean allowAfterTradeUsed = true;

    private static int[] levelCosts = new int[]{0, 16, 52, 64, 96};

    private static volatile int cfgVersion = 1;
    private static volatile int cfgHash = 0;

    private ServerConfig() {}

    // ---------------------------------------------------------------------
    // Load / reload hooks
    // ---------------------------------------------------------------------

    public static void onConfigLoading(ModConfigEvent.Loading e) {
        if (e.getConfig().getSpec() == SPEC) reload("loading");
    }

    public static void onConfigReloading(ModConfigEvent.Reloading e) {
        if (e.getConfig().getSpec() == SPEC) reload("reloading");
    }

    private static void reload(String reason) {
        try {
            costSpec = COST_ITEM_OR_TAG.get();
            preferWallet = PREFER_WALLET.get();
            autoPreferLCIfPresent = AUTO_DEFAULT_LC_IF_PRESENT.get();

            freeOffers = Math.max(0, FREE_OFFERS.get());
            costPerOffer = Math.max(0, COST_PER_OFFER.get());
            maxDeductibleLockedOffers = Math.max(0, MAX_DEDUCTIBLE_LOCKED_OFFERS.get());

            autoHourlyThreshold = Math.max(0, AUTO_HOURLY_THRESHOLD.get());
            autoHourlyDiscountOrIncreasePct = Math.max(0.0, AUTO_HOURLY_DISCOUNT_OR_INCREASE_PCT.get());

            cooldownTicks = COOLDOWN_TICKS.get();
            perVillagerDaily = PER_VILLAGER_DAILY.get();
            allowAfterTradeUsed = ALLOW_AFTER_TRADE_USED.get();

            // legacy
            levelCosts = parseLevelCosts(LEVEL_COSTS.get());

            if (autoPreferLCIfPresent
                    && ModList.get().isLoaded("lightmanscurrency")
                    && "minecraft:emerald".equals(costSpec)) {
                costSpec = "lightmanscurrency:coin_emerald";
                EZVillagerReroll.LOG().info("[EZVR] Auto-switched cost to LC emerald coin");
            }

            cfgVersion++;
            cfgHash = computeHash();

            EZVillagerReroll.LOG().info(
                    "[EZVR] ServerConfig {} OK | v={} hash={} costSpec='{}' preferWallet={} freeOffers={} costPerOffer={} maxDeductibleLockedOffers={} autoHourlyThreshold={} autoHourlyDiscountOrIncreasePct={} cooldownTicks={} perVillagerDaily={} allowAfterTradeUsed={} legacyLevelCosts={}",
                    reason, cfgVersion, cfgHash,
                    costSpec, preferWallet,
                    freeOffers, costPerOffer, maxDeductibleLockedOffers,
                    autoHourlyThreshold, autoHourlyDiscountOrIncreasePct,
                    cooldownTicks, perVillagerDaily, allowAfterTradeUsed,
                    debug(levelCosts)
            );

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ServerConfig reload failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // REQUIRED API (legacy)
    // ---------------------------------------------------------------------

    /** @return defensive copy, length = 5 */
    public static int[] costsByLevel5() {
        int[] out = new int[5];
        System.arraycopy(levelCosts, 0, out, 0, Math.min(levelCosts.length, 5));
        return out;
    }

    public static int costForVillagerLevel(int level) {
        int l = Math.max(1, Math.min(5, level));
        return levelCosts[l - 1];
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static int[] parseLevelCosts(List<? extends Number> raw) {
        int[] out = new int[]{0, 16, 52, 64, 96};
        if (raw == null) return out;

        for (int i = 0; i < 5 && i < raw.size(); i++) {
            long v = raw.get(i).longValue();
            if (v < 0) v = 0;
            if (v > Integer.MAX_VALUE) v = Integer.MAX_VALUE;
            out[i] = (int) v;
        }
        return out;
    }

    private static int computeHash() {
        int h = 1;
        h = 31 * h + Objects.hashCode(costSpec);
        h = 31 * h + (preferWallet ? 1 : 0);
        h = 31 * h + (autoPreferLCIfPresent ? 1 : 0);

        h = 31 * h + freeOffers;
        h = 31 * h + costPerOffer;
        h = 31 * h + maxDeductibleLockedOffers;

        h = 31 * h + autoHourlyThreshold;
        long pctBits = Double.doubleToLongBits(autoHourlyDiscountOrIncreasePct);
        h = 31 * h + (int) (pctBits ^ (pctBits >>> 32));

        h = 31 * h + cooldownTicks;
        h = 31 * h + perVillagerDaily;
        h = 31 * h + (allowAfterTradeUsed ? 1 : 0);

        for (int v : levelCosts) h = 31 * h + v;
        return h;
    }

    private static String debug(int[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        return sb.append(']').toString();
    }

    // ---------------------------------------------------------------------
    // Misc
    // ---------------------------------------------------------------------

    public static boolean isTagSpec(String s) {
        return s != null && s.startsWith("#");
    }

    public static TagKey<Item> asItemTag(String s) {
        if (!isTagSpec(s)) return null;
        ResourceLocation id = ResourceLocation.tryParse(s.substring(1));
        return id == null ? null : TagKey.create(BuiltInRegistries.ITEM.key(), id);
    }

    public static int cfgVersion() { return cfgVersion; }
    public static int cfgHash() { return cfgHash; }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\config\ServerConfig.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\logic\CostUtil.java ----
// MainFile: src/main/java/org/z2six/ezvillagerreroll/logic/CostUtil.java
        package org.z2six.ezvillagerreroll.logic;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

public final class CostUtil {

    public static Ingredient parseIngredient(String spec) {
        try {
            if (spec == null || spec.isBlank()) return Ingredient.EMPTY;
            spec = spec.trim();
            if (spec.startsWith("#")) {
                ResourceLocation id = ResourceLocation.tryParse(spec.substring(1));
                if (id == null) return Ingredient.EMPTY;
                TagKey<Item> tag = TagKey.create(BuiltInRegistries.ITEM.key(), id);
                return Ingredient.of(tag);
            } else {
                ResourceLocation id = ResourceLocation.tryParse(spec);
                if (id == null) return Ingredient.EMPTY;
                Item item = BuiltInRegistries.ITEM.get(id);
                if (item == null) return Ingredient.EMPTY;
                return Ingredient.of(item);
            }
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] parseIngredient failed for '{}'", spec, t);
            return Ingredient.EMPTY;
        }
    }

    public static boolean consume(net.minecraft.server.level.ServerPlayer sp, Ingredient ing, int count) {
        try {
            if (count <= 0) return true;
            Inventory inv = sp.getInventory();
            int remaining = count;

            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s.isEmpty()) continue;
                if (!ing.test(s)) continue;

                int take = Math.min(remaining, s.getCount());
                s.shrink(take);
                remaining -= take;

                if (remaining <= 0) break;
            }
            if (remaining > 0) {
                EZVillagerReroll.LOG().info("[EZVR] Not enough items: need {}, short by {}", count, remaining);
                return false;
            }
            return true;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] consume() exception", t);
            return false;
        }
    }

    private CostUtil() {}
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\logic\CostUtil.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\logic\MoneyBridge.java ----
// MainFile: src/main/java/org/z2six/ezvillagerreroll/logic/MoneyBridge.java
        package org.z2six.ezvillagerreroll.logic;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class MoneyBridge {

    private static volatile boolean lookedUp = false;
    private static volatile boolean lcPresent = false;

    private static Class<?> clsMoneyAPI;
    private static Class<?> clsIMoneyHandler;
    private static Class<?> clsMoneyValue;
    private static Class<?> clsCoinValue;

    private static Method mMoneyAPI_getPlayersMoneyHandler;
    private static Method mIMoneyHandler_extractMoney;
    private static Method mMoneyValue_isEmpty;
    private static Method mCoinValue_fromItem_long;
    private static Method mCoinValue_fromItem_int;
    private static Method mCoinValue_fromStack_long;
    private static Method mCoinValue_fromStack_int;

    private static Object moneyApiTarget;
    private static Object moneyApiCompanion;

    public static boolean isLCPresent() {
        if (!lookedUp) {
            lcPresent = ModList.get().isLoaded("lightmanscurrency");
            lookedUp = true;
            if (lcPresent) {
                EZVillagerReroll.LOG().info("[EZVR] LC present: binding MoneyAPI (factory path)...");
                bind();
            } else {
                EZVillagerReroll.LOG().info("[EZVR] LC not present: MoneyAPI disabled.");
            }
        }
        if (lcPresent && !areBound()) {
            EZVillagerReroll.LOG().info("[EZVR] MoneyAPI not fully bound; retrying bind...");
            bind();
            if (!areBound()) EZVillagerReroll.LOG().info("[EZVR] MoneyAPI still not fully bound after retry.");
        }
        return lcPresent;
    }

    public static boolean canAfford(ServerPlayer player, ResourceLocation coinId, int count) {
        try {
            if (!isLCPresent() || !areBound()) return false;
            if (coinId == null || count <= 0) return true;
            Item coinItem = BuiltInRegistries.ITEM.get(coinId);
            if (coinItem == null) return false;

            Object moneyValue = buildMoneyValue(coinItem, count);
            if (moneyValue == null) return false;
            Object handler = invokeHandlerGetter(player);
            if (handler == null) return false;

            Object remainderSim = mIMoneyHandler_extractMoney.invoke(handler, moneyValue, Boolean.TRUE);
            return isMoneyEmpty(remainderSim);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().info("[EZVR] MoneyAPI: canAfford simulate failed: {}", t.toString());
            return false;
        }
    }

    public static boolean tryExtract(ServerPlayer player, ResourceLocation coinId, int count) {
        try {
            if (!isLCPresent() || !areBound()) {
                EZVillagerReroll.LOG().info("[EZVR] MoneyAPI not bound; falling back.");
                return false;
            }
            if (coinId == null || count <= 0) return true;

            Item coinItem = BuiltInRegistries.ITEM.get(coinId);
            if (coinItem == null) {
                EZVillagerReroll.LOG().info("[EZVR] MoneyAPI: item '{}' not found.", coinId);
                return false;
            }

            Object moneyValue = buildMoneyValue(coinItem, count);
            if (moneyValue == null) {
                EZVillagerReroll.LOG().info("[EZVR] MoneyAPI: failed to build MoneyValue for {} x {}", count, coinId);
                return false;
            }

            Object handler = invokeHandlerGetter(player);
            EZVillagerReroll.LOG().debug("[EZVR] MoneyAPI: handler -> {}", handler == null ? "null" : handler.getClass().getName());
            if (handler == null) return false;

            Object remainderSim = mIMoneyHandler_extractMoney.invoke(handler, moneyValue, Boolean.TRUE);
            boolean simOK = isMoneyEmpty(remainderSim);
            EZVillagerReroll.LOG().debug("[EZVR] MoneyAPI: simulate isEmpty -> {}", simOK);
            if (!simOK) return false;

            Object remainderReal = mIMoneyHandler_extractMoney.invoke(handler, moneyValue, Boolean.FALSE);
            boolean realOK = isMoneyEmpty(remainderReal);
            EZVillagerReroll.LOG().debug("[EZVR] MoneyAPI: real isEmpty -> {}", realOK);
            return realOK;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().warn("[EZVR] MoneyAPI extract failed (soft): {}", t.toString());
            return false;
        }
    }

    private static void bind() {
        try {
            clsMoneyAPI = Class.forName("io.github.lightman314.lightmanscurrency.api.money.MoneyAPI");
            clsIMoneyHandler = Class.forName("io.github.lightman314.lightmanscurrency.api.capability.money.IMoneyHandler");
            clsMoneyValue = Class.forName("io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue");
            clsCoinValue = Class.forName("io.github.lightman314.lightmanscurrency.api.money.value.builtin.CoinValue");

            mMoneyAPI_getPlayersMoneyHandler = tryGetHandlerGetter(clsMoneyAPI);

            if (mMoneyAPI_getPlayersMoneyHandler != null && !Modifier.isStatic(mMoneyAPI_getPlayersMoneyHandler.getModifiers())) {
                try {
                    Field f = clsMoneyAPI.getDeclaredField("API");
                    f.setAccessible(true);
                    if (Modifier.isStatic(f.getModifiers())) {
                        Object v = f.get(null);
                        if (v != null) {
                            moneyApiTarget = v;
                            EZVillagerReroll.LOG().info("[EZVR] MoneyAPI bind: using MoneyAPI.API singleton -> {}", v.getClass().getName());
                        }
                    }
                } catch (NoSuchFieldException ignored) {}
                if (moneyApiTarget == null) {
                    resolveMoneyApiInstances(clsMoneyAPI);
                }
            } else if (mMoneyAPI_getPlayersMoneyHandler != null) {
                EZVillagerReroll.LOG().info("[EZVR] MoneyAPI bind: handler getter is STATIC");
            }

            mIMoneyHandler_extractMoney = clsIMoneyHandler.getMethod("extractMoney", clsMoneyValue, boolean.class);
            mIMoneyHandler_extractMoney.setAccessible(true);
            EZVillagerReroll.LOG().info("[EZVR] MoneyAPI bind: IMoneyHandler#extractMoney(...) OK");

            mMoneyValue_isEmpty = clsMoneyValue.getMethod("isEmpty");
            mMoneyValue_isEmpty.setAccessible(true);
            EZVillagerReroll.LOG().info("[EZVR] MoneyAPI bind: MoneyValue#isEmpty() OK");

            bindCoinValueFactories();

            EZVillagerReroll.LOG().info(
                    "[EZVR] MoneyAPI bound check: getter={}, static={}, hasAPIorInstance={}, hasCompanion={}, extractMoney={}, factories(item,long|int; stack,long|int)={}|{};{}|{}, isEmpty={}",
                    (mMoneyAPI_getPlayersMoneyHandler != null),
                    (mMoneyAPI_getPlayersMoneyHandler != null && Modifier.isStatic(mMoneyAPI_getPlayersMoneyHandler.getModifiers())),
                    (moneyApiTarget != null),
                    (moneyApiCompanion != null),
                    (mIMoneyHandler_extractMoney != null),
                    (mCoinValue_fromItem_long != null),
                    (mCoinValue_fromItem_int != null),
                    (mCoinValue_fromStack_long != null),
                    (mCoinValue_fromStack_int != null),
                    (mMoneyValue_isEmpty != null)
            );

        } catch (Throwable t) {
            EZVillagerReroll.LOG().warn("[EZVR] MoneyBridge.bind failed: {}", t.toString());
        }
    }

    private static boolean areBound() {
        boolean handlerOK;
        if (mMoneyAPI_getPlayersMoneyHandler == null) handlerOK = false;
        else if (Modifier.isStatic(mMoneyAPI_getPlayersMoneyHandler.getModifiers())) handlerOK = true;
        else handlerOK = (moneyApiTarget != null || moneyApiCompanion != null);

        return clsMoneyAPI != null
                && clsIMoneyHandler != null
                && clsMoneyValue != null
                && clsCoinValue != null
                && handlerOK
                && mIMoneyHandler_extractMoney != null
                && mMoneyValue_isEmpty != null
                && (mCoinValue_fromItem_long != null
                || mCoinValue_fromItem_int != null
                || mCoinValue_fromStack_long != null
                || mCoinValue_fromStack_int != null);
    }

    private static Method tryGetHandlerGetter(Class<?> moneyAPI) {
        String[] names = new String[]{
                "GetPlayersMoneyHandler", "getPlayersMoneyHandler",
                "GetPlayerMoneyHandler", "getPlayerMoneyHandler",
                "GetMoneyHandler", "getMoneyHandler"
        };
        Class<?>[] params = new Class<?>[]{
                Player.class, ServerPlayer.class, LivingEntity.class, Entity.class
        };
        for (String n : names) {
            for (Class<?> p : params) {
                try {
                    Method m = moneyAPI.getMethod(n, p);
                    m.setAccessible(true);
                    EZVillagerReroll.LOG().info("[EZVR] MoneyAPI bind: MoneyAPI#{}({}) OK", n, p.getSimpleName());
                    return m;
                } catch (NoSuchMethodException ignored) {}
                try {
                    Method m = moneyAPI.getDeclaredMethod(n, p);
                    m.setAccessible(true);
                    EZVillagerReroll.LOG().info("[EZVR] MoneyAPI bind: MoneyAPI#declared {}({}) OK", n, p.getSimpleName());
                    return m;
                } catch (NoSuchMethodException ignored2) {}
            }
        }
        EZVillagerReroll.LOG().info("[EZVR] MoneyAPI bind: handler getter not found (tried common names)");
        return null;
    }

    private static void resolveMoneyApiInstances(Class<?> moneyAPI) {
        try {
            if (moneyAPI.isEnum()) {
                Object[] constants = moneyAPI.getEnumConstants();
                if (constants != null && constants.length > 0) {
                    moneyApiTarget = constants[0];
                    EZVillagerReroll.LOG().info("[EZVR] MoneyAPI bind: using enum constant as instance.");
                    return;
                }
            }
        } catch (Throwable ignored) {}

        for (String fn : new String[]{"INSTANCE", "Instance", "instance"}) {
            try {
                Field f = null;
                try { f = moneyAPI.getField(fn); } catch (NoSuchFieldException ignored) {}
                if (f == null) f = moneyAPI.getDeclaredField(fn);
                f.setAccessible(true);
                if (Modifier.isStatic(f.getModifiers())) {
                    Object v = f.get(null);
                    if (v != null) { moneyApiTarget = v; return; }
                }
            } catch (Throwable ignored) {}
        }

        try {
            Field f = null;
            try { f = moneyAPI.getField("Companion"); } catch (NoSuchFieldException ignored) {}
            if (f == null) f = moneyAPI.getDeclaredField("Companion");
            f.setAccessible(true);
            if (Modifier.isStatic(f.getModifiers())) {
                Object v = f.get(null);
                if (v != null) moneyApiCompanion = v;
            }
        } catch (Throwable ignored) {}

        for (String mn : new String[]{"get", "getInstance", "instance"}) {
            try {
                Method m = null;
                try { m = moneyAPI.getMethod(mn); } catch (NoSuchMethodException ignored) {}
                if (m == null) m = moneyAPI.getDeclaredMethod(mn);
                m.setAccessible(true);
                if (Modifier.isStatic(m.getModifiers())) {
                    Object v = m.invoke(null);
                    if (v != null) { moneyApiTarget = v; return; }
                }
            } catch (Throwable ignored) {}
        }

        try {
            Constructor<?> c = moneyAPI.getDeclaredConstructor();
            c.setAccessible(true);
            Object v = c.newInstance();
            if (v != null) moneyApiTarget = v;
        } catch (Throwable ignored) {}
    }

    private static void bindCoinValueFactories() {
        try {
            for (Method m : clsCoinValue.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (!clsMoneyValue.isAssignableFrom(m.getReturnType())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == Item.class && p[1] == long.class) mCoinValue_fromItem_long = m;
                else if (p.length == 2 && p[0] == Item.class && p[1] == int.class) mCoinValue_fromItem_int = m;
                else if (p.length == 2 && p[0] == ItemStack.class && p[1] == long.class) mCoinValue_fromStack_long = m;
                else if (p.length == 2 && p[0] == ItemStack.class && p[1] == int.class) mCoinValue_fromStack_int = m;
            }
            EZVillagerReroll.LOG().info(
                    "[EZVR] MoneyAPI bind: CoinValue factories -> item,long={}, item,int={}, stack,long={}, stack,int={}",
                    (mCoinValue_fromItem_long != null),
                    (mCoinValue_fromItem_int != null),
                    (mCoinValue_fromStack_long != null),
                    (mCoinValue_fromStack_int != null)
            );
        } catch (Throwable t) {
            EZVillagerReroll.LOG().warn("[EZVR] MoneyAPI bind: scanning CoinValue factories failed: {}", t.toString());
        }
    }

    private static Object buildMoneyValue(Item coinItem, int count) {
        try {
            if (count <= 0) count = 1;
            if (mCoinValue_fromItem_long != null)
                return mCoinValue_fromItem_long.invoke(null, coinItem, (long) count);
            if (mCoinValue_fromItem_int != null)
                return mCoinValue_fromItem_int.invoke(null, coinItem, count);

            ItemStack stack = new ItemStack(coinItem, count);
            if (mCoinValue_fromStack_long != null)
                return mCoinValue_fromStack_long.invoke(null, stack, (long) count);
            if (mCoinValue_fromStack_int != null)
                return mCoinValue_fromStack_int.invoke(null, stack, count);

            return null;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().warn("[EZVR] MoneyAPI: buildMoneyValue failed: {}", t.toString());
            return null;
        }
    }

    private static boolean isMoneyEmpty(Object moneyValue) {
        try {
            if (moneyValue == null) return true;
            Object r = mMoneyValue_isEmpty.invoke(moneyValue);
            return (r instanceof Boolean b) && b;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object invokeHandlerGetter(ServerPlayer player) {
        try {
            if (mMoneyAPI_getPlayersMoneyHandler == null) return null;

            Class<?> p = mMoneyAPI_getPlayersMoneyHandler.getParameterTypes()[0];
            Object arg = (p == Player.class) ? (Player) player
                    : (p == ServerPlayer.class) ? player
                    : (p == LivingEntity.class) ? (LivingEntity) player
                    : (p == Entity.class) ? (Entity) player
                    : player;

            if (Modifier.isStatic(mMoneyAPI_getPlayersMoneyHandler.getModifiers()))
                return mMoneyAPI_getPlayersMoneyHandler.invoke(null, arg);

            if (moneyApiTarget != null)
                return mMoneyAPI_getPlayersMoneyHandler.invoke(moneyApiTarget, arg);

            if (moneyApiCompanion != null)
                return mMoneyAPI_getPlayersMoneyHandler.invoke(moneyApiCompanion, arg);

            return null;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().info("[EZVR] MoneyAPI: invoke handler getter failed: {}", t.toString());
            return null;
        }
    }

    private MoneyBridge() {}
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\logic\MoneyBridge.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\logic\RerollExecutor.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/logic/RerollExecutor.java
        package org.z2six.ezvillagerreroll.logic;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.trading.Merchant;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.config.ServerConfig;
import org.z2six.ezvillagerreroll.mixin.MerchantMenuAccessor;

public final class RerollExecutor {

    public static void tryReroll(ServerPlayer sp) {
        try {
            if (!(sp.containerMenu instanceof MerchantMenu menu)) {
                EZVillagerReroll.LOG().debug("[EZVR] tryReroll: player not in MerchantMenu; ignoring (player={})", sp.getGameProfile().getName());
                return;
            }

            if (menu.getSlot(2).hasItem()) {
                toast(sp, "ezvr.msg.mid_trade");
                EZVillagerReroll.LOG().info("[EZVR] Reroll refused: result slot occupied (player={})", sp.getGameProfile().getName());
                return;
            }

            Merchant trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Villager vill)) {
                toast(sp, "ezvr.msg.not_villager");
                EZVillagerReroll.LOG().info("[EZVR] Reroll refused: trader is not a Villager (player={}, traderType={})",
                        sp.getGameProfile().getName(),
                        trader == null ? "null" : trader.getClass().getName()
                );
                return;
            }

            int level = Math.max(1, Math.min(5, vill.getVillagerData().getLevel()));
            int xp = vill.getVillagerXp();

            int offersBefore = vill.getOffers() != null ? vill.getOffers().size() : -1;

            if (!RerollState.canReroll(sp, vill)) {
                toast(sp, "ezvr.msg.cooldown_or_cap");
                EZVillagerReroll.LOG().info("[EZVR] Reroll refused: cooldown/cap (player={}, villager={})",
                        sp.getGameProfile().getName(), vill.getUUID());
                return;
            }

            if (!ServerConfig.allowAfterTradeUsed && xp > 0) {
                toast(sp, "ezvr.msg.after_used_disabled");
                EZVillagerReroll.LOG().info("[EZVR] Reroll refused: allowAfterTradeUsed=false (player={}, villager={})",
                        sp.getGameProfile().getName(), vill.getUUID());
                return;
            }

            // ---- NEW offer-based cost ----
            int totalOffers = Math.max(0, offersBefore);
            long lockMask = TradeLockState.getMask(vill);
            long sanitized = TradeLockState.sanitizeMaskForSize(lockMask, totalOffers);
            if (sanitized != lockMask) {
                TradeLockState.setMask(vill, sanitized);
                lockMask = sanitized;
                EZVillagerReroll.LOG().debug("[EZVR] Sanitized lock mask during reroll cost calc (villager={} beforeMask={} afterMask={})",
                        vill.getUUID(), Long.toUnsignedString(lockMask), Long.toUnsignedString(sanitized));
            }

            int lockedCount = Long.bitCount(lockMask);
            int maxDeduct = Math.max(0, ServerConfig.maxDeductibleLockedOffers);
            int deductibleLocks = Math.min(lockedCount, maxDeduct);

            int effectiveOffers = Math.max(0, totalOffers - deductibleLocks);
            int freeOffers = Math.max(0, ServerConfig.freeOffers);
            int costPerOffer = Math.max(0, ServerConfig.costPerOffer);

            int paidOffers = Math.max(0, effectiveOffers - freeOffers);

            int cost;
            try {
                long c = (long) paidOffers * (long) costPerOffer;
                if (c < 0) c = 0;
                if (c > Integer.MAX_VALUE) c = Integer.MAX_VALUE;
                cost = (int) c;
            } catch (Throwable t) {
                cost = Integer.MAX_VALUE;
            }

            EZVillagerReroll.LOG().info(
                    "[EZVR] Reroll attempt: player={}, villager={}, prof={}, level={}, xp={}, offersBefore={}, lockedCount={}, deductibleLocks={}, effectiveOffers={}, freeOffers={}, paidOffers={}, costPerOffer={}, cost={}, costSpec='{}' (preferWallet={})",
                    sp.getGameProfile().getName(),
                    vill.getUUID(),
                    vill.getVillagerData().getProfession(),
                    level, xp, offersBefore,
                    lockedCount, deductibleLocks, effectiveOffers,
                    freeOffers, paidOffers, costPerOffer,
                    cost, ServerConfig.costSpec, ServerConfig.preferWallet
            );

            boolean paid = false;

            if (cost > 0) {
                boolean specIsTag = ServerConfig.isTagSpec(ServerConfig.costSpec);
                ResourceLocation itemId = specIsTag ? null : ResourceLocation.tryParse(ServerConfig.costSpec);
                boolean isExactItem = itemId != null && !specIsTag;

                // Wallet paths only supported for exact item specs (not tags)
                if (ServerConfig.preferWallet && isExactItem && MoneyBridge.isLCPresent()) {
                    boolean apiPaid = MoneyBridge.tryExtract(sp, itemId, cost);
                    if (apiPaid) {
                        EZVillagerReroll.LOG().info("[EZVR] Cost paid via LC MoneyAPI: {} x {}", cost, itemId);
                        paid = true;
                    } else {
                        EZVillagerReroll.LOG().info("[EZVR] LC MoneyAPI extraction failed; trying direct wallet next: {} x {}", cost, itemId);
                    }
                }

                if (!paid && ServerConfig.preferWallet && isExactItem && WalletBridge.isLCPresent()) {
                    boolean walletPaid = WalletBridge.tryWithdrawFromWallet(sp, itemId, cost);
                    if (walletPaid) {
                        EZVillagerReroll.LOG().info("[EZVR] Cost paid via LC wallet (direct): {} x {}", cost, itemId);
                        paid = true;
                    } else {
                        EZVillagerReroll.LOG().info("[EZVR] LC direct wallet failed; falling back to inventory: {} x {}", cost, itemId);
                    }
                }

                if (!paid) {
                    Ingredient ing = CostUtil.parseIngredient(ServerConfig.costSpec);
                    if (ing == Ingredient.EMPTY || !CostUtil.consume(sp, ing, cost)) {
                        toast(sp, "ezvr.msg.not_enough");
                        EZVillagerReroll.LOG().info("[EZVR] Reroll refused: insufficient inventory for {} x {}", cost, ServerConfig.costSpec);
                        return;
                    }
                    EZVillagerReroll.LOG().info("[EZVR] Cost consumed from inventory: {} x {}", cost, ServerConfig.costSpec);
                    paid = true;
                }
            } else {
                EZVillagerReroll.LOG().info("[EZVR] Cost is zero (free reroll per offer-based config).");
                paid = true;
            }

            if (!TradeUtil.rebuildOffers(vill, sp)) {
                EZVillagerReroll.LOG().warn("[EZVR] Reroll failed during rebuild (villager={})", vill.getUUID());
                toast(sp, "ezvr.msg.failed");
                return;
            }

            int offersAfter = vill.getOffers() != null ? vill.getOffers().size() : -1;
            RerollState.markRerolled(sp, vill);
            toast(sp, "ezvr.msg.success");

            EZVillagerReroll.LOG().info(
                    "[EZVR] Reroll success: villager={}, offers {} -> {}, player={}, paid={}",
                    vill.getUUID(), offersBefore, offersAfter, sp.getGameProfile().getName(),
                    (cost <= 0) ? "free" : (paid ? "yes" : "no")
            );

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] tryReroll exception", t);
        }
    }

    private static void toast(ServerPlayer sp, String key) {
        try {
            sp.displayClientMessage(Component.translatable(key), true);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().warn("[EZVR] toast failed for key={}: {}", key, t.toString());
        }
    }

    private RerollExecutor() {}
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\logic\RerollExecutor.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\logic\RerollState.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/logic/RerollState.java
        package org.z2six.ezvillagerreroll.logic;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import org.z2six.ezvillagerreroll.config.ServerConfig;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RerollState {

    private static final Map<UUID, Long> lastTick = new HashMap<>();
    private static final Map<UUID, Integer> dailyCount = new HashMap<>();
    private static long lastDay = Long.MIN_VALUE;

    public static boolean canReroll(ServerPlayer sp, Villager vill) {
        if (sp == null || vill == null) return false;
        return canReroll(sp.serverLevel(), vill);
    }

    public static boolean canReroll(ServerLevel lvl, Villager vill) {
        if (lvl == null || vill == null) return false;

        long now = lvl.getGameTime();
        long day = lvl.getDayTime() / 24000L;

        if (day != lastDay) {
            dailyCount.clear();
            lastDay = day;
        }

        int cooldown = ServerConfig.cooldownTicks;
        if (cooldown > 0) {
            long last = lastTick.getOrDefault(vill.getUUID(), Long.MIN_VALUE);
            if (last != Long.MIN_VALUE && now - last < cooldown) return false;
        }

        int cap = ServerConfig.perVillagerDaily;
        if (cap > 0) {
            int used = dailyCount.getOrDefault(vill.getUUID(), 0);
            if (used >= cap) return false;
        }

        return true;
    }

    public static void markRerolled(ServerPlayer sp, Villager vill) {
        if (sp == null || vill == null) return;
        markRerolled(sp.serverLevel(), vill);
    }

    public static void markRerolled(ServerLevel lvl, Villager vill) {
        if (lvl == null || vill == null) return;

        long now = lvl.getGameTime();
        UUID id = vill.getUUID();
        lastTick.put(id, now);
        if (ServerConfig.perVillagerDaily > 0) {
            dailyCount.merge(id, 1, Integer::sum);
        }
    }

    /**
     * Returns how many ticks remain until cooldown is finished for this villager.
     * 0 means "no cooldown currently active" OR "cooldown disabled".
     *
     * This is used for server->client UI disabling; server remains authoritative.
     */
    public static int cooldownRemainingTicks(ServerLevel lvl, Villager vill) {
        try {
            if (lvl == null || vill == null) return 0;

            int cooldown = ServerConfig.cooldownTicks;
            if (cooldown <= 0) return 0;

            long now = lvl.getGameTime();
            long last = lastTick.getOrDefault(vill.getUUID(), Long.MIN_VALUE);
            if (last == Long.MIN_VALUE) return 0;

            long elapsed = now - last;
            if (elapsed >= cooldown) return 0;

            long rem = cooldown - elapsed;
            if (rem < 0) rem = 0;
            if (rem > Integer.MAX_VALUE) rem = Integer.MAX_VALUE;
            return (int) rem;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] cooldownRemainingTicks failed (soft): {}", t.toString());
            return 0;
        }
    }

    private RerollState() {}
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\logic\RerollState.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\logic\TradeLockState.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/logic/TradeLockState.java
        package org.z2six.ezvillagerreroll.logic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.npc.Villager;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

public final class TradeLockState {

    private static final String NBT_ROOT = "ezvillagerreroll";
    private static final String NBT_LOCK_MASK = "locked_trade_mask";

    public static long getMask(Villager vill) {
        try {
            if (vill == null) return 0L;
            CompoundTag pd = vill.getPersistentData();
            if (!pd.contains(NBT_ROOT, CompoundTag.TAG_COMPOUND)) return 0L;
            CompoundTag root = pd.getCompound(NBT_ROOT);
            if (!root.contains(NBT_LOCK_MASK, CompoundTag.TAG_LONG)) return 0L;
            return root.getLong(NBT_LOCK_MASK);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] TradeLockState.getMask failed: {}", t.toString());
            return 0L;
        }
    }

    public static void setMask(Villager vill, long mask) {
        try {
            if (vill == null) return;
            CompoundTag pd = vill.getPersistentData();
            CompoundTag root = pd.contains(NBT_ROOT, CompoundTag.TAG_COMPOUND) ? pd.getCompound(NBT_ROOT) : new CompoundTag();
            root.putLong(NBT_LOCK_MASK, mask);
            pd.put(NBT_ROOT, root);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] TradeLockState.setMask failed: {}", t.toString());
        }
    }

    public static long toggle(Villager vill, int idx) {
        try {
            long mask = getMask(vill);
            long bit = (1L << idx);
            long next = mask ^ bit;
            setMask(vill, next);
            return next;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] TradeLockState.toggle failed: {}", t.toString());
            return getMask(vill);
        }
    }

    /**
     * Clears bits larger than size (or if size smaller than 0, clears all).
     */
    public static long sanitizeMaskForSize(long mask, int size) {
        if (size <= 0) return 0L;
        if (size >= 63) return mask; // we only ever toggle indices < 63, but keep it safe.
        long allowed = (1L << size) - 1L;
        return mask & allowed;
    }

    private TradeLockState() {}
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\logic\TradeLockState.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\logic\TradeUtil.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/logic/TradeUtil.java
        package org.z2six.ezvillagerreroll.logic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.mixin.AbstractVillagerAccessor;
import org.z2six.ezvillagerreroll.mixin.VillagerAccessor;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public final class TradeUtil {

    /**
     * Max attempts to rebuild offers until we avoid duplicates against preserved locked offers.
     * Keep this bounded to avoid stalls.
     */
    private static final int MAX_DEDUP_ATTEMPTS = 25;

    public static boolean rebuildOffers(Villager vill, ServerPlayer player) {
        return rebuildOffersInternal(vill, player, true);
    }

    /**
     * Internal offer rebuild used by:
     * - normal reroll (syncToPlayer=true)
     * - auto-search rerolls (syncToPlayer=false)
     * - catalog sampling (syncToPlayer=false)
     *
     * Must preserve trade locks and avoid "duplicate exact offers" in the final list when possible.
     */
    public static boolean rebuildOffersInternal(Villager vill, ServerPlayer player, boolean syncToPlayer) {
        try {
            if (vill == null) return false;

            final VillagerData original = vill.getVillagerData();
            final int targetLevel = Math.max(1, Math.min(5, original.getLevel()));
            final int offersBefore = vill.getOffers() != null ? vill.getOffers().size() : -1;

            // Snapshot old offers (needed to preserve locked indices)
            final MerchantOffers oldOffersSnapshot = snapshotOffersSafe(vill);

            // Preserve lock mask (server-side authoritative on the entity via TradeLockState)
            final long beforeMask = TradeLockState.getMask(vill);

            // We may rebuild multiple times if we detect duplicates involving unlocked trades.
            MerchantOffers finalOffers = null;
            long finalMask = beforeMask;

            int attempt;
            boolean accepted = false;

            for (attempt = 1; attempt <= MAX_DEDUP_ATTEMPTS; attempt++) {
                // 1) rebuild fresh offers using vanilla updateTrades progression
                MerchantOffers rebuilt = rebuildOffersVanillaSteps(vill, player, original, targetLevel);

                // 2) sanitize lock mask to rebuilt size
                long afterMask = TradeLockState.sanitizeMaskForSize(beforeMask, rebuilt == null ? 0 : rebuilt.size());
                if (afterMask != beforeMask) {
                    // keep entity state updated so future logic is consistent
                    TradeLockState.setMask(vill, afterMask);
                    EZVillagerReroll.LOG().debug("[EZVR] Lock mask sanitized due to offer size change (villager={} before={} after={})",
                            vill.getUUID(), Long.toUnsignedString(beforeMask), Long.toUnsignedString(afterMask));
                }

                // 3) restore locked offers into the rebuilt list
                if (rebuilt != null && oldOffersSnapshot != null && afterMask != 0L) {
                    restoreLockedOffersSafe(rebuilt, oldOffersSnapshot, afterMask, vill);
                }

                // 4) check duplicates in final list (especially duplicates caused by restoring locks)
                boolean hasBadDupes = hasDuplicatesInvolvingUnlocked(rebuilt, afterMask);

                if (!hasBadDupes) {
                    // success
                    finalOffers = rebuilt;
                    finalMask = afterMask;
                    accepted = true;
                    break;
                }

                // If we get here, duplicates exist where at least one is unlocked.
                // Try again with a new random roll.
                if (attempt == 1 || attempt == 5 || attempt == 10 || attempt == 25) {
                    EZVillagerReroll.LOG().debug(
                            "[EZVR] Dedup rebuild retry {} / {} (villager={} level={}): duplicates detected involving unlocked offers.",
                            attempt, MAX_DEDUP_ATTEMPTS, vill.getUUID(), targetLevel
                    );
                }
            }

            if (!accepted) {
                // We tried; keep whatever we have currently on the villager (already set by last rebuild).
                finalOffers = vill.getOffers();
                finalMask = TradeLockState.getMask(vill);

                EZVillagerReroll.LOG().warn(
                        "[EZVR] Dedup rebuild exhausted ({} attempts). Keeping last rebuild result (villager={}, offers={}).",
                        MAX_DEDUP_ATTEMPTS, vill.getUUID(), finalOffers == null ? -1 : finalOffers.size()
                );
            }

            // Ensure villager has the final offers applied (in case our helper returned a detached instance)
            try {
                if (finalOffers != null && vill.getOffers() != finalOffers) {
                    ((AbstractVillagerAccessor) (AbstractVillager) vill).ezvr$setOffers(finalOffers);
                }
            } catch (Throwable t) {
                EZVillagerReroll.LOG().debug("[EZVR] Failed to force-set final offers reference (soft): {}", t.toString());
            }

            // Optional GUI sync
            if (syncToPlayer && player != null && player.containerMenu instanceof MerchantMenu menu) {
                MerchantOffers offers = vill.getOffers();
                int offersAfter = offers != null ? offers.size() : -1;

                player.sendMerchantOffers(
                        menu.containerId,
                        offers,
                        vill.getVillagerData().getLevel(),
                        vill.getVillagerXp(),
                        vill.showProgressBar(),
                        vill.canRestock()
                );

                EZVillagerReroll.LOG().info(
                        "[EZVR] Rebuilt offers (dedup attempts={}): villager={}, level={}, offers {} -> {}",
                        accepted ? attempt : MAX_DEDUP_ATTEMPTS,
                        vill.getUUID(), targetLevel, offersBefore, offersAfter
                );
            } else {
                MerchantOffers offers = vill.getOffers();
                int offersAfter = offers != null ? offers.size() : -1;

                EZVillagerReroll.LOG().debug(
                        "[EZVR] Rebuilt offers (no GUI sync, dedup attempts={}): villager={}, level={}, offers {} -> {}",
                        accepted ? attempt : MAX_DEDUP_ATTEMPTS,
                        vill.getUUID(), targetLevel, offersBefore, offersAfter
                );
            }

            // If we changed/sanitized locks, push updated locks to active traders so UI stays consistent.
            if (finalMask != beforeMask) {
                try {
                    org.z2six.ezvillagerreroll.server.TradeLockSyncService.syncToActiveTraders(vill, finalMask);
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().debug("[EZVR] Failed to sync sanitized lock mask to active traders: {}", t.toString());
                }
            }

            return true;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] rebuildOffersInternal exception", t);
            return false;
        }
    }

    /**
     * Rebuild offers by clearing and running vanilla updateTrades() per level up to targetLevel.
     * Returns the offers instance currently on the villager after rebuild.
     */
    private static MerchantOffers rebuildOffersVanillaSteps(Villager vill, ServerPlayer player, VillagerData original, int targetLevel) {
        try {
            // Clear and rebuild per level
            ((AbstractVillagerAccessor) (AbstractVillager) vill).ezvr$setOffers(new MerchantOffers());

            for (int l = 1; l <= targetLevel; l++) {
                VillagerData step = new VillagerData(original.getType(), original.getProfession(), l);
                vill.setVillagerData(step);
                ((VillagerAccessor) vill).ezvr$updateTrades(); // appends that level's offers
            }

            vill.setVillagerData(original);

            // Only apply special prices if player is known
            try {
                if (player != null) ((VillagerAccessor) vill).ezvr$updateSpecialPrices(player);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().debug("[EZVR] updateSpecialPrices skipped/failed (soft): {}", t.toString());
            }

            return vill.getOffers();
        } catch (Throwable t) {
            EZVillagerReroll.LOG().warn("[EZVR] rebuildOffersVanillaSteps failed (villager={}): {}", vill == null ? "null" : vill.getUUID(), t.toString());
            return vill != null ? vill.getOffers() : null;
        }
    }

    private static MerchantOffers snapshotOffersSafe(Villager vill) {
        try {
            if (vill == null) return null;
            if (vill.getOffers() == null) return null;

            MerchantOffers snap = new MerchantOffers();
            for (MerchantOffer o : vill.getOffers()) {
                if (o != null) snap.add(o);
            }
            return snap;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().warn("[EZVR] Failed to snapshot old offers (villager={}): {}", vill == null ? "null" : vill.getUUID(), t.toString());
            return null;
        }
    }

    private static void restoreLockedOffersSafe(MerchantOffers current, MerchantOffers oldOffers, long mask, Villager vill) {
        try {
            if (current == null || oldOffers == null || mask == 0L) return;

            int limit = Math.min(current.size(), oldOffers.size());
            int preserved = 0;

            for (int i = 0; i < limit; i++) {
                if ((mask & (1L << i)) == 0L) continue;

                try {
                    MerchantOffer old = oldOffers.get(i);
                    if (old != null) {
                        current.set(i, old);
                        preserved++;
                    }
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().warn("[EZVR] Failed to preserve locked offer idx={} villager={} {}",
                            i, vill == null ? "null" : vill.getUUID(), t.toString());
                }
            }

            EZVillagerReroll.LOG().debug("[EZVR] Preserved {} locked offers (villager={}, mask={})",
                    preserved, vill == null ? "null" : vill.getUUID(), Long.toUnsignedString(mask));
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] restoreLockedOffersSafe failed (soft): {}", t.toString());
        }
    }

    /**
     * Returns true if there are duplicates in the offer list where at least one of the duplicates is UNLOCKED.
     * If duplicates exist but they are both locked, we do not retry (we cannot safely change locked offers).
     */
    private static boolean hasDuplicatesInvolvingUnlocked(MerchantOffers offers, long lockMask) {
        try {
            if (offers == null || offers.isEmpty()) return false;

            Map<String, Integer> firstIndexBySig = new HashMap<>();
            for (int i = 0; i < offers.size(); i++) {
                MerchantOffer o = offers.get(i);
                if (o == null) continue;

                String sig = signatureOf(o);
                Integer first = firstIndexBySig.putIfAbsent(sig, i);
                if (first == null) continue;

                boolean firstLocked = (lockMask & (1L << first)) != 0L;
                boolean thisLocked = (lockMask & (1L << i)) != 0L;

                // If at least one is unlocked, it's fixable -> retry rebuild.
                if (!firstLocked || !thisLocked) {
                    EZVillagerReroll.LOG().debug("[EZVR] Duplicate offer detected (idxA={}, idxB={}, lockedA={}, lockedB={}, sig={})",
                            first, i, firstLocked, thisLocked, sig);
                    return true;
                }
            }

            return false;
        } catch (Throwable t) {
            // Fail open: don't get stuck rebuilding forever if signature logic breaks.
            EZVillagerReroll.LOG().debug("[EZVR] hasDuplicatesInvolvingUnlocked failed (soft): {}", t.toString());
            return false;
        }
    }

    /**
     * Build a stable-ish signature for an offer.
     * We intentionally focus on the economic identity: inputs + output (including tag/components).
     * This allows multiple enchanted books (different data) but blocks exact duplicates like paper->emerald twice.
     */
    private static String signatureOf(MerchantOffer offer) {
        try {
            if (offer == null) return "null";

            ItemStack a = safeStack(offer.getBaseCostA());
            ItemStack b = safeStack(offer.getCostB());
            ItemStack r = safeStack(offer.getResult());

            StringBuilder sb = new StringBuilder(256);
            sb.append("A=").append(stackSig(a)).append('|');
            sb.append("B=").append(stackSig(b)).append('|');
            sb.append("R=").append(stackSig(r)).append('|');

            // Include a couple offer params that can differentiate “same item” trades
            try { sb.append("MU=").append(offer.getMaxUses()).append('|'); } catch (Throwable ignored) {}
            try { sb.append("XP=").append(offer.getXp()).append('|'); } catch (Throwable ignored) {}
            try { sb.append("PM=").append(offer.getPriceMultiplier()).append('|'); } catch (Throwable ignored) {}

            return sb.toString();
        } catch (Throwable t) {
            return "err";
        }
    }

    private static ItemStack safeStack(ItemStack s) {
        return s == null ? ItemStack.EMPTY : s;
    }

    /**
     * ItemStack identity for dedup:
     * - item id
     * - count
     * - "extra data" (NBT or components), discovered reflectively to survive 1.21.x signature changes.
     */
    private static String stackSig(ItemStack s) {
        try {
            if (s == null || s.isEmpty()) return "empty";

            String id = "unknown";
            try {
                Item item = s.getItem();
                id = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item));
            } catch (Throwable ignored) {}

            int count = 0;
            try { count = s.getCount(); } catch (Throwable ignored) {}

            String extra = readExtraDataStringReflective(s);

            // Keep signature bounded-ish; this is internal logic, not player-facing.
            if (extra != null && extra.length() > 2048) {
                extra = extra.substring(0, 2048);
            }

            return id + "x" + count + ":" + (extra == null ? "" : extra);
        } catch (Throwable t) {
            return "stack_err";
        }
    }

    /**
     * Try multiple strategies to extract differentiating data:
     * - getTag() (CompoundTag) if present
     * - getComponents() / getComponentsPatch() if present
     * - finally fall back to toString()
     */
    private static String readExtraDataStringReflective(ItemStack s) {
        try {
            if (s == null) return "";

            // 1) getTag() -> CompoundTag
            try {
                Method m = s.getClass().getMethod("getTag");
                Object tagObj = m.invoke(s);
                if (tagObj instanceof CompoundTag tag) {
                    return tag.toString();
                }
            } catch (Throwable ignored) {}

            // 2) getComponentsPatch()
            try {
                Method m = s.getClass().getMethod("getComponentsPatch");
                Object patch = m.invoke(s);
                if (patch != null) return patch.toString();
            } catch (Throwable ignored) {}

            // 3) getComponents()
            try {
                Method m = s.getClass().getMethod("getComponents");
                Object comps = m.invoke(s);
                if (comps != null) return comps.toString();
            } catch (Throwable ignored) {}

            // 4) last resort
            try {
                return s.toString();
            } catch (Throwable ignored) {
                return "";
            }

        } catch (Throwable t) {
            return "";
        }
    }

    private TradeUtil() {}
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\logic\TradeUtil.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\logic\WalletBridge.java ----
// MainFile: src/main/java/org/z2six/ezvillagerreroll/logic/WalletBridge.java
        package org.z2six.ezvillagerreroll.logic;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

public final class WalletBridge {

    private static volatile boolean lookedUp = false;
    private static volatile boolean lcPresent = false;

    private static final List<String> WALLET_HANDLER_CANDIDATES = Arrays.asList(
            "io.github.lightman314.lightmanscurrency.common.wallet.WalletHandler",
            "lightmanscurrency.common.wallet.WalletHandler"
    );
    private static final List<String> WALLET_ITEM_CANDIDATES = Arrays.asList(
            "io.github.lightman314.lightmanscurrency.common.items.WalletItem",
            "lightmanscurrency.common.items.WalletItem"
    );
    private static final List<String> WALLET_WRAPPER_CANDIDATES = Arrays.asList(
            "io.github.lightman314.lightmanscurrency.common.wallet.WalletDataWrapper",
            "lightmanscurrency.common.wallet.WalletDataWrapper"
    );

    private static Class<?> clsWalletHandler;
    private static Class<?> clsWalletItem;
    private static Class<?> clsWalletDataWrapper;

    private static Method mWalletHandler_get;        // static WalletHandler WalletHandler.get(Entity)
    private static Method mWalletHandler_getWallet;  // ItemStack WalletHandler.getWallet()
    private static Method mWalletHandler_setChanged; // void WalletHandler.setChanged()

    private static Method mWalletItem_isWallet;       // boolean WalletItem.isWallet(ItemStack)
    private static Method mWalletItem_getDataWrapper; // WalletDataWrapper WalletItem.getDataWrapper(ItemStack)

    private static Method mWrapper_getContents; // Container WalletDataWrapper.getContents()
    private static Method mWrapper_setContents; // void WalletDataWrapper.setContents(Container, LivingEntity)

    public static boolean isLCPresent() {
        if (!lookedUp) {
            lcPresent = ModList.get().isLoaded("lightmanscurrency");
            lookedUp = true;
            if (lcPresent) {
                EZVillagerReroll.LOG().info("[EZVR] Lightman's Currency detected (soft-integration active)");
                bindReflectionHandles();
            }
        }
        return lcPresent;
    }

    public static boolean tryWithdrawFromWallet(ServerPlayer player, ResourceLocation coinId, int count) {
        try {
            if (!isLCPresent()) return false;
            if (coinId == null || count <= 0) return false;

            if (!areBound()) {
                EZVillagerReroll.LOG().info("[EZVR] LC wallet API not bound; falling back to inventory.");
                return false;
            }

            Item targetItem = BuiltInRegistries.ITEM.get(coinId);
            if (targetItem == null) {
                EZVillagerReroll.LOG().info("[EZVR] LC withdraw: item '{}' not found in registry.", coinId);
                return false;
            }

            Object handler = mWalletHandler_get.invoke(null, player);
            if (handler == null) {
                EZVillagerReroll.LOG().info("[EZVR] LC withdraw: WalletHandler.get(player) returned null.");
                return false;
            }

            Object walletStackObj = mWalletHandler_getWallet.invoke(handler);
            if (!(walletStackObj instanceof ItemStack walletStack)) {
                EZVillagerReroll.LOG().info("[EZVR] LC withdraw: getWallet() did not return an ItemStack.");
                return false;
            }

            if (walletStack.isEmpty() || !(Boolean) mWalletItem_isWallet.invoke(null, walletStack)) {
                EZVillagerReroll.LOG().info("[EZVR] LC withdraw: no valid wallet equipped.");
                return false;
            }

            Object wrapper = mWalletItem_getDataWrapper.invoke(null, walletStack);
            if (wrapper == null) {
                EZVillagerReroll.LOG().info("[EZVR] LC withdraw: getDataWrapper() returned null.");
                return false;
            }

            Object containerObj = mWrapper_getContents.invoke(wrapper);
            if (!(containerObj instanceof Container container)) {
                EZVillagerReroll.LOG().info("[EZVR] LC withdraw: getContents() did not return a Container.");
                return false;
            }

            int available = 0;
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack s = container.getItem(i);
                if (s.isEmpty()) continue;
                if (s.getItem() == targetItem) available += s.getCount();
                if (available >= count) break;
            }
            if (available < count) {
                EZVillagerReroll.LOG().info("[EZVR] LC withdraw: insufficient wallet coins. Need {}, have {}.", count, available);
                return false;
            }

            int remaining = count;
            for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
                ItemStack s = container.getItem(i);
                if (s.isEmpty()) continue;
                if (s.getItem() != targetItem) continue;
                int take = Math.min(remaining, s.getCount());
                s.shrink(take);
                remaining -= take;
            }

            mWrapper_setContents.invoke(wrapper, container, (LivingEntity) player);
            mWalletHandler_setChanged.invoke(handler);

            EZVillagerReroll.LOG().info("[EZVR] LC wallet: withdrew {} x {}", count, coinId);
            return true;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().warn("[EZVR] LC wallet withdraw failed (soft): {}", t.toString());
            return false;
        }
    }

    private static boolean areBound() {
        return mWalletHandler_get != null
                && mWalletHandler_getWallet != null
                && mWalletHandler_setChanged != null
                && mWalletItem_isWallet != null
                && mWalletItem_getDataWrapper != null
                && mWrapper_getContents != null
                && mWrapper_setContents != null;
    }

    private static void bindReflectionHandles() {
        try {
            clsWalletHandler = tryLoadAny(WALLET_HANDLER_CANDIDATES);
            clsWalletItem = tryLoadAny(WALLET_ITEM_CANDIDATES);
            clsWalletDataWrapper = tryLoadAny(WALLET_WRAPPER_CANDIDATES);

            if (clsWalletHandler == null || clsWalletItem == null || clsWalletDataWrapper == null) {
                EZVillagerReroll.LOG().info("[EZVR] LC wallet classes not found. Candidates tried: {}, {}, {}",
                        WALLET_HANDLER_CANDIDATES, WALLET_ITEM_CANDIDATES, WALLET_WRAPPER_CANDIDATES);
                return;
            }

            mWalletHandler_get = safeGetMethod(clsWalletHandler, "get", net.minecraft.world.entity.Entity.class);
            mWalletHandler_getWallet = safeGetMethod(clsWalletHandler, "getWallet");
            mWalletHandler_setChanged = safeGetMethod(clsWalletHandler, "setChanged");

            mWalletItem_isWallet = safeGetMethod(clsWalletItem, "isWallet", ItemStack.class);
            mWalletItem_getDataWrapper = safeGetMethod(clsWalletItem, "getDataWrapper", ItemStack.class);

            mWrapper_getContents = safeGetMethod(clsWalletDataWrapper, "getContents");
            mWrapper_setContents = safeGetMethod(clsWalletDataWrapper, "setContents",
                    net.minecraft.world.Container.class, net.minecraft.world.entity.LivingEntity.class);

            if (areBound()) {
                EZVillagerReroll.LOG().info("[EZVR] LC wallet API bound successfully via reflection.");
            } else {
                EZVillagerReroll.LOG().info("[EZVR] LC wallet API binding incomplete (methods missing). Will fall back if used.");
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().warn("[EZVR] bindReflectionHandles failed: {}", t.toString());
        }
    }

    private static Class<?> tryLoadAny(List<String> candidates) {
        for (String name : candidates) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }

    private static Method safeGetMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            Method m = cls.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private WalletBridge() {}
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\logic\WalletBridge.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\mixin\AbstractVillagerAccessor.java ----
// MainFile: src/main/java/org/z2six/ezvillagerreroll/mixin/AbstractVillagerAccessor.java
        package org.z2six.ezvillagerreroll.mixin;

import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractVillager.class)
public interface AbstractVillagerAccessor {

    @Accessor("offers")
    void ezvr$setOffers(MerchantOffers offers);
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\mixin\AbstractVillagerAccessor.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\mixin\MerchantMenuAccessor.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/mixin/MerchantMenuAccessor.java
        package org.z2six.ezvillagerreroll.mixin;

import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.Merchant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MerchantMenu.class)
public interface MerchantMenuAccessor {

    @Accessor("trader")
    Merchant ezvr$getTrader();
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\mixin\MerchantMenuAccessor.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\mixin\MerchantScreenAccessor.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/mixin/MerchantScreenAccessor.java
        package org.z2six.ezvillagerreroll.mixin;

import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Access private scrolling state on MerchantScreen so we can map visible row -> absolute offer index.
 *
 * Vanilla uses a fixed set of row buttons (0..6) and a scroll offset to decide which offers they represent.
 */
@Mixin(MerchantScreen.class)
public interface MerchantScreenAccessor {

    /**
     * The scroll offset in the offer list. In Mojmap 1.21.x this is typically named "scrollOff".
     * If mappings change, the accessor may fail at runtime, so callers should fall back defensively.
     */
    @Accessor("scrollOff")
    int ezvr$getScrollOff();
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\mixin\MerchantScreenAccessor.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\mixin\MerchantScreenRerollButtonRightClickMixin.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/mixin/MerchantScreenRerollButtonRightClickMixin.java
        package org.z2six.ezvillagerreroll.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MerchantMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.client.ClientRerollCooldownCache;
import org.z2six.ezvillagerreroll.client.ClientUI;

/**
 * RMB on our reroll button opens the auto-search catalog UI.
 * But: block while server cooldown is active.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class MerchantScreenRerollButtonRightClickMixin {

    @Inject(
            method = "mouseClicked(DDI)Z",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void ezvr$mouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        try {
            // RMB only
            if (button != 1) return;

            Minecraft mc = Minecraft.getInstance();
            Screen current = (mc == null) ? null : mc.screen;
            if (!(current instanceof MerchantScreen ms)) return;

            Button reroll = ClientUI.getRerollButtonFor(ms);
            if (reroll == null) return;

            if (!reroll.visible) return;
            if (!reroll.isMouseOver(mouseX, mouseY)) return;

            // NEW: cooldown gate
            int containerId = -1;
            try {
                if (ms.getMenu() instanceof MerchantMenu menu) containerId = menu.containerId;
            } catch (Throwable ignored) {}

            if (containerId >= 0 && ClientRerollCooldownCache.isCoolingDown(containerId)) {
                int rem = ClientRerollCooldownCache.getRemainingTicks(containerId);
                double sec = rem / 20.0;

                EZVillagerReroll.LOG().debug("[EZVR] RMB blocked: cooling down (containerId={} remTicks={})", containerId, rem);

                try {
                    if (mc != null && mc.player != null) {
                        mc.player.displayClientMessage(Component.literal(String.format("Reroll cooldown: %.1fs", sec)), true);
                    }
                } catch (Throwable ignoredMsg) {}

                cir.setReturnValue(true); // consume
                return;
            }

            int villagerEntityId = ClientUI.resolveTraderEntityId(ms);
            EZVillagerReroll.LOG().info("[EZVR] RMB on reroll button -> open catalog (villagerEntityId={})", villagerEntityId);

            ClientUI.openSearchCatalogScreen(ms, villagerEntityId);

            cir.setReturnValue(true); // consume
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] MerchantScreenRerollButtonRightClickMixin failed", t);
        }
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\mixin\MerchantScreenRerollButtonRightClickMixin.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\mixin\MerchantScreenTradeLockRightClickMixin.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/mixin/MerchantScreenTradeLockRightClickMixin.java
        package org.z2six.ezvillagerreroll.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.world.inventory.MerchantMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.network.ClientTradeLockCache;
import org.z2six.ezvillagerreroll.network.Network;
import org.z2six.ezvillagerreroll.network.PacketToggleTradeLock;
import org.z2six.ezvillagerreroll.network.PacketTradeLocks;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Right-click on a trade offer button toggles lock.
 *
 * IMPORTANT:
 * The TradeOfferButton's 'index' is the visible row index (0..6). Absolute offer index is:
 *   absoluteIndex = scrollOffset + rowIndex
 *
 * We compute this consistently for both toggling and rendering.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class MerchantScreenTradeLockRightClickMixin {

    @Unique
    private static final String EZVR_TRADE_BUTTON_CLASS =
            "net.minecraft.client.gui.screens.inventory.MerchantScreen$TradeOfferButton";

    @Inject(
            method = "mouseClicked(DDI)Z",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void ezvr$mouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        try {
            // RMB only
            if (button != 1) return;

            Minecraft mc = Minecraft.getInstance();
            Screen current = (mc == null) ? null : mc.screen;
            if (!(current instanceof MerchantScreen screen)) return;

            AbstractWidget hovered = ezvr$findHoveredTradeButton(screen, mouseX, mouseY);
            if (hovered == null) {
                return;
            }

            int rowIdx = ezvr$readTradeButtonRowIndexReflective(hovered);
            if (rowIdx < 0 || rowIdx > 63) {
                EZVillagerReroll.LOG().debug("[EZVR] RMB MerchantScreen: could not read trade row index (rowIdx={})", rowIdx);
                return;
            }

            int offerCount = ezvr$safeOfferCount(screen);
            int scrollOff = ezvr$getScrollOffsetSafe(screen, offerCount);
            int absoluteIdx = scrollOff + rowIdx;

            // Guard hard: only allow within actual offers
            if (offerCount >= 0 && (absoluteIdx < 0 || absoluteIdx >= offerCount)) {
                EZVillagerReroll.LOG().debug("[EZVR] RMB MerchantScreen: computed absoluteIdx out of range (rowIdx={}, scrollOff={}, absoluteIdx={}, offerCount={})",
                        rowIdx, scrollOff, absoluteIdx, offerCount);
                return;
            }

            if (absoluteIdx > 63) {
                EZVillagerReroll.LOG().warn("[EZVR] RMB MerchantScreen: absolute trade index {} > 63; ignoring for safety.", absoluteIdx);
                return;
            }

            // Send server-authoritative toggle request (server will compute villager + persist mask)
            Network.sendToServer(new PacketToggleTradeLock(absoluteIdx));
            EZVillagerReroll.LOG().debug("[EZVR] Sent PacketToggleTradeLock(absoluteIdx={}) (rowIdx={}, scrollOff={})", absoluteIdx, rowIdx, scrollOff);

            // Optimistic local toggle (visuals) keyed by containerId
            int cid = ezvr$getContainerId(screen);
            if (cid >= 0) {
                long oldMask = ClientTradeLockCache.getMaskForContainer(cid);
                long nextMask = oldMask ^ (1L << absoluteIdx);

                ClientTradeLockCache.set(new PacketTradeLocks(cid, nextMask));

                EZVillagerReroll.LOG().debug("[EZVR] Optimistic mask: containerId={} old={} next={} (absoluteIdx={})",
                        cid, Long.toUnsignedString(oldMask), Long.toUnsignedString(nextMask), absoluteIdx);
            }

            // Consume click so vanilla doesn't treat RMB as something else
            cir.setReturnValue(true);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] MerchantScreenTradeLockRightClickMixin error", t);
        }
    }

    @Unique
    private static int ezvr$getContainerId(MerchantScreen screen) {
        try {
            if (screen == null) return -1;
            if (!(screen.getMenu() instanceof MerchantMenu menu)) return -1;
            return menu.containerId;
        } catch (Throwable t) {
            return -1;
        }
    }

    @Unique
    private static int ezvr$safeOfferCount(MerchantScreen screen) {
        try {
            if (screen == null) return -1;
            if (!(screen.getMenu() instanceof MerchantMenu menu)) return -1;
            var offers = menu.getOffers();
            return offers == null ? 0 : offers.size();
        } catch (Throwable t) {
            return -1;
        }
    }

    @Unique
    private static int ezvr$getScrollOffsetSafe(MerchantScreen screen, int offerCount) {
        try {
            // Prefer accessor (correct field) when available
            try {
                int raw = ((MerchantScreenAccessor) screen).ezvr$getScrollOff();
                return ezvr$clampScroll(raw, offerCount);
            } catch (Throwable ignored) {
                // fall through
            }

            // Fallback reflection: only accept fields with "scroll" in name AND value within max scroll range.
            int maxScroll = Math.max(0, offerCount - 7); // vanilla shows 7 rows
            Class<?> c = screen.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        if (f.getType() != int.class) continue;
                        String n = f.getName();
                        if (n == null) continue;
                        String lower = n.toLowerCase();
                        if (!lower.contains("scroll")) continue;

                        f.setAccessible(true);
                        int v = f.getInt(screen);
                        if (v >= 0 && v <= maxScroll) {
                            return v;
                        }
                    } catch (Throwable ignoredField) {}
                }
                c = c.getSuperclass();
            }

            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    @Unique
    private static int ezvr$clampScroll(int scrollOff, int offerCount) {
        try {
            int maxScroll = Math.max(0, offerCount - 7);
            if (scrollOff < 0) return 0;
            if (scrollOff > maxScroll) return maxScroll;
            return scrollOff;
        } catch (Throwable t) {
            return 0;
        }
    }

    @Unique
    private static AbstractWidget ezvr$findHoveredTradeButton(MerchantScreen screen, double mouseX, double mouseY) {
        try {
            // First try the normal children list (most reliable)
            AbstractWidget w = ezvr$scanChildrenForHoveredTradeButton(screen.children(), mouseX, mouseY);
            if (w != null) return w;

            // Fallback: walk declared fields looking for widget lists
            return ezvr$scanViaReflection(screen, mouseX, mouseY);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ezvr$findHoveredTradeButton failed", t);
            return null;
        }
    }

    @Unique
    private static AbstractWidget ezvr$scanChildrenForHoveredTradeButton(List<? extends GuiEventListener> list, double mouseX, double mouseY) {
        try {
            if (list == null || list.isEmpty()) return null;

            for (GuiEventListener child : list) {
                if (!(child instanceof AbstractWidget w)) continue;
                String cn = w.getClass().getName();
                if (!EZVR_TRADE_BUTTON_CLASS.equals(cn)) continue;
                if (!w.visible) continue;
                if (!w.isMouseOver(mouseX, mouseY)) continue;
                return w;
            }

            return null;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] scanChildrenForHoveredTradeButton failed", t);
            return null;
        }
    }

    @Unique
    private static AbstractWidget ezvr$scanViaReflection(MerchantScreen screen, double mouseX, double mouseY) {
        try {
            Class<?> c = screen.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (!List.class.isAssignableFrom(f.getType())) continue;

                    f.setAccessible(true);
                    Object v = f.get(screen);
                    if (!(v instanceof List<?> list) || list.isEmpty()) continue;

                    for (Object o : list) {
                        if (!(o instanceof AbstractWidget w)) continue;
                        String cn = w.getClass().getName();
                        if (!EZVR_TRADE_BUTTON_CLASS.equals(cn)) continue;
                        if (!w.visible) continue;
                        if (!w.isMouseOver(mouseX, mouseY)) continue;
                        return w;
                    }
                }
                c = c.getSuperclass();
            }
            return null;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] scanViaReflection failed", t);
            return null;
        }
    }

    @Unique
    private static int ezvr$readTradeButtonRowIndexReflective(Object tradeButtonWidget) {
        try {
            Class<?> c = tradeButtonWidget.getClass();

            Field f = null;
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
            return f.getInt(tradeButtonWidget);

        } catch (Throwable t) {
            return -1;
        }
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\mixin\MerchantScreenTradeLockRightClickMixin.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\mixin\VillagerAccessor.java ----
// MainFile: src/main/java/org/z2six/ezvillagerreroll/mixin/VillagerAccessor.java
        package org.z2six.ezvillagerreroll.mixin;

import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Villager.class)
public interface VillagerAccessor {

    @Invoker("updateTrades")
    void ezvr$updateTrades();

    @Invoker("updateSpecialPrices")
    void ezvr$updateSpecialPrices(Player player);
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\mixin\VillagerAccessor.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\mixin\VillagerDisableVanillaAutoTradeRefreshMixin.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/mixin/VillagerDisableVanillaAutoTradeRefreshMixin.java
        package org.z2six.ezvillagerreroll.mixin;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.item.trading.MerchantOffers;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Robustly prevents vanilla from regenerating/resetting villager offers automatically.
 *
 * Problem summary:
 * - Vanilla can call Villager#updateTrades() from multiple code paths:
 *   - periodic AI/tick paths (your original mixin addressed this partially)
 *   - NBT load paths during server restart / chunk load (the missing piece)
 *
 * Critical constraint:
 * - EZVR still needs to be able to reroll trades by invoking updateTrades() manually via your invoker.
 * - Therefore, we do NOT cancel updateTrades() globally.
 * - Instead, we no-op ONLY when updateTrades() is invoked from vanilla "auto" call-sites.
 *
 * Strategy:
 * 1) Intercept updateTrades() call from readAdditionalSaveData:
 *    - This is the most common "restart -> trades changed" trigger.
 *    - We skip ONLY if offers already exist (saved NBT offers were loaded). If offers are empty, we allow vanilla.
 * 2) Intercept updateTrades() call from tick/aiStep/customServerAiStep:
 *    - We skip ONLY for novice villagers with 0 XP *and* non-empty offers (vanilla "refresh until traded" behavior).
 *    - If offers are empty, we allow vanilla to generate initial offers (safer fail-open behavior).
 *
 * Defensive behavior:
 * - Never crash: try/catch around all logic.
 * - Fail open: if anything goes wrong, call the invoker to run vanilla updateTrades().
 * - Debug logs for visibility, but minimal spam.
 */
@Mixin(Villager.class)
public class VillagerDisableVanillaAutoTradeRefreshMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    // -----------------------------------------
    // NBT LOAD PATH (server restart / chunk load)
    // -----------------------------------------

    @Redirect(
            method = "readAdditionalSaveData",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/npc/Villager;updateTrades()V"),
            require = 0
    )
    private void ezvillagerreroll$disableAutoTradeRefresh_readAdditionalSaveData(Villager self) {
        ezvillagerreroll$maybeSkipUpdateTrades(self, "readAdditionalSaveData", /*loadPath=*/true);
    }

    // -----------------------------------------
    // PERIODIC PATHS (daily/periodic/AI refresh)
    // -----------------------------------------

    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/npc/Villager;updateTrades()V"),
            require = 0
    )
    private void ezvillagerreroll$disableAutoTradeRefresh_tick(Villager self) {
        ezvillagerreroll$maybeSkipUpdateTrades(self, "tick", /*loadPath=*/false);
    }

    @Redirect(
            method = "aiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/npc/Villager;updateTrades()V"),
            require = 0
    )
    private void ezvillagerreroll$disableAutoTradeRefresh_aiStep(Villager self) {
        ezvillagerreroll$maybeSkipUpdateTrades(self, "aiStep", /*loadPath=*/false);
    }

    @Redirect(
            method = "customServerAiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/npc/Villager;updateTrades()V"),
            require = 0
    )
    private void ezvillagerreroll$disableAutoTradeRefresh_customServerAiStep(Villager self) {
        ezvillagerreroll$maybeSkipUpdateTrades(self, "customServerAiStep", /*loadPath=*/false);
    }

    // -----------------------------------------
    // Core logic
    // -----------------------------------------

    private static void ezvillagerreroll$maybeSkipUpdateTrades(Villager self, String caller, boolean loadPath) {
        try {
            if (self == null) {
                LOGGER.warn("[EZVR] Intercepted updateTrades() with null villager (caller={}); skipping to avoid crash.", caller);
                return;
            }

            final int id = safeGetId(self);

            // Offers state is the safest guard we can use:
            // - If offers are non-empty, we almost certainly loaded/persisted offers already
            //   and vanilla "refresh" would overwrite/reshuffle them.
            // - If offers are empty, allowing vanilla to generate prevents "no trades" edge cases.
            final MerchantOffers offers = safeGetOffers(self);
            final boolean hasOffers = offers != null && !offers.isEmpty();

            final VillagerData data = self.getVillagerData();
            final int level = (data != null) ? data.getLevel() : -1;
            final int xp = self.getVillagerXp();

            if (loadPath) {
                // Robust fix for "after restarting server, trades reset":
                // If offers were loaded from NBT (non-empty), do not allow vanilla to refresh them on load.
                if (hasOffers) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("[EZVR] Skipping vanilla updateTrades() on NBT load for villager id={} (level={}, xp={}, offers={}).",
                                id, level, xp, offers.size());
                    }
                    return; // NO-OP
                }

                // If offers are empty on load, fail open so villagers still get trades.
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("[EZVR] Allowing updateTrades() on NBT load because offers are empty for villager id={} (level={}, xp={}).",
                            id, level, xp);
                }
                ((VillagerUpdateTradesInvoker) self).ezvillagerreroll$invokeUpdateTrades();
                return;
            }

            // Periodic vanilla refresh behavior typically applies before first trade:
            // novice (level 1) with 0 XP; vanilla can keep refreshing.
            // We only skip if offers already exist, to avoid breaking initial offer generation.
            final boolean isNoviceNeverTraded = (level <= 1 && xp <= 0);

            if (isNoviceNeverTraded && hasOffers) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("[EZVR] Skipping vanilla updateTrades() auto-refresh from {} for villager id={} (level={}, xp={}, offers={}).",
                            caller, id, level, xp, offers.size());
                }
                return; // NO-OP
            }

            // Otherwise, allow vanilla call site to proceed (via invoker).
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[EZVR] Allowing updateTrades() from {} for villager id={} (level={}, xp={}, offersPresent={}).",
                        caller, id, level, xp, hasOffers);
            }

            ((VillagerUpdateTradesInvoker) self).ezvillagerreroll$invokeUpdateTrades();
        } catch (Throwable t) {
            // Fail open: do not risk broken villagers or crash loops.
            int id = -1;
            try {
                id = safeGetId(self);
            } catch (Throwable ignored) {
                // ignore
            }

            LOGGER.warn("[EZVR] Error while intercepting updateTrades() (caller={}, loadPath={}). Falling back to vanilla. Villager id={}.",
                    caller, loadPath, id, t);

            try {
                if (self != null) {
                    ((VillagerUpdateTradesInvoker) self).ezvillagerreroll$invokeUpdateTrades();
                }
            } catch (Throwable t2) {
                LOGGER.warn("[EZVR] Fallback invoker call to updateTrades() also failed; skipping to avoid crash.", t2);
            }
        }
    }

    private static int safeGetId(Villager self) {
        try {
            return self.getId();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static MerchantOffers safeGetOffers(Villager self) {
        try {
            return self.getOffers();
        } catch (Throwable t) {
            return null;
        }
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\mixin\VillagerDisableVanillaAutoTradeRefreshMixin.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\mixin\VillagerUpdateTradesInvoker.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/mixin/VillagerUpdateTradesInvoker.java
        package org.z2six.ezvillagerreroll.mixin;

import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invoker to access Villager#updateTrades(), which is protected.
 */
@Mixin(Villager.class)
public interface VillagerUpdateTradesInvoker {
    @Invoker("updateTrades")
    void ezvillagerreroll$invokeUpdateTrades();
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\mixin\VillagerUpdateTradesInvoker.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\ClientSyncedConfig.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/ClientSyncedConfig.java
        package org.z2six.ezvillagerreroll.network;

import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.util.Arrays;

public final class ClientSyncedConfig {

    public static final class Snapshot {
        public int version;
        public int hash;
        public String costItemOrTag;
        public int[] costsByLevel; // len 6
        public boolean preferWallet;
        public int cooldownTicks;
        public int perVillagerDaily;

        // New (required for client-side displays; server remains authoritative)
        public int freeOffers;
        public int costPerOffer;
        public int maxDeductibleLockedOffers;
        public int autoHourlyThreshold;
        public double autoHourlyDiscountOrIncreasePct;

        public boolean allowAfterTradeUsed;

        @Override
        public String toString() {
            return "Snapshot{" +
                    "version=" + version +
                    ", hash=" + hash +
                    ", costItemOrTag='" + costItemOrTag + '\'' +
                    ", costsByLevel=" + Arrays.toString(costsByLevel) +
                    ", preferWallet=" + preferWallet +
                    ", cooldownTicks=" + cooldownTicks +
                    ", perVillagerDaily=" + perVillagerDaily +
                    ", freeOffers=" + freeOffers +
                    ", costPerOffer=" + costPerOffer +
                    ", maxDeductibleLockedOffers=" + maxDeductibleLockedOffers +
                    ", autoHourlyThreshold=" + autoHourlyThreshold +
                    ", autoHourlyDiscountOrIncreasePct=" + autoHourlyDiscountOrIncreasePct +
                    ", allowAfterTradeUsed=" + allowAfterTradeUsed +
                    '}';
        }
    }

    private static volatile Snapshot last;

    // Your existing method name:
    public static void setFrom(PacketSyncConfig msg) {
        try {
            Snapshot s = new Snapshot();
            s.version = msg.version;
            s.hash = msg.hash;
            s.costItemOrTag = msg.costItemOrTag;
            s.costsByLevel = msg.costsByLevel != null ? msg.costsByLevel.clone() : new int[]{0,0,0,0,0,0};
            if (s.costsByLevel.length != 6) {
                int[] fixed = new int[6];
                for (int i = 0; i < 6 && i < s.costsByLevel.length; i++) fixed[i] = Math.max(0, s.costsByLevel[i]);
                s.costsByLevel = fixed;
            }
            s.preferWallet = msg.preferWallet;
            s.cooldownTicks = msg.cooldownTicks;
            s.perVillagerDaily = msg.perVillagerDaily;

            s.freeOffers = Math.max(0, msg.freeOffers);
            s.costPerOffer = Math.max(0, msg.costPerOffer);
            s.maxDeductibleLockedOffers = Math.max(0, msg.maxDeductibleLockedOffers);
            s.autoHourlyThreshold = Math.max(0, msg.autoHourlyThreshold);
            s.autoHourlyDiscountOrIncreasePct = Math.max(0.0, msg.autoHourlyDiscountOrIncreasePct);

            s.allowAfterTradeUsed = msg.allowAfterTradeUsed;

            last = s;

            EZVillagerReroll.LOG().info("[EZVR] Client received synced SERVER config: {}", s);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Failed to apply synced config on client", t);
        }
    }

    // Network.java calls applyFromServer(...) in your pasted code -> provide it:
    public static void applyFromServer(PacketSyncConfig msg) {
        setFrom(msg);
    }

    public static Snapshot get() {
        return last;
    }

    private ClientSyncedConfig() {}
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\ClientSyncedConfig.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\ClientTooltipCache.java ----
// MainFile: src/main/java/org/z2six/ezvillagerreroll/network/ClientTooltipCache.java
        package org.z2six.ezvillagerreroll.network;

public final class ClientTooltipCache {

    private static volatile PacketTooltipData last;
    private static volatile long lastUpdateMs;

    public static void set(PacketTooltipData data) {
        last = data;
        lastUpdateMs = System.currentTimeMillis();
    }

    public static PacketTooltipData get() {
        return last;
    }

    public static long ageMs() {
        long t = lastUpdateMs;
        return t == 0 ? Long.MAX_VALUE : (System.currentTimeMillis() - t);
    }

    private ClientTooltipCache() {}
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\ClientTooltipCache.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\ClientTradeLockCache.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/ClientTradeLockCache.java
        package org.z2six.ezvillagerreroll.network;

import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientTradeLockCache {

    private static final Map<Integer, Long> MASKS_BY_CONTAINER = new ConcurrentHashMap<>();

    private ClientTradeLockCache() {}

    public static void set(PacketTradeLocks msg) {
        try {
            if (msg == null) return;

            int cid = msg.containerId();
            long mask = msg.mask();

            if (cid < 0) {
                EZVillagerReroll.LOG().warn("[EZVR] ClientTradeLockCache.set: ignoring cid<0 (cid={}, mask={})",
                        cid, Long.toUnsignedString(mask));
                return;
            }

            MASKS_BY_CONTAINER.put(cid, mask);
            EZVillagerReroll.LOG().info("[EZVR] ClientTradeLockCache set: containerId={} mask={}",
                    cid, Long.toUnsignedString(mask));
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ClientTradeLockCache.set failed", t);
        }
    }

    public static long getMaskForContainer(int containerId) {
        try {
            if (containerId < 0) return 0L;
            return MASKS_BY_CONTAINER.getOrDefault(containerId, 0L);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ClientTradeLockCache.getMaskForContainer failed", t);
            return 0L;
        }
    }

    public static void clearContainer(int containerId) {
        try {
            if (containerId < 0) return;
            MASKS_BY_CONTAINER.remove(containerId);
            EZVillagerReroll.LOG().debug("[EZVR] ClientTradeLockCache cleared: containerId={}", containerId);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ClientTradeLockCache.clearContainer failed", t);
        }
    }

    public static void clearAll() {
        try {
            int sz = MASKS_BY_CONTAINER.size();
            MASKS_BY_CONTAINER.clear();
            EZVillagerReroll.LOG().debug("[EZVR] ClientTradeLockCache cleared all ({} entr{}).",
                    sz, sz == 1 ? "y" : "ies");
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ClientTradeLockCache.clearAll failed", t);
        }
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\ClientTradeLockCache.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\Network.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/Network.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.server.TradeLockService;

public final class Network {

    private static final String CLIENT_HANDLERS_CLASS = "org.z2six.ezvillagerreroll.client.ClientNetworkHandlers";
    private static final String CLIENT_NETWORK_CLASS  = "org.z2six.ezvillagerreroll.client.ClientNetwork";

    private Network() {}

    public static void onRegisterPayloadHandlers(final RegisterPayloadHandlersEvent e) {
        try {
            var r = e.registrar("ezvillagerreroll");

            // ---- Serverbound ----
            r.playToServer(PacketTooltipQuery.TYPE, PacketTooltipQuery.STREAM_CODEC,
                    (msg, ctx) -> handleTooltipQueryServer(msg, ctx));
            r.playToServer(PacketRequestReroll.TYPE, PacketRequestReroll.STREAM_CODEC,
                    (msg, ctx) -> handleRerollServer(msg, ctx));

            r.playToServer(PacketTradeLocksQuery.TYPE, PacketTradeLocksQuery.STREAM_CODEC,
                    (msg, ctx) -> handleTradeLocksQueryServer(msg, ctx));
            r.playToServer(PacketToggleTradeLock.TYPE, PacketToggleTradeLock.STREAM_CODEC,
                    (msg, ctx) -> handleToggleTradeLockServer(msg, ctx));

            r.playToServer(PacketSearchCatalogQuery.TYPE, PacketSearchCatalogQuery.STREAM_CODEC,
                    (msg, ctx) -> handleSearchCatalogQueryServer(msg, ctx));
            r.playToServer(PacketStartAutoSearch.TYPE, PacketStartAutoSearch.STREAM_CODEC,
                    (msg, ctx) -> handleStartAutoSearchServer(msg, ctx));
            r.playToServer(PacketCancelAutoSearch.TYPE, PacketCancelAutoSearch.STREAM_CODEC,
                    (msg, ctx) -> handleCancelAutoSearchServer(msg, ctx));
            r.playToServer(PacketContinueAutoSearch.TYPE, PacketContinueAutoSearch.STREAM_CODEC,
                    (msg, ctx) -> handleContinueAutoSearchServer(msg, ctx));

            // NEW: cooldown state query
            r.playToServer(PacketRerollCooldownQuery.TYPE, PacketRerollCooldownQuery.STREAM_CODEC,
                    (msg, ctx) -> handleRerollCooldownQueryServer(msg, ctx));

            // NEW: settlement payment actions (these were missing)
            r.playToServer(PacketPayAutoSearchSettlement.TYPE, PacketPayAutoSearchSettlement.STREAM_CODEC,
                    (msg, ctx) -> handlePayAutoSearchSettlementServer(msg, ctx));
            r.playToServer(PacketDeclineAutoSearchSettlement.TYPE, PacketDeclineAutoSearchSettlement.STREAM_CODEC,
                    (msg, ctx) -> handleDeclineAutoSearchSettlementServer(msg, ctx));

            // ---- Clientbound (must be registered on BOTH sides for handshake) ----
            r.playToClient(PacketTooltipData.TYPE, PacketTooltipData.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onTooltipData", msg, ctx));
            r.playToClient(PacketSyncConfig.TYPE, PacketSyncConfig.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onSyncConfig", msg, ctx));
            r.playToClient(PacketTradeLocks.TYPE, PacketTradeLocks.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onTradeLocks", msg, ctx));

            r.playToClient(PacketSearchCatalogData.TYPE, PacketSearchCatalogData.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onSearchCatalogData", msg, ctx));
            r.playToClient(PacketOpenBusyScreen.TYPE, PacketOpenBusyScreen.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onOpenBusyScreen", msg, ctx));

            // NEW: auto-search completion notification
            r.playToClient(PacketAutoSearchDone.TYPE, PacketAutoSearchDone.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onAutoSearchDone", msg, ctx));

            // NEW: cooldown state update
            r.playToClient(PacketRerollCooldownState.TYPE, PacketRerollCooldownState.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onRerollCooldownState", msg, ctx));

            // NEW: settlement/payment UI packets (these were missing)
            r.playToClient(PacketOpenAutoSearchPaymentScreen.TYPE, PacketOpenAutoSearchPaymentScreen.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onOpenAutoSearchPaymentScreen", msg, ctx));
            r.playToClient(PacketAutoSearchSettlementCleared.TYPE, PacketAutoSearchSettlementCleared.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onAutoSearchSettlementCleared", msg, ctx));

            EZVillagerReroll.LOG().info("[EZVR] Network payloads registered (handshake-safe). distClient={}", isClientDist());
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Network payload registration failed.", t);
        }
    }

    private static void dispatchToClientHandler(String methodName, Object msg, IPayloadContext ctx) {
        try {
            if (!isClientDist()) {
                EZVillagerReroll.LOG().debug("[EZVR] dispatchToClientHandler({}) called on non-client dist; ignoring.", methodName);
                return;
            }

            Class<?> c = Class.forName(CLIENT_HANDLERS_CLASS);
            try {
                c.getMethod(methodName, msg.getClass(), IPayloadContext.class).invoke(null, msg, ctx);
            } catch (NoSuchMethodException ex) {
                c.getMethod(methodName, Object.class, IPayloadContext.class).invoke(null, msg, ctx);
            }
        } catch (ClassNotFoundException cnf) {
            EZVillagerReroll.LOG().error("[EZVR] Missing client handler class {} (cannot handle {}).", CLIENT_HANDLERS_CLASS, methodName);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client handler dispatch failed for {}", methodName, t);
        }
    }

    private static boolean isClientDist() {
        try {
            Class<?> env = Class.forName("net.neoforged.fml.loading.FMLEnvironment");
            Object dist = env.getField("dist").get(null);
            return (boolean) dist.getClass().getMethod("isClient").invoke(dist);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void sendToServer(CustomPacketPayload payload) {
        try {
            if (payload == null) return;

            if (!isClientDist()) {
                EZVillagerReroll.LOG().debug("[EZVR] Network.sendToServer called on non-client dist; dropping {}", payload.type());
                return;
            }

            Class<?> c = Class.forName(CLIENT_NETWORK_CLASS);
            c.getMethod("sendToServer", CustomPacketPayload.class).invoke(null, payload);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Network.sendToServer failed for {}", payload == null ? "null" : payload.getClass().getName(), t);
        }
    }

    public static void sendToServer(PacketRequestReroll msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketTooltipQuery msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketTradeLocksQuery msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketToggleTradeLock msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketSearchCatalogQuery msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketStartAutoSearch msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketCancelAutoSearch msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketContinueAutoSearch msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketRerollCooldownQuery msg) { sendToServer((CustomPacketPayload) msg); }

    // Optional convenience (not required, but consistent):
    public static void sendToServer(PacketPayAutoSearchSettlement msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketDeclineAutoSearchSettlement msg) { sendToServer((CustomPacketPayload) msg); }

    private static void handleTooltipQueryServer(PacketTooltipQuery msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                var player = ctx.player();
                if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;
                var data = org.z2six.ezvillagerreroll.server.TooltipService.computeSnapshot(sp, msg.traderEntityId());
                ctx.reply(data);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] TooltipQuery handler error", t);
            }
        });
    }

    private static void handleTradeLocksQueryServer(PacketTradeLocksQuery msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
                ctx.reply(TradeLockService.computeSnapshot(sp));
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] TradeLocksQuery handler error", t);
            }
        });
    }

    private static void handleToggleTradeLockServer(PacketToggleTradeLock msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleToggleTradeLock(msg, ctx);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] ToggleTradeLock handler error", t);
            }
        });
    }

    private static void handleRerollServer(PacketRequestReroll msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleReroll(msg, ctx);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] Reroll handler error", t);
            }
        });
    }

    private static void handleSearchCatalogQueryServer(PacketSearchCatalogQuery msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleSearchCatalogQuery(msg, ctx);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] SearchCatalogQuery handler error", t);
            }
        });
    }

    private static void handleStartAutoSearchServer(PacketStartAutoSearch msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleStartAutoSearch(msg, ctx);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] StartAutoSearch handler error", t);
            }
        });
    }

    private static void handleCancelAutoSearchServer(PacketCancelAutoSearch msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleCancelAutoSearch(msg, ctx);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] CancelAutoSearch handler error", t);
            }
        });
    }

    private static void handleContinueAutoSearchServer(PacketContinueAutoSearch msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleContinueAutoSearch(msg, ctx);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] ContinueAutoSearch handler error", t);
            }
        });
    }

    private static void handleRerollCooldownQueryServer(PacketRerollCooldownQuery msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleRerollCooldownQuery(msg, ctx);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] CooldownQuery handler error", t);
            }
        });
    }

    private static void handlePayAutoSearchSettlementServer(PacketPayAutoSearchSettlement msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handlePayAutoSearchSettlement(msg, ctx);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] PayAutoSearchSettlement handler error", t);
            }
        });
    }

    private static void handleDeclineAutoSearchSettlementServer(PacketDeclineAutoSearchSettlement msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleDeclineAutoSearchSettlement(msg, ctx);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] DeclineAutoSearchSettlement handler error", t);
            }
        });
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\Network.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketAutoSearchDone.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketAutoSearchDone.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.codec.StreamCodec;
import org.z2six.ezvillagerreroll.Constants;

/**
 * Sent server -> client when an auto-search task completes (a requested item was found).
 * Client uses this to auto-close BusyVillagerScreen if it is open for that villager.
 */
public record PacketAutoSearchDone(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketAutoSearchDone> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "auto_search_done"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketAutoSearchDone> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketAutoSearchDone decode(RegistryFriendlyByteBuf buf) {
            return new PacketAutoSearchDone(buf.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, PacketAutoSearchDone msg) {
            buf.writeVarInt(msg.villagerEntityId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketAutoSearchDone.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketAutoSearchSettlementCleared.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketAutoSearchSettlementCleared.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;

/**
 * Server -> Client: settlement ended (paid or declined) so any payment UI should close.
 */
public record PacketAutoSearchSettlementCleared(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketAutoSearchSettlementCleared> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "auto_search_settlement_cleared"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketAutoSearchSettlementCleared> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketAutoSearchSettlementCleared decode(RegistryFriendlyByteBuf buf) {
            return new PacketAutoSearchSettlementCleared(buf.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, PacketAutoSearchSettlementCleared msg) {
            buf.writeVarInt(msg.villagerEntityId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketAutoSearchSettlementCleared.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketCancelAutoSearch.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketCancelAutoSearch.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;

public record PacketCancelAutoSearch(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketCancelAutoSearch> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cancel_auto_search"));

    public static final StreamCodec<FriendlyByteBuf, PacketCancelAutoSearch> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketCancelAutoSearch decode(FriendlyByteBuf buf) {
            return new PacketCancelAutoSearch(buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketCancelAutoSearch msg) {
            buf.writeVarInt(msg.villagerEntityId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketCancelAutoSearch.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketContinueAutoSearch.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketContinueAutoSearch.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;

/**
 * Client presses "Continue" on the Busy screen.
 * Server does not change state; this is mainly for symmetry/logging and future extension.
 */
public record PacketContinueAutoSearch(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketContinueAutoSearch> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "continue_auto_search"));

    public static final StreamCodec<FriendlyByteBuf, PacketContinueAutoSearch> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketContinueAutoSearch decode(FriendlyByteBuf buf) {
            return new PacketContinueAutoSearch(buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketContinueAutoSearch msg) {
            buf.writeVarInt(msg.villagerEntityId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketContinueAutoSearch.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketDeclineAutoSearchSettlement.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketDeclineAutoSearchSettlement.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;

public record PacketDeclineAutoSearchSettlement(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketDeclineAutoSearchSettlement> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "decline_auto_search_settlement"));

    public static final StreamCodec<FriendlyByteBuf, PacketDeclineAutoSearchSettlement> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketDeclineAutoSearchSettlement decode(FriendlyByteBuf buf) {
            return new PacketDeclineAutoSearchSettlement(buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketDeclineAutoSearchSettlement msg) {
            buf.writeVarInt(msg.villagerEntityId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketDeclineAutoSearchSettlement.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketOpenAutoSearchPaymentScreen.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketOpenAutoSearchPaymentScreen.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

/**
 * Server -> Client: open the "auto-search payment" screen (settlement pending).
 *
 * Client should show:
 * - hourlyCost
 * - elapsedSeconds
 * - finalCost
 * Buttons:
 * - Pay -> send PacketPayAutoSearchSettlement(villagerEntityId)
 * - Cancel -> send PacketDeclineAutoSearchSettlement(villagerEntityId)
 */
public record PacketOpenAutoSearchPaymentScreen(
        int villagerEntityId,
        int hourlyCost,
        int finalCost,
        int elapsedTicks
) implements CustomPacketPayload {

    public static final Type<PacketOpenAutoSearchPaymentScreen> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "open_auto_search_payment"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketOpenAutoSearchPaymentScreen> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketOpenAutoSearchPaymentScreen decode(RegistryFriendlyByteBuf buf) {
            try {
                int id = buf.readVarInt();
                int hourly = buf.readVarInt();
                int fin = buf.readVarInt();
                int ticks = buf.readVarInt();
                return new PacketOpenAutoSearchPaymentScreen(id, Math.max(0, hourly), Math.max(0, fin), Math.max(0, ticks));
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] PacketOpenAutoSearchPaymentScreen decode failed", t);
                return new PacketOpenAutoSearchPaymentScreen(-1, 0, 0, 0);
            }
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, PacketOpenAutoSearchPaymentScreen msg) {
            try {
                buf.writeVarInt(msg.villagerEntityId());
                buf.writeVarInt(Math.max(0, msg.hourlyCost()));
                buf.writeVarInt(Math.max(0, msg.finalCost()));
                buf.writeVarInt(Math.max(0, msg.elapsedTicks()));
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] PacketOpenAutoSearchPaymentScreen encode failed", t);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketOpenAutoSearchPaymentScreen.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketOpenBusyScreen.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketOpenBusyScreen.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.z2six.ezvillagerreroll.Constants;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.util.ArrayList;
import java.util.List;

public record PacketOpenBusyScreen(int villagerEntityId, List<ItemStack> requested) implements CustomPacketPayload {

    public static final Type<PacketOpenBusyScreen> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "open_busy_screen"));

    /**
     * Backwards-friendly alias for call-sites that used "targets()".
     */
    public List<ItemStack> targets() {
        return requested();
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketOpenBusyScreen> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketOpenBusyScreen decode(RegistryFriendlyByteBuf buf) {
            try {
                int id = buf.readVarInt();
                int n = buf.readVarInt();
                if (n < 0) n = 0;
                if (n > 512) n = 512;

                List<ItemStack> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    ItemStack s = ItemStack.STREAM_CODEC.decode(buf);
                    list.add(s == null ? ItemStack.EMPTY : s);
                }
                return new PacketOpenBusyScreen(id, list);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] PacketOpenBusyScreen decode failed", t);
                return new PacketOpenBusyScreen(-1, List.of());
            }
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, PacketOpenBusyScreen msg) {
            try {
                buf.writeVarInt(msg.villagerEntityId());
                List<ItemStack> list = msg.requested() == null ? List.of() : msg.requested();
                int n = Math.min(512, list.size());
                buf.writeVarInt(n);

                for (int i = 0; i < n; i++) {
                    ItemStack s = list.get(i);
                    if (s == null) s = ItemStack.EMPTY;
                    ItemStack.STREAM_CODEC.encode(buf, s);
                }
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] PacketOpenBusyScreen encode failed", t);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketOpenBusyScreen.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketPayAutoSearchSettlement.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketPayAutoSearchSettlement.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;

public record PacketPayAutoSearchSettlement(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketPayAutoSearchSettlement> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "pay_auto_search_settlement"));

    public static final StreamCodec<FriendlyByteBuf, PacketPayAutoSearchSettlement> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketPayAutoSearchSettlement decode(FriendlyByteBuf buf) {
            return new PacketPayAutoSearchSettlement(buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketPayAutoSearchSettlement msg) {
            buf.writeVarInt(msg.villagerEntityId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketPayAutoSearchSettlement.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketRequestReroll.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketRequestReroll.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;

public record PacketRequestReroll() implements CustomPacketPayload {

    public static final Type<PacketRequestReroll> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "req_reroll"));

    public static final StreamCodec<FriendlyByteBuf, PacketRequestReroll> STREAM_CODEC =
            StreamCodec.unit(new PacketRequestReroll());

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketRequestReroll.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketRerollCooldownQuery.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketRerollCooldownQuery.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;

/**
 * Client -> Server: ask for current cooldown remaining ticks for the currently open MerchantMenu villager.
 * Server resolves container + villager server-side and responds with PacketRerollCooldownState.
 */
public record PacketRerollCooldownQuery() implements CustomPacketPayload {

    public static final Type<PacketRerollCooldownQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cooldown_query"));

    public static final StreamCodec<FriendlyByteBuf, PacketRerollCooldownQuery> STREAM_CODEC =
            StreamCodec.unit(new PacketRerollCooldownQuery());

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketRerollCooldownQuery.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketRerollCooldownState.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketRerollCooldownState.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;

/**
 * Server -> Client: authoritative cooldown state for the current MerchantMenu.
 *
 * containerId: ties the state to the open merchant container
 * ticksRemaining: how many ticks until reroll is allowed again (0 means allowed now)
 * cooldownTicksConfigured: server config value (informational; useful for UI/diagnostics)
 */
public record PacketRerollCooldownState(int containerId, int ticksRemaining, int cooldownTicksConfigured) implements CustomPacketPayload {

    public static final Type<PacketRerollCooldownState> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cooldown_state"));

    public static final StreamCodec<FriendlyByteBuf, PacketRerollCooldownState> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketRerollCooldownState decode(FriendlyByteBuf buf) {
            int cid = buf.readVarInt();
            int rem = buf.readVarInt();
            int cfg = buf.readVarInt();
            if (rem < 0) rem = 0;
            if (cfg < 0) cfg = 0;
            return new PacketRerollCooldownState(cid, rem, cfg);
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketRerollCooldownState p) {
            buf.writeVarInt(Math.max(0, p.containerId()));
            buf.writeVarInt(Math.max(0, p.ticksRemaining()));
            buf.writeVarInt(Math.max(0, p.cooldownTicksConfigured()));
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketRerollCooldownState.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketSearchCatalogData.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketSearchCatalogData.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.z2six.ezvillagerreroll.Constants;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.util.ArrayList;
import java.util.List;

public record PacketSearchCatalogData(
        int villagerEntityId,
        List<ItemStack> catalog,
        int offerCount,
        int lockedCount,
        int effectivePaidOffers,
        int manualCost,
        int hourlyCost
) implements CustomPacketPayload {

    public static final Type<PacketSearchCatalogData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "search_catalog_data"));

    /**
     * Keep this in sync with server-side MAX_TOTAL_ITEMS conceptually.
     * This is the transport safety cap.
     */
    private static final int MAX_ITEMS_ON_WIRE = 16384;

    /**
     * Backwards-friendly alias for call-sites that used "items()".
     */
    public List<ItemStack> items() {
        return catalog();
    }

    public static PacketSearchCatalogData minimal(int villagerEntityId, List<ItemStack> catalog) {
        return new PacketSearchCatalogData(villagerEntityId, catalog, -1, -1, -1, -1, -1);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketSearchCatalogData> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketSearchCatalogData decode(RegistryFriendlyByteBuf buf) {
            try {
                int id = buf.readVarInt();
                int n = buf.readVarInt();
                if (n < 0) n = 0;
                if (n > MAX_ITEMS_ON_WIRE) {
                    EZVillagerReroll.LOG().warn("[EZVR] PacketSearchCatalogData.decode: item count {} exceeds cap {}; truncating.",
                            n, MAX_ITEMS_ON_WIRE);
                    n = MAX_ITEMS_ON_WIRE;
                }

                List<ItemStack> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    ItemStack s = ItemStack.STREAM_CODEC.decode(buf);
                    list.add(s == null ? ItemStack.EMPTY : s);
                }

                // Extended fields (newer protocol). If an older server sent only items, reads will fail.
                int offerCount = -1;
                int lockedCount = -1;
                int effectivePaidOffers = -1;
                int manualCost = -1;
                int hourlyCost = -1;

                try { offerCount = buf.readVarInt(); } catch (Throwable ignored) {}
                try { lockedCount = buf.readVarInt(); } catch (Throwable ignored) {}
                try { effectivePaidOffers = buf.readVarInt(); } catch (Throwable ignored) {}
                try { manualCost = buf.readVarInt(); } catch (Throwable ignored) {}
                try { hourlyCost = buf.readVarInt(); } catch (Throwable ignored) {}

                return new PacketSearchCatalogData(id, list, offerCount, lockedCount, effectivePaidOffers, manualCost, hourlyCost);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] PacketSearchCatalogData decode failed", t);
                return new PacketSearchCatalogData(-1, List.of(), -1, -1, -1, -1, -1);
            }
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, PacketSearchCatalogData msg) {
            try {
                buf.writeVarInt(msg.villagerEntityId());
                List<ItemStack> list = msg.catalog() == null ? List.of() : msg.catalog();
                int raw = list.size();
                int n = Math.min(MAX_ITEMS_ON_WIRE, raw);
                buf.writeVarInt(n);

                if (raw > n) {
                    EZVillagerReroll.LOG().warn("[EZVR] PacketSearchCatalogData.encode: truncating catalog {} -> {} (cap).", raw, n);
                }

                for (int i = 0; i < n; i++) {
                    ItemStack s = list.get(i);
                    if (s == null) s = ItemStack.EMPTY;
                    ItemStack.STREAM_CODEC.encode(buf, s);
                }

                // Extended fields (always written by current versions)
                buf.writeVarInt(Math.max(-1, msg.offerCount()));
                buf.writeVarInt(Math.max(-1, msg.lockedCount()));
                buf.writeVarInt(Math.max(-1, msg.effectivePaidOffers()));
                buf.writeVarInt(Math.max(-1, msg.manualCost()));
                buf.writeVarInt(Math.max(-1, msg.hourlyCost()));

            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] PacketSearchCatalogData encode failed", t);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketSearchCatalogData.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketSearchCatalogQuery.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketSearchCatalogQuery.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;

public record PacketSearchCatalogQuery(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketSearchCatalogQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "search_catalog_query"));

    public static final StreamCodec<FriendlyByteBuf, PacketSearchCatalogQuery> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketSearchCatalogQuery decode(FriendlyByteBuf buf) {
            return new PacketSearchCatalogQuery(buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketSearchCatalogQuery msg) {
            buf.writeVarInt(msg.villagerEntityId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketSearchCatalogQuery.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketStartAutoSearch.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketStartAutoSearch.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.z2six.ezvillagerreroll.Constants;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.util.ArrayList;
import java.util.List;

public record PacketStartAutoSearch(int villagerEntityId, List<ItemStack> requested) implements CustomPacketPayload {

    public static final Type<PacketStartAutoSearch> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "start_auto_search"));

    /**
     * Backwards-friendly alias for call-sites that used "targets()".
     */
    public List<ItemStack> targets() {
        return requested();
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketStartAutoSearch> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketStartAutoSearch decode(RegistryFriendlyByteBuf buf) {
            try {
                int id = buf.readVarInt();
                int n = buf.readVarInt();
                if (n < 0) n = 0;
                if (n > 512) n = 512;

                List<ItemStack> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    // RegistryFriendlyByteBuf supports ItemStack codec.
                    ItemStack s = ItemStack.STREAM_CODEC.decode(buf);
                    list.add(s == null ? ItemStack.EMPTY : s);
                }
                return new PacketStartAutoSearch(id, list);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] PacketStartAutoSearch decode failed", t);
                return new PacketStartAutoSearch(-1, List.of());
            }
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, PacketStartAutoSearch msg) {
            try {
                buf.writeVarInt(msg.villagerEntityId());
                List<ItemStack> list = msg.requested() == null ? List.of() : msg.requested();

                int n = Math.min(512, list.size());
                buf.writeVarInt(n);

                for (int i = 0; i < n; i++) {
                    ItemStack s = list.get(i);
                    if (s == null) s = ItemStack.EMPTY;
                    ItemStack.STREAM_CODEC.encode(buf, s);
                }
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] PacketStartAutoSearch encode failed", t);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketStartAutoSearch.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketSyncConfig.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketSyncConfig.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;

public final class PacketSyncConfig implements CustomPacketPayload {

    public static final Type<PacketSyncConfig> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_config"));

    public int version;
    public int hash;
    public String costItemOrTag;
    public int[] costsByLevel; // length 6
    public boolean preferWallet;
    public int cooldownTicks;
    public int perVillagerDaily;

    // New (required client-side for auto-hourly preview & settlement math)
    public int freeOffers;
    public int costPerOffer;
    public int maxDeductibleLockedOffers;
    public int autoHourlyThreshold;
    public double autoHourlyDiscountOrIncreasePct;

    public boolean allowAfterTradeUsed;

    public PacketSyncConfig() {}

    public static final StreamCodec<FriendlyByteBuf, PacketSyncConfig> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketSyncConfig decode(FriendlyByteBuf buf) {
            PacketSyncConfig p = new PacketSyncConfig();
            p.version = buf.readVarInt();
            p.hash = buf.readVarInt();
            p.costItemOrTag = buf.readUtf(256);

            int len = buf.readVarInt();
            if (len < 0) len = 0;
            if (len > 64) len = 64; // hard cap for safety
            p.costsByLevel = new int[len];
            for (int i = 0; i < len; i++) p.costsByLevel[i] = Math.max(0, buf.readVarInt());

            p.preferWallet = buf.readBoolean();
            p.cooldownTicks = buf.readVarInt();
            p.perVillagerDaily = buf.readVarInt();

            // RESERVED slot (kept)
            try { buf.readVarInt(); } catch (Throwable ignored) {}

            // New fields (wrap for backwards safety)
            p.freeOffers = 0;
            p.costPerOffer = 0;
            p.maxDeductibleLockedOffers = 0;
            p.autoHourlyThreshold = 0;
            p.autoHourlyDiscountOrIncreasePct = 0.0;

            try { p.freeOffers = buf.readVarInt(); } catch (Throwable ignored) {}
            try { p.costPerOffer = buf.readVarInt(); } catch (Throwable ignored) {}
            try { p.maxDeductibleLockedOffers = buf.readVarInt(); } catch (Throwable ignored) {}
            try { p.autoHourlyThreshold = buf.readVarInt(); } catch (Throwable ignored) {}
            try { p.autoHourlyDiscountOrIncreasePct = buf.readDouble(); } catch (Throwable ignored) {}

            // Existing final field
            try { p.allowAfterTradeUsed = buf.readBoolean(); }
            catch (Throwable ignored) { p.allowAfterTradeUsed = true; }

            return p;
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketSyncConfig p) {
            buf.writeVarInt(p.version);
            buf.writeVarInt(p.hash);
            buf.writeUtf(p.costItemOrTag == null ? "" : p.costItemOrTag, 256);

            int[] arr = p.costsByLevel == null ? new int[0] : p.costsByLevel;
            buf.writeVarInt(arr.length);
            for (int v : arr) buf.writeVarInt(Math.max(0, v));

            buf.writeBoolean(p.preferWallet);
            buf.writeVarInt(p.cooldownTicks);
            buf.writeVarInt(p.perVillagerDaily);

            buf.writeVarInt(0); // RESERVED for future expansion

            // New fields
            buf.writeVarInt(Math.max(0, p.freeOffers));
            buf.writeVarInt(Math.max(0, p.costPerOffer));
            buf.writeVarInt(Math.max(0, p.maxDeductibleLockedOffers));
            buf.writeVarInt(Math.max(0, p.autoHourlyThreshold));
            buf.writeDouble(Math.max(0.0, p.autoHourlyDiscountOrIncreasePct));

            buf.writeBoolean(p.allowAfterTradeUsed);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketSyncConfig.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketToggleTradeLock.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketToggleTradeLock.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;

/**
 * Client -> Server: toggle lock for a trade index on the CURRENTLY OPEN MerchantMenu.
 *
 * We intentionally do NOT include a trader entity id because on the client the merchant can be
 * a client-side merchant implementation (no backing entity).
 *
 * The server resolves the villager from the player's currently open MerchantMenu.
 */
public record PacketToggleTradeLock(int tradeIndex) implements CustomPacketPayload {

    public static final Type<PacketToggleTradeLock> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "toggle_trade_lock"));

    public static final StreamCodec<FriendlyByteBuf, PacketToggleTradeLock> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketToggleTradeLock::tradeIndex,
                    PacketToggleTradeLock::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketToggleTradeLock.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketTooltipData.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketTooltipData.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;

public final class PacketTooltipData implements CustomPacketPayload {

    public static final Type<PacketTooltipData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "tooltip_data"));

    public static final class Cost {
        public ResourceLocation item;     // null if tag or invalid
        public String itemOrTag;          // exact string from config for display (can be "#tag")
        public int baseCost;              // same as scaledCost here, kept for compatibility
        public int scaledCost;

        // Removed conceptually (we keep field to avoid touching too much UI logic, but it will be null now)
        public Integer nextCostIfUsed;
        public Integer maxCostPossible;

        // NEW: breakdown for offer-based pricing (purely informational)
        public int totalOffers;
        public int lockedOffers;
        public int deductibleLockedOffers;
        public int freeOffers;
        public int paidOffers;
        public int costPerOffer;
    }

    public static final class Afford {
        public boolean canAfford;
        public String source; // "wallet", "inventory", "both", "none"
    }

    public static final class VillagerInfo {
        public int level;
        public int xp;
    }

    public static final class Cap {
        public boolean enabled;
        public int remaining;
        public int cap;
    }

    public static final class Cfg {
        public int version;
        public int hash;
        public boolean preferWallet;
        public boolean freeMode;
        public boolean capEnabled;
    }

    public final Cost cost = new Cost();
    public final Afford afford = new Afford();
    public final VillagerInfo villager = new VillagerInfo();
    public final Cap cap = new Cap();
    public final Cfg cfg = new Cfg();

    public PacketTooltipData() {}

    private static void writeNullableRL(FriendlyByteBuf buf, ResourceLocation rl) {
        buf.writeBoolean(rl != null);
        if (rl != null) buf.writeResourceLocation(rl);
    }

    private static ResourceLocation readNullableRL(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readResourceLocation() : null;
    }

    private static void writeNullableInt(FriendlyByteBuf buf, Integer v) {
        buf.writeBoolean(v != null);
        if (v != null) buf.writeVarInt(v);
    }

    private static Integer readNullableInt(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readVarInt() : null;
    }

    public static final StreamCodec<FriendlyByteBuf, PacketTooltipData> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketTooltipData decode(FriendlyByteBuf buf) {
            var pkt = new PacketTooltipData();

            pkt.cost.item = readNullableRL(buf);
            pkt.cost.itemOrTag = buf.readUtf(128);
            pkt.cost.baseCost = buf.readVarInt();
            pkt.cost.scaledCost = buf.readVarInt();
            pkt.cost.nextCostIfUsed = readNullableInt(buf);
            pkt.cost.maxCostPossible = readNullableInt(buf);

            // NEW fields (breakdown)
            pkt.cost.totalOffers = buf.readVarInt();
            pkt.cost.lockedOffers = buf.readVarInt();
            pkt.cost.deductibleLockedOffers = buf.readVarInt();
            pkt.cost.freeOffers = buf.readVarInt();
            pkt.cost.paidOffers = buf.readVarInt();
            pkt.cost.costPerOffer = buf.readVarInt();

            pkt.afford.canAfford = buf.readBoolean();
            pkt.afford.source = buf.readUtf(16);

            pkt.villager.level = buf.readVarInt();
            pkt.villager.xp = buf.readVarInt();

            pkt.cap.enabled = buf.readBoolean();
            pkt.cap.remaining = buf.readVarInt();
            pkt.cap.cap = buf.readVarInt();

            pkt.cfg.version = buf.readVarInt();
            pkt.cfg.hash = buf.readVarInt();
            pkt.cfg.preferWallet = buf.readBoolean();
            pkt.cfg.freeMode = buf.readBoolean();
            pkt.cfg.capEnabled = buf.readBoolean();

            return pkt;
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketTooltipData pkt) {
            writeNullableRL(buf, pkt.cost.item);
            buf.writeUtf(pkt.cost.itemOrTag == null ? "" : pkt.cost.itemOrTag, 128);
            buf.writeVarInt(pkt.cost.baseCost);
            buf.writeVarInt(pkt.cost.scaledCost);
            writeNullableInt(buf, pkt.cost.nextCostIfUsed);
            writeNullableInt(buf, pkt.cost.maxCostPossible);

            // NEW fields (breakdown)
            buf.writeVarInt(Math.max(0, pkt.cost.totalOffers));
            buf.writeVarInt(Math.max(0, pkt.cost.lockedOffers));
            buf.writeVarInt(Math.max(0, pkt.cost.deductibleLockedOffers));
            buf.writeVarInt(Math.max(0, pkt.cost.freeOffers));
            buf.writeVarInt(Math.max(0, pkt.cost.paidOffers));
            buf.writeVarInt(Math.max(0, pkt.cost.costPerOffer));

            buf.writeBoolean(pkt.afford.canAfford);
            buf.writeUtf(pkt.afford.source == null ? "none" : pkt.afford.source, 16);

            buf.writeVarInt(pkt.villager.level);
            buf.writeVarInt(pkt.villager.xp);

            buf.writeBoolean(pkt.cap.enabled);
            buf.writeVarInt(pkt.cap.remaining);
            buf.writeVarInt(pkt.cap.cap);

            buf.writeVarInt(pkt.cfg.version);
            buf.writeVarInt(pkt.cfg.hash);
            buf.writeBoolean(pkt.cfg.preferWallet);
            buf.writeBoolean(pkt.cfg.freeMode);
            buf.writeBoolean(pkt.cfg.capEnabled);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketTooltipData.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketTooltipQuery.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketTooltipQuery.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;

public record PacketTooltipQuery(int traderEntityId) implements CustomPacketPayload {

    public static final Type<PacketTooltipQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "tooltip_query"));

    public static final StreamCodec<FriendlyByteBuf, PacketTooltipQuery> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketTooltipQuery::traderEntityId,
                    PacketTooltipQuery::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketTooltipQuery.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketTradeLocks.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketTradeLocks.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;

public record PacketTradeLocks(int containerId, long mask) implements CustomPacketPayload {

    public static final Type<PacketTradeLocks> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "trade_locks"));

    public static final StreamCodec<FriendlyByteBuf, PacketTradeLocks> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketTradeLocks decode(FriendlyByteBuf buf) {
            int cid = buf.readVarInt();
            long mask = buf.readLong();
            return new PacketTradeLocks(cid, mask);
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketTradeLocks msg) {
            buf.writeVarInt(msg.containerId());
            buf.writeLong(msg.mask());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketTradeLocks.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketTradeLocksQuery.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketTradeLocksQuery.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;

public record PacketTradeLocksQuery() implements CustomPacketPayload {

    public static final Type<PacketTradeLocksQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "trade_locks_query"));

    public static final StreamCodec<FriendlyByteBuf, PacketTradeLocksQuery> STREAM_CODEC =
            StreamCodec.unit(new PacketTradeLocksQuery());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\PacketTradeLocksQuery.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\ServerHandlers.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/ServerHandlers.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.config.ServerConfig;
import org.z2six.ezvillagerreroll.server.CatalogBuilder;
import org.z2six.ezvillagerreroll.logic.CostUtil;
import org.z2six.ezvillagerreroll.logic.MoneyBridge;
import org.z2six.ezvillagerreroll.logic.RerollExecutor;
import org.z2six.ezvillagerreroll.logic.RerollState;
import org.z2six.ezvillagerreroll.logic.TradeLockState;
import org.z2six.ezvillagerreroll.logic.TradeUtil;
import org.z2six.ezvillagerreroll.logic.WalletBridge;
import org.z2six.ezvillagerreroll.mixin.MerchantMenuAccessor;
import org.z2six.ezvillagerreroll.server.SearchService;
import org.z2six.ezvillagerreroll.server.VillagerOffersSavedData;

import java.util.List;

/**
 * Server-side packet handlers.
 *
 * NOTE:
 * This file is a MERGE of the original ServerHandlers +
 * the new auto-search settlement payment logic.
 */
public final class ServerHandlers {

    private ServerHandlers() {}

    // =========================================================================================
    // EXISTING HANDLERS (UNCHANGED)
    // =========================================================================================

    public static void handleReroll(PacketRequestReroll msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            RerollExecutor.tryReroll(sp);
            sendCooldownStateSnapshot(sp, ctx);
            sendCurrentTradeLocksSnapshot(sp, ctx);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleReroll failed", t);
        }
    }

    public static void handleRerollCooldownQuery(PacketRerollCooldownQuery msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            sendCooldownStateSnapshot(sp, ctx);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleRerollCooldownQuery failed", t);
        }
    }

    public static void handleToggleTradeLock(PacketToggleTradeLock msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            int idx = msg.tradeIndex();
            if (!(sp.containerMenu instanceof MerchantMenu menu)) return;

            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Villager vill)) return;

            long next = TradeLockState.toggle(vill, idx);
            long sanitized = TradeLockState.sanitizeMaskForSize(next, vill.getOffers().size());
            TradeLockState.setMask(vill, sanitized);

            ctx.reply(new PacketTradeLocks(menu.containerId, sanitized));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleToggleTradeLock failed", t);
        }
    }

    public static void handleSearchCatalogQuery(PacketSearchCatalogQuery msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) {
                EZVillagerReroll.LOG().debug("[EZVR] handleSearchCatalogQuery: villager not resolved for entityId={} (player={})",
                        msg.villagerEntityId(), sp.getGameProfile().getName());
                ctx.reply(PacketSearchCatalogData.minimal(msg.villagerEntityId(), List.of()));
                return;
            }

            // Build catalog entries (server side)
            List<net.minecraft.world.item.ItemStack> items;
            try {
                items = CatalogBuilder.buildCatalog(vill);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] handleSearchCatalogQuery: CatalogBuilder.buildCatalog failed (villager={})",
                        vill.getUUID(), t);
                items = List.of();
            }

            // ---- Cost preview computation (must match your config semantics) ----
            // offerCount: total offers currently on villager
            int offerCount = 0;
            try {
                offerCount = (vill.getOffers() == null) ? 0 : Math.max(0, vill.getOffers().size());
            } catch (Throwable ignored) {
                offerCount = 0;
            }

            // lockedCount: current lock mask bits (sanitized to offerCount)
            long lockMask = 0L;
            try {
                lockMask = TradeLockState.getMask(vill);
            } catch (Throwable ignored) {
                lockMask = 0L;
            }

            try {
                long sanitized = TradeLockState.sanitizeMaskForSize(lockMask, offerCount);
                if (sanitized != lockMask) {
                    TradeLockState.setMask(vill, sanitized);
                    EZVillagerReroll.LOG().debug("[EZVR] handleSearchCatalogQuery: sanitized lock mask due to offer size change (villager={} before={} after={} offers={})",
                            vill.getUUID(),
                            Long.toUnsignedString(lockMask),
                            Long.toUnsignedString(sanitized),
                            offerCount);
                    lockMask = sanitized;

                    // Best-effort sync to traders if you already have that service
                    try {
                        org.z2six.ezvillagerreroll.server.TradeLockSyncService.syncToActiveTraders(vill, lockMask);
                    } catch (Throwable syncIgnored) {
                        // soft
                    }
                }
            } catch (Throwable ignored) {
                // keep lockMask as-is
            }

            int lockedCount = 0;
            try {
                lockedCount = Long.bitCount(lockMask);
            } catch (Throwable ignored) {
                lockedCount = 0;
            }

            // deductible locks limited by config
            int maxDeduct = Math.max(0, ServerConfig.maxDeductibleLockedOffers);
            int deductibleLocks = Math.min(lockedCount, maxDeduct);

            // effective offers after deductible locked offers
            int effectiveOffers = Math.max(0, offerCount - deductibleLocks);

            // paid offers after free offers
            int freeOffers = Math.max(0, ServerConfig.freeOffers);
            int effectivePaidOffers = Math.max(0, effectiveOffers - freeOffers);

            // manual cost = paidOffers * costPerOffer (clamped)
            int costPerOffer = Math.max(0, ServerConfig.costPerOffer);
            long manualLong = (long) effectivePaidOffers * (long) costPerOffer;
            if (manualLong < 0L) manualLong = 0L;
            if (manualLong > Integer.MAX_VALUE) manualLong = Integer.MAX_VALUE;
            int manualCost = (int) manualLong;

            // hourly cost preview: use the same centralized logic as settlement creation
            int hourlyCost;
            try {
                hourlyCost = SearchService.computeHourlyCostServer(vill);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().debug("[EZVR] handleSearchCatalogQuery: computeHourlyCostServer failed (soft): {}", t.toString());
                hourlyCost = 0;
            }

            if (EZVillagerReroll.LOG().isDebugEnabled()) {
                EZVillagerReroll.LOG().debug("[EZVR] handleSearchCatalogQuery snapshot: villager={} offers={} locked={} deductibleLocks={} free={} paid={} manual={} hourly={}",
                        vill.getUUID(),
                        offerCount,
                        lockedCount,
                        deductibleLocks,
                        freeOffers,
                        effectivePaidOffers,
                        manualCost,
                        hourlyCost);
            }

            ctx.reply(new PacketSearchCatalogData(
                    vill.getId(),
                    items,
                    offerCount,
                    lockedCount,
                    effectivePaidOffers,
                    manualCost,
                    hourlyCost
            ));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleSearchCatalogQuery failed", t);
        }
    }

    public static void handleStartAutoSearch(PacketStartAutoSearch msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill != null) {
                SearchService.start(sp, vill, msg.targets());
            }
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleStartAutoSearch failed", t);
        }
    }

    public static void handleCancelAutoSearch(PacketCancelAutoSearch msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            SearchService.cancelByEntityId(sp, msg.villagerEntityId());
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleCancelAutoSearch failed", t);
        }
    }

    public static void handleContinueAutoSearch(PacketContinueAutoSearch msg, IPayloadContext ctx) {
        // no-op by design
    }

    // =========================================================================================
    // NEW: AUTO-SEARCH SETTLEMENT HANDLERS
    // =========================================================================================

    public static void handlePayAutoSearchSettlement(PacketPayAutoSearchSettlement msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;

            var settlement = SearchService.getSettlement(vill);
            if (settlement == null) return;

            int cost = SearchService.getSettlementFinalCost(vill);
            if (cost > 0 && !tryChargePlayer(sp, cost)) return;

            SearchService.popSettlement(vill.getUUID());

            var data = VillagerOffersSavedData.get(sp.serverLevel());
            if (data != null) data.capture(vill);

            ctx.reply(new PacketAutoSearchSettlementCleared(vill.getId()));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handlePayAutoSearchSettlement failed", t);
        }
    }

    public static void handleDeclineAutoSearchSettlement(PacketDeclineAutoSearchSettlement msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;

            var settlement = SearchService.getSettlement(vill);
            if (settlement == null) return;

            var data = VillagerOffersSavedData.get(sp.serverLevel());
            if (data != null && data.has(vill.getUUID())) {
                data.apply(vill);
            }

            SearchService.popSettlement(vill.getUUID());
            ctx.reply(new PacketAutoSearchSettlementCleared(vill.getId()));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleDeclineAutoSearchSettlement failed", t);
        }
    }

    // =========================================================================================
    // HELPERS
    // =========================================================================================

    private static boolean tryChargePlayer(ServerPlayer sp, int cost) {
        try {
            boolean isTag = ServerConfig.isTagSpec(ServerConfig.costSpec);
            ResourceLocation id = isTag ? null : ResourceLocation.tryParse(ServerConfig.costSpec);

            if (ServerConfig.preferWallet && id != null && MoneyBridge.isLCPresent()) {
                if (MoneyBridge.tryExtract(sp, id, cost)) return true;
            }

            if (ServerConfig.preferWallet && id != null && WalletBridge.isLCPresent()) {
                if (WalletBridge.tryWithdrawFromWallet(sp, id, cost)) return true;
            }

            Ingredient ing = CostUtil.parseIngredient(ServerConfig.costSpec);
            return ing != Ingredient.EMPTY && CostUtil.consume(sp, ing, cost);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] tryChargePlayer failed (soft): {}", t.toString());
            return false;
        }
    }

    private static Villager resolveVillagerFor(ServerPlayer sp, int entityId) {
        try {
            ServerLevel lvl = sp.serverLevel();
            Entity e = lvl.getEntity(entityId);
            if (e instanceof Villager v) return v;

            if (sp.containerMenu instanceof MerchantMenu menu) {
                var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
                if (trader instanceof Villager v) return v;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void sendCooldownStateSnapshot(ServerPlayer sp, IPayloadContext ctx) {
        try {
            if (!(sp.containerMenu instanceof MerchantMenu menu)) return;
            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Villager vill)) return;

            int remaining = RerollState.cooldownRemainingTicks(sp.serverLevel(), vill);
            ctx.reply(new PacketRerollCooldownState(menu.containerId, remaining, ServerConfig.cooldownTicks));
        } catch (Throwable ignored) {}
    }

    private static void sendCurrentTradeLocksSnapshot(ServerPlayer sp, IPayloadContext ctx) {
        try {
            if (!(sp.containerMenu instanceof MerchantMenu menu)) return;
            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Villager vill)) return;

            long mask = TradeLockState.getMask(vill);
            ctx.reply(new PacketTradeLocks(menu.containerId, mask));
        } catch (Throwable ignored) {}
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\ServerHandlers.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\ServerSync.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/ServerSync.java
        package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.config.ServerConfig;

public final class ServerSync {

    private ServerSync() {}

    public static void syncTo(ServerPlayer sp) {
        try {
            if (sp == null || sp.connection == null) return;

            PacketSyncConfig pkt = new PacketSyncConfig();
            pkt.version = ServerConfig.cfgVersion();
            pkt.hash = ServerConfig.cfgHash();
            pkt.costItemOrTag = (ServerConfig.costSpec == null ? "minecraft:emerald" : ServerConfig.costSpec);

            // ClientSyncedConfig expects len 6 in your current shape.
            int[] lvl5 = ServerConfig.costsByLevel5();
            int[] arr6 = new int[6];
            // indices 0..4 = levels 1..5
            for (int i = 0; i < 5 && i < lvl5.length; i++) arr6[i] = Math.max(0, lvl5[i]);
            arr6[5] = 0; // reserved slot
            pkt.costsByLevel = arr6;

            pkt.preferWallet = ServerConfig.preferWallet;
            pkt.cooldownTicks = ServerConfig.cooldownTicks;
            pkt.perVillagerDaily = ServerConfig.perVillagerDaily;

            // New fields
            pkt.freeOffers = ServerConfig.freeOffers;
            pkt.costPerOffer = ServerConfig.costPerOffer;
            pkt.maxDeductibleLockedOffers = ServerConfig.maxDeductibleLockedOffers;
            pkt.autoHourlyThreshold = ServerConfig.autoHourlyThreshold;
            pkt.autoHourlyDiscountOrIncreasePct = ServerConfig.autoHourlyDiscountOrIncreasePct;

            pkt.allowAfterTradeUsed = ServerConfig.allowAfterTradeUsed;

            sp.connection.send(new ClientboundCustomPayloadPacket(pkt));

            EZVillagerReroll.LOG().debug(
                    "[EZVR] ServerSync: sent config to {} (v={}, hash={})",
                    sp.getGameProfile().getName(), pkt.version, pkt.hash
            );

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ServerSync.syncTo failed", t);
        }
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\network\ServerSync.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\platform\NeoForgePlatformHelper.java ----
        package org.z2six.ezvillagerreroll.platform;

import org.z2six.ezvillagerreroll.platform.services.IPlatformHelper;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;

public class NeoForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {

        return "NeoForge";
    }

    @Override
    public boolean isModLoaded(String modId) {

        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {

        return !FMLLoader.isProduction();
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\platform\NeoForgePlatformHelper.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\server\BusyVillagerBlocker.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/BusyVillagerBlocker.java
        package org.z2six.ezvillagerreroll.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

/**
 * Prevent opening the vanilla Merchant UI while a villager is "busy" searching,
 * and instead open our BusyVillagerScreen.
 *
 * NeoForge 1.21.1 note:
 * - Use gameplay bus (NeoForge.EVENT_BUS).
 * - Use PlayerInteractEvent.EntityInteract (right-click entity).
 */
public final class BusyVillagerBlocker {

    private BusyVillagerBlocker() {}

    public static void register(IEventBus bus) {
        try {
            bus.addListener(BusyVillagerBlocker::onEntityInteract);
            EZVillagerReroll.LOG().info("[EZVR] BusyVillagerBlocker registered.");
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] BusyVillagerBlocker.register failed", t);
        }
    }

    private static void onEntityInteract(PlayerInteractEvent.EntityInteract e) {
        try {
            if (e == null) return;
            if (e.getLevel() == null || e.getLevel().isClientSide) return;

            if (!(e.getEntity() instanceof ServerPlayer sp)) return;
            if (!(e.getTarget() instanceof Villager vill)) return;

            // Only care about main-hand RMB behavior; offhand can produce double-fires on some setups.
            InteractionHand hand = e.getHand();
            if (hand != InteractionHand.MAIN_HAND) {
                EZVillagerReroll.LOG().debug("[EZVR] BusyVillagerBlocker: ignoring offhand interact (player={} villager={})",
                        sp.getGameProfile().getName(), vill.getUUID());
                return;
            }

            if (!SearchService.isBusy(vill)) return;

            // Busy: block vanilla open and open our busy screen.
            EZVillagerReroll.LOG().debug("[EZVR] BusyVillagerBlocker: intercepted interact -> open Busy screen (player={} villager={} entityId={})",
                    sp.getGameProfile().getName(), vill.getUUID(), vill.getId());

            // Cancel the interaction so vanilla doesn't open Merchant UI.
            e.setCanceled(true);
            try {
                e.setCancellationResult(InteractionResult.SUCCESS);
            } catch (Throwable ignored) {
                // Some mappings only support cancel; soft.
            }

            // Open our Busy screen packet to that player.
            try {
                SearchService.openBusyScreen(sp, vill);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] BusyVillagerBlocker: failed opening Busy screen (soft)", t);
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] BusyVillagerBlocker.onEntityInteract failed", t);
        }
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\server\BusyVillagerBlocker.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\server\CatalogBuilder.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/CatalogBuilder.java
        package org.z2six.ezvillagerreroll.server;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Builds a catalog of "possible outputs" a villager could have, not just current offers.
 *
 * Design goals:
 * - Server-safe.
 * - Includes modded items (by using VillagerTrades / listings).
 * - For randomized listings, sample multiple times to capture possibilities.
 * - Librarian special-case: include registry enchantments (vanilla + modded) for enchanted books,
 *   BUT ONLY those that are actually tradeable (via reflective access to a tradeable-flag method).
 *
 * NOTE (important):
 * There is no universal "enumerate all possible offers" API for all modded merchants.
 * Sampling offer generators is the only general mechanism.
 */
public final class CatalogBuilder {

    // Safety caps
    private static final int MAX_TOTAL_ITEMS = 16384;

    // Enough to capture many random listings without being too heavy
    private static final int MAX_SAMPLE_PER_LISTING = 128;

    // Cap how many levels we enumerate per enchantment (mods can go extreme)
    private static final int MAX_ENCHANTABILITY_LEVEL_ENUM = 20;

    // Cached reflective method for "tradeable" flag.
    // We intentionally avoid calling ench.isTradeable() directly to keep compilation compatible
    // with different mappings / NeoForge versions.
    private static volatile Method CACHED_TRADEABLE_METHOD = null;
    private static volatile boolean LOOKED_UP_TRADEABLE_METHOD = false;
    private static volatile boolean LOGGED_TRADEABLE_LOOKUP_FAILURE = false;

    private CatalogBuilder() {}

    public static List<ItemStack> buildCatalog(Villager vill) {
        try {
            if (vill == null) return List.of();

            VillagerData vd = vill.getVillagerData();
            VillagerProfession prof = vd.getProfession();
            int level = Math.max(1, Math.min(5, vd.getLevel()));

            EZVillagerReroll.LOG().info("[EZVR] CatalogBuilder.buildCatalog: villager={} prof={} level={}",
                    vill.getUUID(), prof == null ? "null" : String.valueOf(prof), level);

            // Collect unique outputs by key (includes components to distinguish e.g. enchanted books)
            Map<String, ItemStack> unique = new LinkedHashMap<>();

            // 1) Include CURRENT offers too
            try {
                MerchantOffers offers = vill.getOffers();
                if (offers != null) {
                    for (MerchantOffer o : offers) {
                        if (o == null) continue;
                        ItemStack out = o.getResult();
                        if (out == null || out.isEmpty()) continue;
                        unique.putIfAbsent(keyOf(out), out.copy());
                    }
                }
            } catch (Throwable t) {
                EZVillagerReroll.LOG().warn("[EZVR] CatalogBuilder: failed reading current offers (soft): {}", t.toString());
            }

            // 2) Add possible outputs from VillagerTrades listings (sampling)
            addTradesFromVillagerTrades(vill, prof, level, unique);

            // 3) Special-case librarian enchanted books: registry expansion (tradeable-only)
            tryAddAllEnchantedBooksIfLibrarian(vill, prof, unique);

            // Finalize
            List<ItemStack> out = new ArrayList<>(unique.values());
            if (out.size() > MAX_TOTAL_ITEMS) {
                EZVillagerReroll.LOG().warn("[EZVR] CatalogBuilder.buildCatalog: truncating catalog {} -> MAX_TOTAL_ITEMS={}",
                        out.size(), MAX_TOTAL_ITEMS);
                out = out.subList(0, MAX_TOTAL_ITEMS);
            }

            EZVillagerReroll.LOG().info("[EZVR] CatalogBuilder.buildCatalog: villager={} catalogSize={}",
                    vill.getUUID(), out.size());

            return out;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] CatalogBuilder.buildCatalog failed", t);
            return List.of();
        }
    }

    private static void addTradesFromVillagerTrades(
            Villager vill,
            VillagerProfession prof,
            int level,
            Map<String, ItemStack> unique
    ) {
        try {
            if (vill == null || prof == null) return;

            Object byProfession = null;

            try {
                byProfession = VillagerTrades.TRADES.get(prof);
            } catch (Throwable ignored) {}

            if (byProfession == null) {
                // Fallback: iterate entries to find matching key
                try {
                    for (Object entryObj : VillagerTrades.TRADES.entrySet()) {
                        if (!(entryObj instanceof Map.Entry<?, ?> en)) continue;
                        Object k = en.getKey();
                        if (k == prof) {
                            byProfession = en.getValue();
                            break;
                        }
                    }
                } catch (Throwable ignored) {}
            }

            if (byProfession == null) {
                EZVillagerReroll.LOG().warn("[EZVR] CatalogBuilder: VillagerTrades.TRADES has no entry for prof={}", prof);
                return;
            }

            for (int lvl = 1; lvl <= level; lvl++) {
                VillagerTrades.ItemListing[] listings = getListingsForLevel(byProfession, lvl);
                if (listings == null || listings.length == 0) continue;

                EZVillagerReroll.LOG().debug("[EZVR] CatalogBuilder: prof={} lvl={} listings={}", prof, lvl, listings.length);

                for (VillagerTrades.ItemListing listing : listings) {
                    if (listing == null) continue;

                    int samples = MAX_SAMPLE_PER_LISTING;
                    RandomSource rand = RandomSource.create(0xC0FFEE ^ listing.hashCode() ^ (lvl * 31));

                    for (int i = 0; i < samples; i++) {
                        MerchantOffer offer = safeGetOffer(listing, vill, rand);
                        if (offer == null) continue;

                        ItemStack res = offer.getResult();
                        if (res == null || res.isEmpty()) continue;

                        unique.putIfAbsent(keyOf(res), res.copy());

                        if (unique.size() >= MAX_TOTAL_ITEMS) {
                            EZVillagerReroll.LOG().warn("[EZVR] CatalogBuilder: reached MAX_TOTAL_ITEMS={} while sampling; stopping.", MAX_TOTAL_ITEMS);
                            return;
                        }

                        try {
                            rand.nextInt();
                        } catch (Throwable ignored) {}
                    }
                }
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] CatalogBuilder.addTradesFromVillagerTrades failed", t);
        }
    }

    private static VillagerTrades.ItemListing[] getListingsForLevel(Object byProfession, int lvl) {
        try {
            if (byProfession == null) return null;

            try {
                Method mGet = byProfession.getClass().getMethod("get", int.class);
                Object v = mGet.invoke(byProfession, lvl);
                if (v instanceof VillagerTrades.ItemListing[] arr) return arr;
            } catch (NoSuchMethodException ignored) {
                // fallthrough
            }

            if (byProfession instanceof Map<?, ?> map) {
                Object v = map.get(lvl);
                if (v instanceof VillagerTrades.ItemListing[] arr) return arr;
            }

            return null;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] CatalogBuilder.getListingsForLevel failed (soft): {}", t.toString());
            return null;
        }
    }

    private static MerchantOffer safeGetOffer(VillagerTrades.ItemListing listing, Villager vill, RandomSource rand) {
        try {
            if (listing == null || vill == null || rand == null) return null;

            try {
                return listing.getOffer(vill, rand);
            } catch (Throwable ignored) {}

            try {
                for (Method m : listing.getClass().getMethods()) {
                    if (!m.getName().equals("getOffer")) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 2) {
                        Object offer = m.invoke(listing, vill, rand);
                        return (offer instanceof MerchantOffer mo) ? mo : null;
                    }
                }
            } catch (Throwable ignored) {}

            return null;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] CatalogBuilder.safeGetOffer failed (soft): {}", t.toString());
            return null;
        }
    }

    private static void tryAddAllEnchantedBooksIfLibrarian(Villager vill, VillagerProfession prof, Map<String, ItemStack> unique) {
        try {
            if (vill == null || prof == null) return;
            if (prof != VillagerProfession.LIBRARIAN) return;

            Registry<Enchantment> reg;
            try {
                reg = vill.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().warn("[EZVR] CatalogBuilder: cannot access enchantment registry; skipping enchanted book expansion.");
                return;
            }

            // If we cannot determine tradeability in this environment, do NOT expand the entire registry.
            // This prevents junk/dev/loot-only enchants from showing up.
            if (!ensureTradeableMethodLookedUp()) {
                EZVillagerReroll.LOG().warn("[EZVR] CatalogBuilder: cannot resolve enchantment tradeable-flag method; skipping librarian registry expansion to avoid illegal books.");
                return;
            }

            final int before = unique.size();

            final int[] scanned = new int[] {0};
            final int[] tradeable = new int[] {0};
            final int[] filteredNotTradeable = new int[] {0};
            final int[] attempted = new int[] {0};
            final int[] added = new int[] {0};
            final int[] skippedCapArr = new int[] {0};
            final int[] skippedErrArr = new int[] {0};

            reg.holders().forEach(holder -> {
                try {
                    scanned[0]++;

                    if (unique.size() >= MAX_TOTAL_ITEMS) {
                        skippedCapArr[0]++;
                        return;
                    }

                    Enchantment ench;
                    try {
                        ench = holder.value();
                    } catch (Throwable t) {
                        skippedErrArr[0]++;
                        return;
                    }

                    Boolean isTradeable = isTradeableSafe(ench);
                    if (isTradeable == null) {
                        // Should not happen if ensureTradeableMethodLookedUp() succeeded,
                        // but keep it safe. Fail-closed for registry expansion.
                        filteredNotTradeable[0]++;
                        return;
                    }

                    if (!isTradeable.booleanValue()) {
                        filteredNotTradeable[0]++;
                        return;
                    }
                    tradeable[0]++;

                    int max = 1;
                    try {
                        max = Math.max(1, ench.getMaxLevel());
                    } catch (Throwable ignoredMax) {}

                    if (max > MAX_ENCHANTABILITY_LEVEL_ENUM) {
                        try {
                            EZVillagerReroll.LOG().debug("[EZVR] CatalogBuilder: enchant {} maxLevel={} exceeds cap {}; enumerating 1..{} only.",
                                    safeHolderId(reg, holder), max, MAX_ENCHANTABILITY_LEVEL_ENUM, MAX_ENCHANTABILITY_LEVEL_ENUM);
                        } catch (Throwable ignoredLog) {}
                        max = MAX_ENCHANTABILITY_LEVEL_ENUM;
                    }

                    for (int lvl = 1; lvl <= max; lvl++) {
                        if (unique.size() >= MAX_TOTAL_ITEMS) {
                            skippedCapArr[0]++;
                            break;
                        }

                        attempted[0]++;

                        ItemStack book;
                        try {
                            book = EnchantedBookItem.createForEnchantment(new EnchantmentInstance(holder, lvl));
                        } catch (Throwable t) {
                            skippedErrArr[0]++;
                            continue;
                        }

                        if (book == null || book.isEmpty()) {
                            skippedErrArr[0]++;
                            continue;
                        }

                        String k = keyOf(book);
                        if (unique.putIfAbsent(k, book) == null) {
                            added[0]++;
                        }
                    }
                } catch (Throwable t) {
                    skippedErrArr[0]++;
                }
            });

            EZVillagerReroll.LOG().info(
                    "[EZVR] CatalogBuilder: librarian enchanted-book expansion scannedEnchants={} tradeableEnchants={} filteredNotTradeable={} attemptedBooks={} addedBooks={} skippedCap={} skippedErr={} size {}->{}",
                    scanned[0], tradeable[0], filteredNotTradeable[0], attempted[0], added[0], skippedCapArr[0], skippedErrArr[0],
                    before, unique.size()
            );

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] CatalogBuilder.tryAddAllEnchantedBooksIfLibrarian failed (soft): {}", t.toString());
        }
    }

    /**
     * @return true if we found a usable "tradeable flag" method, false otherwise.
     */
    private static boolean ensureTradeableMethodLookedUp() {
        try {
            if (LOOKED_UP_TRADEABLE_METHOD) return CACHED_TRADEABLE_METHOD != null;

            LOOKED_UP_TRADEABLE_METHOD = true;

            // Try the most likely name first
            Method m = null;
            try {
                m = Enchantment.class.getMethod("isTradeable");
            } catch (Throwable ignored) {}

            // Fallback names (some mappings use different naming)
            if (m == null) {
                try {
                    m = Enchantment.class.getMethod("isTradeableInVillagerTrades");
                } catch (Throwable ignored) {}
            }
            if (m == null) {
                try {
                    m = Enchantment.class.getMethod("canBeTraded");
                } catch (Throwable ignored) {}
            }

            if (m != null) {
                m.setAccessible(true);
                CACHED_TRADEABLE_METHOD = m;
                EZVillagerReroll.LOG().info("[EZVR] CatalogBuilder: resolved tradeable method for enchantments: {}", m.getName());
                return true;
            }

            if (!LOGGED_TRADEABLE_LOOKUP_FAILURE) {
                LOGGED_TRADEABLE_LOOKUP_FAILURE = true;
                EZVillagerReroll.LOG().warn("[EZVR] CatalogBuilder: could not find a tradeable-flag method on Enchantment (tried isTradeable, isTradeableInVillagerTrades, canBeTraded).");
            }

            CACHED_TRADEABLE_METHOD = null;
            return false;

        } catch (Throwable t) {
            if (!LOGGED_TRADEABLE_LOOKUP_FAILURE) {
                LOGGED_TRADEABLE_LOOKUP_FAILURE = true;
                EZVillagerReroll.LOG().warn("[EZVR] CatalogBuilder: tradeable method lookup failed: {}", t.toString());
            }
            CACHED_TRADEABLE_METHOD = null;
            return false;
        }
    }

    /**
     * Returns:
     * - Boolean.TRUE / Boolean.FALSE if we can determine tradeability
     * - null if the environment doesn't expose a usable method (fail-closed for registry expansion)
     */
    private static Boolean isTradeableSafe(Enchantment ench) {
        try {
            if (ench == null) return Boolean.FALSE;

            if (!ensureTradeableMethodLookedUp()) return null;

            Method m = CACHED_TRADEABLE_METHOD;
            if (m == null) return null;

            Object v = m.invoke(ench);
            if (v instanceof Boolean b) return b.booleanValue() ? Boolean.TRUE : Boolean.FALSE;

            // Unexpected return type: treat as unknown
            EZVillagerReroll.LOG().debug("[EZVR] CatalogBuilder: tradeable method returned non-boolean for {} -> {}",
                    ench.getClass().getName(), v == null ? "null" : v.getClass().getName());
            return null;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] CatalogBuilder: tradeable check failed for {} (soft): {}",
                    ench == null ? "null" : ench.getClass().getName(), t.toString());
            return null;
        }
    }

    private static String safeHolderId(Registry<Enchantment> reg, Holder<Enchantment> holder) {
        try {
            if (holder == null) return "null";
            try {
                Enchantment e = holder.value();
                if (e != null && reg != null) {
                    Object key = reg.getKey(e);
                    if (key != null) return String.valueOf(key);
                }
            } catch (Throwable ignored) {}
            return String.valueOf(holder);
        } catch (Throwable t) {
            return "<?>";
        }
    }

    public static String keyOf(ItemStack s) {
        try {
            if (s == null || s.isEmpty()) return "empty";

            String itemPart = String.valueOf(s.getItem());

            String compPart;
            try {
                Object patch = s.getComponentsPatch();
                compPart = (patch == null) ? "noComponents" : patch.toString();
            } catch (Throwable ignored) {
                compPart = s.toString();
            }

            return itemPart + "|" + compPart;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] CatalogBuilder.keyOf failed (soft): {}", t.toString());
            return "err|" + Objects.hashCode(s);
        }
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\server\CatalogBuilder.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\server\SearchSavedData.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/SearchSavedData.java
        package org.z2six.ezvillagerreroll.server;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.util.*;

/**
 * Persistent storage for ongoing villager auto-search tasks AND completed settlements awaiting payment.
 *
 * We store:
 * - activeTasks: villagerUuid -> TaskData
 * - settlements: villagerUuid -> SettlementData
 * - pendingDone: ownerUuid -> list of DoneData (optional delivery when owner logs in)
 */
public final class SearchSavedData extends SavedData {

    public static final String DATA_NAME = "ezvillagerreroll_search_tasks";

    public static final class TaskData {
        public UUID villagerUuid;
        public int villagerEntityId; // best-effort; may be stale across restarts
        public UUID ownerPlayerUuid;

        public long nextRerollGameTime;
        public int cooldownTicks;

        public boolean wasGlowingAtStart;

        public List<ItemStack> requested = new ArrayList<>();
        public Set<String> requestedKeys = new HashSet<>();
    }

    public static final class SettlementData {
        public UUID villagerUuid;
        public int villagerEntityId;
        public UUID ownerPlayerUuid;

        public long startedAtGameTime;
        public long completedAtGameTime;

        public int hourlyCost;
        public int finalCost;

        /**
         * Cached offers as ListTag of wrappers { "v": <MerchantOffer encoded tag> }.
         * This matches the safe pattern used elsewhere (registry-aware decoding will happen server-side).
         */
        public ListTag offers = new ListTag();
    }

    public static final class DoneData {
        public int villagerEntityId;
        public UUID villagerUuid;
        public long completedAtRealMillis;
    }

    private final Map<UUID, TaskData> activeTasks = new LinkedHashMap<>();
    private final Map<UUID, SettlementData> settlements = new LinkedHashMap<>();
    private final Map<UUID, List<DoneData>> pendingDoneByOwner = new LinkedHashMap<>();

    public SearchSavedData() {}

    public Map<UUID, TaskData> activeTasks() {
        return activeTasks;
    }

    public Map<UUID, SettlementData> settlements() {
        return settlements;
    }

    public Map<UUID, List<DoneData>> pendingDoneByOwner() {
        return pendingDoneByOwner;
    }

    // ------------------------------------------------------------
    // Integration hooks
    // ------------------------------------------------------------

    public static void loadIntoSearchService(MinecraftServer server) {
        try {
            if (server == null) return;

            ServerLevel overworld;
            try {
                overworld = server.overworld();
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.loadIntoSearchService: cannot get overworld", t);
                return;
            }
            if (overworld == null) return;

            SearchSavedData data = get(overworld);

            try {
                SearchService.importFromSavedData(server, data);
                EZVillagerReroll.LOG().info("[EZVR] SearchSavedData.loadIntoSearchService: imported activeTasks={} settlements={}",
                        data.activeTasks.size(), data.settlements.size());
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.loadIntoSearchService: import failed", t);
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.loadIntoSearchService failed", t);
        }
    }

    public static void saveFromSearchService(MinecraftServer server) {
        try {
            if (server == null) return;

            ServerLevel overworld;
            try {
                overworld = server.overworld();
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.saveFromSearchService: cannot get overworld", t);
                return;
            }
            if (overworld == null) return;

            SearchSavedData data = get(overworld);

            try {
                SearchService.exportToSavedData(server, data);
                data.setDirty();
                EZVillagerReroll.LOG().info("[EZVR] SearchSavedData.saveFromSearchService: exported activeTasks={} settlements={}",
                        data.activeTasks.size(), data.settlements.size());
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.saveFromSearchService: export failed", t);
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.saveFromSearchService failed", t);
        }
    }

    // ------------------------------------------------------------
    // Loading / Saving
    // ------------------------------------------------------------

    public static SearchSavedData get(ServerLevel overworld) {
        try {
            if (overworld == null) throw new IllegalArgumentException("overworld is null");

            return overworld.getDataStorage().computeIfAbsent(
                    new Factory<>(SearchSavedData::new, SearchSavedData::load),
                    DATA_NAME
            );
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.get failed", t);
            return new SearchSavedData();
        }
    }

    public static SearchSavedData load(CompoundTag tag, HolderLookup.Provider lookup) {
        SearchSavedData data = new SearchSavedData();
        try {
            if (tag == null) return data;

            // Active tasks
            if (tag.contains("activeTasks", Tag.TAG_LIST)) {
                ListTag list = tag.getList("activeTasks", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag t = list.getCompound(i);
                    TaskData td = readTask(t, lookup);
                    if (td == null || td.villagerUuid == null) continue;
                    data.activeTasks.put(td.villagerUuid, td);
                }
            }

            // Settlements
            if (tag.contains("settlements", Tag.TAG_LIST)) {
                ListTag list = tag.getList("settlements", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag t = list.getCompound(i);
                    SettlementData sd = readSettlement(t);
                    if (sd == null || sd.villagerUuid == null) continue;
                    data.settlements.put(sd.villagerUuid, sd);
                }
            }

            // Pending done notifications
            if (tag.contains("pendingDone", Tag.TAG_LIST)) {
                ListTag list = tag.getList("pendingDone", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag root = list.getCompound(i);
                    UUID owner = readUuid(root, "owner");
                    if (owner == null) continue;

                    List<DoneData> dones = new ArrayList<>();
                    if (root.contains("items", Tag.TAG_LIST)) {
                        ListTag items = root.getList("items", Tag.TAG_COMPOUND);
                        for (int j = 0; j < items.size(); j++) {
                            CompoundTag dtag = items.getCompound(j);
                            DoneData dd = new DoneData();
                            dd.villagerEntityId = dtag.getInt("villagerEntityId");
                            dd.villagerUuid = readUuid(dtag, "villagerUuid");
                            dd.completedAtRealMillis = dtag.getLong("completedAtRealMillis");
                            if (dd.villagerUuid != null) dones.add(dd);
                        }
                    }
                    if (!dones.isEmpty()) data.pendingDoneByOwner.put(owner, dones);
                }
            }

            EZVillagerReroll.LOG().info("[EZVR] SearchSavedData loaded: activeTasks={} settlements={} pendingDoneOwners={}",
                    data.activeTasks.size(), data.settlements.size(), data.pendingDoneByOwner.size());

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.load failed", t);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        try {
            if (tag == null) tag = new CompoundTag();

            // Active tasks
            ListTag active = new ListTag();
            for (TaskData td : activeTasks.values()) {
                CompoundTag t = writeTask(td, lookup);
                if (t != null) active.add(t);
            }
            tag.put("activeTasks", active);

            // Settlements
            ListTag settles = new ListTag();
            for (SettlementData sd : settlements.values()) {
                CompoundTag t = writeSettlement(sd);
                if (t != null) settles.add(t);
            }
            tag.put("settlements", settles);

            // Pending done
            ListTag pending = new ListTag();
            for (Map.Entry<UUID, List<DoneData>> en : pendingDoneByOwner.entrySet()) {
                UUID owner = en.getKey();
                List<DoneData> list = en.getValue();
                if (owner == null || list == null || list.isEmpty()) continue;

                CompoundTag root = new CompoundTag();
                writeUuid(root, "owner", owner);

                ListTag items = new ListTag();
                for (DoneData dd : list) {
                    if (dd == null || dd.villagerUuid == null) continue;
                    CompoundTag dtag = new CompoundTag();
                    dtag.putInt("villagerEntityId", dd.villagerEntityId);
                    writeUuid(dtag, "villagerUuid", dd.villagerUuid);
                    dtag.putLong("completedAtRealMillis", dd.completedAtRealMillis);
                    items.add(dtag);
                }
                root.put("items", items);
                pending.add(root);
            }
            tag.put("pendingDone", pending);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.save failed", t);
        }
        return tag;
    }

    // ------------------------------------------------------------
    // Task (de)serialization helpers
    // ------------------------------------------------------------

    private static TaskData readTask(CompoundTag t, HolderLookup.Provider lookup) {
        try {
            if (t == null) return null;

            TaskData td = new TaskData();
            td.villagerUuid = readUuid(t, "villagerUuid");
            td.villagerEntityId = t.getInt("villagerEntityId");
            td.ownerPlayerUuid = readUuid(t, "ownerPlayerUuid");

            td.nextRerollGameTime = t.getLong("nextRerollGameTime");
            td.cooldownTicks = Math.max(1, t.getInt("cooldownTicks"));

            td.wasGlowingAtStart = t.getBoolean("wasGlowingAtStart");

            td.requested.clear();
            if (t.contains("requested", Tag.TAG_LIST)) {
                ListTag req = t.getList("requested", Tag.TAG_COMPOUND);
                int n = Math.min(4096, req.size());
                for (int i = 0; i < n; i++) {
                    CompoundTag st = req.getCompound(i);
                    ItemStack stack;
                    try {
                        stack = ItemStack.parseOptional(lookup, st);
                    } catch (Throwable parseErr) {
                        stack = ItemStack.EMPTY;
                    }
                    if (stack == null) stack = ItemStack.EMPTY;
                    if (!stack.isEmpty()) td.requested.add(stack);
                }
            }

            td.requestedKeys.clear();
            if (t.contains("requestedKeys", Tag.TAG_LIST)) {
                ListTag keys = t.getList("requestedKeys", Tag.TAG_STRING);
                for (int i = 0; i < keys.size(); i++) {
                    String s = keys.getString(i);
                    if (s != null && !s.isBlank()) td.requestedKeys.add(s);
                }
            } else {
                for (ItemStack s : td.requested) {
                    if (s == null || s.isEmpty()) continue;
                    td.requestedKeys.add(CatalogBuilder.keyOf(s));
                }
            }

            if (td.villagerUuid == null || td.ownerPlayerUuid == null || td.requestedKeys.isEmpty()) {
                EZVillagerReroll.LOG().warn("[EZVR] SearchSavedData.readTask: invalid record; skipping (villagerUuid={}, owner={}, keys={})",
                        td.villagerUuid, td.ownerPlayerUuid, td.requestedKeys.size());
                return null;
            }

            return td;

        } catch (Throwable e) {
            EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.readTask failed", e);
            return null;
        }
    }

    private static CompoundTag writeTask(TaskData td, HolderLookup.Provider lookup) {
        try {
            if (td == null || td.villagerUuid == null || td.ownerPlayerUuid == null) return null;

            CompoundTag t = new CompoundTag();
            writeUuid(t, "villagerUuid", td.villagerUuid);
            t.putInt("villagerEntityId", td.villagerEntityId);
            writeUuid(t, "ownerPlayerUuid", td.ownerPlayerUuid);

            t.putLong("nextRerollGameTime", td.nextRerollGameTime);
            t.putInt("cooldownTicks", Math.max(1, td.cooldownTicks));

            t.putBoolean("wasGlowingAtStart", td.wasGlowingAtStart);

            ListTag req = new ListTag();
            int n = td.requested == null ? 0 : Math.min(4096, td.requested.size());
            for (int i = 0; i < n; i++) {
                ItemStack s = td.requested.get(i);
                if (s == null || s.isEmpty()) continue;

                try {
                    Tag tag = s.saveOptional(lookup);
                    if (tag instanceof CompoundTag st) {
                        req.add(st);
                    } else if (tag != null) {
                        EZVillagerReroll.LOG().debug(
                                "[EZVR] SearchSavedData.writeTask: saveOptional returned non-compound Tag type={} for stack={}, skipping.",
                                tag.getClass().getName(),
                                String.valueOf(s)
                        );
                    }
                } catch (Throwable saveErr) {
                    EZVillagerReroll.LOG().debug("[EZVR] SearchSavedData.writeTask: failed to save stack (soft): {}", saveErr.toString());
                }
            }
            t.put("requested", req);

            ListTag keys = new ListTag();
            if (td.requestedKeys != null) {
                for (String k : td.requestedKeys) {
                    if (k == null || k.isBlank()) continue;
                    keys.add(net.minecraft.nbt.StringTag.valueOf(k));
                }
            }
            t.put("requestedKeys", keys);

            return t;

        } catch (Throwable e) {
            EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.writeTask failed", e);
            return null;
        }
    }

    private static SettlementData readSettlement(CompoundTag t) {
        try {
            if (t == null) return null;

            SettlementData sd = new SettlementData();
            sd.villagerUuid = readUuid(t, "villagerUuid");
            sd.villagerEntityId = t.getInt("villagerEntityId");
            sd.ownerPlayerUuid = readUuid(t, "ownerPlayerUuid");

            sd.startedAtGameTime = t.getLong("startedAtGameTime");
            sd.completedAtGameTime = t.getLong("completedAtGameTime");

            sd.hourlyCost = Math.max(0, t.getInt("hourlyCost"));
            sd.finalCost = Math.max(0, t.getInt("finalCost"));

            sd.offers = new ListTag();
            if (t.contains("offers", Tag.TAG_LIST)) {
                ListTag list = t.getList("offers", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    try {
                        CompoundTag wrap = list.getCompound(i);
                        if (wrap != null) sd.offers.add(wrap.copy());
                    } catch (Throwable ignored) {}
                }
            }

            if (sd.villagerUuid == null) return null;
            return sd;
        } catch (Throwable e) {
            EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.readSettlement failed", e);
            return null;
        }
    }

    private static CompoundTag writeSettlement(SettlementData sd) {
        try {
            if (sd == null || sd.villagerUuid == null) return null;

            CompoundTag t = new CompoundTag();
            writeUuid(t, "villagerUuid", sd.villagerUuid);
            t.putInt("villagerEntityId", sd.villagerEntityId);
            if (sd.ownerPlayerUuid != null) writeUuid(t, "ownerPlayerUuid", sd.ownerPlayerUuid);

            t.putLong("startedAtGameTime", sd.startedAtGameTime);
            t.putLong("completedAtGameTime", sd.completedAtGameTime);

            t.putInt("hourlyCost", Math.max(0, sd.hourlyCost));
            t.putInt("finalCost", Math.max(0, sd.finalCost));

            ListTag copy = new ListTag();
            if (sd.offers != null) {
                for (int i = 0; i < sd.offers.size(); i++) {
                    try {
                        CompoundTag wrap = sd.offers.getCompound(i);
                        if (wrap != null) copy.add(wrap.copy());
                    } catch (Throwable ignored) {}
                }
            }
            t.put("offers", copy);

            return t;

        } catch (Throwable e) {
            EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.writeSettlement failed", e);
            return null;
        }
    }

    private static UUID readUuid(CompoundTag tag, String key) {
        try {
            if (tag == null || key == null) return null;
            if (!tag.contains(key + "Most", Tag.TAG_LONG) || !tag.contains(key + "Least", Tag.TAG_LONG)) return null;
            long most = tag.getLong(key + "Most");
            long least = tag.getLong(key + "Least");
            return new UUID(most, least);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeUuid(CompoundTag tag, String key, UUID uuid) {
        try {
            if (tag == null || key == null || uuid == null) return;
            tag.putLong(key + "Most", uuid.getMostSignificantBits());
            tag.putLong(key + "Least", uuid.getLeastSignificantBits());
        } catch (Throwable ignored) {}
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\server\SearchSavedData.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\server\SearchService.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/SearchService.java
        package org.z2six.ezvillagerreroll.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.config.ServerConfig;
import org.z2six.ezvillagerreroll.logic.TradeLockState;
import org.z2six.ezvillagerreroll.logic.TradeUtil;
import org.z2six.ezvillagerreroll.network.PacketAutoSearchDone;
import org.z2six.ezvillagerreroll.network.PacketOpenAutoSearchPaymentScreen;
import org.z2six.ezvillagerreroll.network.PacketOpenBusyScreen;

import java.util.*;
        import java.util.concurrent.ConcurrentHashMap;

public final class SearchService {

    private SearchService() {}

    /**
     * This multiplier defines "base hourly" from manual cost.
     * Your example: manual 48 -> hourly 480 (x10).
     */
    private static final int AUTO_HOURLY_BASE_MULTIPLIER = 10;

    private static final class Task {
        final UUID villagerUuid;
        final int villagerEntityId;
        final UUID ownerPlayerUuid;
        final List<ItemStack> requested;
        final Set<String> requestedKeys;

        long startedAtGameTime;

        long nextRerollGameTime;
        int cooldownTicks;

        Task(Villager vill, ServerPlayer owner, List<ItemStack> requested, long now, int cooldownTicks) {
            this(
                    vill == null ? null : vill.getUUID(),
                    vill == null ? -1 : vill.getId(),
                    owner == null ? null : owner.getUUID(),
                    requested,
                    now,
                    now + Math.max(1, cooldownTicks),
                    Math.max(1, cooldownTicks)
            );
        }

        Task(UUID villagerUuid, int villagerEntityId, UUID ownerPlayerUuid, List<ItemStack> requested, long startedAtGameTime, long nextRerollGameTime, int cooldownTicks) {
            this.villagerUuid = villagerUuid;
            this.villagerEntityId = villagerEntityId;
            this.ownerPlayerUuid = ownerPlayerUuid;
            this.requested = requested == null ? List.of() : new ArrayList<>(requested);

            Set<String> keys = new HashSet<>();
            for (ItemStack s : this.requested) {
                if (s == null || s.isEmpty()) continue;
                keys.add(CatalogBuilder.keyOf(s));
            }
            this.requestedKeys = keys;

            this.startedAtGameTime = Math.max(0L, startedAtGameTime);
            this.nextRerollGameTime = nextRerollGameTime;
            this.cooldownTicks = Math.max(1, cooldownTicks);
        }
    }

    private static final class Settlement {
        final UUID villagerUuid;
        final int villagerEntityId;
        final UUID ownerPlayerUuid;

        final long startedAtGameTime;
        final long completedAtGameTime;

        final int hourlyCost;
        final int finalCost;

        final ListTag offersTag; // cached offers

        Settlement(UUID villagerUuid, int villagerEntityId, UUID ownerPlayerUuid,
                   long startedAtGameTime, long completedAtGameTime,
                   int hourlyCost, int finalCost,
                   ListTag offersTag) {
            this.villagerUuid = villagerUuid;
            this.villagerEntityId = villagerEntityId;
            this.ownerPlayerUuid = ownerPlayerUuid;
            this.startedAtGameTime = startedAtGameTime;
            this.completedAtGameTime = completedAtGameTime;
            this.hourlyCost = Math.max(0, hourlyCost);
            this.finalCost = Math.max(0, finalCost);
            this.offersTag = offersTag == null ? new ListTag() : offersTag;
        }
    }

    private static final Map<UUID, Task> TASKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Settlement> SETTLEMENTS = new ConcurrentHashMap<>();

    // Vanilla glow is not per-player; we approximate by enabling glow only when someone is near.
    private static final double GLOW_RANGE_BLOCKS = 6.0;
    private static final double GLOW_RANGE_SQR = GLOW_RANGE_BLOCKS * GLOW_RANGE_BLOCKS;

    public static boolean isBusy(Villager vill) {
        try {
            if (vill == null) return false;
            UUID id = vill.getUUID();
            return TASKS.containsKey(id) || SETTLEMENTS.containsKey(id);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isAwaitingPayment(Villager vill) {
        try {
            if (vill == null) return false;
            return SETTLEMENTS.containsKey(vill.getUUID());
        } catch (Throwable t) {
            return false;
        }
    }

    public static Settlement getSettlement(Villager vill) {
        try {
            if (vill == null) return null;
            return SETTLEMENTS.get(vill.getUUID());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Called from SearchSavedData on server start.
     * Imports tasks and settlements into memory.
     */
    public static void importFromSavedData(MinecraftServer server, SearchSavedData data) {
        try {
            if (server == null || data == null) return;

            TASKS.clear();
            SETTLEMENTS.clear();

            int imported = 0;
            int skipped = 0;

            for (SearchSavedData.TaskData td : data.activeTasks().values()) {
                try {
                    if (td == null || td.villagerUuid == null || td.ownerPlayerUuid == null) {
                        skipped++;
                        continue;
                    }
                    if (td.requestedKeys == null || td.requestedKeys.isEmpty()) {
                        Set<String> keys = new HashSet<>();
                        if (td.requested != null) {
                            for (ItemStack s : td.requested) {
                                if (s == null || s.isEmpty()) continue;
                                keys.add(CatalogBuilder.keyOf(s));
                            }
                        }
                        td.requestedKeys = keys;
                    }
                    if (td.requestedKeys == null || td.requestedKeys.isEmpty()) {
                        skipped++;
                        continue;
                    }

                    long next = td.nextRerollGameTime;
                    int cd = Math.max(1, td.cooldownTicks);
                    if (next < 0) next = 0;

                    // We didn't previously store startedAtGameTime; best effort: set to (next - cd)
                    long started = Math.max(0L, next - cd);

                    Task t = new Task(
                            td.villagerUuid,
                            td.villagerEntityId,
                            td.ownerPlayerUuid,
                            td.requested,
                            started,
                            next,
                            cd
                    );

                    TASKS.put(td.villagerUuid, t);
                    imported++;

                } catch (Throwable each) {
                    skipped++;
                }
            }

            int settleImported = 0;
            int settleSkipped = 0;

            for (SearchSavedData.SettlementData sd : data.settlements().values()) {
                try {
                    if (sd == null || sd.villagerUuid == null) {
                        settleSkipped++;
                        continue;
                    }
                    Settlement s = new Settlement(
                            sd.villagerUuid,
                            sd.villagerEntityId,
                            sd.ownerPlayerUuid,
                            Math.max(0L, sd.startedAtGameTime),
                            Math.max(0L, sd.completedAtGameTime),
                            Math.max(0, sd.hourlyCost),
                            Math.max(0, sd.finalCost),
                            (sd.offers == null ? new ListTag() : sd.offers)
                    );
                    SETTLEMENTS.put(sd.villagerUuid, s);
                    settleImported++;
                } catch (Throwable ignored) {
                    settleSkipped++;
                }
            }

            EZVillagerReroll.LOG().info("[EZVR] SearchService.importFromSavedData: importedTasks={} skippedTasks={} importedSettlements={} skippedSettlements={}",
                    imported, skipped, settleImported, settleSkipped);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchService.importFromSavedData failed", t);
        }
    }

    /**
     * Called from SearchSavedData on server stop.
     * Exports tasks and settlements.
     */
    public static void exportToSavedData(MinecraftServer server, SearchSavedData data) {
        try {
            if (server == null || data == null) return;

            data.activeTasks().clear();
            data.settlements().clear();

            int exported = 0;

            for (Task t : TASKS.values()) {
                try {
                    if (t == null || t.villagerUuid == null || t.ownerPlayerUuid == null) continue;
                    if (t.requestedKeys == null || t.requestedKeys.isEmpty()) continue;

                    SearchSavedData.TaskData td = new SearchSavedData.TaskData();
                    td.villagerUuid = t.villagerUuid;
                    td.villagerEntityId = t.villagerEntityId;
                    td.ownerPlayerUuid = t.ownerPlayerUuid;
                    td.nextRerollGameTime = t.nextRerollGameTime;
                    td.cooldownTicks = Math.max(1, t.cooldownTicks);

                    td.requested = new ArrayList<>();
                    for (ItemStack s : t.requested) {
                        if (s == null || s.isEmpty()) continue;
                        td.requested.add(s.copy());
                    }

                    td.requestedKeys = new HashSet<>(t.requestedKeys);

                    td.wasGlowingAtStart = false;
                    try {
                        Villager vill = resolveVillagerByUuid(server, t.villagerUuid);
                        if (vill != null) td.wasGlowingAtStart = vill.isCurrentlyGlowing();
                    } catch (Throwable ignored) {}

                    data.activeTasks().put(td.villagerUuid, td);
                    exported++;

                } catch (Throwable ignoredEach) {}
            }

            int settleExported = 0;

            for (Settlement s : SETTLEMENTS.values()) {
                try {
                    if (s == null || s.villagerUuid == null) continue;

                    SearchSavedData.SettlementData sd = new SearchSavedData.SettlementData();
                    sd.villagerUuid = s.villagerUuid;
                    sd.villagerEntityId = s.villagerEntityId;
                    sd.ownerPlayerUuid = s.ownerPlayerUuid;

                    sd.startedAtGameTime = s.startedAtGameTime;
                    sd.completedAtGameTime = s.completedAtGameTime;

                    sd.hourlyCost = Math.max(0, s.hourlyCost);
                    sd.finalCost = Math.max(0, s.finalCost);

                    sd.offers = new ListTag();
                    if (s.offersTag != null) {
                        for (int i = 0; i < s.offersTag.size(); i++) {
                            try {
                                CompoundTag wrap = s.offersTag.getCompound(i);
                                if (wrap != null) sd.offers.add(wrap.copy());
                            } catch (Throwable ignored) {}
                        }
                    }

                    data.settlements().put(sd.villagerUuid, sd);
                    settleExported++;
                } catch (Throwable ignored) {}
            }

            EZVillagerReroll.LOG().info("[EZVR] SearchService.exportToSavedData: exportedTasks={} exportedSettlements={}", exported, settleExported);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchService.exportToSavedData failed", t);
        }
    }

    public static void start(ServerPlayer sp, Villager vill, List<ItemStack> requested) {
        try {
            if (sp == null || vill == null) return;

            // If settlement pending, do not allow starting a new search (avoid state corruption).
            if (SETTLEMENTS.containsKey(vill.getUUID())) {
                EZVillagerReroll.LOG().warn("[EZVR] SearchService.start refused: settlement pending (player={} villager={})",
                        sp.getGameProfile().getName(), vill.getUUID());
                return;
            }

            int cooldown = Math.max(1, ServerConfig.cooldownTicks);
            long now = vill.level().getGameTime();

            Task t = new Task(vill, sp, requested, now, cooldown);

            if (t.requestedKeys.isEmpty()) {
                EZVillagerReroll.LOG().warn("[EZVR] SearchService.start: requested list empty -> ignoring (player={} villager={})",
                        sp.getGameProfile().getName(), vill.getUUID());
                return;
            }

            TASKS.put(vill.getUUID(), t);

            EZVillagerReroll.LOG().info("[EZVR] Auto-search START: player={} villager={} entityId={} requested={} cooldownTicks={}",
                    sp.getGameProfile().getName(),
                    vill.getUUID(),
                    vill.getId(),
                    t.requestedKeys.size(),
                    cooldown
            );

            try {
                updateGlowForBusyVillager(vill, sp.server);
            } catch (Throwable ignored) {}

            // Immediate match check (fast path)
            if (containsAnyRequested(vill, t.requestedKeys)) {
                EZVillagerReroll.LOG().info("[EZVR] Auto-search DONE (already matched): villager={} entityId={} requestedKeys={}",
                        vill.getUUID(), vill.getId(), t.requestedKeys.size());
                TASKS.remove(vill.getUUID());

                trySetVillagerGlow(vill, false);

                // Create settlement immediately (elapsed may be 0)
                createSettlementAndNotify(serverOf(sp), vill, t, now);
            }

        } catch (Throwable e) {
            EZVillagerReroll.LOG().error("[EZVR] SearchService.start failed", e);
        }
    }

    public static void cancelByEntityId(ServerPlayer sp, int villagerEntityId) {
        try {
            if (sp == null) return;

            Villager vill = resolveVillagerByEntityId(sp.serverLevel(), villagerEntityId);
            if (vill == null) {
                EZVillagerReroll.LOG().warn("[EZVR] cancelByEntityId: villager not found (entityId={}, player={})",
                        villagerEntityId, sp.getGameProfile().getName());
                return;
            }

            // Only cancels active search task; settlement cancel must be done via decline packet.
            Task removed = TASKS.remove(vill.getUUID());
            if (removed != null) {
                EZVillagerReroll.LOG().info("[EZVR] Auto-search CANCEL: player={} villager={} entityId={}",
                        sp.getGameProfile().getName(), vill.getUUID(), vill.getId());
            }

            trySetVillagerGlow(vill, false);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] cancelByEntityId failed", t);
        }
    }

    public static void openBusyScreen(ServerPlayer sp, Villager vill) {
        try {
            if (sp == null || vill == null) return;

            // If settlement pending, open payment screen instead of busy screen.
            Settlement settle = SETTLEMENTS.get(vill.getUUID());
            if (settle != null) {
                int elapsedTicks = (int) Math.max(0L, settle.completedAtGameTime - settle.startedAtGameTime);
                try {
                    sp.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                            new PacketOpenAutoSearchPaymentScreen(vill.getId(), settle.hourlyCost, settle.finalCost, elapsedTicks)
                    ));
                    EZVillagerReroll.LOG().debug("[EZVR] openBusyScreen: sent PacketOpenAutoSearchPaymentScreen (player={} villagerEntityId={} hourly={} final={} elapsedTicks={})",
                            sp.getGameProfile().getName(), vill.getId(), settle.hourlyCost, settle.finalCost, elapsedTicks);
                } catch (Throwable sendErr) {
                    EZVillagerReroll.LOG().error("[EZVR] Failed to send payment screen packet (player={} villager={})",
                            sp.getGameProfile().getName(), vill.getUUID(), sendErr);
                }
                return;
            }

            Task t = TASKS.get(vill.getUUID());
            if (t == null) {
                EZVillagerReroll.LOG().debug("[EZVR] openBusyScreen: villager not busy (player={} villager={} entityId={})",
                        sp.getGameProfile().getName(), vill.getUUID(), vill.getId());
                return;
            }

            try {
                sp.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                        new PacketOpenBusyScreen(vill.getId(), t.requested)
                ));
                EZVillagerReroll.LOG().debug("[EZVR] openBusyScreen: sent PacketOpenBusyScreen (player={} villagerEntityId={} req={})",
                        sp.getGameProfile().getName(), vill.getId(), t.requestedKeys.size());
            } catch (Throwable sendErr) {
                EZVillagerReroll.LOG().error("[EZVR] Failed to send Busy screen packet (player={} villager={})",
                        sp.getGameProfile().getName(), vill.getUUID(), sendErr);
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] openBusyScreen failed", t);
        }
    }

    public static void tick(MinecraftServer server) {
        try {
            if (server == null) return;
            if (TASKS.isEmpty()) return;

            Iterator<Map.Entry<UUID, Task>> it = TASKS.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Task> en = it.next();
                Task task = en.getValue();
                if (task == null) {
                    it.remove();
                    continue;
                }

                Villager vill = resolveVillagerByUuid(server, task.villagerUuid);
                if (vill == null) continue;

                // If settlement exists (shouldn't), stop task to avoid double-state.
                if (SETTLEMENTS.containsKey(vill.getUUID())) {
                    EZVillagerReroll.LOG().warn("[EZVR] SearchService.tick: task exists but settlement pending; removing task (villager={})", vill.getUUID());
                    it.remove();
                    continue;
                }

                try {
                    updateGlowForBusyVillager(vill, server);
                } catch (Throwable ignored) {}

                long now = vill.level().getGameTime();
                if (now < task.nextRerollGameTime) continue;

                int cooldown = Math.max(1, ServerConfig.cooldownTicks);
                task.cooldownTicks = cooldown;
                task.nextRerollGameTime = now + cooldown;

                if (containsAnyRequested(vill, task.requestedKeys)) {
                    EZVillagerReroll.LOG().info("[EZVR] Auto-search DONE (already matched): villager={} entityId={} requestedKeys={}",
                            vill.getUUID(), vill.getId(), task.requestedKeys.size());
                    it.remove();

                    trySetVillagerGlow(vill, false);
                    createSettlementAndNotify(server, vill, task, now);
                    continue;
                }

                try {
                    TradeUtil.rebuildOffersInternal(vill, null, false);
                } catch (Throwable rerollErr) {
                    EZVillagerReroll.LOG().error("[EZVR] Auto-search reroll failed (villager={} entityId={})",
                            vill.getUUID(), vill.getId(), rerollErr);
                    continue;
                }

                if (containsAnyRequested(vill, task.requestedKeys)) {
                    EZVillagerReroll.LOG().info("[EZVR] Auto-search FOUND match: villager={} entityId={} requestedKeys={}",
                            vill.getUUID(), vill.getId(), task.requestedKeys.size());
                    it.remove();

                    trySetVillagerGlow(vill, false);
                    createSettlementAndNotify(server, vill, task, now);
                }
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchService.tick failed", t);
        }
    }

    /**
     * Server-authoritative final settlement creation:
     * - caches offers
     * - computes hourly + final cost
     * - blocks trades until paid/declined
     * - notifies owner (auto close busy screen) + keeps villager busy
     */
    private static void createSettlementAndNotify(MinecraftServer server, Villager vill, Task task, long completedAtGameTime) {
        try {
            if (server == null || vill == null || task == null) return;

            long started = Math.max(0L, task.startedAtGameTime);
            long completed = Math.max(started, completedAtGameTime);

            int hourly = computeHourlyCostServer(vill);
            int elapsedTicks = (int) Math.max(0L, completed - started);

            int finalCost = computeFinalCost(hourly, elapsedTicks);

            // Cache offers BEFORE we do anything else.
            ListTag offersTag = serializeOffersCodec(vill);

            Settlement settle = new Settlement(
                    vill.getUUID(),
                    vill.getId(),
                    task.ownerPlayerUuid,
                    started,
                    completed,
                    hourly,
                    finalCost,
                    offersTag
            );

            SETTLEMENTS.put(vill.getUUID(), settle);

            EZVillagerReroll.LOG().info("[EZVR] Auto-search SETTLEMENT created: villager={} entityId={} hourly={} elapsedTicks={} finalCost={} owner={}",
                    vill.getUUID(), vill.getId(), hourly, elapsedTicks, finalCost, String.valueOf(task.ownerPlayerUuid));

            // Notify owner to auto-close busy screen (existing behavior) - still useful.
            notifyOwnerDone(server, task);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] createSettlementAndNotify failed", t);
        }
    }

    private static int computeFinalCost(int hourlyCost, int elapsedTicks) {
        try {
            if (hourlyCost <= 0) return 0;
            if (elapsedTicks <= 0) return 0;

            double hours = elapsedTicks / 72000.0; // 20 tps * 3600
            double raw = hourlyCost * hours;

            // Always round UP (so short searches still pay proportionally)
            long ceil = (long) Math.ceil(raw);

            if (ceil < 0L) ceil = 0L;
            if (ceil > Integer.MAX_VALUE) ceil = Integer.MAX_VALUE;
            return (int) ceil;
        } catch (Throwable t) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Server computed hourly cost, based on the same config semantics as you described.
     *
     * manualCost = paidOffers * costPerOffer
     * hourlyBase = manualCost * AUTO_HOURLY_BASE_MULTIPLIER
     * scaling:
     *  steps = effectivePaidOffers - autoHourlyThreshold
     *  factor = 1 - steps * pct/100 (clamped >= 0)
     *  hourly = ceil(hourlyBase * factor)
     *
     * NOTE:
     * This is used both for settlement creation AND the catalog "hourly preview".
     * It MUST be callable from ServerHandlers, so it is public.
     */
    public static int computeHourlyCostServer(Villager vill) {
        try {
            if (vill == null) return 0;

            int totalOffers = (vill.getOffers() == null) ? 0 : Math.max(0, vill.getOffers().size());

            long lockMask = TradeLockState.getMask(vill);
            long sanitized = TradeLockState.sanitizeMaskForSize(lockMask, totalOffers);
            if (sanitized != lockMask) {
                TradeLockState.setMask(vill, sanitized);
                lockMask = sanitized;
            }

            int lockedOffers = Long.bitCount(lockMask);
            int maxDeduct = Math.max(0, ServerConfig.maxDeductibleLockedOffers);
            int deductibleLocks = Math.min(lockedOffers, maxDeduct);

            int freeOffers = Math.max(0, ServerConfig.freeOffers);
            int costPerOffer = Math.max(0, ServerConfig.costPerOffer);

            int effectiveOffers = Math.max(0, totalOffers - deductibleLocks);
            int paidOffers = Math.max(0, effectiveOffers - freeOffers);

            long manual = (long) paidOffers * (long) costPerOffer;
            if (manual < 0L) manual = 0L;
            if (manual > Integer.MAX_VALUE) manual = Integer.MAX_VALUE;

            long hourlyBase = manual * (long) AUTO_HOURLY_BASE_MULTIPLIER;
            if (hourlyBase < 0L) hourlyBase = 0L;
            if (hourlyBase > Integer.MAX_VALUE) hourlyBase = Integer.MAX_VALUE;

            int threshold = Math.max(0, ServerConfig.autoHourlyThreshold);
            double pct = Math.max(0.0, ServerConfig.autoHourlyDiscountOrIncreasePct);

            int effectivePaidOffers = paidOffers; // matches your config comment naming
            int steps = effectivePaidOffers - threshold;

            double factor = 1.0 - (steps * (pct / 100.0));
            if (factor < 0.0) factor = 0.0;

            long scaled = (long) Math.ceil(hourlyBase * factor);

            if (scaled < 0L) scaled = 0L;
            if (scaled > Integer.MAX_VALUE) scaled = Integer.MAX_VALUE;

            if (EZVillagerReroll.LOG().isDebugEnabled()) {
                EZVillagerReroll.LOG().debug("[EZVR] computeHourlyCostServer: offers={} locked={} deductibleLocks={} free={} paid={} manual={} hourlyBase={} threshold={} pct={} steps={} factor={} hourly={}",
                        totalOffers, lockedOffers, deductibleLocks, freeOffers, paidOffers, manual, hourlyBase,
                        threshold, pct, steps, factor, scaled);
            }

            return (int) scaled;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] computeHourlyCostServer failed (soft): {}", t.toString());
            return 0;
        }
    }

    private static void notifyOwnerDone(MinecraftServer server, Task task) {
        try {
            if (server == null || task == null) return;

            ServerPlayer owner = server.getPlayerList().getPlayer(task.ownerPlayerUuid);
            if (owner == null) {
                EZVillagerReroll.LOG().debug("[EZVR] notifyOwnerDone: owner offline (playerUuid={})", task.ownerPlayerUuid);
                return;
            }

            try {
                owner.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                        new PacketAutoSearchDone(task.villagerEntityId)
                ));
                EZVillagerReroll.LOG().debug("[EZVR] notifyOwnerDone: sent PacketAutoSearchDone(villagerEntityId={}) to {}",
                        task.villagerEntityId, owner.getGameProfile().getName());
            } catch (Throwable sendErr) {
                EZVillagerReroll.LOG().warn("[EZVR] notifyOwnerDone: send failed (soft): {}", sendErr.toString());
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] notifyOwnerDone failed", t);
        }
    }

    private static MinecraftServer serverOf(ServerPlayer sp) {
        try {
            return sp == null ? null : sp.server;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean containsAnyRequested(Villager vill, Set<String> requestedKeys) {
        try {
            if (vill == null) return false;
            if (requestedKeys == null || requestedKeys.isEmpty()) return false;

            MerchantOffers offers = vill.getOffers();
            if (offers == null || offers.isEmpty()) return false;

            for (MerchantOffer o : offers) {
                if (o == null) continue;
                ItemStack out = o.getResult();
                if (out == null || out.isEmpty()) continue;

                String k = CatalogBuilder.keyOf(out);
                if (requestedKeys.contains(k)) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Villager resolveVillagerByEntityId(ServerLevel lvl, int entityId) {
        try {
            if (lvl == null) return null;
            if (entityId < 0) return null;
            Entity e = lvl.getEntity(entityId);
            return (e instanceof Villager v) ? v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Villager resolveVillagerByUuid(MinecraftServer server, UUID uuid) {
        try {
            if (server == null || uuid == null) return null;

            for (ServerLevel lvl : server.getAllLevels()) {
                Entity e = lvl.getEntity(uuid);
                if (e instanceof Villager v) return v;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void updateGlowForBusyVillager(Villager vill, MinecraftServer server) {
        try {
            if (vill == null || server == null) return;
            if (!(vill.level() instanceof ServerLevel sl)) return;

            boolean anyNear = false;
            try {
                List<ServerPlayer> players = sl.players();
                for (ServerPlayer sp : players) {
                    if (sp == null) continue;
                    if (sp.isSpectator()) continue;
                    double d2 = sp.distanceToSqr(vill);
                    if (d2 <= GLOW_RANGE_SQR) {
                        anyNear = true;
                        break;
                    }
                }
            } catch (Throwable t) {
                anyNear = false;
            }

            trySetVillagerGlow(vill, anyNear);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] updateGlowForBusyVillager failed (soft): {}", t.toString());
        }
    }

    private static void trySetVillagerGlow(Villager vill, boolean glow) {
        try {
            if (vill == null) return;

            boolean prev;
            try {
                prev = vill.isCurrentlyGlowing();
            } catch (Throwable ignored) {
                prev = !glow;
            }

            if (prev == glow) return;

            vill.setGlowingTag(glow);

            EZVillagerReroll.LOG().debug("[EZVR] Villager glow updated: villager={} entityId={} glow={}",
                    vill.getUUID(), vill.getId(), glow);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] trySetVillagerGlow failed (soft): {}", t.toString());
        }
    }

    // -----------------------------------------------------------------------------------------
    // Offer serialization for settlement caching (registry-aware, same wrapper format: { "v": tag })
    // -----------------------------------------------------------------------------------------

    private static final String TAG_WRAP_VALUE = "v";

    private static ListTag serializeOffersCodec(Villager vill) {
        try {
            if (vill == null) return new ListTag();
            if (!(vill.level() instanceof ServerLevel level)) return new ListTag();

            MerchantOffers offers = vill.getOffers();
            if (offers == null) return new ListTag();

            var ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess());

            ListTag list = new ListTag();
            for (int i = 0; i < offers.size(); i++) {
                final int idx = i;
                MerchantOffer offer;
                try {
                    offer = offers.get(i);
                } catch (Throwable t) {
                    continue;
                }
                if (offer == null) continue;

                try {
                    var res = MerchantOffer.CODEC.encodeStart(ops, offer);
                    res.resultOrPartial(msg ->
                                    EZVillagerReroll.LOG().warn("[EZVR] Settlement offer encode failed (idx={}): {}", idx, msg))
                            .ifPresent(tag -> {
                                try {
                                    CompoundTag wrap = new CompoundTag();
                                    wrap.put(TAG_WRAP_VALUE, tag);
                                    list.add(wrap);
                                } catch (Throwable ignored) {}
                            });
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().warn("[EZVR] Settlement offer encode threw (idx={}): {}", idx, t.toString());
                }
            }

            return list;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] serializeOffersCodec failed (soft)", t);
            return new ListTag();
        }
    }

    // ---------------------------------------------------------------------
    // Settlement accessors (server-authoritative, safe for handlers)
    // ---------------------------------------------------------------------

    public static int getSettlementFinalCost(Villager vill) {
        try {
            Settlement s = SETTLEMENTS.get(vill.getUUID());
            return s == null ? 0 : Math.max(0, s.finalCost);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int getSettlementHourlyCost(Villager vill) {
        try {
            Settlement s = SETTLEMENTS.get(vill.getUUID());
            return s == null ? 0 : Math.max(0, s.hourlyCost);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int getSettlementElapsedTicks(Villager vill) {
        try {
            Settlement s = SETTLEMENTS.get(vill.getUUID());
            if (s == null) return 0;
            return (int) Math.max(0L, s.completedAtGameTime - s.startedAtGameTime);
        } catch (Throwable t) {
            return 0;
        }
    }

    // -----------------------------------------------------------------------------------------
    // Settlement resolution API used by ServerHandlers
    // -----------------------------------------------------------------------------------------

    public static Settlement popSettlement(UUID villagerUuid) {
        try {
            if (villagerUuid == null) return null;
            return SETTLEMENTS.remove(villagerUuid);
        } catch (Throwable t) {
            return null;
        }
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\server\SearchService.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\server\ServerEvents.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/ServerEvents.java
        package org.z2six.ezvillagerreroll.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.mixin.MerchantMenuAccessor;

public final class ServerEvents {

    private static volatile boolean registered = false;
    private static volatile long lastTickDebugGameTime = -1;

    private ServerEvents() {}

    public static void register(IEventBus bus) {
        if (bus == null) {
            EZVillagerReroll.LOG().error("[EZVR] ServerEvents.register called with null bus");
            return;
        }
        if (registered) {
            EZVillagerReroll.LOG().warn("[EZVR] ServerEvents.register called twice; ignoring.");
            return;
        }
        registered = true;

        try {
            bus.addListener(ServerEvents::onServerTickPost);
            bus.addListener(ServerEvents::onServerStarted);
            bus.addListener(ServerEvents::onServerStopping);
            bus.addListener(ServerEvents::onContainerOpen);

            EZVillagerReroll.LOG().info("[EZVR] ServerEvents registered on gameplay bus.");
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ServerEvents.register failed", t);
        }
    }

    private static void onContainerOpen(PlayerContainerEvent.Open e) {
        try {
            if (e == null) return;

            if (!(e.getEntity() instanceof ServerPlayer sp)) return;
            if (!(e.getContainer() instanceof MerchantMenu menu)) return;

            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof AbstractVillager merchant)) return;

            if (!(merchant instanceof Villager vill)) return;

            // NEW: If awaiting payment settlement, do NOT restore/capture canonical offers here.
            // The villager should not be tradeable at all during this time, and we don't want
            // this hook to overwrite the settlement-cached offers.
            if (SearchService.isAwaitingPayment(vill)) {
                EZVillagerReroll.LOG().debug("[EZVR] onContainerOpen: villager awaiting payment; skipping offer persistence (villager={} player={})",
                        vill.getUUID(), sp.getGameProfile().getName());
                return;
            }

            var level = sp.serverLevel();
            if (level == null) return;

            VillagerOffersSavedData data = VillagerOffersSavedData.get(level);
            if (data == null) {
                EZVillagerReroll.LOG().warn("[EZVR] onContainerOpen: offers saved data unavailable; skipping (player={})", sp.getGameProfile().getName());
                return;
            }

            boolean had = data.has(vill.getUUID());

            if (had) {
                boolean applied = data.apply(vill);
                if (applied) {
                    VillagerOffersSavedData.syncOffersToPlayerIfPossible(sp, menu, vill);

                    EZVillagerReroll.LOG().info(
                            "[EZVR] onContainerOpen: restored canonical offers for villager={} (player={}, offers={})",
                            vill.getUUID(),
                            sp.getGameProfile().getName(),
                            (vill.getOffers() == null ? -1 : vill.getOffers().size())
                    );
                } else {
                    EZVillagerReroll.LOG().debug(
                            "[EZVR] onContainerOpen: had entry but apply failed; leaving vanilla state (villager={}, player={})",
                            vill.getUUID(),
                            sp.getGameProfile().getName()
                    );
                }
            } else {
                data.capture(vill);

                EZVillagerReroll.LOG().info(
                        "[EZVR] onContainerOpen: captured initial offers as canonical for villager={} (player={}, offers={})",
                        vill.getUUID(),
                        sp.getGameProfile().getName(),
                        (vill.getOffers() == null ? -1 : vill.getOffers().size())
                );
            }
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] onContainerOpen failed", t);
        }
    }

    private static void onServerTickPost(ServerTickEvent.Post e) {
        try {
            if (e == null) return;

            MinecraftServer server;
            try {
                server = e.getServer();
            } catch (Throwable ignored) {
                return;
            }
            if (server == null) return;

            try {
                SearchService.tick(server);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] ServerEvents: SearchService.tick failed", t);
            }

            try {
                long gt = server.overworld().getGameTime();
                if (gt % 200L == 0L && gt != lastTickDebugGameTime) {
                    lastTickDebugGameTime = gt;
                    EZVillagerReroll.LOG().debug("[EZVR] Server tick heartbeat (gameTime={})", gt);
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ServerEvents.onServerTickPost failed", t);
        }
    }

    private static void onServerStarted(ServerStartedEvent e) {
        try {
            if (e == null) return;
            MinecraftServer server = e.getServer();
            if (server == null) return;

            EZVillagerReroll.LOG().info("[EZVR] ServerStarted: loading persisted auto-search tasks.");
            try {
                SearchSavedData.loadIntoSearchService(server);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] ServerStarted: loadIntoSearchService failed", t);
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ServerEvents.onServerStarted failed", t);
        }
    }

    private static void onServerStopping(ServerStoppingEvent e) {
        try {
            if (e == null) return;
            MinecraftServer server = e.getServer();
            if (server == null) return;

            EZVillagerReroll.LOG().info("[EZVR] ServerStopping: saving persisted auto-search tasks.");
            try {
                SearchSavedData.saveFromSearchService(server);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] ServerStopping: saveFromSearchService failed", t);
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ServerEvents.onServerStopping failed", t);
        }
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\server\ServerEvents.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\server\TooltipService.java ----
// MainFile: src/main/java/org/z2six/ezvillagerreroll/server/TooltipService.java
        package org.z2six.ezvillagerreroll.server;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.config.ServerConfig;
import org.z2six.ezvillagerreroll.logic.MoneyBridge;
import org.z2six.ezvillagerreroll.logic.TradeLockState;
import org.z2six.ezvillagerreroll.mixin.MerchantMenuAccessor;
import org.z2six.ezvillagerreroll.network.PacketTooltipData;

public final class TooltipService {

    public static PacketTooltipData computeSnapshot(ServerPlayer player, int traderEntityId) {
        var out = new PacketTooltipData();
        try {
            Villager vill = null;

            // 1) Primary: resolve from entity id (works when client can provide it)
            if (traderEntityId >= 0) {
                try {
                    Entity e = player.level().getEntity(traderEntityId);
                    if (e instanceof Villager v) {
                        vill = v;
                        EZVillagerReroll.LOG().debug("[EZVR] TooltipService: resolved villager via entityId={} uuid={}", traderEntityId, v.getUUID());
                    } else if (e != null) {
                        EZVillagerReroll.LOG().debug("[EZVR] TooltipService: entityId={} is not Villager (type={})", traderEntityId, e.getClass().getName());
                    } else {
                        EZVillagerReroll.LOG().debug("[EZVR] TooltipService: no entity for entityId={}", traderEntityId);
                    }
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().debug("[EZVR] TooltipService: entityId resolution failed (soft): {}", t.toString());
                }
            } else {
                EZVillagerReroll.LOG().debug("[EZVR] TooltipService: traderEntityId < 0 (client could not resolve trader id)");
            }

            // 2) Fallback: resolve from open MerchantMenu trader (server-authoritative, matches reroll path)
            if (vill == null) {
                try {
                    if (player.containerMenu instanceof MerchantMenu menu) {
                        Merchant trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
                        if (trader instanceof Villager v) {
                            vill = v;
                            EZVillagerReroll.LOG().debug("[EZVR] TooltipService: resolved villager via MerchantMenu trader uuid={}", v.getUUID());
                        } else {
                            EZVillagerReroll.LOG().debug("[EZVR] TooltipService: MerchantMenu trader is not Villager (traderType={})",
                                    trader == null ? "null" : trader.getClass().getName());
                        }
                    } else {
                        EZVillagerReroll.LOG().debug("[EZVR] TooltipService: player.containerMenu is not MerchantMenu (menuType={})",
                                player.containerMenu == null ? "null" : player.containerMenu.getClass().getName());
                    }
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().debug("[EZVR] TooltipService: MerchantMenu fallback resolution failed (soft): {}", t.toString());
                }
            }

            int villLevel = vill != null ? vill.getVillagerData().getLevel() : 1;
            int villXp = vill != null ? vill.getVillagerXp() : 0;

            String costSpec = ServerConfig.costSpec == null ? "minecraft:emerald" : ServerConfig.costSpec;
            boolean preferWallet = ServerConfig.preferWallet;

            // ---- Offer-based cost computation (authoritative) ----
            int totalOffers = 0;
            long lockMask = 0L;
            int lockedCount = 0;

            if (vill != null) {
                totalOffers = (vill.getOffers() == null) ? 0 : vill.getOffers().size();
                lockMask = TradeLockState.getMask(vill);
                long sanitized = TradeLockState.sanitizeMaskForSize(lockMask, totalOffers);
                if (sanitized != lockMask) {
                    TradeLockState.setMask(vill, sanitized);
                    lockMask = sanitized;
                    EZVillagerReroll.LOG().debug("[EZVR] TooltipService: sanitized lock mask (villager={} offers={} newMask={})",
                            vill.getUUID(), totalOffers, Long.toUnsignedString(lockMask));
                }
                lockedCount = Long.bitCount(lockMask);
            } else {
                EZVillagerReroll.LOG().debug("[EZVR] TooltipService: villager unresolved -> tooltip will show offers=0/cost=free");
            }

            int maxDeduct = Math.max(0, ServerConfig.maxDeductibleLockedOffers);
            int deductibleLocks = Math.min(lockedCount, maxDeduct);

            int effectiveOffers = Math.max(0, totalOffers - deductibleLocks);

            int freeOffers = Math.max(0, ServerConfig.freeOffers);
            int costPerOffer = Math.max(0, ServerConfig.costPerOffer);

            int paidOffers = Math.max(0, effectiveOffers - freeOffers);

            long rawCost = (long) paidOffers * (long) costPerOffer;
            int cost;
            if (rawCost < 0) cost = 0;
            else if (rawCost > Integer.MAX_VALUE) cost = Integer.MAX_VALUE;
            else cost = (int) rawCost;

            EZVillagerReroll.LOG().debug(
                    "[EZVR] TooltipService cost calc: totalOffers={} lockedCount={} deductibleLocks={} effectiveOffers={} freeOffers={} paidOffers={} costPerOffer={} -> cost={}",
                    totalOffers, lockedCount, deductibleLocks, effectiveOffers, freeOffers, paidOffers, costPerOffer, cost
            );

            // Tooltip fields
            out.cost.itemOrTag = costSpec;
            out.cost.item = (!ServerConfig.isTagSpec(costSpec)) ? ResourceLocation.tryParse(costSpec) : null;
            out.cost.baseCost = cost;
            out.cost.scaledCost = cost;

            // "Next level" cost no longer exists in offer-based model
            out.cost.nextCostIfUsed = null;
            out.cost.maxCostPossible = null;

            // Breakdown
            out.cost.totalOffers = totalOffers;
            out.cost.lockedOffers = lockedCount;
            out.cost.deductibleLockedOffers = deductibleLocks;
            out.cost.freeOffers = freeOffers;
            out.cost.paidOffers = paidOffers;
            out.cost.costPerOffer = costPerOffer;

            out.villager.level = villLevel;
            out.villager.xp = villXp;

            // Afford check: wallet only for exact items
            if (cost <= 0) {
                out.afford.canAfford = true;
                out.afford.source = "none";
            } else {
                boolean walletOK = false;
                boolean invOK = false;

                if (preferWallet && out.cost.item != null && MoneyBridge.isLCPresent()) {
                    walletOK = MoneyBridge.canAfford(player, out.cost.item, cost);
                }

                if (out.cost.item != null) {
                    Item item = BuiltInRegistries.ITEM.get(out.cost.item);
                    if (item != null) {
                        int invCount = countInInventory(player, item);
                        invOK = invCount >= cost;
                    }
                } else {
                    invOK = false;
                }

                boolean can = walletOK || invOK;
                out.afford.canAfford = can;
                out.afford.source = can ? (walletOK && invOK ? "both" : (walletOK ? "wallet" : "inventory")) : "none";
            }

            boolean capEnabled = ServerConfig.perVillagerDaily > 0;
            out.cap.enabled = capEnabled;
            out.cap.cap = ServerConfig.perVillagerDaily;
            out.cap.remaining = -1;

            out.cfg.version = ServerConfig.cfgVersion();
            out.cfg.hash = ServerConfig.cfgHash();
            out.cfg.preferWallet = preferWallet;
            out.cfg.freeMode = (cost <= 0);
            out.cfg.capEnabled = capEnabled;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] TooltipService.computeSnapshot failed", t);
        }
        return out;
    }

    private static int countInInventory(ServerPlayer player, Item item) {
        int total = 0;
        try {
            var inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty() && s.getItem() == item) total += s.getCount();
            }
        } catch (Throwable ignored) {}
        return total;
    }

    private TooltipService() {}
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\server\TooltipService.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\server\TradeLockService.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/TradeLockService.java
        package org.z2six.ezvillagerreroll.server;

import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.server.level.ServerPlayer;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.logic.TradeLockState;
import org.z2six.ezvillagerreroll.mixin.MerchantMenuAccessor;
import org.z2six.ezvillagerreroll.network.PacketTradeLocks;

public final class TradeLockService {

    public static PacketTradeLocks computeSnapshot(ServerPlayer player) {
        try {
            if (!(player.containerMenu instanceof MerchantMenu menu)) {
                EZVillagerReroll.LOG().debug("[EZVR] TradeLockService snapshot: player not in MerchantMenu (player={})",
                        player.getGameProfile().getName());
                return new PacketTradeLocks(-1, 0L);
            }

            int containerId = menu.containerId;

            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Villager vill)) {
                EZVillagerReroll.LOG().debug("[EZVR] TradeLockService snapshot: trader not Villager (player={}, trader={})",
                        player.getGameProfile().getName(), trader == null ? "null" : trader.getClass().getName());
                return new PacketTradeLocks(containerId, 0L);
            }

            long mask = TradeLockState.getMask(vill);

            int size = (vill.getOffers() == null) ? 0 : vill.getOffers().size();
            long sanitized = TradeLockState.sanitizeMaskForSize(mask, size);
            if (sanitized != mask) {
                TradeLockState.setMask(vill, sanitized);
                mask = sanitized;
            }

            return new PacketTradeLocks(containerId, mask);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] TradeLockService.computeSnapshot failed", t);
            return new PacketTradeLocks(-1, 0L);
        }
    }

    private TradeLockService() {}
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\server\TradeLockService.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\server\TradeLockSyncService.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/TradeLockSyncService.java
        package org.z2six.ezvillagerreroll.server;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.mixin.MerchantMenuAccessor;
import org.z2six.ezvillagerreroll.network.PacketTradeLocks;

public final class TradeLockSyncService {

    private TradeLockSyncService() {}

    /**
     * Sends the current lock mask to every player who is actively trading with this villager.
     * This is what makes it "other players can see it as well" (server authoritative).
     */
    public static void syncToActiveTraders(Villager vill, long mask) {
        try {
            if (vill == null) return;
            MinecraftServer server = vill.getServer();
            if (server == null) return;

            int villEntityId = vill.getId();
            PacketTradeLocks pkt = new PacketTradeLocks(villEntityId, mask);

            int sent = 0;

            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                try {
                    if (!(sp.containerMenu instanceof MerchantMenu mm)) continue;
                    var trader = ((MerchantMenuAccessor) mm).ezvr$getTrader();
                    if (trader != vill) continue;

                    if (sp.connection != null) {
                        sp.connection.send(new ClientboundCustomPayloadPacket(pkt));
                        sent++;
                    }
                } catch (Throwable ignored) {}
            }

            EZVillagerReroll.LOG().debug("[EZVR] TradeLockSyncService: synced mask={} villager={} to {} active trader(s)",
                    Long.toUnsignedString(mask), vill.getUUID(), sent);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] TradeLockSyncService.syncToActiveTraders failed", t);
        }
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\server\TradeLockSyncService.java ----

        ---- File: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\server\VillagerOffersSavedData.java ----
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/VillagerOffersSavedData.java
        package org.z2six.ezvillagerreroll.server;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.slf4j.Logger;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.mixin.AbstractVillagerAccessor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persisted, world-scoped storage of villager offers keyed by villager UUID.
 *
 * Robustness goals:
 * - Store "canonical" offers for each villager UUID.
 * - Re-apply canonical offers on MerchantMenu open.
 * - Update canonical offers after EZVR rerolls.
 *
 * IMPORTANT: In 1.21.x, MerchantOffer.CODEC parsing/encoding should use registry-aware ops.
 * Using plain NbtOps can cause partial decode failures (dropping offers).
 *
 * Storage format:
 * - Root: { Version:int, Entries:[{ UUID:string, Offers:[{v:Tag}, ...] }, ...] }
 * - Offers list is ALWAYS a list of CompoundTag wrappers, even if the encoded tag isn't a compound.
 */
public final class VillagerOffersSavedData extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String DATA_NAME = "ezvr_villager_offers";

    private static final String TAG_VERSION = "Version";
    private static final String TAG_ENTRIES = "Entries";

    private static final String TAG_UUID = "UUID";
    private static final String TAG_OFFERS = "Offers";

    // Wrapper key: each entry in Offers is a CompoundTag { "v": <encoded MerchantOffer tag> }
    private static final String TAG_WRAP_VALUE = "v";

    private static final int CURRENT_VERSION = 2;

    public static final Factory<VillagerOffersSavedData> FACTORY =
            new Factory<>(VillagerOffersSavedData::new, VillagerOffersSavedData::load, DataFixTypes.LEVEL);

    private final Map<UUID, ListTag> offersByVillager = new HashMap<>();

    public VillagerOffersSavedData() {}

    public static VillagerOffersSavedData get(ServerLevel level) {
        try {
            if (level == null) return null;

            DimensionDataStorage storage = level.getDataStorage();
            if (storage == null) return null;

            return storage.computeIfAbsent(FACTORY, DATA_NAME);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] VillagerOffersSavedData.get(ServerLevel) failed", t);
            return null;
        }
    }

    public static VillagerOffersSavedData get(Level level) {
        try {
            if (!(level instanceof ServerLevel sl)) return null;
            return get(sl);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] VillagerOffersSavedData.get(Level) failed", t);
            return null;
        }
    }

    public static VillagerOffersSavedData load(CompoundTag root, HolderLookup.Provider registries) {
        VillagerOffersSavedData data = new VillagerOffersSavedData();

        try {
            if (root == null) return data;

            int version = 0;
            try {
                version = root.getInt(TAG_VERSION);
            } catch (Throwable ignored) {}

            ListTag entries;
            try {
                entries = root.getList(TAG_ENTRIES, Tag.TAG_COMPOUND);
            } catch (Throwable t) {
                entries = null;
            }

            if (entries == null) {
                LOGGER.debug("[EZVR] VillagerOffersSavedData.load: no Entries tag; starting empty.");
                return data;
            }

            int loaded = 0;

            for (int i = 0; i < entries.size(); i++) {
                try {
                    CompoundTag e = entries.getCompound(i);
                    if (e == null) continue;

                    String uuidStr = e.getString(TAG_UUID);
                    if (uuidStr == null || uuidStr.isEmpty()) continue;

                    UUID uuid;
                    try {
                        uuid = UUID.fromString(uuidStr);
                    } catch (Throwable ignored) {
                        continue;
                    }

                    // Offers is stored as list of CompoundTag wrappers.
                    ListTag offers;
                    try {
                        offers = e.getList(TAG_OFFERS, Tag.TAG_COMPOUND);
                    } catch (Throwable t) {
                        continue;
                    }

                    // Defensive deep copy
                    ListTag copy = new ListTag();
                    for (int j = 0; j < offers.size(); j++) {
                        try {
                            CompoundTag wrap = offers.getCompound(j);
                            if (wrap != null) copy.add(wrap.copy());
                        } catch (Throwable ignored) {}
                    }

                    data.offersByVillager.put(uuid, copy);
                    loaded++;
                } catch (Throwable t) {
                    LOGGER.debug("[EZVR] VillagerOffersSavedData.load: entry parse failed (soft): {}", t.toString());
                }
            }

            EZVillagerReroll.LOG().info("[EZVR] VillagerOffersSavedData.load: loaded {} offer entries (version={}).", loaded, version);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] VillagerOffersSavedData.load failed (soft; returning partial/empty)", t);
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        try {
            if (root == null) root = new CompoundTag();

            root.putInt(TAG_VERSION, CURRENT_VERSION);

            ListTag entries = new ListTag();

            int written = 0;
            for (var it : offersByVillager.entrySet()) {
                try {
                    UUID uuid = it.getKey();
                    ListTag offers = it.getValue();
                    if (uuid == null || offers == null) continue;

                    CompoundTag e = new CompoundTag();
                    e.putString(TAG_UUID, uuid.toString());

                    // Defensive deep copy
                    ListTag copy = new ListTag();
                    for (int j = 0; j < offers.size(); j++) {
                        try {
                            CompoundTag wrap = offers.getCompound(j);
                            if (wrap != null) copy.add(wrap.copy());
                        } catch (Throwable ignored) {}
                    }

                    e.put(TAG_OFFERS, copy);
                    entries.add(e);
                    written++;
                } catch (Throwable t) {
                    LOGGER.debug("[EZVR] VillagerOffersSavedData.save: entry write failed (soft): {}", t.toString());
                }
            }

            root.put(TAG_ENTRIES, entries);

            if (EZVillagerReroll.LOG().isDebugEnabled()) {
                EZVillagerReroll.LOG().debug("[EZVR] VillagerOffersSavedData.save: wrote {} offer entries.", written);
            }
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] VillagerOffersSavedData.save failed (soft)", t);
        }

        return root;
    }

    public boolean has(UUID villagerId) {
        try {
            if (villagerId == null) return false;
            return offersByVillager.containsKey(villagerId);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Capture current offers from villager into world data.
     */
    public void capture(AbstractVillager villager) {
        try {
            if (villager == null) return;

            UUID id = villager.getUUID();
            if (id == null) return;

            ServerLevel level = safeServerLevel(villager.level());
            if (level == null) {
                EZVillagerReroll.LOG().warn("[EZVR] OffersSavedData.capture: non-server level; skipping (villager={})", id);
                return;
            }

            MerchantOffers offers = safeGetOffers(villager);
            if (offers == null) {
                EZVillagerReroll.LOG().warn("[EZVR] OffersSavedData.capture: offers null; skipping (villager={})", id);
                return;
            }

            ListTag list = serializeOffersCodec(level, offers);
            if (list == null) return;

            offersByVillager.put(id, list);
            this.setDirty();

            EZVillagerReroll.LOG().info("[EZVR] OffersSavedData.capture: stored {} offers (villager={})", offers.size(), id);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] OffersSavedData.capture failed (soft)", t);
        }
    }

    /**
     * Apply stored offers to villager, if present.
     *
     * @return true if applied, false otherwise.
     */
    public boolean apply(AbstractVillager villager) {
        try {
            if (villager == null) return false;

            UUID id = villager.getUUID();
            if (id == null) return false;

            ServerLevel level = safeServerLevel(villager.level());
            if (level == null) {
                EZVillagerReroll.LOG().warn("[EZVR] OffersSavedData.apply: non-server level; skipping (villager={})", id);
                return false;
            }

            ListTag stored = offersByVillager.get(id);
            if (stored == null) return false;

            MerchantOffers offers = deserializeOffersCodec(level, stored);
            if (offers == null) return false;

            ((AbstractVillagerAccessor) villager).ezvr$setOffers(offers);

            EZVillagerReroll.LOG().info("[EZVR] OffersSavedData.apply: applied {} offers (villager={})", offers.size(), id);
            return true;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] OffersSavedData.apply failed (soft)", t);
            return false;
        }
    }

    /**
     * Sync the current villager offers to the player's open MerchantMenu (Villager-only fields).
     */
    public static void syncOffersToPlayerIfPossible(ServerPlayer sp, net.minecraft.world.inventory.MerchantMenu menu, AbstractVillager merchant) {
        try {
            if (sp == null || menu == null || merchant == null) return;

            if (merchant instanceof Villager vill) {
                sp.sendMerchantOffers(
                        menu.containerId,
                        vill.getOffers(),
                        vill.getVillagerData().getLevel(),
                        vill.getVillagerXp(),
                        vill.showProgressBar(),
                        vill.canRestock()
                );
            }
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] syncOffersToPlayerIfPossible failed (soft): {}", t.toString());
        }
    }

    // ---------------------------------------------
    // CODEC-BASED SERIALIZATION (registry-aware)
    // ---------------------------------------------

    private static ListTag serializeOffersCodec(ServerLevel level, MerchantOffers offers) {
        try {
            if (level == null || offers == null) return null;

            var ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess());

            ListTag list = new ListTag();

            for (int i = 0; i < offers.size(); i++) {
                final int idx = i;

                MerchantOffer offer;
                try {
                    offer = offers.get(i);
                } catch (Throwable t) {
                    continue;
                }
                if (offer == null) continue;

                try {
                    var res = MerchantOffer.CODEC.encodeStart(ops, offer);

                    // Capture the encoded Tag, and ALWAYS wrap it into a CompoundTag.
                    res.resultOrPartial(msg ->
                                    EZVillagerReroll.LOG().warn("[EZVR] MerchantOffer encode failed (idx={}): {}", idx, msg))
                            .ifPresent(tag -> {
                                try {
                                    CompoundTag wrap = new CompoundTag();
                                    wrap.put(TAG_WRAP_VALUE, tag);
                                    list.add(wrap);
                                } catch (Throwable ignored) {}
                            });
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().warn("[EZVR] MerchantOffer encode threw (idx={}): {}", idx, t.toString());
                }
            }

            return list;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] serializeOffersCodec failed (soft)", t);
            return null;
        }
    }

    private static MerchantOffers deserializeOffersCodec(ServerLevel level, ListTag list) {
        try {
            if (level == null || list == null) return null;

            var ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess());

            MerchantOffers offers = new MerchantOffers();

            for (int i = 0; i < list.size(); i++) {
                final int idx = i;

                CompoundTag wrap;
                try {
                    wrap = list.getCompound(i);
                } catch (Throwable t) {
                    continue;
                }
                if (wrap == null) continue;

                Tag tag;
                try {
                    tag = wrap.get(TAG_WRAP_VALUE);
                } catch (Throwable t) {
                    tag = null;
                }
                if (tag == null) continue;

                try {
                    var res = MerchantOffer.CODEC.parse(ops, tag);
                    res.resultOrPartial(msg ->
                                    EZVillagerReroll.LOG().warn("[EZVR] MerchantOffer decode failed (idx={}): {}", idx, msg))
                            .ifPresent(offers::add);
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().warn("[EZVR] MerchantOffer decode threw (idx={}): {}", idx, t.toString());
                }
            }

            return offers;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] deserializeOffersCodec failed (soft)", t);
            return null;
        }
    }

    private static MerchantOffers safeGetOffers(AbstractVillager villager) {
        try {
            return villager.getOffers();
        } catch (Throwable t) {
            return null;
        }
    }

    private static ServerLevel safeServerLevel(Level level) {
        try {
            return (level instanceof ServerLevel sl) ? sl : null;
        } catch (Throwable t) {
            return null;
        }
    }
}

---- End of file: C:\Programming\IntelliJ IDEA\EZVillagerReroll\neoforge\src\main\java\org\z2six\ezvillagerreroll\server\VillagerOffersSavedData.java ----

