package sh.harold.fulcrum.registry.messages;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Simple registration request message for the registry service.
 * This is a local copy to avoid dependency issues.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RegistrationRequest {
    private String tempId;
    private String serverType;
    private String role;
    private String address;
    private int port;
    private int maxCapacity;
    private String family;
    private Map<String, Object> metadata;
    
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
    
    public String getRole() {
        return role;
    }
    
    public void setRole(String role) {
        this.role = role;
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
    
    public int getMaxCapacity() {
        return maxCapacity;
    }
    
    public void setMaxCapacity(int maxCapacity) {
        this.maxCapacity = maxCapacity;
    }
    
    public String getFamily() {
        return family;
    }
    
    public void setFamily(String family) {
        this.family = family;
    }
    
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}