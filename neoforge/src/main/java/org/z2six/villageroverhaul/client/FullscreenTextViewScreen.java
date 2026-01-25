package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Fullscreen multi-column reader for long text lists (overview/history).
 * Keeps the NeoForge blur overlay disabled.
 */
public final class FullscreenTextViewScreen extends Screen {

    private final Screen parent;
    private final String titleText;
    private final List<Component> raw;

    private List<List<FormattedCharSequence>> columns = List.of();
    private int maxColumnLines = 0;
    private int scrollRow = 0;

    private int contentX, contentY, contentW, contentH;
    private int cols = 1;
    private int colW = 0;
    private int colGap = 12;
    private int rowH = 10;

    public FullscreenTextViewScreen(Screen parent, String title, List<Component> raw) {
        super(Component.empty());
        this.parent = parent;
        this.titleText = title == null ? "" : title;
        this.raw = raw == null ? List.of() : raw;
    }

    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // no-op (prevents NeoForge background blur overlay)
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

        rebuildColumns(true);
    }

    private void rebuildColumns(boolean resetScroll) {
        try {
            Font font = Minecraft.getInstance().font;
            rowH = Math.max(10, font.lineHeight + 2);

            int minColW = 260;
            int maxCols = Math.max(1, Math.min(4, (contentW + colGap) / (minColW + colGap)));
            cols = Math.max(1, maxCols);

            // Recompute width per column
            int totalGap = (cols - 1) * colGap;
            colW = Math.max(80, (contentW - totalGap) / cols);
            int wrapW = Math.max(40, colW - 8);

            // Wrap per component (keep blocks together), then greedily balance across columns.
            List<List<FormattedCharSequence>> blocks = new ArrayList<>();
            for (Component c : raw) {
                if (c == null) continue;
                List<FormattedCharSequence> split = font.split(c, wrapW);
                if (split == null || split.isEmpty()) {
                    blocks.add(List.of(FormattedCharSequence.EMPTY));
                } else {
                    blocks.add(split);
                }
            }

            List<List<List<FormattedCharSequence>>> colBlocks = new ArrayList<>();
            List<Integer> heights = new ArrayList<>();
            for (int i = 0; i < cols; i++) {
                colBlocks.add(new ArrayList<>());
                heights.add(0);
            }

            for (List<FormattedCharSequence> b : blocks) {
                // place into column with smallest current height
                int best = 0;
                int bestH = heights.get(0);
                for (int i = 1; i < cols; i++) {
                    int h = heights.get(i);
                    if (h < bestH) {
                        bestH = h;
                        best = i;
                    }
                }
                colBlocks.get(best).add(b);
                heights.set(best, heights.get(best) + b.size());
            }

            List<List<FormattedCharSequence>> outCols = new ArrayList<>();
            maxColumnLines = 0;
            for (int i = 0; i < cols; i++) {
                List<FormattedCharSequence> flat = new ArrayList<>();
                for (List<FormattedCharSequence> b : colBlocks.get(i)) {
                    flat.addAll(b);
                }
                outCols.add(flat);
                if (flat.size() > maxColumnLines) maxColumnLines = flat.size();
            }
            this.columns = outCols;

            if (resetScroll) scrollRow = 0;
            clampScroll();
        } catch (Throwable ignored) {
            this.columns = List.of(List.of(FormattedCharSequence.EMPTY));
            this.cols = 1;
            this.colW = contentW;
            this.maxColumnLines = 1;
            this.scrollRow = 0;
        }
    }

    private void clampScroll() {
        int visible = Math.max(1, contentH / rowH);
        int maxScroll = Math.max(0, maxColumnLines - visible);
        if (scrollRow < 0) scrollRow = 0;
        if (scrollRow > maxScroll) scrollRow = maxScroll;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0.0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        if (mouseX < contentX || mouseX > contentX + contentW || mouseY < contentY || mouseY > contentY + contentH) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        scrollRow -= (int) Math.signum(scrollY);
        clampScroll();
        return true;
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // Darken background for readability
        gg.fill(0, 0, this.width, this.height, 0xCC0B0B0B);

        Font font = Minecraft.getInstance().font;
        gg.drawString(font, titleText, 12, 16, 0xFFFFFFFF, true);

        // Content area frame
        gg.fill(contentX, contentY, contentX + contentW, contentY + contentH, 0xFF101010);
        gg.fill(contentX, contentY, contentX + contentW, contentY + 1, 0xFF2E2E2E);
        gg.fill(contentX, contentY + contentH - 1, contentX + contentW, contentY + contentH, 0xFF2E2E2E);
        gg.fill(contentX, contentY, contentX + 1, contentY + contentH, 0xFF2E2E2E);
        gg.fill(contentX + contentW - 1, contentY, contentX + contentW, contentY + contentH, 0xFF2E2E2E);

        int visible = Math.max(1, contentH / rowH);
        int start = scrollRow;
        int end = start + visible;

        for (int c = 0; c < columns.size(); c++) {
            int x = contentX + c * (colW + colGap) + 4;
            int w = Math.max(1, colW - 8);

            boolean scissor = false;
            try {
                gg.enableScissor(x, contentY + 2, x + w, contentY + contentH - 2);
                scissor = true;
            } catch (Throwable ignored) {}

            try {
                List<FormattedCharSequence> lines = columns.get(c);
                int y = contentY + 4;
                for (int i = start; i < end; i++) {
                    if (i >= 0 && i < lines.size()) {
                        gg.drawString(font, lines.get(i), x, y, 0xFFFFFFFF, false);
                    }
                    y += rowH;
                }
            } finally {
                if (scissor) {
                    try { gg.disableScissor(); } catch (Throwable ignored) {}
                }
            }
        }

        super.render(gg, mouseX, mouseY, partialTick);
    }

    @Override
    public void resize(Minecraft mc, int width, int height) {
        super.resize(mc, width, height);
        rebuildColumns(false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
