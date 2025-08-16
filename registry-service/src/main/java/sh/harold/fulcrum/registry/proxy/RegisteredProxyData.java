package sh.harold.fulcrum.registry.proxy;

/**
 * Information about a registered proxy
 */
public class RegisteredProxyData {
    
    /**
     * Proxy status states matching server states
     */
    public enum Status {
        AVAILABLE,
        UNAVAILABLE,
        DEAD
    }
    
    private final String proxyId;
    private final String address;
    private final int port;
    private long lastHeartbeat;
    private volatile Status status;
    
    public RegisteredProxyData(String proxyId, String address, int port) {
        this.proxyId = proxyId;
        this.address = address;
        this.port = port;
        this.lastHeartbeat = System.currentTimeMillis();
        this.status = Status.AVAILABLE;
    }
    
    public String getProxyId() {
        return proxyId;
    }
    
    public String getAddress() {
        return address;
    }
    
    public int getPort() {
        return port;
    }
    
    public long getLastHeartbeat() {
        return lastHeartbeat;
    }
    
    public void setLastHeartbeat(long lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }
    
    public Status getStatus() {
        return status;
    }
    
    public void setStatus(Status status) {
        this.status = status;
    }
}