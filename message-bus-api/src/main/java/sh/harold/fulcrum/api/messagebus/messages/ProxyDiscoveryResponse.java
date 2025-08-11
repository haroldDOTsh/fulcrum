package sh.harold.fulcrum.api.messagebus.messages;

import java.io.Serializable;
import java.util.List;
import java.util.ArrayList;

/**
 * Response to a ProxyDiscoveryRequest containing information about available proxies.
 */
public class ProxyDiscoveryResponse implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String responderId;
    private final List<ProxyInfo> proxies;
    private final long timestamp;
    
    public static class ProxyInfo implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private final String proxyId;
        private final String address;
        private final int capacity;
        private final int currentLoad;
        private final ProxyAnnouncementMessage.ProxyType type;
        
        public ProxyInfo(String proxyId, String address, int capacity, int currentLoad, 
                        ProxyAnnouncementMessage.ProxyType type) {
            this.proxyId = proxyId;
            this.address = address;
            this.capacity = capacity;
            this.currentLoad = currentLoad;
            this.type = type;
        }
        
        public String getProxyId() {
            return proxyId;
        }
        
        public String getAddress() {
            return address;
        }
        
        public int getCapacity() {
            return capacity;
        }
        
        public int getCurrentLoad() {
            return currentLoad;
        }
        
        public ProxyAnnouncementMessage.ProxyType getType() {
            return type;
        }
        
        public static ProxyInfo fromAnnouncement(ProxyAnnouncementMessage announcement) {
            return new ProxyInfo(
                announcement.getProxyId(),
                announcement.getAddress(),
                announcement.getCapacity(),
                announcement.getCurrentLoad(),
                announcement.getProxyType()
            );
        }
    }
    
    public ProxyDiscoveryResponse(String responderId) {
        this.responderId = responderId;
        this.proxies = new ArrayList<>();
        this.timestamp = System.currentTimeMillis();
    }
    
    public ProxyDiscoveryResponse(String responderId, List<ProxyInfo> proxies) {
        this.responderId = responderId;
        this.proxies = new ArrayList<>(proxies);
        this.timestamp = System.currentTimeMillis();
    }
    
    public String getResponderId() {
        return responderId;
    }
    
    public List<ProxyInfo> getProxies() {
        return new ArrayList<>(proxies);
    }
    
    public void addProxy(ProxyInfo proxy) {
        proxies.add(proxy);
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    @Override
    public String toString() {
        return String.format("ProxyDiscoveryResponse[responder=%s, proxies=%d]", 
            responderId, proxies.size());
    }
}