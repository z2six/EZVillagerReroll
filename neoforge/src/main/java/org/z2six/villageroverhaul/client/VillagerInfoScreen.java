// VillagerInfoScreen.java
// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/client/VillagerInfoScreen.java
package org.z2six.villageroverhaul.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.ClientSyncedConfig;
import org.z2six.villageroverhaul.network.ClientVillagerStatsCache;
import org.z2six.villageroverhaul.network.PacketVillagerStatsData;
import org.z2six.villageroverhaul.network.PacketVillagerStatsQuery;
import org.z2six.villageroverhaul.server.VillagerStatsService;

import java.util.ArrayList;
import java.util.List;

public final class VillagerInfoScreen extends Screen {

    private static final long STATS_QUERY_DEBOUNCE_MS = 750;

    private final MerchantScreen parent;
    private final int villagerEntityId;

    private LivingEntity cachedEntity;

    private boolean hasStats = false;
    private boolean statsUnavailable = false;

    // Merchant stats
    private int generosity = 0;
    private int timeliness = 0;
    private int intellect  = 0;
    private int hoarder    = 0;

    // Combat stats
    private int vitality = 0;
    private int agility  = 0;
    private int strength = 0;
    private int armor    = 0;

    private long lastStatsQueryMs = 0L;

    // Tabs
    private enum Tab {
        MERCHANT("Merchant stats"),
        COMBAT("Combat stats");

        final String label;
        Tab(String label) { this.label = label; }
    }

    private Tab currentTab = Tab.MERCHANT;

    private IconTabButton tabMerchantBtn;
    private IconTabButton tabCombatBtn;

    // Layout
    private static final int PANEL_W = 316;
    // Slightly taller so bottom icon buttons have breathing room.
    private static final int PANEL_H = 206;

    private static final int PAD = 10;

    // Entity box (left)
    private static final int ENTITY_BOX_W = 120;
    private static final int ENTITY_BOX_H = 140;

    // Bars (right)
    private static final int BAR_W = 140;
    private static final int BAR_H = 12;

    // Custom tab icons
    private static final int TAB_BTN_SIZE = 18;
    private static final int TAB_BTN_GAP = 8;
    // More bottom padding so buttons don't hug the panel edge.
    private static final int TAB_BTN_BOTTOM_PAD = 8;

    // Colors (ARGB)
    private static final int PANEL_BG = 0xCC0B0B0B;
    private static final int PANEL_BORDER = 0xFF3A3A3A;

    private static final int BAR_BG = 0xFF151515;
    private static final int BAR_OUTLINE = 0xFF404040;
    private static final int BAR_CENTER = 0xFFAAAAAA;

    // Merchant colors
    private static final int C_GENEROSITY = 0xFF42D16C; // green
    private static final int C_TIMELINESS = 0xFF2FC7FF; // cyan
    private static final int C_INTELLECT  = 0xFFB26BFF; // purple
    private static final int C_HOARDER    = 0xFFFFB347; // orange

    // Combat colors
    private static final int C_VITALITY = 0xFFFF5A5A; // red-ish
    private static final int C_AGILITY  = 0xFF4DD6FF; // light blue
    private static final int C_STRENGTH = 0xFFFF7A2F; // orange-red
    private static final int C_ARMOR    = 0xFFB0B0B0; // silver

    private enum StatKind {
        // Merchant
        GENEROSITY("Generosity"),
        TIMELINESS("Timeliness"),
        INTELLECT("Intellect"),
        HOARDER("Hoarder"),

        // Combat
        VITALITY("Vitality"),
        AGILITY("Agility"),
        STRENGTH("Strength"),
        ARMOR("Armor");

        final String label;
        StatKind(String label) { this.label = label; }

        boolean isMerchant() {
            return this == GENEROSITY || this == TIMELINESS || this == INTELLECT || this == HOARDER;
        }
    }

