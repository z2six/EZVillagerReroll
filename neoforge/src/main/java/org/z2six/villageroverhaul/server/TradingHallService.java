package org.z2six.villageroverhaul.server;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.z2six.villageroverhaul.block.entity.TradingHallBlockEntity;
import org.z2six.villageroverhaul.content.ModBlocks;
import org.z2six.villageroverhaul.server.ai.VillagerBrain;

public final class TradingHallService {
    public static final int MAX_HALL_DISTANCE_BLOCKS = 16;
    private static final int MAX_HALL_VERTICAL_DELTA = 4;

    private static final String TAG_ROOT = "ezvr_trading";
    private static final String K_DIM = "dim";
    private static final String K_X = "x";
    private static final String K_Y = "y";
    private static final String K_Z = "z";
    private static final String K_LAST_DAILY_CYCLE_DAY = "last_daily_cycle_day";
    private static final String K_RESTOCK_SCHEDULED_DAY = "restock_scheduled_day";
    private static final String K_RESTOCK_TIME_OF_DAY = "restock_time_of_day";
    private static final String K_PENDING_PURCHASE_CHECK = "pending_purchase_check";
    private static final String K_STOREFRONT_DIM = "storefront_dim";
    private static final String K_STOREFRONT_X = "storefront_x";
    private static final String K_STOREFRONT_Y = "storefront_y";
    private static final String K_STOREFRONT_Z = "storefront_z";

    private TradingHallService() {}

    public record RegisteredHall(String dimId, int x, int y, int z) {
        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    public record RegisteredStorefront(String dimId, int x, int y, int z) {
        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    public static void setRegisteredHall(Villager vill, String dimId, int x, int y, int z) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            root.putString(K_DIM, dimId == null ? "" : dimId);
            root.putInt(K_X, x);
            root.putInt(K_Y, y);
            root.putInt(K_Z, z);
        } catch (Throwable ignored) {}
    }

    public static RegisteredHall getRegisteredHall(Villager vill) {
        try {
            if (vill == null) return null;
            CompoundTag root = getOrCreateRoot(vill);
            String dim = root.getString(K_DIM);
            if (dim == null || dim.isBlank()) return null;
            return new RegisteredHall(dim, root.getInt(K_X), root.getInt(K_Y), root.getInt(K_Z));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void setRegisteredStorefront(Villager vill, String dimId, int x, int y, int z) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            root.putString(K_STOREFRONT_DIM, dimId == null ? "" : dimId);
            root.putInt(K_STOREFRONT_X, x);
            root.putInt(K_STOREFRONT_Y, y);
            root.putInt(K_STOREFRONT_Z, z);
        } catch (Throwable ignored) {}
    }

