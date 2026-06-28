// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/mixin/villagerRendering/VillagerRenderStateMixin.java
package org.z2six.villageroverhaul.mixin.villagerRendering;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.api.VillagerOverhaulSwingAccess;
import org.z2six.villageroverhaul.render.VillagerRenderFlags;
import org.z2six.villageroverhaul.server.VillagerFactionService;
import org.z2six.villageroverhaul.server.VillagerGenderService;
import org.z2six.villageroverhaul.server.ai.VillagerBrain;

/**
 * Adds synced render decisions + a synced swing sequence counter to Villager.
 *
 * - Render flags are updated every server tick by VillagerBrain.
 * - Swing sequence is incremented by server combat code exactly when a swing happens.
 */
@Mixin(Villager.class)
public final class VillagerRenderStateMixin implements VillagerOverhaulRenderAccess, VillagerOverhaulSwingAccess {

    @Unique
    private static final EntityDataAccessor<Byte> EZVR_RENDER_FLAGS =
            SynchedEntityData.defineId(Villager.class, EntityDataSerializers.BYTE);

    @Unique
    private static final EntityDataAccessor<Integer> EZVR_SWING_SEQ =
            SynchedEntityData.defineId(Villager.class, EntityDataSerializers.INT);

    @Unique
    private static final EntityDataAccessor<Byte> EZVR_SWING_HAND =
            SynchedEntityData.defineId(Villager.class, EntityDataSerializers.BYTE);

    @Unique
    private static final EntityDataAccessor<ItemStack> EZVR_COMBAT_LOADOUT_MAIN =
            SynchedEntityData.defineId(Villager.class, EntityDataSerializers.ITEM_STACK);

    @Unique
    private static final EntityDataAccessor<ItemStack> EZVR_COMBAT_LOADOUT_OFF =
            SynchedEntityData.defineId(Villager.class, EntityDataSerializers.ITEM_STACK);

    @Unique
    private static final EntityDataAccessor<Byte> EZVR_RELEASE_ALPHA =
            SynchedEntityData.defineId(Villager.class, EntityDataSerializers.BYTE);

    @Unique
    private static final EntityDataAccessor<Byte> EZVR_FACTION =
            SynchedEntityData.defineId(Villager.class, EntityDataSerializers.BYTE);

    @Unique
    private static final EntityDataAccessor<Byte> EZVR_GENDER_ID =
            SynchedEntityData.defineId(Villager.class, EntityDataSerializers.BYTE);

