package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketCcDeleteAction(int villagerEntityId, int actionIndex) implements CustomPacketPayload {

    public static final Type<PacketCcDeleteAction> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "cc_delete_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcDeleteAction> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketCcDeleteAction::villagerEntityId,
                    ByteBufCodecs.VAR_INT, PacketCcDeleteAction::actionIndex,
                    PacketCcDeleteAction::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

