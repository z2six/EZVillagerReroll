// neoforge/src/main/java/org/z2six/villageroverhaul/client/render/VillagerHolsteredLoadoutLayer.java
package org.z2six.villageroverhaul.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.UseAnim;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.render.VillagerRenderFlags;

import java.lang.reflect.Method;

/**
 * Renders the villager's combat loadout cosmetically while the villager is NOT actively holding it.
 *
 * Desired look:
 * - Main loadout item (e.g. sword) on the waist.
 * - Offhand loadout item (e.g. shield) on the back.
 *
 * Source of truth for the item stacks is server -> client SynchedEntityData (see {code VillagerRenderStateMixin}).
 */
public final class VillagerHolsteredLoadoutLayer extends RenderLayer<Villager, VillagerModel<Villager>> {

    public enum WaistProfile {
        DEFAULT,
        BOW,
        CROSSBOW
    }

    // =========================================================================================
    // TWEAKS (alignment)
    // ModelPart#translateAndRotate uses 1/16 scaling, so small numbers matter.
    // =========================================================================================

    private static final boolean ENABLE_WAIST = true;
    private static final float WAIST_TX_DEFAULT = 0.35f;
    private static final float WAIST_TY_DEFAULT = 0.65f;
    private static final float WAIST_TZ_DEFAULT = -0.15f;
    private static final float WAIST_RX_DEG_DEFAULT = -135f;
    private static final float WAIST_RY_DEG_DEFAULT = 180f;
    private static final float WAIST_RZ_DEG_DEFAULT = 180f;
    private static final float WAIST_SCALE = 1.00f;

    public static volatile float WAIST_TX = WAIST_TX_DEFAULT;
    public static volatile float WAIST_TY = WAIST_TY_DEFAULT;
    public static volatile float WAIST_TZ = WAIST_TZ_DEFAULT;
    public static volatile float WAIST_RX_DEG = WAIST_RX_DEG_DEFAULT;
    public static volatile float WAIST_RY_DEG = WAIST_RY_DEG_DEFAULT;
    public static volatile float WAIST_RZ_DEG = WAIST_RZ_DEG_DEFAULT;

    private static final float WAIST_SPIN_DEG_DEFAULT = 0.0f;
    public static volatile float WAIST_SPIN_DEG = WAIST_SPIN_DEG_DEFAULT;

    public enum SpinAxis { X, Y, Z }
    public static volatile SpinAxis WAIST_SPIN_AXIS = SpinAxis.Z;

    private static final float WAIST_ROLL_DEG_DEFAULT = 0.0f;
    public static volatile float WAIST_ROLL_DEG = WAIST_ROLL_DEG_DEFAULT;
    public static volatile SpinAxis WAIST_ROLL_AXIS = SpinAxis.Z;

    private static final float WAIST_BOW_TX_DEFAULT = 0.5499998f;
    private static final float WAIST_BOW_TY_DEFAULT = 0.6399997f;
    private static final float WAIST_BOW_TZ_DEFAULT = 0.41999996f;
    private static final float WAIST_BOW_RX_DEG_DEFAULT = 183.0f;
    private static final float WAIST_BOW_RY_DEG_DEFAULT = 272.0f;
    private static final float WAIST_BOW_RZ_DEG_DEFAULT = 172.0f;
    private static final float WAIST_BOW_SCALE = 1.00f;
    private static final float WAIST_BOW_SPIN_DEG_DEFAULT = 0.0f;
    private static final float WAIST_BOW_ROLL_DEG_DEFAULT = -40.0f;
    public static volatile float WAIST_BOW_TX = WAIST_BOW_TX_DEFAULT;
    public static volatile float WAIST_BOW_TY = WAIST_BOW_TY_DEFAULT;
    public static volatile float WAIST_BOW_TZ = WAIST_BOW_TZ_DEFAULT;
    public static volatile float WAIST_BOW_RX_DEG = WAIST_BOW_RX_DEG_DEFAULT;
    public static volatile float WAIST_BOW_RY_DEG = WAIST_BOW_RY_DEG_DEFAULT;
    public static volatile float WAIST_BOW_RZ_DEG = WAIST_BOW_RZ_DEG_DEFAULT;
    public static volatile float WAIST_BOW_SPIN_DEG = WAIST_BOW_SPIN_DEG_DEFAULT;
    public static volatile SpinAxis WAIST_BOW_SPIN_AXIS = SpinAxis.Z;
    public static volatile float WAIST_BOW_ROLL_DEG = WAIST_BOW_ROLL_DEG_DEFAULT;
    public static volatile SpinAxis WAIST_BOW_ROLL_AXIS = SpinAxis.Z;

