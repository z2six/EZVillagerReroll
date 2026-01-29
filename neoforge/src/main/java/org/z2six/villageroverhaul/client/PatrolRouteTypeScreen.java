// neoforge\src\main\java\org\z2six\villageroverhaul\client\PatrolRouteTypeScreen.java
package org.z2six.villageroverhaul.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.modes.PacketVillagerModeQuery;
import org.z2six.villageroverhaul.network.patrol.PacketPatrolSaveRoute;
import org.z2six.villageroverhaul.network.patrol.PacketPatrolSetRouteType;

public final class PatrolRouteTypeScreen extends Screen {

    private final Screen parent;
    private final int villagerEntityId;
    private EditBox nameBox;

    public PatrolRouteTypeScreen(Screen parent, int villagerEntityId) {
        super(Component.literal("Patrol Route Type"));
        this.parent = parent;
        this.villagerEntityId = villagerEntityId;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        nameBox = new EditBox(this.font, cx - 110, cy - 45, 220, 18, Component.literal("Name"));
        nameBox.setValue("Route");
        nameBox.setMaxLength(32);
        this.addRenderableWidget(nameBox);

        int w = 220;
        int h = 20;

        this.addRenderableWidget(Button.builder(Component.literal("Circular (1→2→3→1)"), b -> {
                    send(PacketPatrolSetRouteType.RouteType.CIRCULAR);
                    toast("Patrol started (circular).", ChatFormatting.YELLOW);
                    closeAll();
                })
                .bounds(cx - w / 2, cy - 20, w, h)
                .build());

        this.addRenderableWidget(Button.builder(Component.literal("Linear (1→2→3→2→1)"), b -> {
                    send(PacketPatrolSetRouteType.RouteType.LINEAR);
                    toast("Patrol started (linear).", ChatFormatting.YELLOW);
                    closeAll();
                })
                .bounds(cx - w / 2, cy + 5, w, h)
                .build());

        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> Minecraft.getInstance().setScreen(parent))
                .bounds(cx - w / 2, cy + 30, w, h)
                .build());
    }

    @Override
    public void renderBackground(net.minecraft.client.gui.GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // Avoid NeoForge blurred menu background.
        gg.fill(0, 0, this.width, this.height, 0xC0101010);
    }

    private void send(PacketPatrolSetRouteType.RouteType type) {
        try {
            String name = nameBox == null ? "" : nameBox.getValue();
            ClientNetwork.sendToServer(new PacketPatrolSaveRoute(villagerEntityId, name, type));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Patrol route select failed", t);
        }
    }

    private void toast(String msg, ChatFormatting fmt) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                mc.player.displayClientMessage(Component.literal(msg).withStyle(fmt), true);
            }
        } catch (Throwable ignored) {}
    }

    private void closeAll() {
        try {
            Minecraft mc = Minecraft.getInstance();
            // We are leaving patrol setup (route type chosen) -> allow normal RMB UI again immediately.
            try { ClientUI.clearPatrolSetupSuppression(villagerEntityId); } catch (Throwable ignored) {}
            try { ClientNetwork.sendToServer(new PacketVillagerModeQuery(villagerEntityId)); } catch (Throwable ignored) {}
            if (mc != null) mc.setScreen(null);
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
