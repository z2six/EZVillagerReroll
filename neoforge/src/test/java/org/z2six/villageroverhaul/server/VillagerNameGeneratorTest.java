package org.z2six.villageroverhaul.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

final class VillagerNameGeneratorTest {

    @Test
    void surnamePoolHasEnoughCombinationsToReduceRepeatedParts() throws Exception {
        String[] lastNames = stringArrayField("LAST_NAMES");
        String[] prefixes = stringArrayField("LAST_PREFIXES");
        String[] suffixes = stringArrayField("LAST_SUFFIXES");

        assertTrue(lastNames.length >= 32768, "surname pool should be large enough for common spawning bursts");
        assertTrue(prefixes.length >= 220, "surname prefixes should not repeat too aggressively");
        assertTrue(suffixes.length >= 160, "surname suffixes should not repeat too aggressively");
    }

    @Test
    void generatedSurnamesStayDeterministic() {
        assertEquals(VillagerNameGenerator.createLastName(new java.util.UUID(1L, 2L)),
                VillagerNameGenerator.createLastName(new java.util.UUID(1L, 2L)));
    }

    private static String[] stringArrayField(String name) throws Exception {
        Field field = VillagerNameGenerator.class.getDeclaredField(name);
        field.setAccessible(true);
        return (String[]) field.get(null);
    }
}
