package org.z2six.villageroverhaul.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

final class ArmorRandomCycleTest {

    @Test
    void cyclesThroughAllItemsBeforeRepeating() {
        ArmorRandomCycle cycle = new ArmorRandomCycle(new Random(42L));
        List<String> ids = List.of("a", "b", "c", "d");

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            seen.add(cycle.next(ids, ""));
        }

        assertEquals(Set.copyOf(ids), seen);
    }

    @Test
    void avoidsCurrentItemWhenOtherChoicesExist() {
        ArmorRandomCycle cycle = new ArmorRandomCycle(new Random(42L));

        String selected = cycle.next(List.of("a", "b", "c"), "a");

        assertFalse("a".equals(selected));
    }
}
