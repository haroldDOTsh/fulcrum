package sh.harold.fulcrum.registry.proxy;

/**
 * Information about a registered proxy
 */
public class RegisteredProxyData {
    private final String proxyId;
    private final String address;
    private final int port;
    private long lastHeartbeat;
    
    public RegisteredProxyData(String proxyId, String address, int port) {
        this.proxyId = proxyId;
        this.address = address;
        this.port = port;
        this.lastHeartbeat = System.currentTimeMillis();
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
}