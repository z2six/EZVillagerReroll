// neoforge\src\main\java\org\z2six\villageroverhaul\client\CombatSettingsScreen.java
package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.combat.CombatSettings;
import org.z2six.villageroverhaul.network.modes.PacketCombatSettingsSync;
import org.z2six.villageroverhaul.network.modes.PacketCombatSettingsUpdate;

import java.util.ArrayList;
import java.util.List;

public final class CombatSettingsScreen extends Screen {

    private static final long SETTINGS_STALE_MS = 1200L;

    private final Screen parent;
    private final int villagerEntityId;
    private final boolean global;

    private CombatSettings settings;

    private enum Tab {
        FLEE("Flee"),
        DEFEND("Defend"),
        AGGRESSIVE("Aggressive"),
        AI("AI");

        final String label;
        Tab(String label) { this.label = label; }
    }

    private Tab currentTab = Tab.FLEE;

    // Layout
    private static final int PANEL_W = 360;
    private static final int PANEL_H = 230;
    private static final int PAD = 10;

    private static final int PANEL_BG = 0xCC0B0B0B;
    private static final int PANEL_BORDER = 0xFF3A3A3A;

    private CheckBoxWidget cbOwnerAttacked;
    private CheckBoxWidget cbOwnerAttacks;
    private CheckBoxWidget cbEntityAttacks;
    private CheckBoxWidget cbEntityAttacked;

    private Button wlOwnerAttacked;
    private Button blOwnerAttacked;
    private Button wlOwnerAttacks;
    private Button blOwnerAttacks;
    private Button wlEntityAttacks;
    private Button blEntityAttacks;
    private Button wlEntityAttacked;
    private Button blEntityAttacked;

    private InfoIconWidget infoOwnerAttacked;
    private InfoIconWidget infoOwnerAttacks;
    private InfoIconWidget infoEntityAttacks;
    private InfoIconWidget infoEntityAttacked;

    private Button aggroWl;
    private Button aggroBl;

    private Button btnSave;
    private Button btnBack;
    private Button btnSync;

    private TabButton tabFlee;
    private TabButton tabDefend;
    private TabButton tabAggressive;
    private TabButton tabAi;

    private final List<AbstractWidget> fleeDefendWidgets = new ArrayList<>();
    private final List<AbstractWidget> aggressiveWidgets = new ArrayList<>();
    private final List<AbstractWidget> aiWidgets = new ArrayList<>();
    private final List<InfoIconWidget> infoIcons = new ArrayList<>();

    // AI tab widgets
    private CheckBoxWidget cbAiBlocking;
    private CheckBoxWidget cbAiEating;
    private CheckBoxWidget cbAiCircling;
    private Button btnAiEatForceHits;
    private Button btnAiEatMaxResets;

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

        if (!global) {
            int syncX = backX - 6 - 58;
            btnSync = Button.builder(Component.literal("Sync"), b -> onSync())
                    .pos(syncX, top + PAD)
                    .size(58, 18)
                    .build();
            this.addRenderableWidget(btnSync);
        }

        btnSave = Button.builder(Component.literal("Save"), b -> onSave())
                .pos(saveX, top + PAD)
                .size(58, 18)
                .build();
        this.addRenderableWidget(btnSave);

        int tabsY = top + PANEL_H - 26;
        int tabW = 80;
        int tabH = 18;
        int tabsX = left + (PANEL_W - (tabW * 4 + 6 * 3)) / 2;

        tabFlee = new TabButton(tabsX, tabsY, tabW, tabH, Tab.FLEE);
        tabDefend = new TabButton(tabsX + (tabW + 6), tabsY, tabW, tabH, Tab.DEFEND);
        tabAggressive = new TabButton(tabsX + 2 * (tabW + 6), tabsY, tabW, tabH, Tab.AGGRESSIVE);
        tabAi = new TabButton(tabsX + 3 * (tabW + 6), tabsY, tabW, tabH, Tab.AI);

        this.addRenderableWidget(tabFlee);
        this.addRenderableWidget(tabDefend);
        this.addRenderableWidget(tabAggressive);
        this.addRenderableWidget(tabAi);

        int x = left + PAD;
        int y = top + 52;
        int blockH = 36;

        cbOwnerAttacked = new CheckBoxWidget(x, y, "Triggered when owner is attacked");
        cbOwnerAttacks = new CheckBoxWidget(x, y + blockH, "Triggered when owner attacks");
        cbEntityAttacks = new CheckBoxWidget(x, y + blockH * 2, "Triggered when entity attacks");
        cbEntityAttacked = new CheckBoxWidget(x, y + blockH * 3, "Triggered when entity is attacked");

