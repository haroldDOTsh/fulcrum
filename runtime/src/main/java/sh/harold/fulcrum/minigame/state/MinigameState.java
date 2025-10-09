package sh.harold.fulcrum.minigame.state;

import sh.harold.fulcrum.minigame.state.context.StateContext;
import sh.harold.fulcrum.minigame.state.event.MinigameEvent;

/**
 * Contract for match lifecycle states.
 */
public interface MinigameState {

    /**
     * Called when the state becomes active.
     */
    void onEnter(StateContext context);

    /**
     * Called once per engine tick while this state is active.
     */
    void onTick(StateContext context);

    /**
     * Called when the state is about to be replaced.
     */
    void onExit(StateContext context);

    /**
     * Called for engine events published to the state machine.
     */
    default void onEvent(StateContext context, MinigameEvent event) {
        // no-op
    }
}
