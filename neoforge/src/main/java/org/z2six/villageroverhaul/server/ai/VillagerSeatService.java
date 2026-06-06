package org.z2six.villageroverhaul.server.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.util.List;

public final class VillagerSeatService {

    private static final String TAG_ROOT = "ezvr_seat";
    private static final String K_VEHICLE = "vehicle";
    private static final String K_X = "x";
    private static final String K_Y = "y";
    private static final String K_Z = "z";

    private static final double MAX_TRANSFER_DISTANCE_SQR = 2.5 * 2.5;

    private VillagerSeatService() {}

    public static boolean shouldDismountBeforeStep(Object type) {
        return VillagerSeatPolicy.shouldDismountBeforeStep(type);
    }

    public static boolean shouldDismountBeforeStepName(String typeName) {
        return VillagerSeatPolicy.shouldDismountBeforeStepName(typeName);
    }

    public static boolean shouldRemainSeatedForCurrentActivity(Villager vill) {
        try {
            if (vill == null) return false;
            VillagerBrain.Mode mode = VillagerBrain.getMode(vill);
            VillagerBrain.CombatMode combatMode = VillagerBrain.getCombatMode(vill);
            return VillagerSeatPolicy.shouldRemainSeatedForActivityName(
                    mode == null ? null : mode.name(),
                    combatMode == null ? null : combatMode.name(),
                    VillagerBrain.isCombatEngaged(vill),
                    VillagerBrain.isStorageActive(vill),
                    VillagerBrain.isManualFarmingActive(vill)
            );
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean dismountIfCurrentActivityRequiresIt(Villager vill, String reason) {
        try {
            if (vill == null || !hasSeatMarker(vill)) return false;
            if (shouldRemainSeatedForCurrentActivity(vill)) return false;
            return dismountIfSeated(vill, reason);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean tryTransferFakePlayerVehicleToVillager(ServerPlayer fakePlayer, Villager vill, BlockPos clickedPos) {
        try {
            if (fakePlayer == null || vill == null || clickedPos == null) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [seatdiag] transfer skipped null fake={} vill={} pos={}", fakePlayer != null, vill != null, clickedPos);
                return false;
            }

            Entity vehicle = null;
            try { vehicle = fakePlayer.getVehicle(); } catch (Throwable ignored) { vehicle = null; }
            if (vehicle == null) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [seatdiag] transfer no fake-player vehicle vill={} pos={} fakePassenger={}",
                        safeUuid(vill), clickedPos, safePassenger(fakePlayer));
                clearFakePlayerRiding(fakePlayer);
                return false;
            }

            boolean valid = isVehicleValidForClickedBlock(vehicle, vill, clickedPos);
            clearFakePlayerRiding(fakePlayer);
            if (!valid) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [seatdiag] transfer vehicle invalid vill={} pos={} vehicle={} vehiclePos={} removed={} sameLevel={} dist2={}",
                        safeUuid(vill), clickedPos, entityType(vehicle), vehicle.blockPosition(), safeRemoved(vehicle), sameLevel(vehicle, vill), distanceToClickedSqr(vehicle, clickedPos));
                return false;
            }

            try {
                if (vill.isPassenger()) vill.stopRiding();
            } catch (Throwable ignored) {}

            boolean mounted = false;
            try { mounted = vill.startRiding(vehicle); } catch (Throwable ignored) { mounted = false; }
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [seatdiag] transfer mount result vill={} pos={} vehicle={} mounted={} villPassenger={}",
                    safeUuid(vill), clickedPos, entityType(vehicle), mounted, safePassenger(vill));
            if (!mounted) return false;

