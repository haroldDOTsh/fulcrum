package sh.harold.fulcrum.api.messagebus;

/**
 * Standardized channel names for the Fulcrum message bus system.
 * 
 * Channel naming convention:
 * - fulcrum.{component}.{category}.{action}
 * - Components: registry, proxy, server, discovery, status, etc.
 * - Categories: registration, heartbeat, status, lifecycle, etc.
 * - Actions: request, response, notification, etc.
 * 
 * @author Fulcrum Framework
 */
public final class ChannelConstants {
    
    private ChannelConstants() {
        // Prevent instantiation
    }
    
    // ============================================
    // Registry Channels - Registration & Lifecycle
    // ============================================
    
    /** Main registration request channel - used by both proxies and servers */
    public static final String REGISTRY_REGISTRATION_REQUEST = "fulcrum.registry.registration.request";
    
    /** Re-registration request when registry restarts */
    public static final String REGISTRY_REREGISTRATION_REQUEST = "fulcrum.registry.registration.reregister";
    
    /** Channel for proxy-specific re-registration (targeted) */
    public static final String REGISTRY_PROXY_REREGISTER_PREFIX = "fulcrum.registry.proxy.reregister.";
    
    /** Channel for server-specific re-registration (targeted) */
    public static final String REGISTRY_SERVER_REREGISTER_PREFIX = "fulcrum.registry.server.reregister.";
    
    // ============================================
    // Registry Channels - Server Management
    // ============================================
    
    /** Server added notification from registry */
    public static final String REGISTRY_SERVER_ADDED = "fulcrum.registry.server.added";
    
    /** Server removed notification from registry */
    public static final String REGISTRY_SERVER_REMOVED = "fulcrum.registry.server.removed";
    
    /** Server status change notification */
    public static final String REGISTRY_STATUS_CHANGE = "fulcrum.registry.status.change";
    
    // ============================================
    // Registry Channels - Proxy Management
    // ============================================
    
    /** Proxy shutdown/removal notification to registry */
    public static final String REGISTRY_PROXY_SHUTDOWN = "fulcrum.registry.proxy.shutdown";
    
    /** Proxy unavailable notification */
    public static final String REGISTRY_PROXY_UNAVAILABLE = "fulcrum.registry.proxy.unavailable";
    
    /** Proxy removed notification from registry */
    public static final String REGISTRY_PROXY_REMOVED = "fulcrum.registry.proxy.removed";
    
    // ============================================
    // Server Channels - Heartbeat & Status
    // ============================================
    
    /** Server heartbeat messages */
    public static final String SERVER_HEARTBEAT = "fulcrum.server.heartbeat.status";
    
    /** Server announcement (post-approval) */
    public static final String SERVER_ANNOUNCEMENT = "fulcrum.server.lifecycle.announcement";
    
    /** Server evacuation request */
    public static final String SERVER_EVACUATION_REQUEST = "fulcrum.server.evacuation.request";
    
    /** Server evacuation response */
    public static final String SERVER_EVACUATION_RESPONSE = "fulcrum.server.evacuation.response";
    
    /** Server registration response */
    public static final String SERVER_REGISTRATION_RESPONSE = "fulcrum.server.registration.response";
    
    /** Server-specific registration response prefix */
    public static final String SERVER_REGISTRATION_RESPONSE_PREFIX = "fulcrum.server.registration.response.";
    
    /** Server removal notification */
    public static final String SERVER_REMOVAL_NOTIFICATION = "fulcrum.server.lifecycle.removal";

    // ============================================
    // Slot Channels - Logical Server Slots
    // ============================================

    /** Logical slot status updates from backends */
    public static final String REGISTRY_SLOT_STATUS = "fulcrum.registry.slot.status";

    /** Slot family capability advertisement */
    public static final String REGISTRY_SLOT_FAMILY_ADVERTISEMENT = "fulcrum.registry.slot.family";

    /** Prefix for directed slot provision commands */
    public static final String SERVER_SLOT_PROVISION_PREFIX = "fulcrum.server.slot.provision.";

    // ============================================
    // Proxy Channels - Registration & Lifecycle
    // ============================================
    
    /** Proxy registration response */
    public static final String PROXY_REGISTRATION_RESPONSE = "fulcrum.proxy.registration.response";
    
    /** Proxy unregister request */
    public static final String PROXY_UNREGISTER = "fulcrum.proxy.lifecycle.unregister";
    
