package sh.harold.fulcrum.common.cooldown;

import java.util.Objects;
import java.util.UUID;

/**
 * Convenience factory for composing {@link CooldownKey} instances.
 */
public final class CooldownKeys {

    private CooldownKeys() {
    }

    public static CooldownKey of(String namespace, String name, UUID subjectId, UUID contextId) {
        Objects.requireNonNull(subjectId, "subjectId");
        return new CooldownKey(namespace, name, subjectId, contextId);
    }

    public static CooldownKey playerScoped(String namespace, String name, UUID subjectId) {
        return of(namespace, name, subjectId, null);
    }

    public static CooldownKey npcInteraction(UUID playerId, UUID npcInstanceId) {
        Objects.requireNonNull(npcInstanceId, "npcInstanceId");
        return new CooldownKey("npc", "interaction", playerId, npcInstanceId);
    }
}