    public VillagerInfoScreen(MerchantScreen parent, int villagerEntityId) {
        super(Component.literal("Villager Info"));
        this.parent = parent;
        this.villagerEntityId = villagerEntityId;

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null && mc.player.getId() == villagerEntityId) {
                VillagerOverhaul.LOG().warn("[VillagerOverhaul] VillagerInfoScreen opened with player entityId={} (expected villager). Trader id resolution may be wrong.",
                        villagerEntityId);
            }
        } catch (Throwable ignored) {}
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

        resolveEntity();

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        // Back button (vanilla is fine)
        this.addRenderableWidget(
                Button.builder(Component.literal("Back"), b -> onClose())
                        .pos(left + PANEL_W - 58 - PAD, top + PAD)
                        .size(58, 18)
                        .build()
        );

        // Custom tab buttons centered at the bottom INSIDE the panel.
        int groupW = TAB_BTN_SIZE * 2 + TAB_BTN_GAP;
        int tabsX = left + (PANEL_W - groupW) / 2;
        int tabsY = top + PANEL_H - TAB_BTN_BOTTOM_PAD - TAB_BTN_SIZE;

        tabMerchantBtn = new IconTabButton(tabsX, tabsY, TAB_BTN_SIZE, "¤",
                Component.literal("Merchant stats"), Tab.MERCHANT);
        tabCombatBtn = new IconTabButton(tabsX + TAB_BTN_SIZE + TAB_BTN_GAP, tabsY, TAB_BTN_SIZE, "⚔",
                Component.literal("Combat stats"), Tab.COMBAT);

        this.addRenderableWidget(tabMerchantBtn);
        this.addRenderableWidget(tabCombatBtn);

        // Kick initial request immediately
        trySendStatsQuery(false);
        tryApplyStatsFromCache();
    }

    @Override
    public void tick() {
        super.tick();

        resolveEntity();
        tryApplyStatsFromCache();

        if (!hasStats && !statsUnavailable) {
            trySendStatsQuery(true);
        }
    }

    private void tryApplyStatsFromCache() {
        try {
            PacketVillagerStatsData snap = ClientVillagerStatsCache.get(this.villagerEntityId);
            if (snap == null) return;

            if (!snap.ok()) {
                this.statsUnavailable = true;
                this.hasStats = false;
                return;
            }

            // Merchant
            this.generosity = VillagerStatsService.clampPoints(snap.generosity());
            this.timeliness = VillagerStatsService.clampPoints(snap.timeliness());
            this.intellect  = VillagerStatsService.clampPoints(snap.intellect());
            this.hoarder    = VillagerStatsService.clampPoints(snap.hoarder());

            // Combat
            this.vitality = VillagerStatsService.clampPoints(snap.vitality());
            this.agility  = VillagerStatsService.clampPoints(snap.agility());
            this.strength = VillagerStatsService.clampPoints(snap.strength());
            this.armor    = VillagerStatsService.clampPoints(snap.armor());

            this.hasStats = true;
            this.statsUnavailable = false;

        } catch (Throwable ignored) {}
    }

    private void trySendStatsQuery(boolean debounced) {
        try {
            long now = System.currentTimeMillis();
            if (debounced && (now - lastStatsQueryMs) < STATS_QUERY_DEBOUNCE_MS) return;
            lastStatsQueryMs = now;

            ClientNetwork.sendToServer(new PacketVillagerStatsQuery(this.villagerEntityId));
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerInfoScreen sent PacketVillagerStatsQuery(entityId={})", this.villagerEntityId);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerInfoScreen.trySendStatsQuery failed (soft): {}", t.toString());
        }
    }

    private void resolveEntity() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) return;

            Entity e = mc.level.getEntity(this.villagerEntityId);
            if (e instanceof LivingEntity le) {
                this.cachedEntity = le;
            }
        } catch (Throwable t) {
            // soft
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            if (this.parent != null) {
                mc.setScreen(this.parent);
            } else {
                mc.setScreen(null);
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] VillagerInfoScreen.onClose failed", t);
            super.onClose();
        }
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // Apply blur/background ONCE
        try {
            super.renderBackground(gg, mouseX, mouseY, partialTick);
        } catch (Throwable ignored) {}

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        drawPanel(gg, left, top, PANEL_W, PANEL_H);

        Font font = Minecraft.getInstance().font;

        gg.drawString(font, Component.literal("Villager Info"), left + PAD, top + PAD + 5, 0xFFFFFFFF, true);

        int boxLeft = left + PAD;
        int boxTop = top + 28;
        int boxRight = boxLeft + ENTITY_BOX_W;
        int boxBottom = boxTop + ENTITY_BOX_H;

        drawEntityBox(gg, boxLeft, boxTop, boxRight, boxBottom);

        int textX = boxRight + PAD;
        int textY = top + 34;

        LivingEntity le = this.cachedEntity;
        if (le == null) {
            gg.drawString(font, Component.literal("Entity: (not found)"), textX, textY, 0xFFFF7777, false);
            gg.drawString(font, Component.literal("Id: " + this.villagerEntityId), textX, textY + 12, 0xFFBFBFBF, false);
        } else {
            Component name = safeName(le);
            gg.drawString(font, Component.literal("Name: ").append(name), textX, textY, 0xFFFFFFFF, false);

            Component prof = safeProfession(le);
            gg.drawString(font, Component.literal("Profession: ").append(prof), textX, textY + 12, 0xFFFFFFFF, false);
        }

        renderVillagerModel(gg, boxLeft, boxTop, boxRight, boxBottom, mouseX, mouseY);

        int barsX = boxRight + PAD;
        int barsY = top + 78;

        // keep titles from colliding with the previous bar
        final int stepY = BAR_H + (font.lineHeight + 2) + 3;

        boolean tooltipDrawn = false;

        if (currentTab == Tab.MERCHANT) {
            tooltipDrawn |= renderStatBar(gg, font, StatKind.GENEROSITY, this.hasStats ? this.generosity : null,
                    barsX, barsY + stepY * 0, BAR_W, BAR_H, C_GENEROSITY, mouseX, mouseY, !tooltipDrawn);

            tooltipDrawn |= renderStatBar(gg, font, StatKind.TIMELINESS, this.hasStats ? this.timeliness : null,
                    barsX, barsY + stepY * 1, BAR_W, BAR_H, C_TIMELINESS, mouseX, mouseY, !tooltipDrawn);

            tooltipDrawn |= renderStatBar(gg, font, StatKind.INTELLECT, this.hasStats ? this.intellect : null,
                    barsX, barsY + stepY * 2, BAR_W, BAR_H, C_INTELLECT, mouseX, mouseY, !tooltipDrawn);

            tooltipDrawn |= renderStatBar(gg, font, StatKind.HOARDER, this.hasStats ? this.hoarder : null,
                    barsX, barsY + stepY * 3, BAR_W, BAR_H, C_HOARDER, mouseX, mouseY, !tooltipDrawn);
        } else {
            tooltipDrawn |= renderStatBar(gg, font, StatKind.VITALITY, this.hasStats ? this.vitality : null,
                    barsX, barsY + stepY * 0, BAR_W, BAR_H, C_VITALITY, mouseX, mouseY, !tooltipDrawn);

            tooltipDrawn |= renderStatBar(gg, font, StatKind.AGILITY, this.hasStats ? this.agility : null,
                    barsX, barsY + stepY * 1, BAR_W, BAR_H, C_AGILITY, mouseX, mouseY, !tooltipDrawn);

            tooltipDrawn |= renderStatBar(gg, font, StatKind.STRENGTH, this.hasStats ? this.strength : null,
                    barsX, barsY + stepY * 2, BAR_W, BAR_H, C_STRENGTH, mouseX, mouseY, !tooltipDrawn);

            tooltipDrawn |= renderStatBar(gg, font, StatKind.ARMOR, this.hasStats ? this.armor : null,
                    barsX, barsY + stepY * 3, BAR_W, BAR_H, C_ARMOR, mouseX, mouseY, !tooltipDrawn);
        }

        if (!this.hasStats) {
            if (this.statsUnavailable) {
                gg.drawString(font, Component.literal("Stats: unavailable"),
                        barsX, barsY + stepY * 4 + 2, 0xFFFF7777, false);
            } else {
                gg.drawString(font, Component.literal("Stats: syncing…"),
                        barsX, barsY + stepY * 4 + 2, 0xFFAAAAAA, false);
            }
        }

        // If no stat bar tooltip was drawn this frame, allow tab-button tooltip.
        // This prevents the “sticky overlay” you saw.
        if (!tooltipDrawn) {
            if (tabMerchantBtn != null && tabMerchantBtn.isHoveredOrFocused()) {
                gg.renderTooltip(font, Component.literal("Merchant stats"), mouseX, mouseY);
            } else if (tabCombatBtn != null && tabCombatBtn.isHoveredOrFocused()) {
                gg.renderTooltip(font, Component.literal("Combat stats"), mouseX, mouseY);
            }
        }

        // Render widgets (tab icons + Back button)
        super.render(gg, mouseX, mouseY, partialTick);
    }

    private static void drawPanel(GuiGraphics gg, int x, int y, int w, int h) {
        try {
            gg.fill(x, y, x + w, y + h, PANEL_BG);

            gg.fill(x, y, x + w, y + 1, PANEL_BORDER);
            gg.fill(x, y + h - 1, x + w, y + h, PANEL_BORDER);
            gg.fill(x, y, x + 1, y + h, PANEL_BORDER);
            gg.fill(x + w - 1, y, x + w, y + h, PANEL_BORDER);
        } catch (Throwable ignored) {}
    }

    private static void drawEntityBox(GuiGraphics gg, int x1, int y1, int x2, int y2) {
        try {
            gg.fill(x1, y1, x2, y2, 0xFF101010);

            gg.fill(x1, y1, x2, y1 + 1, 0xFF2E2E2E);
            gg.fill(x1, y2 - 1, x2, y2, 0xFF2E2E2E);
            gg.fill(x1, y1, x1 + 1, y2, 0xFF2E2E2E);
            gg.fill(x2 - 1, y1, x2, y2, 0xFF2E2E2E);
        } catch (Throwable ignored) {}
    }

    private void renderVillagerModel(GuiGraphics gg, int boxLeft, int boxTop, int boxRight, int boxBottom, int mouseX, int mouseY) {
        try {
            LivingEntity le = this.cachedEntity;
            if (le == null) return;

            int scale = 48;
            float yOffset = 0.0f;

            int x1 = boxLeft + 6;
            int y1 = boxTop + 6;
            int x2 = boxRight - 6;
            int y2 = boxBottom - 6;

            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    gg,
                    x1, y1, x2, y2,
                    scale,
                    yOffset,
                    (float) mouseX, (float) mouseY,
                    le
            );
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerInfoScreen entity render failed (soft): {}", t.toString());
        }
    }

    private static Component safeName(LivingEntity le) {
        try {
            if (le == null) return Component.literal("?");
            return le.getDisplayName();
        } catch (Throwable t) {
            return Component.literal("?");
        }
    }

    private static ResourceLocation safeEntityTypeId(LivingEntity le) {
        try {
            if (le == null) return null;
            return BuiltInRegistries.ENTITY_TYPE.getKey(le.getType());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Component safeProfession(LivingEntity le) {
        try {
            if (le instanceof WanderingTrader) {
                return Component.translatable("entity.minecraft.wandering_trader");
            }

            if (le instanceof Villager v) {
                var prof = v.getVillagerData().getProfession();
                ResourceLocation key = BuiltInRegistries.VILLAGER_PROFESSION.getKey(prof);
                if (key != null) {
                    return Component.translatable("entity.minecraft.villager." + key.getPath());
                }
                return Component.literal("Villager");
            }

            ResourceLocation typeId = safeEntityTypeId(le);
            if (typeId != null) {
                String id = typeId.toString();
                if ("goblintraders:vein_goblin_trader".equals(id)) return Component.literal("Vein Goblin Trader");
                if ("goblintraders:goblin_trader".equals(id)) return Component.literal("Goblin Trader");
                return Component.literal(id);
            }

            return Component.literal("Unknown");
        } catch (Throwable t) {
            return Component.literal("Unknown");
        }
    }

    /**
     * @return true if this bar rendered a tooltip this frame.
     */
    private static boolean renderStatBar(
            GuiGraphics gg,
            Font font,
            StatKind kind,
            Integer valueOrNull,
            int x, int y, int w, int h,
            int color,
            int mouseX, int mouseY,
            boolean allowTooltip
    ) {
        try {
            String label = kind.label;
            int labelW = font.width(label);
            int labelX = x + (w - labelW) / 2;
            int labelY = y - (font.lineHeight + 2);
            gg.drawString(font, label, labelX, labelY, 0xFFFFFFFF, true);

            gg.fill(x, y, x + w, y + h, BAR_BG);

            gg.fill(x, y, x + w, y + 1, BAR_OUTLINE);
            gg.fill(x, y + h - 1, x + w, y + h, BAR_OUTLINE);
            gg.fill(x, y, x + 1, y + h, BAR_OUTLINE);
            gg.fill(x + w - 1, y, x + w, y + h, BAR_OUTLINE);

            int cx = x + w / 2;
            gg.fill(cx, y + 2, cx + 1, y + h - 2, BAR_CENTER);

            if (valueOrNull != null) {
                int v = Mth.clamp(valueOrNull, VillagerStatsService.POINTS_MIN, VillagerStatsService.POINTS_MAX);

                float norm = v / 100.0f; // -1..+1
                int half = w / 2;

                if (norm > 0.0f) {
                    int fillW = (int) (norm * half);
                    gg.fill(cx + 1, y + 2, cx + 1 + fillW, y + h - 2, color);
                } else if (norm < 0.0f) {
                    int fillW = (int) (Math.abs(norm) * half);
                    gg.fill(cx - fillW, y + 2, cx, y + h - 2, color);
                }
            } else {
                gg.drawString(font, "?", x + w - 10, y + 2, 0xFF777777, false);
            }

            boolean hover = mouseX >= x && mouseX < (x + w) && mouseY >= y && mouseY < (y + h);
            if (allowTooltip && hover) {
                gg.renderComponentTooltip(font, buildStatTooltip(kind, valueOrNull), mouseX, mouseY);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static List<Component> buildStatTooltip(StatKind kind, Integer valueOrNull) {
        List<Component> lines = new ArrayList<>(12);

        lines.add(Component.literal(kind.label).withStyle(s ->
                s.withColor(TextColor.fromRgb(statColorRgb(kind)))
        ));

        if (valueOrNull == null) {
            lines.add(Component.literal("Value: (syncing…)").withStyle(ChatFormatting.DARK_GRAY));
            return lines;
        }

        int points = Mth.clamp(valueOrNull, VillagerStatsService.POINTS_MIN, VillagerStatsService.POINTS_MAX);

        ChatFormatting valueColor =
                points > 0 ? ChatFormatting.GREEN :
                        points < 0 ? ChatFormatting.RED :
                                ChatFormatting.GRAY;

        if (kind == StatKind.HOARDER) {
            String effectShort = shortEffectParen(kind, 0.0, points);
            lines.add(Component.literal("Value: " + points + " (" + effectShort + ")").withStyle(valueColor));

            String clampLine = hoarderClampLine();
            if (clampLine != null) lines.add(Component.literal(clampLine).withStyle(ChatFormatting.DARK_GRAY));

            lines.add(Component.literal(""));
            for (Component c : flavorLines(kind)) lines.add(c);
            return lines;
        }

        if (kind.isMerchant()) {
            Double pct = pointsToPercentFromServerConfig(kind, points);

            if (pct == null) {
                lines.add(Component.literal("Value: " + points).withStyle(valueColor));
            } else {
                String effectShort = shortEffectParen(kind, pct, points);
                lines.add(Component.literal("Value: " + points + " (" + effectShort + ")").withStyle(valueColor));

                String multLine = multiplierLine(kind, pct);
                if (multLine != null) lines.add(Component.literal(multLine).withStyle(ChatFormatting.DARK_GRAY));
            }

            lines.add(Component.literal(""));
            for (Component c : flavorLines(kind)) lines.add(c);
            return lines;
        }

        // Combat stats
        String effectShort = shortEffectParen(kind, 0.0, points);
        lines.add(Component.literal("Value: " + points + " (" + effectShort + ")").withStyle(valueColor));

        if (kind == StatKind.VITALITY) {
            lines.add(Component.literal("Unit: HP (2.0 HP = 1 ❤)").withStyle(ChatFormatting.DARK_GRAY));
        }

        String clampLine = combatClampLine(kind);
        if (clampLine != null) lines.add(Component.literal(clampLine).withStyle(ChatFormatting.DARK_GRAY));

        lines.add(Component.literal(""));
        for (Component c : flavorLines(kind)) lines.add(c);

        return lines;
    }

    private static String shortEffectParen(StatKind kind, double traitPctOrUnused, Integer pointsOrNull) {
        if (pointsOrNull == null) return "";

        int points = Mth.clamp(pointsOrNull, VillagerStatsService.POINTS_MIN, VillagerStatsService.POINTS_MAX);

        return switch (kind) {
            case GENEROSITY -> "Emerald costs " + formatSignedPercent1(-safeFinite(traitPctOrUnused));
            case TIMELINESS -> "Cooldown " + formatSignedPercent1(-safeFinite(traitPctOrUnused));
            case INTELLECT  -> "XP " + formatSignedPercent1(safeFinite(traitPctOrUnused));
            case HOARDER    -> {
                Integer delta = pointsToHoarderDeltaFromServerConfig(points);
                if (delta == null) yield "Offers (syncing…)";
                yield "Offers " + formatSignedInt(delta);
            }

            // IMPORTANT: vitality config is in HP, but players think in hearts.
            // Show both so it matches server config and avoids confusion.
            case VITALITY -> {
                Double hp = pointsToVitalityHpDeltaFromServerConfig(points);
                if (hp == null) yield "Max health (syncing…)";
                double hearts = hp / 2.0;
                yield "Max health " + formatSigned1(hp) + " HP (" + formatSigned1(hearts) + "❤)";
            }
            case AGILITY -> {
                Double delta = pointsToAgilityDeltaFromServerConfig(points);
                if (delta == null) yield "Speed (syncing…)";
                yield "Speed " + formatSigned3(delta);
            }
            case STRENGTH -> {
                Double dmg = pointsToStrengthDeltaFromServerConfig(points);
                if (dmg == null) yield "Damage (syncing…)";
                yield "Damage " + formatSigned1(dmg);
            }
            case ARMOR -> {
                Double arm = pointsToArmorDeltaFromServerConfig(points);
                if (arm == null) yield "Armor (syncing…)";
                yield "Armor " + formatSigned1(arm);
            }
        };
    }

    private static String formatSignedInt(int v) {
        if (v > 0) return "+" + v;
        if (v < 0) return String.valueOf(v);
        return "0";
    }

    private static String multiplierLine(StatKind kind, double traitPct) {
        double p = safeFinite(traitPct);

        return switch (kind) {
            case GENEROSITY -> {
                double m = 1.0 - (p / 100.0);
                if (m < 0.0) m = 0.0;
                yield "Multiplier: x" + formatMultiplier(m) + " (cost)";
            }
            case TIMELINESS -> {
                double m = 1.0 - (p / 100.0);
                if (m < 0.0) m = 0.0;
                yield "Multiplier: x" + formatMultiplier(m) + " (cooldown)";
            }
            case INTELLECT -> {
                double m = 1.0 + (p / 100.0);
                if (m < 0.0) m = 0.0;
                yield "Multiplier: x" + formatMultiplier(m) + " (XP)";
            }
            default -> null;
        };
    }

    private static String formatSignedPercent1(double pct) {
        double v = safeFinite(pct);
        double r = Math.round(v * 10.0) / 10.0;
        if (Math.abs(r) < 0.05) r = 0.0;
        if (r > 0.0) return "+" + r + "%";
        if (r < 0.0) return r + "%";
        return "0%";
    }

    private static String formatSigned1(double v) {
        double x = safeFinite(v);
        double r = Math.round(x * 10.0) / 10.0;
        if (Math.abs(r) < 0.05) r = 0.0;
        if (r > 0.0) return "+" + r;
        if (r < 0.0) return String.valueOf(r);
        return "0";
    }

    private static String formatSigned3(double v) {
        double x = safeFinite(v);
        double r = Math.round(x * 1000.0) / 1000.0;
        if (Math.abs(r) < 0.0005) r = 0.0;
        if (r > 0.0) return "+" + r;
        if (r < 0.0) return String.valueOf(r);
        return "0";
    }

    private static String formatMultiplier(double m) {
        double v = safeFinite(m);
        if (v < 0.0) v = 0.0;
        double r = Math.round(v * 1000.0) / 1000.0;
        if (Math.abs(r - 1.0) < 0.0005) return "1.000";
        return String.valueOf(r);
    }

    private static double safeFinite(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return v;
    }

    private static List<Component> flavorLines(StatKind kind) {
        List<String> raw = switch (kind) {
            case GENEROSITY -> List.of("Affects the price of rerolling & trades.", "Higher = cheaper, lower = pricier.");
            case TIMELINESS -> List.of("Affects how quickly rerolls recharge.", "Higher = faster cooldown, lower = slower.");
            case INTELLECT  -> List.of("Affects experience gained from rerolls.", "Higher = more experience, lower = less.");
            case HOARDER    -> List.of("Affects how many trade offers are available.", "Higher = more offers, lower = fewer.");

            case VITALITY -> List.of("Affects maximum health.", "Higher = tougher, lower = frailer.");
            case AGILITY  -> List.of("Affects movement speed.", "Higher = faster, lower = slower.");
            case STRENGTH -> List.of("Affects attack damage.", "Higher = stronger, lower = weaker.");
            case ARMOR    -> List.of("Affects armor value.", "Higher = tankier, lower = squishier.");
        };

        List<Component> out = new ArrayList<>(raw.size());
        for (String s : raw) out.add(Component.literal(s).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        return out;
    }

    private static int statColorRgb(StatKind kind) {
        int argb = switch (kind) {
            case GENEROSITY -> C_GENEROSITY;
            case TIMELINESS -> C_TIMELINESS;
            case INTELLECT  -> C_INTELLECT;
            case HOARDER    -> C_HOARDER;
            case VITALITY   -> C_VITALITY;
            case AGILITY    -> C_AGILITY;
            case STRENGTH   -> C_STRENGTH;
            case ARMOR      -> C_ARMOR;
        };
        return argb & 0x00FFFFFF;
    }

    private static Double pointsToPercentFromServerConfig(StatKind kind, int points) {
        try {
            ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
            if (cfg == null) return null;

            double min;
            double max;

            switch (kind) {
                case GENEROSITY -> { min = cfg.generosityMinPct; max = cfg.generosityMaxPct; }
                case TIMELINESS -> { min = cfg.timelinessMinPct; max = cfg.timelinessMaxPct; }
                case INTELLECT  -> { min = cfg.intellectMinPct; max = cfg.intellectMaxPct; }
                default -> { return null; }
            }

            return lerpFromPoints(points, min, max);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Integer pointsToHoarderDeltaFromServerConfig(int points) {
        try {
            ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
            if (cfg == null) return null;

            int minDelta = cfg.hoarderExtraOffersMin;
            int maxDelta = cfg.hoarderExtraOffersMax;
            if (minDelta > maxDelta) { int tmp = minDelta; minDelta = maxDelta; maxDelta = tmp; }

            double d = lerpFromPoints(points, minDelta, maxDelta);
            int delta = (int) Math.round(d);

            if (delta < minDelta) delta = minDelta;
            if (delta > maxDelta) delta = maxDelta;

            return delta;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String hoarderClampLine() {
        try {
            ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
            if (cfg == null) return null;
            int min = cfg.hoarderExtraOffersMin;
            int max = cfg.hoarderExtraOffersMax;
            if (min > max) { int tmp = min; min = max; max = tmp; }
            return "Clamp: [" + min + ", " + max + "] offers";
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Double pointsToVitalityHpDeltaFromServerConfig(int points) {
        try {
            ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
            if (cfg == null) return null;
            return lerpFromPoints(points, cfg.vitalityMinHealth, cfg.vitalityMaxHealth);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Double pointsToAgilityDeltaFromServerConfig(int points) {
        try {
            ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
            if (cfg == null) return null;
            return lerpFromPoints(points, cfg.agilityMinSpeed, cfg.agilityMaxSpeed);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Double pointsToStrengthDeltaFromServerConfig(int points) {
        try {
            ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
            if (cfg == null) return null;
            return lerpFromPoints(points, cfg.strengthMinDamage, cfg.strengthMaxDamage);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Double pointsToArmorDeltaFromServerConfig(int points) {
        try {
            ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
            if (cfg == null) return null;
            return lerpFromPoints(points, cfg.armorMin, cfg.armorMax);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String combatClampLine(StatKind kind) {
        try {
            ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
            if (cfg == null) return null;

            return switch (kind) {
                case VITALITY -> {
                    // Config is in HP. Show HP first (matches server config), then hearts.
                    double minHp = safeFinite(cfg.vitalityMinHealth);
                    double maxHp = safeFinite(cfg.vitalityMaxHealth);
                    if (minHp > maxHp) { double t = minHp; minHp = maxHp; maxHp = t; }

                    double minHearts = minHp / 2.0;
                    double maxHearts = maxHp / 2.0;

                    yield "Clamp: [" + formatSigned1(minHp) + ", " + formatSigned1(maxHp) + "] HP"
                            + " (" + formatSigned1(minHearts) + " to " + formatSigned1(maxHearts) + "❤)";
                }
                case AGILITY -> {
                    double min = safeFinite(cfg.agilityMinSpeed);
                    double max = safeFinite(cfg.agilityMaxSpeed);
                    if (min > max) { double t = min; min = max; max = t; }
                    yield "Clamp: [" + formatSigned3(min) + ", " + formatSigned3(max) + "] speed";
                }
                case STRENGTH -> {
                    double min = safeFinite(cfg.strengthMinDamage);
                    double max = safeFinite(cfg.strengthMaxDamage);
                    if (min > max) { double t = min; min = max; max = t; }
                    yield "Clamp: [" + formatSigned1(min) + ", " + formatSigned1(max) + "] damage";
                }
                case ARMOR -> {
                    double min = safeFinite(cfg.armorMin);
                    double max = safeFinite(cfg.armorMax);
                    if (min > max) { double t = min; min = max; max = t; }
                    yield "Clamp: [" + formatSigned1(min) + ", " + formatSigned1(max) + "] armor";
                }
                default -> null;
            };
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static double lerpFromPoints(int points, double min, double max) {
        int p = Mth.clamp(points, VillagerStatsService.POINTS_MIN, VillagerStatsService.POINTS_MAX);
        double t = (p + 100.0) / 200.0;
        t = Mth.clamp((float) t, 0.0f, 1.0f);
        return min + (max - min) * t;
    }

    // -----------------------------------------------------------------------------------------
    // Custom icon tab widget (NO tooltip rendering here; screen handles tooltips to avoid overlap).
    // -----------------------------------------------------------------------------------------

    private final class IconTabButton extends AbstractWidget {

        private final String symbol;
        private final Component tooltip;
        private final Tab target;

        IconTabButton(int x, int y, int size, String symbol, Component tooltip, Tab target) {
            super(x, y, size, size, Component.empty());
            this.symbol = symbol == null ? "?" : symbol;
            this.tooltip = tooltip == null ? Component.empty() : tooltip;
            this.target = target == null ? Tab.MERCHANT : target;
        }

        public Component getTooltipComponent() {
            return tooltip;
        }

        @Override
        protected void renderWidget(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
            try {
                boolean selected = (VillagerInfoScreen.this.currentTab == target);
                boolean hover = this.isHoveredOrFocused();

                int x = getX();
                int y = getY();
                int s = this.width;

                int bg = selected ? 0xFF2E2E2E : (hover ? 0xFF242424 : 0xFF1A1A1A);
                int border = selected ? 0xFFFFFFFF : (hover ? 0xFFBFBFBF : 0xFF6A6A6A);
                int txt = selected ? 0xFFFFFFFF : 0xFFEAEAEA;

                gg.fill(x, y, x + s, y + s, bg);
                gg.fill(x, y, x + s, y + 1, border);
                gg.fill(x, y + s - 1, x + s, y + s, border);
                gg.fill(x, y, x + 1, y + s, border);
                gg.fill(x + s - 1, y, x + s, y + s, border);

                Font f = Minecraft.getInstance().font;
                int tw = f.width(symbol);
                int tx = x + (s - tw) / 2;
                int ty = y + (s - f.lineHeight) / 2;
                gg.drawString(f, symbol, tx, ty, txt, false);
            } catch (Throwable ignored) {}
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            try {
                if (VillagerInfoScreen.this.currentTab != target) {
                    VillagerInfoScreen.this.currentTab = target;
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerInfoScreen switched tab -> {}", target.name());
                }
            } catch (Throwable t) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] IconTabButton.onClick failed (soft): {}", t.toString());
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
            try {
                narrationElementOutput.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, tooltip);
            } catch (Throwable ignored) {}
        }
    }
}
