package sh.harold.fulcrum.minigame.state.machine;

import java.util.Objects;
import java.util.function.Predicate;
import sh.harold.fulcrum.minigame.state.context.StateContext;

/**
 * Conditional transition between two states.
 */
public final class StateTransition {
    private final String targetStateId;
    private final Predicate<StateContext> condition;

    public StateTransition(String targetStateId, Predicate<StateContext> condition) {
        this.targetStateId = Objects.requireNonNull(targetStateId, "targetStateId");
        this.condition = Objects.requireNonNull(condition, "condition");
    }

    public boolean shouldTransition(StateContext context) {
        return condition.test(context);
    }

    public String getTargetStateId() {
        return targetStateId;
    }
}
