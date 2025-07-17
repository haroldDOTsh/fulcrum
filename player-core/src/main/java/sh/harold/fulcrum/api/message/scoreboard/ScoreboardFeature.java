package sh.harold.fulcrum.api.message.scoreboard;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.message.scoreboard.command.ScoreboardDebugCommand;
import sh.harold.fulcrum.api.message.scoreboard.impl.DefaultRenderingPipeline;
import sh.harold.fulcrum.api.message.scoreboard.impl.DefaultPlayerScoreboardManager;
import sh.harold.fulcrum.api.message.scoreboard.impl.DefaultScoreboardRegistry;
import sh.harold.fulcrum.api.message.scoreboard.impl.DefaultTitleManager;
import sh.harold.fulcrum.api.message.scoreboard.nms.NMSAdapter;
import sh.harold.fulcrum.api.message.scoreboard.player.PlayerScoreboardManager;
import sh.harold.fulcrum.api.message.scoreboard.registry.ScoreboardRegistry;
import sh.harold.fulcrum.api.message.scoreboard.render.PacketRenderer;
import sh.harold.fulcrum.api.message.scoreboard.render.RenderingPipeline;
import sh.harold.fulcrum.api.message.scoreboard.render.TitleManager;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import org.bukkit.Bukkit;

public class ScoreboardFeature implements PluginFeature {

    private YamlScoreboardService scoreboardService;
    private DefaultPlayerScoreboardManager playerManager;
    private DefaultRenderingPipeline renderingPipeline;
    private PacketRenderer packetRenderer;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        // Create NMS adapter based on server version
        NMSAdapter nmsAdapter = createNMSAdapter();
        if (nmsAdapter == null) {
            plugin.getLogger().severe("Failed to create NMS adapter for server version: " + getServerVersion());
            return;
        }
        
        // Create packet renderer from NMS adapter
        this.packetRenderer = nmsAdapter.createPacketRenderer();
        
        // Create core implementations
        ScoreboardRegistry registry = new DefaultScoreboardRegistry();
        TitleManager titleManager = new DefaultTitleManager();
        this.playerManager = new DefaultPlayerScoreboardManager();
        this.renderingPipeline = new DefaultRenderingPipeline(titleManager, playerManager);
        
        // Create main service
        this.scoreboardService = new YamlScoreboardService(
            plugin.getDataFolder().toPath().resolve("scoreboards"),
            registry,
            playerManager,
            renderingPipeline,
            packetRenderer
        );
        
        // Register services in dependency container
        container.register(ScoreboardService.class, scoreboardService);
        container.register(ScoreboardRegistry.class, registry);
        container.register(PlayerScoreboardManager.class, playerManager);
        container.register(RenderingPipeline.class, renderingPipeline);
        container.register(TitleManager.class, titleManager);
        container.register(PacketRenderer.class, packetRenderer);
        
        // Initialize the service
        scoreboardService.initialize();
        
        // Register debug commands
        ScoreboardDebugCommand debugCommand = new ScoreboardDebugCommand(
            scoreboardService,
            registry,
            playerManager,
            renderingPipeline
        );
        CommandRegistrar.register(debugCommand.build());
        
        plugin.getLogger().info("Scoreboard feature initialized with NMS version: " + nmsAdapter.getVersionInfo());
    }

    @Override
    public void shutdown() {
        if (scoreboardService != null) {
            scoreboardService.shutdown();
        }
        
        if (playerManager != null) {
            playerManager.clearAllPlayerData();
        }
        
        if (packetRenderer != null) {
            // Clear all active scoreboard displays
            for (int i = 0; i < packetRenderer.getActiveDisplayCount(); i++) {
                // This would need to be implemented in the actual packet renderer
                // packetRenderer.clearAllDisplays();
            }
        }
    }

    @Override
    public int getPriority() {
        return 5; // Load after core features but before game features
    }

    private NMSAdapter createNMSAdapter() {
        String version = getServerVersion();
        
        try {
            switch (version) {
                case "v1_21_R1":
                    return (NMSAdapter) Class.forName("sh.harold.fulcrum.api.message.scoreboard.nms.v1_21_R1.NMSAdapterV1_21_R1")
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

    private String getServerVersion() {
        String packageName = Bukkit.getServer().getClass().getPackage().getName();
        return packageName.substring(packageName.lastIndexOf('.') + 1);
    }
}