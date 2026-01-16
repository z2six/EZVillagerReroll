// neoforge\src\main\java\org\z2six\villageroverhaul\network\patrol\PacketPatrolBegin.java
package org.z2six.villageroverhaul.network.patrol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketPatrolBegin(int villagerEntityId, boolean createNew) implements CustomPacketPayload {

    public static final Type<PacketPatrolBegin> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "patrol_begin"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketPatrolBegin> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketPatrolBegin::villagerEntityId,
                    ByteBufCodecs.BOOL, PacketPatrolBegin::createNew,
                    PacketPatrolBegin::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
