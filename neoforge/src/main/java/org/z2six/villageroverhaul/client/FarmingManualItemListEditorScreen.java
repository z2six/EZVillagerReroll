package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Simple two-panel editor for a list of item ids (strings).
 * Used by manual farming harvest/plant lists.
 */
public final class FarmingManualItemListEditorScreen extends Screen {

    private final Screen parent;
    private final List<String> items;
    private final String title;

    private EditBox manualBox;
    private EditBox searchBox;
    private Button removeBtn;

    private final List<Button> leftButtons = new ArrayList<>();
    private final List<Button> rightButtons = new ArrayList<>();

    private int leftScroll = 0;
    private int rightScroll = 0;

    private int leftPanelX;
    private int leftPanelY;
    private int leftPanelW;
    private int leftPanelH;

    private int rightPanelX;
    private int rightPanelY;
    private int rightPanelW;
    private int rightPanelH;

    private String selectedId = "";

    // Match VillagerInfoScreen sizing for consistent UI.
    private static final int PANEL_W = 316;
    private static final int PANEL_H = 206;
    private static final int PAD = 10;

    private static final int PANEL_BG = 0xCC0B0B0B;
    private static final int PANEL_BORDER = 0xFF3A3A3A;

    private static final int ROW_H = 16;
    private static final int ROW_GAP = 4;
    private static final int ROWS_VISIBLE = 6;
    private static final int TOP_BAR_H = 26;