        addFleeDefendWidget(cbOwnerAttacked);
        addFleeDefendWidget(cbOwnerAttacks);
        addFleeDefendWidget(cbEntityAttacks);
        addFleeDefendWidget(cbEntityAttacked);

        int infoSize = 12;
        int infoX = left + PANEL_W - PAD - infoSize;

        infoOwnerAttacked = new InfoIconWidget(infoX, y + 1, infoSize,
                "Trigger when the owner is attacked; lists filter the attacker.");
        infoOwnerAttacks = new InfoIconWidget(infoX, y + blockH + 1, infoSize,
                "Trigger when the owner attacks; lists filter the target.");
        infoEntityAttacks = new InfoIconWidget(infoX, y + blockH * 2 + 1, infoSize,
                "Trigger when any entity attacks; lists filter the attacker.");
        infoEntityAttacked = new InfoIconWidget(infoX, y + blockH * 3 + 1, infoSize,
                "Trigger when any entity is attacked; lists filter the attacked.");

        addFleeDefendWidget(infoOwnerAttacked);
        addFleeDefendWidget(infoOwnerAttacks);
        addFleeDefendWidget(infoEntityAttacks);
        addFleeDefendWidget(infoEntityAttacked);

        infoIcons.add(infoOwnerAttacked);
        infoIcons.add(infoOwnerAttacks);
        infoIcons.add(infoEntityAttacks);
        infoIcons.add(infoEntityAttacked);

        int btnW = 84;
        int btnH = 16;
        int btnGap = 6;
        int btnX = x + 18;

        wlOwnerAttacked = makeListButton(btnX, y + 16, btnW, btnH, "Whitelist", () -> openListForOwnerAttacked(true));
        blOwnerAttacked = makeListButton(btnX + btnW + btnGap, y + 16, btnW, btnH, "Blacklist", () -> openListForOwnerAttacked(false));

        wlOwnerAttacks = makeListButton(btnX, y + blockH + 16, btnW, btnH, "Whitelist", () -> openListForOwnerAttacks(true));
        blOwnerAttacks = makeListButton(btnX + btnW + btnGap, y + blockH + 16, btnW, btnH, "Blacklist", () -> openListForOwnerAttacks(false));

        wlEntityAttacks = makeListButton(btnX, y + blockH * 2 + 16, btnW, btnH, "Whitelist", () -> openListForEntityAttacks(true));
        blEntityAttacks = makeListButton(btnX + btnW + btnGap, y + blockH * 2 + 16, btnW, btnH, "Blacklist", () -> openListForEntityAttacks(false));

        wlEntityAttacked = makeListButton(btnX, y + blockH * 3 + 16, btnW, btnH, "Whitelist", () -> openListForEntityAttacked(true));
        blEntityAttacked = makeListButton(btnX + btnW + btnGap, y + blockH * 3 + 16, btnW, btnH, "Blacklist", () -> openListForEntityAttacked(false));

        addFleeDefendWidget(wlOwnerAttacked);
        addFleeDefendWidget(blOwnerAttacked);
        addFleeDefendWidget(wlOwnerAttacks);
        addFleeDefendWidget(blOwnerAttacks);
        addFleeDefendWidget(wlEntityAttacks);
        addFleeDefendWidget(blEntityAttacks);
        addFleeDefendWidget(wlEntityAttacked);
        addFleeDefendWidget(blEntityAttacked);

        int aggroY = y + blockH;
        aggroWl = makeListButton(x, aggroY, 120, btnH, "Whitelist", this::openAggressiveWhitelist);
        aggroBl = makeListButton(x + 126, aggroY, 120, btnH, "Blacklist", this::openAggressiveBlacklist);

        addAggressiveWidget(aggroWl);
        addAggressiveWidget(aggroBl);

        // ------------------------------------------------------------------
        // AI tab
        // ------------------------------------------------------------------
        int aiX = left + PAD;
        int aiY = top + 72;
        int aiGap = 22;

        cbAiBlocking = new CheckBoxWidget(aiX, aiY, "Enable blocking (shield)");
        cbAiEating = new CheckBoxWidget(aiX, aiY + aiGap, "Enable eating to heal");
        cbAiCircling = new CheckBoxWidget(aiX, aiY + aiGap * 2, "Enable circling/strafe");

        addAiWidget(cbAiBlocking);
        addAiWidget(cbAiEating);
        addAiWidget(cbAiCircling);

        int btnY = aiY + aiGap * 3 + 8;
        int btnW2 = 240;
        int btnH2 = 18;

