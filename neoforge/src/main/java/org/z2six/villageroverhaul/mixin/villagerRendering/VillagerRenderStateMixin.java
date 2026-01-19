// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/mixin/villagerRendering/VillagerRenderStateMixin.java
package org.z2six.villageroverhaul.mixin.villagerRendering;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.api.VillagerOverhaulSwingAccess;
import org.z2six.villageroverhaul.render.VillagerRenderFlags;
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

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void ezvr$defineSynchedData(SynchedEntityData.Builder builder, CallbackInfo ci) {
        try {
            if (builder == null) return;

            builder.define(EZVR_RENDER_FLAGS, VillagerRenderFlags.defaultFlags());
            builder.define(EZVR_SWING_SEQ, 0);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerRenderStateMixin#defineSynchedData failed (soft): {}", t.toString());
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
}
