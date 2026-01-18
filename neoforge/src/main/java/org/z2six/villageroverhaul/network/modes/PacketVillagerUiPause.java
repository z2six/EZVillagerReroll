// neoforge\src\main\java\org\z2six\villageroverhaul\network\modes\PacketVillagerUiPause.java
package org.z2six.villageroverhaul.network.modes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketVillagerUiPause(int villagerEntityId, boolean paused) implements CustomPacketPayload {

    public static final Type<PacketVillagerUiPause> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "villager_ui_pause"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerUiPause> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketVillagerUiPause::villagerEntityId,
                    ByteBufCodecs.BOOL, PacketVillagerUiPause::paused,
                    PacketVillagerUiPause::new
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
