package sh.harold.fulcrum.fundamentals.playerdata;

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
import sh.harold.fulcrum.runtime.threading.PaperRuntime;
import sh.harold.fulcrum.runtime.threading.PlayerSnapshot;

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
    private PaperRuntime runtime;
    private boolean trackIps;
    
    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.config = plugin.getConfig();
        this.runtime = container.get(PaperRuntime.class);
        this.trackIps = config.getBoolean("player-data.track-ips", false);
        
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
        PlayerSnapshot snapshot = PlayerSnapshot.capture(runtime, event.getPlayer(), true);
        runtime.runAsync("player data join save", () -> updatePlayerData(snapshot));
    }
    
    private void updatePlayerData(PlayerSnapshot snapshot) {
        try {
            // Access player document
            Document playerDoc = dataAPI.collection(PLAYERS_COLLECTION).document(snapshot.playerId().toString());
            
            boolean exists = playerDoc.exists();
            
            if (!exists) {
                // Create new player document with unified structure
                logger.info("Creating new player document for: " + snapshot.username() + " (" + snapshot.playerId() + ")");
                
                // Core fields - consistent across all servers
                playerDoc.set("uuid", snapshot.playerId().toString());
                playerDoc.set("username", snapshot.username());
                playerDoc.set("firstJoin", snapshot.capturedAtMillis());
                playerDoc.set("lastJoin", snapshot.capturedAtMillis());
                playerDoc.set("lastSeen", snapshot.capturedAtMillis());
                playerDoc.set("joinCount", 1);
                playerDoc.set("totalPlaytime", 0L);
            } else {
                // Update existing player document
                logger.fine("Updating existing player document for: " + snapshot.username());
                
                // Update core fields
                playerDoc.set("username", snapshot.username());
                
                // Check if this is a new session (last join was more than 30 seconds ago)
                // Handle numeric type conversion (JSON storage may return Double instead of Long)
                Long lastJoin = getNumericAsLong(playerDoc.get("lastJoin"), 0L);
                
                if (snapshot.capturedAtMillis() - lastJoin > 30000) {
                    Integer joinCount = getNumericAsInteger(playerDoc.get("joinCount"), 0);
                    playerDoc.set("joinCount", joinCount + 1);
                }
                
                playerDoc.set("lastJoin", snapshot.capturedAtMillis());
                playerDoc.set("lastSeen", snapshot.capturedAtMillis());
            }
            
            // Update backend-specific fields
            playerDoc.set("online", snapshot.online());
            playerDoc.set("lastWorld", snapshot.worldName());
            playerDoc.set("lastLocation", snapshot.compactLocation());
            playerDoc.set("gamemode", snapshot.gameMode());
            
            // Update player stats
            playerDoc.set("level", snapshot.level());
            playerDoc.set("exp", snapshot.exp());
            playerDoc.set("health", snapshot.health());
            playerDoc.set("foodLevel", snapshot.foodLevel());
            
            // Update IP if tracking is enabled (unified field)
            if (trackIps && snapshot.ipAddress() != null) {
                playerDoc.set("lastIp", snapshot.ipAddress());
            }
            
            logger.fine("Successfully updated player data for " + snapshot.username());
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to update player data for " + snapshot.username(), e);
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        PlayerSnapshot snapshot = PlayerSnapshot.capture(runtime, event.getPlayer(), false);
        runtime.runAsync("player data quit save", () -> updateQuitData(snapshot));
    }

    private void updateQuitData(PlayerSnapshot snapshot) {
            try {
                Document playerDoc = dataAPI.collection(PLAYERS_COLLECTION).document(snapshot.playerId().toString());
                    
                if (!playerDoc.exists()) {
                    logger.warning("Player document not found for quitting player: " + snapshot.username());
                    return;
                }
                
                // Update last seen
                playerDoc.set("lastSeen", snapshot.capturedAtMillis());
                playerDoc.set("online", false);
                
                // Save last location and game state before quit
                playerDoc.set("lastWorld", snapshot.worldName());
                playerDoc.set("lastLocation", snapshot.compactLocation());
                playerDoc.set("gamemode", snapshot.gameMode());
                
                // Calculate and update session time
                // Handle numeric type conversion (JSON storage may return Double instead of Long)
                Long lastJoin = getNumericAsLong(playerDoc.get("lastJoin"), 0L);
                if (lastJoin > 0) {
                    long sessionDuration = snapshot.capturedAtMillis() - lastJoin;
                    Long totalPlaytime = getNumericAsLong(playerDoc.get("totalPlaytime"), 0L);
                    playerDoc.set("totalPlaytime", totalPlaytime + sessionDuration);
                    
                    logger.fine(String.format("Updated playtime for %s: session %d ms, total %d ms",
                               snapshot.username(), sessionDuration, totalPlaytime + sessionDuration));
                }
                
                logger.info("Updated quit data for player: " + snapshot.username());
                
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to update quit data for " + snapshot.username(), e);
            }
    }
    
    /**
     * Gets a player document by UUID
     * Checks for document existence to ensure compatibility with proxy
     */
    public CompletableFuture<Document> getPlayerData(UUID uuid) {
        return runtime.supplyAsync("player data get", () -> {
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
        return runtime.supplyAsync("player data exists", () -> {
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
    
    @Override
    public Class<?>[] getDependencies() {
        return new Class<?>[] { DataAPI.class, PaperRuntime.class };
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
