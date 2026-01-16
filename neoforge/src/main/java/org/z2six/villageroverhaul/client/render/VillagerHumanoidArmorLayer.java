// neoforge\src\main\java\org\z2six\villageroverhaul\client\render\VillagerHumanoidArmorLayer.java
package org.z2six.villageroverhaul.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders armor on Villagers using vanilla humanoid armor models (inner/outer),
 * but drives their pose from VillagerModel parts.
 *
 * 1.21.1 NeoForge uses ArmorMaterial.Layer#texture(boolean) and #dyeable().
 * Do NOT guess texture paths, or you get missing-texture purple/black.
 *
 * THIS VERSION:
 * - Adds hardcoded, easy-to-fiddle transforms per equipment slot.
 * - Adds optional per-part transforms per slot (body/arms/legs/head/hat).
 */
public final class VillagerHumanoidArmorLayer extends RenderLayer<Villager, VillagerModel<Villager>> {

    // =========================================================================================
    // TWEAKABLE TRANSFORMS (hardcoded)
    // All distances here are in *model-space* units (roughly "blocks" / 16, but not exactly).
    // Start with tiny values (0.005 .. 0.05). Z translation helps with clipping.
    // =========================================================================================

    /**
     * Master enable: set false to disable ALL transforms quickly for A/B testing.
     */
    private static final boolean ENABLE_ARMOR_TRANSFORMS = true;

    /**
     * Per-slot transform applied to the whole rendered armor for that slot.
     *
     * Common uses:
     * - Helmet higher: HEAD.y += 0.015 .. 0.03
     * - Chest bigger: CHEST.scaleXYZ = 1.03 .. 1.10
     * - Legs bigger: LEGS.scaleXYZ = 1.02 .. 1.08
     * - Push outward to reduce robe clipping: z += 0.01 .. 0.03
     */
    private static final Transform TX_HEAD  = Transform.of(0.0f, -0.075f, 0.000f, 1.000f, 1.000f, 1.000f);
    private static final Transform TX_CHEST = Transform.of(0.0f, 0.000f, 0.012f, 1.020f, 1.020f, 1.020f);
    private static final Transform TX_LEGS  = Transform.of(0.0f, 0.000f, 0.010f, 1.020f, 1.020f, 1.020f);
    private static final Transform TX_FEET  = Transform.of(0.0f, 0.000f, 0.006f, 1.020f, 1.020f, 1.020f);

    /**
     * Optional per-part transforms (applied just before rendering each ModelPart).
     * This is useful to scale body without scaling arms as much, etc.
     *
     * Set to Transform.IDENTITY to disable a part override.
     */
    // HEAD slot: adjust helmet/hat independently if needed
    private static final Transform TXP_HEAD_HEAD = Transform.IDENTITY;
    private static final Transform TXP_HEAD_HAT  = Transform.IDENTITY;

    // CHEST slot: commonly body needs to be bigger than arms
    private static final Transform TXP_CHEST_BODY = Transform.of(0.0f, 0.000f, 0.000f, 1.070f, 1.070f, 1.070f);
    private static final Transform TXP_CHEST_RARM = Transform.of(0.0f, 0.000f, 0.000f, 1.020f, 1.020f, 1.020f);
    private static final Transform TXP_CHEST_LARM = Transform.of(0.0f, 0.000f, 0.000f, 1.020f, 1.020f, 1.020f);

    // LEGS slot: body (hip) vs legs
    private static final Transform TXP_LEGS_BODY  = Transform.of(0.0f, 0.000f, 0.000f, 1.040f, 1.040f, 1.040f);
    private static final Transform TXP_LEGS_RLEG  = Transform.of(0.0f, 0.000f, 0.000f, 1.030f, 1.030f, 1.030f);
    private static final Transform TXP_LEGS_LLEG  = Transform.of(0.0f, 0.000f, 0.000f, 1.030f, 1.030f, 1.030f);

    // FEET slot: boots (legs only)
    private static final Transform TXP_FEET_RLEG  = Transform.of(0.0f, 0.000f, 0.000f, 1.020f, 1.020f, 1.020f);
    private static final Transform TXP_FEET_LLEG  = Transform.of(0.0f, 0.000f, 0.000f, 1.020f, 1.020f, 1.020f);

