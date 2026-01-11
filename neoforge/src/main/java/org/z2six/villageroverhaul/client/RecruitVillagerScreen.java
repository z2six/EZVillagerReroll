// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/client/RecruitVillagerScreen.java
package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.client.gui.components.Renderable;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.Network;
import org.z2six.villageroverhaul.network.PacketRecruitCostQuery;
import org.z2six.villageroverhaul.network.PacketRecruitVillager;

public final class RecruitVillagerScreen extends Screen {

    private static final int W = 176;
    private static final int H = 94;

    private final int villagerEntityId;
    private int cost;
    private boolean eligible;
    private boolean alreadyRecruited;
    private String serverMessage;

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
        }).bounds(left + 10, top + 60, 75, 20).build();

        cancelBtn = Button.builder(Component.literal("Cancel"), b -> Minecraft.getInstance().setScreen(null))
                .bounds(left + 91, top + 60, 75, 20)
                .build();

        recruitBtn.active = canRecruitNow();

        addRenderableWidget(recruitBtn);
        addRenderableWidget(cancelBtn);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // Draw the vanilla blurred background EXACTLY ONCE.
        // IMPORTANT: We DO NOT call super.render(...) because Screen.render(...) may draw the background again
        // before rendering widgets, which would blur over our custom panel.
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
        gg.drawString(this.font, "Cost:", left + 20, costY, 0xE0E0E0);

        // Emerald icon + amount
        ItemStack emerald = new ItemStack(Items.EMERALD);
        gg.renderItem(emerald, left + 70, costY - 4);
        gg.drawString(this.font, String.valueOf(cost), left + 90, costY, 0xFFFFFF);

        // State message
        if (!serverMessage.isEmpty()) {
            gg.drawCenteredString(this.font, serverMessage, this.width / 2, top + 46, 0xFFCC66);
        } else if (alreadyRecruited) {
            gg.drawCenteredString(this.font, "Already recruited", this.width / 2, top + 46, 0xFFCC66);
        } else if (!eligible) {
            gg.drawCenteredString(this.font, "Not eligible", this.width / 2, top + 46, 0xFF6666);
        } else if (sentRecruit) {
            gg.drawCenteredString(this.font, "Recruiting...", this.width / 2, top + 46, 0x66FF66);
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

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
