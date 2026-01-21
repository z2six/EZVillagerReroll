// neoforge/src/main/java/org/z2six/villageroverhaul/client/PatrolRouteRenameScreen.java
package org.z2six.villageroverhaul.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.patrol.PacketPatrolRouteRename;

import java.util.UUID;

public final class PatrolRouteRenameScreen extends Screen {

    private final PatrolRouteListScreen parent;
    private final int villagerEntityId;
    private final UUID routeId;
    private final String initialName;

    private EditBox nameBox;

    public PatrolRouteRenameScreen(PatrolRouteListScreen parent, int villagerEntityId, UUID routeId, String initialName) {
        super(Component.literal("Rename Route"));
        this.parent = parent;
        this.villagerEntityId = villagerEntityId;
        this.routeId = routeId;
        this.initialName = initialName == null ? "" : initialName;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        nameBox = new EditBox(this.font, cx - 110, cy - 20, 220, 18, Component.literal("Name"));
        nameBox.setValue(initialName);
        nameBox.setMaxLength(32);
        this.addRenderableWidget(nameBox);

        this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(cx - 110, cy + 5, 105, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> closeToParent())
                .bounds(cx + 5, cy + 5, 105, 20).build());
    }

    @Override
    public void renderBackground(net.minecraft.client.gui.GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // Avoid NeoForge blurred menu background.
        gg.fill(0, 0, this.width, this.height, 0xC0101010);
    }

    private void save() {
        try {
            String name = nameBox == null ? "" : nameBox.getValue();
            ClientNetwork.sendToServer(new PacketPatrolRouteRename(villagerEntityId, routeId, name));
            if (parent != null) parent.applyRenameLocal(routeId, name);
            toast("Route renamed.", ChatFormatting.YELLOW);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] PatrolRouteRenameScreen.save failed", t);
        }
        closeToParent();
    }

    private void toast(String msg, ChatFormatting fmt) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                mc.player.displayClientMessage(Component.literal(msg).withStyle(fmt), true);
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