    private static final float WAIST_CROSSBOW_TX_DEFAULT = -0.05f;
    private static final float WAIST_CROSSBOW_TY_DEFAULT = 0.25f;
    private static final float WAIST_CROSSBOW_TZ_DEFAULT = 0.37f;
    private static final float WAIST_CROSSBOW_RX_DEG_DEFAULT = 83.0f;
    private static final float WAIST_CROSSBOW_RY_DEG_DEFAULT = 300.0f;
    private static final float WAIST_CROSSBOW_RZ_DEG_DEFAULT = 172.0f;
    private static final float WAIST_CROSSBOW_SCALE = 1.00f;
    private static final float WAIST_CROSSBOW_SPIN_DEG_DEFAULT = 0.0f;
    private static final float WAIST_CROSSBOW_ROLL_DEG_DEFAULT = 0.0f;
    public static volatile float WAIST_CROSSBOW_TX = WAIST_CROSSBOW_TX_DEFAULT;
    public static volatile float WAIST_CROSSBOW_TY = WAIST_CROSSBOW_TY_DEFAULT;
    public static volatile float WAIST_CROSSBOW_TZ = WAIST_CROSSBOW_TZ_DEFAULT;
    public static volatile float WAIST_CROSSBOW_RX_DEG = WAIST_CROSSBOW_RX_DEG_DEFAULT;
    public static volatile float WAIST_CROSSBOW_RY_DEG = WAIST_CROSSBOW_RY_DEG_DEFAULT;
    public static volatile float WAIST_CROSSBOW_RZ_DEG = WAIST_CROSSBOW_RZ_DEG_DEFAULT;
    public static volatile float WAIST_CROSSBOW_SPIN_DEG = WAIST_CROSSBOW_SPIN_DEG_DEFAULT;
    public static volatile SpinAxis WAIST_CROSSBOW_SPIN_AXIS = SpinAxis.Z;
    public static volatile float WAIST_CROSSBOW_ROLL_DEG = WAIST_CROSSBOW_ROLL_DEG_DEFAULT;
    public static volatile SpinAxis WAIST_CROSSBOW_ROLL_AXIS = SpinAxis.Z;

    private static final boolean ENABLE_BACK = true;
    private static final float BACK_TX = 0.20f; // left/right back perspective
    private static final float BACK_TY = 0.20f; // up/down from back perspective
    private static final float BACK_TZ = 0.15f; // inwards/outwards back perspective
    private static final float BACK_RX_DEG = 180.0f;
    private static final float BACK_RY_DEG = 270.0f;
    private static final float BACK_RZ_DEG = 0.0f;
    private static final float BACK_SCALE = 0.75f;

    // =========================================================================================

    private boolean ezvr$initLogged = false;
    private static Method ROOT_METHOD = null;

    public static void ezvr$resetWaistTweak() {
        ezvr$resetWaistTweak(WaistProfile.DEFAULT);
    }

    public static boolean ezvr$setWaistTweak(String keyRaw, float value, boolean additive) {
        return ezvr$setWaistTweak(WaistProfile.DEFAULT, keyRaw, value, additive);
    }

