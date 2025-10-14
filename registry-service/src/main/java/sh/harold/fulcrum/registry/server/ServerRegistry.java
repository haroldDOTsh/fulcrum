package sh.harold.fulcrum.registry.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.api.messagebus.messages.SlotStatusUpdateMessage;
import sh.harold.fulcrum.registry.allocation.IdAllocator;
import sh.harold.fulcrum.registry.messages.RegistrationRequest;
import sh.harold.fulcrum.registry.slot.LogicalSlotRecord;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing backend server registrations.
 * Handles server lifecycle and state management.
 */
public class ServerRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerRegistry.class);

    private final IdAllocator idAllocator;
    private final Map<String, RegisteredServerData> servers = new ConcurrentHashMap<>();
    private final Map<String, String> tempIdToPermId = new ConcurrentHashMap<>();

    public ServerRegistry(IdAllocator idAllocator) {
        this.idAllocator = idAllocator;
    }

    /**
     * Register a new server
     */
    public synchronized String registerServer(RegistrationRequest request) {
        String requestId = request.getTempId();

        // Check if this is actually a permanent ID (re-registration)
        if (!requestId.startsWith("temp-")) {
            // This is a re-registration with permanent ID
            RegisteredServerData existingServer = servers.get(requestId);
            if (existingServer != null) {
                // Create new server data object with updated values but same ID
                RegisteredServerData updatedServer = new RegisteredServerData(
                        requestId,  // Keep the same permanent ID
                        existingServer.getTempId(),  // Keep original temp ID
                        request.getServerType(),
                        request.getAddress(),
                        request.getPort(),
                        request.getMaxCapacity()
                );

                // Update mutable fields
                updatedServer.setRole(request.getRole() != null ? request.getRole() : "default");
                updatedServer.setLastHeartbeat(System.currentTimeMillis());
                updatedServer.setStatus(RegisteredServerData.Status.STARTING);

                // Replace the server data
                servers.put(requestId, updatedServer);

                LOGGER.info("Re-registered existing server {} (re-registration)", requestId);
                return requestId; // Return the SAME ID
            }
            // If server doesn't exist, treat as new registration
            LOGGER.warn("Re-registration for unknown server {}, treating as new", requestId);
        }

        // Check if this temp ID already has a permanent ID assigned (double-check pattern)
        String existingId = tempIdToPermId.get(requestId);
        if (existingId != null) {
            // Verify the server still exists
            if (servers.containsKey(existingId)) {
                LOGGER.debug("Server {} already registered with ID {}", requestId, existingId);
                return existingId;
            } else {
                // Clean up orphaned mapping
                tempIdToPermId.remove(requestId);
                LOGGER.debug("Cleaned up orphaned temp ID mapping for {}", requestId);
            }
        }

        // Allocate new permanent ID
        String permanentId = idAllocator.allocateServerId(request.getServerType());

        // Check for ID collision (extremely rare but possible)
        if (servers.containsKey(permanentId)) {
            LOGGER.error("ID collision detected for {} - this should not happen!", permanentId);
            throw new IllegalStateException("Server ID collision: " + permanentId);
        }

        // Create server data
        RegisteredServerData serverData = new RegisteredServerData(
                permanentId,
                request.getTempId(),
                request.getServerType(),
                request.getAddress(),
                request.getPort(),
                request.getMaxCapacity()
        );

        serverData.setRole(request.getRole() != null ? request.getRole() : "default");
        serverData.setLastHeartbeat(System.currentTimeMillis());
        serverData.setStatus(RegisteredServerData.Status.STARTING);

        // Store server atomically
        servers.put(permanentId, serverData);
        tempIdToPermId.put(requestId, permanentId);

        LOGGER.info("Registered server {} -> {} (type: {}, role: {})",
                requestId, permanentId, request.getServerType(), serverData.getRole());

        return permanentId;
    }

    /**
     * Deregister a server
     */
    public synchronized void deregisterServer(String serverId) {
        RegisteredServerData server = servers.remove(serverId);
        if (server != null) {
            // Clean up temp ID mapping
            tempIdToPermId.remove(server.getTempId());

            // Return ID to pool for reuse
            idAllocator.releaseServerId(serverId);

            server.clearSlots();
            server.clearSlotFamilyCapacities();
            LOGGER.info("Deregistered server {} (type: {})", serverId, server.getServerType());
        }
    }

    /**
     * Get server by ID (checks both permanent and temporary IDs)
     */
    public RegisteredServerData getServer(String serverId) {
        // First check by permanent ID
        RegisteredServerData server = servers.get(serverId);
        if (server != null) {
            return server;
        }

        // If not found, check if this is a temp ID
        String permanentId = tempIdToPermId.get(serverId);
        if (permanentId != null) {
            return servers.get(permanentId);
        }

        return null;
    }

    /**
     * Get permanent ID for a temp ID
     */
    public String getPermanentId(String tempId) {
        return tempIdToPermId.get(tempId);
    }

    /**
     * Get all registered servers
     */
    public Collection<RegisteredServerData> getAllServers() {
        return servers.values();
    }

    /**
     * Get count of registered servers
     */
    public int getServerCount() {
        return servers.size();
    }

    /**
     * Update server heartbeat
     */
    public void updateHeartbeat(String serverId, int playerCount, double tps) {
        RegisteredServerData server = servers.get(serverId);
        if (server != null) {
            server.setLastHeartbeat(System.currentTimeMillis());
            server.setPlayerCount(playerCount);
            server.setTps(tps);

            // Update status to RUNNING if it was STARTING
            if (RegisteredServerData.Status.STARTING == server.getStatus()) {
                server.setStatus(RegisteredServerData.Status.RUNNING);
                LOGGER.info("Server {} is now RUNNING", serverId);
            }
        }
    }

    /**
     * Update server status
     */
    public void updateStatus(String serverId, String status) {
        RegisteredServerData server = servers.get(serverId);
        if (server != null) {
            RegisteredServerData.Status oldStatus = server.getStatus();
            RegisteredServerData.Status newStatus = RegisteredServerData.Status.valueOf(status);
            server.setStatus(newStatus);
            LOGGER.info("Server {} status changed: {} -> {}", serverId, oldStatus, newStatus);
        }
    }

    /**
     * Apply a slot update for the given server.
     */
    public LogicalSlotRecord updateSlot(String serverId, SlotStatusUpdateMessage update) {
        RegisteredServerData server = servers.get(serverId);
        if (server == null) {
            LOGGER.debug("Ignoring slot update for unknown server {} (slot {})", serverId, update.getSlotId());
            return null;
        }

        LogicalSlotRecord record = server.applySlotUpdate(update);
        if (record != null) {
            String removedFlag = record.getMetadata().get("removed");
            if ("true".equalsIgnoreCase(removedFlag)) {
                server.removeSlot(record.getSlotSuffix());
                LOGGER.debug("Removed slot {} for server {} (removal flag)", record.getSlotId(), serverId);
                return null;
            }
        }
        LOGGER.debug("Updated slot {} for server {} -> status={} players={}/{}",
                record.getSlotId(), serverId, record.getStatus(), record.getOnlinePlayers(), record.getMaxPlayers());
        return record;
    }

    /**
     * Update advertised slot family capacities for a server.
     */
    public void updateFamilyCapabilities(String serverId, Map<String, Integer> capacities) {
        RegisteredServerData server = servers.get(serverId);
        if (server == null) {
            LOGGER.debug("Ignoring family advertisement for unknown server {}", serverId);
            return;
        }

        server.updateSlotFamilyCapacities(capacities);
        LOGGER.debug("Updated family capacities for {} => {}", serverId, capacities);
    }

    /**
     * Check if server exists
     */
    public boolean hasServer(String serverId) {
        return servers.containsKey(serverId);
    }

    /**
     * Clear all servers (for shutdown)
     */
    public void clear() {
        servers.clear();
        tempIdToPermId.clear();
        LOGGER.info("Cleared all server registrations");
    }

    /**
     * Update server metrics
     */
    public void updateServerMetrics(String serverId, int playerCount, double tps) {
        RegisteredServerData server = servers.get(serverId);
        if (server != null) {
            server.setPlayerCount(playerCount);
            server.setTps(tps);
        }
    }

    /**
     * Get count of available servers
     */
    public int getAvailableServerCount() {
        return (int) servers.values().stream()
                .filter(server -> server.getStatus() == RegisteredServerData.Status.AVAILABLE)
                .count();
    }

    /**
     * Get count of unavailable servers
     */
    public int getUnavailableServerCount() {
        return (int) servers.values().stream()
                .filter(server -> server.getStatus() == RegisteredServerData.Status.UNAVAILABLE)
                .count();
    }

    /**
     * Get servers by type
     */
    public Collection<RegisteredServerData> getServersByType(String serverType) {
        return servers.values().stream()
                .filter(server -> serverType.equals(server.getServerType()))
                .collect(java.util.stream.Collectors.toList());
    }
}
