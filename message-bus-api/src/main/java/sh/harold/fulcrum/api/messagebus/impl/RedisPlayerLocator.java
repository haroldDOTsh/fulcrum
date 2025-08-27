package sh.harold.fulcrum.api.messagebus.impl;

import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.PlayerLocator;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Redis-based implementation of PlayerLocator.
 * Stores player presence information with TTL and supports fast lookups.
 * 
 * Uses reflection to avoid compile-time dependencies on Lettuce.
 */
public class RedisPlayerLocator extends PlayerLocator {
    
    private static final Logger LOGGER = Logger.getLogger(RedisPlayerLocator.class.getName());
    private static final String PLAYER_KEY_PREFIX = "player:online:";
    private static final int DEFAULT_TTL_SECONDS = 30;
    
    private final MessageBus messageBus;
    private final Object connection;
    
    /**
     * Creates a new Redis player locator.
     *
     * @param messageBus the message bus for broadcast queries
     * @param connection the Redis connection (as Object to avoid compile-time dependency)
     */
    public RedisPlayerLocator(MessageBus messageBus, Object connection) {
        super(messageBus);
        this.messageBus = messageBus;
        this.connection = connection;
        
        // Subscribe to player presence requests
        messageBus.subscribe("player.presence.request", this::handlePresenceRequest);
        messageBus.subscribe("player.presence.response", this::handlePresenceResponse);
    }
    
    /**
     * Marks a player as online in Redis.
     *
     * @param playerId the player's UUID
     */
    public void setPlayerOnline(UUID playerId) {
        try {
            // Get sync commands from connection
            Object commands = connection.getClass().getMethod("sync").invoke(connection);
            String key = PLAYER_KEY_PREFIX + playerId.toString();
            
            // Set with TTL using reflection
            commands.getClass().getMethod("setex", String.class, long.class, String.class)
                .invoke(commands, key, (long) DEFAULT_TTL_SECONDS, "1");
            
            LOGGER.fine("Marked player " + playerId + " as online");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to mark player as online", e);
        }
    }
    
    /**
     * Marks a player as offline in Redis.
     *
     * @param playerId the player's UUID
     */
    public void setPlayerOffline(UUID playerId) {
        try {
            // Get sync commands from connection
            Object commands = connection.getClass().getMethod("sync").invoke(connection);
            String key = PLAYER_KEY_PREFIX + playerId.toString();
            
            // Delete the key
            commands.getClass().getMethod("del", String[].class)
                .invoke(commands, new Object[] { new String[] { key } });
            
            LOGGER.fine("Marked player " + playerId + " as offline");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to mark player as offline", e);
        }
    }
    
    /**
     * Refreshes the TTL for a player's online status.
     *
     * @param playerId the player's UUID
     */
    public void refreshPlayerStatus(UUID playerId) {
        try {
            // Get sync commands from connection
            Object commands = connection.getClass().getMethod("sync").invoke(connection);
            String key = PLAYER_KEY_PREFIX + playerId.toString();
            
            // Expire the key
            commands.getClass().getMethod("expire", String.class, long.class)
                .invoke(commands, key, (long) DEFAULT_TTL_SECONDS);
            
            LOGGER.fine("Refreshed TTL for player " + playerId);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to refresh player status", e);
        }
    }
    
    @Override
    public CompletableFuture<Boolean> isPlayerOnline(UUID playerId) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(false);
        }
        
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        try {
            // First, try fast Redis lookup
            Object commands = connection.getClass().getMethod("sync").invoke(connection);
            String key = PLAYER_KEY_PREFIX + playerId.toString();
            
            // Get the value
            String value = (String) commands.getClass().getMethod("get", String.class)
                .invoke(commands, key);
            
            if (value != null) {
                future.complete(true);
                LOGGER.fine("Found player " + playerId + " online via Redis");
                return future;
            }
            
            // If not found in Redis, fall back to broadcast request
            LOGGER.fine("Player " + playerId + " not in Redis, broadcasting request");
            
            // Broadcast a presence request to all servers
            messageBus.broadcast("player.presence.request", playerId.toString());
            
            // Set up a timeout for the broadcast response
            future.orTimeout(5, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof java.util.concurrent.TimeoutException) {
                        LOGGER.fine("Player presence request timed out for: " + playerId);
                    }
                    return false;
                });
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to check player online status", e);
            future.complete(false);
        }
        
        return future;
    }
    
    /**
     * Handles incoming player presence requests from other servers.
     *
     * @param envelope the message envelope
     */
    private void handlePresenceRequest(MessageEnvelope envelope) {
        try {
            String playerIdStr = envelope.getPayload().asText();
            UUID playerId = UUID.fromString(playerIdStr);
            
            // Check if this player is online on our server
            Object commands = connection.getClass().getMethod("sync").invoke(connection);
            String key = PLAYER_KEY_PREFIX + playerId.toString();
            String value = (String) commands.getClass().getMethod("get", String.class)
                .invoke(commands, key);
            
            if (value != null) {
                // Player is online, send response
                messageBus.send(envelope.getSenderId(), "player.presence.response",
                    new PresenceResponse(playerId, true));
                LOGGER.fine("Responded to presence request for player: " + playerId);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to handle presence request", e);
        }
    }
    
    /**
     * Handles incoming player presence responses from other servers.
     *
     * @param envelope the message envelope
     */
    private void handlePresenceResponse(MessageEnvelope envelope) {
        // This would need to be implemented to handle responses
        // and complete pending futures from isPlayerOnline requests
        LOGGER.fine("Received presence response from: " + envelope.getSenderId());
    }
    
    /**
     * Response object for player presence queries.
     */
    private static class PresenceResponse {
        public final UUID playerId;
        public final boolean online;
        
        public PresenceResponse(UUID playerId, boolean online) {
            this.playerId = playerId;
            this.online = online;
        }
    }
}