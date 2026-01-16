// neoforge\src\main\java\org\z2six\villageroverhaul\network\recruit\PacketOpenRecruitScreen.java
package org.z2six.villageroverhaul.network.recruit;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketOpenRecruitScreen(
        int villagerEntityId,
        boolean eligible,
        boolean alreadyRecruited,
        int cost,
        String message
) implements CustomPacketPayload {

    public static final Type<PacketOpenRecruitScreen> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "open_recruit_screen"));

    public static PacketOpenRecruitScreen simple(int villagerEntityId, int cost) {
        return new PacketOpenRecruitScreen(villagerEntityId, true, false, Math.max(0, cost), "");
    }

    public static final StreamCodec<FriendlyByteBuf, PacketOpenRecruitScreen> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketOpenRecruitScreen decode(FriendlyByteBuf buf) {
            int id = 0;
            boolean eligible = false;
            boolean recruited = false;
            int cost = 0;
            String msg = "";

            try { id = buf.readVarInt(); } catch (Throwable ignored) {}
            try { eligible = buf.readBoolean(); } catch (Throwable ignored) {}
            try { recruited = buf.readBoolean(); } catch (Throwable ignored) {}
            try { cost = Math.max(0, buf.readVarInt()); } catch (Throwable ignored) {}
            try { msg = buf.readUtf(256); } catch (Throwable ignored) { msg = ""; }

            return new PacketOpenRecruitScreen(id, eligible, recruited, cost, msg == null ? "" : msg);
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketOpenRecruitScreen p) {
            buf.writeVarInt(p.villagerEntityId());
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
