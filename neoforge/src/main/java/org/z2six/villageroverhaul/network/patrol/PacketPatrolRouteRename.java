// neoforge/src/main/java/org/z2six/villageroverhaul/network/patrol/PacketPatrolRouteRename.java
package org.z2six.villageroverhaul.network.patrol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Client -> Server: rename a saved patrol route.
 */
public record PacketPatrolRouteRename(
        int villagerEntityId,
        UUID routeId,
        String newName
) implements CustomPacketPayload {

    public static final Type<PacketPatrolRouteRename> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "patrol_route_rename"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketPatrolRouteRename> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());
                        UUID id = msg.routeId() == null ? new UUID(0L, 0L) : msg.routeId();
                        buf.writeLong(id.getMostSignificantBits());
                        buf.writeLong(id.getLeastSignificantBits());
                        buf.writeUtf(msg.newName() == null ? "" : msg.newName(), 64);
                    },
                    (buf) -> new PacketPatrolRouteRename(
                            buf.readVarInt(),
                            new UUID(buf.readLong(), buf.readLong()),
                            buf.readUtf(64)
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

