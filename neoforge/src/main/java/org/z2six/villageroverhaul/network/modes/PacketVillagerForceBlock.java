// neoforge\src\main\java\org\z2six\villageroverhaul\network\modes\PacketVillagerForceBlock.java
package org.z2six.villageroverhaul.network.modes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketVillagerForceBlock(int villagerEntityId, int ticks) implements CustomPacketPayload {

    public static final Type<PacketVillagerForceBlock> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "villager_force_block"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerForceBlock> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketVillagerForceBlock::villagerEntityId,
                    ByteBufCodecs.VAR_INT, PacketVillagerForceBlock::ticks,
                    PacketVillagerForceBlock::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
