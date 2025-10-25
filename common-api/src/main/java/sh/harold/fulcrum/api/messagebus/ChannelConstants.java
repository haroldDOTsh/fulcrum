package sh.harold.fulcrum.api.messagebus;

/**
 * Standardized channel names for the Fulcrum message bus system.
 * <p>
 * Channel naming convention:
 * - fulcrum.{component}.{category}.{action}
 * - Components: registry, proxy, server, discovery, status, etc.
 * - Categories: registration, heartbeat, status, lifecycle, etc.
 * - Actions: request, response, notification, etc.
 *
 * @author Fulcrum Framework
 */
public final class ChannelConstants {

    /**
     * Main registration request channel - used by both proxies and servers
     */
    public static final String REGISTRY_REGISTRATION_REQUEST = "fulcrum.registry.registration.request";

    // ============================================
    // Registry Channels - Registration & Lifecycle
    // ============================================
    /**
     * Re-registration request when registry restarts
     */
    public static final String REGISTRY_REREGISTRATION_REQUEST = "fulcrum.registry.registration.reregister";
    /**
     * Channel for proxy-specific re-registration (targeted)
     */
    public static final String REGISTRY_PROXY_REREGISTER_PREFIX = "fulcrum.registry.proxy.reregister.";
    /**
     * Channel for server-specific re-registration (targeted)
     */
    public static final String REGISTRY_SERVER_REREGISTER_PREFIX = "fulcrum.registry.server.reregister.";
    /**
     * Server added notification from registry
     */
    public static final String REGISTRY_SERVER_ADDED = "fulcrum.registry.server.added";

    // ============================================
    // Registry Channels - Server Management
    // ============================================
    /**
     * Server removed notification from registry
     */
    public static final String REGISTRY_SERVER_REMOVED = "fulcrum.registry.server.removed";
    /**
     * Server status change notification
     */
    public static final String REGISTRY_STATUS_CHANGE = "fulcrum.registry.status.change";
    /**
     * Proxy shutdown/removal notification to registry
     */
    public static final String REGISTRY_PROXY_SHUTDOWN = "fulcrum.registry.proxy.shutdown";

    // ============================================
    // Registry Channels - Proxy Management
    // ============================================
    /**
     * Proxy unavailable notification
     */
    public static final String REGISTRY_PROXY_UNAVAILABLE = "fulcrum.registry.proxy.unavailable";
    /**
     * Proxy removed notification from registry
     */
    public static final String REGISTRY_PROXY_REMOVED = "fulcrum.registry.proxy.removed";
    /**
     * Server heartbeat messages
     */
    public static final String SERVER_HEARTBEAT = "fulcrum.server.heartbeat.status";

    // ============================================
    // Server Channels - Heartbeat & Status
    // ============================================
    /**
     * Server announcement (post-approval)
     */
    public static final String SERVER_ANNOUNCEMENT = "fulcrum.server.lifecycle.announcement";
    /**
     * Server evacuation request
     */
    public static final String SERVER_EVACUATION_REQUEST = "fulcrum.server.evacuation.request";
    /**
     * Server evacuation response
     */
    public static final String SERVER_EVACUATION_RESPONSE = "fulcrum.server.evacuation.response";
    /**
     * Server registration response
     */
    public static final String SERVER_REGISTRATION_RESPONSE = "fulcrum.server.registration.response";
    /**
     * Server-specific registration response prefix
     */
    public static final String SERVER_REGISTRATION_RESPONSE_PREFIX = "fulcrum.server.registration.response.";
    /**
     * Server removal notification
     */
    public static final String SERVER_REMOVAL_NOTIFICATION = "fulcrum.server.lifecycle.removal";
    /**
     * Logical slot status updates from backends
     */
    public static final String REGISTRY_SLOT_STATUS = "fulcrum.registry.slot.status";