    public static void ezvr$resetWaistTweak(WaistProfile profile) {
        try {
            switch (profile == null ? WaistProfile.DEFAULT : profile) {
                case DEFAULT -> {
                    WAIST_TX = WAIST_TX_DEFAULT;
                    WAIST_TY = WAIST_TY_DEFAULT;
                    WAIST_TZ = WAIST_TZ_DEFAULT;
                    WAIST_RX_DEG = WAIST_RX_DEG_DEFAULT;
                    WAIST_RY_DEG = WAIST_RY_DEG_DEFAULT;
                    WAIST_RZ_DEG = WAIST_RZ_DEG_DEFAULT;
                    WAIST_SPIN_DEG = WAIST_SPIN_DEG_DEFAULT;
                    WAIST_SPIN_AXIS = SpinAxis.Z;
                    WAIST_ROLL_DEG = WAIST_ROLL_DEG_DEFAULT;
                    WAIST_ROLL_AXIS = SpinAxis.Z;
                }
                case BOW -> {
                    WAIST_BOW_TX = WAIST_BOW_TX_DEFAULT;
                    WAIST_BOW_TY = WAIST_BOW_TY_DEFAULT;
                    WAIST_BOW_TZ = WAIST_BOW_TZ_DEFAULT;
                    WAIST_BOW_RX_DEG = WAIST_BOW_RX_DEG_DEFAULT;
                    WAIST_BOW_RY_DEG = WAIST_BOW_RY_DEG_DEFAULT;
                    WAIST_BOW_RZ_DEG = WAIST_BOW_RZ_DEG_DEFAULT;
                    WAIST_BOW_SPIN_DEG = WAIST_BOW_SPIN_DEG_DEFAULT;
                    WAIST_BOW_SPIN_AXIS = SpinAxis.Z;
                    WAIST_BOW_ROLL_DEG = WAIST_BOW_ROLL_DEG_DEFAULT;
                    WAIST_BOW_ROLL_AXIS = SpinAxis.Z;
                }
                case CROSSBOW -> {
                    WAIST_CROSSBOW_TX = WAIST_CROSSBOW_TX_DEFAULT;
                    WAIST_CROSSBOW_TY = WAIST_CROSSBOW_TY_DEFAULT;
                    WAIST_CROSSBOW_TZ = WAIST_CROSSBOW_TZ_DEFAULT;
                    WAIST_CROSSBOW_RX_DEG = WAIST_CROSSBOW_RX_DEG_DEFAULT;
                    WAIST_CROSSBOW_RY_DEG = WAIST_CROSSBOW_RY_DEG_DEFAULT;
                    WAIST_CROSSBOW_RZ_DEG = WAIST_CROSSBOW_RZ_DEG_DEFAULT;
                    WAIST_CROSSBOW_SPIN_DEG = WAIST_CROSSBOW_SPIN_DEG_DEFAULT;
                    WAIST_CROSSBOW_SPIN_AXIS = SpinAxis.Z;
                    WAIST_CROSSBOW_ROLL_DEG = WAIST_CROSSBOW_ROLL_DEG_DEFAULT;
                    WAIST_CROSSBOW_ROLL_AXIS = SpinAxis.Z;
                }
            }
        } catch (Throwable ignored) {}
    }