    public FarmingManualItemListEditorScreen(Screen parent, List<String> items, String title) {
        super(Component.literal("Manual Farming: Items"));
        this.parent = parent;
        this.items = items == null ? new ArrayList<>() : items;
        this.title = title == null ? "Manual farming: items" : title;
    }

    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // no-op (prevents NeoForge background blur overlay)
    }

    @Override
    protected void init() {
        super.init();

        // init() can be called multiple times (e.g., window resize). The game clears widgets, but our lists don't.
        leftButtons.clear();
        rightButtons.clear();

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        int splitW = (PANEL_W - PAD * 3) / 2;

        leftPanelX = left + PAD;
        leftPanelY = top + PAD + TOP_BAR_H;
        leftPanelW = splitW;
        leftPanelH = PANEL_H - PAD * 2 - TOP_BAR_H;

        rightPanelX = leftPanelX + splitW + PAD;
        rightPanelY = leftPanelY;
        rightPanelW = splitW;
        rightPanelH = leftPanelH;

        manualBox = new EditBox(this.font, leftPanelX + 4, leftPanelY + 2, leftPanelW - 42, 16, Component.literal("Add"));
        addRenderableWidget(manualBox);

        Button addBtn = Button.builder(Component.literal("Add"), b -> addManual())
                .pos(leftPanelX + leftPanelW - 34, leftPanelY + 2)
                .size(30, 16)
                .build();
        addRenderableWidget(addBtn);

        searchBox = new EditBox(this.font, rightPanelX + 4, rightPanelY + 2, rightPanelW - 8, 16, Component.literal("Search"));
        addRenderableWidget(searchBox);
        try {
            searchBox.setResponder(s -> {
                rightScroll = 0;
                updateRightButtons();
            });
        } catch (Throwable ignored) {}

        int leftListStartY = leftPanelY + 22;
        int leftBtnW = leftPanelW - 8;
        for (int i = 0; i < ROWS_VISIBLE; i++) {
            int y = leftListStartY + i * (ROW_H + ROW_GAP);
            Button b = Button.builder(Component.literal(""), btn -> {
                        String label = btn.getMessage() == null ? "" : btn.getMessage().getString();
                        String id = parseIdFromLabel(label);
                        if (id.isBlank()) return;
                        select(id);
                    })
                    .pos(leftPanelX + 4, y)
                    .size(leftBtnW, ROW_H)
                    .build();
            b.visible = false;
            b.active = false;
            addRenderableWidget(b);
            leftButtons.add(b);
        }

        int rightListStartY = rightPanelY + 22;
        int rightBtnW = rightPanelW - 8;
        for (int i = 0; i < ROWS_VISIBLE; i++) {
            int y = rightListStartY + i * (ROW_H + ROW_GAP);
            Button b = Button.builder(Component.literal(""), btn -> {
                        String id = btn.getMessage() == null ? "" : btn.getMessage().getString();
                        if (id == null || id.isBlank()) return;
                        addItemId(id);
                        select(id);
                        updateLeftButtons();
                        updateRightButtons();
                    })
                    .pos(rightPanelX + 4, y)
                    .size(rightBtnW, ROW_H)
                    .build();
            b.visible = false;
            b.active = false;
            addRenderableWidget(b);
            rightButtons.add(b);
        }

        int editY = leftPanelY + leftPanelH - 22;
        int removeW = 18;
        int removeX = leftPanelX + leftPanelW - removeW - 4;

        removeBtn = Button.builder(Component.literal("X"), b -> removeSelected())
                .pos(removeX, editY)
                .size(removeW, 16)
                .build();
        addRenderableWidget(removeBtn);

        addRenderableWidget(
                Button.builder(Component.literal("Back"), b -> onClose())
                        .pos(left + PANEL_W - 58 - PAD, top + PAD)
                        .size(58, 18)
                        .build()
        );

        // If we already have entries, auto-select the first one.
        if (selectedId.isBlank() && !items.isEmpty()) {
            String first = String.valueOf(items.get(0));
            if (!first.isBlank()) selectedId = first;
        }

        updateLeftButtons();
        updateRightButtons();
        updateSelectedWidgets();
    }

    private void addManual() {
        if (manualBox == null) return;
        String raw = manualBox.getValue();
        if (raw == null || raw.isBlank()) return;
        String[] parts = raw.split("[,\\s]+");
        if (parts.length <= 0) return;
        String id = parts[0];
        if (id == null || id.isBlank()) return;
        addItemId(id);
        select(id);
        manualBox.setValue("");
        updateLeftButtons();
        updateRightButtons();
    }

    private void addItemId(String id) {
        if (id == null || id.isBlank()) return;
        String norm = id.trim().toLowerCase(Locale.ROOT);
        if (norm.isEmpty()) return;
        try {
            ResourceLocation.parse(norm);
        } catch (Throwable ignored) {
            return;
        }
        for (String e : items) {
            if (e != null && e.equalsIgnoreCase(norm)) return;
        }
        items.add(norm);
    }

    private void select(String id) {
        if (id == null) id = "";
        selectedId = id.trim().toLowerCase(Locale.ROOT);
        updateSelectedWidgets();
        updateLeftButtons();
    }

    private void updateSelectedWidgets() {
        try {
            boolean has = !selectedId.isBlank() && containsId(selectedId);
            if (removeBtn != null) {
                removeBtn.active = has;
                removeBtn.visible = true;
            }
        } catch (Throwable ignored) {}
    }

    private void removeSelected() {
        try {
            if (selectedId == null || selectedId.isBlank()) return;
            items.removeIf(s -> s != null && s.equalsIgnoreCase(selectedId));
            selectedId = "";
            if (!items.isEmpty()) selectedId = String.valueOf(items.get(0));
            updateSelectedWidgets();
            updateLeftButtons();
            updateRightButtons();
        } catch (Throwable ignored) {}
    }

    private boolean containsId(String id) {
        if (id == null || id.isBlank()) return false;
        for (String s : items) {
            if (s != null && s.equalsIgnoreCase(id)) return true;
        }
        return false;
    }

    private void updateLeftButtons() {
        try {
            List<String> sorted = new ArrayList<>();
            for (String s : items) {
                if (s == null || s.isBlank()) continue;
                sorted.add(s.trim().toLowerCase(Locale.ROOT));
            }
            sorted.sort(Comparator.naturalOrder());

            int total = sorted.size();
            int maxScroll = Math.max(0, total - ROWS_VISIBLE);
            if (leftScroll > maxScroll) leftScroll = maxScroll;
            if (leftScroll < 0) leftScroll = 0;

            for (int i = 0; i < leftButtons.size(); i++) {
                Button b = leftButtons.get(i);
                int idx = leftScroll + i;
                if (idx >= 0 && idx < total) {
                    String id = sorted.get(idx);
                    boolean selected = id.equalsIgnoreCase(selectedId);
                    String label = id + (selected ? " \u2190" : "");
                    b.setMessage(Component.literal(label));
                    b.visible = true;
                    b.active = true;
                } else {
                    b.setMessage(Component.literal(""));
                    b.visible = false;
                    b.active = false;
                }
            }
        } catch (Throwable ignored) {}
    }

    private void updateRightButtons() {
        try {
            String q = searchBox == null ? "" : searchBox.getValue();
            String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);

            List<String> all = new ArrayList<>();
            for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
                if (id == null) continue;
                String s = id.toString();
                if (!query.isEmpty() && !s.contains(query)) continue;
                if (containsId(s)) continue;
                all.add(s);
            }
            all.sort(Comparator.naturalOrder());

            int total = all.size();
            int maxScroll = Math.max(0, total - ROWS_VISIBLE);
            if (rightScroll > maxScroll) rightScroll = maxScroll;
            if (rightScroll < 0) rightScroll = 0;

            for (int i = 0; i < rightButtons.size(); i++) {
                Button b = rightButtons.get(i);
                int idx = rightScroll + i;
                if (idx >= 0 && idx < total) {
                    String id = all.get(idx);
                    b.setMessage(Component.literal(id));
                    b.visible = true;
                    b.active = true;
                } else {
                    b.setMessage(Component.literal(""));
                    b.visible = false;
                    b.active = false;
                }
            }
        } catch (Throwable ignored) {}
    }

    private static String parseIdFromLabel(String label) {
        if (label == null) return "";
        String s = label.trim();
        int arrow = s.indexOf('\u2190');
        if (arrow >= 0) s = s.substring(0, arrow).trim();
        return s;
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

        Font font = Minecraft.getInstance().font;
        gg.drawString(font, title, left + PAD, top + PAD + 5, 0xFFFFFFFF, true);

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