    // ============================================
    // Slot Channels - Logical Server Slots
    // ============================================
    /**
     * Slot family capability advertisement
     */
    public static final String REGISTRY_SLOT_FAMILY_ADVERTISEMENT = "fulcrum.registry.slot.family";
    /**
     * Player matchmaking requests from proxies
     */
    public static final String REGISTRY_PLAYER_REQUEST = "fulcrum.registry.player.request";
    public static final String REGISTRY_ENVIRONMENT_ROUTE_REQUEST = "fulcrum.registry.environment.route.request";
    /**
     * Registry broadcast used to locate the proxy currently hosting a player
     */
    public static final String REGISTRY_PLAYER_LOCATE_REQUEST = "fulcrum.registry.player.locate";
    /**
     * Proxies respond to the registry with the result of a locate request
     */
    public static final String REGISTRY_PLAYER_LOCATE_RESPONSE = "fulcrum.registry.player.locate.response";
    /**
     * Player routing acknowledgement channel
     */
    public static final String PLAYER_ROUTE_ACK = "fulcrum.registry.player.route.ack";
    public static final String PLAYER_RESERVATION_REQUEST = "fulcrum.registry.player.reservation.request";
    public static final String PLAYER_RESERVATION_RESPONSE = "fulcrum.registry.player.reservation.response";
    public static final String PARTY_UPDATE = "fulcrum.party.update";
    public static final String PARTY_RESERVATION_CREATED = "fulcrum.party.reservation.created";
    public static final String PARTY_RESERVATION_CLAIMED = "fulcrum.party.reservation.claimed";
    public static final String PARTY_WARP_REQUEST = "fulcrum.party.warp.request";
    public static final String MATCH_ROSTER_CREATED = "fulcrum.match.roster.created";
    public static final String MATCH_ROSTER_ENDED = "fulcrum.match.roster.ended";

    // ============================================
    // Registry Channels - Rank Management
    // ============================================
    public static final String REGISTRY_RANK_MUTATION_REQUEST = "fulcrum.registry.rank.mutation.request";
    public static final String REGISTRY_RANK_MUTATION_RESPONSE = "fulcrum.registry.rank.mutation.response";
    public static final String REGISTRY_RANK_UPDATE = "fulcrum.registry.rank.update";
    /**
     * Network configuration profile promotion and fetch channels
     */
    public static final String REGISTRY_NETWORK_CONFIG_UPDATED = "fulcrum.registry.network.config.updated";
    public static final String REGISTRY_NETWORK_CONFIG_REQUEST = "fulcrum.registry.network.config.request";
    public static final String REGISTRY_NETWORK_CONFIG_RESPONSE = "fulcrum.registry.network.config.response";
    /**
     * Prefix for directed slot provision commands
     */
    public static final String SERVER_SLOT_PROVISION_PREFIX = "fulcrum.server.slot.provision.";
    /**
     * Proxy registration response
     */
    public static final String PROXY_REGISTRATION_RESPONSE = "fulcrum.proxy.registration.response";
    /**
     * Proxy unregister request
     */
    public static final String PROXY_UNREGISTER = "fulcrum.proxy.lifecycle.unregister";
    /**
     * Proxy announcement
     */
    public static final String PROXY_ANNOUNCEMENT = "fulcrum.proxy.lifecycle.announcement";

    // ============================================
    // Proxy Channels - Registration & Lifecycle
    // ============================================
    /**
     * Proxy shutdown notification
     */
    public static final String PROXY_SHUTDOWN = "fulcrum.proxy.lifecycle.shutdown";
    /**
     * Request servers to register with new proxy
     */
    public static final String PROXY_REQUEST_REGISTRATIONS = "fulcrum.proxy.registration.request_servers";
    /**
     * Proxy discovery request
     */
    public static final String PROXY_DISCOVERY_REQUEST = "fulcrum.discovery.proxy.request";
    /**
     * Proxy discovery response
     */
    public static final String PROXY_DISCOVERY_RESPONSE = "fulcrum.discovery.proxy.response";
    /**
     * Proxy registered internally
     */
    public static final String FULCRUM_PROXY_REGISTERED = "fulcrum.internal.proxy.registered";
    /**
     * Cross-network chat channel messages
     */
    public static final String CHAT_CHANNEL_MESSAGE = "fulcrum.chat.channel.message";

