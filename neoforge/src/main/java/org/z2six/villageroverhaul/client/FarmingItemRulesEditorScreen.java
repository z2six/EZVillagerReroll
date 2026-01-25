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
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.farming.FarmingSettings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Editor for per-item farming storage rules:
 * - each item has its own "stacks threshold" (0 = immediate).
 */
public final class FarmingItemRulesEditorScreen extends Screen {

    private final Screen parent;
    private final List<FarmingSettings.ItemRule> rules;
    private final String title;
    private final boolean withdrawMode;

    private EditBox manualBox;
    private EditBox searchBox;
    private EditBox triggerBox;
    private EditBox keepBox;
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
    private boolean suppressBoxApply = false;

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

    public FarmingItemRulesEditorScreen(Screen parent, List<FarmingSettings.ItemRule> rules, String title) {
        super(Component.literal("Farming: Item Rules"));
        this.parent = parent;
        this.rules = rules == null ? new ArrayList<>() : rules;
        this.title = title == null ? "Farming: item rules" : title;
        this.withdrawMode = this.title.toLowerCase(Locale.ROOT).contains("withdraw");
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
                        addRule(id, 0);
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

        int boxW = 34;
        int keepX = removeX - 4 - boxW;
        int triggerX = keepX - 4 - boxW;

        triggerBox = new EditBox(this.font, triggerX, editY, boxW, 16, Component.literal("Trigger"));
        triggerBox.setFilter(s -> s != null && s.matches("\\d{0,3}"));
        addRenderableWidget(triggerBox);
        try { triggerBox.setResponder(s -> { if (!suppressBoxApply) applySelectedFromBoxes(); }); } catch (Throwable ignored) {}

        keepBox = new EditBox(this.font, keepX, editY, boxW, 16, Component.literal("Keep"));
        keepBox.setFilter(s -> s != null && s.matches("\\d{0,3}"));
        addRenderableWidget(keepBox);
        try { keepBox.setResponder(s -> { if (!suppressBoxApply) applySelectedFromBoxes(); }); } catch (Throwable ignored) {}

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
        if (selectedId.isBlank() && !rules.isEmpty()) {
            String first = rules.get(0) == null ? "" : String.valueOf(rules.get(0).itemId);
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
        addRule(id, 0);
        select(id);
        manualBox.setValue("");
        updateLeftButtons();
        updateRightButtons();
    }

    private void addRule(String id, int stacksThreshold) {
        if (id == null || id.isBlank()) return;
        String norm = id.trim().toLowerCase(Locale.ROOT);
        if (norm.isEmpty()) return;
        try {
            ResourceLocation.parse(norm);
        } catch (Throwable ignored) {
            return;
        }
        for (FarmingSettings.ItemRule r : rules) {
            if (r != null && r.itemId != null && r.itemId.equalsIgnoreCase(norm)) return;
        }
        rules.add(new FarmingSettings.ItemRule(norm, stacksThreshold, 0));
    }

    private void select(String id) {
        if (id == null) id = "";
        // Commit any pending edits from the previous selection.
        try { applySelectedFromBoxes(); } catch (Throwable ignored) {}
        selectedId = id.trim().toLowerCase(Locale.ROOT);
        updateSelectedWidgets();
        updateLeftButtons();
    }

    private void updateSelectedWidgets() {
        try {
            FarmingSettings.ItemRule r = findRule(selectedId);
            boolean has = r != null;
            suppressBoxApply = true;
            if (triggerBox != null) {
                triggerBox.setEditable(has);
                triggerBox.setValue(has ? String.valueOf(Math.max(0, r.stacksThreshold)) : "");
            }
            if (keepBox != null) {
                keepBox.setEditable(has);
                keepBox.setValue(has ? String.valueOf(Math.max(0, r.keepStacks)) : "");
            }
            if (removeBtn != null) {
                removeBtn.active = has;
                removeBtn.visible = true;
            }
            suppressBoxApply = false;
        } catch (Throwable ignored) {}
        finally {
            suppressBoxApply = false;
        }
    }

    private void applySelectedFromBoxes() {
        try {
            FarmingSettings.ItemRule r = findRule(selectedId);
            if (r == null) return;

            int trigger = 0;
            try {
                String raw = triggerBox == null ? "" : triggerBox.getValue();
                trigger = raw == null || raw.isBlank() ? 0 : Integer.parseInt(raw.trim());
            } catch (Throwable ignored) { trigger = 0; }

            int keep = 0;
            try {
                String raw = keepBox == null ? "" : keepBox.getValue();
                keep = raw == null || raw.isBlank() ? 0 : Integer.parseInt(raw.trim());
            } catch (Throwable ignored) { keep = 0; }

            r.stacksThreshold = Math.max(0, trigger);
            r.keepStacks = Math.max(0, keep);
            updateLeftButtons();
        } catch (Throwable ignored) {}
    }

    private void renderTooltips(GuiGraphics gg, Font font, int mouseX, int mouseY) {
        try {
            if (gg == null || font == null) return;
            if (triggerBox != null && triggerBox.isMouseOver(mouseX, mouseY)) {
                String action = withdrawMode ? "withdraw" : "deposit";
                gg.renderTooltip(font,
                        Component.literal("Amount of item stacks that triggers the Villager to " + action + "."),
                        mouseX, mouseY);
                return;
            }
            if (keepBox != null && keepBox.isMouseOver(mouseX, mouseY)) {
                gg.renderTooltip(font,
                        Component.literal(withdrawMode
                                ? "How much to keep in the chest."
                                : "Amount of item stacks the Villager should keep in its inventory."),
                        mouseX, mouseY);
            }
        } catch (Throwable ignored) {}
    }

    private void removeSelected() {
        try {
            String id = selectedId == null ? "" : selectedId.trim().toLowerCase(Locale.ROOT);
            if (id.isBlank()) return;
            rules.removeIf(r -> r != null && r.itemId != null && r.itemId.equalsIgnoreCase(id));
            selectedId = "";
            if (!rules.isEmpty()) {
                FarmingSettings.ItemRule first = rules.get(0);
                if (first != null && first.itemId != null) selectedId = first.itemId;
            }
            updateLeftButtons();
            updateRightButtons();
            updateSelectedWidgets();
        } catch (Throwable ignored) {}
    }

    private void updateLeftButtons() {
        List<FarmingSettings.ItemRule> view = new ArrayList<>();
        for (FarmingSettings.ItemRule r : rules) {
            if (r == null || r.itemId == null) continue;
            String id = r.itemId.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) continue;
            view.add(r);
        }

        int maxScroll = Math.max(0, view.size() - ROWS_VISIBLE);
        if (leftScroll > maxScroll) leftScroll = maxScroll;
        if (leftScroll < 0) leftScroll = 0;

        for (int i = 0; i < leftButtons.size(); i++) {
            Button b = leftButtons.get(i);
            int idx = leftScroll + i;
            if (idx >= view.size()) {
                b.visible = false;
                b.active = false;
                b.setMessage(Component.literal(""));
                continue;
            }

            FarmingSettings.ItemRule r = view.get(idx);
            String id = r.itemId == null ? "" : r.itemId.trim().toLowerCase(Locale.ROOT);
            int t = Math.max(0, r.stacksThreshold);
            int k = Math.max(0, r.keepStacks);

            boolean selected = !selectedId.isBlank() && selectedId.equalsIgnoreCase(id);

            MutableComponent msg = Component.literal(id);
            msg.append(Component.literal(" (" + t + "/" + k + ")")
                    .withStyle(selected ? ChatFormatting.GOLD : ChatFormatting.GRAY));

            b.visible = true;
            b.active = true;
            b.setMessage(msg);
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
            boolean already = hasRule(id);
            b.visible = true;
            b.active = !already;
            b.setMessage(already
                    ? Component.literal(id).withStyle(ChatFormatting.DARK_GRAY)
                    : Component.literal(id));
        }
    }