    @Unique
    private static final String EZVR_NBT_FACTION = "VillagerOverhaulFaction";

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void ezvr$defineSynchedData(SynchedEntityData.Builder builder, CallbackInfo ci) {
        try {
            if (builder == null) return;

            builder.define(EZVR_RENDER_FLAGS, VillagerRenderFlags.defaultFlags());
            builder.define(EZVR_SWING_SEQ, 0);
            builder.define(EZVR_SWING_HAND, (byte) 0);
            builder.define(EZVR_COMBAT_LOADOUT_MAIN, ItemStack.EMPTY);
            builder.define(EZVR_COMBAT_LOADOUT_OFF, ItemStack.EMPTY);
            builder.define(EZVR_RELEASE_ALPHA, (byte) 255);
            builder.define(EZVR_FACTION, VillagerFactionService.FACTION_UNASSIGNED);
            builder.define(EZVR_GENDER_ID, (byte) VillagerGenderService.GENDER_UNKNOWN);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerRenderStateMixin#defineSynchedData failed (soft): {}", t.toString());
        }
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void ezvr$addAdditionalSaveData(CompoundTag tag, CallbackInfo ci) {
        try {
            if (tag == null) return;
            tag.putByte(EZVR_NBT_FACTION, ezvr$getFaction());
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerRenderStateMixin#addAdditionalSaveData failed (soft): {}", t.toString());
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void ezvr$readAdditionalSaveData(CompoundTag tag, CallbackInfo ci) {
        try {
            if (tag == null || !tag.contains(EZVR_NBT_FACTION)) {
                ezvr$setFaction(VillagerFactionService.FACTION_HUMAN);
                return;
            }
            ezvr$setFaction(tag.getByte(EZVR_NBT_FACTION));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerRenderStateMixin#readAdditionalSaveData failed (soft): {}", t.toString());
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void ezvr$tick(CallbackInfo ci) {
        try {
            Villager self = (Villager) (Object) this;
            if (self.level() == null) return;
            if (self.level().isClientSide()) return;

            // VillagerBrain is the ONLY authority that decides what the client should render.
            VillagerBrain.tickRenderDecisions(self);
            int genderId = VillagerGenderService.getGenderId(self);
            if (genderId == VillagerGenderService.GENDER_UNKNOWN) {
                genderId = VillagerGenderService.ensureAssigned(self);
            }
            if (genderId == VillagerGenderService.GENDER_MALE || genderId == VillagerGenderService.GENDER_FEMALE) {
                ezvr$setGenderId((byte) genderId);
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerRenderStateMixin#tick failed (soft): {}", t.toString());
        }
    }

    // -------------------------------------------------------------------------
    // Render flags (existing)
    // -------------------------------------------------------------------------

    @Override
    public byte ezvr$getRenderFlags() {
        try {
            Villager self = (Villager) (Object) this;
            if (self.getEntityData() == null) return VillagerRenderFlags.defaultFlags();
            Byte b = self.getEntityData().get(EZVR_RENDER_FLAGS);
            return b == null ? VillagerRenderFlags.defaultFlags() : b;
        } catch (Throwable ignored) {
            return VillagerRenderFlags.defaultFlags();
        }
    }

    @Override
    public void ezvr$setRenderFlags(byte flags) {
        try {
            Villager self = (Villager) (Object) this;
            if (self.getEntityData() == null) return;
            self.getEntityData().set(EZVR_RENDER_FLAGS, flags);
        } catch (Throwable ignored) {}
    }

    @Override
    public byte ezvr$getReleaseAlpha() {
        try {
            Villager self = (Villager) (Object) this;
            if (self.getEntityData() == null) return (byte) 255;
            Byte b = self.getEntityData().get(EZVR_RELEASE_ALPHA);
            return b == null ? (byte) 255 : b;
        } catch (Throwable ignored) {
            return (byte) 255;
        }
    }

    @Override
    public void ezvr$setReleaseAlpha(byte alpha) {
        try {
            Villager self = (Villager) (Object) this;
            if (self.getEntityData() == null) return;
            self.getEntityData().set(EZVR_RELEASE_ALPHA, alpha);
        } catch (Throwable ignored) {}
    }

    @Override
    public byte ezvr$getFaction() {
        try {
            Villager self = (Villager) (Object) this;
            if (self.getEntityData() == null) return VillagerFactionService.FACTION_UNASSIGNED;
            Byte b = self.getEntityData().get(EZVR_FACTION);
            return b == null ? VillagerFactionService.FACTION_UNASSIGNED : b;
        } catch (Throwable ignored) {
            return VillagerFactionService.FACTION_UNASSIGNED;
        }
    }

    @Override
    public void ezvr$setFaction(byte faction) {
        try {
            Villager self = (Villager) (Object) this;
            if (self.getEntityData() == null) return;
            self.getEntityData().set(EZVR_FACTION, faction);
        } catch (Throwable ignored) {}
    }

    @Override
    public byte ezvr$getGenderId() {
        try {
            Villager self = (Villager) (Object) this;
            if (self.getEntityData() == null) return (byte) VillagerGenderService.GENDER_UNKNOWN;
            Byte b = self.getEntityData().get(EZVR_GENDER_ID);
            return b == null ? (byte) VillagerGenderService.GENDER_UNKNOWN : b;
        } catch (Throwable ignored) {
            return (byte) VillagerGenderService.GENDER_UNKNOWN;
        }
    }

    @Override
    public void ezvr$setGenderId(byte genderId) {
        try {
            Villager self = (Villager) (Object) this;
            if (self.getEntityData() == null) return;
            self.getEntityData().set(EZVR_GENDER_ID, genderId);
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // Combat loadout (server -> client) for holstered rendering
    // -------------------------------------------------------------------------

    @Override
    public ItemStack ezvr$getCombatLoadoutMain() {
        try {
            Villager self = (Villager) (Object) this;
            if (self.getEntityData() == null) return ItemStack.EMPTY;
            ItemStack st = self.getEntityData().get(EZVR_COMBAT_LOADOUT_MAIN);
            return st == null ? ItemStack.EMPTY : st;
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    @Override
    public ItemStack ezvr$getCombatLoadoutOff() {
        try {
            Villager self = (Villager) (Object) this;
            if (self.getEntityData() == null) return ItemStack.EMPTY;
            ItemStack st = self.getEntityData().get(EZVR_COMBAT_LOADOUT_OFF);
            return st == null ? ItemStack.EMPTY : st;
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    @Override
    public void ezvr$setCombatLoadoutMain(ItemStack stack) {
        try {
            Villager self = (Villager) (Object) this;
            if (self.getEntityData() == null) return;
            self.getEntityData().set(EZVR_COMBAT_LOADOUT_MAIN, stack == null ? ItemStack.EMPTY : stack);
        } catch (Throwable ignored) {}
    }

    @Override
    public void ezvr$setCombatLoadoutOff(ItemStack stack) {
        try {
            Villager self = (Villager) (Object) this;
            if (self.getEntityData() == null) return;
            self.getEntityData().set(EZVR_COMBAT_LOADOUT_OFF, stack == null ? ItemStack.EMPTY : stack);
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // Swing sequence (new)
    // -------------------------------------------------------------------------

    @Override
    public int ezvr$getSwingSeq() {
        try {
            Villager self = (Villager) (Object) this;
            if (self.getEntityData() == null) return 0;
            Integer v = self.getEntityData().get(EZVR_SWING_SEQ);
            return v == null ? 0 : v.intValue();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    @Override
    public void ezvr$setSwingSeq(int seq) {
        try {
            Villager self = (Villager) (Object) this;
            if (self.getEntityData() == null) return;
            self.getEntityData().set(EZVR_SWING_SEQ, seq);
        } catch (Throwable ignored) {}
    }

    @Override
    public byte ezvr$getSwingHand() {
        try {
            Villager self = (Villager) (Object) this;
            if (self.getEntityData() == null) return (byte) 0;
            Byte b = self.getEntityData().get(EZVR_SWING_HAND);
            return b == null ? (byte) 0 : b;
        } catch (Throwable ignored) {
            return (byte) 0;
        }
    }

    @Override
    public void ezvr$setSwingHand(byte hand) {
        try {
            Villager self = (Villager) (Object) this;
            if (self.getEntityData() == null) return;
            self.getEntityData().set(EZVR_SWING_HAND, hand);
        } catch (Throwable ignored) {}
    }
}
