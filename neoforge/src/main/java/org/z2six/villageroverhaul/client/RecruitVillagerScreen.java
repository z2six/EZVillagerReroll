// neoforge\src\main\java\org\z2six\villageroverhaul\client\RecruitVillagerScreen.java
package org.z2six.villageroverhaul.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.ClientSyncedConfig;
import org.z2six.villageroverhaul.network.ClientVillagerStatsCache;
import org.z2six.villageroverhaul.network.Network;
import org.z2six.villageroverhaul.network.recruit.PacketRecruitCostQuery;
import org.z2six.villageroverhaul.network.recruit.PacketRecruitVillager;
import org.z2six.villageroverhaul.network.stats.PacketVillagerStatsData;
import org.z2six.villageroverhaul.network.stats.PacketVillagerStatsQuery;
import org.z2six.villageroverhaul.server.VillagerStatsService;

import java.util.ArrayList;
import java.util.List;

public final class RecruitVillagerScreen extends Screen {

    // Room for 2 columns of text
    private static final int W = 320;
    private static final int H = 190;

    private static final long STATS_QUERY_DEBOUNCE_MS = 750;

    // Merchant colors (MATCH VillagerInfoScreen)
    private static final int C_GENEROSITY = 0xFF42D16C; // green
    private static final int C_TIMELINESS = 0xFF2FC7FF; // cyan
    private static final int C_INTELLECT  = 0xFFB26BFF; // purple
    private static final int C_HOARDER    = 0xFFFFB347; // orange

    // Combat colors
    private static final int C_VITALITY = 0xFFFF5A5A; // red-ish
    private static final int C_AGILITY  = 0xFF4DD6FF; // light blue
    private static final int C_STRENGTH = 0xFFFF7A2F; // orange-red
    private static final int C_ARMOR    = 0xFFB0B0B0; // silver

    private final int villagerEntityId;

    private int cost;
    private boolean eligible;
    private boolean alreadyRecruited;
    private String serverMessage;

    // Stats state
    private boolean hasStats = false;
    private boolean statsUnavailable = false;

    private int generosity = 0, timeliness = 0, intellect = 0, hoarder = 0;
    private int vitality = 0, agility = 0, strength = 0, armor = 0;

    private long lastStatsQueryMs = 0L;
    private boolean sentStatsQuery = false;

    private Button recruitBtn;
    private Button cancelBtn;

    private boolean sentRecruit = false;
    private boolean sentCostQuery = false;

    private static final int TIP_ICON = 9;

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

    public RecruitVillagerScreen(int villagerEntityId, int cost, boolean eligible, boolean alreadyRecruited, String serverMessage) {
        super(Component.literal("Recruit villager"));
        this.villagerEntityId = villagerEntityId;
        this.cost = Math.max(0, cost);
        this.eligible = eligible;
        this.alreadyRecruited = alreadyRecruited;
        this.serverMessage = serverMessage == null ? "" : serverMessage;
    }

    public int getVillagerEntityId() {
        return villagerEntityId;
    }

    public void applyResult(boolean success, boolean nowRecruited, int costPaid, String msg) {
        try {
            this.alreadyRecruited = nowRecruited;
            this.serverMessage = msg == null ? "" : msg;

            if (success && nowRecruited) {
                Minecraft.getInstance().setScreen(null);
            } else {
                this.sentRecruit = false;
                if (recruitBtn != null) recruitBtn.active = canRecruitNow();
                if (cancelBtn != null) cancelBtn.active = true;
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] RecruitVillagerScreen.applyResult failed (soft): {}", t.toString());
        }
    }

