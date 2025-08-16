package sh.harold.fulcrum.api.messagebus.messages;

import java.io.Serializable;

/**
 * Broadcast message sent after successful server registration.
 * Proxies listen for these messages to dynamically add backend servers.
 */
public class ServerAnnouncementMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String serverId;     // The assigned server ID
    private final String serverType;   // e.g., "mini", "mega", "hub"
    private final String environment;  // Environment identifier
    private final String role;         // Server role
    private final int capacity;        // Max player capacity
    private final String address;      // Server address for proxy connection
    private final int port;           // Server port
    
    public ServerAnnouncementMessage(String serverId, String serverType,
                                    String environment, String role,
                                    int capacity, String address, int port) {
        this.serverId = serverId;
        this.serverType = serverType;
        this.environment = environment;
        this.role = role;
        this.capacity = capacity;
        this.address = address;
        this.port = port;
    }
    
    public String getServerId() {
        return serverId;
    }
    
    public String getServerType() {
        return serverType;
    }
    
    public String getEnvironment() {
        return environment;
    }
    
    public String getRole() {
        return role;
    }
    
    public int getCapacity() {
        return capacity;
    }
    
    public String getAddress() {
        return address;
    }
    
    public int getPort() {
        return port;
    }
    
    @Override
    public String toString() {
        return String.format("ServerAnnouncement[id=%s, type=%s, env=%s, role=%s, address=%s:%d]",
                serverId, serverType, environment, role, address, port);
    }
}