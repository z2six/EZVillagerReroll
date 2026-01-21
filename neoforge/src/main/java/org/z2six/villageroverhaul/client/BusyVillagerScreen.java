// neoforge\src\main\java\org\z2six\villageroverhaul\client\BusyVillagerScreen.java
package org.z2six.villageroverhaul.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.autoReroll.PacketCancelAutoSearch;
import org.z2six.villageroverhaul.network.autoReroll.PacketContinueAutoSearch;
import org.z2six.villageroverhaul.network.autoReroll.PacketOpenBusyScreen;

import java.util.ArrayList;
import java.util.List;

public final class BusyVillagerScreen extends Screen {

    private final int villagerEntityId;
    private final List<ItemStack> requested;
    private final boolean canCancel;

    private Button btnCancel;
    private Button btnContinue;

    // Grid/scroll
    private int scrollRow = 0;

    private static final int ITEM_SIZE = 18;
    private static final int PAD = 4;
    private static final int SCROLLBAR_W = 8;

    private SimpleScrollBar scrollBar;

    public BusyVillagerScreen(PacketOpenBusyScreen msg) {
        this(msg == null ? -1 : msg.villagerEntityId(), msg == null ? List.of() : msg.requested(), msg != null && msg.canCancel());
    }

    public BusyVillagerScreen(int villagerEntityId, List<ItemStack> requested) {
        this(villagerEntityId, requested, true);
    }

    public BusyVillagerScreen(int villagerEntityId, List<ItemStack> requested, boolean canCancel) {
        super(Component.translatable("ezvr.busy.title"));
        this.villagerEntityId = villagerEntityId;
        this.requested = requested == null ? List.of() : new ArrayList<>(requested);
        this.canCancel = canCancel;
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
                            VillagerOverhaul.LOG().debug("[VillagerOverhaul] BusyVillagerScreen: sent cancel request (villagerEntityId={})", villagerEntityId);
                        } catch (Throwable t) {
                            VillagerOverhaul.LOG().error("[VillagerOverhaul] BusyVillagerScreen: cancel send failed", t);
                        }
                        tryClose();
                    })
                    .pos(cx - 110, y)
                    .size(100, 20)
                    .build();
            btnCancel.active = canCancel;

            btnContinue = Button.builder(Component.translatable("ezvr.busy.continue"), b -> {
                        try {
                            ClientNetwork.sendToServer(new PacketContinueAutoSearch(villagerEntityId));
                            VillagerOverhaul.LOG().debug("[VillagerOverhaul] BusyVillagerScreen: continue pressed (villagerEntityId={})", villagerEntityId);
                        } catch (Throwable t) {
                            VillagerOverhaul.LOG().error("[VillagerOverhaul] BusyVillagerScreen: continue send failed", t);
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
            VillagerOverhaul.LOG().error("[VillagerOverhaul] BusyVillagerScreen.init failed", t);
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
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] BusyVillagerScreen.tryClose failed (soft): {}", t.toString());
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

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] BusyVillagerScreen mouseScrolled scrollY={} -> scrollRow={}", scrollY, scrollRow);
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
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] BusyVillagerScreen tooltip render failed (soft): {}", t.toString());
                }
            }

            // Scrollbar
            int totalRows = computeTotalRows();
            int maxRow = Math.max(0, totalRows - visibleRows);
            if (scrollBar != null) {
                scrollBar.render(gg, scrollRow, maxRow, totalRows, visibleRows);
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] BusyVillagerScreen.render failed", t);
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
