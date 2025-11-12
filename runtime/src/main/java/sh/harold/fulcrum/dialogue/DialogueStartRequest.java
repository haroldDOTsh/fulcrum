package sh.harold.fulcrum.dialogue;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Input payload for beginning a conversation.
 */
public final class DialogueStartRequest {
    private final Player player;
    private final Dialogue dialogue;
    private final String displayName;
    private final UUID npcId;
    private final String groupIdOverride;
    private final Map<String, Object> attributes;

    private DialogueStartRequest(Builder builder) {
        this.player = Objects.requireNonNull(builder.player, "player");
        this.dialogue = Objects.requireNonNull(builder.dialogue, "dialogue");
        this.displayName = builder.displayName != null ? builder.displayName : dialogue.id();
        this.npcId = builder.npcId;
        this.groupIdOverride = builder.groupIdOverride;
        this.attributes = Map.copyOf(builder.attributes);
    }

    public static Builder builder(Player player, Dialogue dialogue) {
        return new Builder(player, dialogue);
    }

    public Player player() {
        return player;
    }

    public Dialogue dialogue() {
        return dialogue;
    }

    public String displayName() {
        return displayName;
    }

    public UUID npcId() {
        return npcId;
    }

    public String groupIdOverride() {
        return groupIdOverride;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    public static final class Builder {
        private final Player player;
        private final Dialogue dialogue;
        private final Map<String, Object> attributes = new HashMap<>();
        private String displayName;
        private UUID npcId;
        private String groupIdOverride;

        private Builder(Player player, Dialogue dialogue) {
            this.player = Objects.requireNonNull(player, "player");
            this.dialogue = Objects.requireNonNull(dialogue, "dialogue");
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder npcId(UUID npcId) {
            this.npcId = npcId;
            return this;
        }

        public Builder groupIdOverride(String groupIdOverride) {
            this.groupIdOverride = groupIdOverride;
            return this;
        }

        public Builder attribute(String key, Object value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            attributes.put(key, value);
            return this;
        }

        public DialogueStartRequest build() {
            return new DialogueStartRequest(this);
        }
    }
}
