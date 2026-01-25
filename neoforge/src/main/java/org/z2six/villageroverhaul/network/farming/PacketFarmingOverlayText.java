package org.z2six.villageroverhaul.network.farming;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketFarmingOverlayText(String message, int durationMs) implements CustomPacketPayload {

    public static final Type<PacketFarmingOverlayText> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "farming_overlay_text"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketFarmingOverlayText> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketFarmingOverlayText decode(RegistryFriendlyByteBuf buf) {
            String msg = "";
            int dur = 2000;
            try { msg = buf.readUtf(256); } catch (Throwable ignored) { msg = ""; }
            try { dur = buf.readVarInt(); } catch (Throwable ignored) { dur = 2000; }
            if (dur < 250) dur = 250;
            if (dur > 60_000) dur = 60_000;
            return new PacketFarmingOverlayText(msg, dur);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, PacketFarmingOverlayText msg) {
            buf.writeUtf(msg == null || msg.message() == null ? "" : msg.message(), 256);
            buf.writeVarInt(msg == null ? 2000 : msg.durationMs());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

