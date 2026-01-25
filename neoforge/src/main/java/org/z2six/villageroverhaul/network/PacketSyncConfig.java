// neoforge\src\main\java\org\z2six\villageroverhaul\network\PacketSyncConfig.java
package org.z2six.villageroverhaul.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public final class PacketSyncConfig implements CustomPacketPayload {

    public static final Type<PacketSyncConfig> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_config"));

    public int version;
    public int hash;
    public String costItemOrTag;
    public int[] costsByLevel; // length 6
    public boolean preferWallet;
    public int cooldownTicks;
    public int perVillagerDaily;

    public int freeOffers;
    public int costPerOffer;
    public int maxDeductibleLockedOffers;
    public int autoHourlyThreshold;
    public double autoHourlyDiscountOrIncreasePct;

    public boolean allowAfterTradeUsed;

    public double manualRerollXpPerOffer;

    public double generosityMinPct;
    public double generosityMaxPct;

    public double timelinessMinPct;
    public double timelinessMaxPct;

    public double intellectMinPct;
    public double intellectMaxPct;

    public int hoarderExtraOffersMin;
    public int hoarderExtraOffersMax;

    public int recruitCostMin;
    public int recruitCostMax;
    public double respawnCostMultiplier;
    public boolean respawnKeepEquipment;
    public boolean respawnKeepInventory;

    // combat bounds
    public double vitalityMinHealth;
    public double vitalityMaxHealth;

    public double agilityMinSpeed;
    public double agilityMaxSpeed;

    public double strengthMinDamage;
    public double strengthMaxDamage;

    public double armorMin;
    public double armorMax;

    // farming baselines (manual farming)
    public int manualFarmBaseRange;
    public int manualFarmWorkStartTick;
    public int manualFarmWorkEndTick;
    public int plantWhispererIntervalSeconds;
    public double plantWhispererBaseChancePct;

    // farming stat bounds
    public double motivationMinPct;
    public double motivationMaxPct;

    public double efficiencyMinPct;
    public double efficiencyMaxPct;

    public double plantWhispererMinPct;
    public double plantWhispererMaxPct;

    public double rangerMinPct;
    public double rangerMaxPct;

    // modules
    public boolean enableMerchantModule;
    public boolean enableCombatModule;
    public boolean enableFarmingModule;

    public PacketSyncConfig() {}

    public static final StreamCodec<FriendlyByteBuf, PacketSyncConfig> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketSyncConfig decode(FriendlyByteBuf buf) {
            PacketSyncConfig p = new PacketSyncConfig();
            p.version = buf.readVarInt();
            p.hash = buf.readVarInt();
            p.costItemOrTag = buf.readUtf(256);

            int len = buf.readVarInt();
            if (len < 0) len = 0;
            if (len > 64) len = 64;
            p.costsByLevel = new int[len];
            for (int i = 0; i < len; i++) p.costsByLevel[i] = Math.max(0, buf.readVarInt());

            p.preferWallet = buf.readBoolean();
            p.cooldownTicks = buf.readVarInt();
            p.perVillagerDaily = buf.readVarInt();

            try { buf.readVarInt(); } catch (Throwable ignored) {}

            p.freeOffers = 0;
            p.costPerOffer = 0;
            p.maxDeductibleLockedOffers = 0;
            p.autoHourlyThreshold = 0;
            p.autoHourlyDiscountOrIncreasePct = 0.0;

            try { p.freeOffers = buf.readVarInt(); } catch (Throwable ignored) {}
            try { p.costPerOffer = buf.readVarInt(); } catch (Throwable ignored) {}
            try { p.maxDeductibleLockedOffers = buf.readVarInt(); } catch (Throwable ignored) {}
            try { p.autoHourlyThreshold = buf.readVarInt(); } catch (Throwable ignored) {}
            try { p.autoHourlyDiscountOrIncreasePct = buf.readDouble(); } catch (Throwable ignored) {}

            try { p.allowAfterTradeUsed = buf.readBoolean(); }
            catch (Throwable ignored) { p.allowAfterTradeUsed = true; }

            p.manualRerollXpPerOffer = 0.0;
            try { p.manualRerollXpPerOffer = buf.readDouble(); } catch (Throwable ignored) {}

            // Defaults for bounds (match ServerConfig defaults)
            p.generosityMinPct = -20.0;
            p.generosityMaxPct = 20.0;

            p.timelinessMinPct = -20.0;
            p.timelinessMaxPct = 20.0;

            p.intellectMinPct = -20.0;
            p.intellectMaxPct = 20.0;

            p.hoarderExtraOffersMin = -3;
            p.hoarderExtraOffersMax = 3;

            p.recruitCostMin = 8;
            p.recruitCostMax = 64;
            p.respawnCostMultiplier = 2.0;
            p.respawnKeepEquipment = false;
            p.respawnKeepInventory = true;

            // combat defaults
            p.vitalityMinHealth = -6.0;
            p.vitalityMaxHealth = 10.0;

            p.agilityMinSpeed = -0.02;
            p.agilityMaxSpeed = 0.03;

            p.strengthMinDamage = -1.0;
            p.strengthMaxDamage = 3.0;

            p.armorMin = -5.0;
            p.armorMax = 15.0;

            // manual farming defaults
            p.manualFarmBaseRange = 10;
            p.manualFarmWorkStartTick = 2000;
            p.manualFarmWorkEndTick = 9000;
            p.plantWhispererIntervalSeconds = 60;
            p.plantWhispererBaseChancePct = 50.0;

            // farming stat defaults (match ServerConfig defaults)
            p.motivationMinPct = -20.0;
            p.motivationMaxPct = 20.0;

            p.efficiencyMinPct = -20.0;
            p.efficiencyMaxPct = 20.0;

            p.plantWhispererMinPct = -20.0;
            p.plantWhispererMaxPct = 20.0;

            p.rangerMinPct = -20.0;
            p.rangerMaxPct = 20.0;

            // module defaults
            p.enableMerchantModule = true;
            p.enableCombatModule = true;
            p.enableFarmingModule = true;

            // trait bounds
            try { p.generosityMinPct = buf.readDouble(); } catch (Throwable ignored) {}
            try { p.generosityMaxPct = buf.readDouble(); } catch (Throwable ignored) {}

            try { p.timelinessMinPct = buf.readDouble(); } catch (Throwable ignored) {}
            try { p.timelinessMaxPct = buf.readDouble(); } catch (Throwable ignored) {}

            try { p.intellectMinPct = buf.readDouble(); } catch (Throwable ignored) {}
            try { p.intellectMaxPct = buf.readDouble(); } catch (Throwable ignored) {}

            // hoarder clamp
            try { p.hoarderExtraOffersMin = buf.readVarInt(); } catch (Throwable ignored) {}
            try { p.hoarderExtraOffersMax = buf.readVarInt(); } catch (Throwable ignored) {}

            // recruit bounds
            try { p.recruitCostMin = Math.max(0, buf.readVarInt()); } catch (Throwable ignored) {}
            try { p.recruitCostMax = Math.max(0, buf.readVarInt()); } catch (Throwable ignored) {}

            // respawn multiplier (append-only, safe to be missing)
            try { p.respawnCostMultiplier = buf.readDouble(); } catch (Throwable ignored) { p.respawnCostMultiplier = 2.0; }
            if (Double.isNaN(p.respawnCostMultiplier) || Double.isInfinite(p.respawnCostMultiplier) || p.respawnCostMultiplier < 0.0) {
                p.respawnCostMultiplier = 0.0;
            }
            // combat bounds (append-only, safe to be missing)
            try { p.vitalityMinHealth = buf.readDouble(); } catch (Throwable ignored) {}
            try { p.vitalityMaxHealth = buf.readDouble(); } catch (Throwable ignored) {}

            try { p.agilityMinSpeed = buf.readDouble(); } catch (Throwable ignored) {}
            try { p.agilityMaxSpeed = buf.readDouble(); } catch (Throwable ignored) {}

            try { p.strengthMinDamage = buf.readDouble(); } catch (Throwable ignored) {}
            try { p.strengthMaxDamage = buf.readDouble(); } catch (Throwable ignored) {}

            try { p.armorMin = buf.readDouble(); } catch (Throwable ignored) {}
            try { p.armorMax = buf.readDouble(); } catch (Throwable ignored) {}

            // respawn keep-equipment toggle (append-only)
            try { p.respawnKeepEquipment = buf.readBoolean(); } catch (Throwable ignored) { p.respawnKeepEquipment = false; }
            // respawn keep-inventory toggle (append-only)
            try { p.respawnKeepInventory = buf.readBoolean(); } catch (Throwable ignored) { p.respawnKeepInventory = true; }

            // manual farming baselines (append-only)
            try { p.manualFarmBaseRange = Math.max(1, buf.readVarInt()); } catch (Throwable ignored) {}
            try { p.manualFarmWorkStartTick = buf.readVarInt(); } catch (Throwable ignored) {}
            try { p.manualFarmWorkEndTick = buf.readVarInt(); } catch (Throwable ignored) {}
            try { p.plantWhispererIntervalSeconds = Math.max(1, buf.readVarInt()); } catch (Throwable ignored) {}
            try { p.plantWhispererBaseChancePct = buf.readDouble(); } catch (Throwable ignored) {}
            if (Double.isNaN(p.plantWhispererBaseChancePct) || Double.isInfinite(p.plantWhispererBaseChancePct)) p.plantWhispererBaseChancePct = 50.0;
            if (p.plantWhispererBaseChancePct < 0.0) p.plantWhispererBaseChancePct = 0.0;
            if (p.plantWhispererBaseChancePct > 100.0) p.plantWhispererBaseChancePct = 100.0;

            // farming stat bounds (append-only)
            try { p.motivationMinPct = buf.readDouble(); } catch (Throwable ignored) {}
            try { p.motivationMaxPct = buf.readDouble(); } catch (Throwable ignored) {}
            try { p.efficiencyMinPct = buf.readDouble(); } catch (Throwable ignored) {}
            try { p.efficiencyMaxPct = buf.readDouble(); } catch (Throwable ignored) {}
            try { p.plantWhispererMinPct = buf.readDouble(); } catch (Throwable ignored) {}
            try { p.plantWhispererMaxPct = buf.readDouble(); } catch (Throwable ignored) {}
            try { p.rangerMinPct = buf.readDouble(); } catch (Throwable ignored) {}
            try { p.rangerMaxPct = buf.readDouble(); } catch (Throwable ignored) {}

            // modules (append-only)
            try { p.enableMerchantModule = buf.readBoolean(); } catch (Throwable ignored) { p.enableMerchantModule = true; }
            try { p.enableCombatModule = buf.readBoolean(); } catch (Throwable ignored) { p.enableCombatModule = true; }
            try { p.enableFarmingModule = buf.readBoolean(); } catch (Throwable ignored) { p.enableFarmingModule = true; }

            return p;
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketSyncConfig p) {
            buf.writeVarInt(p.version);
            buf.writeVarInt(p.hash);
            buf.writeUtf(p.costItemOrTag == null ? "" : p.costItemOrTag, 256);

            int[] arr = p.costsByLevel == null ? new int[0] : p.costsByLevel;
            buf.writeVarInt(arr.length);
            for (int v : arr) buf.writeVarInt(Math.max(0, v));

            buf.writeBoolean(p.preferWallet);
            buf.writeVarInt(p.cooldownTicks);
            buf.writeVarInt(p.perVillagerDaily);

            buf.writeVarInt(0); // RESERVED

            buf.writeVarInt(Math.max(0, p.freeOffers));
            buf.writeVarInt(Math.max(0, p.costPerOffer));
            buf.writeVarInt(Math.max(0, p.maxDeductibleLockedOffers));
            buf.writeVarInt(Math.max(0, p.autoHourlyThreshold));
            buf.writeDouble(Math.max(0.0, p.autoHourlyDiscountOrIncreasePct));

            buf.writeBoolean(p.allowAfterTradeUsed);

            buf.writeDouble(Math.max(0.0, p.manualRerollXpPerOffer));

            buf.writeDouble(p.generosityMinPct);
            buf.writeDouble(p.generosityMaxPct);

            buf.writeDouble(p.timelinessMinPct);
            buf.writeDouble(p.timelinessMaxPct);

            buf.writeDouble(p.intellectMinPct);
            buf.writeDouble(p.intellectMaxPct);

            buf.writeVarInt(p.hoarderExtraOffersMin);
            buf.writeVarInt(p.hoarderExtraOffersMax);

            buf.writeVarInt(Math.max(0, p.recruitCostMin));
            buf.writeVarInt(Math.max(0, p.recruitCostMax));

            buf.writeDouble(Math.max(0.0, p.respawnCostMultiplier));

            // combat bounds (append-only)
            buf.writeDouble(p.vitalityMinHealth);
            buf.writeDouble(p.vitalityMaxHealth);

            buf.writeDouble(p.agilityMinSpeed);
            buf.writeDouble(p.agilityMaxSpeed);

            buf.writeDouble(p.strengthMinDamage);
            buf.writeDouble(p.strengthMaxDamage);

            buf.writeDouble(p.armorMin);
            buf.writeDouble(p.armorMax);

            // respawn keep-equipment toggle (append-only)
            buf.writeBoolean(p.respawnKeepEquipment);
            // respawn keep-inventory toggle (append-only)
            buf.writeBoolean(p.respawnKeepInventory);

            // manual farming baselines (append-only)
            buf.writeVarInt(Math.max(1, p.manualFarmBaseRange));
            buf.writeVarInt(p.manualFarmWorkStartTick);
            buf.writeVarInt(p.manualFarmWorkEndTick);
            buf.writeVarInt(Math.max(1, p.plantWhispererIntervalSeconds));
            buf.writeDouble(Math.max(0.0, Math.min(100.0, p.plantWhispererBaseChancePct)));

            // farming stat bounds (append-only)
            buf.writeDouble(p.motivationMinPct);
            buf.writeDouble(p.motivationMaxPct);
            buf.writeDouble(p.efficiencyMinPct);
            buf.writeDouble(p.efficiencyMaxPct);
            buf.writeDouble(p.plantWhispererMinPct);
            buf.writeDouble(p.plantWhispererMaxPct);
            buf.writeDouble(p.rangerMinPct);
            buf.writeDouble(p.rangerMaxPct);

            // modules (append-only)
            buf.writeBoolean(p.enableMerchantModule);
            buf.writeBoolean(p.enableCombatModule);
            buf.writeBoolean(p.enableFarmingModule);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
