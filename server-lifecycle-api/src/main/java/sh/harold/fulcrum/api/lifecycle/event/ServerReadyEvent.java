package sh.harold.fulcrum.api.lifecycle.event;

import sh.harold.fulcrum.api.lifecycle.ServerMetadata;

/**
 * Event fired when a server transitions to READY status.
 */
public class ServerReadyEvent extends ServerLifecycleEvent {
    public ServerReadyEvent(ServerMetadata server) {
        super(server);
    }
}