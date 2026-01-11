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
import org.z2six.villageroverhaul.network.PacketRecruitCostQuery;
import org.z2six.villageroverhaul.network.PacketRecruitVillager;
import org.z2six.villageroverhaul.network.PacketVillagerStatsData;
import org.z2six.villageroverhaul.network.PacketVillagerStatsQuery;
import org.z2six.villageroverhaul.server.VillagerStatsService;

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

    // Combat colors (new)
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
            // Merchant column
            drawStatLine(gg, col1X, lineY + lh * 0, "Generosity", generosity, C_GENEROSITY, merchantEffect("generosity", generosity));
            drawStatLine(gg, col1X, lineY + lh * 1, "Timeliness", timeliness, C_TIMELINESS, merchantEffect("timeliness", timeliness));
            drawStatLine(gg, col1X, lineY + lh * 2, "Intellect", intellect, C_INTELLECT, merchantEffect("intellect", intellect));
            drawStatLine(gg, col1X, lineY + lh * 3, "Hoarder", hoarder, C_HOARDER, hoarderEffect(hoarder));

            // Combat column
            drawStatLine(gg, col2X, lineY + lh * 0, "Vitality", vitality, C_VITALITY, vitalityEffect(vitality));
            drawStatLine(gg, col2X, lineY + lh * 1, "Agility", agility, C_AGILITY, agilityEffect(agility));
            drawStatLine(gg, col2X, lineY + lh * 2, "Strength", strength, C_STRENGTH, strengthEffect(strength));
            drawStatLine(gg, col2X, lineY + lh * 3, "Armor", armor, C_ARMOR, armorEffect(armor));
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

    private void drawStatLine(GuiGraphics gg, int x, int y, String label, int points, int argbLabel, String effect) {
        try {
            int p = Mth.clamp(points, VillagerStatsService.POINTS_MIN, VillagerStatsService.POINTS_MAX);

            ChatFormatting pv =
                    p > 0 ? ChatFormatting.GREEN :
                            p < 0 ? ChatFormatting.RED :
                                    ChatFormatting.GRAY;

            Component c = Component.literal(label + ": ")
                    .withStyle(s -> s.withColor(TextColor.fromRgb(argbLabel & 0x00FFFFFF)))
                    .append(Component.literal(formatSignedPoints(p)).withStyle(pv))
                    .append(Component.literal(" (" + (effect == null ? "config…" : effect) + ")").withStyle(ChatFormatting.DARK_GRAY));

            gg.drawString(this.font, c, x, y, 0xFFFFFFFF, false);
        } catch (Throwable ignored) {}
    }

    private static String formatSignedPoints(int p) {
        if (p > 0) return "+" + p;
        return String.valueOf(p);
    }

    // -----------------------------
    // Effect text helpers (uses ClientSyncedConfig)
    // -----------------------------

    private static String merchantEffect(String kind, int points) {
        try {
            ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
            if (cfg == null) return null;

            double min, max;
            switch (kind) {
                case "generosity" -> { min = cfg.generosityMinPct; max = cfg.generosityMaxPct; }
                case "timeliness" -> { min = cfg.timelinessMinPct; max = cfg.timelinessMaxPct; }
                case "intellect"  -> { min = cfg.intellectMinPct;  max = cfg.intellectMaxPct;  }
                default -> { return null; }
            }

            double pct = lerpFromPoints(points, min, max);
            long r = Math.round(Math.abs(pct));

            if ("generosity".equals(kind)) {
                return (pct >= 0.0) ? (r + "% discount") : (r + "% surcharge");
            }
            if ("timeliness".equals(kind)) {
                return (pct >= 0.0) ? (r + "% faster") : (r + "% slower");
            }
            // intellect
            return (pct >= 0.0) ? (r + "% more XP") : (r + "% less XP");

        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String hoarderEffect(int points) {
        try {
            ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
            if (cfg == null) return null;

            int min = cfg.hoarderExtraOffersMin;
            int max = cfg.hoarderExtraOffersMax;
            if (min > max) { int tmp = min; min = max; max = tmp; }

            double d = lerpFromPoints(points, min, max);
            int delta = (int) Math.round(d);

            if (delta > 0) return "+" + delta + " offers";
            if (delta < 0) return delta + " offers";
            return "0 offers";
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String vitalityEffect(int points) {
        try {
            ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
            if (cfg == null) return null;

            // Config is in HEALTH POINTS (2.0 = 1 heart)
            double hp = lerpFromPoints(points, cfg.vitalityMinHealth, cfg.vitalityMaxHealth);
            double hearts = hp / 2.0;

            return formatSigned1(hearts) + " hearts";
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String agilityEffect(int points) {
        try {
            ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
            if (cfg == null) return null;

            // Config is raw movement speed ADDITIVE delta (not percent)
            double delta = lerpFromPoints(points, cfg.agilityMinSpeed, cfg.agilityMaxSpeed);

            // Show more precision because these are usually small (e.g. 0.03)
            return formatSigned3(delta) + " move speed";
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String strengthEffect(int points) {
        try {
            ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
            if (cfg == null) return null;

            double dmg = lerpFromPoints(points, cfg.strengthMinDamage, cfg.strengthMaxDamage);
            return formatSigned1(dmg) + " damage";
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String armorEffect(int points) {
        try {
            ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
            if (cfg == null) return null;

            double arm = lerpFromPoints(points, cfg.armorMin, cfg.armorMax);
            return formatSigned1(arm) + " armor";
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

    private static String formatSigned1(double v) {
        double r = Math.round(v * 10.0) / 10.0;
        if (Math.abs(r) < 0.05) r = 0.0;
        if (r > 0.0) return "+" + r;
        return String.valueOf(r);
    }

    private static String formatSigned3(double v) {
        double r = Math.round(v * 1000.0) / 1000.0;

        // avoid "-0.000"
        if (Math.abs(r) < 0.0005) r = 0.0;

        if (r > 0.0) return "+" + r;
        return String.valueOf(r);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
