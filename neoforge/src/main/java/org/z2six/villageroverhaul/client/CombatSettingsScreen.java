// neoforge\src\main\java\org\z2six\villageroverhaul\client\CombatSettingsScreen.java
package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.combat.CombatSettings;
import org.z2six.villageroverhaul.network.modes.PacketCombatSettingsUpdate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class CombatSettingsScreen extends Screen {

    private static final long SETTINGS_STALE_MS = 1200L;

    private final Screen parent;
    private final int villagerEntityId;
    private final boolean global;

    private CombatSettings settings;

    private enum Tab {
        GENERAL("General"),
        FLEE("Flee"),
        DEFEND("Defend"),
        AGGRESSIVE("Aggressive");

        final String label;
        Tab(String label) { this.label = label; }
    }

    private Tab currentTab = Tab.GENERAL;

    // Layout
    private static final int PANEL_W = 360;
    private static final int PANEL_H = 230;
    private static final int PAD = 10;

    private static final int PANEL_BG = 0xCC0B0B0B;
    private static final int PANEL_BORDER = 0xFF3A3A3A;

    private static final int INPUT_W = 240;
    private static final int INPUT_H = 16;

    private CheckBoxWidget cbOwnerAttacked;
    private CheckBoxWidget cbOwnerAttacks;

    private EditBox wlAttacked;
    private EditBox wlAttacks;
    private EditBox blAttacked;
    private EditBox blAttacks;

    private Button btnAddWlAttacked;
    private Button btnAddWlAttacks;
    private Button btnAddBlAttacked;
    private Button btnAddBlAttacks;

    private Button btnSave;
    private Button btnBack;

    private TabButton tabGeneral;
    private TabButton tabFlee;
    private TabButton tabDefend;
    private TabButton tabAggressive;
    private final List<InfoIconWidget> infoIcons = new ArrayList<>();

    public CombatSettingsScreen(Screen parent, int villagerEntityId, boolean global) {
        super(Component.literal("Combat Settings"));
        this.parent = parent;
        this.villagerEntityId = villagerEntityId;
        this.global = global;
    }

    public int getVillagerEntityId() {
        return villagerEntityId;
    }

    public boolean isGlobal() {
        return global;
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

        int right = left + PANEL_W - PAD;
        int saveX = right - 58;
        int backX = saveX - 6 - 58;

        btnBack = Button.builder(Component.literal("Back"), b -> onClose())
                .pos(backX, top + PAD)
                .size(58, 18)
                .build();
        this.addRenderableWidget(btnBack);

        btnSave = Button.builder(Component.literal("Save"), b -> onSave())
                .pos(saveX, top + PAD)
                .size(58, 18)
                .build();
        this.addRenderableWidget(btnSave);

        int tabsY = top + PANEL_H - 26;
        int tabW = 72;
        int tabH = 18;
        int tabsX = left + (PANEL_W - (tabW * 4 + 6 * 3)) / 2;

        tabGeneral = new TabButton(tabsX, tabsY, tabW, tabH, Tab.GENERAL);
        tabFlee = new TabButton(tabsX + (tabW + 6), tabsY, tabW, tabH, Tab.FLEE);
        tabDefend = new TabButton(tabsX + 2 * (tabW + 6), tabsY, tabW, tabH, Tab.DEFEND);
        tabAggressive = new TabButton(tabsX + 3 * (tabW + 6), tabsY, tabW, tabH, Tab.AGGRESSIVE);

        this.addRenderableWidget(tabGeneral);
        this.addRenderableWidget(tabFlee);
        this.addRenderableWidget(tabDefend);
        this.addRenderableWidget(tabAggressive);

        int x = left + PAD;
        int y = top + 40;

        cbOwnerAttacked = new CheckBoxWidget(x, y, "Triggered when owner attacked");
        cbOwnerAttacks = new CheckBoxWidget(x, y + 18, "Triggered when owner attacks");

        this.addRenderableWidget(cbOwnerAttacked);
        this.addRenderableWidget(cbOwnerAttacks);

        int infoSize = 12;
        int infoX = left + PANEL_W - PAD - infoSize;
        InfoIconWidget infoOwnerAttacked = new InfoIconWidget(infoX, y + 1, infoSize,
                "If the owner is the TARGET of an attack, this rule can trigger.");
        InfoIconWidget infoOwnerAttacks = new InfoIconWidget(infoX, y + 19, infoSize,
                "If the owner is the ATTACKER, this rule can trigger.");
        infoIcons.add(infoOwnerAttacked);
        infoIcons.add(infoOwnerAttacks);
        this.addRenderableWidget(infoOwnerAttacked);
        this.addRenderableWidget(infoOwnerAttacks);

        int listX = x;
        int listY = y + 46;
        int btnX = listX + INPUT_W + 6;

        wlAttacked = new EditBox(this.font, listX, listY, INPUT_W, INPUT_H, Component.literal("Whitelist ATTACKED"));
        btnAddWlAttacked = Button.builder(Component.literal("+"), b -> openEntityPicker(wlAttacked))
                .pos(btnX, listY).size(18, INPUT_H).build();

        wlAttacks = new EditBox(this.font, listX, listY + 24, INPUT_W, INPUT_H, Component.literal("Whitelist ATTACKS"));
        btnAddWlAttacks = Button.builder(Component.literal("+"), b -> openEntityPicker(wlAttacks))
                .pos(btnX, listY + 24).size(18, INPUT_H).build();

        blAttacked = new EditBox(this.font, listX, listY + 48, INPUT_W, INPUT_H, Component.literal("Blacklist ATTACKED"));
        btnAddBlAttacked = Button.builder(Component.literal("+"), b -> openEntityPicker(blAttacked))
                .pos(btnX, listY + 48).size(18, INPUT_H).build();

        blAttacks = new EditBox(this.font, listX, listY + 72, INPUT_W, INPUT_H, Component.literal("Blacklist ATTACKS"));
        btnAddBlAttacks = Button.builder(Component.literal("+"), b -> openEntityPicker(blAttacks))
                .pos(btnX, listY + 72).size(18, INPUT_H).build();

        setEditHint(wlAttacked, "Attacked whitelist (comma-separated)");
        setEditHint(wlAttacks, "Attacker whitelist (comma-separated)");
        setEditHint(blAttacked, "Attacked blacklist (comma-separated)");
        setEditHint(blAttacks, "Attacker blacklist (comma-separated)");

        this.addRenderableWidget(wlAttacked);
        this.addRenderableWidget(wlAttacks);
        this.addRenderableWidget(blAttacked);
        this.addRenderableWidget(blAttacks);
        this.addRenderableWidget(btnAddWlAttacked);
        this.addRenderableWidget(btnAddWlAttacks);
        this.addRenderableWidget(btnAddBlAttacked);
        this.addRenderableWidget(btnAddBlAttacks);

        int labelInfoX = btnX + 20;
        InfoIconWidget infoWlAttacked = new InfoIconWidget(labelInfoX, listY + 1, infoSize,
                "List of entities that can be TARGETS. Empty = no restriction.");
        InfoIconWidget infoWlAttacks = new InfoIconWidget(labelInfoX, listY + 25, infoSize,
                "List of entities that can be ATTACKERS. Empty = no restriction.");
        InfoIconWidget infoBlAttacked = new InfoIconWidget(labelInfoX, listY + 49, infoSize,
                "List of entities that can NOT be TARGETS.");
        InfoIconWidget infoBlAttacks = new InfoIconWidget(labelInfoX, listY + 73, infoSize,
                "List of entities that can NOT be ATTACKERS.");
        infoIcons.add(infoWlAttacked);
        infoIcons.add(infoWlAttacks);
        infoIcons.add(infoBlAttacked);
        infoIcons.add(infoBlAttacks);
        this.addRenderableWidget(infoWlAttacked);
        this.addRenderableWidget(infoWlAttacks);
        this.addRenderableWidget(infoBlAttacked);
        this.addRenderableWidget(infoBlAttacks);

        tryApplySettingsFromCache();
        applyTabToWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        tryApplySettingsFromCache();
    }

    private void tryApplySettingsFromCache() {
        try {
            if (settings != null) return;
            if (ClientCombatSettingsCache.getAgeMs(villagerEntityId, global) > SETTINGS_STALE_MS) return;
            CombatSettings snap = ClientCombatSettingsCache.get(villagerEntityId, global);
            if (snap == null) return;
            settings = snap;
            applyTabToWidgets();
        } catch (Throwable ignored) {}
    }

    private void applyTabToWidgets() {
        if (settings == null) return;
        CombatSettings.ModeSettings m = getCurrentModeSettings();
        if (m == null) return;

        cbOwnerAttacked.setChecked(m.triggerWhenOwnerAttacked);
        cbOwnerAttacks.setChecked(m.triggerWhenOwnerAttacks);

        wlAttacked.setValue(String.join(", ", m.whitelistAttacked));
        wlAttacks.setValue(String.join(", ", m.whitelistAttacks));
        blAttacked.setValue(String.join(", ", m.blacklistAttacked));
        blAttacks.setValue(String.join(", ", m.blacklistAttacks));
    }

    private void storeWidgetsToTab() {
        if (settings == null) settings = new CombatSettings();
        CombatSettings.ModeSettings m = getCurrentModeSettings();
        if (m == null) return;

        m.triggerWhenOwnerAttacked = cbOwnerAttacked.isChecked();
        m.triggerWhenOwnerAttacks = cbOwnerAttacks.isChecked();

        m.whitelistAttacked.clear();
        m.whitelistAttacks.clear();
        m.blacklistAttacked.clear();
        m.blacklistAttacks.clear();

        m.whitelistAttacked.addAll(parseList(wlAttacked));
        m.whitelistAttacks.addAll(parseList(wlAttacks));
        m.blacklistAttacked.addAll(parseList(blAttacked));
        m.blacklistAttacks.addAll(parseList(blAttacks));
    }

    private CombatSettings.ModeSettings getCurrentModeSettings() {
        if (settings == null) return null;
        return switch (currentTab) {
            case GENERAL -> settings.general;
            case FLEE -> settings.flee;
            case DEFEND -> settings.defend;
            case AGGRESSIVE -> settings.aggressive;
        };
    }

    private void onSave() {
        try {
            storeWidgetsToTab();
            if (settings == null) return;
            ClientNetwork.sendToServer(new PacketCombatSettingsUpdate(villagerEntityId, global, settings.toTag()));
            VillagerOverhaul.LOG().info("[VillagerOverhaul] CombatSettingsScreen saved (global={} villagerEntityId={})",
                    global, villagerEntityId);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] CombatSettingsScreen.onSave failed", t);
        }
    }

    @Override
    public void onClose() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            mc.setScreen(parent);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] CombatSettingsScreen.onClose failed", t);
            super.onClose();
        }
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        try {
            super.renderBackground(gg, mouseX, mouseY, partialTick);
        } catch (Throwable ignored) {}

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        drawPanel(gg, left, top, PANEL_W, PANEL_H);

        Font font = Minecraft.getInstance().font;
        String title = global ? "Combat Settings (Global)" : "Combat Settings";
        gg.drawString(font, title, left + PAD, top + PAD + 5, 0xFFFFFFFF, true);

        drawLabels(gg, font, left + PAD, top + 86);

        for (InfoIconWidget w : infoIcons) {
            if (w == null || !w.isHoveredOrFocused()) continue;
            gg.renderTooltip(font, Component.literal(w.tooltip), mouseX, mouseY);
            break;
        }

        super.render(gg, mouseX, mouseY, partialTick);
    }

    private static void drawPanel(GuiGraphics gg, int x, int y, int w, int h) {
        gg.fill(x, y, x + w, y + h, PANEL_BG);
        gg.fill(x, y, x + w, y + 1, PANEL_BORDER);
        gg.fill(x, y + h - 1, x + w, y + h, PANEL_BORDER);
        gg.fill(x, y, x + 1, y + h, PANEL_BORDER);
        gg.fill(x + w - 1, y, x + w, y + h, PANEL_BORDER);
    }

    private static void drawLabels(GuiGraphics gg, Font font, int x, int startY) {
        gg.drawString(font, "Whitelist ATTACKED", x, startY, 0xFFFFFFFF, false);
        gg.drawString(font, "Whitelist ATTACKS", x, startY + 24, 0xFFFFFFFF, false);
        gg.drawString(font, "Blacklist ATTACKED", x, startY + 48, 0xFFFFFFFF, false);
        gg.drawString(font, "Blacklist ATTACKS", x, startY + 72, 0xFFFFFFFF, false);
    }

    private static List<String> parseList(EditBox box) {
        String text = box == null ? "" : box.getValue();
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        String[] parts = text.split("[,\\s]+");
        for (String p : parts) {
            String v = p.trim().toLowerCase(Locale.ROOT);
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    private void openEntityPicker(EditBox target) {
        try {
            if (target == null) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            storeWidgetsToTab();
            Consumer<String> add = id -> {
                addIdToCurrentList(target, id);
                applyTabToWidgets();
            };
            mc.setScreen(new EntityPickerScreen(this, add, new java.util.HashSet<>(parseList(target))));
        } catch (Throwable ignored) {}
    }

    private void addIdToCurrentList(EditBox target, String id) {
        if (target == null || id == null || id.isBlank()) return;
        if (settings == null) settings = new CombatSettings();
        CombatSettings.ModeSettings m = getCurrentModeSettings();
        if (m == null) return;

        List<String> list = null;
        if (target == wlAttacked) list = m.whitelistAttacked;
        else if (target == wlAttacks) list = m.whitelistAttacks;
        else if (target == blAttacked) list = m.blacklistAttacked;
        else if (target == blAttacks) list = m.blacklistAttacks;

        if (list == null) return;
        String norm = id.trim().toLowerCase(Locale.ROOT);
        if (norm.isEmpty()) return;
        for (String s : list) {
            if (s != null && s.equalsIgnoreCase(norm)) return;
        }
        list.add(norm);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class TabButton extends AbstractWidget {
        private final Tab target;

        TabButton(int x, int y, int w, int h, Tab target) {
            super(x, y, w, h, Component.empty());
            this.target = target;
        }

        @Override
        protected void renderWidget(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
            boolean selected = currentTab == target;
            boolean hover = isHoveredOrFocused();

            int bg = selected ? 0xFF2E2E2E : (hover ? 0xFF242424 : 0xFF1A1A1A);
            int border = selected ? 0xFFFFFFFF : (hover ? 0xFFBFBFBF : 0xFF6A6A6A);
            int txt = selected ? 0xFFFFFFFF : 0xFFEAEAEA;

            gg.fill(getX(), getY(), getX() + width, getY() + height, bg);
            gg.fill(getX(), getY(), getX() + width, getY() + 1, border);
            gg.fill(getX(), getY() + height - 1, getX() + width, getY() + height, border);
            gg.fill(getX(), getY(), getX() + 1, getY() + height, border);
            gg.fill(getX() + width - 1, getY(), getX() + width, getY() + height, border);

            Font f = Minecraft.getInstance().font;
            String label = target.label;
            int tw = f.width(label);
            int tx = getX() + (width - tw) / 2;
            int ty = getY() + (height - f.lineHeight) / 2;
            gg.drawString(f, label, tx, ty, txt, false);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            try {
                if (currentTab == target) return;
                storeWidgetsToTab();
                currentTab = target;
                applyTabToWidgets();
            } catch (Throwable ignored) {}
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
            // no-op
        }
    }

    private static final class CheckBoxWidget extends AbstractWidget {
        private boolean checked = false;
        private final String label;

        CheckBoxWidget(int x, int y, String label) {
            super(x, y, 14, 14, Component.empty());
            this.label = label == null ? "" : label;
        }

        public boolean isChecked() { return checked; }

        public void setChecked(boolean v) { checked = v; }

        @Override
        protected void renderWidget(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            int s = this.width;
            int bg = 0xFF1A1A1A;
            int border = isHoveredOrFocused() ? 0xFFBFBFBF : 0xFF6A6A6A;

            gg.fill(x, y, x + s, y + s, bg);
            gg.fill(x, y, x + s, y + 1, border);
            gg.fill(x, y + s - 1, x + s, y + s, border);
            gg.fill(x, y, x + 1, y + s, border);
            gg.fill(x + s - 1, y, x + s, y + s, border);

            if (checked) {
                gg.fill(x + 3, y + 3, x + s - 3, y + s - 3, 0xFF58E766);
            }

            Font f = Minecraft.getInstance().font;
            gg.drawString(f, label, x + s + 6, y + 2, 0xFFFFFFFF, false);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            checked = !checked;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
            // no-op
        }
    }

    private static void setEditHint(EditBox box, String hint) {
        try {
            if (box == null || hint == null) return;
            var m = box.getClass().getMethod("setHint", Component.class);
            m.invoke(box, Component.literal(hint).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        } catch (Throwable ignored) {}
    }

    private static final class InfoIconWidget extends AbstractWidget {
        private final String tooltip;

        InfoIconWidget(int x, int y, int size, String tooltip) {
            super(x, y, size, size, Component.empty());
            this.tooltip = tooltip == null ? "" : tooltip;
        }

        @Override
        protected void renderWidget(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            int s = this.width;
            int bg = 0xFF1A1A1A;
            int border = isHoveredOrFocused() ? 0xFFBFBFBF : 0xFF6A6A6A;

            gg.fill(x, y, x + s, y + s, bg);
            gg.fill(x, y, x + s, y + 1, border);
            gg.fill(x, y + s - 1, x + s, y + s, border);
            gg.fill(x, y, x + 1, y + s, border);
            gg.fill(x + s - 1, y, x + s, y + s, border);

            Font f = Minecraft.getInstance().font;
            int tw = f.width("?");
            int tx = x + (s - tw) / 2;
            int ty = y + (s - f.lineHeight) / 2;
            gg.drawString(f, "?", tx, ty, 0xFFEAEAEA, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
            // no-op
        }
    }
}
