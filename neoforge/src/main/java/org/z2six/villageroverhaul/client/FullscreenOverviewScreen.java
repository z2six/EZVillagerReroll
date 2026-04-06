package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.z2six.villageroverhaul.network.trades.PacketVillagerTradesData;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class FullscreenOverviewScreen extends Screen {
    private static final int SCROLLBAR_W = 7;
    private static final int LIST_TEXT_PAD_X = 6;
    private static final int OVERVIEW_RADAR_ROWS = 7;

    private final Screen parent;
    private final int villagerEntityId;
    private final List<Component> raw;
    private final PacketVillagerTradesData inlineTrades;
    private final VillagerInfoScreen.RadarSummary radarSummary;

    private List<FormattedCharSequence> lines = List.of();
    private List<VillagerInfoScreen.OverviewRowMeta> rowMeta = List.of();
    private int scrollRow = 0;
    private int rowH = 10;

    private int contentX;
    private int contentY;
    private int contentW;
    private int contentH;
    private int textW;
    private SimpleScrollBar scrollBar;

    public FullscreenOverviewScreen(
            Screen parent,
            int villagerEntityId,
            List<Component> raw,
            PacketVillagerTradesData inlineTrades,
            VillagerInfoScreen.RadarSummary radarSummary
    ) {
        super(Component.literal("Overview"));
        this.parent = parent;
        this.villagerEntityId = villagerEntityId;
        this.raw = raw == null ? List.of() : raw;
        this.inlineTrades = inlineTrades;
        this.radarSummary = radarSummary;
    }

    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    protected void init() {
        super.init();

        int pad = 12;
        int topBarH = 26;

        contentX = pad;
        contentY = pad + topBarH;
        contentW = Math.max(40, this.width - pad * 2);
        contentH = Math.max(40, this.height - contentY - pad);

        this.addRenderableWidget(
                Button.builder(Component.literal("Back"), b -> onClose())
                        .pos(this.width - pad - 58, pad)
                        .size(58, 18)
                        .build()
        );

        scrollBar = new SimpleScrollBar(contentX + contentW - SCROLLBAR_W - 2, contentY + 2, SCROLLBAR_W, Math.max(1, contentH - 4));
        rebuildRows(true);
    }

    private void rebuildRows(boolean resetScroll) {
        try {
            Font font = Minecraft.getInstance().font;
            rowH = Math.max(18, font.lineHeight + 2);
            textW = Math.max(40, Math.min(920, contentW - (SCROLLBAR_W + 28)));
            int wrapW = Math.max(40, textW - 8);

            VillagerInfoScreen.OverviewWrap wrap = VillagerInfoScreen.wrapOverview(font, raw, wrapW, villagerEntityId, inlineTrades, radarSummary);
            this.lines = wrap.lines();
            this.rowMeta = wrap.rowMeta();

            if (resetScroll) {
                scrollRow = 0;
            }
            clampScroll();
        } catch (Throwable ignored) {
            this.lines = List.of(FormattedCharSequence.EMPTY);
            this.rowMeta = List.of();
            this.scrollRow = 0;
            this.textW = Math.max(40, contentW - (SCROLLBAR_W + 28));
        }
    }

    private void clampScroll() {
        int visible = Math.max(1, Math.max(1, contentH - 8) / rowH);
        int maxScroll = Math.max(0, lines.size() - visible);
        if (scrollRow < 0) scrollRow = 0;
        if (scrollRow > maxScroll) scrollRow = maxScroll;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0.0D) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (mouseX < contentX || mouseX > contentX + contentW || mouseY < contentY || mouseY > contentY + contentH) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        scrollRow -= (int) Math.signum(scrollY);
        clampScroll();
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean handled = false;
        if (scrollBar != null) {
            handled = scrollBar.mouseClicked(mouseX, mouseY, button);
        }
        return handled || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = false;
        if (scrollBar != null) {
            handled = scrollBar.mouseReleased(mouseX, mouseY, button);
        }
        return handled || super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        boolean handled = false;
        if (scrollBar != null) {
            handled = scrollBar.mouseDragged(mouseX, mouseY, button, dragX, dragY);
            if (handled) {
                int visible = Math.max(1, Math.max(1, contentH - 8) / rowH);
                int maxScroll = Math.max(0, lines.size() - visible);
                scrollRow = scrollBar.getScrollRowFromThumb(scrollRow, maxScroll);
                clampScroll();
            }
        }
        return handled || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        gg.fill(0, 0, this.width, this.height, 0xCC0B0B0B);

        Font font = Minecraft.getInstance().font;
        gg.drawString(font, "Overview", 12, 16, 0xFFFFFFFF, true);

        gg.fill(contentX, contentY, contentX + contentW, contentY + contentH, 0xFF101010);
        gg.fill(contentX, contentY, contentX + contentW, contentY + 1, 0xFF2E2E2E);
        gg.fill(contentX, contentY + contentH - 1, contentX + contentW, contentY + contentH, 0xFF2E2E2E);
        gg.fill(contentX, contentY, contentX + 1, contentY + contentH, 0xFF2E2E2E);
        gg.fill(contentX + contentW - 1, contentY, contentX + contentW, contentY + contentH, 0xFF2E2E2E);

        int innerTop = contentY + 4;
        int innerBottom = contentY + contentH - 4;
        int innerH = Math.max(1, innerBottom - innerTop);
        int innerW = Math.max(1, textW);
        int visible = Math.max(1, innerH / rowH);
        int start = scrollRow;
        int end = Math.min(lines.size(), start + visible);
        int textAreaW = Math.max(1, contentW - SCROLLBAR_W - 8);
        int textX = contentX + Math.max(4, (textAreaW - innerW) / 2) + 4;

        boolean tooltipDrawn = false;
        boolean scissor = false;
        try {
            gg.enableScissor(textX, contentY + 2, textX + innerW, contentY + contentH - 2);
            scissor = true;
        } catch (Throwable ignored) {}

        try {
            int y = innerTop;
            Set<Integer> renderedRadarAnchors = new HashSet<>();
            for (int i = start; i < end; i++) {
                VillagerInfoScreen.OverviewRowMeta meta = (i >= 0 && i < rowMeta.size()) ? rowMeta.get(i) : null;
                if (meta instanceof VillagerInfoScreen.RadarSpacerRow) {
                    int anchorIndex = findRadarAnchorIndex(i);
                    if (anchorIndex >= 0 && renderedRadarAnchors.add(anchorIndex)) {
                        VillagerInfoScreen.OverviewRowMeta anchorMeta = rowMeta.get(anchorIndex);
                        if (anchorMeta instanceof VillagerInfoScreen.RadarRow rr) {
                            int anchorY = y - (i - anchorIndex) * rowH;
                            if (VillagerInfoScreen.renderOverviewRadarRow(gg, font, rr, textX, anchorY, innerW - LIST_TEXT_PAD_X - 2, rowH * OVERVIEW_RADAR_ROWS, mouseX, mouseY)) {
                                tooltipDrawn = true;
                            }
                        }
                    }
                } else if (meta instanceof VillagerInfoScreen.TradeRow tr) {
                    int tradeRowW = VillagerInfoScreen.estimateTradeRowWidth(tr);
                    int tradeX = textX + Math.max(0, (innerW - tradeRowW) / 2);
                    if (VillagerInfoScreen.renderTradeRow(gg, font, tr, tradeX, y + 1, mouseX, mouseY)) {
                        tooltipDrawn = true;
                    }
                } else if (meta instanceof VillagerInfoScreen.RadarRow rr) {
                    renderedRadarAnchors.add(i);
                    if (VillagerInfoScreen.renderOverviewRadarRow(gg, font, rr, textX, y, innerW - LIST_TEXT_PAD_X - 2, rowH * OVERVIEW_RADAR_ROWS, mouseX, mouseY)) {
                        tooltipDrawn = true;
                    }
                } else if (i >= 0 && i < lines.size()) {
                    FormattedCharSequence line = lines.get(i);
                    int lineX = textX + Math.max(0, (innerW - font.width(line)) / 2);
                    gg.drawString(font, line, lineX, y, 0xFFFFFFFF, false);
                }
                y += rowH;
            }
        } finally {
            if (scissor) {
                try {
                    gg.disableScissor();
                } catch (Throwable ignored) {}
            }
        }

        if (scrollBar != null) {
            int maxScroll = Math.max(0, lines.size() - visible);
            scrollBar.setBounds(contentX + contentW - SCROLLBAR_W - 2, contentY + 2, SCROLLBAR_W, Math.max(1, contentH - 4));
            scrollBar.render(gg, scrollRow, maxScroll, lines.size(), visible);
        }

        super.render(gg, mouseX, mouseY, partialTick);
    }

    @Override
    public void resize(Minecraft mc, int width, int height) {
        super.resize(mc, width, height);
        int pad = 12;
        int topBarH = 26;
        contentX = pad;
        contentY = pad + topBarH;
        contentW = Math.max(40, this.width - pad * 2);
        contentH = Math.max(40, this.height - contentY - pad);
        rebuildRows(false);
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int findRadarAnchorIndex(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= rowMeta.size()) {
            return -1;
        }
        for (int i = rowIndex; i >= 0; i--) {
            VillagerInfoScreen.OverviewRowMeta meta = rowMeta.get(i);
            if (meta instanceof VillagerInfoScreen.RadarRow) {
                return i;
            }
            if (!(meta instanceof VillagerInfoScreen.RadarSpacerRow)) {
                break;
            }
        }
        return -1;
    }

    private static final class SimpleScrollBar {
        private int x;
        private int y;
        private int w;
        private int h;
        private boolean dragging = false;
        private double lastMouseY = 0.0D;

        private SimpleScrollBar(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        private void setBounds(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        private void render(GuiGraphics gg, int scrollRow, int maxRow, int totalRows, int visibleRows) {
            try {
                gg.fill(x, y, x + w, y + h, 0xFF161616);
                if (totalRows <= visibleRows) {
                    gg.fill(x, y, x + w, y + h, 0xFF6A6A6A);
                    return;
                }

                int thumbH = Math.max(10, (int) Math.floor(h * (visibleRows / (double) totalRows)));
                int travel = Math.max(1, h - thumbH);
                int thumbY = y + (int) Math.round((scrollRow / (double) Math.max(1, maxRow)) * travel);
                gg.fill(x, thumbY, x + w, thumbY + thumbH, 0xFF6A6A6A);
            } catch (Throwable ignored) {}
        }

        private boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0) {
                return false;
            }
            if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) {
                return false;
            }
            dragging = true;
            lastMouseY = mouseY;
            return true;
        }

        private boolean mouseReleased(double mouseX, double mouseY, int button) {
            if (button == 0 && dragging) {
                dragging = false;
                return true;
            }
            return false;
        }

        private boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            if (!dragging || button != 0) {
                return false;
            }
            lastMouseY = mouseY;
            return true;
        }

        private int getScrollRowFromThumb(int currentScroll, int maxScroll) {
            if (maxScroll <= 0) {
                return 0;
            }
            double thumbPos = (lastMouseY - y) / Math.max(1.0D, h);
            return (int) Math.round(Math.max(0.0D, Math.min(1.0D, thumbPos)) * maxScroll);
        }
    }
}
