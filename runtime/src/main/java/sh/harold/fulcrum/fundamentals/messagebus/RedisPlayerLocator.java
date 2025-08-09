package sh.harold.fulcrum.fundamentals.messagebus;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.PlayerLocator;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Redis-based implementation of PlayerLocator.
 * Stores player presence information with TTL and supports fast lookups.
 */
public class RedisPlayerLocator extends PlayerLocator {
    private static final Logger LOGGER = Logger.getLogger(RedisPlayerLocator.class.getName());
    
    private static final String PLAYER_KEY_PREFIX = "player:";
    private static final int DEFAULT_TTL_SECONDS = 30;
    private static final String LOCATION_SEPARATOR = ":";
    
    private final MessageBus messageBus;
    private final StatefulRedisConnection<String, String> connection;
    private final String serverId;
    private final String proxyId;
    
    /**
     * Creates a new Redis player locator.
     * 
     * @param messageBus the message bus for broadcast queries
     * @param connection the Redis connection
     * @param serverId the current server ID
     * @param proxyId the proxy ID (can be null for non-proxy servers)
     */
    public RedisPlayerLocator(MessageBus messageBus, StatefulRedisConnection<String, String> connection,
                              String serverId, String proxyId) {
        super(messageBus);
        this.messageBus = messageBus;
        this.connection = connection;
        this.serverId = serverId;
        this.proxyId = proxyId;
        
        // Subscribe to player location requests
        messageBus.subscribe("player.locate.request", this::handleLocationRequest);
        messageBus.subscribe("player.locate.response", this::handleLocationResponse);
    }
    
    /**
     * Updates the player's location in Redis.
     * 
     * @param playerId the player's UUID
     * @param serverId the server the player is on
     * @param proxyId the proxy the player is connected through (optional)
     */
    public void updatePlayerLocation(UUID playerId, String serverId, String proxyId) {
        try {
            RedisCommands<String, String> commands = connection.sync();
            String key = PLAYER_KEY_PREFIX + playerId.toString();
            String value = proxyId != null ? proxyId + LOCATION_SEPARATOR + serverId : serverId;
            
            // Set with TTL
            commands.setex(key, DEFAULT_TTL_SECONDS, value);
            
            LOGGER.fine("Updated location for player " + playerId + ": " + value);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to update player location", e);
        }
    }
    
    /**
     * Removes a player's location from Redis.
     * 
     * @param playerId the player's UUID
     */
    public void removePlayerLocation(UUID playerId) {
        try {
            RedisCommands<String, String> commands = connection.sync();
            String key = PLAYER_KEY_PREFIX + playerId.toString();
            commands.del(key);
            
            LOGGER.fine("Removed location for player " + playerId);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to remove player location", e);
        }
    }
    
    /**
     * Refreshes the TTL for a player's location.
     * 
     * @param playerId the player's UUID
     */
    public void refreshPlayerLocation(UUID playerId) {
        try {
            RedisCommands<String, String> commands = connection.sync();
            String key = PLAYER_KEY_PREFIX + playerId.toString();
            commands.expire(key, DEFAULT_TTL_SECONDS);
            
            LOGGER.fine("Refreshed TTL for player " + playerId);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to refresh player location", e);
        }
    }
    
    @Override
    public CompletableFuture<Optional<PlayerLocation>> locatePlayer(UUID playerId) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        
        CompletableFuture<Optional<PlayerLocation>> future = new CompletableFuture<>();
        
        try {
            // First, try fast Redis lookup
            RedisCommands<String, String> commands = connection.sync();
            String key = PLAYER_KEY_PREFIX + playerId.toString();
            String value = commands.get(key);
            
            if (value != null && !value.isEmpty()) {
                // Parse the location value
                PlayerLocation location = parseLocation(value);
                future.complete(Optional.of(location));
                LOGGER.fine("Found player " + playerId + " via Redis: " + value);
                return future;
            }
            
            // If not found in Redis, fall back to broadcast request
            LOGGER.fine("Player " + playerId + " not in Redis, broadcasting request");
            
            // Broadcast a location request to all servers
            messageBus.broadcast("player.locate.request", playerId.toString());
            
            // Set up a timeout for the broadcast response
            future.orTimeout(5, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof java.util.concurrent.TimeoutException) {
                        LOGGER.fine("Player location request timed out for: " + playerId);
                    }
                    return Optional.empty();
                });
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to locate player", e);
            future.complete(Optional.empty());
        }
        
        return future;
    }
    
    /**
     * Handles incoming player location requests from other servers.
     * 
     * @param envelope the message envelope
     */
    private void handleLocationRequest(MessageEnvelope envelope) {
        try {
            String playerIdStr = envelope.getPayload().asText();
            UUID playerId = UUID.fromString(playerIdStr);
            
            // Check if this player is on our server
            // This would need to be implemented based on your server's player tracking
            // For now, we'll just check Redis
            
            RedisCommands<String, String> commands = connection.sync();
            String key = PLAYER_KEY_PREFIX + playerId.toString();
            String value = commands.get(key);
            
            if (value != null && value.contains(serverId)) {
                // We have this player, send response
                messageBus.send(envelope.getSenderId(), "player.locate.response",
                    new LocationResponse(playerId, serverId, proxyId));
                LOGGER.fine("Responded to location request for player: " + playerId);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to handle location request", e);
        }
    }
    
    /**
     * Handles incoming player location responses from other servers.
     * 
     * @param envelope the message envelope
     */
    private void handleLocationResponse(MessageEnvelope envelope) {
        // This would need to be implemented to handle responses
        // and complete pending futures from locatePlayer requests
        LOGGER.fine("Received location response from: " + envelope.getSenderId());
    }
    
    /**
     * Parses a location string from Redis.
     * 
     * @param value the location value (format: "proxyId:serverId" or "serverId")
     * @return the parsed PlayerLocation
     */
    private PlayerLocation parseLocation(String value) {
        if (value.contains(LOCATION_SEPARATOR)) {
            String[] parts = value.split(LOCATION_SEPARATOR, 2);
            return new PlayerLocation(parts[0], parts[1]);
        } else {
            return new PlayerLocation(null, value);
        }
    }
    
    /**
     * Response object for player location queries.
     */
    private static class LocationResponse {
        public final UUID playerId;
        public final String serverId;
        public final String proxyId;
        
        public LocationResponse(UUID playerId, String serverId, String proxyId) {
            this.playerId = playerId;
            this.serverId = serverId;
            this.proxyId = proxyId;
        }
    }
}