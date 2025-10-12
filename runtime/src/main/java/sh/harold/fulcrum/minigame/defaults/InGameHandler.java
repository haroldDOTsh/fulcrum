package sh.harold.fulcrum.minigame.defaults;

import sh.harold.fulcrum.minigame.state.context.StateContext;
import sh.harold.fulcrum.minigame.state.event.MinigameEvent;

/**
 * Callback for the default in-game state.
 */
public interface InGameHandler {
    default void onMatchStart(StateContext context) {
    }

    default void onTick(StateContext context) {
    }

    default void onMatchEnd(StateContext context) {
    }

    default void onEvent(StateContext context, MinigameEvent event) {
    }
}
