package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.z2six.villageroverhaul.config.ServerConfig;
import org.z2six.villageroverhaul.network.chatcommands.PacketPlayerChatCommandsQuery;
import org.z2six.villageroverhaul.network.chatcommands.PacketPlayerChatCommandsUpdate;

import java.util.ArrayList;
import java.util.List;

public final class PlayerChatCommandsScreen extends Screen {

    private static final int PANEL_W = 316;
    private static final int PANEL_H = 206;
    private static final int PAD = 6;
    private static final int MAX_PHRASE_LEN = 256;

    private static final int PANEL_BG = 0xFF101010;
    private static final int PANEL_BORDER = 0xFF2E2E2E;

    private static final int LIST_BG = 0xFF101010;
    private static final int LIST_BORDER = 0xFF2E2E2E;

    private static final int SCROLLBAR_W = 7;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 2;

    private final Screen parent;

    private Button backBtn;
    private Button saveBtn;
    private Button chainBtn;
    private Button caseBtn;

    private EditBox rangeBox;

    private final List<Row> rows = new ArrayList<>();
    private int scrollRow = 0;
    private SimpleScrollBar scrollBar;

    private boolean chain = false;
    private boolean caseSensitive = false;

    public PlayerChatCommandsScreen(Screen parent) {
        super(Component.literal("Chat Commands"));
        this.parent = parent;
    }

