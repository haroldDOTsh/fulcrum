package sh.harold.fulcrum.velocity.fundamentals.lifecycle;

import sh.harold.fulcrum.api.lifecycle.*;
import sh.harold.fulcrum.api.lifecycle.registration.RegistrationResult;
import sh.harold.fulcrum.api.lifecycle.registration.ServerRegistration;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of ServerRegistry for development.
 */
public class VelocityDefaultServerRegistry implements ServerRegistry {
    private final Map<String, ServerMetadata> servers = new ConcurrentHashMap<>();
    private final Map<String, UUID> serverInstances = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<RegistrationResult> register(ServerRegistration registration, UUID instanceUuid) {
        return CompletableFuture.supplyAsync(() -> {
            // Generate ID for proxy servers
            String serverId = generateServerId(registration);
            
            // Check if ID is reserved for this instance (crash recovery)
            if (servers.containsKey(serverId)) {
                UUID existingInstance = serverInstances.get(serverId);
                if (existingInstance != null && existingInstance.equals(instanceUuid)) {
                    // Same instance reclaiming after crash
                    ServerMetadata metadata = createMetadata(serverId, registration, instanceUuid);
                    servers.put(serverId, metadata);
                    return RegistrationResult.reclaimed(serverId, metadata);
                } else if (existingInstance == null || isServerCrashed(serverId)) {
                    // Server crashed, allow new instance to take over
                    ServerMetadata metadata = createMetadata(serverId, registration, instanceUuid);
                    servers.put(serverId, metadata);
                    serverInstances.put(serverId, instanceUuid);
                    return RegistrationResult.reclaimed(serverId, metadata);
                } else {
                    // ID already taken by another active instance
                    return RegistrationResult.failure("Server ID already in use: " + serverId);
                }
            }
            
            // Create new registration
            ServerMetadata metadata = createMetadata(serverId, registration, instanceUuid);
            servers.put(serverId, metadata);
            serverInstances.put(serverId, instanceUuid);
            
            return RegistrationResult.success(serverId, metadata);
        });
    }
    
    private ServerMetadata createMetadata(String serverId, ServerRegistration registration, UUID instanceUuid) {
        return new ServerMetadata(
            serverId,
            registration.family(),
            registration.type(),
            registration.address(),
            registration.port(),
            ServerStatus.STARTING,
            Instant.now(),
            Instant.now(),
            instanceUuid
        );
    }

    private String generateServerId(ServerRegistration registration) {
        if (registration.type() == ServerType.PROXY) {
            // Auto-generate proxy IDs
            return generateProxyId();
        } else {
            // For game servers, use family-based naming
            return registration.family() + "-" + generateGameServerId(registration.family());
        }
    }

    private synchronized String generateProxyId() {
        // Find the lowest available proxy ID
        Set<Integer> usedIds = new HashSet<>();
        String prefix = "fulcrum-proxy-";
        
        for (String serverId : servers.keySet()) {
            if (serverId.startsWith(prefix)) {
                try {
                    String numStr = serverId.substring(prefix.length());
                    usedIds.add(Integer.parseInt(numStr));
                } catch (NumberFormatException e) {
                    // Ignore malformed IDs
                }
            }
        }
        
        // Find lowest available contiguous number
        int id = 0;
        while (usedIds.contains(id)) {
            id++;
        }
        
        return prefix + id;
    }
    
    private synchronized String generateGameServerId(String family) {
        // Find the lowest available game server ID for this family
        Set<Integer> usedIds = new HashSet<>();
        String prefix = family + "-";
        
        for (String serverId : servers.keySet()) {
            if (serverId.startsWith(prefix)) {
                try {
                    String numStr = serverId.substring(prefix.length());
                    usedIds.add(Integer.parseInt(numStr));
                } catch (NumberFormatException e) {
                    // Ignore malformed IDs
                }
            }
        }
        
        // Find lowest available number
        int id = 0;
        while (usedIds.contains(id)) {
            id++;
        }
        
        return String.valueOf(id);
    }
    
