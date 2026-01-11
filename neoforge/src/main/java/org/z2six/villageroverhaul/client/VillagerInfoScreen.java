// VillagerInfoScreen.java
// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/client/VillagerInfoScreen.java
package org.z2six.villageroverhaul.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
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

    private int generosity = 0;
    private int timeliness = 0;
    private int intellect  = 0;
    private int hoarder    = 0;

    private long lastStatsQueryMs = 0L;

    // Layout
    // (slightly wider than before: ~8% increase)
    private static final int PANEL_W = 316;
    private static final int PANEL_H = 190;

    private static final int PAD = 10;

    // Entity box (left)
    private static final int ENTITY_BOX_W = 120;
    private static final int ENTITY_BOX_H = 140;

    // Bars (right)
    private static final int BAR_W = 140;
    private static final int BAR_H = 12;
    private static final int BAR_GAP = 10;

    // Colors (ARGB)
    private static final int PANEL_BG = 0xCC0B0B0B;
    private static final int PANEL_BORDER = 0xFF3A3A3A;

    private static final int BAR_BG = 0xFF151515;
    private static final int BAR_OUTLINE = 0xFF404040;
    private static final int BAR_CENTER = 0xFFAAAAAA;

    private static final int C_GENEROSITY = 0xFF42D16C; // green
    private static final int C_TIMELINESS = 0xFF2FC7FF; // cyan
    private static final int C_INTELLECT  = 0xFFB26BFF; // purple
    private static final int C_HOARDER    = 0xFFFFB347; // orange

    private enum StatKind {
        GENEROSITY("Generosity"),
        TIMELINESS("Timeliness"),
        INTELLECT("Intellect"),
        HOARDER("Hoarder");

        final String label;
        StatKind(String label) { this.label = label; }
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

        this.addRenderableWidget(
                Button.builder(Component.literal("Back"), b -> onClose())
                        .pos(left + PANEL_W - 58 - PAD, top + PAD)
                        .size(58, 18)
                        .build()
        );

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

            this.generosity = VillagerStatsService.clampPoints(snap.generosity());
            this.timeliness = VillagerStatsService.clampPoints(snap.timeliness());
            this.intellect  = VillagerStatsService.clampPoints(snap.intellect());
            this.hoarder    = VillagerStatsService.clampPoints(snap.hoarder());

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

            // Removed "Type: <namespace:id>" row (not needed)
        }

        renderVillagerModel(gg, boxLeft, boxTop, boxRight, boxBottom, mouseX, mouseY);

        int barsX = boxRight + PAD;
        int barsY = top + 78;

        renderStatBar(gg, font, StatKind.GENEROSITY, this.hasStats ? this.generosity : null,
                barsX, barsY, BAR_W, BAR_H, C_GENEROSITY, mouseX, mouseY);

        renderStatBar(gg, font, StatKind.TIMELINESS, this.hasStats ? this.timeliness : null,
                barsX, barsY + (BAR_H + BAR_GAP) * 1, BAR_W, BAR_H, C_TIMELINESS, mouseX, mouseY);

        renderStatBar(gg, font, StatKind.INTELLECT, this.hasStats ? this.intellect : null,
                barsX, barsY + (BAR_H + BAR_GAP) * 2, BAR_W, BAR_H, C_INTELLECT, mouseX, mouseY);

        renderStatBar(gg, font, StatKind.HOARDER, this.hasStats ? this.hoarder : null,
                barsX, barsY + (BAR_H + BAR_GAP) * 3, BAR_W, BAR_H, C_HOARDER, mouseX, mouseY);

        if (!this.hasStats) {
            if (this.statsUnavailable) {
                gg.drawString(font, Component.literal("Stats: unavailable"),
                        barsX, barsY + (BAR_H + BAR_GAP) * 5 + 2, 0xFFFF7777, false);
            } else {
                gg.drawString(font, Component.literal("Stats: syncing…"),
                        barsX, barsY + (BAR_H + BAR_GAP) * 5 + 2, 0xFFAAAAAA, false);
            }
        }

        // Render widgets (Back button)
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

            // Non-villager merchants: show a human-friendly label for known goblintraders entity ids.
            ResourceLocation typeId = safeEntityTypeId(le);
            if (typeId != null) {
                String id = typeId.toString();
                if ("goblintraders:vein_goblin_trader".equals(id)) {
                    return Component.literal("Vein Goblin Trader");
                }
                if ("goblintraders:goblin_trader".equals(id)) {
                    return Component.literal("Goblin Trader");
                }
                return Component.literal(id);
            }

            return Component.literal("Unknown");
        } catch (Throwable t) {
            return Component.literal("Unknown");
        }
    }

    private static void renderStatBar(
            GuiGraphics gg,
            Font font,
            StatKind kind,
            Integer valueOrNull,
            int x, int y, int w, int h,
            int color,
            int mouseX, int mouseY
    ) {
        try {
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
            if (hover) {
                gg.renderComponentTooltip(font, buildStatTooltip(kind, valueOrNull), mouseX, mouseY);
            }
        } catch (Throwable ignored) {}
    }

    // -----------------------------------------------------------------------------------------
    // Tooltip building: points + server-config-based percent + player-facing explanation.
    // -----------------------------------------------------------------------------------------

    private static List<Component> buildStatTooltip(StatKind kind, Integer valueOrNull) {
        List<Component> lines = new ArrayList<>(8);

        // Colored title (same as bar color)
        lines.add(Component.literal(kind.label).withStyle(s ->
                s.withColor(TextColor.fromRgb(statColorRgb(kind)))
        ));

        if (valueOrNull == null) {
            lines.add(Component.literal("Value: (syncing…)").withStyle(ChatFormatting.DARK_GRAY));
            return lines;
        }

        int points = Mth.clamp(valueOrNull, VillagerStatsService.POINTS_MIN, VillagerStatsService.POINTS_MAX);

        // Value line colored based on sign
        ChatFormatting valueColor =
                points > 0 ? ChatFormatting.GREEN :
                        points < 0 ? ChatFormatting.RED :
                                ChatFormatting.GRAY;

        Double pct = pointsToPercentFromServerConfig(kind, points);

        // Main "Value" line now shows *player-facing effect* (not the raw trait pct)
        if (pct == null) {
            lines.add(Component.literal("Value: " + points).withStyle(valueColor));
        } else {
            String effectShort = shortEffectParen(kind, pct);
            lines.add(Component.literal("Value: " + points + " (" + effectShort + ")").withStyle(valueColor));

            // Extra “what it means” line: show multiplier where it is well-defined in code
            String multLine = multiplierLine(kind, pct);
            if (multLine != null) {
                lines.add(Component.literal(multLine).withStyle(ChatFormatting.DARK_GRAY));
            }
        }

        // Spacer
        lines.add(Component.literal(""));

        // Flavor text: grey-ish + cursive (italic)
        for (Component c : flavorLines(kind)) {
            lines.add(c);
        }

        return lines;
    }

    /**
     * Short parenthetical summary shown next to the points.
     * Example: "Reroll cost +19.9%" for negative Generosity.
     */
    private static String shortEffectParen(StatKind kind, double traitPct) {
        double p = safeFinite(traitPct);

        return switch (kind) {
            case GENEROSITY -> "Reroll cost " + formatSignedPercent1(-p);
            case TIMELINESS -> "Cooldown " + formatSignedPercent1(-p);
            case INTELLECT  -> "XP " + formatSignedPercent1(p);
            case HOARDER    -> "Offers " + formatSignedPercent1(p);
        };
    }

    /**
     * Optional second line that shows the actual multiplier used by the server-side logic.
     * Only emits for stats with a clearly-defined multiplier in your code today.
     */
    private static String multiplierLine(StatKind kind, double traitPct) {
        double p = safeFinite(traitPct);

        return switch (kind) {
            case GENEROSITY -> {
                // costMult = 1 - pct/100
                double m = 1.0 - (p / 100.0);
                if (m < 0.0) m = 0.0;
                yield "Multiplier: x" + formatMultiplier(m) + " (cost)";
            }
            case TIMELINESS -> {
                // cooldownMult = 1 - pct/100
                double m = 1.0 - (p / 100.0);
                if (m < 0.0) m = 0.0;
                yield "Multiplier: x" + formatMultiplier(m) + " (cooldown)";
            }
            case INTELLECT -> {
                // xpMult = 1 + pct/100
                double m = 1.0 + (p / 100.0);
                if (m < 0.0) m = 0.0;
                yield "Multiplier: x" + formatMultiplier(m) + " (XP)";
            }
            case HOARDER -> null; // effect model isn't a simple multiplier (yet / depends on your implementation)
        };
    }

    private static String formatSignedPercent1(double pct) {
        double v = safeFinite(pct);

        // round to 1 decimal (so you can get e.g. 19.9%)
        double r = Math.round(v * 10.0) / 10.0;

        // avoid "-0.0%"
        if (Math.abs(r) < 0.05) r = 0.0;

        if (r > 0.0) return "+" + r + "%";
        if (r < 0.0) return r + "%";
        return "0%";
    }

    private static String formatMultiplier(double m) {
        double v = safeFinite(m);
        if (v < 0.0) v = 0.0;

        // 3 decimals feels nice for multipliers (x1.200, x0.801, etc.)
        double r = Math.round(v * 1000.0) / 1000.0;

        // make "1.0" show as "1" if you prefer; leaving as-is is also fine
        if (Math.abs(r - 1.0) < 0.0005) return "1.000";
        return String.valueOf(r);
    }

    private static double safeFinite(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return v;
    }

    private static List<Component> flavorLines(StatKind kind) {
        List<String> raw = switch (kind) {
            case GENEROSITY -> List.of(
                    "Affects the price of rerolling.",
                    "Higher = cheaper, lower = pricier."
            );
            case TIMELINESS -> List.of(
                    "Affects how quickly rerolls recharge.",
                    "Higher = faster cooldown, lower = slower."
            );
            case INTELLECT -> List.of(
                    "Affects experience gained from rerolls.",
                    "Higher = more experience, lower = less."
            );
            case HOARDER -> List.of(
                    "Affects how many trade offers are available.",
                    "Higher = more offers, lower = fewer."
            );
        };

        List<Component> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            out.add(Component.literal(s).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        }
        return out;
    }

    private static int statColorRgb(StatKind kind) {
        int argb = switch (kind) {
            case GENEROSITY -> C_GENEROSITY;
            case TIMELINESS -> C_TIMELINESS;
            case INTELLECT  -> C_INTELLECT;
            case HOARDER    -> C_HOARDER;
        };
        return argb & 0x00FFFFFF; // strip alpha
    }

    private static String formatPercent(double pct) {
        long rounded = Math.round(pct);
        if (rounded > 0) return "+" + rounded + "%";
        if (rounded < 0) return rounded + "%";
        return "0%";
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
                case INTELLECT -> { min = cfg.intellectMinPct; max = cfg.intellectMaxPct; }
                case HOARDER -> { min = cfg.hoarderMinPct; max = cfg.hoarderMaxPct; }
                default -> { return null; }
            }

            int p = Mth.clamp(points, VillagerStatsService.POINTS_MIN, VillagerStatsService.POINTS_MAX);

            double t = (p + 100.0) / 200.0;
            t = Mth.clamp((float) t, 0.0f, 1.0f);

            return min + (max - min) * t;

        } catch (Throwable ignored) {
            return null;
        }
    }
}