    public static boolean ezvr$setWaistTweak(WaistProfile profile, String keyRaw, float value, boolean additive) {
        try {
            String key = (keyRaw == null) ? "" : keyRaw.trim().toLowerCase(java.util.Locale.ROOT);
            if (key.isEmpty()) return false;
            return switch (profile == null ? WaistProfile.DEFAULT : profile) {
                case DEFAULT -> switch (key) {
                    case "tx" -> { WAIST_TX = additive ? (WAIST_TX + value) : value; yield true; }
                    case "ty" -> { WAIST_TY = additive ? (WAIST_TY + value) : value; yield true; }
                    case "tz" -> { WAIST_TZ = additive ? (WAIST_TZ + value) : value; yield true; }
                    case "rx", "rx_deg" -> { WAIST_RX_DEG = additive ? (WAIST_RX_DEG + value) : value; yield true; }
                    case "ry", "ry_deg" -> { WAIST_RY_DEG = additive ? (WAIST_RY_DEG + value) : value; yield true; }
                    case "rz", "rz_deg" -> { WAIST_RZ_DEG = additive ? (WAIST_RZ_DEG + value) : value; yield true; }
                    case "spin", "spin_deg" -> { WAIST_SPIN_DEG = additive ? (WAIST_SPIN_DEG + value) : value; yield true; }
                    case "roll", "roll_deg" -> { WAIST_ROLL_DEG = additive ? (WAIST_ROLL_DEG + value) : value; yield true; }
                    default -> false;
                };
                case BOW -> switch (key) {
                    case "tx" -> { WAIST_BOW_TX = additive ? (WAIST_BOW_TX + value) : value; yield true; }
                    case "ty" -> { WAIST_BOW_TY = additive ? (WAIST_BOW_TY + value) : value; yield true; }
                    case "tz" -> { WAIST_BOW_TZ = additive ? (WAIST_BOW_TZ + value) : value; yield true; }
                    case "rx", "rx_deg" -> { WAIST_BOW_RX_DEG = additive ? (WAIST_BOW_RX_DEG + value) : value; yield true; }
                    case "ry", "ry_deg" -> { WAIST_BOW_RY_DEG = additive ? (WAIST_BOW_RY_DEG + value) : value; yield true; }
                    case "rz", "rz_deg" -> { WAIST_BOW_RZ_DEG = additive ? (WAIST_BOW_RZ_DEG + value) : value; yield true; }
                    case "spin", "spin_deg" -> { WAIST_BOW_SPIN_DEG = additive ? (WAIST_BOW_SPIN_DEG + value) : value; yield true; }
                    case "roll", "roll_deg" -> { WAIST_BOW_ROLL_DEG = additive ? (WAIST_BOW_ROLL_DEG + value) : value; yield true; }
                    default -> false;
                };
                case CROSSBOW -> switch (key) {
                    case "tx" -> { WAIST_CROSSBOW_TX = additive ? (WAIST_CROSSBOW_TX + value) : value; yield true; }
                    case "ty" -> { WAIST_CROSSBOW_TY = additive ? (WAIST_CROSSBOW_TY + value) : value; yield true; }
                    case "tz" -> { WAIST_CROSSBOW_TZ = additive ? (WAIST_CROSSBOW_TZ + value) : value; yield true; }
                    case "rx", "rx_deg" -> { WAIST_CROSSBOW_RX_DEG = additive ? (WAIST_CROSSBOW_RX_DEG + value) : value; yield true; }
                    case "ry", "ry_deg" -> { WAIST_CROSSBOW_RY_DEG = additive ? (WAIST_CROSSBOW_RY_DEG + value) : value; yield true; }
                    case "rz", "rz_deg" -> { WAIST_CROSSBOW_RZ_DEG = additive ? (WAIST_CROSSBOW_RZ_DEG + value) : value; yield true; }
                    case "spin", "spin_deg" -> { WAIST_CROSSBOW_SPIN_DEG = additive ? (WAIST_CROSSBOW_SPIN_DEG + value) : value; yield true; }
                    case "roll", "roll_deg" -> { WAIST_CROSSBOW_ROLL_DEG = additive ? (WAIST_CROSSBOW_ROLL_DEG + value) : value; yield true; }
                    default -> false;
                };
            };
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean ezvr$setWaistSpinAxis(String axisRaw) {
        return ezvr$setWaistSpinAxis(WaistProfile.DEFAULT, axisRaw);
    }

    public static boolean ezvr$setWaistSpinAxis(WaistProfile profile, String axisRaw) {
        try {
            String axis = (axisRaw == null) ? "" : axisRaw.trim().toUpperCase(java.util.Locale.ROOT);
            if (axis.isEmpty()) return false;
            switch (profile == null ? WaistProfile.DEFAULT : profile) {
                case DEFAULT -> WAIST_SPIN_AXIS = SpinAxis.valueOf(axis);
                case BOW -> WAIST_BOW_SPIN_AXIS = SpinAxis.valueOf(axis);
                case CROSSBOW -> WAIST_CROSSBOW_SPIN_AXIS = SpinAxis.valueOf(axis);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean ezvr$setWaistRollAxis(String axisRaw) {
        return ezvr$setWaistRollAxis(WaistProfile.DEFAULT, axisRaw);
    }

    public static boolean ezvr$setWaistRollAxis(WaistProfile profile, String axisRaw) {
        try {
            String axis = (axisRaw == null) ? "" : axisRaw.trim().toUpperCase(java.util.Locale.ROOT);
            if (axis.isEmpty()) return false;
            switch (profile == null ? WaistProfile.DEFAULT : profile) {
                case DEFAULT -> WAIST_ROLL_AXIS = SpinAxis.valueOf(axis);
                case BOW -> WAIST_BOW_ROLL_AXIS = SpinAxis.valueOf(axis);
                case CROSSBOW -> WAIST_CROSSBOW_ROLL_AXIS = SpinAxis.valueOf(axis);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String ezvr$waistTweakString() {
        return ezvr$waistTweakString(WaistProfile.DEFAULT);
    }

    public static String ezvr$waistTweakString(WaistProfile profile) {
        try {
            return switch (profile == null ? WaistProfile.DEFAULT : profile) {
                case DEFAULT -> "tx=" + WAIST_TX
                        + " ty=" + WAIST_TY
                        + " tz=" + WAIST_TZ
                        + " rx=" + WAIST_RX_DEG
                        + " ry=" + WAIST_RY_DEG
                        + " rz=" + WAIST_RZ_DEG
                        + " spin=" + WAIST_SPIN_DEG
                        + " spinAxis=" + String.valueOf(WAIST_SPIN_AXIS)
                        + " roll=" + WAIST_ROLL_DEG
                        + " rollAxis=" + String.valueOf(WAIST_ROLL_AXIS);
                case BOW -> "tx=" + WAIST_BOW_TX
                        + " ty=" + WAIST_BOW_TY
                        + " tz=" + WAIST_BOW_TZ
                        + " rx=" + WAIST_BOW_RX_DEG
                        + " ry=" + WAIST_BOW_RY_DEG
                        + " rz=" + WAIST_BOW_RZ_DEG
                        + " spin=" + WAIST_BOW_SPIN_DEG
                        + " spinAxis=" + String.valueOf(WAIST_BOW_SPIN_AXIS)
                        + " roll=" + WAIST_BOW_ROLL_DEG
                        + " rollAxis=" + String.valueOf(WAIST_BOW_ROLL_AXIS);
                case CROSSBOW -> "tx=" + WAIST_CROSSBOW_TX
                        + " ty=" + WAIST_CROSSBOW_TY
                        + " tz=" + WAIST_CROSSBOW_TZ
                        + " rx=" + WAIST_CROSSBOW_RX_DEG
                        + " ry=" + WAIST_CROSSBOW_RY_DEG
                        + " rz=" + WAIST_CROSSBOW_RZ_DEG
                        + " spin=" + WAIST_CROSSBOW_SPIN_DEG
                        + " spinAxis=" + String.valueOf(WAIST_CROSSBOW_SPIN_AXIS)
                        + " roll=" + WAIST_CROSSBOW_ROLL_DEG
                        + " rollAxis=" + String.valueOf(WAIST_CROSSBOW_ROLL_AXIS);
            };
        } catch (Throwable ignored) {
            return "tx=? ty=? tz=? rx=? ry=? rz=? spin=? spinAxis=? roll=? rollAxis=?";
        }
    }

    public static String ezvr$waistProfileLabel(WaistProfile profile) {
        return switch (profile == null ? WaistProfile.DEFAULT : profile) {
            case DEFAULT -> "Generic";
            case BOW -> "Bow";
            case CROSSBOW -> "Crossbow";
        };
    }

    public VillagerHolsteredLoadoutLayer(RenderLayerParent<Villager, VillagerModel<Villager>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack,
                       MultiBufferSource buffer,
                       int packedLight,
                       Villager villager,
                       float limbSwing,
                       float limbSwingAmount,
                       float partialTick,
                       float ageInTicks,
                       float netHeadYaw,
                       float headPitch) {
        try {
            if (villager == null || poseStack == null || buffer == null) return;

            if (!ezvr$initLogged) {
                ezvr$initLogged = true;
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] VillagerHolsteredLoadoutLayer ACTIVE");
            }

            // If the villager is holding anything, let the held-item layers handle it.
            ItemStack heldMain = villager.getMainHandItem();
            ItemStack heldOff = villager.getOffhandItem();
            if ((heldMain != null && !heldMain.isEmpty()) || (heldOff != null && !heldOff.isEmpty())) return;

            byte flags = VillagerRenderFlags.defaultFlags();
            if (villager instanceof VillagerOverhaulRenderAccess acc) {
                flags = acc.ezvr$getRenderFlags();
            }

            // Only show holstered loadout while using vanilla crossed arms (i.e., NOT custom arms).
            if (VillagerRenderFlags.renderCustomArms(flags)) return;

            if (!(villager instanceof VillagerOverhaulRenderAccess acc)) return;
            ItemStack loadoutMain = acc.ezvr$getCombatLoadoutMain();
            ItemStack loadoutOff = acc.ezvr$getCombatLoadoutOff();
            if ((loadoutMain == null || loadoutMain.isEmpty()) && (loadoutOff == null || loadoutOff.isEmpty())) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getItemRenderer() == null) return;

            ModelPart body = resolveBodyPart(getParentModel());

            if (ENABLE_WAIST && loadoutMain != null && !loadoutMain.isEmpty()) {
                HolsterTransform waist = getWaistTransform(loadoutMain);
                renderOne(
                        villager,
                        loadoutMain,
                        ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                        false,
                        body,
                        poseStack,
                        buffer,
                        packedLight,
                        waist.tx, waist.ty, waist.tz,
                        waist.rxDeg, waist.ryDeg, waist.rzDeg,
                        waist.scale,
                        waist.spinDeg,
                        waist.spinAxis,
                        waist.rollDeg,
                        waist.rollAxis
                );
            }

            if (ENABLE_BACK && loadoutOff != null && !loadoutOff.isEmpty()) {
                renderOne(
                        villager,
                        loadoutOff,
                        ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
                        true,
                        body,
                        poseStack,
                        buffer,
                        packedLight,
                        BACK_TX, BACK_TY, BACK_TZ,
                        BACK_RX_DEG, BACK_RY_DEG, BACK_RZ_DEG,
                        BACK_SCALE,
                        0.0f,
                        SpinAxis.Z,
                        0.0f,
                        SpinAxis.Z
                );
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerHolsteredLoadoutLayer.render failed (soft): {}", t.toString());
        }
    }

    private static HolsterTransform getWaistTransform(ItemStack stack) {
        return getWaistTransform(ezvr$getWaistProfileForStack(stack));
    }

    public static WaistProfile ezvr$getWaistProfileForStack(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return WaistProfile.DEFAULT;
            if (stack.getItem() instanceof CrossbowItem) return WaistProfile.CROSSBOW;
            if (stack.getItem() instanceof BowItem) return WaistProfile.BOW;
            if (stack.getItem() instanceof ProjectileWeaponItem) {
                UseAnim anim = stack.getUseAnimation();
                if (anim == UseAnim.CROSSBOW) return WaistProfile.CROSSBOW;
                if (anim == UseAnim.BOW) return WaistProfile.BOW;
                try {
                    if (stack.useOnRelease()) return WaistProfile.CROSSBOW;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return WaistProfile.DEFAULT;
    }

    private static HolsterTransform getWaistTransform(WaistProfile profile) {
        return switch (profile == null ? WaistProfile.DEFAULT : profile) {
            case DEFAULT -> new HolsterTransform(
                    WAIST_TX, WAIST_TY, WAIST_TZ,
                    WAIST_RX_DEG, WAIST_RY_DEG, WAIST_RZ_DEG,
                    WAIST_SCALE,
                    WAIST_SPIN_DEG,
                    WAIST_SPIN_AXIS,
                    WAIST_ROLL_DEG,
                    WAIST_ROLL_AXIS
            );
            case BOW -> new HolsterTransform(
                    WAIST_BOW_TX, WAIST_BOW_TY, WAIST_BOW_TZ,
                    WAIST_BOW_RX_DEG, WAIST_BOW_RY_DEG, WAIST_BOW_RZ_DEG,
                    WAIST_BOW_SCALE,
                    WAIST_BOW_SPIN_DEG,
                    WAIST_BOW_SPIN_AXIS,
                    WAIST_BOW_ROLL_DEG,
                    WAIST_BOW_ROLL_AXIS
            );
            case CROSSBOW -> new HolsterTransform(
                    WAIST_CROSSBOW_TX, WAIST_CROSSBOW_TY, WAIST_CROSSBOW_TZ,
                    WAIST_CROSSBOW_RX_DEG, WAIST_CROSSBOW_RY_DEG, WAIST_CROSSBOW_RZ_DEG,
                    WAIST_CROSSBOW_SCALE,
                    WAIST_CROSSBOW_SPIN_DEG,
                    WAIST_CROSSBOW_SPIN_AXIS,
                    WAIST_CROSSBOW_ROLL_DEG,
                    WAIST_CROSSBOW_ROLL_AXIS
            );
        };
    }

    private static void renderOne(Villager villager,
                                  ItemStack stack,
                                  ItemDisplayContext ctx,
                                  boolean leftHand,
                                  ModelPart bodyOrNull,
                                  PoseStack poseStack,
                                  MultiBufferSource buffer,
                                  int packedLight,
                                  float tx, float ty, float tz,
                                  float rxDeg, float ryDeg, float rzDeg,
                                  float scale,
                                  float spinDeg,
                                  SpinAxis spinAxis,
                                  float rollDeg,
                                  SpinAxis rollAxis) {
        try {
            if (stack == null || stack.isEmpty()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getItemRenderer() == null) return;

            poseStack.pushPose();
            try {
                if (bodyOrNull != null) {
                    bodyOrNull.translateAndRotate(poseStack);
                }

                poseStack.translate(tx, ty, tz);
                if (rxDeg != 0.0f) poseStack.mulPose(Axis.XP.rotationDegrees(rxDeg));
                if (ryDeg != 0.0f) poseStack.mulPose(Axis.YP.rotationDegrees(ryDeg));
                if (rzDeg != 0.0f) poseStack.mulPose(Axis.ZP.rotationDegrees(rzDeg));
                if (spinDeg != 0.0f) {
                    if (spinAxis == null) spinAxis = SpinAxis.Z;
                    switch (spinAxis) {
                        case X -> poseStack.mulPose(Axis.XP.rotationDegrees(spinDeg));
                        case Y -> poseStack.mulPose(Axis.YP.rotationDegrees(spinDeg));
                        case Z -> poseStack.mulPose(Axis.ZP.rotationDegrees(spinDeg));
                    }
                }
                if (scale != 1.0f) poseStack.scale(scale, scale, scale);

                HolsterItemRenderTweakState.Axis ra = HolsterItemRenderTweakState.Axis.Z;
                if (rollAxis != null) {
                    ra = switch (rollAxis) {
                        case X -> HolsterItemRenderTweakState.Axis.X;
                        case Y -> HolsterItemRenderTweakState.Axis.Y;
                        case Z -> HolsterItemRenderTweakState.Axis.Z;
                    };
                }

                HolsterItemRenderTweakState.push(rollDeg, ra);
                try {
                    mc.getItemRenderer().renderStatic(
                            villager,
                            stack,
                            ctx,
                            leftHand,
                            poseStack,
                            buffer,
                            villager.level(),
                            packedLight,
                            OverlayTexture.NO_OVERLAY,
                            villager.getId()
                    );
                } finally {
                    HolsterItemRenderTweakState.pop();
                }

            } finally {
                poseStack.popPose();
            }

        } catch (Throwable ignored) {}
    }

    private static ModelPart resolveBodyPart(VillagerModel<?> model) {
        try {
            if (model == null) return null;
            ModelPart root = tryCallRoot(model);
            if (root == null) return null;

            try {
                return root.getChild("body");
            } catch (Throwable ignored) {
                return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ModelPart tryCallRoot(VillagerModel<?> model) {
        try {
            if (model == null) return null;

            Method m = ROOT_METHOD;
            if (m == null) {
                m = model.getClass().getMethod("root");
                m.setAccessible(true);
                ROOT_METHOD = m;
            }

            Object v = m.invoke(model);
            return (v instanceof ModelPart mp) ? mp : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private record HolsterTransform(
            float tx,
            float ty,
            float tz,
            float rxDeg,
            float ryDeg,
            float rzDeg,
            float scale,
            float spinDeg,
            SpinAxis spinAxis,
            float rollDeg,
            SpinAxis rollAxis
    ) {}
}
