package sh.harold.fulcrum.fundamentals.playerdata;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PlayerDataFeature implements PluginFeature, Listener {
    private static final String PLAYERS_COLLECTION = "players";
    
    private JavaPlugin plugin;
    private Logger logger;
    private DataAPI dataAPI;
    private FileConfiguration config;
    
    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.config = plugin.getConfig();
        
        // Get DataAPI from DependencyContainer
        this.dataAPI = container.getOptional(DataAPI.class).orElse(null);
        
        if (dataAPI == null) {
            logger.severe("DataAPI not available! PlayerDataFeature requires DataAPI to be initialized first.");
            throw new RuntimeException("DataAPI not available");
        }
        
        // Register event listeners
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        
        logger.info("PlayerDataFeature initialized - tracking backend player data");
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Run async to avoid blocking main thread
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            updatePlayerData(player);
        });
    }
    
    private void updatePlayerData(Player player) {
        try {
            // Access player document
            Document playerDoc = dataAPI.collection(PLAYERS_COLLECTION).document(player.getUniqueId().toString());
            
            boolean exists = playerDoc.exists();
            
            if (!exists) {
                // Create new player document with unified structure
                logger.info("Creating new player document for: " + player.getName() + " (" + player.getUniqueId() + ")");
                
                // Core fields - consistent across all servers
                playerDoc.set("uuid", player.getUniqueId().toString());
                playerDoc.set("username", player.getName());
                playerDoc.set("firstJoin", System.currentTimeMillis());
                playerDoc.set("lastJoin", System.currentTimeMillis());
                playerDoc.set("lastSeen", System.currentTimeMillis());
                playerDoc.set("joinCount", 1);
                playerDoc.set("totalPlaytime", 0L);
            } else {
                // Update existing player document
                logger.fine("Updating existing player document for: " + player.getName());
                
                // Update core fields
                playerDoc.set("username", player.getName());
                
                // Check if this is a new session (last join was more than 30 seconds ago)
                // Handle numeric type conversion (JSON storage may return Double instead of Long)
                Long lastJoin = getNumericAsLong(playerDoc.get("lastJoin"), 0L);
                
                if (System.currentTimeMillis() - lastJoin > 30000) {
                    Integer joinCount = getNumericAsInteger(playerDoc.get("joinCount"), 0);
                    playerDoc.set("joinCount", joinCount + 1);
                }
                
                playerDoc.set("lastJoin", System.currentTimeMillis());
                playerDoc.set("lastSeen", System.currentTimeMillis());
            }
            
            // Update backend-specific fields
            Location loc = player.getLocation();
            playerDoc.set("lastWorld", player.getWorld().getName());
            playerDoc.set("lastLocation", String.format("%.2f,%.2f,%.2f",
                loc.getX(), loc.getY(), loc.getZ()));
            playerDoc.set("gamemode", player.getGameMode().toString());
            
            // Update player stats
            playerDoc.set("level", player.getLevel());
            playerDoc.set("exp", player.getExp());
            playerDoc.set("health", player.getHealth());
            playerDoc.set("foodLevel", player.getFoodLevel());
            
            // Update IP if tracking is enabled (unified field)
            if (config.getBoolean("player-data.track-ips", false)) {
                if (player.getAddress() != null) {
                    playerDoc.set("lastIp", player.getAddress().getAddress().getHostAddress());
                }
            }
            
            logger.fine("Successfully updated player data for " + player.getName());
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to update player data for " + player.getName(), e);
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Document playerDoc = dataAPI.collection(PLAYERS_COLLECTION).document(player.getUniqueId().toString());
                    
                if (!playerDoc.exists()) {
                    logger.warning("Player document not found for quitting player: " + player.getName());
                    return;
                }
                
                // Update last seen
                playerDoc.set("lastSeen", System.currentTimeMillis());
                playerDoc.set("online", false);
                
                // Save last location and game state before quit
                Location loc = player.getLocation();
                playerDoc.set("lastWorld", player.getWorld().getName());
                playerDoc.set("lastLocation", String.format("%.2f,%.2f,%.2f",
                    loc.getX(), loc.getY(), loc.getZ()));
                playerDoc.set("gamemode", player.getGameMode().toString());
                
                // Calculate and update session time
                // Handle numeric type conversion (JSON storage may return Double instead of Long)
                Long lastJoin = getNumericAsLong(playerDoc.get("lastJoin"), 0L);
                if (lastJoin > 0) {
                    long sessionDuration = System.currentTimeMillis() - lastJoin;
                    Long totalPlaytime = getNumericAsLong(playerDoc.get("totalPlaytime"), 0L);
                    playerDoc.set("totalPlaytime", totalPlaytime + sessionDuration);
                    
                    logger.fine(String.format("Updated playtime for %s: session %d ms, total %d ms",
                               player.getName(), sessionDuration, totalPlaytime + sessionDuration));
                }
                
                logger.info("Updated quit data for player: " + player.getName());
                
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to update quit data for " + player.getName(), e);
            }
        });
    }
    
    /**
     * Gets a player document by UUID
     * Checks for document existence to ensure compatibility with proxy
     */
    public CompletableFuture<Document> getPlayerData(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Document doc = dataAPI.collection(PLAYERS_COLLECTION).document(uuid.toString());
                return doc.exists() ? doc : null;
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to get player data for UUID " + uuid, e);
                return null;
            }
        });
    }
    
    /**
     * Check if a player document exists before creating
     */
    public CompletableFuture<Boolean> playerDataExists(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Document doc = dataAPI.collection(PLAYERS_COLLECTION).document(uuid.toString());
                return doc.exists();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to check player data existence for UUID " + uuid, e);
                return false;
            }
        });
    }
    
    @Override
    public void shutdown() {
        logger.info("Shutting down PlayerDataFeature");
    }
    
    @Override
    public int getPriority() {
        return 50; // After DataAPI (priority 10)
    }
    
    /**
     * Safely converts a numeric value to Long, handling different numeric types
     * that may be returned from different storage backends (e.g., JSON returns Double)
     *
     * @param value The value to convert
     * @param defaultValue The default value if conversion fails
     * @return The value as a Long
     */
    private Long getNumericAsLong(Object value, Long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        
        if (value instanceof Long) {
            return (Long) value;
        } else if (value instanceof Double) {
            return ((Double) value).longValue();
        } else if (value instanceof Integer) {
            return ((Integer) value).longValue();
        } else if (value instanceof Float) {
            return ((Float) value).longValue();
        } else if (value instanceof Number) {
            return ((Number) value).longValue();
        } else if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                logger.fine("Failed to parse Long from string: " + value);
                return defaultValue;
            }
        }
        
        logger.fine("Unexpected type for Long conversion: " + value.getClass().getName());
        return defaultValue;
    }
    
    /**
     * Safely converts a numeric value to Integer, handling different numeric types
     * that may be returned from different storage backends (e.g., JSON returns Double)
     *
     * @param value The value to convert
     * @param defaultValue The default value if conversion fails
     * @return The value as an Integer
     */
    private Integer getNumericAsInteger(Object value, Integer defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        
        if (value instanceof Integer) {
            return (Integer) value;
        } else if (value instanceof Double) {
            return ((Double) value).intValue();
        } else if (value instanceof Long) {
            return ((Long) value).intValue();
        } else if (value instanceof Float) {
            return ((Float) value).intValue();
        } else if (value instanceof Number) {
            return ((Number) value).intValue();
        } else if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                logger.fine("Failed to parse Integer from string: " + value);
                return defaultValue;
            }
        }
        
        logger.fine("Unexpected type for Integer conversion: " + value.getClass().getName());
        return defaultValue;
    }
}
