package sh.harold.fulcrum.api.lifecycle.registration;

import sh.harold.fulcrum.api.lifecycle.ServerMetadata;

/**
 * Result of a server registration attempt.
 */
public record RegistrationResult(
    boolean success,
    String serverId,
    ServerMetadata metadata,
    String message
) {
    /**
     * Creates a successful registration result.
     */
    public static RegistrationResult success(String serverId, ServerMetadata metadata) {
        return new RegistrationResult(
            true, serverId, metadata, 
            "Server registered successfully with ID: " + serverId
        );
    }

    /**
     * Creates a failed registration result.
     */
    public static RegistrationResult failure(String message) {
        return new RegistrationResult(false, null, null, message);
    }

    /**
     * Creates a result for ID reclaim after crash.
     */
    public static RegistrationResult reclaimed(String serverId, ServerMetadata metadata) {
        return new RegistrationResult(
            true, serverId, metadata,
            "Server reclaimed existing ID: " + serverId
        );
    }
}