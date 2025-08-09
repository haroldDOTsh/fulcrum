package sh.harold.fulcrum.api.lifecycle;

/**
 * Represents the lifecycle status of a server.
 */
public enum ServerStatus {
    /**
     * Server is starting up but not yet ready to accept players.
     */
    STARTING,

    /**
     * Server is fully operational and ready to accept players.
     */
    READY,

    /**
     * Server is in the process of shutting down.
     */
    STOPPING,

    /**
     * Server is offline.
     */
    OFFLINE,

    /**
     * Server is restarting.
     */
    RESTARTING
}