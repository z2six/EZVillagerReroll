// neoforge/src/main/java/org/z2six/villageroverhaul/network/PacketRecruitResult.java
package org.z2six.villageroverhaul.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketRecruitResult(
        int villagerEntityId,
        boolean success,
        boolean nowRecruited,
        int costPaid,
        String message
) implements CustomPacketPayload {

    public static final Type<PacketRecruitResult> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "recruit_result"));

    public static final StreamCodec<FriendlyByteBuf, PacketRecruitResult> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketRecruitResult decode(FriendlyByteBuf buf) {
            int id = 0;
            boolean success = false;
            boolean now = false;
            int cost = 0;
            String msg = "";

            try { id = buf.readVarInt(); } catch (Throwable ignored) {}
            try { success = buf.readBoolean(); } catch (Throwable ignored) {}
            try { now = buf.readBoolean(); } catch (Throwable ignored) {}
            try { cost = Math.max(0, buf.readVarInt()); } catch (Throwable ignored) {}
            try { msg = buf.readUtf(256); } catch (Throwable ignored) { msg = ""; }

            return new PacketRecruitResult(id, success, now, cost, msg == null ? "" : msg);
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketRecruitResult p) {
            buf.writeVarInt(p.villagerEntityId());
            buf.writeBoolean(p.success());
            buf.writeBoolean(p.nowRecruited());
            buf.writeVarInt(Math.max(0, p.costPaid()));
            buf.writeUtf(p.message() == null ? "" : p.message(), 256);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
