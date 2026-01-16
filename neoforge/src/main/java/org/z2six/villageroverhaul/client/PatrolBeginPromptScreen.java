// PatrolBeginPromptScreen.java
// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/client/PatrolBeginPromptScreen.java
package org.z2six.villageroverhaul.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.patrol.PacketPatrolBegin;
import org.z2six.villageroverhaul.network.patrol.PacketPatrolInteractRequest;

public final class PatrolBeginPromptScreen extends Screen {

    private final Screen parent;
    private final int villagerEntityId;

    private Button useExistingBtn;
    private boolean serverStateReceived = false;

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

        // Use existing route (disabled until server confirms a finalized route exists)
        useExistingBtn = this.addRenderableWidget(Button.builder(Component.literal("Use existing patrol"), b -> {
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

        // Start disabled; will be enabled if server says route exists
        useExistingBtn.active = false;

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

        // Ask server whether a finalized patrol route exists for this villager.
        // Server replies with PacketPatrolOpenGui (we reuse hasPatrolData as "has finalized route").
        try {
            ClientNetwork.sendToServer(new PacketPatrolInteractRequest(villagerEntityId));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] PatrolBeginPromptScreen query failed (soft): {}", t.toString());
        }
    }

    /**
     * Called by ClientUI when PacketPatrolOpenGui arrives while this prompt is open.
     * hasPatrolData is interpreted as: "has finalized patrol route".
     */
    public void acceptServerState(boolean hasFinalizedRoute) {
        try {
            serverStateReceived = true;
            if (useExistingBtn != null) {
                useExistingBtn.active = hasFinalizedRoute;
            }
        } catch (Throwable ignored) {}
    }

    public int getVillagerEntityId() {
        return villagerEntityId;
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
