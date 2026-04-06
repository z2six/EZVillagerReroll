// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/client/HolsterTweakScreen.java
package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.client.render.VillagerHolsteredLoadoutLayer;

/**
 * Simple live-tweak UI for holstered loadout rendering.
 *
 * Controls:
 * - Mouse wheel: adjust selected param
 * - TAB: cycle selected param
 * - RMB drag: rotate the preview villager
 * - Alt + LMB drag: adjust item rotations (dx -> ry, dy -> rx). Ctrl adds rz.
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
    private static final float PREVIEW_YAW_PER_PIXEL = 1.35f;
    private static final float PREVIEW_YAW_PER_PIXEL_FINE = 0.45f;
    private static final float PREVIEW_PITCH_PER_PIXEL = 0.9f;
    private static final float PREVIEW_PITCH_PER_PIXEL_FINE = 0.3f;

    private enum Param {
        TX, TY, TZ,
        RX, RY, RZ,
        ROLL
    }

    private enum ProfileMode {
        AUTO,
        GENERIC,
        BOW,
        CROSSBOW
    }

    private final Screen parent;
    private int villagerId = -1;
    private Param selected = Param.TX;
    private ProfileMode profileMode = ProfileMode.AUTO;
    private Button profileButton;

    private boolean leftDragging = false;
    private boolean rightDragging = false;
    private double lastDragX = 0.0;
    private double lastDragY = 0.0;

    // Persistent preview orientation for the explicit-angle renderer.
    private float previewYawDeg = 0.0f;
    private float previewPitchDeg = 0.0f;

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

        this.profileButton = this.addRenderableWidget(Button.builder(Component.literal("Profile: Auto"), b -> cycleProfileMode())
                .bounds(left + 230, top, 110, 20)
                .build());
        refreshProfileButton();

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
        refreshProfileButton();
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

    private void cycleProfileMode() {
        ProfileMode[] vals = ProfileMode.values();
        int idx = profileMode.ordinal();
        idx = (idx + 1) % vals.length;
        profileMode = vals[idx];
        refreshProfileButton();
    }

    private void refreshProfileButton() {
        if (profileButton == null) return;
        String label = switch (profileMode) {
            case AUTO -> "Profile: Auto";
            case GENERIC -> "Profile: Generic";
            case BOW -> "Profile: Bow";
            case CROSSBOW -> "Profile: Crossbow";
        };
        profileButton.setMessage(Component.literal(label));
    }

    private VillagerHolsteredLoadoutLayer.WaistProfile getResolvedProfile() {
        return switch (profileMode) {
            case GENERIC -> VillagerHolsteredLoadoutLayer.WaistProfile.DEFAULT;
            case BOW -> VillagerHolsteredLoadoutLayer.WaistProfile.BOW;
            case CROSSBOW -> VillagerHolsteredLoadoutLayer.WaistProfile.CROSSBOW;
            case AUTO -> detectProfileFromTarget();
        };
    }

    private VillagerHolsteredLoadoutLayer.WaistProfile detectProfileFromTarget() {
        try {
            LivingEntity le = resolveEntity();
            if (le instanceof VillagerOverhaulRenderAccess acc) {
                ItemStack stack = acc.ezvr$getCombatLoadoutMain();
                return VillagerHolsteredLoadoutLayer.ezvr$getWaistProfileForStack(stack);
            }
        } catch (Throwable ignored) {}
        return VillagerHolsteredLoadoutLayer.WaistProfile.DEFAULT;
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
                VillagerHolsteredLoadoutLayer.ezvr$resetWaistTweak(getResolvedProfile());
                previewYawDeg = 0.0f;
                previewPitchDeg = 0.0f;
                return true;
            }

            // X/Y/Z set roll axis (post-transform)
            if (keyCode == 88) { // GLFW_KEY_X
                VillagerHolsteredLoadoutLayer.ezvr$setWaistRollAxis(getResolvedProfile(), "x");
                return true;
            }
            if (keyCode == 89) { // GLFW_KEY_Y
                VillagerHolsteredLoadoutLayer.ezvr$setWaistRollAxis(getResolvedProfile(), "y");
                return true;
            }
            if (keyCode == 90) { // GLFW_KEY_Z
                VillagerHolsteredLoadoutLayer.ezvr$setWaistRollAxis(getResolvedProfile(), "z");
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
        var profile = getResolvedProfile();

        float posStep = fine ? STEP_POS_FINE : STEP_POS;
        float degStep = fine ? STEP_DEG_FINE : STEP_DEG;

        switch (selected) {
            case TX -> VillagerHolsteredLoadoutLayer.ezvr$setWaistTweak(profile, "tx", dir * posStep, true);
            case TY -> VillagerHolsteredLoadoutLayer.ezvr$setWaistTweak(profile, "ty", dir * posStep, true);
            case TZ -> VillagerHolsteredLoadoutLayer.ezvr$setWaistTweak(profile, "tz", dir * posStep, true);
            case RX -> VillagerHolsteredLoadoutLayer.ezvr$setWaistTweak(profile, "rx", dir * degStep, true);
            case RY -> VillagerHolsteredLoadoutLayer.ezvr$setWaistTweak(profile, "ry", dir * degStep, true);
            case RZ -> VillagerHolsteredLoadoutLayer.ezvr$setWaistTweak(profile, "rz", dir * degStep, true);
            case ROLL -> VillagerHolsteredLoadoutLayer.ezvr$setWaistTweak(profile, "roll", dir * degStep, true);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        try {
            if (isInsideEntityBox(mouseX, mouseY) && button == 0) {
                leftDragging = true;
                lastDragX = mouseX;
                lastDragY = mouseY;
                return true;
            }
            if (isInsideEntityBox(mouseX, mouseY) && button == 1) {
                rightDragging = true;
                lastDragX = mouseX;
                lastDragY = mouseY;
                return true;
            }
        } catch (Throwable ignored) {}
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        try {
            if (button == 0) {
                leftDragging = false;
            }
            if (button == 1) {
                rightDragging = false;
            }
        } catch (Throwable ignored) {}
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        try {
            if (button == 0 && leftDragging && Screen.hasAltDown()) {
                var profile = getResolvedProfile();
                double dx = mouseX - lastDragX;
                double dy = mouseY - lastDragY;
                lastDragX = mouseX;
                lastDragY = mouseY;

                boolean fine = Screen.hasShiftDown();
                float degPerPixel = fine ? 0.15f : 0.45f;

                VillagerHolsteredLoadoutLayer.ezvr$setWaistTweak(profile, "ry", (float) dx * degPerPixel, true);
                VillagerHolsteredLoadoutLayer.ezvr$setWaistTweak(profile, "rx", (float) dy * degPerPixel, true);

                if (Screen.hasControlDown()) {
                    VillagerHolsteredLoadoutLayer.ezvr$setWaistTweak(profile, "rz", (float) dx * degPerPixel, true);
                }
                return true;
            } else if (button == 1 && rightDragging) {
                double dx = mouseX - lastDragX;
                double dy = mouseY - lastDragY;
                lastDragX = mouseX;
                lastDragY = mouseY;

                boolean fine = Screen.hasShiftDown();
                float yawPerPixel = fine ? PREVIEW_YAW_PER_PIXEL_FINE : PREVIEW_YAW_PER_PIXEL;
                float pitchPerPixel = fine ? PREVIEW_PITCH_PER_PIXEL_FINE : PREVIEW_PITCH_PER_PIXEL;

                previewYawDeg = Mth.wrapDegrees(previewYawDeg + (float) dx * yawPerPixel);
                previewPitchDeg = Mth.clamp(previewPitchDeg + (float) dy * pitchPerPixel, -85.0f, 85.0f);
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
            var resolved = getResolvedProfile();
            VillagerOverhaul.LOG().info("[VillagerOverhaul] HolsterTweak profile mode={} resolved={}",
                    profileMode, VillagerHolsteredLoadoutLayer.ezvr$waistProfileLabel(resolved));
            VillagerOverhaul.LOG().info("[VillagerOverhaul] HolsterTweak generic: {}",
                    VillagerHolsteredLoadoutLayer.ezvr$waistTweakString(VillagerHolsteredLoadoutLayer.WaistProfile.DEFAULT));
            VillagerOverhaul.LOG().info("[VillagerOverhaul] HolsterTweak bow: {}",
                    VillagerHolsteredLoadoutLayer.ezvr$waistTweakString(VillagerHolsteredLoadoutLayer.WaistProfile.BOW));
            VillagerOverhaul.LOG().info("[VillagerOverhaul] HolsterTweak crossbow: {}",
                    VillagerHolsteredLoadoutLayer.ezvr$waistTweakString(VillagerHolsteredLoadoutLayer.WaistProfile.CROSSBOW));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] HolsterTweak dump failed: {}", t.toString());
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
            InventoryScreen.renderEntityInInventoryFollowsAngle(
                    gg,
                    boxLeft + 6, boxTop + 6,
                    boxRight - 6, boxBottom - 6,
                    52,
                    0.0f,
                    previewYawDeg / 20.0f,
                    previewPitchDeg / 20.0f,
                    le
            );
        }

        int infoY = boxBottom + 12;
        var resolvedProfile = getResolvedProfile();
        String profileLabel = VillagerHolsteredLoadoutLayer.ezvr$waistProfileLabel(resolvedProfile);
        String profileModeLabel = profileMode == ProfileMode.AUTO ? "Auto" : profileLabel;
        gg.drawCenteredString(this.font, "Selected: " + selected.name() + " | Profile: " + profileModeLabel + (profileMode == ProfileMode.AUTO ? " -> " + profileLabel : ""), this.width / 2, infoY, 0xFFFFFF);
        gg.drawCenteredString(this.font, VillagerHolsteredLoadoutLayer.ezvr$waistTweakString(resolvedProfile), this.width / 2, infoY + 14, 0xC0C0C0);
        gg.drawCenteredString(this.font, "Wheel: adjust selected | TAB: next | Shift: fine", this.width / 2, infoY + 32, 0xA0A0A0);
        gg.drawCenteredString(this.font, "RMB drag: rotate villager | Alt+LMB drag: rotate item | Ctrl adds RZ", this.width / 2, infoY + 46, 0xA0A0A0);
        gg.drawCenteredString(this.font, "X/Y/Z: roll axis | R: reset profile | Enter: dump all profiles", this.width / 2, infoY + 60, 0xA0A0A0);
        if (le == null) {
            gg.drawCenteredString(this.font, "No villager found (use Retarget).", this.width / 2, infoY + 78, 0xFF6060);
        }

        super.render(gg, mouseX, mouseY, partialTick);
    }
}
