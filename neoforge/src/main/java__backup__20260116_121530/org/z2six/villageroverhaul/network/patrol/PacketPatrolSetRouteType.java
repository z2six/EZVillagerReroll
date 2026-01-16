// PacketPatrolSetRouteType.java
// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/network/PacketPatrolSetRouteType.java
package org.z2six.villageroverhaul.network.patrol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketPatrolSetRouteType(int villagerEntityId, RouteType routeType) implements CustomPacketPayload {

    public enum RouteType {
        CIRCULAR(1),
        LINEAR(2);

        public final int id;
        RouteType(int id) { this.id = id; }

        public static RouteType fromId(int id) {
            for (RouteType t : values()) if (t.id == id) return t;
            return CIRCULAR;
        }
    }

    public static final Type<PacketPatrolSetRouteType> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "patrol_route"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketPatrolSetRouteType> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketPatrolSetRouteType::villagerEntityId,
                    ByteBufCodecs.VAR_INT, p -> p.routeType == null ? RouteType.CIRCULAR.id : p.routeType.id,
                    (id, typeId) -> new PacketPatrolSetRouteType(id, RouteType.fromId(typeId))
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