    public void applyCostUpdate(boolean ok, boolean eligible, boolean alreadyRecruited, int cost, String msg) {
        try {
            this.eligible = eligible;
            this.alreadyRecruited = alreadyRecruited;
            if (ok) this.cost = Math.max(0, cost);
            this.serverMessage = msg == null ? "" : msg;

            if (recruitBtn != null) recruitBtn.active = canRecruitNow();
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] RecruitVillagerScreen.applyCostUpdate failed (soft): {}", t.toString());
        }
    }

    private boolean canRecruitNow() {
        return !sentRecruit && eligible && !alreadyRecruited && cost >= 0;
    }

    @Override
    protected void init() {
        int left = (this.width - W) / 2;
        int top = (this.height - H) / 2;

        // Refresh eligibility/cost from server when opened (once)
        if (!sentCostQuery) {
            sentCostQuery = true;
            try {
                Network.sendToServer(new PacketRecruitCostQuery(villagerEntityId));
            } catch (Throwable t) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] RecruitVillagerScreen cost query failed (soft): {}", t.toString());
            }
        }

        // Request stats once on open
        if (!sentStatsQuery) {
            sentStatsQuery = true;
            trySendStatsQuery(false);
        }
        tryApplyStatsFromCache();

        recruitBtn = Button.builder(Component.literal("Recruit"), b -> {
            try {
                if (sentRecruit) return;
                sentRecruit = true;
                recruitBtn.active = false;
                cancelBtn.active = false;
                Network.sendToServer(new PacketRecruitVillager(villagerEntityId));
            } catch (Throwable t) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Recruit button failed (soft): {}", t.toString());
                sentRecruit = false;
                if (recruitBtn != null) recruitBtn.active = canRecruitNow();
                if (cancelBtn != null) cancelBtn.active = true;
            }
        }).bounds(left + 16, top + H - 28, 110, 20).build();

        cancelBtn = Button.builder(Component.literal("Cancel"), b -> Minecraft.getInstance().setScreen(null))
                .bounds(left + W - 16 - 110, top + H - 28, 110, 20)
                .build();

        recruitBtn.active = canRecruitNow();

        addRenderableWidget(recruitBtn);
        addRenderableWidget(cancelBtn);
    }

    @Override
    public void tick() {
        super.tick();
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

            Network.sendToServer(new PacketVillagerStatsQuery(this.villagerEntityId));
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] RecruitVillagerScreen sent PacketVillagerStatsQuery(entityId={})", this.villagerEntityId);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] RecruitVillagerScreen.trySendStatsQuery failed (soft): {}", t.toString());
        }
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        try {
            super.renderBackground(gg, mouseX, mouseY, partialTick);
        } catch (Throwable ignored) {}

        int left = (this.width - W) / 2;
        int top = (this.height - H) / 2;

        // panel
        gg.fill(left, top, left + W, top + H, 0xCC000000);
        gg.fill(left + 1, top + 1, left + W - 1, top + H - 1, 0xAA1A1A1A);

        // Title
        gg.drawCenteredString(this.font, "Recruit villager", this.width / 2, top + 10, 0xFFFFFF);

        // Cost line
        int costY = top + 30;
        gg.drawString(this.font, "Cost:", left + 16, costY, 0xE0E0E0);

        ItemStack emerald = new ItemStack(Items.EMERALD);
        gg.renderItem(emerald, left + 60, costY - 4);
        gg.drawString(this.font, String.valueOf(cost), left + 80, costY, 0xFFFFFF);

        // State message
        int msgY = top + 46;
        if (!serverMessage.isEmpty()) {
            gg.drawCenteredString(this.font, serverMessage, this.width / 2, msgY, 0xFFCC66);
        } else if (alreadyRecruited) {
            gg.drawCenteredString(this.font, "Already recruited", this.width / 2, msgY, 0xFFCC66);
        } else if (!eligible) {
            gg.drawCenteredString(this.font, "Not eligible", this.width / 2, msgY, 0xFF6666);
        } else if (sentRecruit) {
            gg.drawCenteredString(this.font, "Recruiting...", this.width / 2, msgY, 0x66FF66);
        }

        // Stats area
        int statsTop = top + 66;
        int colGap = 16;
        int colW = (W - 32 - colGap) / 2;
        int col1X = left + 16;
        int col2X = col1X + colW + colGap;

        gg.drawString(this.font, "Merchant stats", col1X, statsTop, 0xFFFFFFFF);
        gg.drawString(this.font, "Combat stats", col2X, statsTop, 0xFFFFFFFF);

        int lineY = statsTop + 14;
        int lh = this.font.lineHeight + 3;

        if (!hasStats) {
            int c = statsUnavailable ? 0xFFFF7777 : 0xFFAAAAAA;
            String s = statsUnavailable ? "Stats: unavailable" : "Stats: syncing…";
            gg.drawString(this.font, s, col1X, lineY, c);
            gg.drawString(this.font, s, col2X, lineY, c);
        } else {
            // Merchant column (tooltips EXACTLY like VillagerInfoScreen)
            drawStatLine(gg, col1X, lineY + lh * 0, StatKind.GENEROSITY, generosity, C_GENEROSITY, mouseX, mouseY);
            drawStatLine(gg, col1X, lineY + lh * 1, StatKind.TIMELINESS, timeliness, C_TIMELINESS, mouseX, mouseY);
            drawStatLine(gg, col1X, lineY + lh * 2, StatKind.INTELLECT, intellect, C_INTELLECT, mouseX, mouseY);
            drawStatLine(gg, col1X, lineY + lh * 3, StatKind.HOARDER, hoarder, C_HOARDER, mouseX, mouseY);

            // Combat column
            drawStatLine(gg, col2X, lineY + lh * 0, StatKind.VITALITY, vitality, C_VITALITY, mouseX, mouseY);
            drawStatLine(gg, col2X, lineY + lh * 1, StatKind.AGILITY, agility, C_AGILITY, mouseX, mouseY);
            drawStatLine(gg, col2X, lineY + lh * 2, StatKind.STRENGTH, strength, C_STRENGTH, mouseX, mouseY);
            drawStatLine(gg, col2X, lineY + lh * 3, StatKind.ARMOR, armor, C_ARMOR, mouseX, mouseY);
        }

        // Render widgets manually (buttons), WITHOUT re-drawing background.
        try {
            for (Renderable r : this.renderables) {
                try {
                    r.render(gg, mouseX, mouseY, partialTick);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] RecruitVillagerScreen widget render failed (soft): {}", t.toString());
        }
    }

    private void drawStatLine(GuiGraphics gg, int x, int y, StatKind kind, int points, int argbLabel, int mouseX, int mouseY) {
        try {
            int p = Mth.clamp(points, VillagerStatsService.POINTS_MIN, VillagerStatsService.POINTS_MAX);

            ChatFormatting pv =
                    p > 0 ? ChatFormatting.GREEN :
                            p < 0 ? ChatFormatting.RED :
                                    ChatFormatting.GRAY;

            Component base = Component.literal(kind.label + ": ")
                    .withStyle(s -> s.withColor(TextColor.fromRgb(argbLabel & 0x00FFFFFF)))
                    .append(Component.literal(formatSignedPoints(p)).withStyle(pv));

            gg.drawString(this.font, base, x, y, 0xFFFFFFFF, false);

            int baseW = this.font.width(base);
            int ix = x + baseW + 6;
            int iy = y + 1;

            // little box
            int bg = 0xFF2A2A2A;
            int border = 0xFF6A6A6A;
            gg.fill(ix, iy, ix + TIP_ICON, iy + TIP_ICON, bg);

            // border (1px)
            gg.fill(ix, iy, ix + TIP_ICON, iy + 1, border);
            gg.fill(ix, iy + TIP_ICON - 1, ix + TIP_ICON, iy + TIP_ICON, border);
            gg.fill(ix, iy, ix + 1, iy + TIP_ICON, border);
            gg.fill(ix + TIP_ICON - 1, iy, ix + TIP_ICON, iy + TIP_ICON, border);

            // "?"
            gg.drawString(this.font, "?", ix + 3, iy + 1, 0xFFEAEAEA, false);

            boolean hover = mouseX >= ix && mouseX < (ix + TIP_ICON) && mouseY >= iy && mouseY < (iy + TIP_ICON);
            if (hover) {
                gg.renderComponentTooltip(this.font, buildStatTooltip(kind, p), mouseX, mouseY);
            }
        } catch (Throwable ignored) {}
    }

    private static String formatSignedPoints(int p) {
        if (p > 0) return "+" + p;
        return String.valueOf(p);
    }

    // -----------------------------------------------------------------------------------------
    // Tooltips (copied 1:1 in behavior from VillagerInfoScreen)
    // -----------------------------------------------------------------------------------------

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

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
