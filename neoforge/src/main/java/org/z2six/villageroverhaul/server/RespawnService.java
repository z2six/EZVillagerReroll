// neoforge/src/main/java/org/z2six/villageroverhaul/server/RespawnService.java
package org.z2six.villageroverhaul.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.config.ServerConfig;
import org.z2six.villageroverhaul.logic.PaymentUtil;
import org.z2six.villageroverhaul.server.ai.VillagerBrain;

import java.util.*;

/**
 * Server-side respawn snapshot capture + respawn execution.
 */
public final class RespawnService {

    public static final String TAG_RESPAWN_ID = "ezvr_respawn_id"; // UUID

    private RespawnService() {}

    public static UUID ensureRespawnId(Villager vill) {
        try {
            if (vill == null) return null;
            CompoundTag pd = vill.getPersistentData();
            if (pd == null) return null;
            if (pd.hasUUID(TAG_RESPAWN_ID)) return pd.getUUID(TAG_RESPAWN_ID);
            UUID id = UUID.randomUUID();
            pd.putUUID(TAG_RESPAWN_ID, id);
            return id;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static int computeRespawnCost(int recruitCostAtDeath) {
        double mult = ServerConfig.respawnCostMultiplier;
        if (Double.isNaN(mult) || Double.isInfinite(mult) || mult < 0.0) mult = 0.0;
        double c = Math.max(0, recruitCostAtDeath) * mult;
        long r = Math.round(c);
        if (r < 0) return 0;
        if (r > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) r;
    }

    public static void captureOnDeath(Villager vill) {
        try {
            if (vill == null) return;
            if (!(vill.level() instanceof ServerLevel level)) return;
            if (level.isClientSide()) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (VillagerReleaseService.isReleasedNoRespawn(vill)) return;

            UUID owner = RecruitService.getRecruiterUuid(vill);
            if (owner == null) return;

            UUID rid = ensureRespawnId(vill);
            if (rid == null) return;

            RespawnSavedData data = RespawnSavedData.get(level);
            Map<UUID, RespawnSavedData.Snapshot> byId =
                    data.byOwner().computeIfAbsent(owner, k -> new LinkedHashMap<>());

            RespawnSavedData.Snapshot snap = byId.get(rid);
            if (snap == null) {
                snap = new RespawnSavedData.Snapshot();
                snap.owner = owner;
                snap.respawnId = rid;
                snap.deaths = 0;
                byId.put(rid, snap);
            }

            // increment deaths counter
            snap.deaths = Math.max(0, snap.deaths) + 1;

            snap.recruitCostAtDeath = Math.max(0, RecruitService.computeRecruitCost(vill));
            snap.capturedAtGameTime = level.getGameTime();

            // name/profession info for list UI
            try {
                snap.nameJson = net.minecraft.network.chat.Component.Serializer.toJson(vill.getName(), level.registryAccess());
            } catch (Throwable ignored) {
                snap.nameJson = "{\"text\":\"Villager\"}";
            }
            try {
                ResourceLocation key = BuiltInRegistries.VILLAGER_PROFESSION.getKey(vill.getVillagerData().getProfession());
                snap.professionId = key == null ? "minecraft:none" : key.toString();
            } catch (Throwable ignored) {
                snap.professionId = "minecraft:none";
            }

            // Full entity state: save without entity id/pos, then strip UUID so we can respawn a new entity.
            CompoundTag tag = new CompoundTag();
            vill.saveWithoutId(tag);

            // Ensure the respawn id remains stable
            try {
                CompoundTag pd = tag.contains("ForgeData", CompoundTag.TAG_COMPOUND) ? tag.getCompound("ForgeData") : null;
                if (pd != null) {
                    pd.putUUID(TAG_RESPAWN_ID, rid);
                    tag.put("ForgeData", pd);
                }
            } catch (Throwable ignored) {}

            tag.remove("UUID");
            tag.remove("Pos");
            tag.remove("Motion");
            tag.remove("Rotation");
            tag.remove("FallDistance");
            tag.remove("OnGround");

            snap.villagerNbt = tag;

            data.setDirty();

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [respawn] captured death snapshot owner={} rid={} recruitCost={} deaths={}",
                    owner, rid, snap.recruitCostAtDeath, snap.deaths);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] RespawnService.captureOnDeath failed", t);
        }
    }

