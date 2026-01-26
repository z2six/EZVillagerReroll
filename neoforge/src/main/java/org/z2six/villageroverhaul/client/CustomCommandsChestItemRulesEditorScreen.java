package org.z2six.villageroverhaul.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.network.customcommands.PacketCcSetChestRules;
import org.z2six.villageroverhaul.network.customcommands.PacketCcUpdateActionStepRules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Editor for a recorded CC deposit/withdraw step's item rules: item id -> item count.
 */
public final class CustomCommandsChestItemRulesEditorScreen extends Screen {

    private final Screen parent;
    private final int villagerEntityId;
    private final int actionIndex;
    private final int stepIndex;
    private final int kind; // 1=withdraw, 2=deposit
    private final List<PacketCcSetChestRules.Rule> initialRules;

    private static final int PANEL_W = 316;
    private static final int PANEL_H = 206;
    private static final int PAD = 10;

    private static final int PANEL_BG = 0xCC0B0B0B;
    private static final int PANEL_BORDER = 0xFF3A3A3A;

    private static final int ROW_H = 16;
    private static final int ROW_GAP = 4;
    private static final int ROWS_VISIBLE = 6;
    private static final int TOP_BAR_H = 26;

    private EditBox manualBox;
    private EditBox searchBox;
    private EditBox countBox;
    private Button removeBtn;

    private final List<Button> leftButtons = new ArrayList<>();
    private final List<Button> rightButtons = new ArrayList<>();
    private final List<PacketCcSetChestRules.Rule> leftView = new ArrayList<>();
    private final List<String> rightMatches = new ArrayList<>();

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

    private final List<PacketCcSetChestRules.Rule> rules = new ArrayList<>();

    public CustomCommandsChestItemRulesEditorScreen(int villagerEntityId, int stepIndex, int kind) {
        this(null, villagerEntityId, -1, stepIndex, kind, List.of());
    }

