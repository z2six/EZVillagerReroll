package org.z2six.villageroverhaul.network.modes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketVillagerReleaseCommand(int villagerEntityId, boolean shooAway) implements CustomPacketPayload {

    public static final Type<PacketVillagerReleaseCommand> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "villager_release_command"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerReleaseCommand> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketVillagerReleaseCommand::villagerEntityId,
                    ByteBufCodecs.BOOL, PacketVillagerReleaseCommand::shooAway,
                    PacketVillagerReleaseCommand::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