    /** Proxy announcement */
    public static final String PROXY_ANNOUNCEMENT = "fulcrum.proxy.lifecycle.announcement";
    
    /** Proxy shutdown notification */
    public static final String PROXY_SHUTDOWN = "fulcrum.proxy.lifecycle.shutdown";
    
    /** Request servers to register with new proxy */
    public static final String PROXY_REQUEST_REGISTRATIONS = "fulcrum.proxy.registration.request_servers";
    
    // ============================================
    // Discovery Channels
    // ============================================
    
    /** Proxy discovery request */
    public static final String PROXY_DISCOVERY_REQUEST = "fulcrum.discovery.proxy.request";
    
    /** Proxy discovery response */
    public static final String PROXY_DISCOVERY_RESPONSE = "fulcrum.discovery.proxy.response";
    
    // ============================================
    // Internal Fulcrum Channels
    // ============================================
    
    /** Proxy registered internally */
    public static final String FULCRUM_PROXY_REGISTERED = "fulcrum.internal.proxy.registered";
    
    /** Proxy removed internally */
    public static final String FULCRUM_PROXY_REMOVED = "fulcrum.internal.proxy.removed";
    
    /** Proxy status update */
    public static final String FULCRUM_PROXY_STATUS = "fulcrum.internal.proxy.status";
    
    // ============================================
    // Direct Communication Channel Prefixes
    // ============================================
    
    /** Direct server-to-server communication prefix */
    public static final String SERVER_DIRECT_PREFIX = "fulcrum.direct.server.";
    
    /** Direct proxy-to-proxy communication prefix */
    public static final String PROXY_DIRECT_PREFIX = "fulcrum.direct.proxy.";
    
    /** Request channel prefix for targeted requests */
    public static final String REQUEST_PREFIX = "fulcrum.request.";
    
    /** Response channel prefix for targeted responses */
    public static final String RESPONSE_PREFIX = "fulcrum.response.";
    
    // ============================================
    // Broadcast Channels
    // ============================================
    
    /** Global broadcast channel for all nodes */
    public static final String BROADCAST_CHANNEL = "fulcrum.broadcast.global";
    
    // ============================================
    // Helper Methods
    // ============================================
    
    /**
     * Get the channel name for a server-specific registration response.
     * @param serverId The server ID
     * @return The channel name
     */
    public static String getServerRegistrationResponseChannel(String serverId) {
        return SERVER_REGISTRATION_RESPONSE_PREFIX + serverId;
    }
    
    /**
     * Get the channel name for a proxy-specific re-registration.
     * @param proxyId The proxy ID
     * @return The channel name
     */
    public static String getProxyReregisterChannel(String proxyId) {
        return REGISTRY_PROXY_REREGISTER_PREFIX + proxyId;
    }
    
    /**
     * Get the channel name for a server-specific re-registration.
     * @param serverId The server ID
     * @return The channel name
     */
    public static String getServerReregisterChannel(String serverId) {
        return REGISTRY_SERVER_REREGISTER_PREFIX + serverId;
    }
    
    /**
     * Get the direct communication channel for a specific server.
     * @param serverId The server ID
     * @return The channel name
     */
    public static String getServerDirectChannel(String serverId) {
        return SERVER_DIRECT_PREFIX + serverId;
    }

    /**
     * Get the slot provision channel for a specific server.
     * @param serverId the server identifier
     * @return the channel name used for slot provision commands
     */
    public static String getSlotProvisionChannel(String serverId) {
        return SERVER_SLOT_PROVISION_PREFIX + serverId;
    }

    /**
     * Get the direct communication channel for a specific proxy.
     * @param proxyId The proxy ID
     * @return The channel name
     */
    public static String getProxyDirectChannel(String proxyId) {
        return PROXY_DIRECT_PREFIX + proxyId;
    }
    
    /**
     * Get the request channel for a specific target.
     * @param targetId The target ID (server or proxy)
     * @return The channel name
     */
    public static String getRequestChannel(String targetId) {
        return REQUEST_PREFIX + targetId;
    }
    
    /**
     * Get the response channel for a specific target.
     * @param targetId The target ID (server or proxy)
     * @return The channel name
     */
    public static String getResponseChannel(String targetId) {
        return RESPONSE_PREFIX + targetId;
    }
}
