// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/client/AutoSearchPaymentScreen.java
package org.z2six.ezvillagerreroll.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.network.Network;
import org.z2six.ezvillagerreroll.network.PacketDeclineAutoSearchSettlement;
import org.z2six.ezvillagerreroll.network.PacketPayAutoSearchSettlement;

/**
 * Simple settlement/payment UI for auto-search.
 *
 * Server sends PacketOpenAutoSearchPaymentScreen to open this.
 * Client sends:
 * - PacketPayAutoSearchSettlement(villagerEntityId)
 * - PacketDeclineAutoSearchSettlement(villagerEntityId)
 *
 * Server responds with PacketAutoSearchSettlementCleared which closes this screen via ClientNetworkHandlers.
 */
public final class AutoSearchPaymentScreen extends Screen {

    private final int villagerEntityId;
    private final int hourlyCost;
    private final int finalCost;
    private final int elapsedTicks;

    private Button btnPay;
    private Button btnDecline;

    private boolean sentAction = false;

    public AutoSearchPaymentScreen(int villagerEntityId, int hourlyCost, int finalCost, int elapsedTicks) {
        super(Component.translatable("ezvr.auto_search.payment.title"));
        this.villagerEntityId = villagerEntityId;
        this.hourlyCost = Math.max(0, hourlyCost);
        this.finalCost = Math.max(0, finalCost);
        this.elapsedTicks = Math.max(0, elapsedTicks);
    }

    public int getVillagerEntityId() {
        return villagerEntityId;
    }

    @Override
    protected void init() {
        try {
            super.init();

            int cx = this.width / 2;
            int y = this.height / 2 + 30;

            btnPay = Button.builder(Component.literal("Pay").withStyle(ChatFormatting.GREEN), b -> onPay())
                    .pos(cx - 110, y)
                    .size(100, 20)
                    .build();

            btnDecline = Button.builder(Component.literal("Decline").withStyle(ChatFormatting.RED), b -> onDecline())
                    .pos(cx + 10, y)
                    .size(100, 20)
                    .build();

            addRenderableWidget(btnPay);
            addRenderableWidget(btnDecline);

            EZVillagerReroll.LOG().info("[EZVR] AutoSearchPaymentScreen opened: villagerEntityId={} hourlyCost={} finalCost={} elapsedTicks={}",
                    villagerEntityId, hourlyCost, finalCost, elapsedTicks);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] AutoSearchPaymentScreen.init failed", t);
        }
    }

    private void onPay() {
        try {
            if (sentAction) {
                EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen: Pay ignored (already sent action).");
                return;
            }
            if (villagerEntityId < 0) {
                EZVillagerReroll.LOG().warn("[EZVR] AutoSearchPaymentScreen: Pay ignored (villagerEntityId<0).");
                return;
            }

            sentAction = true;
            setButtonsActive(false);

            EZVillagerReroll.LOG().info("[EZVR] AutoSearchPaymentScreen: sending PacketPayAutoSearchSettlement(villagerEntityId={})",
                    villagerEntityId);
            Network.sendToServer(new PacketPayAutoSearchSettlement(villagerEntityId));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] AutoSearchPaymentScreen.onPay failed", t);
            sentAction = false;
            setButtonsActive(true);
        }
    }

    private void onDecline() {
        try {
            if (sentAction) {
                EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen: Decline ignored (already sent action).");
                return;
            }
            if (villagerEntityId < 0) {
                EZVillagerReroll.LOG().warn("[EZVR] AutoSearchPaymentScreen: Decline ignored (villagerEntityId<0).");
                return;
            }

            sentAction = true;
            setButtonsActive(false);

            EZVillagerReroll.LOG().info("[EZVR] AutoSearchPaymentScreen: sending PacketDeclineAutoSearchSettlement(villagerEntityId={})",
                    villagerEntityId);
            Network.sendToServer(new PacketDeclineAutoSearchSettlement(villagerEntityId));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] AutoSearchPaymentScreen.onDecline failed", t);
            sentAction = false;
            setButtonsActive(true);
        }
    }

    private void setButtonsActive(boolean active) {
        try {
            if (btnPay != null) btnPay.active = active;
            if (btnDecline != null) btnDecline.active = active;
        } catch (Throwable ignored) {}
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        try {
            this.renderBackground(gg, mouseX, mouseY, partialTick);
            super.render(gg, mouseX, mouseY, partialTick);

            int cx = this.width / 2;
            int y = this.height / 2 - 40;

            gg.drawCenteredString(this.font,
                    Component.literal("Auto-search complete").withStyle(ChatFormatting.GOLD),
                    cx, y, 0xFFFFFF);

            y += 16;

            // elapsed seconds
            int seconds = elapsedTicks / 20;
            gg.drawCenteredString(this.font,
                    Component.literal("Time: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(seconds + "s").withStyle(ChatFormatting.WHITE)),
                    cx, y, 0xFFFFFF);

            y += 14;

            gg.drawCenteredString(this.font,
                    Component.literal("Hourly cost: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(String.valueOf(hourlyCost)).withStyle(ChatFormatting.WHITE)),
                    cx, y, 0xFFFFFF);

            y += 14;

            gg.drawCenteredString(this.font,
                    Component.literal("Final cost: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(String.valueOf(finalCost)).withStyle(ChatFormatting.WHITE)),
                    cx, y, 0xFFFFFF);

            // Tiny emerald icon next to final cost line (purely cosmetic).
            try {
                ItemStack em = new ItemStack(Items.EMERALD);
                int iconX = cx + this.font.width("Final cost: " + finalCost) / 2 + 6;
                int iconY = (this.height / 2 - 40) + 16 + 14 + 14 + 14 - 2;
                gg.renderItem(em, iconX, iconY);
                gg.renderItemDecorations(this.font, em, iconX, iconY);
            } catch (Throwable ignored) {}

            y += 18;

            if (sentAction) {
                gg.drawCenteredString(this.font,
                        Component.literal("Waiting for server…").withStyle(ChatFormatting.DARK_GRAY),
                        cx, y, 0xFFFFFF);
            } else {
                gg.drawCenteredString(this.font,
                        Component.literal("Pay to keep the offers, or decline to revert.").withStyle(ChatFormatting.DARK_GRAY),
                        cx, y, 0xFFFFFF);
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] AutoSearchPaymentScreen.render failed", t);
        }
    }

    /**
     * ESC should just close the screen locally (does not decline).
     * Settlement remains pending server-side, and RMB can reopen it.
     */
    @Override
    public void onClose() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.setScreen(null);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen.onClose failed (soft): {}", t.toString());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
