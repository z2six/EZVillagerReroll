// neoforge/src/main/java/org/z2six/villageroverhaul/network/patrol/PacketPatrolSaveRoute.java
package org.z2six.villageroverhaul.network.patrol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> Server: save the currently recorded patrol (setup) as a named route and start patrolling.
 */
public record PacketPatrolSaveRoute(
        int villagerEntityId,
        String name,
        PacketPatrolSetRouteType.RouteType routeType
) implements CustomPacketPayload {

    public static final Type<PacketPatrolSaveRoute> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "patrol_route_save"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketPatrolSaveRoute> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());
                        buf.writeUtf(msg.name() == null ? "" : msg.name(), 64);
                        int typeId = (msg.routeType() == null) ? PacketPatrolSetRouteType.RouteType.CIRCULAR.id : msg.routeType().id;
                        buf.writeVarInt(typeId);
                    },
                    (buf) -> new PacketPatrolSaveRoute(
                            buf.readVarInt(),
                            buf.readUtf(64),
                            PacketPatrolSetRouteType.RouteType.fromId(buf.readVarInt())
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

