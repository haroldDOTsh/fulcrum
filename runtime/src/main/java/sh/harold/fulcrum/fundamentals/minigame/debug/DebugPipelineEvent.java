package sh.harold.fulcrum.fundamentals.minigame.debug;

import sh.harold.fulcrum.minigame.state.event.MinigameEvent;

/**
 * Simple event dispatched by the debug pipeline feature to advance the match via external triggers.
 */
public final class DebugPipelineEvent implements MinigameEvent {
    public static final String TYPE = "debug.pipeline.complete";

    private final String reason;

    private DebugPipelineEvent(String reason) {
        this.reason = reason;
    }

    public static DebugPipelineEvent completion(String reason) {
        return new DebugPipelineEvent(reason == null ? "unspecified" : reason);
    }

    @Override
    public String type() {
        return TYPE;
    }

    public String reason() {
        return reason;
    }
}