    // =========================================================================================

    private final HumanoidModel<?> innerModel;
    private final HumanoidModel<?> outerModel;

    // Cached accessors for villager model parts (reflection, because mappings drift)
    private final PartAccess villagerParts;

    // Cached ModelPart#render overloads (1.21+ variants exist)
    private static volatile Method MODEL_PART_RENDER_5; // (PoseStack, VertexConsumer, int, int, int)
    private static volatile Method MODEL_PART_RENDER_4; // (PoseStack, VertexConsumer, int, int)

    // Reflection cache for armor material layer access
    private static volatile Method HOLDER_VALUE;                 // Holder#value()
    private static volatile Method ARMOR_MATERIAL_LAYERS;        // ArmorMaterial#layers()

    // IMPORTANT: method names differ by mappings/version
    // NeoForge/MojMap 1.21.1: Layer#texture(boolean), Layer#dyeable()
    // Yarn-ish: Layer#getTexture(boolean), Layer#isDyeable()
    private static volatile Method ARMOR_LAYER_TEXTURE;          // texture(boolean) OR getTexture(boolean)
    private static volatile Method ARMOR_LAYER_DYEABLE;          // dyeable() OR isDyeable()

    // ItemStack component access for dyed color (kept reflective to be robust)
    private static volatile Class<?> DATA_COMPONENT_TYPE_CLASS;
    private static volatile Object DYED_COLOR_COMPONENT_KEY;
    private static volatile Method ITEMSTACK_GET_COMPONENT;

