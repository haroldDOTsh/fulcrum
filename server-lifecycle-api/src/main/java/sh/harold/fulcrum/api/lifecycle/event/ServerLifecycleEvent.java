package sh.harold.fulcrum.api.lifecycle.event;

import sh.harold.fulcrum.api.lifecycle.ServerMetadata;

import java.time.Instant;

/**
 * Base class for server lifecycle events.
 */
public abstract class ServerLifecycleEvent {
    private final ServerMetadata server;
    private final Instant timestamp;

    protected ServerLifecycleEvent(ServerMetadata server) {
        this.server = server;
        this.timestamp = Instant.now();
    }

    public ServerMetadata getServer() {
        return server;
    }

    public String getServerId() {
        return server.id();
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}