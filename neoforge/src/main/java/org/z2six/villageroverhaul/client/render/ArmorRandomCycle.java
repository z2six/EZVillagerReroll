package org.z2six.villageroverhaul.client.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class ArmorRandomCycle {
    private final Random random;
    private List<String> source = List.of();
    private List<String> bag = new ArrayList<>();

    public ArmorRandomCycle(Random random) {
        this.random = random == null ? new Random() : random;
    }

    public String next(List<String> ids, String current) {
        List<String> normalized = normalize(ids);
        if (normalized.isEmpty()) {
            source = List.of();
            bag.clear();
            return "";
        }
        if (!source.equals(normalized) || bag.isEmpty()) {
            source = normalized;
            refill(current);
        }
        if (bag.isEmpty()) return normalized.get(0);
        return bag.remove(bag.size() - 1);
    }

    private void refill(String current) {
        bag = new ArrayList<>(source);
        if (bag.size() > 1 && current != null && !current.isBlank()) {
            bag.remove(current);
        }
        Collections.shuffle(bag, random);
    }

    private static List<String> normalize(List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (id != null && !id.isBlank()) out.add(id);
        }
        Collections.sort(out);
        return List.copyOf(out);
    }
}
