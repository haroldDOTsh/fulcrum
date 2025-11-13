package sh.harold.fulcrum.dialogue;

import sh.harold.fulcrum.common.cooldown.CooldownPolicy;
import sh.harold.fulcrum.common.cooldown.CooldownSpec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable dialogue script definition shared across entry points.
 */
public record Dialogue(
        String id,
        List<DialogueLine> lines,
        DialogueCallbacks callbacks,
        CooldownSpec cooldownSpec,
        String cooldownGroup,
        Duration timeout
) {

    private static final Duration DEFAULT_WINDOW = Duration.ofSeconds(5);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    public Dialogue {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(lines, "lines");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Dialogue lines cannot be empty for " + id);
        }
        Objects.requireNonNull(callbacks, "callbacks");
        Objects.requireNonNull(cooldownSpec, "cooldownSpec");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must be positive for " + id);
        }
        lines = List.copyOf(lines);
        callbacks = callbacks;
        cooldownGroup = (cooldownGroup == null || cooldownGroup.isBlank()) ? id : cooldownGroup;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<DialogueLine> lines = new ArrayList<>();
        private String id;
        private DialogueCallbacks callbacks = DialogueCallbacks.NONE;
        private CooldownSpec cooldownSpec = CooldownSpec.rejecting(DEFAULT_WINDOW);
        private String cooldownGroup;
        private Duration timeout = DEFAULT_TIMEOUT;

        private Builder() {
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder lines(List<DialogueLine> lines) {
            this.lines.clear();
            if (lines != null) {
                this.lines.addAll(lines);
            }
            return this;
        }

        public Builder addLine(DialogueLine line) {
            Objects.requireNonNull(line, "line");
            this.lines.add(line);
            return this;
        }

        public Builder callbacks(DialogueCallbacks callbacks) {
            this.callbacks = callbacks == null ? DialogueCallbacks.NONE : callbacks;
            return this;
        }

        public Builder cooldown(Duration window) {
            Objects.requireNonNull(window, "window");
            this.cooldownSpec = CooldownSpec.rejecting(window);
            return this;
        }

        public Builder cooldownSpec(CooldownSpec spec) {
            this.cooldownSpec = Objects.requireNonNull(spec, "spec");
            return this;
        }

        public Builder cooldownPolicy(CooldownPolicy policy, Duration window) {
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(window, "window");
            this.cooldownSpec = new CooldownSpec(window, policy);
            return this;
        }

        public Builder cooldownGroup(String cooldownGroup) {
            this.cooldownGroup = cooldownGroup;
            return this;
        }

        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("Timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        public Dialogue build() {
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("Dialogue id must be set");
            }
            if (lines.isEmpty()) {
                throw new IllegalStateException("Dialogue requires at least one line");
            }
            return new Dialogue(id,
                    Collections.unmodifiableList(new ArrayList<>(lines)),
                    callbacks,
                    cooldownSpec,
                    cooldownGroup,
                    timeout);
        }
    }
}
