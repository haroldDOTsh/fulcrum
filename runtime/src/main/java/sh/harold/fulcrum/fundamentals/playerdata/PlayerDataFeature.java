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
import sh.harold.fulcrum.api.data.authority.client.AuthorityCommands;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.runtime.threading.PaperRuntime;
import sh.harold.fulcrum.runtime.threading.PlayerSnapshot;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PlayerDataFeature implements PluginFeature, Listener {
    private JavaPlugin plugin;
    private Logger logger;
    private DataAuthority.CommandPort commandPort;
    private DataAuthority.CommandSubmissionPort commandSubmissionPort;
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
        this.commandSubmissionPort = container.getOptional(DataAuthority.CommandSubmissionPort.class).orElse(null);
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
        AuthorityCommands.PlayerCommands playerCommands = AuthorityCommands.actor("paper-runtime")
            .player(snapshot.playerId());
        DataAuthority.PlayerProfileCommand command = commandType == DataAuthority.CommandType.RECORD_PLAYER_LOGIN
            ? playerCommands.recordLogin(
                snapshot.username(),
                snapshot.capturedAtMillis(),
                null,
                null,
                trackIps ? snapshot.ipAddress() : null,
                snapshot.worldName(),
                snapshot.compactLocation(),
                snapshot.gameMode(),
                snapshot.level(),
                snapshot.exp(),
                snapshot.health(),
                snapshot.foodLevel()
            )
            : playerCommands.recordLogout(
                snapshot.username(),
                snapshot.capturedAtMillis(),
                null,
                null,
                trackIps ? snapshot.ipAddress() : null,
                snapshot.worldName(),
                snapshot.compactLocation(),
                snapshot.gameMode(),
                "lastJoin"
            );

        if (submitDurableIfAvailable(command, snapshot.username())) {
            return;
        }

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

    private boolean submitDurableIfAvailable(
        DataAuthority.PlayerProfileCommand command,
        String username
    ) {
        if (commandSubmissionPort == null) {
            return false;
        }
        commandSubmissionPort.submitDurable(command).whenComplete((receipt, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "Failed to durably submit player command for " + username, error);
            } else {
                logger.fine("Durably submitted " + command.type() + " for " + username
                    + " to " + receipt.commandTopic() + "[" + receipt.partition() + "]@" + receipt.offset());
            }
        });
        return true;
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
        return 50; // After data authority
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