    public VillagerHumanoidArmorLayer(RenderLayerParent<Villager, VillagerModel<Villager>> parent,
                                      HumanoidModel<?> innerModel,
                                      HumanoidModel<?> outerModel) {
        super(parent);
        this.innerModel = innerModel;
        this.outerModel = outerModel;

        this.villagerParts = new PartAccess(parent.getModel());

        warmupModelPartRenderMethods();
        warmupArmorMaterialLayerReflection();
        warmupDyedColorReflection();
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
            if (villager == null) return;

            renderArmorSlot(villager, EquipmentSlot.HEAD, poseStack, buffer, packedLight);
            renderArmorSlot(villager, EquipmentSlot.CHEST, poseStack, buffer, packedLight);
            renderArmorSlot(villager, EquipmentSlot.LEGS, poseStack, buffer, packedLight);
            renderArmorSlot(villager, EquipmentSlot.FEET, poseStack, buffer, packedLight);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerHumanoidArmorLayer render failed (soft): {}", t.toString());
        }
    }

    private void renderArmorSlot(Villager vill,
                                 EquipmentSlot slot,
                                 PoseStack poseStack,
                                 MultiBufferSource buffer,
                                 int packedLight) {
        ItemStack stack;
        try {
            stack = vill.getItemBySlot(slot);
        } catch (Throwable ignored) {
            return;
        }
        if (stack == null || stack.isEmpty()) return;
        if (!(stack.getItem() instanceof ArmorItem armor)) return;
        if (armor.getEquipmentSlot() != slot) return;

        // In vanilla terminology: leggings use the "inner" texture/model.
        boolean innerTexture = (slot == EquipmentSlot.LEGS);
        HumanoidModel<?> model = innerTexture ? innerModel : outerModel;

        setPartVisibility(model, slot);
        copyVillagerPoseIntoHumanoid(this.getParentModel(), model);

        List<ArmorLayer> layers = getArmorMaterialLayersSafe(armor, innerTexture);
        if (layers.isEmpty()) {
            if (VillagerOverhaul.LOG().isDebugEnabled()) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] No armor layers resolved for item={} slot={} (innerTexture={})",
                        stack.getItem(), slot, innerTexture);
            }
            return;
        }

        int overlay = OverlayTexture.NO_OVERLAY;

        // Dye (only used for dyeable layers)
        int dyeRGB = tryGetDyedLeatherColorRGB(stack);
        boolean hasDye = (dyeRGB != -1);

        // Vanilla-ish default leather color when undyed
        int defaultLeather = 0xA06540;
        int usedRGB = hasDye ? dyeRGB : defaultLeather;

        // --- SLOT LEVEL TRANSFORM ---
        boolean doTx = ENABLE_ARMOR_TRANSFORMS;
        Transform slotTx = doTx ? transformForSlot(slot) : Transform.IDENTITY;

        poseStack.pushPose();
        try {
            if (doTx && !slotTx.isIdentity()) {
                slotTx.apply(poseStack);
            }

            for (ArmorLayer layer : layers) {
                if (layer == null || layer.texture == null) continue;

                int packedColor = 0xFFFFFFFF;
                if (layer.dyeable) {
                    packedColor = FastColor.ARGB32.color(255,
                            FastColor.ARGB32.red(usedRGB),
                            FastColor.ARGB32.green(usedRGB),
                            FastColor.ARGB32.blue(usedRGB));
                }

                try {
                    var vc = buffer.getBuffer(RenderType.armorCutoutNoCull(layer.texture));

                    // Render visible parts with optional per-part transforms for this slot
                    renderVisiblePartsWithPerPartTransforms(slot, model, poseStack, vc, packedLight, overlay, packedColor);

                } catch (Throwable t) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] Armor layer render failed tex={} item={} (soft): {}",
                            layer.texture, stack.getItem(), t.toString());
                }
            }
        } finally {
            poseStack.popPose();
        }
    }

    // -----------------------------------------------------------------------------------------
    // Per-slot / per-part transform selection
    // -----------------------------------------------------------------------------------------

    private static Transform transformForSlot(EquipmentSlot slot) {
        if (slot == null) return Transform.IDENTITY;
        return switch (slot) {
            case HEAD -> TX_HEAD;
            case CHEST -> TX_CHEST;
            case LEGS -> TX_LEGS;
            case FEET -> TX_FEET;
            default -> Transform.IDENTITY;
        };
    }

    private static Transform partTransformFor(EquipmentSlot slot, PartKind part) {
        if (slot == null || part == null) return Transform.IDENTITY;

        return switch (slot) {
            case HEAD -> switch (part) {
                case HEAD -> TXP_HEAD_HEAD;
                case HAT  -> TXP_HEAD_HAT;
                default -> Transform.IDENTITY;
            };
            case CHEST -> switch (part) {
                case BODY -> TXP_CHEST_BODY;
                case RIGHT_ARM -> TXP_CHEST_RARM;
                case LEFT_ARM  -> TXP_CHEST_LARM;
                default -> Transform.IDENTITY;
            };
            case LEGS -> switch (part) {
                case BODY -> TXP_LEGS_BODY;
                case RIGHT_LEG -> TXP_LEGS_RLEG;
                case LEFT_LEG  -> TXP_LEGS_LLEG;
                default -> Transform.IDENTITY;
            };
            case FEET -> switch (part) {
                case RIGHT_LEG -> TXP_FEET_RLEG;
                case LEFT_LEG  -> TXP_FEET_LLEG;
                default -> Transform.IDENTITY;
            };
            default -> Transform.IDENTITY;
        };
    }

    private enum PartKind {
        HEAD, HAT, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG
    }

    private static void renderVisiblePartsWithPerPartTransforms(EquipmentSlot slot,
                                                                HumanoidModel<?> model,
                                                                PoseStack poseStack,
                                                                com.mojang.blaze3d.vertex.VertexConsumer vc,
                                                                int light,
                                                                int overlay,
                                                                int packedColor) {
        try {
            if (model == null) return;

            // HEAD
            if (model.head != null && model.head.visible) {
                renderPartWithTransform(slot, PartKind.HEAD, model.head, poseStack, vc, light, overlay, packedColor);
            }
            if (model.hat != null && model.hat.visible) {
                renderPartWithTransform(slot, PartKind.HAT, model.hat, poseStack, vc, light, overlay, packedColor);
            }

            // BODY
            if (model.body != null && model.body.visible) {
                renderPartWithTransform(slot, PartKind.BODY, model.body, poseStack, vc, light, overlay, packedColor);
            }

            // ARMS
            if (model.rightArm != null && model.rightArm.visible) {
                renderPartWithTransform(slot, PartKind.RIGHT_ARM, model.rightArm, poseStack, vc, light, overlay, packedColor);
            }
            if (model.leftArm != null && model.leftArm.visible) {
                renderPartWithTransform(slot, PartKind.LEFT_ARM, model.leftArm, poseStack, vc, light, overlay, packedColor);
            }

            // LEGS
            if (model.rightLeg != null && model.rightLeg.visible) {
                renderPartWithTransform(slot, PartKind.RIGHT_LEG, model.rightLeg, poseStack, vc, light, overlay, packedColor);
            }
            if (model.leftLeg != null && model.leftLeg.visible) {
                renderPartWithTransform(slot, PartKind.LEFT_LEG, model.leftLeg, poseStack, vc, light, overlay, packedColor);
            }

        } catch (Throwable ignored) {}
    }

    private static void renderPartWithTransform(EquipmentSlot slot,
                                                PartKind kind,
                                                ModelPart part,
                                                PoseStack poseStack,
                                                com.mojang.blaze3d.vertex.VertexConsumer vc,
                                                int light,
                                                int overlay,
                                                int packedColor) {
        if (part == null) return;

        Transform tx = (ENABLE_ARMOR_TRANSFORMS) ? partTransformFor(slot, kind) : Transform.IDENTITY;
        if (tx == null) tx = Transform.IDENTITY;

        poseStack.pushPose();
        try {
            if (!tx.isIdentity()) {
                tx.apply(poseStack);
            }
            invokeModelPartRender(part, poseStack, vc, light, overlay, packedColor);
        } catch (Throwable ignored) {
        } finally {
            poseStack.popPose();
        }
    }

    private static final class Transform {
        static final Transform IDENTITY = new Transform(0f, 0f, 0f, 1f, 1f, 1f);

        final float x, y, z;
        final float sx, sy, sz;

        private Transform(float x, float y, float z, float sx, float sy, float sz) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
        }

        static Transform of(float x, float y, float z, float sx, float sy, float sz) {
            return new Transform(x, y, z, sx, sy, sz);
        }

        boolean isIdentity() {
            return x == 0f && y == 0f && z == 0f && sx == 1f && sy == 1f && sz == 1f;
        }

        void apply(PoseStack ps) {
            try {
                ps.translate(x, y, z);
                ps.scale(sx, sy, sz);
            } catch (Throwable ignored) {}
        }
    }

    // -----------------------------------------------------------------------------------------
    // ArmorMaterial.Layer based texture resolution
    // -----------------------------------------------------------------------------------------

    private static final class ArmorLayer {
        final ResourceLocation texture;
        final boolean dyeable;

        ArmorLayer(ResourceLocation texture, boolean dyeable) {
            this.texture = texture;
            this.dyeable = dyeable;
        }
    }

    private static List<ArmorLayer> getArmorMaterialLayersSafe(ArmorItem armor, boolean innerTexture) {
        List<ArmorLayer> out = new ArrayList<>();
        try {
            if (armor == null) return out;

            Object holder = armor.getMaterial(); // Holder<ArmorMaterial> in MojMap
            if (holder == null) return out;

            Object material = invokeHolderValue(holder);
            if (material == null) return out;

            Object layersObj = invokeArmorMaterialLayers(material);
            if (!(layersObj instanceof List<?> layers)) return out;

            for (Object layer : layers) {
                if (layer == null) continue;

                ResourceLocation tex = invokeArmorLayerTexture(layer, innerTexture);
                boolean dyeable = invokeArmorLayerDyeable(layer);

                if (tex != null) {
                    out.add(new ArmorLayer(tex, dyeable));
                }
            }

            if (VillagerOverhaul.LOG().isDebugEnabled()) {
                if (out.isEmpty()) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] ArmorMaterial.layers() produced 0 textures for material={} (innerTexture={})",
                            safeToString(material), innerTexture);
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (ArmorLayer al : out) {
                        sb.append(al.texture).append(al.dyeable ? "(dye)" : "(plain)").append(" ");
                    }
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] Armor layers for {} innerTexture={}: {}",
                            armor, innerTexture, sb.toString().trim());
                }
            }

            return out;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] getArmorMaterialLayersSafe failed (soft): {}", t.toString());
            return out;
        }
    }

    private static Object invokeHolderValue(Object holder) {
        try {
            // Prefer direct Holder#value() if present
            try {
                Method m = holder.getClass().getMethod("value");
                return m.invoke(holder);
            } catch (Throwable ignored) {}

            if (HOLDER_VALUE != null) return HOLDER_VALUE.invoke(holder);

            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeArmorMaterialLayers(Object material) {
        try {
            // Record-like accessor: layers()
            try {
                Method m = material.getClass().getMethod("layers");
                return m.invoke(material);
            } catch (Throwable ignored) {}

            if (ARMOR_MATERIAL_LAYERS != null) return ARMOR_MATERIAL_LAYERS.invoke(material);

            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ResourceLocation invokeArmorLayerTexture(Object layer, boolean innerTexture) {
        try {
            // NeoForge/MojMap 1.21.1: texture(boolean)
            try {
                Method m = layer.getClass().getMethod("texture", boolean.class);
                Object v = m.invoke(layer, innerTexture);
                if (v instanceof ResourceLocation rl) return rl;
            } catch (Throwable ignored) {}

            // Other mappings: getTexture(boolean)
            try {
                Method m = layer.getClass().getMethod("getTexture", boolean.class);
                Object v = m.invoke(layer, innerTexture);
                if (v instanceof ResourceLocation rl) return rl;
            } catch (Throwable ignored) {}

            if (ARMOR_LAYER_TEXTURE != null) {
                Object v = ARMOR_LAYER_TEXTURE.invoke(layer, innerTexture);
                if (v instanceof ResourceLocation rl) return rl;
            }

            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean invokeArmorLayerDyeable(Object layer) {
        try {
            // NeoForge/MojMap 1.21.1: dyeable()
            try {
                Method m = layer.getClass().getMethod("dyeable");
                Object v = m.invoke(layer);
                return (v instanceof Boolean b) && b;
            } catch (Throwable ignored) {}

            // Other mappings: isDyeable()
            try {
                Method m = layer.getClass().getMethod("isDyeable");
                Object v = m.invoke(layer);
                return (v instanceof Boolean b) && b;
            } catch (Throwable ignored) {}

            if (ARMOR_LAYER_DYEABLE != null) {
                Object v = ARMOR_LAYER_DYEABLE.invoke(layer);
                return (v instanceof Boolean b) && b;
            }

            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void warmupArmorMaterialLayerReflection() {
        try {
            // Best-effort soft caching; per-instance reflection above already handles most cases.
            try {
                Class<?> holder = Class.forName("net.minecraft.core.Holder");
                HOLDER_VALUE = holder.getMethod("value");
            } catch (Throwable ignored) {}

            try {
                Class<?> armorMaterial = Class.forName("net.minecraft.world.item.ArmorMaterial");
                ARMOR_MATERIAL_LAYERS = armorMaterial.getMethod("layers");
            } catch (Throwable ignored) {}

            try {
                Class<?> layerClz = null;
                try {
                    layerClz = Class.forName("net.minecraft.world.item.ArmorMaterial$Layer");
                } catch (Throwable ignored) {}

                if (layerClz != null) {
                    // Prefer MojMap: texture(boolean), dyeable()
                    try {
                        ARMOR_LAYER_TEXTURE = layerClz.getMethod("texture", boolean.class);
                    } catch (Throwable ignored) {}
                    try {
                        ARMOR_LAYER_DYEABLE = layerClz.getMethod("dyeable");
                    } catch (Throwable ignored) {}

                    // Fallback names
                    if (ARMOR_LAYER_TEXTURE == null) {
                        try {
                            ARMOR_LAYER_TEXTURE = layerClz.getMethod("getTexture", boolean.class);
                        } catch (Throwable ignored) {}
                    }
                    if (ARMOR_LAYER_DYEABLE == null) {
                        try {
                            ARMOR_LAYER_DYEABLE = layerClz.getMethod("isDyeable");
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    // -----------------------------------------------------------------------------------------
    // Visibility + pose copy
    // -----------------------------------------------------------------------------------------

    private static void setPartVisibility(HumanoidModel<?> model, EquipmentSlot slot) {
        try {
            model.setAllVisible(false);
        } catch (Throwable ignored) {
            try {
                if (model.head != null) model.head.visible = false;
                if (model.hat != null) model.hat.visible = false;
                if (model.body != null) model.body.visible = false;
                if (model.rightArm != null) model.rightArm.visible = false;
                if (model.leftArm != null) model.leftArm.visible = false;
                if (model.rightLeg != null) model.rightLeg.visible = false;
                if (model.leftLeg != null) model.leftLeg.visible = false;
            } catch (Throwable ignored2) {}
        }

        switch (slot) {
            case HEAD -> {
                if (model.head != null) model.head.visible = true;
                if (model.hat != null) model.hat.visible = true;
            }
            case CHEST -> {
                if (model.body != null) model.body.visible = true;
                if (model.rightArm != null) model.rightArm.visible = true;
                if (model.leftArm != null) model.leftArm.visible = true;
            }
            case LEGS -> {
                if (model.body != null) model.body.visible = true;
                if (model.rightLeg != null) model.rightLeg.visible = true;
                if (model.leftLeg != null) model.leftLeg.visible = true;
            }
            case FEET -> {
                if (model.rightLeg != null) model.rightLeg.visible = true;
                if (model.leftLeg != null) model.leftLeg.visible = true;
            }
            default -> {}
        }
    }

    private void copyVillagerPoseIntoHumanoid(VillagerModel<Villager> villagerModel, HumanoidModel<?> humanoid) {
        if (villagerModel == null || humanoid == null) return;

        ModelPart vHead = villagerParts.head(villagerModel);
        ModelPart vBody = villagerParts.body(villagerModel);
        ModelPart vRA   = villagerParts.rightArm(villagerModel);
        ModelPart vLA   = villagerParts.leftArm(villagerModel);
        ModelPart vRL   = villagerParts.rightLeg(villagerModel);
        ModelPart vLL   = villagerParts.leftLeg(villagerModel);

        if (vHead != null && humanoid.head != null) copyPart(vHead, humanoid.head);
        if (humanoid.hat != null && humanoid.head != null) copyPart(humanoid.head, humanoid.hat);

        if (vBody != null && humanoid.body != null) copyPart(vBody, humanoid.body);
        if (vRA != null && humanoid.rightArm != null) copyPart(vRA, humanoid.rightArm);
        if (vLA != null && humanoid.leftArm != null) copyPart(vLA, humanoid.leftArm);
        if (vRL != null && humanoid.rightLeg != null) copyPart(vRL, humanoid.rightLeg);
        if (vLL != null && humanoid.leftLeg != null) copyPart(vLL, humanoid.leftLeg);
    }

    private static void copyPart(ModelPart from, ModelPart to) {
        if (from == null || to == null) return;

        try {
            Method m = ModelPart.class.getMethod("copyFrom", ModelPart.class);
            m.invoke(to, from);
            return;
        } catch (Throwable ignored) {}

        try {
            to.xRot = from.xRot;
            to.yRot = from.yRot;
            to.zRot = from.zRot;

            to.x = from.x;
            to.y = from.y;
            to.z = from.z;

            to.visible = from.visible;
        } catch (Throwable ignored) {}
    }

    private static void invokeModelPartRender(ModelPart part,
                                              PoseStack poseStack,
                                              com.mojang.blaze3d.vertex.VertexConsumer vc,
                                              int light,
                                              int overlay,
                                              int packedColor) {
        try {
            if (MODEL_PART_RENDER_5 != null) {
                MODEL_PART_RENDER_5.invoke(part, poseStack, vc, light, overlay, packedColor);
                return;
            }
            if (MODEL_PART_RENDER_4 != null) {
                MODEL_PART_RENDER_4.invoke(part, poseStack, vc, light, overlay);
            }
        } catch (Throwable ignored) {}
    }

    private static void warmupModelPartRenderMethods() {
        try {
            if (MODEL_PART_RENDER_5 != null || MODEL_PART_RENDER_4 != null) return;

            for (Method m : ModelPart.class.getMethods()) {
                if (!"render".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 5) {
                    MODEL_PART_RENDER_5 = m;
                } else if (p.length == 4) {
                    MODEL_PART_RENDER_4 = m;
                }
            }
        } catch (Throwable ignored) {}
    }

    // -----------------------------------------------------------------------------------------
    // Dyed leather color (DataComponents.DYED_COLOR)
    // -----------------------------------------------------------------------------------------

    private static void warmupDyedColorReflection() {
        try {
            if (DATA_COMPONENT_TYPE_CLASS != null && DYED_COLOR_COMPONENT_KEY != null && ITEMSTACK_GET_COMPONENT != null) return;

            DATA_COMPONENT_TYPE_CLASS = Class.forName("net.minecraft.core.component.DataComponentType");

            Class<?> dataComponents = Class.forName("net.minecraft.core.component.DataComponents");
            Field dyedField = dataComponents.getField("DYED_COLOR");
            DYED_COLOR_COMPONENT_KEY = dyedField.get(null);

            ITEMSTACK_GET_COMPONENT = ItemStack.class.getMethod("get", DATA_COMPONENT_TYPE_CLASS);
        } catch (Throwable ignored) {
            // soft
        }
    }

    private static int tryGetDyedLeatherColorRGB(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return -1;
            if (!(stack.getItem() instanceof ArmorItem)) return -1;

            if (DYED_COLOR_COMPONENT_KEY == null || ITEMSTACK_GET_COMPONENT == null) return -1;

            Object dyedObj = ITEMSTACK_GET_COMPONENT.invoke(stack, DYED_COLOR_COMPONENT_KEY);
            if (dyedObj == null) return -1;

            try {
                Method rgb = dyedObj.getClass().getMethod("rgb");
                Object v = rgb.invoke(dyedObj);
                if (v instanceof Integer i) return i;
            } catch (Throwable ignored) {}

            try {
                Method getColor = dyedObj.getClass().getMethod("getColor");
                Object v = getColor.invoke(dyedObj);
                if (v instanceof Integer i) return i;
            } catch (Throwable ignored) {}

            return -1;

        } catch (Throwable ignored) {
            return -1;
        }
    }

    // -----------------------------------------------------------------------------------------
    // Reflection-based access to VillagerModel parts.
    // -----------------------------------------------------------------------------------------

    private static final class PartAccess {
        private final Field headF, bodyF, rightArmF, leftArmF, rightLegF, leftLegF;

        PartAccess(VillagerModel<Villager> model) {
            this.headF = findPartField(model, "head");
            this.bodyF = findPartField(model, "body");
            this.rightArmF = findPartField(model, "rightArm");
            this.leftArmF = findPartField(model, "leftArm");
            this.rightLegF = findPartField(model, "rightLeg");
            this.leftLegF = findPartField(model, "leftLeg");
        }

        ModelPart head(VillagerModel<Villager> m) { return get(m, headF); }
        ModelPart body(VillagerModel<Villager> m) { return get(m, bodyF); }
        ModelPart rightArm(VillagerModel<Villager> m) { return get(m, rightArmF); }
        ModelPart leftArm(VillagerModel<Villager> m) { return get(m, leftArmF); }
        ModelPart rightLeg(VillagerModel<Villager> m) { return get(m, rightLegF); }
        ModelPart leftLeg(VillagerModel<Villager> m) { return get(m, leftLegF); }

        private static ModelPart get(Object inst, Field f) {
            try {
                if (inst == null || f == null) return null;
                Object v = f.get(inst);
                return (v instanceof ModelPart mp) ? mp : null;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static Field findPartField(Object inst, String name) {
            try {
                if (inst == null || name == null) return null;
                Class<?> c = inst.getClass();
                while (c != null && c != Object.class) {
                    try {
                        Field f = c.getDeclaredField(name);
                        f.setAccessible(true);
                        return f;
                    } catch (NoSuchFieldException ignored) {}
                    c = c.getSuperclass();
                }
                return null;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Misc
    // -----------------------------------------------------------------------------------------

    private static String safeToString(Object o) {
        try {
            return String.valueOf(o);
        } catch (Throwable ignored) {
            return "<err>";
        }
    }
}
