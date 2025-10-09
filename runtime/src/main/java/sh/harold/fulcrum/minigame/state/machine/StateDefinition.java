package sh.harold.fulcrum.minigame.state.machine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import sh.harold.fulcrum.minigame.state.MinigameState;

/**
 * Factory wrapper for states and their transitions.
 */
public final class StateDefinition {
    private final Supplier<? extends MinigameState> factory;
    private final List<StateTransition> transitions = new ArrayList<>();

    public StateDefinition(Supplier<? extends MinigameState> factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    public Supplier<? extends MinigameState> getFactory() {
        return factory;
    }

    public List<StateTransition> getTransitions() {
        return Collections.unmodifiableList(transitions);
    }

    public void addTransition(StateTransition transition) {
        transitions.add(transition);
    }
}
