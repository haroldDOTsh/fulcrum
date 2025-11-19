package sh.harold.fulcrum.api.friends;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Canonical request describing a friend mutation routed through the registry.
 */
public record FriendMutationRequest(
        FriendMutationType type,
        UUID actorId,
        UUID targetId,
        Instant expiresAt,
        String reason,
        Map<String, Object> metadata
) {
    public static final String METADATA_IGNORE_BYPASS = "fulcrum.ignore.bypass";
    public static final String METADATA_ACTOR_NAME = "actorName";

    public FriendMutationRequest {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(targetId, "targetId");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public boolean bypassesIgnoreChecks() {
        Object flag = metadata.get(METADATA_IGNORE_BYPASS);
        if (flag instanceof Boolean booleanFlag) {
            return booleanFlag;
        }
        if (flag instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return false;
    }

    public static Builder builder(FriendMutationType type) {
        return new Builder(type);
    }

    public static final class Builder {
        private final FriendMutationType type;
        private final Map<String, Object> metadata = new LinkedHashMap<>();
        private UUID actorId;
        private UUID targetId;
        private Instant expiresAt;
        private String reason;

        public Builder(FriendMutationType type) {
            this.type = Objects.requireNonNull(type, "type");
        }

        public Builder actor(UUID actorId) {
            this.actorId = actorId;
            return this;
        }

        public Builder target(UUID targetId) {
            this.targetId = targetId;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder putMetadata(String key, Object value) {
            if (key != null && value != null) {
                metadata.put(key, value);
            }
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            if (metadata != null) {
                metadata.forEach(this::putMetadata);
            }
            return this;
        }

        public FriendMutationRequest build() {
            return new FriendMutationRequest(type, actorId, targetId, expiresAt, reason, metadata);
        }
    }
}
