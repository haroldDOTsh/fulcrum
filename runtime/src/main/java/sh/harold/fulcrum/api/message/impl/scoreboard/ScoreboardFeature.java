package sh.harold.fulcrum.api.message.impl.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.message.impl.scoreboard.impl.DefaultPlayerScoreboardManager;
import sh.harold.fulcrum.api.message.impl.scoreboard.impl.DefaultRenderingPipeline;
import sh.harold.fulcrum.api.message.impl.scoreboard.impl.DefaultScoreboardRegistry;
import sh.harold.fulcrum.api.message.impl.scoreboard.impl.DefaultTitleManager;
import sh.harold.fulcrum.api.message.impl.scoreboard.nms.NMSAdapter;
import sh.harold.fulcrum.api.message.scoreboard.ScoreboardService;
import sh.harold.fulcrum.api.message.scoreboard.player.PlayerScoreboardManager;
import sh.harold.fulcrum.api.message.scoreboard.registry.ScoreboardDefinition;
import sh.harold.fulcrum.api.message.scoreboard.registry.ScoreboardRegistry;
import sh.harold.fulcrum.api.message.scoreboard.render.PacketRenderer;
import sh.harold.fulcrum.api.message.scoreboard.render.RenderedScoreboard;
import sh.harold.fulcrum.api.message.scoreboard.render.RenderingPipeline;
import sh.harold.fulcrum.api.message.scoreboard.render.TitleManager;
import sh.harold.fulcrum.api.message.scoreboard.util.ScoreboardFlashTask;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;

public class ScoreboardFeature implements PluginFeature, Listener {

    private ScoreboardService scoreboardService;
    private DefaultPlayerScoreboardManager playerManager;
    private DefaultRenderingPipeline renderingPipeline;
    private PacketRenderer packetRenderer;
    private ScoreboardRegistry registry;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        // Create NMS adapter based on server version
        NMSAdapter nmsAdapter = createNMSAdapter(plugin);
        if (nmsAdapter == null) {
            plugin.getLogger().severe("Failed to create NMS adapter for server version: " + getServerVersion(plugin));
            return;
        }

        // Create packet renderer from NMS adapter
        this.packetRenderer = nmsAdapter.createPacketRenderer();

        // Create core implementations
        this.registry = new DefaultScoreboardRegistry();
        TitleManager titleManager = new DefaultTitleManager();
        this.playerManager = new DefaultPlayerScoreboardManager();
        this.renderingPipeline = new DefaultRenderingPipeline(titleManager, playerManager);

        // Create main service
        this.scoreboardService = new SimpleScoreboardService(registry, playerManager, renderingPipeline, packetRenderer);

        // Register services in dependency container
        container.register(ScoreboardService.class, scoreboardService);
        container.register(ScoreboardRegistry.class, registry);
        container.register(PlayerScoreboardManager.class, playerManager);
        container.register(RenderingPipeline.class, renderingPipeline);
        container.register(TitleManager.class, titleManager);
        container.register(PacketRenderer.class, packetRenderer);

        // Register event listener for player joins
        Bukkit.getPluginManager().registerEvents(this, plugin);

