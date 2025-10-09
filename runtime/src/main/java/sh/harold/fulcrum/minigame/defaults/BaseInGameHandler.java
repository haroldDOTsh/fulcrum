package sh.harold.fulcrum.minigame.defaults;

import sh.harold.fulcrum.minigame.state.context.StateContext;
import sh.harold.fulcrum.minigame.state.event.MinigameEvent;

/**
 * Convenience adapter with empty methods.
 */
public class BaseInGameHandler implements InGameHandler {
    @Override
    public void onMatchStart(StateContext context) {
        // no-op
    }

    @Override
    public void onTick(StateContext context) {
        // no-op
    }

    @Override
    public void onMatchEnd(StateContext context) {
        // no-op
    }

    @Override
    public void onEvent(StateContext context, MinigameEvent event) {
        // no-op
    }
}
