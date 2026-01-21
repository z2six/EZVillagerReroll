// neoforge/src/main/java/org/z2six/villageroverhaul/network/patrol/PacketPatrolRouteDelete.java
package org.z2six.villageroverhaul.network.patrol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Client -> Server: delete a saved patrol route by id.
 */
public record PacketPatrolRouteDelete(
        int villagerEntityId,
        UUID routeId
) implements CustomPacketPayload {

    public static final Type<PacketPatrolRouteDelete> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "patrol_route_delete"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketPatrolRouteDelete> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());
                        UUID id = msg.routeId() == null ? new UUID(0L, 0L) : msg.routeId();
                        buf.writeLong(id.getMostSignificantBits());
                        buf.writeLong(id.getLeastSignificantBits());
                    },
                    (buf) -> new PacketPatrolRouteDelete(
                            buf.readVarInt(),
                            new UUID(buf.readLong(), buf.readLong())
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

