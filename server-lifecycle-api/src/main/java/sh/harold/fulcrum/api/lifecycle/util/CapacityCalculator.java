package sh.harold.fulcrum.api.lifecycle.util;

import sh.harold.fulcrum.api.lifecycle.ServerHeartbeat;
import sh.harold.fulcrum.api.lifecycle.ServerMetadata;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * Utility for calculating server capacity and selecting best servers.
 */
public class CapacityCalculator {
    
    /**
     * Finds the best server for a player to join.
     * Considers health, capacity, and current load.
     */
    public static Optional<ServerMetadata> findBestServer(
        Collection<ServerMetadata> servers,
        Collection<ServerHeartbeat> heartbeats
    ) {
        return servers.stream()
            .filter(server -> server.status() == sh.harold.fulcrum.api.lifecycle.ServerStatus.READY)
            .map(server -> {
                Optional<ServerHeartbeat> heartbeat = findHeartbeat(server.id(), heartbeats);
                return new ServerScore(server, heartbeat.orElse(null));
            })
            .filter(score -> score.isJoinable())
            .min(Comparator.comparing(ServerScore::getScore))
            .map(ServerScore::getServer);
    }
    
    private static Optional<ServerHeartbeat> findHeartbeat(String serverId, Collection<ServerHeartbeat> heartbeats) {
        return heartbeats.stream()
            .filter(hb -> hb.serverId().equals(serverId))
            .findFirst();
    }
    
    /**
     * Internal class for scoring servers.
     */
    private static class ServerScore {
        private final ServerMetadata server;
        private final ServerHeartbeat heartbeat;
        
        ServerScore(ServerMetadata server, ServerHeartbeat heartbeat) {
            this.server = server;
            this.heartbeat = heartbeat;
        }
        
        boolean isJoinable() {
            if (heartbeat == null) {
                return false; // No heartbeat data
            }
            
            // Server must be healthy and not at soft cap
            return heartbeat.isHealthy() && !heartbeat.isSoftCapReached();
        }
        
        double getScore() {
            if (heartbeat == null) {
                return Double.MAX_VALUE;
            }
            
            // Lower score is better
            // Factor in: player count percentage, TPS deviation
            double fillPercentage = (double) heartbeat.playerCount() / heartbeat.softCap();
            double tpsScore = Math.max(0, 20.0 - heartbeat.tps()) / 20.0;
            
            // Weight fill percentage more heavily than TPS
            return (fillPercentage * 0.7) + (tpsScore * 0.3);
        }
        
        ServerMetadata getServer() {
            return server;
        }
    }
    
    /**
     * Checks if a server should accept a new player.
     */
    public static boolean shouldAcceptPlayer(
        ServerHeartbeat heartbeat,
        boolean isStaff,
        boolean isFriend
    ) {
        if (heartbeat.isHardCapReached()) {
            return isStaff; // Only staff can join at hard cap
        }
        
        if (heartbeat.isSoftCapReached()) {
            return isStaff || isFriend; // Staff and friends can join at soft cap
        }
        
        return heartbeat.isHealthy(); // Anyone can join if healthy and below soft cap
    }
}