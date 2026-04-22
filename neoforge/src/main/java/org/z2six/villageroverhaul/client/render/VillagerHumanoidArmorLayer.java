// neoforge\src\main\java\org\z2six\villageroverhaul\client\render\VillagerHumanoidArmorLayer.java
package org.z2six.villageroverhaul.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.render.VillagerRenderFlags;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    // Helmets from many mods are authored strictly for player proportions; villagers have a taller head/nose.
    // We apply a small uniform scale for *custom* armor models to reduce head clipping.
    // Single setting for all helmets (vanilla + modded). Tune here.
    //
    // From the perspective of player (villager facing the player), variables are as follows:
    // x = ??? | y = positive = down | z = ???
    // sx = width | sy = height | sz = depth
    private static final Transform TX_HEAD  = Transform.of(0.0f, 0.100f, 0.000f, 1.080f, 1.550f, 1.080f);
    private static final Transform TX_CHEST = Transform.of(0.0f, 0.000f, 0.012f, 1.060f, 1.060f, 1.250f);
    private static final Transform TX_LEGS  = Transform.of(0.0f, 0.000f, 0.010f, 1.060f, 1.060f, 1.150f);
    private static final Transform TX_FEET  = Transform.of(0.0f, 0.000f, 0.006f, 1.060f, 1.060f, 1.150f);

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
    private final HumanoidModel<LivingEntity> driverHumanoid;
    private final VillagerCombatArmsModel armsModel;

    private TextureAtlas armorTrimAtlas;

    private static final Set<String> EZVR_ARMOR_INFO_LOG_ONCE =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Cached accessors for villager model parts (reflection, because mappings drift)
    private final PartAccess villagerParts;

    // Cached ModelPart#render overloads (1.21+ variants exist)
    private static volatile Method MODEL_PART_RENDER_5; // (PoseStack, VertexConsumer, int, int, int)
    private static volatile Method MODEL_PART_RENDER_4; // (PoseStack, VertexConsumer, int, int)

    public VillagerHumanoidArmorLayer(RenderLayerParent<Villager, VillagerModel<Villager>> parent,
                                      HumanoidModel<?> innerModel,
                                      HumanoidModel<?> outerModel,
                                      HumanoidModel<LivingEntity> driverHumanoid,
                                      VillagerCombatArmsModel armsModel) {
        super(parent);
        this.innerModel = innerModel;
        this.outerModel = outerModel;
        this.driverHumanoid = driverHumanoid;
        this.armsModel = armsModel;

        this.villagerParts = new PartAccess(parent.getModel());

        warmupModelPartRenderMethods();

        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            var mm = mc == null ? null : mc.getModelManager();
            this.armorTrimAtlas = mm == null ? null : mm.getAtlas(Sheets.ARMOR_TRIMS_SHEET);
        } catch (Throwable ignored) {
            this.armorTrimAtlas = null;
        }
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

            // Match vanilla HumanoidArmorLayer order
            renderArmorSlot(villager, EquipmentSlot.CHEST, poseStack, buffer, packedLight, limbSwing, limbSwingAmount, partialTick, ageInTicks, netHeadYaw, headPitch);
            renderArmorSlot(villager, EquipmentSlot.LEGS, poseStack, buffer, packedLight, limbSwing, limbSwingAmount, partialTick, ageInTicks, netHeadYaw, headPitch);
            renderArmorSlot(villager, EquipmentSlot.FEET, poseStack, buffer, packedLight, limbSwing, limbSwingAmount, partialTick, ageInTicks, netHeadYaw, headPitch);
            renderArmorSlot(villager, EquipmentSlot.HEAD, poseStack, buffer, packedLight, limbSwing, limbSwingAmount, partialTick, ageInTicks, netHeadYaw, headPitch);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerHumanoidArmorLayer render failed (soft): {}", t.toString());
        }
    }

    private void renderArmorSlot(Villager vill,
                                 EquipmentSlot slot,
                                 PoseStack poseStack,
                                 MultiBufferSource buffer,
                                 int packedLight,
                                 float limbSwing,
                                 float limbSwingAmount,
                                 float partialTick,
                                 float ageInTicks,
                                 float netHeadYaw,
                                 float headPitch) {
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
        HumanoidModel<?> baseHumanoidModel = innerTexture ? innerModel : outerModel;

        // IMPORTANT: EntityModel.young defaults to TRUE.
        // If we don't set this explicitly, armor models rendered via renderToBuffer will be treated as "baby"
        // and get scaled/translated (looks like tiny armor attached around hips/knees).
        try {
            baseHumanoidModel.young = vill.isBaby();
            baseHumanoidModel.riding = vill.isPassenger();
        } catch (Throwable ignored) {}

        setPartVisibility(baseHumanoidModel, slot);
        copyVillagerPoseIntoHumanoid(this.getParentModel(), baseHumanoidModel);

        net.minecraft.client.model.Model armorModel;
        try {
            armorModel = ClientHooks.getArmorModel(vill, stack, slot, (HumanoidModel) baseHumanoidModel);
        } catch (Throwable ignored) {
            armorModel = baseHumanoidModel;
        }

        boolean useDriverArms = false;
        try {
            if (vill instanceof VillagerOverhaulRenderAccess acc) {
                useDriverArms = VillagerRenderFlags.renderCustomArms(acc.ezvr$getRenderFlags());
            }
        } catch (Throwable ignored) {
            useDriverArms = false;
        }

        HumanoidModel<?> humanoidArmorModel = null;
        if (armorModel instanceof HumanoidModel<?> hm) {
            humanoidArmorModel = hm;

            // Critical for modded armor: many armor models default to all parts hidden.
            try { setPartVisibility(hm, slot); } catch (Throwable ignored) {}

            // Preserve modded model pivots: copy rotations only (not x/y/z).
            try {
                hm.young = vill.isBaby();
                hm.riding = vill.isPassenger();
            } catch (Throwable ignored) {}

            // Seed head/body/legs from our base model. (Arms are handled AFTER setupModelAnimations below.)
            try {
                if (baseHumanoidModel instanceof HumanoidModel<?> base) {
                    if (base.head != null && hm.head != null) copyPartRot(base.head, hm.head);
                    if (hm.hat != null && hm.head != null) copyPartRot(hm.head, hm.hat);
                    if (base.body != null && hm.body != null) copyPartRot(base.body, hm.body);
                    if (base.rightLeg != null && hm.rightLeg != null) copyPartRot(base.rightLeg, hm.rightLeg);
                    if (base.leftLeg != null && hm.leftLeg != null) copyPartRot(base.leftLeg, hm.leftLeg);
                }
            } catch (Throwable ignored) {}
        }

        // Ensure any replacement model also has correct young/riding state (covers non-humanoid Model impls too).
        try {
            if (armorModel instanceof net.minecraft.client.model.EntityModel<?> em) {
                em.young = vill.isBaby();
                em.riding = vill.isPassenger();
            }
        } catch (Throwable ignored) {}

        IClientItemExtensions extensions = null;
        try { extensions = IClientItemExtensions.of(stack); } catch (Throwable ignored) { extensions = null; }
        try {
            if (extensions != null) {
                extensions.setupModelAnimations(vill, stack, slot, armorModel, limbSwing, limbSwingAmount, partialTick, ageInTicks, netHeadYaw, headPitch);
            }
        } catch (Throwable ignored) {}

        // IMPORTANT: Many mods set/overwrite arm part rotations inside setupModelAnimations.
        // Apply our arm override AFTER that so chestplate arm/shoulder geometry follows the villager arms.
        try {
            HumanoidModel<?> hm = humanoidArmorModel;
            if (hm != null && slot == EquipmentSlot.CHEST) {
                if (useDriverArms && driverHumanoid != null) {
                    // Prefer the actual custom-arms model rotations (these already reflect the driver used by the arms layer,
                    // which may be a PlayerModel instead of the injected driverHumanoid).
                    if (armsModel != null) {
                        armsModel.copyArmRotationsTo(hm.rightArm, hm.leftArm);
                    } else {
                        if (driverHumanoid.rightArm != null && hm.rightArm != null) copyPartRot(driverHumanoid.rightArm, hm.rightArm);
                        if (driverHumanoid.leftArm != null && hm.leftArm != null) copyPartRot(driverHumanoid.leftArm, hm.leftArm);
                    }
                } else {
                    // If we are not rendering custom arms, hide armor arms to avoid "hanging arms" during crossed-arms pose.
                    if (hm.rightArm != null) hm.rightArm.visible = false;
                    if (hm.leftArm != null) hm.leftArm.visible = false;
                }
            }
        } catch (Throwable ignored) {}

        ArmorMaterial material;
        try {
            material = armor.getMaterial().value();
        } catch (Throwable ignored) {
            return;
        }

        int overlay = OverlayTexture.NO_OVERLAY;

        int fallbackColor = 0xA06540;
        try { if (extensions != null) fallbackColor = extensions.getDefaultDyeColor(stack); } catch (Throwable ignored) {}

        ezvr$debugOnce(
                "armor_begin:" + String.valueOf(stack.getItem()) + ":" + slot,
                "[VillagerOverhaul] Armor render begin: item={}, slot={}, modelClass={}, baseModelClass={}, extClass={}, materialLayers={}",
                String.valueOf(stack.getItem()),
                String.valueOf(slot),
                (armorModel == null ? "null" : armorModel.getClass().getName()),
                (baseHumanoidModel == null ? "null" : baseHumanoidModel.getClass().getName()),
                (extensions == null ? "null" : extensions.getClass().getName()),
                (material == null || material.layers() == null ? -1 : material.layers().size())
        );

        // --- SLOT LEVEL TRANSFORM ---
        boolean doTx = ENABLE_ARMOR_TRANSFORMS;
        Transform slotTx = doTx ? transformForSlot(slot) : Transform.IDENTITY;

        poseStack.pushPose();
        try {
            if (doTx && !slotTx.isIdentity()) {
                slotTx.apply(poseStack);
            }

            List<ArmorMaterial.Layer> layers = material.layers();
            if (layers == null || layers.isEmpty()) {
                // Some mods don't populate ArmorMaterial.layers() (or rely on legacy armor texture hooks).
                // Vanilla HumanoidArmorLayer would render nothing in this case, but players still often work because
                // mods override Item#getArmorTexture(). We synthesize a single layer so ClientHooks.getArmorTexture()
                // can call into mod code and return the right texture.
                ResourceLocation syntheticAsset = deriveSyntheticArmorAsset(stack, slot);
                ArmorMaterial.Layer synthetic = new ArmorMaterial.Layer(syntheticAsset);
                layers = List.of(synthetic);

                ezvr$debugOnce(
                        "armor_layers_empty:" + String.valueOf(stack.getItem()) + ":" + slot,
                        "[VillagerOverhaul] ArmorMaterial.layers() was empty; using synthetic layer asset={} (item={}, slot={})",
                        String.valueOf(syntheticAsset),
                        String.valueOf(stack.getItem()),
                        String.valueOf(slot)
                );
            }
            for (int layerIdx = 0; layerIdx < layers.size(); layerIdx++) {
                ArmorMaterial.Layer layer = layers.get(layerIdx);
                if (layer == null) continue;

                int packedColor = 0xFFFFFFFF;
                try {
                    if (extensions != null) {
                        packedColor = extensions.getArmorLayerTintColor(stack, vill, layer, layerIdx, fallbackColor);
                    } else if (layer.dyeable()) {
                        int usedRGB = fallbackColor;
                        try {
                            var dyed = stack.get(DataComponents.DYED_COLOR);
                            if (dyed != null) usedRGB = dyed.rgb();
                        } catch (Throwable ignored) {}
                        packedColor = FastColor.ARGB32.color(255,
                                FastColor.ARGB32.red(usedRGB),
                                FastColor.ARGB32.green(usedRGB),
                                FastColor.ARGB32.blue(usedRGB));
                    }
                } catch (Throwable ignored) {}

                // Some mods return 0 for non-player entities (which makes armor invisible).
                // Keep vanilla behavior for players, but for villagers fallback to a sane tint so it renders.
                if (packedColor == 0) {
                    int fallbackPacked = 0xFFFFFFFF;
                    try {
                        if (layer.dyeable()) {
                            int usedRGB = fallbackColor;
                            try {
                                var dyed = stack.get(DataComponents.DYED_COLOR);
                                if (dyed != null) usedRGB = dyed.rgb();
                            } catch (Throwable ignored) {}
                            fallbackPacked = FastColor.ARGB32.color(255,
                                    FastColor.ARGB32.red(usedRGB),
                                    FastColor.ARGB32.green(usedRGB),
                                    FastColor.ARGB32.blue(usedRGB));
                        }
                    } catch (Throwable ignored) {}

                    ezvr$debugOnce(
                            "armor_tint0:" + String.valueOf(stack.getItem()) + ":" + slot + ":" + layerIdx,
                            "[VillagerOverhaul] Armor tint was 0; forcing fallback tint so it renders (item={}, slot={}, layerIdx={}, dyeable={})",
                            String.valueOf(stack.getItem()),
                            String.valueOf(slot),
                            layerIdx,
                            (safeDyeable(layer))
                    );
                    packedColor = fallbackPacked;
                }

                ResourceLocation tex = null;
                try { tex = ClientHooks.getArmorTexture(vill, stack, layer, innerTexture, slot); } catch (Throwable ignored) { tex = null; }
                if (tex == null) {
                    try { tex = layer.texture(innerTexture); } catch (Throwable ignored) { tex = null; }
                }
                if (tex == null) continue;

                ezvr$debugOnce(
                        "armor_layer:" + String.valueOf(stack.getItem()) + ":" + slot + ":" + layerIdx,
                        "[VillagerOverhaul] Armor layer: item={}, slot={}, layerIdx={}, tex={}, packedColor={}",
                        String.valueOf(stack.getItem()),
                        String.valueOf(slot),
                        layerIdx,
                        String.valueOf(tex),
                        String.format("0x%08X", packedColor)
                );

                try {
                    var vc = buffer.getBuffer(RenderType.armorCutoutNoCull(tex));

                    // Use our per-part transforms for ANY humanoid armor model so villager-fit scaling is consistent.
                    // Most modded armor models attach extra bits as children of body/arms/head/legs, so they render too.
                    if (armorModel instanceof HumanoidModel<?> hm) {
                        renderVisiblePartsWithPerPartTransforms(slot, hm, poseStack, vc, packedLight, overlay, packedColor);
                    } else {
                        armorModel.renderToBuffer(poseStack, vc, packedLight, overlay, packedColor);
                    }

                } catch (Throwable t) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] Armor layer render failed tex={} item={} (soft): {}",
                            tex, stack.getItem(), t.toString());
                }
            }

            // Trim (vanilla parity)
            try {
                ArmorTrim trim = stack.get(DataComponents.TRIM);
                if (trim != null) {
                    ezvr$debugOnce(
                            "armor_trim:" + String.valueOf(stack.getItem()) + ":" + slot,
                            "[VillagerOverhaul] Armor trim present: item={}, slot={}, pattern={}",
                            String.valueOf(stack.getItem()),
                            String.valueOf(slot),
                            String.valueOf(trim.pattern())
                    );
                    renderTrim(armor.getMaterial(), trim, armorModel, poseStack, buffer, packedLight, innerTexture);
                }
            } catch (Throwable ignored) {}

            // Glint (vanilla parity)
            try {
                if (stack.hasFoil()) {
                    ezvr$debugOnce(
                            "armor_glint:" + String.valueOf(stack.getItem()) + ":" + slot,
                            "[VillagerOverhaul] Armor glint present: item={}, slot={}",
                            String.valueOf(stack.getItem()),
                            String.valueOf(slot)
                    );
                    var vc = buffer.getBuffer(RenderType.armorEntityGlint());
                    if (armorModel instanceof HumanoidModel<?> hm) {
                        renderVisiblePartsWithPerPartTransforms(slot, hm, poseStack, vc, packedLight, overlay, 0xFFFFFFFF);
                    } else {
                        armorModel.renderToBuffer(poseStack, vc, packedLight, overlay, 0xFFFFFFFF);
                    }
                }
            } catch (Throwable ignored) {}
        } finally {
            poseStack.popPose();
        }
    }

    private void renderTrim(net.minecraft.core.Holder<ArmorMaterial> armorMaterialHolder,
                            ArmorTrim trim,
                            net.minecraft.client.model.Model armorModel,
                            PoseStack poseStack,
                            MultiBufferSource buffer,
                            int packedLight,
                            boolean innerTexture) {
        try {
            if (trim == null || armorMaterialHolder == null || armorModel == null) return;

            if (this.armorTrimAtlas == null) {
                try {
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    var mm = mc == null ? null : mc.getModelManager();
                    this.armorTrimAtlas = mm == null ? null : mm.getAtlas(Sheets.ARMOR_TRIMS_SHEET);
                } catch (Throwable ignored) {
                    this.armorTrimAtlas = null;
                }
            }
            if (this.armorTrimAtlas == null) return;

            TextureAtlasSprite sprite = this.armorTrimAtlas.getSprite(innerTexture ? trim.innerTexture(armorMaterialHolder) : trim.outerTexture(armorMaterialHolder));
            var vc = sprite.wrap(buffer.getBuffer(Sheets.armorTrimsSheet(trim.pattern().value().decal())));
            armorModel.renderToBuffer(poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        } catch (Throwable ignored) {}
    }

    private static ResourceLocation deriveSyntheticArmorAsset(ItemStack stack, EquipmentSlot slot) {
        try {
            ResourceLocation id = null;
            try { id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()); } catch (Throwable ignored) { id = null; }
            if (id == null) return ResourceLocation.withDefaultNamespace("empty");

            String p = id.getPath();
            if (slot != null) {
                // Common suffixes across mods
                p = switch (slot) {
                    case HEAD -> stripEnd(p, "_helmet", "_head", "_cap", "_hood");
                    case CHEST -> stripEnd(p, "_chestplate", "_chest", "_tunic");
                    case LEGS -> stripEnd(p, "_leggings", "_legs", "_pants");
                    case FEET -> stripEnd(p, "_boots", "_feet", "_shoes");
                    default -> p;
                };
            }
            if (p.isBlank()) p = "empty";
            return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), p);
        } catch (Throwable ignored) {
            return ResourceLocation.withDefaultNamespace("empty");
        }
    }

    private static String stripEnd(String s, String... suffixes) {
        try {
            if (s == null) return "";
            for (String suf : suffixes) {
                if (suf == null || suf.isEmpty()) continue;
                if (s.endsWith(suf)) return s.substring(0, s.length() - suf.length());
            }
            return s;
        } catch (Throwable ignored) {
            return s == null ? "" : s;
        }
    }

    private static boolean safeDyeable(Object layer) {
        try {
            if (layer instanceof ArmorMaterial.Layer l) return l.dyeable();
        } catch (Throwable ignored) {}
        return false;
    }

    private static void ezvr$debugOnce(String key, String fmt, Object... args) {
        try {
            if (!VillagerOverhaul.LOG().isDebugEnabled()) return;
            if (key == null) return;
            if (!EZVR_ARMOR_INFO_LOG_ONCE.add(key)) return;
            VillagerOverhaul.LOG().debug(fmt, args);
        } catch (Throwable ignored) {}
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

        // IMPORTANT:
        // Many modded armor models rely on specific part pivot positions/hierarchies.
        // Copying ModelPart translations (x/y/z) from VillagerModel into a HumanoidModel can shift the entire
        // armor set (e.g. helmets ending up at hips) if the armor model uses a different structure.
        // To preserve mod compatibility, copy rotations only.
        if (vHead != null && humanoid.head != null) copyPartRot(vHead, humanoid.head);
        if (humanoid.hat != null && humanoid.head != null) copyPartRot(humanoid.head, humanoid.hat);

        if (vBody != null && humanoid.body != null) copyPartRot(vBody, humanoid.body);
        if (vRA != null && humanoid.rightArm != null) copyPartRot(vRA, humanoid.rightArm);
        if (vLA != null && humanoid.leftArm != null) copyPartRot(vLA, humanoid.leftArm);
        if (vRL != null && humanoid.rightLeg != null) copyPartRot(vRL, humanoid.rightLeg);
        if (vLL != null && humanoid.leftLeg != null) copyPartRot(vLL, humanoid.leftLeg);
    }

    private static void copyPartRot(ModelPart from, ModelPart to) {
        if (from == null || to == null) return;
        try {
            to.xRot = from.xRot;
            to.yRot = from.yRot;
            to.zRot = from.zRot;
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