            markSeated(vill, vehicle, clickedPos);
            dismountIfCurrentActivityRequiresIt(vill, "seat_activity_after_transfer");
            return true;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [seatdiag] transfer failed vill={} pos={} err={}",
                    safeUuid(vill), clickedPos, t.toString());
            try { clearFakePlayerRiding(fakePlayer); } catch (Throwable ignored2) {}
            return false;
        }
    }

    public static boolean trySeatVillagerViaEntityInside(Level level, BlockPos clickedPos, Villager vill) {
        try {
            if (level == null || clickedPos == null || vill == null) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [seatdiag] entityInside skipped null level={} vill={} pos={}", level != null, vill != null, clickedPos);
                return false;
            }
            if (level.isClientSide()) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [seatdiag] entityInside skipped client side vill={} pos={}", safeUuid(vill), clickedPos);
                return false;
            }
            if (vill.isPassenger()) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [seatdiag] entityInside skipped already passenger vill={} pos={} vehicle={}",
                        safeUuid(vill), clickedPos, entityType(vill.getVehicle()));
                return false;
            }

            BlockState state = level.getBlockState(clickedPos);
            if (state == null) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [seatdiag] entityInside no blockstate vill={} pos={}", safeUuid(vill), clickedPos);
                return false;
            }

            VillagerOverhaul.LOG().info("[VillagerOverhaul] [seatdiag] entityInside invoke vill={} pos={} block={} blockClass={}",
                    safeUuid(vill), clickedPos, blockId(state), state.getBlock().getClass().getName());

            state.entityInside(level, clickedPos, vill);

            Entity vehicle = null;
            try { vehicle = vill.getVehicle(); } catch (Throwable ignored) { vehicle = null; }
            if (vehicle == null) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [seatdiag] entityInside no villager vehicle vill={} pos={} block={}",
                        safeUuid(vill), clickedPos, blockId(state));
                return false;
            }
            if (!isVehicleValidForClickedBlock(vehicle, vill, clickedPos)) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [seatdiag] entityInside vehicle invalid vill={} pos={} block={} vehicle={} vehiclePos={} removed={} sameLevel={} dist2={}",
                        safeUuid(vill), clickedPos, blockId(state), entityType(vehicle), vehicle.blockPosition(), safeRemoved(vehicle), sameLevel(vehicle, vill), distanceToClickedSqr(vehicle, clickedPos));
                return false;
            }

            markSeated(vill, vehicle, clickedPos);
            dismountIfCurrentActivityRequiresIt(vill, "seat_activity_after_entity_inside");
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [seatdiag] entityInside seated vill={} pos={} block={} vehicle={}",
                    safeUuid(vill), clickedPos, blockId(state), entityType(vehicle));
            return true;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [seatdiag] entityInside failed vill={} pos={} err={}",
                    safeUuid(vill), clickedPos, t.toString());
            return false;
        }
    }

    public static boolean tryMountVillagerOnNearbySeatEntity(Level level, BlockPos clickedPos, Villager vill, String source) {
        try {
            if (level == null || clickedPos == null || vill == null) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [seatdiag] nearbySeat skipped null level={} vill={} pos={} source={}",
                        level != null, vill != null, clickedPos, source);
                return false;
            }
            if (level.isClientSide()) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [seatdiag] nearbySeat skipped client side vill={} pos={} source={}",
                        safeUuid(vill), clickedPos, source);
                return false;
            }
            if (vill.isPassenger()) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [seatdiag] nearbySeat skipped already passenger vill={} pos={} source={} vehicle={}",
                        safeUuid(vill), clickedPos, source, entityType(vill.getVehicle()));
                return false;
            }

            AABB searchBox = new AABB(clickedPos).inflate(0.75D, 1.5D, 0.75D);
            List<Entity> candidates = level.getEntities(vill, searchBox, entity ->
                    entity != null
                            && !entity.isRemoved()
                            && entity.getPassengers().isEmpty()
                            && isLikelySeatEntity(entity));

            VillagerOverhaul.LOG().info("[VillagerOverhaul] [seatdiag] nearbySeat candidates vill={} pos={} source={} count={}",
                    safeUuid(vill), clickedPos, source, candidates.size());

            candidates.sort(java.util.Comparator.comparingDouble(entity -> distanceToClickedSqr(entity, clickedPos)));
            for (Entity candidate : candidates) {
                if (!isVehicleValidForClickedBlock(candidate, vill, clickedPos)) {
                    VillagerOverhaul.LOG().info("[VillagerOverhaul] [seatdiag] nearbySeat invalid vill={} pos={} source={} vehicle={} vehiclePos={} removed={} sameLevel={} dist2={}",
                            safeUuid(vill), clickedPos, source, entityType(candidate), candidate.blockPosition(), safeRemoved(candidate), sameLevel(candidate, vill), distanceToClickedSqr(candidate, clickedPos));
                    continue;
                }

                boolean mounted = false;
                try { mounted = vill.startRiding(candidate, true); } catch (Throwable ignored) { mounted = false; }
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [seatdiag] nearbySeat mount result vill={} pos={} source={} vehicle={} mounted={} villPassenger={}",
                        safeUuid(vill), clickedPos, source, entityType(candidate), mounted, safePassenger(vill));
                if (mounted) {
                    markSeated(vill, candidate, clickedPos);
                    dismountIfCurrentActivityRequiresIt(vill, "seat_activity_after_nearby_mount");
                    return true;
                }
            }

            return false;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [seatdiag] nearbySeat failed vill={} pos={} source={} err={}",
                    safeUuid(vill), clickedPos, source, t.toString());
            return false;
        }
    }

    public static void clearFakePlayerRiding(ServerPlayer fakePlayer) {
        try {
            if (fakePlayer != null && fakePlayer.isPassenger()) fakePlayer.stopRiding();
        } catch (Throwable ignored) {}
    }

    public static boolean dismountIfSeated(Villager vill, String reason) {
        try {
            if (vill == null) return false;
            if (!hasSeatMarker(vill)) return false;

            boolean wasPassenger = false;
            try { wasPassenger = vill.isPassenger(); } catch (Throwable ignored) { wasPassenger = false; }
            if (wasPassenger) {
                try { vill.stopRiding(); } catch (Throwable ignored) {}
            }
            clearSeatMarker(vill);
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [seatdiag] dismount vill={} reason={} wasPassenger={}",
                    safeUuid(vill), reason, wasPassenger);
            return wasPassenger;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isMacroSeated(Villager vill) {
        try {
            return vill != null && hasSeatMarker(vill) && vill.isPassenger();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isVehicleValidForClickedBlock(Entity vehicle, Villager vill, BlockPos clickedPos) {
        try {
            if (vehicle == null || vill == null || clickedPos == null) return false;
            if (vehicle.isRemoved()) return false;
            if (vehicle.level() != vill.level()) return false;

            double cx = clickedPos.getX() + 0.5D;
            double cy = clickedPos.getY() + 0.5D;
            double cz = clickedPos.getZ() + 0.5D;
            return vehicle.distanceToSqr(cx, cy, cz) <= MAX_TRANSFER_DISTANCE_SQR;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void markSeated(Villager vill, Entity vehicle, BlockPos clickedPos) {
        try {
            if (vill == null || vehicle == null || clickedPos == null) return;
            CompoundTag tag = new CompoundTag();
            try { tag.putUUID(K_VEHICLE, vehicle.getUUID()); } catch (Throwable ignored) {}
            tag.putInt(K_X, clickedPos.getX());
            tag.putInt(K_Y, clickedPos.getY());
            tag.putInt(K_Z, clickedPos.getZ());
            vill.getPersistentData().put(TAG_ROOT, tag);
        } catch (Throwable ignored) {}
    }

    private static boolean hasSeatMarker(Villager vill) {
        try {
            return vill != null && vill.getPersistentData().contains(TAG_ROOT);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void clearSeatMarker(Villager vill) {
        try {
            if (vill != null) vill.getPersistentData().remove(TAG_ROOT);
        } catch (Throwable ignored) {}
    }

    private static String safeUuid(Entity entity) {
        try { return entity == null ? "null" : String.valueOf(entity.getUUID()); } catch (Throwable ignored) { return "?"; }
    }

    private static boolean safePassenger(Entity entity) {
        try { return entity != null && entity.isPassenger(); } catch (Throwable ignored) { return false; }
    }

    private static boolean safeRemoved(Entity entity) {
        try { return entity == null || entity.isRemoved(); } catch (Throwable ignored) { return true; }
    }

    private static boolean sameLevel(Entity a, Entity b) {
        try { return a != null && b != null && a.level() == b.level(); } catch (Throwable ignored) { return false; }
    }

    private static double distanceToClickedSqr(Entity entity, BlockPos clickedPos) {
        try {
            if (entity == null || clickedPos == null) return -1.0D;
            return entity.distanceToSqr(clickedPos.getX() + 0.5D, clickedPos.getY() + 0.5D, clickedPos.getZ() + 0.5D);
        } catch (Throwable ignored) {
            return -1.0D;
        }
    }

    private static String entityType(Entity entity) {
        try {
            if (entity == null) return "null";
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            return id == null ? entity.getType().toString() : id.toString();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static boolean isLikelySeatEntity(Entity entity) {
        try {
            if (entity == null) return false;
            return VillagerSeatPolicy.isLikelySeatEntityName(entityType(entity), entity.getClass().getName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String blockId(BlockState state) {
        try {
            if (state == null || state.getBlock() == null) return "null";
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return id == null ? state.getBlock().toString() : id.toString();
        } catch (Throwable ignored) {
            return "?";
        }
    }
}
