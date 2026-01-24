package org.z2six.villageroverhaul.client;

import net.minecraft.ChatFormatting;
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

public final class ItemListEditorScreen extends Screen {

    private final Screen parent;
    private final List<String> list;
    private final String title;

    private EditBox manualBox;
    private EditBox searchBox;

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

    private static final int PANEL_W = 360;
    private static final int PANEL_H = 230;
    private static final int PAD = 10;

    private static final int PANEL_BG = 0xCC0B0B0B;
    private static final int PANEL_BORDER = 0xFF3A3A3A;

    private static final int ROW_H = 16;
    private static final int ROW_GAP = 4;
    private static final int ROWS_VISIBLE = 8;

    public ItemListEditorScreen(Screen parent, List<String> list, String title) {
        super(Component.literal("Item List"));
        this.parent = parent;
        this.list = list == null ? new ArrayList<>() : list;
        this.title = title == null ? "Item List" : title;
    }

    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // no-op (prevents NeoForge background blur overlay)
    }

    @Override
    protected void init() {
        super.init();

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        int splitW = (PANEL_W - PAD * 3) / 2;

        leftPanelX = left + PAD;
        leftPanelY = top + PAD + 22;
        leftPanelW = splitW;
        leftPanelH = PANEL_H - PAD * 2 - 22;

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

        int listStartY = leftPanelY + 22;
        int listBtnW = leftPanelW - 8;

        for (int i = 0; i < ROWS_VISIBLE; i++) {
            int y = listStartY + i * (ROW_H + ROW_GAP);
            Button b = Button.builder(Component.literal(""), btn -> {
                        String id = btn.getMessage() == null ? "" : btn.getMessage().getString();
                        if (id == null || id.isBlank()) return;
                        removeId(id);
                        updateLeftButtons();
                        updateRightButtons();
                    })
                    .pos(leftPanelX + 4, y)
                    .size(listBtnW, ROW_H)
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
                        addId(id);
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

        addRenderableWidget(
                Button.builder(Component.literal("Back"), b -> onClose())
                        .pos(left + PANEL_W - 58 - PAD, top + PAD)
                        .size(58, 18)
                        .build()
        );

        updateLeftButtons();
        updateRightButtons();
    }

    private void updateLeftButtons() {
        List<String> sorted = new ArrayList<>();
        for (String s : list) {
            if (s == null) continue;
            String id = s.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) continue;
            sorted.add(id);
        }
        sorted.sort(Comparator.naturalOrder());

        int maxScroll = Math.max(0, sorted.size() - ROWS_VISIBLE);
        if (leftScroll > maxScroll) leftScroll = maxScroll;
        if (leftScroll < 0) leftScroll = 0;

        for (int i = 0; i < leftButtons.size(); i++) {
            Button b = leftButtons.get(i);
            int idx = leftScroll + i;
            if (idx >= sorted.size()) {
                b.visible = false;
                b.active = false;
                b.setMessage(Component.literal(""));
                continue;
            }
            String id = sorted.get(idx);
            b.visible = true;
            b.active = true;
            b.setMessage(Component.literal(id));
        }
    }

    private void updateRightButtons() {
        List<String> matches = new ArrayList<>();
        String q = searchBox == null ? "" : searchBox.getValue();
        q = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);

        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            if (id == null) continue;
            String s = id.toString();
            if (q.isEmpty() || s.contains(q)) {
                matches.add(s);
            }
        }

        matches.sort(Comparator.naturalOrder());

        int maxScroll = Math.max(0, matches.size() - ROWS_VISIBLE);
        if (rightScroll > maxScroll) rightScroll = maxScroll;
        if (rightScroll < 0) rightScroll = 0;

        for (int i = 0; i < rightButtons.size(); i++) {
            Button b = rightButtons.get(i);
            int idx = rightScroll + i;
            if (idx >= matches.size()) {
                b.visible = false;
                b.active = false;
                b.setMessage(Component.literal(""));
                continue;
            }
            String id = matches.get(idx);
            boolean already = containsId(id);
            b.visible = true;
            b.active = !already;
            b.setMessage(already
                    ? Component.literal(id).withStyle(ChatFormatting.DARK_GRAY)
                    : Component.literal(id));
        }
    }

    private void addManual() {
        if (manualBox == null) return;
        String raw = manualBox.getValue();
        if (raw == null || raw.isBlank()) return;
        String[] parts = raw.split("[,\\s]+");
        if (parts.length <= 0) return;
        addId(parts[0]);
        manualBox.setValue("");
        updateLeftButtons();
        updateRightButtons();
    }

    private void addId(String id) {
        if (id == null || id.isBlank()) return;
        String norm = id.trim().toLowerCase(Locale.ROOT);
        if (norm.isEmpty()) return;
        try {
            ResourceLocation.parse(norm);
        } catch (Throwable ignored) {
            return;
        }
        for (String s : list) {
            if (s != null && s.equalsIgnoreCase(norm)) return;
        }
        list.add(norm);
    }

    private void removeId(String id) {
        if (id == null || id.isBlank()) return;
        String norm = id.trim().toLowerCase(Locale.ROOT);
        list.removeIf(s -> s != null && s.equalsIgnoreCase(norm));
    }

    private boolean containsId(String id) {
        if (id == null || id.isBlank()) return false;
        String norm = id.trim().toLowerCase(Locale.ROOT);
        for (String s : list) {
            if (s != null && s.equalsIgnoreCase(norm)) return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        boolean handled = false;
        if (isInside(mouseX, mouseY, leftPanelX, leftPanelY, leftPanelW, leftPanelH)) {
            leftScroll -= (int) Math.signum(scrollY);
            updateLeftButtons();
            handled = true;
        }
        if (isInside(mouseX, mouseY, rightPanelX, rightPanelY, rightPanelW, rightPanelH)) {
            rightScroll -= (int) Math.signum(scrollY);
            updateRightButtons();
            handled = true;
        }
        return handled || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private static boolean isInside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx <= (x + w) && my <= (y + h);
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

        gg.drawString(font, "Selected", leftPanelX + 4, leftPanelY - 12, 0xFFBFBFBF, false);
        gg.drawString(font, "All items", rightPanelX + 4, rightPanelY - 12, 0xFFBFBFBF, false);

        drawPanel(gg, leftPanelX, leftPanelY, leftPanelW, leftPanelH);
        drawPanel(gg, rightPanelX, rightPanelY, rightPanelW, rightPanelH);

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
