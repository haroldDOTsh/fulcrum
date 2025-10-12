package sh.harold.fulcrum.velocity.api;

import java.util.UUID;

/**
 * Local interface for server identification in Velocity.
 * This is separate from the runtime ServerIdentifier as Velocity
 * manages server information through the message bus and registry.
 */
public interface ServerIdentifier {

    /**
     * Gets the server's unique ID (e.g., "mini1", "mega2", "dynamic104D").
     */
    String getServerId();

    /**
     * Gets the server's role (e.g., "minigames", "survival", "lobby", "game").
     */
    String getRole();

    /**
     * Gets the server type (e.g., "MINI", "MEGA", "LOBBY").
     */
    String getType();

    /**
     * Gets the server's instance UUID (unique per restart).
     */
    UUID getInstanceUuid();

    /**
     * Gets the server's address.
     */
    String getAddress();

    /**
     * Gets the server's port.
     */
    int getPort();

    /**
     * Gets the soft player cap.
     */
    int getSoftCap();

    /**
     * Gets the hard player cap.
     */
    int getHardCap();

    /**
     * Checks if this is the local server.
     */
    boolean isLocal();
}