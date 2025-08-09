package sh.harold.fulcrum.api.lifecycle.event;

import sh.harold.fulcrum.api.lifecycle.ServerMetadata;

/**
 * Event fired when a server is registered.
 */
public class ServerRegisteredEvent extends ServerLifecycleEvent {
    private final boolean reclaimed;

    public ServerRegisteredEvent(ServerMetadata server, boolean reclaimed) {
        super(server);
        this.reclaimed = reclaimed;
    }

    /**
     * Whether this was a reclaim of an existing ID after crash.
     */
    public boolean isReclaimed() {
        return reclaimed;
    }
}