    // ============================================
    // Discovery Channels
    // ============================================
    /**
     * Proxy removed internally
     */
    public static final String FULCRUM_PROXY_REMOVED = "fulcrum.internal.proxy.removed";
    /**
     * Proxy status update
     */
    public static final String FULCRUM_PROXY_STATUS = "fulcrum.internal.proxy.status";

    // ============================================
    // Internal Fulcrum Channels
    // ============================================
    /**
     * Direct server-to-server communication prefix
     */
    public static final String SERVER_DIRECT_PREFIX = "fulcrum.direct.server.";
    /**
     * Direct proxy-to-proxy communication prefix
     */
    public static final String PROXY_DIRECT_PREFIX = "fulcrum.direct.proxy.";
    /**
     * Request channel prefix for targeted requests
     */
    public static final String REQUEST_PREFIX = "fulcrum.request.";

    // ============================================
    // Direct Communication Channel Prefixes
    // ============================================
    /**
     * Response channel prefix for targeted responses
     */
    public static final String RESPONSE_PREFIX = "fulcrum.response.";
    /**
     * Global broadcast channel for all nodes
     */
    public static final String BROADCAST_CHANNEL = "fulcrum.broadcast.global";
    /**
     * Prefix for player routing commands to proxies
     */
    private static final String PLAYER_ROUTE_PREFIX = "fulcrum.registry.player.route.";
    private static final String SERVER_PLAYER_ROUTE_PREFIX = "fulcrum.server.player.route.";

    // ============================================
    // Broadcast Channels
    // ============================================

    private ChannelConstants() {
        // Prevent instantiation
    }

    // ============================================
    // Helper Methods
    // ============================================

    /**
     * Get the channel name for a server-specific registration response.
     *
     * @param serverId The server ID
     * @return The channel name
     */
    public static String getServerRegistrationResponseChannel(String serverId) {
        return SERVER_REGISTRATION_RESPONSE_PREFIX + serverId;
    }

    /**
     * Get the channel name for a proxy-specific re-registration.
     *
     * @param proxyId The proxy ID
     * @return The channel name
     */
    public static String getProxyReregisterChannel(String proxyId) {
        return REGISTRY_PROXY_REREGISTER_PREFIX + proxyId;
    }

    /**
     * Get the channel name for a server-specific re-registration.
     *
     * @param serverId The server ID
     * @return The channel name
     */
    public static String getServerReregisterChannel(String serverId) {
        return REGISTRY_SERVER_REREGISTER_PREFIX + serverId;
    }

    /**
     * Get the direct communication channel for a specific server.
     *
     * @param serverId The server ID
     * @return The channel name
     */
    public static String getServerDirectChannel(String serverId) {
        return SERVER_DIRECT_PREFIX + serverId;
    }

    /**
     * Get the slot provision channel for a specific server.
     *
     * @param serverId the server identifier
     * @return the channel name used for slot provision commands
     */
    public static String getSlotProvisionChannel(String serverId) {
        return SERVER_SLOT_PROVISION_PREFIX + serverId;
    }

    /**
     * Get the player routing channel for a proxy.
     *
     * @param proxyId the proxy identifier
     * @return the channel name used for routing commands
     */
    public static String getPlayerRouteChannel(String proxyId) {
        return PLAYER_ROUTE_PREFIX + proxyId;
    }

    public static String getServerPlayerRouteChannel(String serverId) {
        return SERVER_PLAYER_ROUTE_PREFIX + serverId;
    }

    /**
     * Get the direct communication channel for a specific proxy.
     *
     * @param proxyId The proxy ID
     * @return The channel name
     */
    public static String getProxyDirectChannel(String proxyId) {
        return PROXY_DIRECT_PREFIX + proxyId;
    }

    /**
     * Get the request channel for a specific target.
     *
     * @param targetId The target ID (server or proxy)
     * @return The channel name
     */
    public static String getRequestChannel(String targetId) {
        return REQUEST_PREFIX + targetId;
    }

    /**
     * Get the response channel for a specific target.
     *
     * @param targetId The target ID (server or proxy)
     * @return The channel name
     */
    public static String getResponseChannel(String targetId) {
        return RESPONSE_PREFIX + targetId;
    }
}
