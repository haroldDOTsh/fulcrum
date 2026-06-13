package sh.harold.fulcrum.fundamentals.playerdata;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.runtime.threading.PaperRuntime;
import sh.harold.fulcrum.runtime.threading.PlayerSnapshot;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PlayerDataFeature implements PluginFeature, Listener {
    private JavaPlugin plugin;
    private Logger logger;
    private DataAuthority.CommandPort commandPort;
    private DataAuthority.PlayerProfileReader profileReader;
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
        
        this.commandPort = container.getOptional(DataAuthority.CommandPort.class).orElse(null);
        this.profileReader = container.getOptional(DataAuthority.PlayerProfileReader.class).orElse(null);
        
        if (commandPort == null || profileReader == null) {
            logger.severe("Data authority not available! PlayerDataFeature requires command and profile reader ports.");
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
    
    public CompletableFuture<Optional<DataAuthority.PlayerProfileSnapshot>> getPlayerProfile(UUID uuid) {
        return profileReader.findProfile(uuid)
            .toCompletableFuture()
            .exceptionally(e -> {
                logger.log(Level.WARNING, "Failed to get player profile for UUID " + uuid, e);
                return Optional.empty();
            });
    }
    
    public CompletableFuture<Boolean> playerProfileExists(UUID uuid) {
        return profileReader.profileExists(uuid)
            .toCompletableFuture()
            .exceptionally(e -> {
                logger.log(Level.WARNING, "Failed to check player profile existence for UUID " + uuid, e);
                return false;
            });
    }
    
    @Override
    public void shutdown() {
        logger.info("Shutting down PlayerDataFeature");
    }
    
    @Override
    public int getPriority() {
        return 50; // After data authority (priority 10)
    }
    
    @Override
    public Class<?>[] getDependencies() {
        return new Class<?>[] {
            DataAuthority.CommandPort.class,
            DataAuthority.PlayerProfileReader.class,
            PaperRuntime.class
        };
    }
}
