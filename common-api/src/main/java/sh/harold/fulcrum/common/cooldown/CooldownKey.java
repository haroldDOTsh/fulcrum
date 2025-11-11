package sh.harold.fulcrum.common.cooldown;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies a logical cooldown slot. Keys are immutable value objects.
 *
 * @param namespace logical subsystem grouping (e.g., {@code npc})
 * @param name      specific cooldown name inside the namespace
 * @param subjectId primary actor being throttled (usually a player)
 * @param contextId optional secondary identifier (e.g., npc instance id)
 */
public record CooldownKey(String namespace, String name, UUID subjectId, UUID contextId) {

    public CooldownKey {
        namespace = sanitize(namespace, "namespace");
        name = sanitize(name, "name");
    }

    private static String sanitize(String value, String label) {
        Objects.requireNonNull(value, label + " must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return trimmed;
    }
}
