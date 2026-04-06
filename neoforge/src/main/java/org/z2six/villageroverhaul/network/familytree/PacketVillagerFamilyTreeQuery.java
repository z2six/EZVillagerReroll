package org.z2six.villageroverhaul.network.familytree;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketVillagerFamilyTreeQuery(int villagerEntityId) implements CustomPacketPayload {
    public static final Type<PacketVillagerFamilyTreeQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "villager_family_tree_query"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerFamilyTreeQuery> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PacketVillagerFamilyTreeQuery decode(RegistryFriendlyByteBuf buf) {
                    return new PacketVillagerFamilyTreeQuery(buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PacketVillagerFamilyTreeQuery msg) {
                    buf.writeVarInt(msg.villagerEntityId());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