        btnAiEatForceHits = Button.builder(Component.literal("Force-eat after hits: ..."), b -> {
                    try {
                        if (settings == null) settings = new CombatSettings();
                        int v = settings.ai.eatForceHits;
                        v = (v >= 6) ? 0 : (v + 1);
                        settings.ai.eatForceHits = v;
                        b.setMessage(Component.literal("Force-eat after hits: " + v));
                    } catch (Throwable ignored) {}
                })
                .pos(aiX, btnY)
                .size(btnW2, btnH2)
                .build();

        btnAiEatMaxResets = Button.builder(Component.literal("Eat resets before force: ..."), b -> {
                    try {
                        if (settings == null) settings = new CombatSettings();
                        int v = settings.ai.eatMaxResets;
                        v = (v >= 5) ? 0 : (v + 1);
                        settings.ai.eatMaxResets = v;
                        b.setMessage(Component.literal("Eat resets before force: " + v));
                    } catch (Throwable ignored) {}
                })
                .pos(aiX, btnY + btnH2 + 6)
                .size(btnW2, btnH2)
                .build();

        addAiWidget(btnAiEatForceHits);
        addAiWidget(btnAiEatMaxResets);

        tryApplySettingsFromCache();
        applyTabToWidgets();
    }

    private void addFleeDefendWidget(AbstractWidget w) {
        this.addRenderableWidget(w);
        fleeDefendWidgets.add(w);
    }

    private void addAggressiveWidget(AbstractWidget w) {
        this.addRenderableWidget(w);
        aggressiveWidgets.add(w);
    }

    private void addAiWidget(AbstractWidget w) {
        this.addRenderableWidget(w);
        aiWidgets.add(w);
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
        boolean fleeDefend = currentTab == Tab.FLEE || currentTab == Tab.DEFEND;
        boolean aggressive = currentTab == Tab.AGGRESSIVE;
        boolean ai = currentTab == Tab.AI;

        setGroupVisible(fleeDefendWidgets, fleeDefend);
        setGroupVisible(aggressiveWidgets, aggressive);
        setGroupVisible(aiWidgets, ai);

        if (settings == null) return;

        if (ai) {
            cbAiBlocking.setChecked(settings.ai.enableBlocking);
            cbAiEating.setChecked(settings.ai.enableEating);
            cbAiCircling.setChecked(settings.ai.enableCircling);

            if (btnAiEatForceHits != null) btnAiEatForceHits.setMessage(Component.literal("Force-eat after hits: " + settings.ai.eatForceHits));
            if (btnAiEatMaxResets != null) btnAiEatMaxResets.setMessage(Component.literal("Eat resets before force: " + settings.ai.eatMaxResets));
            return;
        }

        CombatSettings.ModeSettings m = getCurrentModeSettings();
        if (m == null) return;

        if (!aggressive) {
            cbOwnerAttacked.setChecked(m.ownerAttacked.enabled);
            cbOwnerAttacks.setChecked(m.ownerAttacks.enabled);
            cbEntityAttacks.setChecked(m.entityAttacks.enabled);
            cbEntityAttacked.setChecked(m.entityAttacked.enabled);
        }
    }

    private void storeWidgetsToTab() {
        if (settings == null) settings = new CombatSettings();

        if (currentTab == Tab.AI) {
            settings.ai.enableBlocking = cbAiBlocking != null && cbAiBlocking.isChecked();
            settings.ai.enableEating = cbAiEating != null && cbAiEating.isChecked();
            settings.ai.enableCircling = cbAiCircling != null && cbAiCircling.isChecked();
            return;
        }

        CombatSettings.ModeSettings m = getCurrentModeSettings();
        if (m == null) return;

        if (currentTab != Tab.AGGRESSIVE) {
            m.ownerAttacked.enabled = cbOwnerAttacked.isChecked();
            m.ownerAttacks.enabled = cbOwnerAttacks.isChecked();
            m.entityAttacks.enabled = cbEntityAttacks.isChecked();
            m.entityAttacked.enabled = cbEntityAttacked.isChecked();
        }
    }

    private CombatSettings.ModeSettings getCurrentModeSettings() {
        if (settings == null) return null;
        return switch (currentTab) {
            case FLEE -> settings.flee;
            case DEFEND -> settings.defend;
            case AGGRESSIVE -> settings.aggressive;
            case AI -> null;
        };
    }

    private void onSave() {
        try {
            storeWidgetsToTab();
            if (settings == null) return;
            ClientNetwork.sendToServer(new PacketCombatSettingsUpdate(villagerEntityId, global, settings.toTag()));
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] CombatSettingsScreen saved (global={} villagerEntityId={})",
                    global, villagerEntityId);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] CombatSettingsScreen.onSave failed", t);
        }
    }

    private void onSync() {
        try {
            if (global) return;
            settings = null;
            ClientNetwork.sendToServer(new PacketCombatSettingsSync(villagerEntityId));
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] CombatSettingsScreen sync requested (villagerEntityId={})",
                    villagerEntityId);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] CombatSettingsScreen.onSync failed", t);
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

        drawIntroText(gg, font, left, top);

        for (InfoIconWidget w : infoIcons) {
            if (w == null || !w.isHoveredOrFocused()) continue;
            gg.renderTooltip(font, Component.literal(w.tooltip), mouseX, mouseY);
            break;
        }

        super.render(gg, mouseX, mouseY, partialTick);
    }

    private void drawIntroText(GuiGraphics gg, Font font, int left, int top) {
        int y = top + PAD + 24;
        String[] lines = switch (currentTab) {
            case FLEE -> new String[] { "Configure when villagers should flee." };
            case DEFEND -> new String[] { "Configure when villagers should defend." };
            case AGGRESSIVE -> new String[] {
                    "Configure which entities are attacked on sight.",
                    "Does nothing if whitelist and blacklist are both empty."
            };
            case AI -> new String[] {
                    "Configure combat AI behavior.",
                    "These settings are clamped to avoid overpowered configs."
            };
        };
        for (String line : lines) {
            gg.drawString(font, line, left + PAD, y, 0xFFBFBFBF, false);
            y += font.lineHeight + 2;
        }
    }

    private static void drawPanel(GuiGraphics gg, int x, int y, int w, int h) {
        gg.fill(x, y, x + w, y + h, PANEL_BG);
        gg.fill(x, y, x + w, y + 1, PANEL_BORDER);
        gg.fill(x, y + h - 1, x + w, y + h, PANEL_BORDER);
        gg.fill(x, y, x + 1, y + h, PANEL_BORDER);
        gg.fill(x + w - 1, y, x + w, y + h, PANEL_BORDER);
    }

    private static void setGroupVisible(List<AbstractWidget> widgets, boolean visible) {
        if (widgets == null) return;
        for (AbstractWidget w : widgets) {
            if (w == null) continue;
            w.visible = visible;
            if (w instanceof Button b) b.active = visible;
        }
    }

    private Button makeListButton(int x, int y, int w, int h, String label, Runnable action) {
        Button b = Button.builder(Component.literal(label), btn -> {
                    try {
                        storeWidgetsToTab();
                        if (action != null) action.run();
                    } catch (Throwable ignored) {}
                })
                .pos(x, y)
                .size(w, h)
                .build();
        return b;
    }

    private void openListForOwnerAttacked(boolean whitelist) {
        CombatSettings.ModeSettings m = getCurrentModeSettings();
        if (m == null) return;
        String title = whitelist ? "Owner attacked: attacker whitelist" : "Owner attacked: attacker blacklist";
        openListEditor(whitelist ? m.ownerAttacked.whitelist : m.ownerAttacked.blacklist, title);
    }

    private void openListForOwnerAttacks(boolean whitelist) {
        CombatSettings.ModeSettings m = getCurrentModeSettings();
        if (m == null) return;
        String title = whitelist ? "Owner attacks: target whitelist" : "Owner attacks: target blacklist";
        openListEditor(whitelist ? m.ownerAttacks.whitelist : m.ownerAttacks.blacklist, title);
    }

    private void openListForEntityAttacks(boolean whitelist) {
        CombatSettings.ModeSettings m = getCurrentModeSettings();
        if (m == null) return;
        String title = whitelist ? "Entity attacks: attacker whitelist" : "Entity attacks: attacker blacklist";
        openListEditor(whitelist ? m.entityAttacks.whitelist : m.entityAttacks.blacklist, title);
    }

    private void openListForEntityAttacked(boolean whitelist) {
        CombatSettings.ModeSettings m = getCurrentModeSettings();
        if (m == null) return;
        String title = whitelist ? "Entity attacked: attacked whitelist" : "Entity attacked: attacked blacklist";
        openListEditor(whitelist ? m.entityAttacked.whitelist : m.entityAttacked.blacklist, title);
    }

    private void openAggressiveWhitelist() {
        CombatSettings.ModeSettings m = getCurrentModeSettings();
        if (m == null) return;
        openListEditor(m.aggressiveWhitelist, "Aggressive: whitelist");
    }

    private void openAggressiveBlacklist() {
        CombatSettings.ModeSettings m = getCurrentModeSettings();
        if (m == null) return;
        openListEditor(m.aggressiveBlacklist, "Aggressive: blacklist");
    }

    private void openListEditor(List<String> list, String title) {
        if (list == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.setScreen(new EntityListEditorScreen(this, list, title));
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
