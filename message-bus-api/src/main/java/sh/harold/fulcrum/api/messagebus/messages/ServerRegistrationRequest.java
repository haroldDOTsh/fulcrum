package sh.harold.fulcrum.api.messagebus.messages;

import java.io.Serializable;

/**
 * Request sent by a server to register itself with the network.
 * The server uses a temporary ID initially and receives a permanent ID in the response.
 */
public class ServerRegistrationRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String tempId;        // Temporary UUID used before registration
    private String serverType;    // MINI, MEGA, LOBBY, etc.
    private int maxCapacity;      // Maximum player capacity
    private String address;       // Server IP address
    private int port;            // Server port
    private String family;        // From environment file (e.g., "production", "development")
    private String role;          // From environment file (e.g., "game", "lobby", "auth")
    
    public ServerRegistrationRequest() {
        // Default constructor for serialization
    }
    
    public ServerRegistrationRequest(String tempId, String serverType, int maxCapacity) {
        this.tempId = tempId;
        this.serverType = serverType;
        this.maxCapacity = maxCapacity;
    }
    
    // Getters and setters
    public String getTempId() {
        return tempId;
    }
    
    public void setTempId(String tempId) {
        this.tempId = tempId;
    }
    
    public String getServerType() {
        return serverType;
    }
    
    public void setServerType(String serverType) {
        this.serverType = serverType;
    }
    
    public int getMaxCapacity() {
        return maxCapacity;
    }
    
    public void setMaxCapacity(int maxCapacity) {
        this.maxCapacity = maxCapacity;
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
    
    public String getFamily() {
        return family;
    }
    
    public void setFamily(String family) {
        this.family = family;
    }
    
    public String getRole() {
        return role;
    }
    
    public void setRole(String role) {
        this.role = role;
    }
    
    @Override
    public String toString() {
        return String.format("ServerRegistrationRequest[tempId=%s, type=%s, capacity=%d, family=%s, role=%s]",
                tempId, serverType, maxCapacity, family, role);
    }
}