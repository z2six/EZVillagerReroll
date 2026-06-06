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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    // Built-in armor fit defaults now live in ArmorEditorSettings.defaults().

    // =========================================================================================

    private final HumanoidModel<?> innerModel;
    private final HumanoidModel<?> outerModel;
    private final HumanoidModel<LivingEntity> driverHumanoid;
    private final VillagerCombatArmsModel armsModel;

    private TextureAtlas armorTrimAtlas;

    private static final Set<String> EZVR_ARMOR_INFO_LOG_ONCE =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<Class<?>, Method> EZVR_EXTENDED_MATERIAL_METHODS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> EZVR_GET_PIECES_METHODS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> EZVR_PIECE_RENDER_METHODS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, ResourceLocation> EZVR_ARMOR_TEXTURE_REDIRECT_CACHE = new ConcurrentHashMap<>();

    // Cached accessors for villager model parts (reflection, because mappings drift)
    private final PartAccess villagerParts;

    // Cached ModelPart#render overloads (1.21+ variants exist)
    private static volatile Method MODEL_PART_RENDER_5; // (PoseStack, VertexConsumer, int, int, int)
    private static volatile Method MODEL_PART_RENDER_4; // (PoseStack, VertexConsumer, int, int)
    private static volatile Method MODEL_PART_COMPILE_5; // (PoseStack.Pose, VertexConsumer, int, int, int)
    private static volatile Field MODEL_PART_CHILDREN_FIELD;

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
        applyPassengerLegPoseIfNeeded(vill, baseHumanoidModel);

        boolean useDriverArms = false;
        try {
            if (vill instanceof VillagerOverhaulRenderAccess acc) {
                useDriverArms = VillagerRenderFlags.renderCustomArms(acc.ezvr$getRenderFlags());
            }
        } catch (Throwable ignored) {
            useDriverArms = false;
        }
        try { applyDriverArmsToHumanoidModel(baseHumanoidModel, useDriverArms); } catch (Throwable ignored) {}

        boolean doTx = ENABLE_ARMOR_TRANSFORMS;
        ArmorEditorTransform slotTx = doTx ? transformForSlot(slot) : ArmorEditorTransform.IDENTITY;

        // Some armor mods replace vanilla HumanoidArmorLayer rendering with their own slot-piece pipeline.
        // Our villager layer is not HumanoidArmorLayer, so those mixins do not see us. If an item exposes that
        // generic "extended material -> pieces -> render(...)" shape, let the mod's pieces render themselves.
        poseStack.pushPose();
        try {
            if (renderExtendedArmorPiecesIfPresent(vill, stack, slot, poseStack, buffer, packedLight, partialTick, baseHumanoidModel, doTx)) {
                return;
            }
        } finally {
            poseStack.popPose();
        }

        net.minecraft.client.model.Model armorModel;
        try {
            armorModel = ClientHooks.getArmorModel(vill, stack, slot, (HumanoidModel) baseHumanoidModel);
        } catch (Throwable ignored) {
            armorModel = baseHumanoidModel;
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
                applyPassengerLegPoseIfNeeded(vill, hm);
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

        try {
            if (humanoidArmorModel != null) {
                applyPassengerLegPoseIfNeeded(vill, humanoidArmorModel);
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

        try {
            if (humanoidArmorModel != null) {
                ArmorEditorRenderContext.applyToHumanoidModel(humanoidArmorModel);
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

        poseStack.pushPose();
        try {
            boolean localHumanoidTransforms = armorModel instanceof HumanoidModel<?>;
            if (!localHumanoidTransforms && doTx && slotTx != null && !slotTx.isIdentity()) {
                applyTransform(poseStack, slotTx);
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
                tex = resolveExistingArmorTexture(tex, stack, slot, innerTexture);
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
                    renderTrim(armor.getMaterial(), trim, slot, armorModel, poseStack, buffer, packedLight, innerTexture);
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
                            EquipmentSlot slot,
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
            if (armorModel instanceof HumanoidModel<?> hm) {
                renderVisiblePartsWithPerPartTransforms(slot, hm, poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            } else {
                armorModel.renderToBuffer(poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            }
        } catch (Throwable ignored) {}
    }

    private boolean renderExtendedArmorPiecesIfPresent(Villager vill,
                                                       ItemStack stack,
                                                       EquipmentSlot slot,
                                                       PoseStack poseStack,
                                                       MultiBufferSource buffer,
                                                       int packedLight,
                                                       float partialTick,
                                                       HumanoidModel<?> baseHumanoidModel,
                                                       boolean applyEditorTransforms) {
        try {
            if (vill == null || stack == null || stack.isEmpty() || slot == null || baseHumanoidModel == null) return false;

            Object item = stack.getItem();
            Method getExtendedMaterial = findNoArgMethodCached(EZVR_EXTENDED_MATERIAL_METHODS, item.getClass(), "getExtendedMaterial");
            if (getExtendedMaterial == null) return false;

            Object extendedMaterial = getExtendedMaterial.invoke(item);
            if (extendedMaterial == null) return false;

            Method getPieces = findOneArgMethodCached(EZVR_GET_PIECES_METHODS, extendedMaterial.getClass(), "getPieces", EquipmentSlot.class);
            if (getPieces == null) return false;

            Object piecesObj = getPieces.invoke(extendedMaterial, slot);
            if (!(piecesObj instanceof Iterable<?> pieces)) return false;

            boolean renderedAny = false;
            ModelPartTransformScope transformScope = applyEditorTransforms
                    ? bakeEditorTransformsIntoHumanoidModel(baseHumanoidModel, slot)
                    : ModelPartTransformScope.EMPTY;
            try {
                for (Object piece : pieces) {
                    if (piece == null) continue;
                    Method render = findPieceRenderMethod(piece.getClass());
                    if (render == null) continue;
                    try {
                        render.invoke(piece, poseStack, buffer, packedLight, vill, stack, partialTick, slot, baseHumanoidModel);
                        renderedAny = true;
                    } catch (Throwable t) {
                        ezvr$debugOnce(
                                "armor_extended_piece_fail:" + String.valueOf(stack.getItem()) + ":" + slot + ":" + piece.getClass().getName(),
                                "[VillagerOverhaul] Extended armor piece render failed (item={}, slot={}, piece={}, soft={})",
                                String.valueOf(stack.getItem()),
                                String.valueOf(slot),
                                piece.getClass().getName(),
                                t.toString()
                        );
                    }
                }
            } finally {
                transformScope.restore();
            }

            if (renderedAny) {
                ezvr$debugOnce(
                        "armor_extended_pieces:" + String.valueOf(stack.getItem()) + ":" + slot,
                        "[VillagerOverhaul] Rendered extended armor pieces for item={} slot={} materialClass={}",
                        String.valueOf(stack.getItem()),
                        String.valueOf(slot),
                        extendedMaterial.getClass().getName()
                );
            }
            return renderedAny;
        } catch (Throwable t) {
            ezvr$debugOnce(
                    "armor_extended_piece_path_fail:" + String.valueOf(stack == null ? "null" : stack.getItem()) + ":" + slot,
                    "[VillagerOverhaul] Extended armor piece path failed (item={}, slot={}, soft={})",
                    String.valueOf(stack == null ? "null" : stack.getItem()),
                    String.valueOf(slot),
                    t.toString()
            );
            return false;
        }
    }

    private void applyDriverArmsToHumanoidModel(HumanoidModel<?> model, boolean useDriverArms) {
        try {
            if (model == null || !useDriverArms) return;
            if (model.rightArm == null && model.leftArm == null) return;
            if (armsModel != null) {
                armsModel.copyArmRotationsTo(model.rightArm, model.leftArm);
                return;
            }
            if (driverHumanoid != null) {
                if (driverHumanoid.rightArm != null && model.rightArm != null) copyPartRot(driverHumanoid.rightArm, model.rightArm);
                if (driverHumanoid.leftArm != null && model.leftArm != null) copyPartRot(driverHumanoid.leftArm, model.leftArm);
            }
        } catch (Throwable ignored) {}
    }

    private static ModelPartTransformScope bakeEditorTransformsIntoHumanoidModel(HumanoidModel<?> model, EquipmentSlot slot) {
        if (!ENABLE_ARMOR_TRANSFORMS || model == null || slot == null) return ModelPartTransformScope.EMPTY;

        List<ModelPartState> states = new ArrayList<>(7);
        switch (slot) {
            case HEAD -> {
                bakeEditorTransformIntoPart(model.head, slot, PartKind.HEAD, states);
                bakeEditorTransformIntoPart(model.hat, slot, PartKind.HAT, states);
            }
            case CHEST -> {
                bakeEditorTransformIntoPart(model.body, slot, PartKind.BODY, states);
                bakeEditorTransformIntoPart(model.rightArm, slot, PartKind.RIGHT_ARM, states);
                bakeEditorTransformIntoPart(model.leftArm, slot, PartKind.LEFT_ARM, states);
            }
            case LEGS -> {
                bakeEditorTransformIntoPart(model.body, slot, PartKind.BODY, states);
                bakeEditorTransformIntoPart(model.rightLeg, slot, PartKind.RIGHT_LEG, states);
                bakeEditorTransformIntoPart(model.leftLeg, slot, PartKind.LEFT_LEG, states);
            }
            case FEET -> {
                bakeEditorTransformIntoPart(model.rightLeg, slot, PartKind.RIGHT_LEG, states);
                bakeEditorTransformIntoPart(model.leftLeg, slot, PartKind.LEFT_LEG, states);
            }
            default -> {}
        }

        if (states.isEmpty()) return ModelPartTransformScope.EMPTY;
        return new ModelPartTransformScope(states);
    }

    private static void bakeEditorTransformIntoPart(ModelPart part,
                                                    EquipmentSlot slot,
                                                    PartKind kind,
                                                    List<ModelPartState> states) {
        if (part == null || states == null) return;

        ArmorEditorTransform slotTx = transformForSlot(slot);
        ArmorEditorTransform partTx = partTransformFor(slot, kind);
        if (slotTx == null) slotTx = ArmorEditorTransform.IDENTITY;
        if (partTx == null) partTx = ArmorEditorTransform.IDENTITY;
        if (slotTx.isIdentity() && partTx.isIdentity()) return;

        states.add(new ModelPartState(part));

        part.x += (slotTx.x() + partTx.x()) * 16.0f;
        part.y += (slotTx.y() + partTx.y()) * 16.0f;
        part.z += (slotTx.z() + partTx.z()) * 16.0f;
        part.xScale *= slotTx.sx() * partTx.sx();
        part.yScale *= slotTx.sy() * partTx.sy();
        part.zScale *= slotTx.sz() * partTx.sz();
    }

    private static Method findNoArgMethodCached(Map<Class<?>, Method> cache, Class<?> type, String name) {
        try {
            if (cache == null || type == null || name == null || name.isBlank()) return null;
            Method cached = cache.get(type);
            if (cached != null) return cached;
            Method found = findMethod(type, name, 0, null);
            if (found != null) cache.put(type, found);
            return found;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findOneArgMethodCached(Map<Class<?>, Method> cache, Class<?> type, String name, Class<?> argType) {
        try {
            if (cache == null || type == null || name == null || name.isBlank() || argType == null) return null;
            Method cached = cache.get(type);
            if (cached != null) return cached;
            Method found = findMethod(type, name, 1, new Class<?>[]{argType});
            if (found != null) cache.put(type, found);
            return found;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findPieceRenderMethod(Class<?> type) {
        try {
            if (type == null) return null;
            Method cached = EZVR_PIECE_RENDER_METHODS.get(type);
            if (cached != null) return cached;

            for (Method m : type.getMethods()) {
                if (!"render".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 8) continue;
                if (!PoseStack.class.isAssignableFrom(p[0])) continue;
                if (!MultiBufferSource.class.isAssignableFrom(p[1])) continue;
                if (p[2] != int.class) continue;
                if (!LivingEntity.class.isAssignableFrom(p[3])) continue;
                if (!ItemStack.class.isAssignableFrom(p[4])) continue;
                if (p[5] != float.class) continue;
                if (!EquipmentSlot.class.isAssignableFrom(p[6])) continue;
                if (!HumanoidModel.class.isAssignableFrom(p[7])) continue;
                try { m.setAccessible(true); } catch (Throwable ignored) {}
                EZVR_PIECE_RENDER_METHODS.put(type, m);
                return m;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Method findMethod(Class<?> type, String name, int arity, Class<?>[] argTypes) {
        try {
            List<Method> candidates = new ArrayList<>();
            Collections.addAll(candidates, type.getMethods());
            Collections.addAll(candidates, type.getDeclaredMethods());
            for (Method m : candidates) {
                if (m == null || !name.equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != arity) continue;
                if (argTypes != null) {
                    boolean ok = true;
                    for (int i = 0; i < argTypes.length; i++) {
                        if (argTypes[i] == null) continue;
                        if (!p[i].isAssignableFrom(argTypes[i]) && !argTypes[i].isAssignableFrom(p[i])) {
                            ok = false;
                            break;
                        }
                    }
                    if (!ok) continue;
                }
                try { m.setAccessible(true); } catch (Throwable ignored) {}
                return m;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static ResourceLocation resolveExistingArmorTexture(ResourceLocation tex, ItemStack stack, EquipmentSlot slot, boolean innerTexture) {
        try {
            if (tex == null) return null;
            ResourceLocation cached = EZVR_ARMOR_TEXTURE_REDIRECT_CACHE.get(tex);
            if (cached != null) return cached;
            if (resourceExists(tex)) {
                EZVR_ARMOR_TEXTURE_REDIRECT_CACHE.put(tex, tex);
                return tex;
            }

            List<ResourceLocation> candidates = fallbackArmorTextureCandidates(tex, stack, slot, innerTexture);
            for (ResourceLocation candidate : candidates) {
                if (candidate == null || candidate.equals(tex)) continue;
                if (resourceExists(candidate)) {
                    EZVR_ARMOR_TEXTURE_REDIRECT_CACHE.put(tex, candidate);
                    ezvr$debugOnce(
                            "armor_texture_redirect:" + tex,
                            "[VillagerOverhaul] Armor texture missing; redirected {} -> {}",
                            String.valueOf(tex),
                            String.valueOf(candidate)
                    );
                    return candidate;
                }
            }

            EZVR_ARMOR_TEXTURE_REDIRECT_CACHE.put(tex, tex);
            return tex;
        } catch (Throwable ignored) {
            return tex;
        }
    }

    private static List<ResourceLocation> fallbackArmorTextureCandidates(ResourceLocation tex, ItemStack stack, EquipmentSlot slot, boolean innerTexture) {
        List<ResourceLocation> out = new ArrayList<>();
        try {
            if (tex == null) return out;
            String path = tex.getPath();
            if (path != null && !path.isBlank() && !"minecraft".equals(tex.getNamespace())) {
                out.add(ResourceLocation.fromNamespaceAndPath("minecraft", path));
            }

            ResourceLocation syntheticAsset = deriveSyntheticArmorAsset(stack, slot);
            if (syntheticAsset != null) {
                String layerPath = "textures/models/armor/" + syntheticAsset.getPath() + "_layer_" + (innerTexture ? "2" : "1") + ".png";
                out.add(ResourceLocation.fromNamespaceAndPath(syntheticAsset.getNamespace(), layerPath));
                if (!"minecraft".equals(syntheticAsset.getNamespace())) {
                    out.add(ResourceLocation.fromNamespaceAndPath("minecraft", layerPath));
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static boolean resourceExists(ResourceLocation location) {
        try {
            if (location == null) return false;
            var mc = net.minecraft.client.Minecraft.getInstance();
            var rm = mc == null ? null : mc.getResourceManager();
            if (rm == null) return true;
            return rm.getResource(location).isPresent();
        } catch (Throwable ignored) {
            return true;
        }
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

    private static final class ModelPartTransformScope {
        static final ModelPartTransformScope EMPTY = new ModelPartTransformScope(List.of());

        private final List<ModelPartState> states;

        ModelPartTransformScope(List<ModelPartState> states) {
            this.states = states == null ? List.of() : states;
        }

        void restore() {
            for (int i = states.size() - 1; i >= 0; i--) {
                states.get(i).restore();
            }
        }
    }

    private static final class ModelPartState {
        private final ModelPart part;
        private final float x;
        private final float y;
        private final float z;
        private final float xScale;
        private final float yScale;
        private final float zScale;

        ModelPartState(ModelPart part) {
            this.part = part;
            this.x = part.x;
            this.y = part.y;
            this.z = part.z;
            this.xScale = part.xScale;
            this.yScale = part.yScale;
            this.zScale = part.zScale;
        }

        void restore() {
            if (part == null) return;
            part.x = x;
            part.y = y;
            part.z = z;
            part.xScale = xScale;
            part.yScale = yScale;
            part.zScale = zScale;
        }
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

    private static ArmorEditorTransform transformForSlot(EquipmentSlot slot) {
        if (slot == null) return ArmorEditorTransform.IDENTITY;
        return ArmorEditorRuntimeSettings.slotTransform(slot);
    }

    private static ArmorEditorTransform partTransformFor(EquipmentSlot slot, PartKind part) {
        if (slot == null || part == null) return ArmorEditorTransform.IDENTITY;
        return ArmorEditorRuntimeSettings.partTransform(slot, part.toEditorPart());
    }

    private enum PartKind {
        HEAD, HAT, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG;

        ArmorEditorSettings.PartKey toEditorPart() {
            return switch (this) {
                case HEAD -> ArmorEditorSettings.PartKey.HEAD;
                case HAT -> ArmorEditorSettings.PartKey.HAT;
                case BODY -> ArmorEditorSettings.PartKey.BODY;
                case RIGHT_ARM -> ArmorEditorSettings.PartKey.RIGHT_ARM;
                case LEFT_ARM -> ArmorEditorSettings.PartKey.LEFT_ARM;
                case RIGHT_LEG -> ArmorEditorSettings.PartKey.RIGHT_LEG;
                case LEFT_LEG -> ArmorEditorSettings.PartKey.LEFT_LEG;
            };
        }
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

        ArmorEditorTransform slotTx = (ENABLE_ARMOR_TRANSFORMS) ? transformForSlot(slot) : ArmorEditorTransform.IDENTITY;
        ArmorEditorTransform partTx = (ENABLE_ARMOR_TRANSFORMS) ? partTransformFor(slot, kind) : ArmorEditorTransform.IDENTITY;
        if (slotTx == null) slotTx = ArmorEditorTransform.IDENTITY;
        if (partTx == null) partTx = ArmorEditorTransform.IDENTITY;

        poseStack.pushPose();
        try {
            if (!renderModelPartWithLocalTransforms(part, poseStack, vc, light, overlay, packedColor, slotTx, partTx)) {
                if (!slotTx.isIdentity()) applyTransform(poseStack, slotTx);
                if (!partTx.isIdentity()) applyTransform(poseStack, partTx);
                invokeModelPartRender(part, poseStack, vc, light, overlay, packedColor);
            }
        } catch (Throwable ignored) {
        } finally {
            poseStack.popPose();
        }
    }

    private static boolean renderModelPartWithLocalTransforms(ModelPart part,
                                                              PoseStack poseStack,
                                                              com.mojang.blaze3d.vertex.VertexConsumer vc,
                                                              int light,
                                                              int overlay,
                                                              int packedColor,
                                                              ArmorEditorTransform slotTx,
                                                              ArmorEditorTransform partTx) {
        try {
            if (part == null || poseStack == null || vc == null || !part.visible) return true;
            if (MODEL_PART_COMPILE_5 == null) return false;

            poseStack.pushPose();
            try {
                part.translateAndRotate(poseStack);
                if (slotTx != null && !slotTx.isIdentity()) applyTransform(poseStack, slotTx);
                if (partTx != null && !partTx.isIdentity()) applyTransform(poseStack, partTx);

                if (!part.skipDraw) {
                    MODEL_PART_COMPILE_5.invoke(part, poseStack.last(), vc, light, overlay, packedColor);
                }

                Map<String, ModelPart> children = modelPartChildren(part);
                if (children != null && !children.isEmpty()) {
                    for (ModelPart child : children.values()) {
                        if (child != null) {
                            invokeModelPartRender(child, poseStack, vc, light, overlay, packedColor);
                        }
                    }
                }
            } finally {
                poseStack.popPose();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
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

        try {
            ArmorEditorRenderContext.applyToHumanoidModel(model);
        } catch (Throwable ignored) {}
    }

    private static void applyTransform(PoseStack ps, ArmorEditorTransform tx) {
        try {
            if (ps == null || tx == null) return;
            ps.translate(tx.x(), tx.y(), tx.z());
            ps.scale(tx.sx(), tx.sy(), tx.sz());
        } catch (Throwable ignored) {}
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

    private static void applyPassengerLegPoseIfNeeded(Villager villager, HumanoidModel<?> humanoid) {
        try {
            if (villager == null || humanoid == null || !villager.isPassenger()) return;
            applyPassengerLegPose(humanoid);
        } catch (Throwable ignored) {}
    }

    private static void applyPassengerLegPose(HumanoidModel<?> humanoid) {
        if (humanoid == null) return;
        try {
            if (humanoid.rightLeg != null) {
                humanoid.rightLeg.xRot = -1.4137167F;
                humanoid.rightLeg.yRot = (float) (Math.PI / 10.0D);
                humanoid.rightLeg.zRot = 0.07853982F;
            }
            if (humanoid.leftLeg != null) {
                humanoid.leftLeg.xRot = -1.4137167F;
                humanoid.leftLeg.yRot = (float) (-Math.PI / 10.0D);
                humanoid.leftLeg.zRot = -0.07853982F;
            }
        } catch (Throwable ignored) {}
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

    @SuppressWarnings("unchecked")
    private static Map<String, ModelPart> modelPartChildren(ModelPart part) {
        try {
            if (part == null || MODEL_PART_CHILDREN_FIELD == null) return null;
            Object value = MODEL_PART_CHILDREN_FIELD.get(part);
            if (value instanceof Map<?, ?> map) {
                return (Map<String, ModelPart>) map;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void warmupModelPartRenderMethods() {
        try {
            if (MODEL_PART_RENDER_5 == null || MODEL_PART_RENDER_4 == null) {
                for (Method m : ModelPart.class.getMethods()) {
                    if (!"render".equals(m.getName())) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 5) {
                        MODEL_PART_RENDER_5 = m;
                    } else if (p.length == 4) {
                        MODEL_PART_RENDER_4 = m;
                    }
                }
            }

            if (MODEL_PART_COMPILE_5 == null) {
                for (Method m : ModelPart.class.getDeclaredMethods()) {
                    if (!"compile".equals(m.getName())) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length != 5) continue;
                    try { m.setAccessible(true); } catch (Throwable ignored) {}
                    MODEL_PART_COMPILE_5 = m;
                    break;
                }
            }

            if (MODEL_PART_CHILDREN_FIELD == null) {
                for (Field f : ModelPart.class.getDeclaredFields()) {
                    if (!Map.class.isAssignableFrom(f.getType())) continue;
                    try { f.setAccessible(true); } catch (Throwable ignored) {}
                    MODEL_PART_CHILDREN_FIELD = f;
                    break;
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
