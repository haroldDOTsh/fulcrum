package sh.harold.fulcrum.minigame.defaults;

import sh.harold.fulcrum.minigame.state.AbstractMinigameState;
import sh.harold.fulcrum.minigame.state.context.StateContext;
import sh.harold.fulcrum.minigame.state.event.MinigameEvent;

/**
 * Thin wrapper that delegates to an {@link InGameHandler}.
 */
public final class DefaultInGameState extends AbstractMinigameState {
    private final InGameHandler handler;

    public DefaultInGameState(InGameHandler handler) {
        this.handler = handler;
    }

    @Override
    public void onEnter(StateContext context) {
        handler.onMatchStart(context);
    }

    @Override
    public void onTick(StateContext context) {
        handler.onTick(context);
    }

    @Override
    public void onExit(StateContext context) {
        handler.onMatchEnd(context);
    }

    @Override
    public void onEvent(StateContext context, MinigameEvent event) {
        handler.onEvent(context, event);
    }
}
