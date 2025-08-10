package sh.harold.fulcrum.api.lifecycle;

import java.util.UUID;

/**
 * Interface for accessing the current server's identity.
 */
public interface ServerIdentifier {
    
    /**
     * Gets the server's unique ID (e.g., "mini1", "mega2", "dynamic104D").
     */
    String getServerId();
    
    /**
     * Gets the server's family/role (e.g., "minigames", "survival").
     */
    String getFamily();
    
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