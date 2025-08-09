package sh.harold.fulcrum.api.lifecycle.event;

import sh.harold.fulcrum.api.lifecycle.ServerMetadata;

import java.time.Duration;

/**
 * Event fired when a server is detected as crashed.
 */
public class ServerCrashedEvent extends ServerLifecycleEvent {
    private final Duration timeSinceLastHeartbeat;

    public ServerCrashedEvent(ServerMetadata server, Duration timeSinceLastHeartbeat) {
        super(server);
        this.timeSinceLastHeartbeat = timeSinceLastHeartbeat;
    }

    public Duration getTimeSinceLastHeartbeat() {
        return timeSinceLastHeartbeat;
    }
}