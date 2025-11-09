package sh.harold.fulcrum.fundamentals.world;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.data.schema.SchemaDefinition;
import sh.harold.fulcrum.api.data.schema.SchemaRegistry;
import sh.harold.fulcrum.api.rank.RankUtils;
import sh.harold.fulcrum.api.world.poi.POIRegistry;
import sh.harold.fulcrum.fundamentals.world.commands.WorldCommand;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.npc.poi.PoiActivationBus;
import sh.harold.fulcrum.npc.poi.SimplePoiActivationBus;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Feature for managing world loading from storage.
 * Initializes the world service, warms the cache, and exposes the world manager.
 */
public class WorldFeature implements PluginFeature {

    private JavaPlugin plugin;
    private PostgresConnectionAdapter connectionAdapter;
    private WorldService worldService;
    private WorldManager worldManager;
    private POIRegistry poiRegistry;
    private WorldCommand worldCommand;
    private Logger logger;
    private PoiActivationBus poiActivationBus;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();

        resolveConnectionAdapter(container);
        if (connectionAdapter == null) {
            logger.severe("WorldFeature could not obtain a PostgreSQL connection adapter.");
            registerFallbackCommand("World database unavailable. Check database-config.yml");
            return;
        }

        this.worldService = new WorldService(plugin, connectionAdapter);
        try {
            worldService.initialize().join();
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
        this.poiActivationBus = new SimplePoiActivationBus();
        this.worldManager = new WorldManager(plugin, worldService, poiRegistry, poiActivationBus);

        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().registerService(WorldService.class, worldService);
            ServiceLocatorImpl.getInstance().registerService(WorldManager.class, worldManager);
            ServiceLocatorImpl.getInstance().registerService(POIRegistry.class, poiRegistry);
            ServiceLocatorImpl.getInstance().registerService(PoiActivationBus.class, poiActivationBus);
        }

        container.register(POIRegistry.class, poiRegistry);
        container.register(PoiActivationBus.class, poiActivationBus);

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
            ServiceLocatorImpl.getInstance().unregisterService(PoiActivationBus.class);
        }

    }

    private void resolveConnectionAdapter(DependencyContainer container) {
        connectionAdapter = null;
        if (container != null) {
            connectionAdapter = container.getOptional(PostgresConnectionAdapter.class).orElse(null);
        }
        if (connectionAdapter == null && ServiceLocatorImpl.getInstance() != null) {
            connectionAdapter = ServiceLocatorImpl.getInstance()
                    .findService(PostgresConnectionAdapter.class)
                    .orElse(null);
        }

        if (connectionAdapter != null && !ensureWorldSchema(connectionAdapter)) {
            connectionAdapter = null;
        }
    }

    private boolean ensureWorldSchema(PostgresConnectionAdapter adapter) {
        try {
            SchemaRegistry.ensureSchema(
                    adapter,
                    SchemaDefinition.fromResource(
                            "world-maps-001",
                            "Create world map storage table",
                            plugin.getClass().getClassLoader(),
                            "migrations/world_maps.sql"
                    )
            );
            return true;
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "Failed to ensure world map schema", ex);
            return false;
        }
    }

    private void registerFallbackCommand(String message) {
        LiteralCommandNode<CommandSourceStack> node = Commands.literal("world")
                .requires(source -> RankUtils.isAdmin(source.getSender()))
                .executes(ctx -> {
                    ctx.getSource().getSender().sendMessage(Component.text(message, NamedTextColor.RED));
                    return Command.SINGLE_SUCCESS;
                })
                .build();
        CommandRegistrar.register(node);
        CommandRegistrar.registerAlias(node, "worlds");
        plugin.getLogger().warning("[FUNDAMENTALS] World commands limited - " + message);
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

}








