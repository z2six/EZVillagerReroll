// neoforge/src/main/java/org/z2six/villageroverhaul/network/autoReroll/PacketAutoSearchPaymentFailed.java
package org.z2six.villageroverhaul.network.autoReroll;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketAutoSearchPaymentFailed(int villagerEntityId, String reason) implements CustomPacketPayload {

    public static final Type<PacketAutoSearchPaymentFailed> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "auto_search_payment_failed"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketAutoSearchPaymentFailed> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketAutoSearchPaymentFailed decode(RegistryFriendlyByteBuf buf) {
            int id = -1;
            String r = "";
            try { id = buf.readVarInt(); } catch (Throwable ignored) {}
            try { r = buf.readUtf(32767); } catch (Throwable ignored) { r = ""; }
            if (r == null) r = "";
            return new PacketAutoSearchPaymentFailed(id, r);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, PacketAutoSearchPaymentFailed msg) {
            buf.writeVarInt(msg == null ? -1 : msg.villagerEntityId());
            String r = msg == null ? "" : msg.reason();
            if (r == null) r = "";
            if (r.length() > 32767) r = r.substring(0, 32767);
            buf.writeUtf(r);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

