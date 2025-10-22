package sh.harold.fulcrum.api.messagebus.messages;

import java.io.Serializable;

/**
 * Broadcast message sent after successful server registration.
 * Proxies listen for these messages to dynamically add backend servers.
 */
public class ServerAnnouncementMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private String serverId;     // The assigned server ID
    private String serverType;   // e.g., "mini", "mega", "hub"
    private String environment;  // Environment identifier
    private String role;         // Server role
    private int capacity;        // Max player capacity
    private String address;      // Server address for proxy connection
    private int port;           // Server port

    // Default constructor for Jackson deserialization
    public ServerAnnouncementMessage() {
    }

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

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public String getServerType() {
        return serverType;
    }

    public void setServerType(String serverType) {
        this.serverType = serverType;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    @Override
    public String toString() {
        return String.format("ServerAnnouncement[id=%s, type=%s, env=%s, role=%s, address=%s:%d]",
                serverId, serverType, environment, role, address, port);
    }
}