// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/client/HolsterTweakScreen.java
package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.client.render.VillagerHolsteredLoadoutLayer;

/**
 * Simple live-tweak UI for holstered loadout rendering.
 *
 * Controls:
 * - Mouse wheel: adjust selected param
 * - TAB: cycle selected param
 * - Alt + drag: adjust rotations (dx -> ry, dy -> rx). Ctrl adds rz.
 * - Shift: smaller step (wheel + drag)
 * - X/Y/Z: set roll axis (post-transform)
 * - R: reset
 * - Dump button: logs all values at INFO
 */
public final class HolsterTweakScreen extends Screen {

    private static final int BOX_W = 260;
    private static final int BOX_H = 300;

    private static final float STEP_POS = 0.01f;
    private static final float STEP_POS_FINE = 0.0025f;
    private static final float STEP_DEG = 2.0f;
    private static final float STEP_DEG_FINE = 0.5f;

    private enum Param {
        TX, TY, TZ,
        RX, RY, RZ,
        ROLL
    }

    private final Screen parent;
    private int villagerId = -1;
    private Param selected = Param.TX;

    private boolean dragging = false;
    private double lastDragX = 0.0;
    private double lastDragY = 0.0;

    // Synthetic mouse offsets used by InventoryScreen.renderEntityInInventoryFollowsMouse
    // so the preview doesn't constantly follow the real cursor.
    private float previewMouseXOff = 0.0f;
    private float previewMouseYOff = 0.0f;

