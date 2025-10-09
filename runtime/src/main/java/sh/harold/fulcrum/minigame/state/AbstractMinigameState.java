package sh.harold.fulcrum.minigame.state;

import sh.harold.fulcrum.minigame.state.context.StateContext;
import sh.harold.fulcrum.minigame.state.event.MinigameEvent;

/**
 * Convenience base with no-op hooks.
 */
public abstract class AbstractMinigameState implements MinigameState {

    @Override
    public void onEnter(StateContext context) {
        // no-op
    }

    @Override
    public void onTick(StateContext context) {
        // no-op
    }

    @Override
    public void onExit(StateContext context) {
        // no-op
    }

    @Override
    public void onEvent(StateContext context, MinigameEvent event) {
        // no-op
    }
}
