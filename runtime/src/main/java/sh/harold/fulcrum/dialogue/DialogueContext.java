package sh.harold.fulcrum.dialogue;

import org.bukkit.entity.Player;

import java.util.*;

/**
 * Immutable snapshot of the conversation environment.
 */
public record DialogueContext(
        Player player,
        UUID playerId,
        Dialogue dialogue,
        String displayName,
        UUID npcId,
        Map<String, Object> attributes
) {

    public DialogueContext {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(dialogue, "dialogue");
        displayName = (displayName == null || displayName.isBlank()) ? dialogue.id() : displayName;
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(attributes);
    }

    public Optional<UUID> npcId() {
        return Optional.ofNullable(npcId);
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> attribute(String key, Class<T> type) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Object value = attributes.get(key);
        if (type.isInstance(value)) {
            return Optional.of((T) value);
        }
        return Optional.empty();
    }
}