    public static RegisteredStorefront getRegisteredStorefront(Villager vill) {
        try {
            if (vill == null) return null;
            CompoundTag root = getOrCreateRoot(vill);
            String dim = root.getString(K_STOREFRONT_DIM);
            if (dim == null || dim.isBlank()) return null;
            return new RegisteredStorefront(dim, root.getInt(K_STOREFRONT_X), root.getInt(K_STOREFRONT_Y), root.getInt(K_STOREFRONT_Z));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static BlockPos getResolvedStorefrontPos(ServerLevel level, Villager vill) {
        try {
            RegisteredStorefront reg = getRegisteredStorefront(vill);
            if (level == null || reg == null) return null;
            String curDim = String.valueOf(level.dimension().location());
            if (!curDim.equals(reg.dimId())) return null;
            BlockPos pos = reg.pos();
            if (!level.isLoaded(pos)) return null;
            return pos;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static TradingHallBlockEntity getResolvedHall(ServerLevel level, Villager vill) {
        try {
            RegisteredHall reg = getRegisteredHall(vill);
            if (level == null || reg == null) return null;
            String curDim = String.valueOf(level.dimension().location());
            if (!curDim.equals(reg.dimId())) return null;
            BlockPos pos = reg.pos();
            if (!level.isLoaded(pos)) return null;
            if (!isWithinReasonableDistance(level, vill, pos)) return null;
            if (!level.getBlockState(pos).is(ModBlocks.TRADING_HALL.get())) return null;
            BlockEntity be = level.getBlockEntity(pos);
            return be instanceof TradingHallBlockEntity hall ? hall : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isWithinReasonableDistance(ServerLevel level, Villager vill, BlockPos hallPos) {
        try {
            if (level == null || vill == null || hallPos == null) return false;

            FarmingSettingsService.RegisteredWorkstation ws = FarmingSettingsService.getEffectiveWorkstation(level, vill);
            Vec3Like anchor;
            if (ws != null) {
                anchor = new Vec3Like(ws.x() + 0.5D, ws.y() + 0.5D, ws.z() + 0.5D);
            } else {
                anchor = new Vec3Like(vill.getX(), vill.getY(), vill.getZ());
            }

            double dx = (hallPos.getX() + 0.5D) - anchor.x;
            double dz = (hallPos.getZ() + 0.5D) - anchor.z;
            double dy = Math.abs((hallPos.getY() + 0.5D) - anchor.y);
            if (dy > MAX_HALL_VERTICAL_DELTA) return false;

            return (dx * dx + dz * dz) <= (double) (MAX_HALL_DISTANCE_BLOCKS * MAX_HALL_DISTANCE_BLOCKS);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static long getLastDailyCycleDay(Villager vill) {
        try {
            if (vill == null) return -1L;
            CompoundTag root = getOrCreateRoot(vill);
            return root.contains(K_LAST_DAILY_CYCLE_DAY, Tag.TAG_LONG) ? root.getLong(K_LAST_DAILY_CYCLE_DAY) : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    public static void setLastDailyCycleDay(Villager vill, long day) {
        try {
            if (vill == null) return;
            getOrCreateRoot(vill).putLong(K_LAST_DAILY_CYCLE_DAY, day);
        } catch (Throwable ignored) {}
    }

    public static long getRestockScheduledDay(Villager vill) {
        try {
            if (vill == null) return -1L;
            CompoundTag root = getOrCreateRoot(vill);
            return root.contains(K_RESTOCK_SCHEDULED_DAY, Tag.TAG_LONG) ? root.getLong(K_RESTOCK_SCHEDULED_DAY) : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    public static void setRestockScheduledDay(Villager vill, long day) {
        try {
            if (vill == null) return;
            getOrCreateRoot(vill).putLong(K_RESTOCK_SCHEDULED_DAY, day);
        } catch (Throwable ignored) {}
    }

    public static int getRestockTimeOfDay(Villager vill) {
        try {
            if (vill == null) return -1;
            CompoundTag root = getOrCreateRoot(vill);
            return root.contains(K_RESTOCK_TIME_OF_DAY, Tag.TAG_INT) ? root.getInt(K_RESTOCK_TIME_OF_DAY) : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public static void setRestockTimeOfDay(Villager vill, int timeOfDay) {
        try {
            if (vill == null) return;
            getOrCreateRoot(vill).putInt(K_RESTOCK_TIME_OF_DAY, timeOfDay);
        } catch (Throwable ignored) {}
    }

    public static void resetDailyTradingState(Villager vill) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            root.remove(K_LAST_DAILY_CYCLE_DAY);
            root.remove(K_RESTOCK_SCHEDULED_DAY);
            root.remove(K_RESTOCK_TIME_OF_DAY);
        } catch (Throwable ignored) {}
    }

    public static boolean hasPendingPurchaseCheck(Villager vill) {
        try {
            if (vill == null) return false;
            return getOrCreateRoot(vill).getBoolean(K_PENDING_PURCHASE_CHECK);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void setPendingPurchaseCheck(Villager vill, boolean pending) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            if (pending) root.putBoolean(K_PENDING_PURCHASE_CHECK, true);
            else root.remove(K_PENDING_PURCHASE_CHECK);
        } catch (Throwable ignored) {}
    }

    public static void notifyHallChanged(ServerLevel level, BlockPos hallPos) {
        try {
            if (level == null || hallPos == null) return;
            double r = MAX_HALL_DISTANCE_BLOCKS + 8.0D;
            AABB box = new AABB(hallPos).inflate(r, MAX_HALL_VERTICAL_DELTA + 8.0D, r);
            for (Villager vill : level.getEntitiesOfClass(Villager.class, box)) {
                if (vill == null || !vill.isAlive()) continue;
                if (VillagerBrain.getMode(vill) != VillagerBrain.Mode.TRADING) continue;
                RegisteredHall reg = getRegisteredHall(vill);
                if (reg == null) continue;
                if (!String.valueOf(level.dimension().location()).equals(reg.dimId())) continue;
                if (!hallPos.equals(reg.pos())) continue;
                setPendingPurchaseCheck(vill, true);
            }
        } catch (Throwable ignored) {}
    }

    private static CompoundTag getOrCreateRoot(Villager vill) {
        CompoundTag pd = vill.getPersistentData();
        if (!pd.contains(TAG_ROOT, Tag.TAG_COMPOUND)) {
            pd.put(TAG_ROOT, new CompoundTag());
        }
        return pd.getCompound(TAG_ROOT);
    }

    private record Vec3Like(double x, double y, double z) {}
}
