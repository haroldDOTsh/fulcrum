package sh.harold.fulcrum.fundamentals.lifecycle;

import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;

import java.util.UUID;

/**
 * Default implementation of ServerIdentifier for the local server.
 * Supports updating the server ID after successful registration.
 */
public class DefaultServerIdentifier implements ServerIdentifier {
    
    private volatile String serverId;  // Made volatile and non-final for updates
    private final String role;
    private final String type;
    private final UUID instanceUuid;
    private final String address;
    private final int port;
    private final int softCap;
    private final int hardCap;
    
    public DefaultServerIdentifier(String serverId, String role, String type,
                                   UUID instanceUuid,
                                   String address, int port,
                                   int softCap, int hardCap) {
        this.serverId = serverId;
        this.role = role;
        this.type = type;
        this.instanceUuid = instanceUuid;
        this.address = address;
        this.port = port;
        this.softCap = softCap;
        this.hardCap = hardCap;
    }
    
    @Override
    public String getServerId() {
        return serverId;
    }
    
    /**
     * Updates the server ID after successful registration
     * @param newServerId The permanent server ID assigned by the registry
     */
    public void updateServerId(String newServerId) {
        this.serverId = newServerId;
    }
    
    @Override
    public String getRole() {
        return role;
    }
    
    @Override
    public String getType() {
        return type;
    }
    
    @Override
    public UUID getInstanceUuid() {
        return instanceUuid;
    }
    
    @Override
    public String getAddress() {
        return address;
    }
    
    @Override
    public int getPort() {
        return port;
    }
    
    @Override
    public int getSoftCap() {
        return softCap;
    }
    
    @Override
    public int getHardCap() {
        return hardCap;
    }
    
    @Override
    public boolean isLocal() {
        return true; // Always true for the local server
    }
}