    public CustomCommandsChestItemRulesEditorScreen(Screen parent, int villagerEntityId, int actionIndex, int stepIndex, int kind, List<PacketCcSetChestRules.Rule> initialRules) {
        super(Component.literal("Chest rules"));
        this.parent = parent;
        this.villagerEntityId = villagerEntityId;
        this.actionIndex = actionIndex;
        this.stepIndex = stepIndex;
        this.kind = kind;
        this.initialRules = initialRules == null ? List.of() : initialRules;
    }

    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // no-op (prevents NeoForge background blur overlay)
    }

    @Override
    protected void init() {
        super.init();

        leftButtons.clear();
        rightButtons.clear();
        leftView.clear();
        rightMatches.clear();
        rules.clear();
        try { rules.addAll(initialRules); } catch (Throwable ignored) {}

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
        manualBox.setTooltip(Tooltip.create(Component.literal("Add an item by id (e.g. minecraft:wheat_seeds).")));
        addRenderableWidget(manualBox);

        Button addBtn = Button.builder(Component.literal("Add"), b -> addManual())
                .pos(leftPanelX + leftPanelW - 34, leftPanelY + 2)
                .size(30, 16)
                .build();
        addRenderableWidget(addBtn);

        countBox = new EditBox(this.font, leftPanelX + 4, leftPanelY + 22, leftPanelW - 8, 16, Component.literal("Count"));
        countBox.setMaxLength(6);
        countBox.setTooltip(Tooltip.create(Component.literal("Item count to move for the selected entry (not stacks).")));
        addRenderableWidget(countBox);
        try {
            countBox.setResponder(s -> {
                if (suppressBoxApply) return;
                applyCountToSelected();
            });
        } catch (Throwable ignored) {}

        removeBtn = Button.builder(Component.literal("Remove"), b -> removeSelected())
                .pos(leftPanelX + 4, leftPanelY + 42)
                .size(leftPanelW - 8, 16)
                .build();
        removeBtn.setTooltip(Tooltip.create(Component.literal("Remove the selected item rule.")));
        addRenderableWidget(removeBtn);

        searchBox = new EditBox(this.font, rightPanelX + 4, rightPanelY + 2, rightPanelW - 8, 16, Component.literal("Search"));
        addRenderableWidget(searchBox);
        try {
            searchBox.setResponder(s -> {
                rightScroll = 0;
                updateRightButtons();
            });
        } catch (Throwable ignored) {}

        Button btnSave = Button.builder(Component.literal("Save"), b -> onSave())
                .pos(left + PANEL_W - 10 - 60, top + 6)
                .size(60, 18)
                .build();
        btnSave.setTooltip(Tooltip.create(Component.literal("Save the item rules for this recorded chest step.")));
        addRenderableWidget(btnSave);

        Button btnCancel = Button.builder(Component.literal("Cancel"), b -> onClose())
                .pos(left + PANEL_W - 10 - 60 - 64, top + 6)
                .size(60, 18)
                .build();
        addRenderableWidget(btnCancel);

        // Pre-create row buttons (we only update message/visibility).
        int leftListStartY = leftPanelY + 62;
        for (int i = 0; i < ROWS_VISIBLE; i++) {
            final int row = i;
            Button b = Button.builder(Component.literal(""), bb -> {
                        int idx = leftScroll + row;
                        if (idx < 0 || idx >= leftView.size()) return;
                        PacketCcSetChestRules.Rule r = leftView.get(idx);
                        if (r == null) return;
                        selectedId = r.itemId();
                        updateSelectedUi();
                        updateLeftButtons();
                        updateRightButtons();
                    })
                    .pos(leftPanelX + 4, leftListStartY + i * (ROW_H + ROW_GAP))
                    .size(leftPanelW - 8, ROW_H)
                    .build();
            leftButtons.add(b);
            addRenderableWidget(b);
        }

        int rightListStartY = rightPanelY + 22;
        for (int i = 0; i < ROWS_VISIBLE; i++) {
            final int row = i;
            Button b = Button.builder(Component.literal(""), bb -> {
                        int idx = rightScroll + row;
                        if (idx < 0 || idx >= rightMatches.size()) return;
                        tryAddRule(rightMatches.get(idx), 1);
                        updateLeftButtons();
                        updateRightButtons();
                    })
                    .pos(rightPanelX + 4, rightListStartY + i * (ROW_H + ROW_GAP))
                    .size(rightPanelW - 8, ROW_H)
                    .build();
            rightButtons.add(b);
            addRenderableWidget(b);
        }

        updateLeftButtons();
        updateRightButtons();
        updateSelectedUi();
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            if (actionIndex >= 0 && parent != null) mc.setScreen(parent);
            else mc.setScreen(new CustomCommandsTeachMenuScreen(villagerEntityId));
        }
    }

    private void onSave() {
        try {
            if (actionIndex >= 0) {
                List<PacketCcUpdateActionStepRules.Rule> rr = new ArrayList<>();
                for (PacketCcSetChestRules.Rule r : rules) {
                    if (r == null) continue;
                    rr.add(new PacketCcUpdateActionStepRules.Rule(r.itemId(), r.count()));
                }
                ClientNetwork.sendToServer(new PacketCcUpdateActionStepRules(villagerEntityId, actionIndex, stepIndex, rr));
                ClientNetwork.sendToServer(new org.z2six.villageroverhaul.network.customcommands.PacketCcActionDetailQuery(villagerEntityId, actionIndex));
            } else {
                ClientNetwork.sendToServer(new PacketCcSetChestRules(villagerEntityId, stepIndex, new ArrayList<>(rules)));
            }
        } catch (Throwable ignored) {}
        onClose();
    }

    private void addManual() {
        try {
            String id = manualBox == null ? "" : manualBox.getValue();
            id = id == null ? "" : id.trim();
            if (id.isBlank()) return;
            tryAddRule(id, 1);
            manualBox.setValue("");
        } catch (Throwable ignored) {}
    }

    private void tryAddRule(String id, int count) {
        try {
            if (id == null) return;
            id = id.trim();
            if (id.isBlank()) return;
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) return;
            if (!BuiltInRegistries.ITEM.containsKey(rl)) return;

            for (PacketCcSetChestRules.Rule r : rules) {
                if (r != null && id.equals(r.itemId())) {
                    selectedId = id;
                    updateSelectedUi();
                    return;
                }
            }
            rules.add(new PacketCcSetChestRules.Rule(id, Math.max(0, count)));
            rules.sort(Comparator.comparing(a -> a == null ? "" : a.itemId()));
            selectedId = id;
            leftScroll = 0;
            updateLeftButtons();
            updateSelectedUi();
        } catch (Throwable ignored) {}
    }

    private void removeSelected() {
        try {
            if (selectedId == null || selectedId.isBlank()) return;
            rules.removeIf(r -> r != null && selectedId.equals(r.itemId()));
            selectedId = "";
            updateLeftButtons();
            updateSelectedUi();
        } catch (Throwable ignored) {}
    }

    private void applyCountToSelected() {
        try {
            if (selectedId == null || selectedId.isBlank()) return;
            int n = parseInt(countBox == null ? "" : countBox.getValue(), 1);
            if (n < 0) n = 0;
            for (int i = 0; i < rules.size(); i++) {
                PacketCcSetChestRules.Rule r = rules.get(i);
                if (r == null) continue;
                if (!selectedId.equals(r.itemId())) continue;
                rules.set(i, new PacketCcSetChestRules.Rule(r.itemId(), n));
                break;
            }
            updateLeftButtons();
        } catch (Throwable ignored) {}
    }

    private void updateSelectedUi() {
        try {
            boolean has = selectedId != null && !selectedId.isBlank();
            if (removeBtn != null) removeBtn.active = has;

            suppressBoxApply = true;
            if (!has) {
                if (countBox != null) countBox.setValue("");
            } else {
                int n = 0;
                for (PacketCcSetChestRules.Rule r : rules) {
                    if (r != null && selectedId.equals(r.itemId())) {
                        n = r.count();
                        break;
                    }
                }
                if (countBox != null) countBox.setValue(String.valueOf(n));
            }
        } catch (Throwable ignored) {
        } finally {
            suppressBoxApply = false;
        }
    }

    private void updateLeftButtons() {
        leftView.clear();
        for (PacketCcSetChestRules.Rule r : rules) {
            if (r == null || r.itemId() == null) continue;
            String id = r.itemId().trim();
            if (id.isBlank()) continue;
            leftView.add(r);
        }

        leftView.sort(Comparator.comparing(a -> a == null ? "" : a.itemId()));

        int maxScroll = Math.max(0, leftView.size() - ROWS_VISIBLE);
        if (leftScroll > maxScroll) leftScroll = maxScroll;
        if (leftScroll < 0) leftScroll = 0;

        for (int i = 0; i < leftButtons.size(); i++) {
            Button b = leftButtons.get(i);
            int idx = leftScroll + i;
            if (idx < 0 || idx >= leftView.size()) {
                b.visible = false;
                b.active = false;
                b.setMessage(Component.literal(""));
                continue;
            }
            PacketCcSetChestRules.Rule r = leftView.get(idx);
            String id = r == null || r.itemId() == null ? "" : r.itemId().trim();
            int c = r == null ? 0 : Math.max(0, r.count());
            boolean selected = selectedId != null && !selectedId.isBlank() && selectedId.equalsIgnoreCase(id);
            b.visible = true;
            b.active = true;
            b.setMessage(selected
                    ? Component.literal(id + " x" + c).withStyle(ChatFormatting.GOLD)
                    : Component.literal(id + " x" + c));
        }
    }

    private void updateRightButtons() {
        rightMatches.clear();
        String q = "";
        try { q = searchBox == null ? "" : searchBox.getValue(); } catch (Throwable ignored) { q = ""; }
        q = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);

        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            if (id == null) continue;
            String s = id.toString();
            if (!q.isEmpty() && !s.toLowerCase(Locale.ROOT).contains(q)) continue;
            rightMatches.add(s);
        }
        rightMatches.sort(String::compareTo);

        int maxScroll = Math.max(0, rightMatches.size() - ROWS_VISIBLE);
        if (rightScroll > maxScroll) rightScroll = maxScroll;
        if (rightScroll < 0) rightScroll = 0;

        for (int i = 0; i < rightButtons.size(); i++) {
            Button b = rightButtons.get(i);
            int idx = rightScroll + i;
            if (idx < 0 || idx >= rightMatches.size()) {
                b.visible = false;
                b.active = false;
                b.setMessage(Component.literal(""));
                continue;
            }
            String id = rightMatches.get(idx);
            boolean already = false;
            for (PacketCcSetChestRules.Rule r : rules) {
                if (r != null && id.equals(r.itemId())) { already = true; break; }
            }
            b.visible = true;
            b.active = !already;
            b.setMessage(already
                    ? Component.literal(id).withStyle(ChatFormatting.DARK_GRAY)
                    : Component.literal(id));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);

        if (inLeftPanel(mouseX, mouseY)) {
            int maxScroll = Math.max(0, rules.size() - ROWS_VISIBLE);
            leftScroll -= (int) Math.signum(scrollY);
            if (leftScroll < 0) leftScroll = 0;
            if (leftScroll > maxScroll) leftScroll = maxScroll;
            updateLeftButtons();
            return true;
        }

        if (inRightPanel(mouseX, mouseY)) {
            int maxScroll = Math.max(0, rightMatches.size() - ROWS_VISIBLE);
            rightScroll -= (int) Math.signum(scrollY);
            if (rightScroll < 0) rightScroll = 0;
            if (rightScroll > maxScroll) rightScroll = maxScroll;
            updateRightButtons();
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean inLeftPanel(double mx, double my) {
        int x0 = leftPanelX;
        int y0 = leftPanelY + 60;
        int x1 = leftPanelX + leftPanelW;
        int y1 = leftPanelY + leftPanelH;
        return mx >= x0 && mx <= x1 && my >= y0 && my <= y1;
    }

    private boolean inRightPanel(double mx, double my) {
        int x0 = rightPanelX;
        int y0 = rightPanelY + 20;
        int x1 = rightPanelX + rightPanelW;
        int y1 = rightPanelY + rightPanelH;
        return mx >= x0 && mx <= x1 && my >= y0 && my <= y1;
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        gg.fill(left, top, left + PANEL_W, top + PANEL_H, PANEL_BG);
        gg.renderOutline(left, top, PANEL_W, PANEL_H, PANEL_BORDER);

        String mode = kind == 2 ? "Deposit" : "Withdraw";
        gg.drawString(this.font, Component.literal(mode + ": items"), left + 10, top + 10, 0xFFFFFFFF);

        // panels
        gg.fill(leftPanelX, leftPanelY, leftPanelX + leftPanelW, leftPanelY + leftPanelH, 0x22000000);
        gg.renderOutline(leftPanelX, leftPanelY, leftPanelW, leftPanelH, 0xFF2E2E2E);
        gg.drawString(this.font, Component.literal("Configured"), leftPanelX + 6, leftPanelY - 12, 0xFFB0B0B0);

        gg.fill(rightPanelX, rightPanelY, rightPanelX + rightPanelW, rightPanelY + rightPanelH, 0x22000000);
        gg.renderOutline(rightPanelX, rightPanelY, rightPanelW, rightPanelH, 0xFF2E2E2E);
        gg.drawString(this.font, Component.literal("Available"), rightPanelX + 6, rightPanelY - 12, 0xFFB0B0B0);

        super.render(gg, mouseX, mouseY, partialTick);

        // hint
        if (rules.isEmpty()) {
            gg.drawString(this.font, Component.literal("Add at least one item.").withStyle(ChatFormatting.GRAY), left + 10, top + PANEL_H - 18, 0xFFB0B0B0);
        }
    }

    private static int parseInt(String s, int fallback) {
        try {
            if (s == null) return fallback;
            String t = s.trim();
            if (t.isEmpty()) return fallback;
            return Integer.parseInt(t);
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
