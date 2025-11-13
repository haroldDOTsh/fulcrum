package sh.harold.fulcrum.api.messagebus.messages;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;

/**
 * Response to a server registration request.
 * Contains the assigned permanent server ID or failure information.
 * This message is also forwarded to all proxies for dynamic backend registration.
 */
@MessageType(value = "server.registration.response", version = 1)
public class ServerRegistrationResponse implements BaseMessage {
    private static final long serialVersionUID = 1L;

    private String tempId;            // The temporary ID from the request
    private String assignedServerId;  // The permanent server ID (e.g., "mini1", "mega2")
    private boolean success;          // Whether registration was successful
    private String message;           // Optional message or error details
    private String serverType;        // Forwarded for proxy registration
    private String address;           // Forwarded for proxy registration
    private int port;                // Forwarded for proxy registration
    private String proxyId;           // ID of the proxy that approved the registration

    public ServerRegistrationResponse() {
        // Default constructor for serialization
    }

    public ServerRegistrationResponse(String tempId, String assignedServerId, boolean success) {
        this.tempId = tempId;
        this.assignedServerId = assignedServerId;
        this.success = success;
    }

    public ServerRegistrationResponse(String proxyId, boolean success, String message) {
        this.proxyId = proxyId;
        this.success = success;
        this.message = message;
    }

    // Getters and setters
    public String getTempId() {
        return tempId;
    }

    public void setTempId(String tempId) {
        this.tempId = tempId;
    }

    public String getAssignedServerId() {
        return assignedServerId;
    }

    public void setAssignedServerId(String assignedServerId) {
        this.assignedServerId = assignedServerId;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getServerType() {
        return serverType;
    }

    public void setServerType(String serverType) {
        this.serverType = serverType;
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

    public String getProxyId() {
        return proxyId;
    }

    public void setProxyId(String proxyId) {
        this.proxyId = proxyId;
    }

    @Override
    public String toString() {
        if (success) {
            return String.format("ServerRegistrationResponse[SUCCESS: %s -> %s, proxy=%s]",
                    tempId, assignedServerId, proxyId);
        } else {
            return String.format("ServerRegistrationResponse[FAILED: %s - %s]",
                    tempId, message);
        }
    }
}