    private boolean isServerCrashed(String serverId) {
        ServerMetadata metadata = servers.get(serverId);
        return metadata != null && 
               (metadata.status() == ServerStatus.OFFLINE || 
                metadata.isCrashed(60)); // 60 second timeout
    }

    @Override
    public CompletableFuture<Boolean> updateStatus(String serverId, ServerStatus status) {
        return CompletableFuture.supplyAsync(() -> {
            ServerMetadata existing = servers.get(serverId);
            if (existing != null) {
                servers.put(serverId, existing.withStatus(status));
                return true;
            }
            return false;
        });
    }

    @Override
    public CompletableFuture<Boolean> heartbeat(ServerHeartbeat heartbeat) {
        return CompletableFuture.supplyAsync(() -> {
            ServerMetadata existing = servers.get(heartbeat.serverId());
            if (existing != null) {
                servers.put(heartbeat.serverId(), existing.withHeartbeat(heartbeat.timestamp()));
                return true;
            }
            return false;
        });
    }

    @Override
    public CompletableFuture<Optional<ServerMetadata>> getServer(String serverId) {
        return CompletableFuture.completedFuture(Optional.ofNullable(servers.get(serverId)));
    }

    @Override
    public CompletableFuture<Collection<ServerMetadata>> getServersByFamily(String family) {
        return CompletableFuture.completedFuture(
            servers.values().stream()
                .filter(s -> s.family().equals(family))
                .collect(Collectors.toList())
        );
    }

    @Override
    public CompletableFuture<Collection<ServerMetadata>> getServersByType(ServerType type) {
        return CompletableFuture.completedFuture(
            servers.values().stream()
                .filter(s -> s.type() == type)
                .collect(Collectors.toList())
        );
    }

    @Override
    public CompletableFuture<Collection<ServerMetadata>> getServersByStatus(ServerStatus status) {
        return CompletableFuture.completedFuture(
            servers.values().stream()
                .filter(s -> s.status() == status)
                .collect(Collectors.toList())
        );
    }

    @Override
    public CompletableFuture<Collection<ServerMetadata>> getAllServers() {
        return CompletableFuture.completedFuture(new ArrayList<>(servers.values()));
    }

    @Override
    public CompletableFuture<Boolean> unregister(String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            servers.remove(serverId);
            serverInstances.remove(serverId);
            return true;
        });
    }

    @Override
    public CompletableFuture<Collection<String>> checkCrashedServers(int timeoutSeconds) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> crashed = new ArrayList<>();
            
            for (ServerMetadata server : servers.values()) {
                if (server.isCrashed(timeoutSeconds)) {
                    crashed.add(server.id());
                    // Update status to offline
                    servers.put(server.id(), server.withStatus(ServerStatus.OFFLINE));
                }
            }
            
            return crashed;
        });
    }

    @Override
    public CompletableFuture<Boolean> isReserved(String serverId, UUID instanceUuid) {
        return CompletableFuture.supplyAsync(() -> {
            UUID existing = serverInstances.get(serverId);
            if (existing == null) {
                return false;
            }
            
            // Check if it's the same instance trying to reclaim
            if (existing.equals(instanceUuid)) {
                return true;
            }
            
            // Check if the server is crashed
            ServerMetadata metadata = servers.get(serverId);
            if (metadata != null && 
                (metadata.status() == ServerStatus.OFFLINE || metadata.isCrashed(60))) {
                // Allow reclaiming crashed server IDs
                return true;
            }
            
            return false;
        });
    }

    @Override
    public CompletableFuture<Optional<ServerMetadata>> getBestServer(String family) {
        return CompletableFuture.supplyAsync(() -> {
            return servers.values().stream()
                .filter(s -> s.family().equals(family))
                .filter(s -> s.status() == ServerStatus.READY)
                .filter(s -> !s.isCrashed(30))
                .findFirst();
        });
    }
}