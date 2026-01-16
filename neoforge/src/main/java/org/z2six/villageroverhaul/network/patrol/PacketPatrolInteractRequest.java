// neoforge\src\main\java\org\z2six\villageroverhaul\network\patrol\PacketPatrolInteractRequest.java
package org.z2six.villageroverhaul.network.patrol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketPatrolInteractRequest(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketPatrolInteractRequest> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "patrol_interact_req"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketPatrolInteractRequest> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketPatrolInteractRequest::villagerEntityId,
                    PacketPatrolInteractRequest::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
