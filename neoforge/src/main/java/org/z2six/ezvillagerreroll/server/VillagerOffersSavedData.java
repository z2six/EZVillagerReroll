// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/VillagerOffersSavedData.java
package org.z2six.ezvillagerreroll.server;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.slf4j.Logger;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.mixin.AbstractVillagerAccessor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persisted, world-scoped storage of villager offers keyed by villager UUID.
 *
 * Robustness goals:
 * - Store "canonical" offers for each villager UUID.
 * - Re-apply canonical offers on MerchantMenu open.
 * - Update canonical offers after EZVR rerolls.
 *
 * IMPORTANT: In 1.21.x, MerchantOffer.CODEC parsing/encoding should use registry-aware ops.
 * Using plain NbtOps can cause partial decode failures (dropping offers).
 *
 * Storage format:
 * - Root: { Version:int, Entries:[{ UUID:string, Offers:[{v:Tag}, ...] }, ...] }
 * - Offers list is ALWAYS a list of CompoundTag wrappers, even if the encoded tag isn't a compound.
 */
public final class VillagerOffersSavedData extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String DATA_NAME = "ezvr_villager_offers";

    private static final String TAG_VERSION = "Version";
    private static final String TAG_ENTRIES = "Entries";

    private static final String TAG_UUID = "UUID";
    private static final String TAG_OFFERS = "Offers";

    // Wrapper key: each entry in Offers is a CompoundTag { "v": <encoded MerchantOffer tag> }
    private static final String TAG_WRAP_VALUE = "v";

    private static final int CURRENT_VERSION = 2;

    public static final Factory<VillagerOffersSavedData> FACTORY =
            new Factory<>(VillagerOffersSavedData::new, VillagerOffersSavedData::load, DataFixTypes.LEVEL);

    private final Map<UUID, ListTag> offersByVillager = new HashMap<>();

    public VillagerOffersSavedData() {}

    public static VillagerOffersSavedData get(ServerLevel level) {
        try {
            if (level == null) return null;

            DimensionDataStorage storage = level.getDataStorage();
            if (storage == null) return null;

            return storage.computeIfAbsent(FACTORY, DATA_NAME);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] VillagerOffersSavedData.get(ServerLevel) failed", t);
            return null;
        }
    }

    public static VillagerOffersSavedData get(Level level) {
        try {
            if (!(level instanceof ServerLevel sl)) return null;
            return get(sl);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] VillagerOffersSavedData.get(Level) failed", t);
            return null;
        }
    }

    public static VillagerOffersSavedData load(CompoundTag root, HolderLookup.Provider registries) {
        VillagerOffersSavedData data = new VillagerOffersSavedData();

        try {
            if (root == null) return data;

            int version = 0;
            try {
                version = root.getInt(TAG_VERSION);
            } catch (Throwable ignored) {}

            ListTag entries;
            try {
                entries = root.getList(TAG_ENTRIES, Tag.TAG_COMPOUND);
            } catch (Throwable t) {
                entries = null;
            }

            if (entries == null) {
                LOGGER.debug("[EZVR] VillagerOffersSavedData.load: no Entries tag; starting empty.");
                return data;
            }

            int loaded = 0;

            for (int i = 0; i < entries.size(); i++) {
                try {
                    CompoundTag e = entries.getCompound(i);
                    if (e == null) continue;

                    String uuidStr = e.getString(TAG_UUID);
                    if (uuidStr == null || uuidStr.isEmpty()) continue;

                    UUID uuid;
                    try {
                        uuid = UUID.fromString(uuidStr);
                    } catch (Throwable ignored) {
                        continue;
                    }

                    ListTag offers;
                    try {
                        offers = e.getList(TAG_OFFERS, Tag.TAG_COMPOUND);
                    } catch (Throwable t) {
                        continue;
                    }

                    ListTag copy = new ListTag();
                    for (int j = 0; j < offers.size(); j++) {
                        try {
                            CompoundTag wrap = offers.getCompound(j);
                            if (wrap != null) copy.add(wrap.copy());
                        } catch (Throwable ignored) {}
                    }

                    data.offersByVillager.put(uuid, copy);
                    loaded++;
                } catch (Throwable t) {
                    LOGGER.debug("[EZVR] VillagerOffersSavedData.load: entry parse failed (soft): {}", t.toString());
                }
            }

            EZVillagerReroll.LOG().info("[EZVR] VillagerOffersSavedData.load: loaded {} offer entries (version={}).", loaded, version);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] VillagerOffersSavedData.load failed (soft; returning partial/empty)", t);
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        try {
            if (root == null) root = new CompoundTag();

            root.putInt(TAG_VERSION, CURRENT_VERSION);

            ListTag entries = new ListTag();

            int written = 0;
            for (var it : offersByVillager.entrySet()) {
                try {
                    UUID uuid = it.getKey();
                    ListTag offers = it.getValue();
                    if (uuid == null || offers == null) continue;

                    CompoundTag e = new CompoundTag();
                    e.putString(TAG_UUID, uuid.toString());

                    ListTag copy = new ListTag();
                    for (int j = 0; j < offers.size(); j++) {
                        try {
                            CompoundTag wrap = offers.getCompound(j);
                            if (wrap != null) copy.add(wrap.copy());
                        } catch (Throwable ignored) {}
                    }

                    e.put(TAG_OFFERS, copy);
                    entries.add(e);
                    written++;
                } catch (Throwable t) {
                    LOGGER.debug("[EZVR] VillagerOffersSavedData.save: entry write failed (soft): {}", t.toString());
                }
            }

            root.put(TAG_ENTRIES, entries);

            if (EZVillagerReroll.LOG().isDebugEnabled()) {
                EZVillagerReroll.LOG().debug("[EZVR] VillagerOffersSavedData.save: wrote {} offer entries.", written);
            }
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] VillagerOffersSavedData.save failed (soft)", t);
        }

        return root;
    }

    public boolean has(UUID villagerId) {
        try {
            if (villagerId == null) return false;
            return offersByVillager.containsKey(villagerId);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Returns a defensive deep copy of the stored offers ListTag for UI/debug purposes.
     * This is intentionally "raw" (wrappers { "v": tag }) so it can be shipped to clients.
     *
     * @return copy of stored offers, or empty ListTag if missing.
     */
    public ListTag getStoredOffersTag(UUID villagerId) {
        try {
            if (villagerId == null) return new ListTag();

            ListTag stored = offersByVillager.get(villagerId);
            if (stored == null) return new ListTag();

            ListTag copy = new ListTag();
            int n = Math.min(256, stored.size());
            for (int i = 0; i < n; i++) {
                try {
                    CompoundTag wrap = stored.getCompound(i);
                    if (wrap != null) copy.add(wrap.copy());
                } catch (Throwable ignored) {}
            }

            EZVillagerReroll.LOG().debug("[EZVR] OffersSavedData.getStoredOffersTag: villager={} offers={}", villagerId, copy.size());
            return copy;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] OffersSavedData.getStoredOffersTag failed (soft): {}", t.toString());
            return new ListTag();
        }
    }

    /**
     * Capture current offers from villager into world data.
     */
    public void capture(AbstractVillager villager) {
        try {
            if (villager == null) return;

            UUID id = villager.getUUID();
            if (id == null) return;

            ServerLevel level = safeServerLevel(villager.level());
            if (level == null) {
                EZVillagerReroll.LOG().warn("[EZVR] OffersSavedData.capture: non-server level; skipping (villager={})", id);
                return;
            }

            MerchantOffers offers = safeGetOffers(villager);
            if (offers == null) {
                EZVillagerReroll.LOG().warn("[EZVR] OffersSavedData.capture: offers null; skipping (villager={})", id);
                return;
            }

            ListTag list = serializeOffersCodec(level, offers);
            if (list == null) return;

            offersByVillager.put(id, list);
            this.setDirty();

            EZVillagerReroll.LOG().info("[EZVR] OffersSavedData.capture: stored {} offers (villager={})", offers.size(), id);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] OffersSavedData.capture failed (soft)", t);
        }
    }

    /**
     * Apply stored offers to villager, if present.
     *
     * @return true if applied, false otherwise.
     */
    public boolean apply(AbstractVillager villager) {
        try {
            if (villager == null) return false;

            UUID id = villager.getUUID();
            if (id == null) return false;

            ServerLevel level = safeServerLevel(villager.level());
            if (level == null) {
                EZVillagerReroll.LOG().warn("[EZVR] OffersSavedData.apply: non-server level; skipping (villager={})", id);
                return false;
            }

            ListTag stored = offersByVillager.get(id);
            if (stored == null) return false;

            MerchantOffers offers = deserializeOffersCodec(level, stored);
            if (offers == null) return false;

            ((AbstractVillagerAccessor) villager).ezvr$setOffers(offers);

            EZVillagerReroll.LOG().info("[EZVR] OffersSavedData.apply: applied {} offers (villager={})", offers.size(), id);
            return true;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] OffersSavedData.apply failed (soft)", t);
            return false;
        }
    }

    /**
     * Sync the current villager offers to the player's open MerchantMenu (Villager-only fields).
     */
    public static void syncOffersToPlayerIfPossible(ServerPlayer sp, net.minecraft.world.inventory.MerchantMenu menu, AbstractVillager merchant) {
        try {
            if (sp == null || menu == null || merchant == null) return;

            if (merchant instanceof Villager vill) {
                sp.sendMerchantOffers(
                        menu.containerId,
                        vill.getOffers(),
                        vill.getVillagerData().getLevel(),
                        vill.getVillagerXp(),
                        vill.showProgressBar(),
                        vill.canRestock()
                );
            }
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] syncOffersToPlayerIfPossible failed (soft): {}", t.toString());
        }
    }

    // ---------------------------------------------
    // CODEC-BASED SERIALIZATION (registry-aware)
    // ---------------------------------------------

    private static ListTag serializeOffersCodec(ServerLevel level, MerchantOffers offers) {
        try {
            if (level == null || offers == null) return null;

            var ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess());

            ListTag list = new ListTag();

            for (int i = 0; i < offers.size(); i++) {
                final int idx = i;

                MerchantOffer offer;
                try {
                    offer = offers.get(i);
                } catch (Throwable t) {
                    continue;
                }
                if (offer == null) continue;

                try {
                    var res = MerchantOffer.CODEC.encodeStart(ops, offer);

                    res.resultOrPartial(msg ->
                                    EZVillagerReroll.LOG().warn("[EZVR] MerchantOffer encode failed (idx={}): {}", idx, msg))
                            .ifPresent(tag -> {
                                try {
                                    CompoundTag wrap = new CompoundTag();
                                    wrap.put(TAG_WRAP_VALUE, tag);
                                    list.add(wrap);
                                } catch (Throwable ignored) {}
                            });
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().warn("[EZVR] MerchantOffer encode threw (idx={}): {}", idx, t.toString());
                }
            }

            return list;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] serializeOffersCodec failed (soft)", t);
            return null;
        }
    }

    private static MerchantOffers deserializeOffersCodec(ServerLevel level, ListTag list) {
        try {
            if (level == null || list == null) return null;

            var ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess());

            MerchantOffers offers = new MerchantOffers();

            for (int i = 0; i < list.size(); i++) {
                final int idx = i;

                CompoundTag wrap;
                try {
                    wrap = list.getCompound(i);
                } catch (Throwable t) {
                    continue;
                }
                if (wrap == null) continue;

                Tag tag;
                try {
                    tag = wrap.get(TAG_WRAP_VALUE);
                } catch (Throwable t) {
                    tag = null;
                }
                if (tag == null) continue;

                try {
                    var res = MerchantOffer.CODEC.parse(ops, tag);
                    res.resultOrPartial(msg ->
                                    EZVillagerReroll.LOG().warn("[EZVR] MerchantOffer decode failed (idx={}): {}", idx, msg))
                            .ifPresent(offers::add);
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().warn("[EZVR] MerchantOffer decode threw (idx={}): {}", idx, t.toString());
                }
            }

            return offers;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] deserializeOffersCodec failed (soft)", t);
            return null;
        }
    }

    private static MerchantOffers safeGetOffers(AbstractVillager villager) {
        try {
            return villager.getOffers();
        } catch (Throwable t) {
            return null;
        }
    }

    private static ServerLevel safeServerLevel(Level level) {
        try {
            return (level instanceof ServerLevel sl) ? sl : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
