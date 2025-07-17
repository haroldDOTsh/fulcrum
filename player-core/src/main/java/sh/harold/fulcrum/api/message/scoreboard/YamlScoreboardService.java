package sh.harold.fulcrum.api.message.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import sh.harold.fulcrum.api.message.scoreboard.module.ScoreboardModule;
import sh.harold.fulcrum.api.message.scoreboard.player.PlayerScoreboardManager;
import sh.harold.fulcrum.api.message.scoreboard.registry.ScoreboardDefinition;
import sh.harold.fulcrum.api.message.scoreboard.registry.ScoreboardRegistry;
import sh.harold.fulcrum.api.message.scoreboard.render.PacketRenderer;
import sh.harold.fulcrum.api.message.scoreboard.render.RenderedScoreboard;
import sh.harold.fulcrum.api.message.scoreboard.render.RenderingPipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class YamlScoreboardService implements ScoreboardService, Listener {
    
    private final Path scoreboardsDirectory;
    private final ScoreboardRegistry registry;
    private final PlayerScoreboardManager playerManager;
    private final RenderingPipeline renderingPipeline;
    private final PacketRenderer packetRenderer;
    private final ScheduledExecutorService scheduler;
    
    // Configuration cache
    private final Map<String, FileConfiguration> configCache = new ConcurrentHashMap<>();
    
    // Active refresh tasks
    private final Map<UUID, CompletableFuture<Void>> refreshTasks = new ConcurrentHashMap<>();
    
    public YamlScoreboardService(Path scoreboardsDirectory, ScoreboardRegistry registry, 
                                PlayerScoreboardManager playerManager, RenderingPipeline renderingPipeline,
                                PacketRenderer packetRenderer) {
        this.scoreboardsDirectory = scoreboardsDirectory;
        this.registry = registry;
        this.playerManager = playerManager;
        this.renderingPipeline = renderingPipeline;
        this.packetRenderer = packetRenderer;
        this.scheduler = Executors.newScheduledThreadPool(2);
    }
    
    public void initialize() {
        try {
            if (Files.notExists(scoreboardsDirectory)) {
                Files.createDirectories(scoreboardsDirectory);
            }
            loadScoreboardDefinitions();
            
            // Register event listener
            Bukkit.getPluginManager().registerEvents(this, Bukkit.getPluginManager().getPlugin("Fulcrum"));
            
            // Start periodic refresh task
            scheduler.scheduleAtFixedRate(this::refreshActiveScoreboards, 1, 1, TimeUnit.SECONDS);
            
        } catch (IOException e) {
            Bukkit.getLogger().severe("Failed to initialize scoreboard service: " + e.getMessage());
        }
    }
    
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Clear all active scoreboards
        for (Player player : Bukkit.getOnlinePlayers()) {
            hideScoreboard(player.getUniqueId());
        }
    }
    
    private void loadScoreboardDefinitions() {
        configCache.clear();
        
        try {
            Files.walk(scoreboardsDirectory, 1)
                .filter(path -> !Files.isDirectory(path) && path.toString().endsWith(".yml"))
                .forEach(this::loadScoreboardDefinition);
        } catch (IOException e) {
            Bukkit.getLogger().severe("Failed to load scoreboard definitions: " + e.getMessage());
        }
    }
    
    private void loadScoreboardDefinition(Path configFile) {
        try {
            String scoreboardId = configFile.getFileName().toString().replace(".yml", "");
            FileConfiguration config = YamlConfiguration.loadConfiguration(configFile.toFile());
            
            configCache.put(scoreboardId, config);
            
            // Create scoreboard definition from config
            ScoreboardDefinition definition = createDefinitionFromConfig(config);
            registry.register(definition);
            
        } catch (Exception e) {
            Bukkit.getLogger().severe("Failed to load scoreboard definition from " + configFile + ": " + e.getMessage());
        }
    }
    
    private ScoreboardDefinition createDefinitionFromConfig(FileConfiguration config) {
        String scoreboardId = config.getString("id", "default");
        String title = config.getString("title", "Scoreboard");
        // For now, return a basic definition - this would be expanded based on the config structure
        return new ScoreboardDefinition(scoreboardId, title, new HashMap<>());
    }

    @Override
    public void registerScoreboard(String scoreboardId, ScoreboardDefinition definition) {
        if (scoreboardId == null || scoreboardId.isEmpty()) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null or empty");
        }
        if (definition == null) {
            throw new IllegalArgumentException("Scoreboard definition cannot be null");
        }
        
        registry.register(definition);
    }

    @Override
    public void unregisterScoreboard(String scoreboardId) {
        if (scoreboardId == null || scoreboardId.isEmpty()) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null or empty");
        }
        
        // Hide scoreboard for all players currently viewing it
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (scoreboardId.equals(getCurrentScoreboardId(player.getUniqueId()))) {
                hideScoreboard(player.getUniqueId());
            }
        }
        
        registry.unregister(scoreboardId);
        configCache.remove(scoreboardId);
    }

    @Override
    public boolean isScoreboardRegistered(String scoreboardId) {
        if (scoreboardId == null || scoreboardId.isEmpty()) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null or empty");
        }
        return registry.isRegistered(scoreboardId);
    }

    @Override
    public void showScoreboard(UUID playerId, String scoreboardId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (scoreboardId == null) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null");
        }
        
        ScoreboardDefinition definition = registry.get(scoreboardId);
        if (definition == null) {
            throw new IllegalStateException("Scoreboard not registered: " + scoreboardId);
        }
        
        playerManager.setPlayerScoreboard(playerId, scoreboardId);
        refreshPlayerScoreboard(playerId);
    }

    @Override
    public void hideScoreboard(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        
        packetRenderer.hideScoreboard(playerId);
        playerManager.removePlayerScoreboard(playerId);
    }

    @Override
    public void flashModule(UUID playerId, int priority, ScoreboardModule module, Duration duration) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (module == null) {
            throw new IllegalArgumentException("Module cannot be null");
        }
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException("Duration cannot be null or negative");
        }
        
        playerManager.startFlash(playerId, priority, module, duration);
        refreshPlayerScoreboard(playerId);
    }

    @Override
    public void setPlayerTitle(UUID playerId, String title) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        
        playerManager.setPlayerTitle(playerId, title);
        
        // Update only the title if player has an active scoreboard
        if (hasScoreboardDisplayed(playerId)) {
            String processedTitle = renderingPipeline.processColorCodes(title);
            packetRenderer.updateTitle(playerId, processedTitle);
        }
    }

    @Override
    public void clearPlayerTitle(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        
        playerManager.clearPlayerTitle(playerId);
        refreshPlayerScoreboard(playerId);
    }

    @Override
    public void refreshPlayerScoreboard(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        
        String scoreboardId = getCurrentScoreboardId(playerId);
        if (scoreboardId == null) {
            return;
        }
        
        ScoreboardDefinition definition = registry.get(scoreboardId);
        if (definition == null) {
            return;
        }
        
        // Cancel any existing refresh task
        CompletableFuture<Void> existingTask = refreshTasks.get(playerId);
        if (existingTask != null) {
            existingTask.cancel(false);
        }
        
        // Create new refresh task
        CompletableFuture<Void> refreshTask = CompletableFuture.runAsync(() -> {
            try {
                RenderedScoreboard rendered = renderingPipeline.renderScoreboard(playerId, definition);
                packetRenderer.updateScoreboard(playerId, rendered);
            } catch (Exception e) {
                Bukkit.getLogger().warning("Failed to refresh scoreboard for player " + playerId + ": " + e.getMessage());
            }
        }, scheduler);
        
        refreshTasks.put(playerId, refreshTask);
    }

    @Override
    public String getCurrentScoreboardId(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        return playerManager.getCurrentScoreboardId(playerId);
    }

    @Override
    public boolean hasScoreboardDisplayed(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        return playerManager.hasScoreboard(playerId);
    }

    @Override
    public void setModuleOverride(UUID playerId, String moduleId, boolean enabled) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (moduleId == null) {
            throw new IllegalArgumentException("Module ID cannot be null");
        }
        
        playerManager.setModuleOverride(playerId, moduleId, enabled);
        refreshPlayerScoreboard(playerId);
    }

    @Override
    public boolean isModuleOverrideEnabled(UUID playerId, String moduleId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (moduleId == null) {
            throw new IllegalArgumentException("Module ID cannot be null");
        }
        return playerManager.hasModuleOverride(playerId, moduleId);
    }

    @Override
    public void clearPlayerData(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        
        // Cancel any refresh tasks
        CompletableFuture<Void> refreshTask = refreshTasks.remove(playerId);
        if (refreshTask != null) {
            refreshTask.cancel(false);
        }
        
        // Clear packet renderer state
        packetRenderer.clearPlayerPackets(playerId);
        
        // Clear player manager state
        playerManager.clearPlayerData(playerId);
    }
    
    private void refreshActiveScoreboards() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            
            // Check if player needs a refresh
            if (playerManager.needsRefresh(playerId)) {
                playerManager.clearRefreshFlag(playerId);
                refreshPlayerScoreboard(playerId);
            }
        }
    }
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        
        // Auto-show default scoreboard if configured
        // This would be configurable in the future
        String defaultScoreboardId = "default";
        if (isScoreboardRegistered(defaultScoreboardId)) {
            scheduler.schedule(() -> showScoreboard(playerId, defaultScoreboardId), 1, TimeUnit.SECONDS);
        }
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        clearPlayerData(event.getPlayer().getUniqueId());
    }
}