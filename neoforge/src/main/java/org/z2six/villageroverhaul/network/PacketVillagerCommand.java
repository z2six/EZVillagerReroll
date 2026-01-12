package org.z2six.villageroverhaul.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketVillagerCommand(int villagerEntityId, Command command) implements CustomPacketPayload {

    public enum Command {
        IDLE(1),
        NEUTRAL(2),
        FOLLOW(3);

        public final int id;
        Command(int id) { this.id = id; }

        public static Command fromId(int id) {
            for (Command c : values()) if (c.id == id) return c;
            return IDLE;
        }
    }

    public static final Type<PacketVillagerCommand> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "villager_command"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerCommand> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketVillagerCommand::villagerEntityId,
                    ByteBufCodecs.VAR_INT, p -> p.command == null ? Command.IDLE.id : p.command.id,
                    (id, cmdId) -> new PacketVillagerCommand(id, Command.fromId(cmdId))
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
