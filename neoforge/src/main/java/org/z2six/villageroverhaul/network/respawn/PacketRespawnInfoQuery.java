// neoforge/src/main/java/org/z2six/villageroverhaul/network/respawn/PacketRespawnInfoQuery.java
package org.z2six.villageroverhaul.network.respawn;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Client -> Server: request full snapshot data for a specific respawnId.
 * Also includes the anchor position (for the follow-up respawn button).
 */
public record PacketRespawnInfoQuery(
        int anchorX, int anchorY, int anchorZ,
        long respawnMsb, long respawnLsb
) implements CustomPacketPayload {

    public static final Type<PacketRespawnInfoQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "respawn_info_query"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketRespawnInfoQuery> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PacketRespawnInfoQuery decode(RegistryFriendlyByteBuf buf) {
                    int x = 0, y = 0, z = 0;
                    long msb = 0L, lsb = 0L;
                    try { x = buf.readInt(); } catch (Throwable ignored) {}
                    try { y = buf.readInt(); } catch (Throwable ignored) {}
                    try { z = buf.readInt(); } catch (Throwable ignored) {}
                    try { msb = buf.readLong(); } catch (Throwable ignored) {}
                    try { lsb = buf.readLong(); } catch (Throwable ignored) {}
                    return new PacketRespawnInfoQuery(x, y, z, msb, lsb);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PacketRespawnInfoQuery msg) {
                    buf.writeInt(msg.anchorX());
                    buf.writeInt(msg.anchorY());
                    buf.writeInt(msg.anchorZ());
                    buf.writeLong(msg.respawnMsb());
                    buf.writeLong(msg.respawnLsb());
                }
            };

    public UUID respawnId() {
        return new UUID(respawnMsb, respawnLsb);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
