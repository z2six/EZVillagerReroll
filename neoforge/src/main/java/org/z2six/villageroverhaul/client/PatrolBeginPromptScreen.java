// PatrolBeginPromptScreen.java
// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/client/PatrolBeginPromptScreen.java
package org.z2six.villageroverhaul.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.PacketPatrolBegin;

public final class PatrolBeginPromptScreen extends Screen {

    private final Screen parent;
    private final int villagerEntityId;

    public PatrolBeginPromptScreen(Screen parent, int villagerEntityId) {
        super(Component.literal("Patrol"));
        this.parent = parent;
        this.villagerEntityId = villagerEntityId;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        int w = 220;
        int h = 20;

        // Use existing route
        this.addRenderableWidget(Button.builder(Component.literal("Use existing patrol"), b -> {
                    try {
                        ClientNetwork.sendToServer(new PacketPatrolBegin(villagerEntityId, false));
                        notifyStarted(false);
                    } catch (Throwable t) {
                        VillagerOverhaul.LOG().error("[VillagerOverhaul] Use existing patrol click failed", t);
                    }
                    closeToParent();
                })
                .bounds(cx - w / 2, cy - 30, w, h)
                .build());

        // Create new
        this.addRenderableWidget(Button.builder(Component.literal("Create new patrol"), b -> {
                    try {
                        ClientNetwork.sendToServer(new PacketPatrolBegin(villagerEntityId, true));
                        notifyStarted(true);
                    } catch (Throwable t) {
                        VillagerOverhaul.LOG().error("[VillagerOverhaul] Create new patrol click failed", t);
                    }
                    closeToParent();
                })
                .bounds(cx - w / 2, cy - 5, w, h)
                .build());

        // Cancel
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> closeToParent())
                .bounds(cx - w / 2, cy + 20, w, h)
                .build());
    }

    private void notifyStarted(boolean isNew) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) return;

            if (isNew) {
                mc.player.displayClientMessage(
                        Component.literal("Patrol setup started. Right-click the villager to add waypoints.")
                                .withStyle(ChatFormatting.YELLOW),
                        true
                );
            } else {
                mc.player.displayClientMessage(
                        Component.literal("Patrol requested. If a route exists, villager will start patrolling.")
                                .withStyle(ChatFormatting.YELLOW),
                        true
                );
            }
        } catch (Throwable ignored) {}
    }

    private void closeToParent() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.setScreen(parent);
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
