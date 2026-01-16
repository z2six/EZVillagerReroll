// neoforge\src\main\java\org\z2six\villageroverhaul\network\recruit\PacketRecruitCostData.java
package org.z2six.villageroverhaul.network.recruit;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketRecruitCostData(
        int villagerEntityId,
        boolean ok,
        boolean eligible,
        boolean alreadyRecruited,
        int cost,
        String message
) implements CustomPacketPayload {

    public static final Type<PacketRecruitCostData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "recruit_cost_data"));

    public static PacketRecruitCostData missing(int villagerEntityId) {
        return new PacketRecruitCostData(villagerEntityId, false, false, false, 0, "Missing villager");
    }

    public static final StreamCodec<FriendlyByteBuf, PacketRecruitCostData> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketRecruitCostData decode(FriendlyByteBuf buf) {
            int id = 0;
            boolean ok = false;
            boolean eligible = false;
            boolean recruited = false;
            int cost = 0;
            String msg = "";

            try { id = buf.readVarInt(); } catch (Throwable ignored) {}
            try { ok = buf.readBoolean(); } catch (Throwable ignored) {}
            try { eligible = buf.readBoolean(); } catch (Throwable ignored) {}
            try { recruited = buf.readBoolean(); } catch (Throwable ignored) {}
            try { cost = Math.max(0, buf.readVarInt()); } catch (Throwable ignored) {}
            try { msg = buf.readUtf(256); } catch (Throwable ignored) { msg = ""; }

            return new PacketRecruitCostData(id, ok, eligible, recruited, cost, msg == null ? "" : msg);
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketRecruitCostData p) {
            buf.writeVarInt(p.villagerEntityId());
            buf.writeBoolean(p.ok());
            buf.writeBoolean(p.eligible());
            buf.writeBoolean(p.alreadyRecruited());
            buf.writeVarInt(Math.max(0, p.cost()));
            buf.writeUtf(p.message() == null ? "" : p.message(), 256);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
