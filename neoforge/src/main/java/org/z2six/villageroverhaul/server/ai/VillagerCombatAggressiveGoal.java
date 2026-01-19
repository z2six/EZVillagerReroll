// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/ai/VillagerCombatAggressiveGoal.java
package org.z2six.villageroverhaul.server.ai;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.combat.CombatSettings;
import org.z2six.villageroverhaul.server.CombatSettingsService;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Combat module: AGGRESSIVE (activation only in Step 1).
 *
 * IMPORTANT (Step 1 behavior):
 * - Does NOT claim MOVE/JUMP/LOOK flags (so it will not interfere with existing movement AI yet).
 * - Only indicates "this mode is active" and provides a safe hook point for Step 3 AI.
 */
public final class VillagerCombatAggressiveGoal extends Goal {

    private final Villager vill;
    private boolean loggedActive = false;
    private java.util.UUID targetUuid = null;
    private long lastNoThreatLogAt = 0L;
    private long lastRejectLogAt = 0L;
    private long lastScanAt = 0L;

    public VillagerCombatAggressiveGoal(Villager vill) {
        this.vill = vill;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        try {
            if (vill == null) return false;
            if (vill.level() == null || vill.level().isClientSide()) return false;

            if (!VillagerBrain.shouldCombatActNow(vill)) return false;
            if (VillagerBrain.isUiPaused(vill)) return false;

            if (VillagerBrain.getCombatMode(vill) != VillagerBrain.CombatMode.AGGRESSIVE) return false;

            CombatSettings settings = CombatSettingsService.getPerVillager(vill);
            if (settings == null) {
                logNoThreat("no_settings");
                return false;
            }
            CombatSettings.ModeSettings m = settings.aggressive;
            Set<String> wl = normalize(m.aggressiveWhitelist);
            Set<String> bl = normalize(m.aggressiveBlacklist);
            if (wl.isEmpty() && bl.isEmpty()) {
                logNoThreat("no_filters");
                return false;
            }

            if (findAggressiveTarget(wl, bl) != null) return true;

            logNoThreat("no_target");
            return false;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerCombatAggressiveGoal.canUse failed (soft): {}", t.toString());
            return false;
        }
    }

    @Override
    public boolean canContinueToUse() {
        try {
            if (vill == null) return false;
            if (vill.level() == null || vill.level().isClientSide()) return false;

            if (!VillagerBrain.shouldCombatActNow(vill)) return false;
            if (VillagerBrain.isUiPaused(vill)) return false;
            if (VillagerBrain.getCombatMode(vill) != VillagerBrain.CombatMode.AGGRESSIVE) return false;

            if (targetUuid == null) return false;
            LivingEntity t = findTargetByUuid(targetUuid);
            return t != null && t.isAlive();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void start() {
        try {
            loggedActive = false;
            targetUuid = null;
            lastNoThreatLogAt = 0L;
            lastRejectLogAt = 0L;
            lastScanAt = 0L;
        } catch (Throwable ignored) {}
    }

    @Override
    public void tick() {
        try {
            // Step 1: do nothing besides optional debug.
            if (!loggedActive) {
                loggedActive = true;
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Combat goal active: AGGRESSIVE (villager={}, mode={})",
                        vill == null ? "null" : vill.getUUID(),
                        vill == null ? "null" : VillagerBrain.getMode(vill).id);
            }

            if (vill == null || vill.level() == null) return;

            LivingEntity target = null;
            if (targetUuid != null) {
                target = findTargetByUuid(targetUuid);
                if (target == null || !target.isAlive()) {
                    targetUuid = null;
                }
            }

            CombatSettings settings = CombatSettingsService.getPerVillager(vill);
            if (settings == null) return;
            CombatSettings.ModeSettings m = settings.aggressive;
            Set<String> wl = normalize(m.aggressiveWhitelist);
            Set<String> bl = normalize(m.aggressiveBlacklist);
            if (wl.isEmpty() && bl.isEmpty()) return;

            LivingEntity found = findAggressiveTarget(wl, bl);
            if (found != null && (target == null || !found.getUUID().equals(targetUuid))) {
                targetUuid = found.getUUID();
                target = found;
            }

            if (target == null) return;

            VillagerCombatDirector.tickAttack(vill, target);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerCombatAggressiveGoal.tick failed (soft): {}", t.toString());
        }
    }

    @Override
    public void stop() {
        try {
            loggedActive = false;
            targetUuid = null;
            lastNoThreatLogAt = 0L;
            lastRejectLogAt = 0L;
            lastScanAt = 0L;
            VillagerCombatDirector.stop(vill);
        } catch (Throwable ignored) {}
    }

    private LivingEntity findAggressiveTarget(Set<String> wl, Set<String> bl) {
        try {
            if (vill == null || vill.level() == null) return null;
            long now = vill.level().getGameTime();
            if ((now - lastScanAt) < 10L) return null;
            lastScanAt = now;

            AABB box = vill.getBoundingBox().inflate(16.0);
            List<LivingEntity> nearby = vill.level().getEntitiesOfClass(LivingEntity.class, box, e -> e != null && e.isAlive());

            LivingEntity best = null;
            double bestDist = Double.MAX_VALUE;

            for (LivingEntity e : nearby) {
                if (e == vill) continue;
                String id = safeEntityId(e);
                if (id.isEmpty()) continue;

                if (bl.contains(id)) {
                    logReject(id, "blacklisted");
                    continue;
                }

                if (!wl.isEmpty() && !wl.contains(id)) {
                    logReject(id, "not_whitelisted");
                    continue;
                }

                double d2 = vill.distanceToSqr(e);
                if (d2 < bestDist) {
                    bestDist = d2;
                    best = e;
                }
            }

            if (best != null) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] AGGRESSIVE target found (villager={} target={})",
                        vill.getUUID(), best.getUUID());
            }
            return best;
        } catch (Throwable ignored) {}

        return null;
    }

    private String safeEntityId(LivingEntity e) {
        try {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            return id == null ? "" : id.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private void logReject(String id, String reason) {
        try {
            if (vill == null || vill.level() == null) return;
            long now = vill.level().getGameTime();
            if ((now - lastRejectLogAt) < 40L) return;
            lastRejectLogAt = now;
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] AGGRESSIVE candidate rejected (villager={} entity={} reason={})",
                    vill.getUUID(), id, reason);
        } catch (Throwable ignored) {}
    }

    private void logNoThreat(String reason) {
        try {
            if (vill == null || vill.level() == null) return;
            long now = vill.level().getGameTime();
            if ((now - lastNoThreatLogAt) < 40L) return;
            lastNoThreatLogAt = now;
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] AGGRESSIVE waiting (villager={} reason={})", vill.getUUID(), reason);
        } catch (Throwable ignored) {}
    }

    private static Set<String> normalize(Iterable<String> items) {
        Set<String> out = new HashSet<>();
        if (items == null) return out;
        for (String s : items) {
            if (s == null || s.isBlank()) continue;
            out.add(s.trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private LivingEntity findTargetByUuid(java.util.UUID id) {
        try {
            if (id == null || vill == null || vill.level() == null) return null;
            AABB box = vill.getBoundingBox().inflate(32.0);
            List<LivingEntity> nearby = vill.level().getEntitiesOfClass(LivingEntity.class, box, e -> e != null && e.isAlive());
            for (LivingEntity e : nearby) {
                if (id.equals(e.getUUID())) return e;
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
