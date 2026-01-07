// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/client/BusyVillagerScreen.java
package org.z2six.ezvillagerreroll.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.network.PacketCancelAutoSearch;
import org.z2six.ezvillagerreroll.network.PacketContinueAutoSearch;

import java.util.ArrayList;
import java.util.List;

/**
 * Shown when a villager is "busy" auto-rerolling for requested items.
 */
public final class BusyVillagerScreen extends Screen {

    private final int villagerEntityId;
    private final List<ItemStack> requested;
    private Button btnCancel;
    private Button btnContinue;

    public BusyVillagerScreen(int villagerEntityId, List<ItemStack> requested) {
        super(Component.translatable("ezvr.busy.title"));
        this.villagerEntityId = villagerEntityId;
        this.requested = requested == null ? List.of() : new ArrayList<>(requested);
    }

    @Override
    protected void init() {
        try {
            super.init();

            int cx = this.width / 2;
            int y = this.height - 28;

            btnCancel = Button.builder(Component.translatable("ezvr.busy.cancel"), b -> {
                        try {
                            ClientNetwork.sendToServer(new PacketCancelAutoSearch(villagerEntityId));
                            EZVillagerReroll.LOG().info("[EZVR] BusyVillagerScreen: sent cancel request (villagerEntityId={})", villagerEntityId);
                        } catch (Throwable t) {
                            EZVillagerReroll.LOG().error("[EZVR] BusyVillagerScreen: cancel send failed", t);
                        }
                        tryClose();
                    })
                    .pos(cx - 110, y)
                    .size(100, 20)
                    .build();

            btnContinue = Button.builder(Component.translatable("ezvr.busy.continue"), b -> {
                        try {
                            ClientNetwork.sendToServer(new PacketContinueAutoSearch(villagerEntityId));
                            EZVillagerReroll.LOG().info("[EZVR] BusyVillagerScreen: continue pressed (villagerEntityId={})", villagerEntityId);
                        } catch (Throwable t) {
                            EZVillagerReroll.LOG().error("[EZVR] BusyVillagerScreen: continue send failed", t);
                        }
                        tryClose();
                    })
                    .pos(cx + 10, y)
                    .size(100, 20)
                    .build();

            addRenderableWidget(btnCancel);
            addRenderableWidget(btnContinue);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] BusyVillagerScreen.init failed", t);
        }
    }

    private void tryClose() {
        try {
            if (this.minecraft != null) this.minecraft.setScreen(null);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] BusyVillagerScreen.tryClose failed (soft): {}", t.toString());
        }
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        try {
            this.renderBackground(gg, mouseX, mouseY, partialTick);
            super.render(gg, mouseX, mouseY, partialTick);

            int cx = this.width / 2;
            int y = 20;

            gg.drawCenteredString(this.font, Component.translatable("ezvr.busy.header"), cx, y, 0xFFFFFF);
            y += 18;

            gg.drawCenteredString(this.font, Component.translatable("ezvr.busy.subheader"), cx, y, 0xB0B0B0);
            y += 18;

            int size = 18;
            int pad = 4;
            int cols = Math.max(1, (this.width - 40) / (size + pad));
            int startX = 20;
            int x = startX;
            int col = 0;

            for (ItemStack s : requested) {
                if (s == null || s.isEmpty()) continue;

                gg.renderItem(s, x, y);
                gg.renderItemDecorations(this.font, s, x, y);

                col++;
                x += size + pad;
                if (col >= cols) {
                    col = 0;
                    x = startX;
                    y += size + pad;
                    if (y > this.height - 70) break;
                }
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] BusyVillagerScreen.render failed", t);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
