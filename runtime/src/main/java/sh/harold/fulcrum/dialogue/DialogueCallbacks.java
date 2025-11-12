package sh.harold.fulcrum.dialogue;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Lifecycle hooks invoked as conversations progress.
 */
public final class DialogueCallbacks {
    public static final DialogueCallbacks NONE = new DialogueCallbacks(builder());

    private final Consumer<DialogueProgress> onStart;
    private final Consumer<DialogueProgress> onAdvance;
    private final Consumer<DialogueProgress> onComplete;

    private DialogueCallbacks(Builder builder) {
        this.onStart = builder.onStart;
        this.onAdvance = builder.onAdvance;
        this.onComplete = builder.onComplete;
    }

    public static Builder builder() {
        return new Builder();
    }

    public void fireStart(DialogueProgress ctx) {
        onStart.accept(ctx);
    }

    public void fireAdvance(DialogueProgress ctx) {
        onAdvance.accept(ctx);
    }

    public void fireComplete(DialogueProgress ctx) {
        onComplete.accept(ctx);
    }

    public static final class Builder {
        private Consumer<DialogueProgress> onStart = ctx -> {
        };
        private Consumer<DialogueProgress> onAdvance = ctx -> {
        };
        private Consumer<DialogueProgress> onComplete = ctx -> {
        };

        private Builder() {
        }

        public Builder onStart(Consumer<DialogueProgress> consumer) {
            this.onStart = Objects.requireNonNull(consumer, "onStart");
            return this;
        }

        public Builder onAdvance(Consumer<DialogueProgress> consumer) {
            this.onAdvance = Objects.requireNonNull(consumer, "onAdvance");
            return this;
        }

        public Builder onComplete(Consumer<DialogueProgress> consumer) {
            this.onComplete = Objects.requireNonNull(consumer, "onComplete");
            return this;
        }

        public DialogueCallbacks build() {
            return new DialogueCallbacks(this);
        }
    }
}