        plugin.getLogger().info("[SCOREBOARD] Initialized with NMS version: " + nmsAdapter.getVersionInfo());
    }

    @Override
    public void shutdown() {
        if (playerManager != null) {
            playerManager.clearAllPlayerData();
        }

        if (packetRenderer != null) {
            // Clear all active scoreboard displays
            for (int i = 0; i < packetRenderer.getActiveDisplayCount(); i++) {
                // TODO: This would need to be implemented in the actual packet renderer
                // packetRenderer.clearAllDisplays();
            }
        }
    }

    @Override
    public int getPriority() {
        return 5; // Load after core features but before game features
    }

    private NMSAdapter createNMSAdapter(JavaPlugin plugin) {
        String version = getServerVersion(plugin);

        try {
            switch (version) {
                case "v1_21_R1":
                    return (NMSAdapter) Class.forName("sh.harold.fulcrum.api.message.impl.scoreboard.nms.v1_21_R1.NMSAdapterV1_21_R1")
                            .getDeclaredConstructor().newInstance();
                default:
                    Bukkit.getLogger().severe("Unsupported server version: " + version + ". Supported versions: v1_21_R1 (Minecraft 1.21.6/7)");
                    return null;
            }
        } catch (Exception e) {
            Bukkit.getLogger().severe("Failed to create NMS adapter for version " + version + ": " + e.getMessage());
            return null;
        }
    }

    private String getServerVersion(JavaPlugin plugin) {
        try {
            String minecraftVersion = Bukkit.getMinecraftVersion();
            plugin.getLogger().info("Detected Minecraft version: " + minecraftVersion);

            // Map Minecraft versions to NMS versions
            String nmsVersion = mapMinecraftVersionToNMS(minecraftVersion);
            plugin.getLogger().info("Mapped to NMS version: " + nmsVersion);

            return nmsVersion;
        } catch (Exception e) {
            plugin.getLogger().severe("Error detecting server version: " + e.getMessage());
            return "unknown";
        }
    }

    private String mapMinecraftVersionToNMS(String minecraftVersion) {
        if (minecraftVersion == null) {
            return "unknown";
        }

        // Map Minecraft versions to their corresponding NMS versions
        // Minecraft 1.21.6 and 1.21.7 both use v1_21_R1
        if (minecraftVersion.startsWith("1.21.6") || minecraftVersion.startsWith("1.21.7") || minecraftVersion.startsWith("1.21.8")) {
            return "v1_21_R1";
        }

        return "unknown";
    }

    /**
     * Simple implementation of ScoreboardService that delegates to the individual components
     * without requiring YAML configuration.
     */
    private static class SimpleScoreboardService implements ScoreboardService {

        private final ScoreboardRegistry registry;
        private final PlayerScoreboardManager playerManager;
        private final RenderingPipeline renderingPipeline;
        private final PacketRenderer packetRenderer;
        private final ScheduledExecutorService scheduler;

        // Active refresh tasks
        private final ConcurrentHashMap<UUID, CompletableFuture<Void>> refreshTasks = new ConcurrentHashMap<>();

        public SimpleScoreboardService(ScoreboardRegistry registry,
                                       PlayerScoreboardManager playerManager,
                                       RenderingPipeline renderingPipeline,
                                       PacketRenderer packetRenderer) {
            this.registry = registry;
            this.playerManager = playerManager;
            this.renderingPipeline = renderingPipeline;
            this.packetRenderer = packetRenderer;
            this.scheduler = Executors.newScheduledThreadPool(2);

            // Register event listener and start refresh task
            Bukkit.getPluginManager().registerEvents(new PlayerListener(), Bukkit.getPluginManager().getPlugin("Fulcrum"));
            scheduler.scheduleAtFixedRate(this::refreshActiveScoreboards, 1, 1, TimeUnit.SECONDS);
        }

        @Override
        public String getPlayerTitle(UUID playerId) {
            if (playerId == null) {
                throw new IllegalArgumentException("Player ID cannot be null");
            }
            return playerManager.getPlayerTitle(playerId);
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
        public void flashModule(UUID playerId, int moduleIndex, sh.harold.fulcrum.api.message.scoreboard.module.ScoreboardModule module, Duration duration) {
            if (playerId == null) {
                throw new IllegalArgumentException("Player ID cannot be null");
            }
            if (module == null) {
                throw new IllegalArgumentException("Module cannot be null");
            }
            if (duration == null || duration.isNegative()) {
                throw new IllegalArgumentException("Duration cannot be null or negative");
            }

            // Create and schedule the flash task for automatic expiration
            ScoreboardFlashTask flashTask = new ScoreboardFlashTask(playerId, moduleIndex, module, playerManager, duration);

            // Set the refresh callback to trigger immediate refresh on expiration
            flashTask.setRefreshCallback(this::refreshPlayerScoreboard);

            // Schedule the task to run after the flash duration
            ScheduledFuture<?> scheduledTask = scheduler.schedule(flashTask, duration.toMillis(), TimeUnit.MILLISECONDS);

            // Set the scheduled future on the task for proper cancellation handling
            flashTask.setScheduledFuture(scheduledTask);

            // Start the flash operation
            playerManager.startFlash(playerId, moduleIndex, module, duration);

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

        private class PlayerListener implements Listener {
            @EventHandler
            public void onPlayerQuit(PlayerQuitEvent event) {
                clearPlayerData(event.getPlayer().getUniqueId());
            }
        }
    }
}