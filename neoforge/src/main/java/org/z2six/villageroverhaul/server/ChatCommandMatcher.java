package org.z2six.villageroverhaul.server;

import java.util.Locale;

public final class ChatCommandMatcher {

    private ChatCommandMatcher() {}

    public static boolean matches(String commandSpec, String chatMessage, boolean caseSensitive) {
        try {
            if (commandSpec == null || chatMessage == null) return false;

            String spec = commandSpec.trim();
            String msg = chatMessage.trim();
            if (spec.isEmpty() || msg.isEmpty()) return false;

            String[] parts = spec.contains("##") ? spec.split("##", -1) : new String[]{spec};
            String normalizedMsg = caseSensitive ? msg : msg.toLowerCase(Locale.ROOT);

            for (String rawPart : parts) {
                if (rawPart == null) continue;
                String trigger = rawPart.trim();
                if (trigger.isEmpty()) continue;

                boolean containsMatch = trigger.length() >= 4 && trigger.startsWith("$$") && trigger.endsWith("$$");
                if (containsMatch) {
                    String needle = trigger.substring(2, trigger.length() - 2).trim();
                    if (needle.isEmpty()) continue;

                    String[] andParts = needle.contains("&&") ? needle.split("&&", -1) : null;
                    if (andParts != null) {
                        boolean ok = true;
                        for (String rawAndPart : andParts) {
                            if (rawAndPart == null) continue;
                            String andPart = rawAndPart.trim();
                            if (andPart.isEmpty()) continue;
                            String probe = caseSensitive ? andPart : andPart.toLowerCase(Locale.ROOT);
                            if (!normalizedMsg.contains(probe)) {
                                ok = false;
                                break;
                            }
                        }
                        if (ok) return true;
                        continue;
                    }

                    String probe = caseSensitive ? needle : needle.toLowerCase(Locale.ROOT);
                    if (normalizedMsg.contains(probe)) return true;
                    continue;
                }

                if (caseSensitive ? trigger.equals(msg) : trigger.equalsIgnoreCase(msg)) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
