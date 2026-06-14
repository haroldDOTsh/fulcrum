package sh.harold.fulcrum.fundamentals.world;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.rank.RankUtils;
import sh.harold.fulcrum.api.data.impl.messagebus.MessageBusWorldMapStoreClient;
import sh.harold.fulcrum.api.data.world.WorldMapStore;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.world.poi.POIRegistry;
import sh.harold.fulcrum.fundamentals.world.commands.WorldCommand;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.runtime.threading.PaperRuntime;

import java.io.File;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

/**
 * Feature for managing world loading from storage.
 * Initializes the world service, warms the cache, and exposes the world manager.
 */
public class WorldFeature implements PluginFeature {

    private JavaPlugin plugin;
    private WorldMapStore worldMapStore;
    private WorldService worldService;
    private WorldManager worldManager;
    private POIRegistry poiRegistry;
    private WorldCommand worldCommand;
    private Logger logger;
    private PaperRuntime runtime;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.runtime = container.get(PaperRuntime.class);

        try {
            this.worldMapStore = createWorldMapStore(plugin, container);
        } catch (Exception exception) {
            logger.severe("WorldFeature could not create the world map authority client: " + exception.getMessage());
            registerFallbackCommand("World map authority unavailable. Check message bus and registry-service.");
            return;
        }

        this.worldService = new WorldService(plugin, worldMapStore, runtime.asyncExecutor());
        try {
            worldService.initialize().whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    logger.severe("World cache failed to initialize: " + throwable.getMessage());
                    return;
                }
                logger.info("World cache initialized with " + worldService.getAllWorlds().size() + " world definitions.");
            });
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            logger.severe("Failed to initialize WorldService: " + cause.getMessage());
            cause.printStackTrace();
            registerFallbackCommand("World cache failed to initialize. See console for details.");
            return;
        } catch (Exception ex) {
            logger.severe("Unexpected error initializing WorldService: " + ex.getMessage());
            ex.printStackTrace();
            registerFallbackCommand("World service crashed during startup.");
            return;
        }

        this.poiRegistry = new POIRegistry(logger);
        this.worldManager = new WorldManager(plugin, worldService, poiRegistry, runtime);

        container.register(WorldService.class, worldService);
        container.register(WorldManager.class, worldManager);
        container.register(POIRegistry.class, poiRegistry);

        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().registerService(WorldService.class, worldService);
            ServiceLocatorImpl.getInstance().registerService(WorldManager.class, worldManager);
            ServiceLocatorImpl.getInstance().registerService(POIRegistry.class, poiRegistry);
        }

        this.worldCommand = new WorldCommand(plugin, worldService, worldManager);
        LiteralCommandNode<CommandSourceStack> worldRoot = worldCommand.build();
        CommandRegistrar.register(worldRoot);
        CommandRegistrar.registerAlias(worldRoot, "worlds");
        plugin.getLogger().info("[FUNDAMENTALS] Registered World Commands");

        logger.info("WorldFeature initialized with " + worldService.getAllWorlds().size() + " cached world definitions.");
    }

    @Override
    public void shutdown() {
        if (worldService != null) {
            worldService.shutdown();
        }

        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().unregisterService(WorldService.class);
            ServiceLocatorImpl.getInstance().unregisterService(WorldManager.class);
            ServiceLocatorImpl.getInstance().unregisterService(POIRegistry.class);
        }
    }

    private WorldMapStore createWorldMapStore(JavaPlugin plugin, DependencyContainer container) {
        MessageBus messageBus = container.get(MessageBus.class);
        WorldMapClientConfig config = loadWorldMapClientConfig(plugin);
        logger.info("Using remote world map store via message bus server " + config.authorityServerId());
        return new MessageBusWorldMapStoreClient(
            messageBus,
            config.authorityServerId(),
            Duration.ofMillis(config.timeoutMs())
        );
    }

    private void registerFallbackCommand(String message) {
        LiteralCommandNode<CommandSourceStack> node = Commands.literal("world")
            .requires(source -> {
            var sender = source.getSender();
            return RankUtils.isAdmin(sender) || sender.hasPermission("fulcrum.world.manage") || sender.hasPermission("fulcrum.world.view") || sender.isOp();
        })
            .executes(ctx -> {
                ctx.getSource().getSender().sendMessage(Component.text(message, NamedTextColor.RED));
                return Command.SINGLE_SUCCESS;
            })
            .build();
        CommandRegistrar.register(node);
        CommandRegistrar.registerAlias(node, "worlds");
        plugin.getLogger().warning("[FUNDAMENTALS] World commands limited - " + message);
    }

    private WorldMapClientConfig loadWorldMapClientConfig(JavaPlugin plugin) {
        File configFile = new File(plugin.getDataFolder(), "database-config.yml");
        if (!configFile.exists()) {
            plugin.saveResource("database-config.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
        return new WorldMapClientConfig(
            yaml.getString("authority.server-id", "registry-service"),
            yaml.getLong("authority.request-timeout-ms", 5000L)
        );
    }

    public CompletableFuture<Void> refreshWorldCache() {
        if (worldService != null) {
            return worldService.refreshCache();
        }
        return CompletableFuture.completedFuture(null);
    }

    public WorldService getWorldService() {
        return worldService;
    }

    public WorldManager getWorldManager() {
        return worldManager;
    }

    @Override
    public int getPriority() {
        return 200;
    }

    @Override
    public Class<?>[] getDependencies() {
        return new Class<?>[] { PaperRuntime.class, MessageBus.class };
    }

    private record WorldMapClientConfig(String authorityServerId, long timeoutMs) {}

}



















