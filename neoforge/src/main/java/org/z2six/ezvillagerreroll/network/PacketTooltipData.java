// MainFile: src/main/java/org/z2six/ezvillagerreroll/network/PacketTooltipData.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

public final class PacketTooltipData implements CustomPacketPayload {

    public static final Type<PacketTooltipData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EZVillagerReroll.MODID, "tooltip_data"));

    public static final class Cost {
        public ResourceLocation item;     // null if tag or invalid
        public String itemOrTag;          // exact string from config for display (can be "#tag")
        public int baseCost;              // same as scaledCost here, kept for compatibility
        public int scaledCost;
        public Integer nextCostIfUsed;
        public Integer maxCostPossible;
    }

    public static final class Afford {
        public boolean canAfford;
        public String source; // "wallet", "inventory", "both", "none"
    }

    public static final class VillagerInfo {
        public int level;
        public int xp;
    }

    public static final class Cap {
        public boolean enabled;
        public int remaining;
        public int cap;
    }

    public static final class Cfg {
        public int version;
        public int hash;
        public boolean preferWallet;
        public boolean freeMode;
        public boolean capEnabled;
    }

    public final Cost cost = new Cost();
    public final Afford afford = new Afford();
    public final VillagerInfo villager = new VillagerInfo();
    public final Cap cap = new Cap();
    public final Cfg cfg = new Cfg();

    public PacketTooltipData() {}

    private static void writeNullableRL(FriendlyByteBuf buf, ResourceLocation rl) {
        buf.writeBoolean(rl != null);
        if (rl != null) buf.writeResourceLocation(rl);
    }

    private static ResourceLocation readNullableRL(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readResourceLocation() : null;
    }

    private static void writeNullableInt(FriendlyByteBuf buf, Integer v) {
        buf.writeBoolean(v != null);
        if (v != null) buf.writeVarInt(v);
    }

    private static Integer readNullableInt(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readVarInt() : null;
    }

    public static final StreamCodec<FriendlyByteBuf, PacketTooltipData> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketTooltipData decode(FriendlyByteBuf buf) {
            var pkt = new PacketTooltipData();

            pkt.cost.item = readNullableRL(buf);
            pkt.cost.itemOrTag = buf.readUtf(128);
            pkt.cost.baseCost = buf.readVarInt();
            pkt.cost.scaledCost = buf.readVarInt();
            pkt.cost.nextCostIfUsed = readNullableInt(buf);
            pkt.cost.maxCostPossible = readNullableInt(buf);

            pkt.afford.canAfford = buf.readBoolean();
            pkt.afford.source = buf.readUtf(16);

            pkt.villager.level = buf.readVarInt();
            pkt.villager.xp = buf.readVarInt();

            pkt.cap.enabled = buf.readBoolean();
            pkt.cap.remaining = buf.readVarInt();
            pkt.cap.cap = buf.readVarInt();

            pkt.cfg.version = buf.readVarInt();
            pkt.cfg.hash = buf.readVarInt();
            pkt.cfg.preferWallet = buf.readBoolean();
            pkt.cfg.freeMode = buf.readBoolean();
            pkt.cfg.capEnabled = buf.readBoolean();

            return pkt;
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketTooltipData pkt) {
            writeNullableRL(buf, pkt.cost.item);
            buf.writeUtf(pkt.cost.itemOrTag == null ? "" : pkt.cost.itemOrTag, 128);
            buf.writeVarInt(pkt.cost.baseCost);
            buf.writeVarInt(pkt.cost.scaledCost);
            writeNullableInt(buf, pkt.cost.nextCostIfUsed);
            writeNullableInt(buf, pkt.cost.maxCostPossible);

            buf.writeBoolean(pkt.afford.canAfford);
            buf.writeUtf(pkt.afford.source == null ? "none" : pkt.afford.source, 16);

            buf.writeVarInt(pkt.villager.level);
            buf.writeVarInt(pkt.villager.xp);

            buf.writeBoolean(pkt.cap.enabled);
            buf.writeVarInt(pkt.cap.remaining);
            buf.writeVarInt(pkt.cap.cap);

            buf.writeVarInt(pkt.cfg.version);
            buf.writeVarInt(pkt.cfg.hash);
            buf.writeBoolean(pkt.cfg.preferWallet);
            buf.writeBoolean(pkt.cfg.freeMode);
            buf.writeBoolean(pkt.cfg.capEnabled);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
