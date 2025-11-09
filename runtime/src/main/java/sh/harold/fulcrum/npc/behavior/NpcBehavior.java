package sh.harold.fulcrum.npc.behavior;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Declares the passive + interactive callbacks backing an NPC.
 */
public final class NpcBehavior {

    private static final PassiveHandler NOOP_PASSIVE = ctx -> {
    };
    private static final InteractionHandler NOOP_INTERACTION = ctx -> {
    };

    private final PassiveHandler passiveHandler;
    private final InteractionHandler interactionHandler;
    private final int passiveIntervalTicks;
    private final int interactionCooldownTicks;

    private NpcBehavior(Builder builder) {
        this.passiveHandler = builder.passiveHandler;
        this.interactionHandler = builder.interactionHandler;
        this.passiveIntervalTicks = builder.passiveIntervalTicks;
        this.interactionCooldownTicks = builder.interactionCooldownTicks;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static NpcBehavior simple(Consumer<Builder> consumer) {
        Builder builder = builder();
        consumer.accept(builder);
        return builder.build();
    }

    public PassiveHandler passiveHandler() {
        return passiveHandler;
    }

    public InteractionHandler interactionHandler() {
        return interactionHandler;
    }

    public int passiveIntervalTicks() {
        return passiveIntervalTicks;
    }

    public int interactionCooldownTicks() {
        return interactionCooldownTicks;
    }

    public Builder toBuilder() {
        return builder()
                .passiveIntervalTicks(passiveIntervalTicks)
                .passive(passiveHandler)
                .interactionCooldownTicks(interactionCooldownTicks)
                .onInteract(interactionHandler);
    }

    public interface PassiveHandler {
        void execute(PassiveContext context);
    }

    public interface InteractionHandler {
        void execute(InteractionContext context);
    }

    public static final class Builder {
        private PassiveHandler passiveHandler = NOOP_PASSIVE;
        private InteractionHandler interactionHandler = NOOP_INTERACTION;
        private int passiveIntervalTicks = 20;
        private int interactionCooldownTicks = 20;

        private Builder() {
        }

        public Builder passive(PassiveHandler handler) {
            this.passiveHandler = Objects.requireNonNullElse(handler, NOOP_PASSIVE);
            return this;
        }

        public Builder passiveIntervalTicks(int ticks) {
            if (ticks < 1) {
                throw new IllegalArgumentException("Passive interval must be >= 1 tick");
            }
            this.passiveIntervalTicks = ticks;
            return this;
        }

        public Builder onInteract(InteractionHandler handler) {
            this.interactionHandler = Objects.requireNonNullElse(handler, NOOP_INTERACTION);
            return this;
        }

        public Builder interactionCooldownTicks(int ticks) {
            if (ticks < 0) {
                throw new IllegalArgumentException("Interaction cooldown must be >= 0");
            }
            this.interactionCooldownTicks = ticks;
            return this;
        }

        public NpcBehavior build() {
            return new NpcBehavior(this);
        }
    }
}
