package sh.harold.fulcrum.registry.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.api.messagebus.messages.RuntimeAuthorityDeliveryManifest;
import sh.harold.fulcrum.api.messagebus.messages.RuntimeDataAuthorityAttestation;
import sh.harold.fulcrum.api.messagebus.messages.SlotLifecycleStatus;
import sh.harold.fulcrum.api.messagebus.messages.SlotStatusUpdateMessage;
import sh.harold.fulcrum.registry.allocation.IdAllocator;
import sh.harold.fulcrum.registry.authority.AuthorityDeliveryManifestValidator;
import sh.harold.fulcrum.registry.messages.RegistrationRequest;
import sh.harold.fulcrum.registry.persistence.RegistryNodeSnapshot;
import sh.harold.fulcrum.registry.persistence.RegistryNodeSnapshotStore;
import sh.harold.fulcrum.registry.slot.LogicalSlotRecord;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
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
    private RegistryNodeSnapshotStore snapshotStore = RegistryNodeSnapshotStore.NOOP;
    
    public ServerRegistry(IdAllocator idAllocator) {
        this.idAllocator = idAllocator;
    }

    /**
     * Set the durable node snapshot store.
     *
     * @param snapshotStore snapshot store to use.
     */
    public void setSnapshotStore(RegistryNodeSnapshotStore snapshotStore) {
        this.snapshotStore = Objects.requireNonNullElse(snapshotStore, RegistryNodeSnapshotStore.NOOP);
    }

    /**
     * Restore backend reservations from durable snapshots.
     *
     * @param snapshots snapshots loaded from durable registry metadata.
     * @return number of active backend reservations restored.
     */
    public synchronized int restoreSnapshots(Collection<RegistryNodeSnapshot> snapshots) {
        int restored = 0;
        if (snapshots == null) {
            return restored;
        }
        for (RegistryNodeSnapshot snapshot : snapshots) {
            if (restoreSnapshot(snapshot)) {
                restored++;
            }
        }
        return restored;
    }

    /**
     * Restore one backend reservation from a durable snapshot.
     *
     * @param snapshot snapshot loaded from durable registry metadata.
     * @return true when the backend reservation was restored.
     */
    public synchronized boolean restoreSnapshot(RegistryNodeSnapshot snapshot) {
        if (snapshot == null || !snapshot.isBackend() || snapshot.nodeId() == null || snapshot.nodeId().isBlank()) {
            return false;
        }
        if (!snapshot.permitsRestore()) {
            LOGGER.warn(
                "Skipping backend snapshot {} from {} because attestation fingerprint does not match payload",
                snapshot.nodeId(),
                snapshot.snapshotSource()
            );
            return false;
        }

        idAllocator.observeServerId(snapshot.nodeId());
        if (isTerminalSnapshotState(snapshot.state()) || servers.containsKey(snapshot.nodeId())) {
            return false;
        }

        Map<String, Object> metadata = snapshot.metadata();
        String tempId = stringValue(metadata.get("tempId"), "restored-" + snapshot.nodeId());
        String serverType = stringValue(metadata.get("serverType"), deriveServerType(snapshot.nodeId()));
        RegisteredServerData server = new RegisteredServerData(
            snapshot.nodeId(),
            tempId,
            serverType,
            snapshot.address(),
            snapshot.port(),
            snapshot.capacity()
        );
        server.setRole(snapshot.role() == null || snapshot.role().isBlank() ? "default" : snapshot.role());
        server.setStatus(RegisteredServerData.Status.UNAVAILABLE);
        server.setLastHeartbeat(snapshot.updatedAt().toEpochMilli());
        server.setPlayerCount(intValue(metadata.get("playerCount"), 0));
        server.setTps(doubleValue(metadata.get("tps"), 20.0));
        server.setMemoryUsage(doubleValue(metadata.get("memoryUsage"), 0.0));
        server.setCpuUsage(doubleValue(metadata.get("cpuUsage"), 0.0));
        server.setDataAuthorityAttestation(dataAuthorityAttestation(metadata.get("dataAuthorityAttestation")));
        RuntimeAuthorityDeliveryManifest manifest =
            authorityDeliveryManifest(metadata.get("authorityDeliveryManifest"));
        if (manifest != null) {
            String manifestRejection = AuthorityDeliveryManifestValidator.rejection(manifest);
            if (manifestRejection == null) {
                server.setAuthorityDeliveryManifest(manifest);
            } else {
                LOGGER.warn(
                    "Dropping restored Data Authority delivery manifest for {} because it failed admission: {}",
                    snapshot.nodeId(),
                    manifestRejection
                );
            }
        }
        server.updateSlotFamilyCapacities(intMap(metadata.get("slotFamilies")));
        restoreSlots(server, metadata.get("slots"));

        servers.put(server.getServerId(), server);
        tempIdToPermId.put(server.getTempId(), server.getServerId());
        LOGGER.info("Restored backend snapshot {} as UNAVAILABLE; awaiting fresh heartbeat", server.getServerId());
        return true;
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
                updatedServer.setDataAuthorityAttestation(
                    request.getDataAuthorityAttestation() != null
                        ? request.getDataAuthorityAttestation()
                        : existingServer.getDataAuthorityAttestation()
                );
                updatedServer.setAuthorityDeliveryManifest(
                    request.getAuthorityDeliveryManifest() != null
                        ? request.getAuthorityDeliveryManifest()
                        : existingServer.getAuthorityDeliveryManifest()
                );
                
                // Replace the server data
                servers.put(requestId, updatedServer);
                snapshot(updatedServer);
                
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
                updateDataAuthorityAttestation(existingId, request.getDataAuthorityAttestation());
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
        serverData.setDataAuthorityAttestation(request.getDataAuthorityAttestation());
        serverData.setAuthorityDeliveryManifest(request.getAuthorityDeliveryManifest());
        
        // Store server atomically
        servers.put(permanentId, serverData);
        tempIdToPermId.put(requestId, permanentId);
        snapshot(serverData);
        
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
            markOffline(serverId);
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
            snapshot(server);
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
            snapshot(server);
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

        LogicalSlotRecord previous = server.getSlot(update.getSlotSuffix());
        SlotLifecycleStatus previousStatus = previous != null ? previous.getStatus() : null;
        String previousFamily = previous != null ? previous.getMetadata().get("family") : null;

        LogicalSlotRecord record = server.applySlotUpdate(update);
        String familyId = record.getMetadata().getOrDefault("family", previousFamily);
        if (familyId != null
            && isTerminal(update.getStatus())
            && !isTerminal(previousStatus)) {
            server.releaseFamilySlot(familyId);
        }
        snapshot(server);
        LOGGER.debug("Updated slot {} for server {} -> status={} players={}/{}",
            record.getSlotId(), serverId, record.getStatus(), record.getOnlinePlayers(), record.getMaxPlayers());
        return record;
    }

    private static boolean isTerminal(SlotLifecycleStatus status) {
        return status == SlotLifecycleStatus.COOLDOWN || status == SlotLifecycleStatus.FAULTED;
    }

    public void snapshotServer(String serverId) {
        RegisteredServerData server = getServer(serverId);
        if (server != null) {
            snapshot(server);
        }
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
        snapshot(server);
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
            snapshot(server);
        }
    }

    public void updateDataAuthorityAttestation(
        String serverId,
        RuntimeDataAuthorityAttestation dataAuthorityAttestation
    ) {
        if (serverId == null || serverId.isBlank() || dataAuthorityAttestation == null) {
            return;
        }
        RegisteredServerData server = servers.get(serverId);
        if (server != null && !sameAttestation(server.getDataAuthorityAttestation(), dataAuthorityAttestation)) {
            server.setDataAuthorityAttestation(dataAuthorityAttestation);
            snapshot(server);
            LOGGER.info(
                "Updated Data Authority attestation for {} (nodeKind={}, attestationFingerprint={})",
                serverId,
                dataAuthorityAttestation.getNodeKind(),
                dataAuthorityAttestation.getAttestationFingerprint()
            );
        }
    }

    public void updateAuthorityDeliveryManifest(
        String serverId,
        RuntimeAuthorityDeliveryManifest authorityDeliveryManifest
    ) {
        if (serverId == null || serverId.isBlank() || authorityDeliveryManifest == null) {
            return;
        }
        RegisteredServerData server = servers.get(serverId);
        if (server != null && !sameAuthorityDeliveryManifest(
            server.getAuthorityDeliveryManifest(),
            authorityDeliveryManifest
        )) {
            server.setAuthorityDeliveryManifest(authorityDeliveryManifest);
            snapshot(server);
            LOGGER.info(
                "Updated Data Authority delivery manifest for {} (nodeKind={}, manifestFingerprint={})",
                serverId,
                authorityDeliveryManifest.getNodeKind(),
                authorityDeliveryManifest.getManifestFingerprint()
            );
        }
    }

    private void snapshot(RegisteredServerData server) {
        try {
            snapshotStore.snapshotServer(server);
        } catch (Exception exception) {
            LOGGER.warn("Failed to snapshot server {}", server.getServerId(), exception);
        }
    }

    private void markOffline(String serverId) {
        try {
            snapshotStore.markOffline(serverId, "BACKEND", RegisteredServerData.Status.DEAD.name());
        } catch (Exception exception) {
            LOGGER.warn("Failed to mark server {} offline in snapshot store", serverId, exception);
        }
    }

    private void restoreSlots(RegisteredServerData server, Object rawSlots) {
        if (!(rawSlots instanceof Iterable<?> slots)) {
            return;
        }

        for (Object rawSlot : slots) {
            Map<String, Object> slotData = objectMap(rawSlot);
            String slotId = stringValue(slotData.get("slotId"), null);
            String slotSuffix = stringValue(slotData.get("slotSuffix"), null);
            if (slotId == null || slotSuffix == null) {
                continue;
            }

            LogicalSlotRecord slot = new LogicalSlotRecord(slotId, slotSuffix, server.getServerId());
            slot.setGameType(stringValue(slotData.get("gameType"), null));
            slot.setStatus(slotStatus(slotData.get("status")));
            slot.setMaxPlayers(intValue(slotData.get("maxPlayers"), 0));
            slot.setOnlinePlayers(intValue(slotData.get("onlinePlayers"), 0));
            slot.setLastUpdated(server.getLastHeartbeat());
            slot.replaceMetadata(stringMap(slotData.get("metadata")));
            markRestoredSlotNonRoutable(slot);
            server.restoreSlot(slot);
        }
    }

    private static void markRestoredSlotNonRoutable(LogicalSlotRecord slot) {
        if (slot.getStatus() != SlotLifecycleStatus.AVAILABLE) {
            return;
        }

        Map<String, String> metadata = new java.util.LinkedHashMap<>(slot.getMetadata());
        metadata.put("restoredStatus", SlotLifecycleStatus.AVAILABLE.name());
        metadata.put("restoreRequiresFreshSlotStatus", "true");
        slot.setStatus(SlotLifecycleStatus.PROVISIONING);
        slot.replaceMetadata(metadata);
    }

    private static boolean isTerminalSnapshotState(String state) {
        return RegisteredServerData.Status.DEAD.name().equalsIgnoreCase(state)
            || RegisteredServerData.Status.STOPPING.name().equalsIgnoreCase(state);
    }

    private static SlotLifecycleStatus slotStatus(Object value) {
        if (value != null) {
            try {
                return SlotLifecycleStatus.valueOf(value.toString());
            } catch (IllegalArgumentException ignored) {
                // Restore invalid historical slot states as non-routable.
            }
        }
        return SlotLifecycleStatus.PROVISIONING;
    }

    private static String deriveServerType(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return "unknown";
        }
        int index = 0;
        while (index < nodeId.length() && !Character.isDigit(nodeId.charAt(index))) {
            index++;
        }
        return index == 0 ? "unknown" : nodeId.substring(0, index);
    }

    private static String stringValue(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? fallback : Integer.parseInt(value.toString());
    }

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return value == null ? fallback : Double.parseDouble(value.toString());
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private static RuntimeDataAuthorityAttestation dataAuthorityAttestation(Object value) {
        if (value instanceof RuntimeDataAuthorityAttestation attestation) {
            return attestation;
        }
        Map<String, Object> raw = objectMap(value);
        if (raw.isEmpty()) {
            return null;
        }

        RuntimeDataAuthorityAttestation attestation = new RuntimeDataAuthorityAttestation();
        attestation.setNodeKind(stringValue(raw.get("nodeKind"), null));
        attestation.setManifestVersion(intValue(raw.get("manifestVersion"), 0));
        attestation.setPassed(booleanValue(raw.get("passed"), false));
        attestation.setRuntimeDataMode(stringValue(raw.get("runtimeDataMode"), null));
        attestation.setCacheMode(stringValue(raw.get("cacheMode"), null));
        attestation.setCommandSchemaVersion(intValue(raw.get("commandSchemaVersion"), 0));
        attestation.setCommandContractFingerprint(stringValue(raw.get("commandContractFingerprint"), null));
        attestation.setReadSchemaVersion(intValue(raw.get("readSchemaVersion"), 0));
        attestation.setReadContractFingerprint(stringValue(raw.get("readContractFingerprint"), null));
        attestation.setConfigFingerprint(stringValue(raw.get("configFingerprint"), null));
        attestation.setClasspathFingerprint(stringValue(raw.get("classpathFingerprint"), null));
        attestation.setAttestationFingerprint(stringValue(raw.get("attestationFingerprint"), null));
        return attestation.getAttestationFingerprint() == null ? null : attestation;
    }

    private static RuntimeAuthorityDeliveryManifest authorityDeliveryManifest(Object value) {
        if (value instanceof RuntimeAuthorityDeliveryManifest manifest) {
            return manifest;
        }
        Map<String, Object> raw = objectMap(value);
        if (raw.isEmpty()) {
            return null;
        }

        RuntimeAuthorityDeliveryManifest manifest = new RuntimeAuthorityDeliveryManifest();
        manifest.setNodeKind(stringValue(raw.get("nodeKind"), null));
        manifest.setManifestVersion(intValue(raw.get("manifestVersion"), 0));
        manifest.setAuthorityServerId(stringValue(raw.get("authorityServerId"), null));
        manifest.setRuntimeDataMode(stringValue(raw.get("runtimeDataMode"), null));
        manifest.setCacheMode(stringValue(raw.get("cacheMode"), null));
        manifest.setStartupAttestationFingerprint(stringValue(raw.get("startupAttestationFingerprint"), null));
        manifest.setCommandSchemaVersion(intValue(raw.get("commandSchemaVersion"), 0));
        manifest.setCommandContractFingerprint(stringValue(raw.get("commandContractFingerprint"), null));
        manifest.setCommandRouteManifestFingerprint(stringValue(raw.get("commandRouteManifestFingerprint"), null));
        manifest.setReadSchemaVersion(intValue(raw.get("readSchemaVersion"), 0));
        manifest.setReadContractFingerprint(stringValue(raw.get("readContractFingerprint"), null));
        manifest.setCommandDomainsByType(stringMap(raw.get("commandDomainsByType")));
        manifest.setCommandDeliveryModesByType(stringMap(raw.get("commandDeliveryModesByType")));
        manifest.setCommandPartitionKeyVectorsByType(stringMap(raw.get("commandPartitionKeyVectorsByType")));
        manifest.setCommandLogStoresByType(stringMap(raw.get("commandLogStoresByType")));
        manifest.setCommandHotProjectionStoresByType(stringMap(raw.get("commandHotProjectionStoresByType")));
        manifest.setCommandHistoryStoresByType(stringMap(raw.get("commandHistoryStoresByType")));
        manifest.setCommandCacheStoresByType(stringMap(raw.get("commandCacheStoresByType")));
        manifest.setReadProjectionFamiliesByType(stringMap(raw.get("readProjectionFamiliesByType")));
        manifest.setReadServingStoresByType(stringMap(raw.get("readServingStoresByType")));
        manifest.setReadCacheStoresByType(stringMap(raw.get("readCacheStoresByType")));
        manifest.setManifestFingerprint(stringValue(raw.get("manifestFingerprint"), null));
        return manifest.getManifestFingerprint() == null ? null : manifest;
    }

    private static boolean sameAttestation(
        RuntimeDataAuthorityAttestation current,
        RuntimeDataAuthorityAttestation candidate
    ) {
        if (current == candidate) {
            return true;
        }
        if (current == null || candidate == null) {
            return false;
        }
        return Objects.equals(current.getAttestationFingerprint(), candidate.getAttestationFingerprint());
    }

    private static boolean sameAuthorityDeliveryManifest(
        RuntimeAuthorityDeliveryManifest current,
        RuntimeAuthorityDeliveryManifest candidate
    ) {
        if (current == candidate) {
            return true;
        }
        if (current == null || candidate == null) {
            return false;
        }
        return Objects.equals(current.getManifestFingerprint(), candidate.getManifestFingerprint());
    }

    private static Map<String, Integer> intMap(Object value) {
        Map<String, Object> raw = objectMap(value);
        if (raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        raw.forEach((key, mapValue) -> result.put(key, intValue(mapValue, 0)));
        return result;
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, Object> raw = objectMap(value);
        if (raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new java.util.LinkedHashMap<>();
        raw.forEach((key, mapValue) -> {
            if (mapValue != null) {
                result.put(key, mapValue.toString());
            }
        });
        return result;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> raw) || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        raw.forEach((key, mapValue) -> {
            if (key != null) {
                result.put(key.toString(), mapValue);
            }
        });
        return result;
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
