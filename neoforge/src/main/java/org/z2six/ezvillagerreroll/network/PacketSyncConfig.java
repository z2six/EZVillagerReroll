// MainFile: src/main/java/org/z2six/ezvillagerreroll/network/PacketSyncConfig.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

public final class PacketSyncConfig implements CustomPacketPayload {

    public static final Type<PacketSyncConfig> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EZVillagerReroll.MODID, "sync_config"));

    public int version;
    public int hash;
    public String costItemOrTag;
    public int[] costsByLevel; // length 6
    public boolean preferWallet;
    public int cooldownTicks;
    public int perVillagerDaily;
    public boolean allowAfterTradeUsed;

    public PacketSyncConfig() {}

    public static final StreamCodec<FriendlyByteBuf, PacketSyncConfig> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketSyncConfig decode(FriendlyByteBuf buf) {
            PacketSyncConfig p = new PacketSyncConfig();
            p.version = buf.readVarInt();
            p.hash = buf.readVarInt();
            p.costItemOrTag = buf.readUtf(256);

            int len = buf.readVarInt();
            if (len < 0) len = 0;
            if (len > 64) len = 64; // hard cap for safety
            p.costsByLevel = new int[len];
            for (int i = 0; i < len; i++) p.costsByLevel[i] = Math.max(0, buf.readVarInt());

            p.preferWallet = buf.readBoolean();
            p.cooldownTicks = buf.readVarInt();
            p.perVillagerDaily = buf.readVarInt();
            p.allowAfterTradeUsed = buf.readBoolean();
            return p;
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketSyncConfig p) {
            buf.writeVarInt(p.version);
            buf.writeVarInt(p.hash);
            buf.writeUtf(p.costItemOrTag == null ? "" : p.costItemOrTag, 256);

            int[] arr = p.costsByLevel == null ? new int[0] : p.costsByLevel;
            buf.writeVarInt(arr.length);
            for (int v : arr) buf.writeVarInt(Math.max(0, v));

            buf.writeBoolean(p.preferWallet);
            buf.writeVarInt(p.cooldownTicks);
            buf.writeVarInt(p.perVillagerDaily);
            buf.writeVarInt(0); // RESERVED for future expansion (keeps compatibility if you add fields later)
            buf.writeBoolean(p.allowAfterTradeUsed);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
