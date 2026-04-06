package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import java.util.List;
import org.z2six.villageroverhaul.network.familytree.ClientVillagerFamilyTreeCache;
import org.z2six.villageroverhaul.network.familytree.PacketVillagerFamilyTreeData;

public final class FullscreenFamilyTreeScreen extends Screen {
    private final Screen parent;
    private final int villagerEntityId;

    private float zoom = 1.0F;
    private double panX = 0.0D;
    private double panY = 0.0D;
    private boolean dragging = false;

    private int viewportX;
    private int viewportY;
    private int viewportW;
    private int viewportH;

    public FullscreenFamilyTreeScreen(Screen parent, int villagerEntityId) {
        super(Component.literal("Family Tree"));
        this.parent = parent;
        this.villagerEntityId = villagerEntityId;
    }

    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    protected void init() {
        super.init();
        this.addRenderableWidget(
                Button.builder(Component.literal("Back"), b -> onClose())
                        .pos(this.width - 70, 12)
                        .size(58, 18)
                        .build()
        );
        resetViewport();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && insideViewport(mouseX, mouseY)) {
            dragging = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && dragging) {
            panX += dragX;
            panY += dragY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            dragging = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0.0D || !insideViewport(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        applyZoom(mouseX, mouseY, scrollY);
        return true;
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        gg.fill(0, 0, this.width, this.height, 0xCC0B0B0B);

        Font font = Minecraft.getInstance().font;
        gg.drawString(font, "Family Tree", 12, 16, 0xFFFFFFFF, true);

        PacketVillagerFamilyTreeData data = ClientVillagerFamilyTreeCache.get(villagerEntityId);
        List<Component> tooltip = FamilyTreeGraphRenderer.render(gg, font, data, viewportX, viewportY, viewportW, viewportH, zoom, panX, panY, mouseX, mouseY);
        gg.drawString(font, "Drag to pan  |  Scroll to zoom", viewportX, viewportY - 12, 0xFFBFBFBF, false);

        super.render(gg, mouseX, mouseY, partialTick);

        if (tooltip != null) {
            gg.renderComponentTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    @Override
    public void resize(Minecraft mc, int width, int height) {
        super.resize(mc, width, height);
        resetViewport();
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void resetViewport() {
        int pad = 12;
        viewportX = pad;
        viewportY = 40;
        viewportW = Math.max(40, this.width - pad * 2);
        viewportH = Math.max(40, this.height - viewportY - pad);
    }

    private boolean insideViewport(double mouseX, double mouseY) {
        return mouseX >= viewportX && mouseX <= viewportX + viewportW && mouseY >= viewportY && mouseY <= viewportY + viewportH;
    }

    private void applyZoom(double mouseX, double mouseY, double scrollY) {
        float oldZoom = zoom;
        zoom = Mth.clamp(zoom + (float) Math.signum(scrollY) * 0.1F, 0.4F, 3.0F);
        if (Math.abs(zoom - oldZoom) < 0.0001F) {
            return;
        }
        double anchorX = mouseX - (viewportX + viewportW / 2.0D + panX);
        double anchorY = mouseY - (viewportY + viewportH / 2.0D + panY);
        double scale = zoom / oldZoom;
        panX -= anchorX * (scale - 1.0D);
        panY -= anchorY * (scale - 1.0D);
    }
}
