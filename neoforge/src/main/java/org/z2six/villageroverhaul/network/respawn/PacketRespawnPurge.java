// neoforge/src/main/java/org/z2six/villageroverhaul/network/respawn/PacketRespawnPurge.java
package org.z2six.villageroverhaul.network.respawn;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Client -> Server: remove a respawn snapshot from the list without respawning it.
 */
public record PacketRespawnPurge(
        int anchorX, int anchorY, int anchorZ,
        long respawnMsb, long respawnLsb
) implements CustomPacketPayload {

    public static final Type<PacketRespawnPurge> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "respawn_purge"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketRespawnPurge> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PacketRespawnPurge decode(RegistryFriendlyByteBuf buf) {
                    int x = 0, y = 0, z = 0;
                    long msb = 0L, lsb = 0L;
                    try {
                        x = buf.readInt();
                        y = buf.readInt();
                        z = buf.readInt();
                        msb = buf.readLong();
                        lsb = buf.readLong();
                    } catch (Throwable ignored) {}
                    return new PacketRespawnPurge(x, y, z, msb, lsb);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PacketRespawnPurge msg) {
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

