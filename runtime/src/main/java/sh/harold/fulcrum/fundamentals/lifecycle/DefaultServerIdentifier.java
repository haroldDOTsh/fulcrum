package sh.harold.fulcrum.fundamentals.lifecycle;

import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.api.lifecycle.ServerStatus;
import sh.harold.fulcrum.api.lifecycle.ServerType;

import java.util.UUID;

/**
 * Default implementation of ServerIdentifier for the local server.
 */
public class DefaultServerIdentifier implements ServerIdentifier {
    
    private final String serverId;
    private final String family;
    private final ServerType type;
    private volatile ServerStatus status;
    private final UUID instanceUuid;
    private final String address;
    private final int port;
    private final int softCap;
    private final int hardCap;
    
    public DefaultServerIdentifier(String serverId, String family, ServerType type,
                                   ServerStatus status, UUID instanceUuid,
                                   String address, int port,
                                   int softCap, int hardCap) {
        this.serverId = serverId;
        this.family = family;
        this.type = type;
        this.status = status;
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
    
    @Override
    public String getFamily() {
        return family;
    }
    
    @Override
    public ServerType getType() {
        return type;
    }
    
    @Override
    public ServerStatus getStatus() {
        return status;
    }
    
    /**
     * Updates the status of this server identifier.
     */
    public void updateStatus(ServerStatus newStatus) {
        this.status = newStatus;
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