    public HolsterTweakScreen(Screen parent) {
        super(Component.literal("Holster Tweak"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        int left = 10;
        int top = 10;

        this.addRenderableWidget(Button.builder(Component.literal("Retarget"), b -> retarget())
                .bounds(left, top, 80, 20)
                .build());

        this.addRenderableWidget(Button.builder(Component.literal("Dump"), b -> dumpToLog())
                .bounds(left + 90, top, 60, 20)
                .build());

        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null) mc.setScreen(parent);
                })
                .bounds(left + 160, top, 60, 20)
                .build());

        retarget();
    }

    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // Avoid NeoForge blurred menu background.
        gg.fill(0, 0, this.width, this.height, 0xC0101010);
    }

    private void retarget() {
        try {
            Villager v = findNearbyVillager();
            this.villagerId = (v == null) ? -1 : v.getId();
            if (v == null) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] HolsterTweakScreen: no villager found to preview.");
            } else {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] HolsterTweakScreen: targeting villager id={} uuid={}", v.getId(), v.getUUID());
            }
        } catch (Throwable ignored) {
            this.villagerId = -1;
        }
    }

    private static Villager findNearbyVillager() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) return null;
            if (mc.player == null) return null;

            // 1) Crosshair target
            HitResult hr = mc.hitResult;
            if (hr instanceof EntityHitResult ehr) {
                if (ehr.getEntity() instanceof Villager v) return v;
            }

            // 2) Nearest within radius
            final double radius = 8.0;
            AABB box = mc.player.getBoundingBox().inflate(radius);
            Villager nearest = null;
            double best = Double.MAX_VALUE;

            for (Villager v : mc.player.level().getEntitiesOfClass(Villager.class, box)) {
                if (v == null) continue;
                double d2 = v.distanceToSqr(mc.player);
                if (d2 < best) {
                    best = d2;
                    nearest = v;
                }
            }
            return nearest;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private LivingEntity resolveEntity() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) return null;
            if (villagerId < 0) return null;
            return (mc.level.getEntity(villagerId) instanceof LivingEntity le) ? le : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        try {
            // TAB cycles selected param
            if (keyCode == 258) { // GLFW_KEY_TAB
                cycleSelected();
                return true;
            }

            // R resets
            if (keyCode == 82) { // GLFW_KEY_R
                VillagerHolsteredLoadoutLayer.ezvr$resetWaistTweak();
                previewMouseXOff = 0.0f;
                previewMouseYOff = 0.0f;
                return true;
            }

            // X/Y/Z set roll axis (post-transform)
            if (keyCode == 88) { // GLFW_KEY_X
                VillagerHolsteredLoadoutLayer.ezvr$setWaistRollAxis("x");
                return true;
            }
            if (keyCode == 89) { // GLFW_KEY_Y
                VillagerHolsteredLoadoutLayer.ezvr$setWaistRollAxis("y");
                return true;
            }
            if (keyCode == 90) { // GLFW_KEY_Z
                VillagerHolsteredLoadoutLayer.ezvr$setWaistRollAxis("z");
                return true;
            }

            // ENTER dumps
            if (keyCode == 257) { // GLFW_KEY_ENTER
                dumpToLog();
                return true;
            }
        } catch (Throwable ignored) {}

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void cycleSelected() {
        Param[] vals = Param.values();
        int idx = selected.ordinal();
        idx = (idx + 1) % vals.length;
        selected = vals[idx];
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        try {
            float dir = (deltaY > 0) ? 1.0f : (deltaY < 0 ? -1.0f : 0.0f);
            if (dir != 0.0f) {
                applyDeltaWheel(dir);
                return true;
            }
        } catch (Throwable ignored) {}
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    private void applyDeltaWheel(float dir) {
        boolean fine = Screen.hasShiftDown();

        float posStep = fine ? STEP_POS_FINE : STEP_POS;
        float degStep = fine ? STEP_DEG_FINE : STEP_DEG;

        switch (selected) {
            case TX -> VillagerHolsteredLoadoutLayer.WAIST_TX += dir * posStep;
            case TY -> VillagerHolsteredLoadoutLayer.WAIST_TY += dir * posStep;
            case TZ -> VillagerHolsteredLoadoutLayer.WAIST_TZ += dir * posStep;
            case RX -> VillagerHolsteredLoadoutLayer.WAIST_RX_DEG += dir * degStep;
            case RY -> VillagerHolsteredLoadoutLayer.WAIST_RY_DEG += dir * degStep;
            case RZ -> VillagerHolsteredLoadoutLayer.WAIST_RZ_DEG += dir * degStep;
            case ROLL -> VillagerHolsteredLoadoutLayer.WAIST_ROLL_DEG += dir * degStep;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        try {
            if (button == 0 && isInsideEntityBox(mouseX, mouseY)) {
                dragging = true;
                lastDragX = mouseX;
                lastDragY = mouseY;
            }
        } catch (Throwable ignored) {}
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        try {
            if (button == 0) {
                dragging = false;
            }
        } catch (Throwable ignored) {}
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        try {
            if (button == 0 && dragging && Screen.hasAltDown()) {
                double dx = mouseX - lastDragX;
                double dy = mouseY - lastDragY;
                lastDragX = mouseX;
                lastDragY = mouseY;

                boolean fine = Screen.hasShiftDown();
                float degPerPixel = fine ? 0.15f : 0.45f;

                VillagerHolsteredLoadoutLayer.WAIST_RY_DEG += (float) dx * degPerPixel;
                VillagerHolsteredLoadoutLayer.WAIST_RX_DEG += (float) dy * degPerPixel;

                if (Screen.hasControlDown()) {
                    VillagerHolsteredLoadoutLayer.WAIST_RZ_DEG += (float) dx * degPerPixel;
                }
                return true;
            } else if (button == 0 && dragging && isInsideEntityBox(mouseX, mouseY)) {
                // Rotate the preview villager using synthetic "mouse" offsets.
                double dx = mouseX - lastDragX;
                double dy = mouseY - lastDragY;
                lastDragX = mouseX;
                lastDragY = mouseY;

                boolean fine = Screen.hasShiftDown();
                float pxPerPixel = fine ? 0.35f : 0.9f;

                previewMouseXOff += (float) dx * pxPerPixel;
                previewMouseYOff += (float) dy * pxPerPixel;

                // Keep it sane.
                previewMouseXOff = clamp(previewMouseXOff, -240.0f, 240.0f);
                previewMouseYOff = clamp(previewMouseYOff, -180.0f, 180.0f);
                return true;
            }
        } catch (Throwable ignored) {}
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    private boolean isInsideEntityBox(double mouseX, double mouseY) {
        int boxLeft = (this.width - BOX_W) / 2;
        int boxTop = 50;
        int boxRight = boxLeft + BOX_W;
        int boxBottom = boxTop + BOX_H;
        return mouseX >= boxLeft && mouseX <= boxRight && mouseY >= boxTop && mouseY <= boxBottom;
    }

    private static float clamp(float v, float min, float max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private void dumpToLog() {
        try {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] HolsterTweak dump: {}", VillagerHolsteredLoadoutLayer.ezvr$waistTweakString());
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] Hardcode waist: "
                            + "WAIST_TX=%.4ff WAIST_TY=%.4ff WAIST_TZ=%.4ff "
                            + "WAIST_RX_DEG=%.2ff WAIST_RY_DEG=%.2ff WAIST_RZ_DEG=%.2ff "
                            + "WAIST_SPIN_DEG=%.2ff WAIST_SPIN_AXIS=%s "
                            + "WAIST_ROLL_DEG=%.2ff WAIST_ROLL_AXIS=%s",
                    VillagerHolsteredLoadoutLayer.WAIST_TX,
                    VillagerHolsteredLoadoutLayer.WAIST_TY,
                    VillagerHolsteredLoadoutLayer.WAIST_TZ,
                    VillagerHolsteredLoadoutLayer.WAIST_RX_DEG,
                    VillagerHolsteredLoadoutLayer.WAIST_RY_DEG,
                    VillagerHolsteredLoadoutLayer.WAIST_RZ_DEG,
                    VillagerHolsteredLoadoutLayer.WAIST_SPIN_DEG,
                    String.valueOf(VillagerHolsteredLoadoutLayer.WAIST_SPIN_AXIS),
                    VillagerHolsteredLoadoutLayer.WAIST_ROLL_DEG,
                    String.valueOf(VillagerHolsteredLoadoutLayer.WAIST_ROLL_AXIS)
            );
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] HolsterTweak dump failed: {}", t.toString());
        }
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gg, mouseX, mouseY, partialTick);

        int boxLeft = (this.width - BOX_W) / 2;
        int boxTop = 50;
        int boxRight = boxLeft + BOX_W;
        int boxBottom = boxTop + BOX_H;

        gg.fill(boxLeft, boxTop, boxRight, boxBottom, 0xAA101010);
        gg.fill(boxLeft, boxTop, boxRight, boxTop + 1, 0xFF606060);
        gg.fill(boxLeft, boxBottom - 1, boxRight, boxBottom, 0xFF606060);
        gg.fill(boxLeft, boxTop, boxLeft + 1, boxBottom, 0xFF606060);
        gg.fill(boxRight - 1, boxTop, boxRight, boxBottom, 0xFF606060);

        LivingEntity le = resolveEntity();
        if (le != null) {
            float cx = (boxLeft + boxRight) * 0.5f;
            float cy = (boxTop + boxBottom) * 0.5f;
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    gg,
                    boxLeft + 6, boxTop + 6,
                    boxRight - 6, boxBottom - 6,
                    52,
                    0.0f,
                    cx + previewMouseXOff, cy + previewMouseYOff,
                    le
            );
        }

        int infoY = boxBottom + 12;
        gg.drawCenteredString(this.font, "Selected: " + selected.name(), this.width / 2, infoY, 0xFFFFFF);
        gg.drawCenteredString(this.font, VillagerHolsteredLoadoutLayer.ezvr$waistTweakString(), this.width / 2, infoY + 14, 0xC0C0C0);
        gg.drawCenteredString(this.font, "Wheel: adjust selected | TAB: next | Shift: fine", this.width / 2, infoY + 32, 0xA0A0A0);
        gg.drawCenteredString(this.font, "Drag (in box): turn villager | Alt+drag: rotate item | Ctrl adds RZ", this.width / 2, infoY + 46, 0xA0A0A0);
        gg.drawCenteredString(this.font, "X/Y/Z: roll axis | R: reset | Enter: dump", this.width / 2, infoY + 60, 0xA0A0A0);
        if (le == null) {
            gg.drawCenteredString(this.font, "No villager found (use Retarget).", this.width / 2, infoY + 78, 0xFF6060);
        }

        super.render(gg, mouseX, mouseY, partialTick);
    }
}
