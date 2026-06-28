package org.z2six.villageroverhaul.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.Constants;
import org.z2six.villageroverhaul.render.VillagerRenderFlags;
import org.z2six.villageroverhaul.server.VillagerFactionService;
import org.z2six.villageroverhaul.server.VillagerGenderService;

import java.util.Set;

public final class DwarfVillagerLayer extends RenderLayer<Villager, net.minecraft.client.model.VillagerModel<Villager>> {
    private static final ResourceLocation MALE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/entity/dwarf/dwarf_male.png");
    private static final ResourceLocation FEMALE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/entity/dwarf/dwarf_female.png");
    private static final Set<String> DWARF_PROFESSION_TEXTURES = Set.of(
            "armorer",
            "butcher",
            "cartographer",
            "cleric",
            "farmer",
            "fisherman",
            "fletcher",
            "leatherworker",
            "librarian",
            "mason",
            "shepherd",
            "toolsmith",
            "weaponsmith"
    );

    private final DwarfVillagerModel model;
    private final VillagerHumanoidArmsLayer armPoseDriver;

    public DwarfVillagerLayer(RenderLayerParent<Villager, net.minecraft.client.model.VillagerModel<Villager>> parent,
                             DwarfVillagerModel model,
                             VillagerHumanoidArmsLayer armPoseDriver) {
        super(parent);
        this.model = model;
        this.armPoseDriver = armPoseDriver;
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
        if (!VillagerFactionService.isDwarf(villager) || model == null) return;

        byte flags = villager instanceof VillagerOverhaulRenderAccess acc
                ? acc.ezvr$getRenderFlags()
                : VillagerRenderFlags.defaultFlags();
        byte genderId = genderId(villager);

        model.setupAnim(villager, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        if (armPoseDriver != null) {
            armPoseDriver.updateDwarfArmPose(villager, limbSwing, limbSwingAmount, partialTick, ageInTicks, netHeadYaw, headPitch, model);
        }

        boolean renderCrossedArms = true;
        boolean hideBodyShape = hasChestpiece(villager) && ArmorEditorRuntimeSettings.hideDwarfBodyShapeWithChest();
        var baseConsumer = buffer.getBuffer(RenderType.entityCutoutNoCull(baseTexture(genderId)));
        model.renderBase(poseStack, baseConsumer, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF, renderCrossedArms, genderId, hideBodyShape);

        renderOverlays(poseStack, buffer, packedLight, villager, flags, genderId, hideBodyShape);
    }

    private static ResourceLocation baseTexture(byte genderId) {
        return genderId == (byte) VillagerGenderService.GENDER_FEMALE ? FEMALE_TEXTURE : MALE_TEXTURE;
    }

    private void renderOverlays(PoseStack poseStack, MultiBufferSource buffer, int packedLight, Villager villager, byte flags, byte genderId, boolean hideBodyShape) {
        if (villager == null || !VillagerRenderFlags.renderBodywear(flags)) return;

        ResourceLocation profession = professionTexture(villager);
        if (profession != null) {
            var vc = buffer.getBuffer(RenderType.entityCutoutNoCull(profession));
            boolean renderCrossedArms = true;
            model.renderOverlay(poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF, renderCrossedArms, genderId, hideBodyShape);
        }
    }

    private static boolean hasChestpiece(Villager villager) {
        try {
            ItemStack stack = villager == null ? ItemStack.EMPTY : villager.getItemBySlot(EquipmentSlot.CHEST);
            return stack != null && !stack.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static byte genderId(Villager villager) {
        try {
            if (villager instanceof VillagerOverhaulRenderAccess acc) {
                return acc.ezvr$getGenderId();
            }
        } catch (Throwable ignored) {}
        return (byte) VillagerGenderService.GENDER_UNKNOWN;
    }

    private static ResourceLocation professionTexture(Villager villager) {
        try {
            VillagerProfession profession = villager.getVillagerData().getProfession();
            if (profession == null || profession == VillagerProfession.NONE) return null;
            ResourceLocation id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession);
            if (id == null) return null;
            String professionName = id.getPath();
            if (!DWARF_PROFESSION_TEXTURES.contains(professionName)) return null;
            return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/entity/dwarf/professions/" + professionName + ".png");
        } catch (Throwable ignored) {
            return null;
        }
    }
}