    private boolean hasRule(String itemId) {
        if (itemId == null || itemId.isBlank()) return false;
        String norm = itemId.trim().toLowerCase(Locale.ROOT);
        for (FarmingSettings.ItemRule r : rules) {
            if (r != null && r.itemId != null && r.itemId.equalsIgnoreCase(norm)) return true;
        }
        return false;
    }

    private static String parseIdFromLabel(String label) {
        if (label == null) return "";
        String s = label.trim();
        int idx = s.indexOf(" (");
        if (idx > 0) return s.substring(0, idx).trim();
        int sp = s.indexOf(' ');
        if (sp > 0) return s.substring(0, sp).trim();
        return s;
    }

    private FarmingSettings.ItemRule findRule(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        String norm = itemId.trim().toLowerCase(Locale.ROOT);
        for (FarmingSettings.ItemRule r : rules) {
            if (r != null && r.itemId != null && r.itemId.equalsIgnoreCase(norm)) return r;
        }
        return null;
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
        try { applySelectedFromBoxes(); } catch (Throwable ignored) {}
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        drawPanel(gg, left, top, PANEL_W, PANEL_H);

        Font font = Minecraft.getInstance().font;
        gg.drawString(font, title, left + PAD, top + PAD + 2, 0xFFFFFFFF, true);

        gg.drawString(font, "Configured (click to edit)", leftPanelX + 4, leftPanelY - 12, 0xFFBFBFBF, false);
        gg.drawString(font, "All items", rightPanelX + 4, rightPanelY - 12, 0xFFBFBFBF, false);

        drawPanel(gg, leftPanelX, leftPanelY, leftPanelW, leftPanelH);
        drawPanel(gg, rightPanelX, rightPanelY, rightPanelW, rightPanelH);

        super.render(gg, mouseX, mouseY, partialTick);
        renderTooltips(gg, font, mouseX, mouseY);
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
