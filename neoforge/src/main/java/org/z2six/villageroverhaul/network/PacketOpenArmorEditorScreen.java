package org.z2six.villageroverhaul.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketOpenArmorEditorScreen() implements CustomPacketPayload {
    public static final Type<PacketOpenArmorEditorScreen> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "open_armor_editor_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketOpenArmorEditorScreen> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PacketOpenArmorEditorScreen decode(RegistryFriendlyByteBuf buf) {
                    return new PacketOpenArmorEditorScreen();
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PacketOpenArmorEditorScreen msg) {
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
