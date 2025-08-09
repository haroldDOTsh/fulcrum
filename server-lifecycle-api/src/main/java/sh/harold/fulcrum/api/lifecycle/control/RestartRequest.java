package sh.harold.fulcrum.api.lifecycle.control;

import java.util.UUID;

/**
 * Request to restart a server.
 */
public record RestartRequest(
    String serverId,
    int countdownSeconds,
    String reason,
    UUID requestedBy,
    boolean saveWorld
) {
    /**
     * Creates a standard restart request with defaults.
     */
    public static RestartRequest standard(String serverId, String reason, UUID requestedBy) {
        return new RestartRequest(
            serverId,
            30, // 30 second countdown
            reason,
            requestedBy,
            true // save world
        );
    }

    /**
     * Creates an immediate restart request.
     */
    public static RestartRequest immediate(String serverId, String reason, UUID requestedBy) {
        return new RestartRequest(
            serverId,
            0, // no countdown
            reason,
            requestedBy,
            true // still save world
        );
    }

    /**
     * Creates a restart request for updates.
     */
    public static RestartRequest forUpdate(String serverId, UUID requestedBy) {
        return new RestartRequest(
            serverId,
            60, // 60 second countdown for updates
            "Server restarting for updates",
            requestedBy,
            true // save world
        );
    }

    /**
     * Gets the warning message for players.
     */
    public String getWarningMessage() {
        if (countdownSeconds > 0) {
            return String.format("Server restarting in %d seconds: %s", 
                countdownSeconds, reason);
        }
        return String.format("Server restarting: %s", reason);
    }

    /**
     * Converts to a shutdown request (first phase of restart).
     */
    public ShutdownRequest toShutdownRequest() {
        return new ShutdownRequest(
            serverId,
            countdownSeconds,
            reason + " (restart pending)",
            requestedBy,
            saveWorld,
            true // kick players
        );
    }
}