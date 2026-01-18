// neoforge\src\main\java\org\z2six\villageroverhaul\network\modes\PacketVillagerCombatCommand.java
package org.z2six.villageroverhaul.network.modes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketVillagerCombatCommand(int villagerEntityId, Command command) implements CustomPacketPayload {

    public enum Command {
        OFF(0),
        FLEE(1),
        DEFEND(2),
        AGGRESSIVE(3);

        public final int id;
        Command(int id) { this.id = id; }

        public static Command fromId(int id) {
            for (Command c : values()) if (c.id == id) return c;
            return OFF;
        }
    }

    public static final Type<PacketVillagerCombatCommand> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "villager_combat_command"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerCombatCommand> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketVillagerCombatCommand::villagerEntityId,
                    ByteBufCodecs.VAR_INT, p -> p.command == null ? Command.OFF.id : p.command.id,
                    (id, cmdId) -> new PacketVillagerCombatCommand(id, Command.fromId(cmdId))
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
