// neoforge\src\main\java\org\z2six\villageroverhaul\mixin\villagerRendering\VillagerRenderStateMixin.java
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
import org.z2six.villageroverhaul.render.VillagerRenderFlags;
import org.z2six.villageroverhaul.server.ai.VillagerBrain;

/**
 * Adds a synced byte to Villager containing render decisions, and updates it every server tick
 * using VillagerBrain as the single authority.
 */
@Mixin(Villager.class)
public final class VillagerRenderStateMixin implements VillagerOverhaulRenderAccess {

    @Unique
    private static final EntityDataAccessor<Byte> EZVR_RENDER_FLAGS =
            SynchedEntityData.defineId(Villager.class, EntityDataSerializers.BYTE);

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void ezvr$defineSynchedData(SynchedEntityData.Builder builder, CallbackInfo ci) {
        try {
            if (builder == null) return;
            builder.define(EZVR_RENDER_FLAGS, VillagerRenderFlags.defaultFlags());
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
}
