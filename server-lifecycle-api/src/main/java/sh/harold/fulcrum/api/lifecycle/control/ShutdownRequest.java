package sh.harold.fulcrum.api.lifecycle.control;

import java.util.UUID;

/**
 * Request to shutdown a server.
 */
public record ShutdownRequest(
    String serverId,
    int countdownSeconds,
    String reason,
    UUID requestedBy,
    boolean saveWorld,
    boolean kickPlayers
) {
    /**
     * Creates a standard shutdown request with defaults.
     */
    public static ShutdownRequest standard(String serverId, String reason, UUID requestedBy) {
        return new ShutdownRequest(
            serverId, 
            30, // 30 second countdown
            reason, 
            requestedBy,
            true, // save world
            true  // kick players
        );
    }

    /**
     * Creates an immediate shutdown request.
     */
    public static ShutdownRequest immediate(String serverId, String reason, UUID requestedBy) {
        return new ShutdownRequest(
            serverId,
            0, // no countdown
            reason,
            requestedBy,
            true, // still save world
            true  // kick players
        );
    }

    /**
     * Creates an emergency shutdown request (no saving).
     */
    public static ShutdownRequest emergency(String serverId, UUID requestedBy) {
        return new ShutdownRequest(
            serverId,
            0, // immediate
            "Emergency shutdown",
            requestedBy,
            false, // don't save
            true   // kick players
        );
    }

    /**
     * Gets the warning message for players.
     */
    public String getWarningMessage() {
        if (countdownSeconds > 0) {
            return String.format("Server shutting down in %d seconds: %s", 
                countdownSeconds, reason);
        }
        return String.format("Server shutting down: %s", reason);
    }
}