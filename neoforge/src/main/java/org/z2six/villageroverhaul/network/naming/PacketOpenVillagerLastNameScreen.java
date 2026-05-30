package org.z2six.villageroverhaul.network.naming;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketOpenVillagerLastNameScreen(
        int villagerEntityId,
        String currentLastName,
        List<String> lastNames,
        String message
) implements CustomPacketPayload {
    private static final int MAX_NAMES = 128;
    private static final int MAX_NAME_LEN = 64;

    public static final Type<PacketOpenVillagerLastNameScreen> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "open_villager_last_name_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketOpenVillagerLastNameScreen> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PacketOpenVillagerLastNameScreen decode(RegistryFriendlyByteBuf buf) {
                    int id = 0;
                    String current = "";
                    String message = "";
                    List<String> names = new ArrayList<>();
                    try { id = buf.readVarInt(); } catch (Throwable ignored) {}
                    try { current = buf.readUtf(MAX_NAME_LEN); } catch (Throwable ignored) { current = ""; }
                    try {
                        int n = Math.max(0, Math.min(MAX_NAMES, buf.readVarInt()));
                        for (int i = 0; i < n; i++) {
                            String name = "";
                            try { name = buf.readUtf(MAX_NAME_LEN); } catch (Throwable ignored) { name = ""; }
                            if (!name.isBlank()) names.add(name.trim());
                        }
                    } catch (Throwable ignored) {
                        names.clear();
                    }
                    try { message = buf.readUtf(256); } catch (Throwable ignored) { message = ""; }
                    return new PacketOpenVillagerLastNameScreen(id, current == null ? "" : current.trim(), names, message == null ? "" : message);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PacketOpenVillagerLastNameScreen msg) {
                    buf.writeVarInt(msg == null ? 0 : msg.villagerEntityId());
                    buf.writeUtf(msg == null || msg.currentLastName() == null ? "" : msg.currentLastName().trim(), MAX_NAME_LEN);
                    List<String> names = msg == null || msg.lastNames() == null ? List.of() : msg.lastNames();
                    int n = Math.max(0, Math.min(MAX_NAMES, names.size()));
                    buf.writeVarInt(n);
                    for (int i = 0; i < n; i++) {
                        String name = names.get(i);
                        buf.writeUtf(name == null ? "" : name.trim(), MAX_NAME_LEN);
                    }
                    buf.writeUtf(msg == null || msg.message() == null ? "" : msg.message(), 256);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
