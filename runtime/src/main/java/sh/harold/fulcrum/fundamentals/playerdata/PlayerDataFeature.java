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
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.runtime.threading.PaperRuntime;
import sh.harold.fulcrum.runtime.threading.PlayerSnapshot;

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
    private DataAuthority.CommandPort commandPort;
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
        this.commandPort = container.getOptional(DataAuthority.CommandPort.class).orElse(null);
        
        if (dataAPI == null || commandPort == null) {
            logger.severe("Data authority not available! PlayerDataFeature requires DataAPI and CommandPort.");
            throw new RuntimeException("Data authority not available");
        }
        
        // Register event listeners
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        
        logger.info("PlayerDataFeature initialized - tracking backend player data");
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        PlayerSnapshot snapshot = PlayerSnapshot.capture(runtime, event.getPlayer(), true);
        runtime.runAsync("player data join save", () -> submitPlayerCommand(DataAuthority.CommandType.RECORD_PLAYER_LOGIN, snapshot));
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        PlayerSnapshot snapshot = PlayerSnapshot.capture(runtime, event.getPlayer(), false);
        runtime.runAsync("player data quit save", () -> submitPlayerCommand(DataAuthority.CommandType.RECORD_PLAYER_LOGOUT, snapshot));
    }

    private void submitPlayerCommand(DataAuthority.CommandType commandType, PlayerSnapshot snapshot) {
        DataAuthority.CommandEnvelope command = new DataAuthority.CommandEnvelope(
            UUID.randomUUID(),
            commandType,
            "paper-runtime",
            "player:" + snapshot.playerId(),
            commandType.name() + ":" + snapshot.playerId() + ":" + snapshot.capturedAtMillis(),
            snapshot.capturedAtMillis() + 5000L,
            "",
            0L,
            playerPayload(commandType, snapshot)
        );

        commandPort.submit(command).whenComplete((result, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "Failed to submit player command for " + snapshot.username(), error);
            } else if (!result.accepted()) {
                logger.warning("Player command rejected for " + snapshot.username() + ": " + result.rejectionReason()
                    + " " + result.message());
            } else {
                logger.fine("Submitted " + commandType + " for " + snapshot.username());
            }
        });
    }

    private Map<String, Object> playerPayload(DataAuthority.CommandType commandType, PlayerSnapshot snapshot) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("playerId", snapshot.playerId().toString());
        payload.put("username", snapshot.username());
        payload.put("timestamp", snapshot.capturedAtMillis());
        payload.put("online", snapshot.online());
        payload.put("lastWorld", snapshot.worldName());
        payload.put("lastLocation", snapshot.compactLocation());
        payload.put("gamemode", snapshot.gameMode());

        if (commandType == DataAuthority.CommandType.RECORD_PLAYER_LOGIN) {
            payload.put("level", snapshot.level());
            payload.put("exp", snapshot.exp());
            payload.put("health", snapshot.health());
            payload.put("foodLevel", snapshot.foodLevel());
        }

        if (commandType == DataAuthority.CommandType.RECORD_PLAYER_LOGOUT) {
            payload.put("playtimeStartField", "lastJoin");
        }

        if (trackIps && snapshot.ipAddress() != null) {
            payload.put("lastIp", snapshot.ipAddress());
        }

        return payload;
    }
    
    /**
     * Gets a player document by UUID
     * Checks for document existence to ensure compatibility with proxy
     */
    public CompletableFuture<Document> getPlayerData(UUID uuid) {
        return dataAPI.collection(PLAYERS_COLLECTION)
            .selectAsync(uuid.toString())
            .thenApply(doc -> doc.exists() ? doc : null)
            .exceptionally(e -> {
                logger.log(Level.WARNING, "Failed to get player data for UUID " + uuid, e);
                return null;
            });
    }
    
    /**
     * Check if a player document exists before creating
     */
    public CompletableFuture<Boolean> playerDataExists(UUID uuid) {
        return dataAPI.collection(PLAYERS_COLLECTION)
            .selectAsync(uuid.toString())
            .thenApply(Document::exists)
            .exceptionally(e -> {
                logger.log(Level.WARNING, "Failed to check player data existence for UUID " + uuid, e);
                return false;
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
        return new Class<?>[] { DataAPI.class, DataAuthority.CommandPort.class, PaperRuntime.class };
    }
}
