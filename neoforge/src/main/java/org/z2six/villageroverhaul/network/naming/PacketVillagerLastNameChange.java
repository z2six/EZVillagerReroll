package org.z2six.villageroverhaul.network.naming;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketVillagerLastNameChange(int villagerEntityId, boolean generateNew, String lastName) implements CustomPacketPayload {
    private static final int MAX_NAME_LEN = 64;

    public static final Type<PacketVillagerLastNameChange> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "villager_last_name_change"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerLastNameChange> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PacketVillagerLastNameChange decode(RegistryFriendlyByteBuf buf) {
                    int id = 0;
                    boolean generate = false;
                    String name = "";
                    try { id = buf.readVarInt(); } catch (Throwable ignored) {}
                    try { generate = buf.readBoolean(); } catch (Throwable ignored) {}
                    try { name = buf.readUtf(MAX_NAME_LEN); } catch (Throwable ignored) { name = ""; }
                    return new PacketVillagerLastNameChange(id, generate, name == null ? "" : name.trim());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PacketVillagerLastNameChange msg) {
                    buf.writeVarInt(msg == null ? 0 : msg.villagerEntityId());
                    buf.writeBoolean(msg != null && msg.generateNew());
                    buf.writeUtf(msg == null || msg.lastName() == null ? "" : msg.lastName().trim(), MAX_NAME_LEN);
                }
            };

    public PacketVillagerLastNameChange(int villagerEntityId, String lastName) {
        this(villagerEntityId, false, lastName);
    }

    public static PacketVillagerLastNameChange generateNew(int villagerEntityId) {
        return new PacketVillagerLastNameChange(villagerEntityId, true, "");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