    /**
     * Keep this NO-OP.
     * We apply the blur/background once in render(), then draw our panel above it.
     */
    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // no-op
    }

    @Override
    protected void init() {
        super.init();

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        backBtn = Button.builder(Component.literal("Back"), b -> onBack())
                .pos(left + PANEL_W - 58 - PAD - 58 - 4, top + PAD)
                .size(58, 18)
                .build();
        saveBtn = Button.builder(Component.literal("Save"), b -> onSave())
                .pos(left + PANEL_W - 58 - PAD, top + PAD)
                .size(58, 18)
                .build();
        addRenderableWidget(backBtn);
        addRenderableWidget(saveBtn);

        int rowY = top + PAD + 22;

        rangeBox = new EditBox(this.font, left + PAD + 56, rowY, 48, 18, Component.literal("Range"));
        rangeBox.setValue(String.valueOf(Math.max(1, ServerConfig.customCommandsChatRadius)));
        rangeBox.setMaxLength(3);
        addRenderableWidget(rangeBox);

        chainBtn = Button.builder(Component.literal("Chain []"), b -> {
            chain = !chain;
            chainBtn.setMessage(Component.literal(chain ? "Chain [x]" : "Chain []"));
        }).pos(left + PAD + 112, rowY).size(72, 18).build();
        addRenderableWidget(chainBtn);

        caseBtn = Button.builder(Component.literal("Case sensitive []"), b -> {
            caseSensitive = !caseSensitive;
            caseBtn.setMessage(Component.literal(caseSensitive ? "Case sensitive [x]" : "Case sensitive []"));
        }).pos(left + PAD + 112 + 72 + 4, rowY).size(120, 18).build();
        addRenderableWidget(caseBtn);

        int listX = left + PAD;
        int listY = top + PAD + 46;
        int listW = PANEL_W - (PAD * 2) - SCROLLBAR_W - 2;
        int listH = top + PANEL_H - PAD - listY;
        scrollBar = new SimpleScrollBar(listX + listW + 2, listY, SCROLLBAR_W, listH);

        addChatRow(listX, listY, listW, "Help", "help");
        addChatRow(listX, listY, listW, "Equip", "equip");
        addChatRow(listX, listY, listW, "Stash", "stash");
        addChatRow(listX, listY, listW, "Neutral", "neutral");
        addChatRow(listX, listY, listW, "Idle", "idle");
        addChatRow(listX, listY, listW, "Follow", "follow");
        addChatRow(listX, listY, listW, "Patrol", "patrol");
        addChatRow(listX, listY, listW, "Manual farming", "manualFarming");
        addChatRow(listX, listY, listW, "Flee", "flee");
        addChatRow(listX, listY, listW, "Defend", "defend");
        addChatRow(listX, listY, listW, "Aggressive", "aggressive");
        updateRowPositions();

        try { ClientNetwork.sendToServer(new PacketPlayerChatCommandsQuery()); } catch (Throwable ignored) {}
    }

    private void addChatRow(int listX, int listY, int listW, String label, String key) {
        int boxW = Math.min(190, listW - 110);
        EditBox b = new EditBox(this.font, listX + 110, listY, boxW, 18, Component.literal(label));
        b.setMaxLength(MAX_PHRASE_LEN);
        addRenderableWidget(b);
        rows.add(new Row(label, key, b));
    }

    @Override
    public void tick() {
        super.tick();
        CompoundTag cfg = PlayerChatCommandsClientCache.consume();
        if (cfg != null) applyConfig(cfg);
    }

    private void applyConfig(CompoundTag cfg) {
        try {
            int r = cfg.getInt("range");
            if (r <= 0) r = Math.max(1, ServerConfig.customCommandsChatRadius);
            rangeBox.setValue(String.valueOf(r));
            chain = cfg.getBoolean("chain");
            chainBtn.setMessage(Component.literal(chain ? "Chain [x]" : "Chain []"));
            caseSensitive = cfg.getBoolean("caseSensitive");
            caseBtn.setMessage(Component.literal(caseSensitive ? "Case sensitive [x]" : "Case sensitive []"));

            for (Row row : rows) {
                if (row == null || row.box == null) continue;
                row.box.setValue(cfg.getString(row.key));
            }
        } catch (Throwable ignored) {}
    }

    private void onBack() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    private void onSave() {
        CompoundTag t = new CompoundTag();
        t.putInt("range", parseInt(rangeBox.getValue(), 1, 128));
        t.putBoolean("chain", chain);
        t.putBoolean("caseSensitive", caseSensitive);
        for (Row row : rows) {
            if (row == null || row.box == null) continue;
            t.putString(row.key, safeStr(row.box.getValue()));
        }
        try { ClientNetwork.sendToServer(new PacketPlayerChatCommandsUpdate(t)); } catch (Throwable ignored) {}
        onBack();
    }

    private static String safeStr(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() > MAX_PHRASE_LEN) t = t.substring(0, MAX_PHRASE_LEN);
        return t;
    }

    private static int parseInt(String s, int min, int max) {
        try {
            int v = Integer.parseInt(s.trim());
            if (v < min) v = min;
            if (v > max) v = max;
            return v;
        } catch (Throwable ignored) {
            return min;
        }
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // Apply blur/background once, then draw our panel above it.
        super.renderBackground(gg, mouseX, mouseY, partialTick);

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;
        drawPanel(gg, left, top, PANEL_W, PANEL_H, PANEL_BG, PANEL_BORDER);

        gg.drawString(this.font, Component.literal("Chat Commands"), left + PAD, top + PAD, 0xFFFFFF);
        gg.drawString(this.font, Component.literal("Range:"), left + PAD, top + PAD + 26, 0xC8C8C8);

        int listX = left + PAD;
        int listY = top + PAD + 46;
        int listW = PANEL_W - (PAD * 2) - SCROLLBAR_W - 2;
        int listH = top + PANEL_H - PAD - listY;
        drawPanel(gg, listX, listY, listW, listH, LIST_BG, LIST_BORDER);

        boolean scissor = false;
        try {
            gg.enableScissor(listX + 1, listY + 1, listX + listW - 1, listY + listH - 1);
            scissor = true;
        } catch (Throwable ignored) { scissor = false; }

        int y0 = listY + 4;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row == null) continue;
            int y = y0 + (i - scrollRow) * (ROW_H + ROW_GAP);
            if (y + ROW_H < listY || y > (listY + listH)) continue;
            gg.drawString(this.font, Component.literal(row.label + ":"), listX + 6, y + 4, 0xC8C8C8);
        }

        if (scissor) {
            try { gg.disableScissor(); } catch (Throwable ignored) {}
        }

        if (scrollBar != null) {
            int visibleRows = getVisibleRows();
            int maxRow = Math.max(0, rows.size() - visibleRows);
            scrollBar.render(gg, scrollRow, maxRow, rows.size(), visibleRows);
        }

        super.render(gg, mouseX, mouseY, partialTick);
        renderTooltips(gg, mouseX, mouseY);
    }

    private void renderTooltips(GuiGraphics gg, int mouseX, int mouseY) {
        try {
            if (rangeBox != null && rangeBox.isMouseOver(mouseX, mouseY)) {
                int max = Math.max(1, ServerConfig.customCommandsChatRadius);
                renderTooltipLines(gg, mouseX, mouseY,
                        "How far away villagers can hear your chat commands.",
                        "Server-configured maximum: " + max + " blocks (clamped server-side)."
                );
                return;
            }

            if (chainBtn != null && chainBtn.isMouseOver(mouseX, mouseY)) {
                renderTooltipLines(gg, mouseX, mouseY,
                        "When enabled: villagers also pass the command to nearby villagers (chain reaction).",
                        "Only villagers that allow passing will spread it further."
                );
                return;
            }

            if (caseBtn != null && caseBtn.isMouseOver(mouseX, mouseY)) {
                renderTooltipLines(gg, mouseX, mouseY,
                        "When enabled: chat commands must match EXACTLY (uppercase/lowercase matters).",
                        "When disabled: matching is case-insensitive."
                );
                return;
            }

            for (Row row : rows) {
                if (row == null || row.box == null) continue;
                if (!row.box.visible || !row.box.active) continue;
                if (!row.box.isMouseOver(mouseX, mouseY)) continue;

                String label = row.label == null ? "" : row.label;
                String extra = switch (row.key) {
                    case "help" -> "Triggers the \"Help\" combat behavior (temporary, target-driven).";
                    case "neutral" -> "Sets villagers to Neutral mode.";
                    case "idle" -> "Sets villagers to Idle mode.";
                    case "follow" -> "Sets villagers to Follow mode (follow you).";
                    case "patrol" -> "Sets villagers to Patrol (starts first saved patrol route, else Idle).";
                    case "manualFarming" -> "Turns on Manual Farming mode (Farmer + workstation required).";
                    case "flee" -> "Sets villagers to Flee mode.";
                    case "defend" -> "Sets villagers to Defend mode.";
                    case "aggressive" -> "Sets villagers to Aggressive mode.";
                    default -> "";
                };

                if (extra.isBlank()) {
                    renderTooltipLines(gg, mouseX, mouseY, "Leave empty to disable.");
                } else {
                    renderTooltipLines(gg, mouseX, mouseY, extra, "Leave empty to disable.");
                }
                return;
            }
        } catch (Throwable ignored) {}
    }

    private void renderTooltipLines(GuiGraphics gg, int mouseX, int mouseY, String... lines) {
        try {
            if (gg == null || this.font == null || lines == null || lines.length == 0) return;
            List<FormattedCharSequence> out = new ArrayList<>();
            for (String s : lines) {
                if (s == null || s.isBlank()) continue;
                out.add(Component.literal(s).getVisualOrderText());
            }
            if (out.isEmpty()) return;
            gg.renderTooltip(this.font, out, mouseX, mouseY);
        } catch (Throwable ignored) {}
    }

    private void updateRowPositions() {
        try {
            int left = (this.width - PANEL_W) / 2;
            int top = (this.height - PANEL_H) / 2;

            int rowY = top + PAD + 22;
            rangeBox.setX(left + PAD + 56);
            rangeBox.setY(rowY);
            chainBtn.setX(left + PAD + 112);
            chainBtn.setY(rowY);
            caseBtn.setX(left + PAD + 112 + 72 + 4);
            caseBtn.setY(rowY);
            backBtn.setPosition(left + PANEL_W - 58 - PAD - 58 - 4, top + PAD);
            saveBtn.setPosition(left + PANEL_W - 58 - PAD, top + PAD);

            int listX = left + PAD;
            int listY = top + PAD + 46;
            int listW = PANEL_W - (PAD * 2) - SCROLLBAR_W - 2;
            int listH = top + PANEL_H - PAD - listY;

            int visibleRows = getVisibleRows();
            int maxRow = Math.max(0, rows.size() - visibleRows);
            if (scrollRow < 0) scrollRow = 0;
            if (scrollRow > maxRow) scrollRow = maxRow;

            int y0 = listY + 2;
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                if (row == null || row.box == null) continue;
                int y = y0 + (i - scrollRow) * (ROW_H + ROW_GAP);
                // NOTE: EditBox does NOT respect our scissor, so we must hide rows that would render outside.
                boolean v = y >= listY && (y + ROW_H) <= (listY + listH);
                row.box.setX(listX + 110);
                row.box.setY(y);
                row.box.visible = v;
                row.box.active = v;
            }

            if (scrollBar != null) scrollBar.setBounds(listX + listW + 2, listY, SCROLLBAR_W, listH);
        } catch (Throwable ignored) {}
    }

    private int getVisibleRows() {
        try {
            int top = (this.height - PANEL_H) / 2;
            int listY = top + PAD + 46;
            int listH = top + PANEL_H - PAD - listY;
            return Math.max(1, (listH - 8) / (ROW_H + ROW_GAP));
        } catch (Throwable ignored) {
            return 4;
        }
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        super.resize(minecraft, width, height);
        updateRowPositions();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0.0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        try {
            int left = (this.width - PANEL_W) / 2;
            int top = (this.height - PANEL_H) / 2;
            int listX = left + PAD;
            int listY = top + PAD + 46;
            int listW = PANEL_W - (PAD * 2);
            int listH = top + PANEL_H - PAD - listY;
            boolean in = mouseX >= listX && mouseX < (listX + listW) && mouseY >= listY && mouseY < (listY + listH);
            if (!in) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
            scrollRow -= (int) Math.signum(scrollY);
            updateRowPositions();
        } catch (Throwable ignored) {}
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean handled = false;
        if (scrollBar != null) handled = scrollBar.mouseClicked(mouseX, mouseY, button);
        return handled || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = false;
        if (scrollBar != null) handled = scrollBar.mouseReleased(mouseX, mouseY, button);
        return handled || super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        boolean handled = false;
        if (scrollBar != null) {
            handled = scrollBar.mouseDragged(mouseX, mouseY, button, dragX, dragY);
            if (handled) {
                int maxRow = Math.max(0, rows.size() - getVisibleRows());
                scrollRow = scrollBar.getScrollRowFromThumb(scrollRow, maxRow);
                updateRowPositions();
            }
        }
        return handled || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    private static void drawPanel(GuiGraphics gg, int x, int y, int w, int h, int bg, int border) {
        try {
            gg.fill(x, y, x + w, y + h, bg);
            gg.fill(x, y, x + w, y + 1, border);
            gg.fill(x, y + h - 1, x + w, y + h, border);
            gg.fill(x, y, x + 1, y + h, border);
            gg.fill(x + w - 1, y, x + w, y + h, border);
        } catch (Throwable ignored) {}
    }

    private record Row(String label, String key, EditBox box) {}

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
        }

        void render(GuiGraphics gg, int scrollRow, int maxRow, int totalRows, int visibleRows) {
            try {
                gg.fill(x, y, x + w, y + h, 0xFF161616);

                if (totalRows <= visibleRows) {
                    gg.fill(x, y, x + w, y + h, 0xFF6A6A6A);
                    return;
                }

                int thumbH = Math.max(12, (int) Math.round((h * (visibleRows / (double) totalRows))));
                int travel = Math.max(1, h - thumbH);

                double t = (maxRow <= 0) ? 0.0 : (scrollRow / (double) maxRow);
                int thumbY = y + (int) Math.round(travel * t);

                gg.fill(x, thumbY, x + w, thumbY + thumbH, dragging ? 0xFFA0A0A0 : 0xFF6A6A6A);
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