    public static List<RespawnSavedData.Snapshot> listForOwner(ServerPlayer sp, ServerLevel level) {
        try {
            if (sp == null || level == null) return List.of();
            RespawnSavedData data = RespawnSavedData.get(level);
            Map<UUID, RespawnSavedData.Snapshot> map = data.byOwner().get(sp.getUUID());
            if (map == null || map.isEmpty()) return List.of();
            return new ArrayList<>(map.values());
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    public static RespawnSavedData.Snapshot getForOwner(ServerPlayer sp, ServerLevel level, UUID respawnId) {
        try {
            if (sp == null || level == null || respawnId == null) return null;
            RespawnSavedData data = RespawnSavedData.get(level);
            Map<UUID, RespawnSavedData.Snapshot> map = data.byOwner().get(sp.getUUID());
            if (map == null) return null;
            return map.get(respawnId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Remove a respawn snapshot from the owner's list (server authoritative).
     * Returns true only if the entry existed and was removed.
     */
    public static boolean purgeForOwner(ServerPlayer sp, ServerLevel level, UUID respawnId) {
        try {
            if (sp == null || level == null || respawnId == null) return false;
            RespawnSavedData data = RespawnSavedData.get(level);
            Map<UUID, RespawnSavedData.Snapshot> map = data.byOwner().get(sp.getUUID());
            if (map == null || map.isEmpty()) return false;

            RespawnSavedData.Snapshot removed = map.remove(respawnId);
            if (removed == null) return false;
            if (map.isEmpty()) data.byOwner().remove(sp.getUUID());
            data.setDirty();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Villager respawn(ServerPlayer sp, ServerLevel level, RespawnSavedData.Snapshot snap, BlockPos anchorPos) {
        try {
            if (sp == null || level == null || snap == null) return null;
            if (anchorPos == null) anchorPos = sp.blockPosition();

            // Consume the snapshot up-front to prevent duplication spam (multi-packet / hacked client).
            // If payment/spawn fails, we restore the snapshot.
            RespawnSavedData data = RespawnSavedData.get(level);
            Map<UUID, RespawnSavedData.Snapshot> map = data.byOwner().get(sp.getUUID());
            if (map == null || snap.respawnId == null) return null;
            if (map.remove(snap.respawnId) == null) return null;
            if (map.isEmpty()) data.byOwner().remove(sp.getUUID());
            data.setDirty();

            int cost = computeRespawnCost(snap.recruitCostAtDeath);
            if (!PaymentUtil.tryCharge(sp, cost)) {
                try {
                    Map<UUID, RespawnSavedData.Snapshot> back = data.byOwner().computeIfAbsent(sp.getUUID(), k -> new LinkedHashMap<>());
                    back.put(snap.respawnId, snap);
                    data.setDirty();
                } catch (Throwable ignored) {}
                return null;
            }

            Villager v = EntityType.VILLAGER.create(level);
            if (v == null) {
                try {
                    Map<UUID, RespawnSavedData.Snapshot> back = data.byOwner().computeIfAbsent(sp.getUUID(), k -> new LinkedHashMap<>());
                    back.put(snap.respawnId, snap);
                    data.setDirty();
                } catch (Throwable ignored) {}
                return null;
            }

            CompoundTag tag = (snap.villagerNbt == null) ? new CompoundTag() : snap.villagerNbt.copy();
            tag.remove("UUID");
            tag.remove("Pos");
            tag.remove("Motion");
            tag.remove("Rotation");

            // NeoForge persistent data lives under ForgeData (and sometimes NeoForgeData).
            // v.load(tag) is not guaranteed to restore it, so explicitly merge it.
            try {
                CompoundTag forge = null;
                if (tag.contains("ForgeData", CompoundTag.TAG_COMPOUND)) forge = tag.getCompound("ForgeData");
                else if (tag.contains("NeoForgeData", CompoundTag.TAG_COMPOUND)) forge = tag.getCompound("NeoForgeData");
                if (forge != null) {
                    v.getPersistentData().merge(forge.copy());
                }
            } catch (Throwable ignored) {}

            try {
                v.load(tag);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] RespawnService.respawn: villager.load failed", t);
            }

            // Merge persistent data again (load may overwrite/clear state depending on mappings).
            try {
                CompoundTag forge = null;
                if (tag.contains("ForgeData", CompoundTag.TAG_COMPOUND)) forge = tag.getCompound("ForgeData");
                else if (tag.contains("NeoForgeData", CompoundTag.TAG_COMPOUND)) forge = tag.getCompound("NeoForgeData");
                if (forge != null) {
                    v.getPersistentData().merge(forge.copy());
                }
            } catch (Throwable ignored) {}

            // ensure stable respawn id and ownership persisted
            try { ensureRespawnId(v); } catch (Throwable ignored) {}
            try {
                // Ensure recruit tags are present even if ForgeData decode failed.
                v.getPersistentData().putBoolean(RecruitService.TAG_RECRUITED, true);
                v.getPersistentData().putUUID(RecruitService.TAG_RECRUITED_BY, sp.getUUID());
            } catch (Throwable ignored) {}

            // Re-apply all runtime-only setup that normally happens on natural spawn/join.
            try { VillagerStatsService.ensureStats(v); } catch (Throwable ignored) {}
            try { VillagerCombatAttributeService.applyCombatModifiers(v); } catch (Throwable ignored) {}
            try { VillagerBrain.ensureAttached(v); } catch (Throwable ignored) {}
            try { VillagerBrain.setUiPaused(v, false); } catch (Throwable ignored) {}
            try { org.z2six.villageroverhaul.server.ai.VillagerCombatLoadoutService.resetAfterRespawn(v); } catch (Throwable ignored) {}

            // By default, equipment is lost on respawn.
            if (!ServerConfig.respawnKeepEquipment) {
                try {
                    // Clear any stored combat loadout items as well (treat as equipment loss on death).
                    try { v.getPersistentData().remove("ezvr_combat_loadout"); } catch (Throwable ignored2) {}

                    v.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    v.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, ItemStack.EMPTY);
                    v.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
                    v.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
                    v.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
                    v.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
                } catch (Throwable ignored) {}
            }

            if (!ServerConfig.respawnKeepInventory) {
                try {
                    var inv = v.getInventory();
                    if (inv != null) {
                        for (int i = 0; i < inv.getContainerSize(); i++) {
                            inv.setItem(i, ItemStack.EMPTY);
                        }
                        inv.setChanged();
                    }
                } catch (Throwable ignored) {}
            }

            double x = anchorPos.getX() + 0.5;
            double y = anchorPos.getY() + 1.0;
            double z = anchorPos.getZ() + 0.5;
            v.moveTo(x, y, z, sp.getYRot(), 0.0f);

            level.addFreshEntity(v);

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [respawn] respawned villager rid={} for player={} cost={}",
                    snap.respawnId, sp.getGameProfile().getName(), cost);

            return v;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] RespawnService.respawn failed", t);
            return null;
        }
    }
}
