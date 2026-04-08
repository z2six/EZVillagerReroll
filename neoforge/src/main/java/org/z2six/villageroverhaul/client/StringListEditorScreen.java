package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class StringListEditorScreen extends Screen {

    private static final int PANEL_W = 320;
    private static final int PANEL_H = 220;
    private static final int PAD = 10;
    private static final int ROW_H = 16;
    private static final int ROW_GAP = 4;
    private static final int ROWS_VISIBLE = 8;

    private static final int PANEL_BG = 0xCC0B0B0B;
    private static final int PANEL_BORDER = 0xFF3A3A3A;

    private final Screen parent;
    private final List<String> list;
    private final String title;
    private final String inputHint;

    private EditBox inputBox;
    private final List<Button> rowButtons = new ArrayList<>();
    private int scroll = 0;

    private int listX;
    private int listY;
    private int listW;
    private int listH;

    public StringListEditorScreen(Screen parent, List<String> list, String title, String inputHint) {
        super(Component.literal(title == null ? "List Editor" : title));
        this.parent = parent;
        this.list = list == null ? new ArrayList<>() : list;
        this.title = title == null ? "List Editor" : title;
        this.inputHint = inputHint == null ? "Add entry" : inputHint;
    }

    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // no-op
    }

    @Override
    protected void init() {
        super.init();

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        listX = left + PAD;
        listY = top + PAD + 44;
        listW = PANEL_W - PAD * 2;
        listH = PANEL_H - PAD * 2 - 44;

        inputBox = new EditBox(this.font, left + PAD, top + PAD + 20, PANEL_W - PAD * 2 - 40, 16, Component.literal(inputHint));
        addRenderableWidget(inputBox);

        addRenderableWidget(
                Button.builder(Component.literal("Add"), b -> addManual())
                        .pos(left + PANEL_W - PAD - 34, top + PAD + 20)
                        .size(34, 16)
                        .build()
        );

        int buttonW = listW - 8;
        int rowsStartY = listY + 4;
        for (int i = 0; i < ROWS_VISIBLE; i++) {
            int y = rowsStartY + i * (ROW_H + ROW_GAP);
            Button b = Button.builder(Component.literal(""), btn -> {
                        String value = btn.getMessage() == null ? "" : btn.getMessage().getString();
                        if (value.isBlank()) return;
                        removeValue(value);
                        updateRows();
                    })
                    .pos(listX + 4, y)
                    .size(buttonW, ROW_H)
                    .build();
            b.visible = false;
            b.active = false;
            addRenderableWidget(b);
            rowButtons.add(b);
        }

        addRenderableWidget(
                Button.builder(Component.literal("Back"), b -> onClose())
                        .pos(left + PANEL_W - PAD - 58, top + PAD)
                        .size(58, 18)
                        .build()
        );

        updateRows();
    }

    private void addManual() {
        if (inputBox == null) return;
        String raw = inputBox.getValue();
        if (raw == null || raw.isBlank()) return;

        String[] parts = raw.split("[,\\s]+");
        for (String part : parts) {
            addValue(part);
        }

        inputBox.setValue("");
        updateRows();
    }

    private void addValue(String value) {
        if (value == null || value.isBlank()) return;
        String normalized = value.trim();
        if (normalized.isEmpty()) return;
        for (String existing : list) {
            if (existing != null && existing.equalsIgnoreCase(normalized)) return;
        }
        list.add(normalized);
    }

    private void removeValue(String value) {
        if (value == null || value.isBlank()) return;
        list.removeIf(existing -> existing != null && existing.equalsIgnoreCase(value.trim()));
    }

    private void updateRows() {
        List<String> items = new ArrayList<>();
        for (String value : list) {
            if (value == null) continue;
            String trimmed = value.trim();
            if (trimmed.isEmpty()) continue;
            items.add(trimmed);
        }
        items.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));

        int maxScroll = Math.max(0, items.size() - ROWS_VISIBLE);
        if (scroll < 0) scroll = 0;
        if (scroll > maxScroll) scroll = maxScroll;

        for (int i = 0; i < rowButtons.size(); i++) {
            Button b = rowButtons.get(i);
            int idx = scroll + i;
            if (idx >= items.size()) {
                b.visible = false;
                b.active = false;
                b.setMessage(Component.literal(""));
                continue;
            }
            b.visible = true;
            b.active = true;
            b.setMessage(Component.literal(items.get(idx)));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= listX && mouseX <= (listX + listW) && mouseY >= listY && mouseY <= (listY + listH)) {
            scroll -= (int) Math.signum(scrollY);
            updateRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        drawPanel(gg, left, top, PANEL_W, PANEL_H);
        drawPanel(gg, listX, listY, listW, listH);

        Font font = Minecraft.getInstance().font;
        gg.drawString(font, title, left + PAD, top + PAD + 5, 0xFFFFFFFF, true);
        gg.drawString(font, "Click an entry to remove it.", left + PAD, top + PAD + 30, 0xFFBFBFBF, false);

        super.render(gg, mouseX, mouseY, partialTick);
    }

    private static void drawPanel(GuiGraphics gg, int x, int y, int w, int h) {
        gg.fill(x, y, x + w, y + h, PANEL_BG);
        gg.fill(x, y, x + w, y + 1, PANEL_BORDER);
        gg.fill(x, y + h - 1, x + w, y + h, PANEL_BORDER);
        gg.fill(x, y, x + 1, y + h, PANEL_BORDER);
        gg.fill(x + w - 1, y, x + w, y + h, PANEL_BORDER);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
