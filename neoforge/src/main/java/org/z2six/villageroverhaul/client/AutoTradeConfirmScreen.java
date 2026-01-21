// neoforge\src\main\java\org\z2six\villageroverhaul\client\AutoTradeConfirmScreen.java
package org.z2six.villageroverhaul.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.z2six.villageroverhaul.VillagerOverhaul;

/**
 * One-time confirmation for auto-trade, with an optional “Don't ask again” toggle.
 *
 * We deliberately keep this simple and store the decision in a client config file
 * so users can delete it to reset.
 */
public final class AutoTradeConfirmScreen extends Screen {

    private static final int W = 320;
    private static final int H = 150;

    private final MerchantScreen parent;
    private final int absoluteOfferIndex;
    private final String recipeKey;
    private final ItemStack buyA;
    private final ItemStack buyB;
    private final ItemStack sell;

    private boolean dontAskAgain = false;

    public AutoTradeConfirmScreen(
            MerchantScreen parent,
            int absoluteOfferIndex,
            String recipeKey,
            ItemStack buyA,
            ItemStack buyB,
            ItemStack sell
    ) {
        super(Component.literal("Auto-trade"));
        this.parent = parent;
        this.absoluteOfferIndex = absoluteOfferIndex;
        this.recipeKey = recipeKey == null ? "" : recipeKey;
        this.buyA = buyA == null ? ItemStack.EMPTY : buyA.copy();
        this.buyB = buyB == null ? ItemStack.EMPTY : buyB.copy();
        this.sell = sell == null ? ItemStack.EMPTY : sell.copy();
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        int x0 = cx - W / 2;
        int y0 = cy - H / 2;

        // Toggle "Don't ask again"
        Button toggle = Button.builder(toggleLabel(), b -> {
                    dontAskAgain = !dontAskAgain;
                    b.setMessage(toggleLabel());
                })
                .bounds(x0 + 20, y0 + 78, W - 40, 20)
                .build();
        this.addRenderableWidget(toggle);

        // Yes / No
        int bw = 140;
        this.addRenderableWidget(Button.builder(Component.literal("Yes"), b -> choose(true))
                .bounds(cx - bw - 10, y0 + 105, bw, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("No"), b -> choose(false))
                .bounds(cx + 10, y0 + 105, bw, 20).build());
    }

    private Component toggleLabel() {
        String box = dontAskAgain ? "[x] " : "[ ] ";
        return Component.literal(box + "Don't ask again").withStyle(ChatFormatting.GRAY);
    }

    private void choose(boolean allow) {
        try {
            if (dontAskAgain && !recipeKey.isBlank()) {
                AutoTradeConsentStore.rememberDecision(recipeKey, allow);
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            // Return to merchant screen and (if allowed) start auto-trade.
            mc.setScreen(parent);

            if (allow && parent != null) {
                AutoTradeService.start(parent, absoluteOfferIndex);
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] AutoTradeConfirmScreen.choose failed", t);
            closeToParent();
        }
    }

    private void closeToParent() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.setScreen(parent);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onClose() {
        closeToParent();
    }

    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // Avoid NeoForge blurred menu background.
        gg.fill(0, 0, this.width, this.height, 0xC0101010);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        try {
            this.renderBackground(gg, mouseX, mouseY, partialTick);
        } catch (Throwable ignored) {}

        int cx = this.width / 2;
        int cy = this.height / 2;

        int x0 = cx - W / 2;
        int y0 = cy - H / 2;

        // panel
        gg.fill(x0, y0, x0 + W, y0 + H, 0xCC000000);
        gg.fill(x0 + 1, y0 + 1, x0 + W - 1, y0 + H - 1, 0xAA1A1A1A);

        gg.drawCenteredString(this.font, Component.literal("Auto-trade confirmation"), cx, y0 + 14, 0xFFFFFF);
        gg.drawCenteredString(this.font, buildQuestion(), cx, y0 + 40, 0xFFFFFF);

        // Render widgets manually (buttons), WITHOUT re-drawing background.
        try {
            for (Renderable r : this.renderables) {
                try {
                    r.render(gg, mouseX, mouseY, partialTick);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private Component buildQuestion() {
        try {
            String inA = safeName(buyA);
            String inB = safeName(buyB);
            String out = safeName(sell);

            String mid = (buyB == null || buyB.isEmpty()) ? inA : (inA + " + " + inB);
            return Component.literal("Automatically trade all " + mid + " in your inventory for " + out + "?");
        } catch (Throwable t) {
            return Component.literal("Automatically trade this offer repeatedly?");
        }
    }

    private static String safeName(ItemStack s) {
        try {
            if (s == null || s.isEmpty()) return "nothing";
            return s.getHoverName().getString();
        } catch (Throwable t) {
            return "item";
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
