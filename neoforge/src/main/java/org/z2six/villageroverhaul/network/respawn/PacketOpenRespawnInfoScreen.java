// neoforge/src/main/java/org/z2six/villageroverhaul/network/respawn/PacketOpenRespawnInfoScreen.java
package org.z2six.villageroverhaul.network.respawn;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server -> Client: open the VillagerInfoScreen in "snapshot/respawn" mode.
 */
public record PacketOpenRespawnInfoScreen(
        int anchorX, int anchorY, int anchorZ,
        long respawnMsb, long respawnLsb,
        int recruitCostAtDeath,
        int respawnCost,
        int deaths,
        CompoundTag villagerNbt
) implements CustomPacketPayload {

    public static final Type<PacketOpenRespawnInfoScreen> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "respawn_open_info"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketOpenRespawnInfoScreen> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PacketOpenRespawnInfoScreen decode(RegistryFriendlyByteBuf buf) {
                    int x = 0, y = 0, z = 0;
                    long msb = 0L, lsb = 0L;
                    int recruit = 0, cost = 0, deaths = 0;
                    CompoundTag tag = new CompoundTag();
                    try {
                        x = buf.readInt();
                        y = buf.readInt();
                        z = buf.readInt();
                        msb = buf.readLong();
                        lsb = buf.readLong();
                        recruit = Math.max(0, buf.readVarInt());
                        cost = Math.max(0, buf.readVarInt());
                        deaths = Math.max(0, buf.readVarInt());
                        tag = buf.readNbt();
                        if (tag == null) tag = new CompoundTag();
                    } catch (Throwable ignored) {}
                    return new PacketOpenRespawnInfoScreen(x, y, z, msb, lsb, recruit, cost, deaths, tag);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PacketOpenRespawnInfoScreen msg) {
                    buf.writeInt(msg.anchorX());
                    buf.writeInt(msg.anchorY());
                    buf.writeInt(msg.anchorZ());
                    buf.writeLong(msg.respawnMsb());
                    buf.writeLong(msg.respawnLsb());
                    buf.writeVarInt(Math.max(0, msg.recruitCostAtDeath()));
                    buf.writeVarInt(Math.max(0, msg.respawnCost()));
                    buf.writeVarInt(Math.max(0, msg.deaths()));
                    buf.writeNbt(msg.villagerNbt() == null ? new CompoundTag() : msg.villagerNbt());
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
