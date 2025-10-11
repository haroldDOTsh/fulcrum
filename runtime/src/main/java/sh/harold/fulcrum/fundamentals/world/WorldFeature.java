package sh.harold.fulcrum.fundamentals.world;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.rank.RankUtils;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.data.schema.SchemaDefinition;
import sh.harold.fulcrum.api.data.schema.SchemaRegistry;
import sh.harold.fulcrum.api.data.storage.ConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.StorageType;
import sh.harold.fulcrum.api.world.poi.POIRegistry;
import sh.harold.fulcrum.fundamentals.world.commands.WorldCommand;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
    private boolean ownsConnectionAdapter;

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
            closeOwnedAdapter();
            return;
        } catch (Exception ex) {
            logger.severe("Unexpected error initializing WorldService: " + ex.getMessage());
            ex.printStackTrace();
            registerFallbackCommand("World service crashed during startup.");
            closeOwnedAdapter();
            return;
        }

        this.poiRegistry = new POIRegistry(logger);
        this.worldManager = new WorldManager(plugin, worldService, poiRegistry);

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

        closeOwnedAdapter();
    }

    private void resolveConnectionAdapter(DependencyContainer container) {
        ownsConnectionAdapter = false;
        ConnectionAdapter adapter = null;
        if (container != null) {
            adapter = container.getOptional(ConnectionAdapter.class).orElse(null);
        }
        if (adapter == null && ServiceLocatorImpl.getInstance() != null) {
            adapter = ServiceLocatorImpl.getInstance().findService(ConnectionAdapter.class).orElse(null);
        }

        if (adapter instanceof PostgresConnectionAdapter postgresAdapter) {
            connectionAdapter = postgresAdapter;
            return;
        }

        if (adapter != null && adapter.getStorageType() == StorageType.POSTGRES) {
            logger.warning("ConnectionAdapter reported POSTGRES but is not PostgresConnectionAdapter. Creating dedicated adapter.");
        }

        PostgresConnectionAdapter standalone = createStandalonePostgresAdapter();
        if (standalone != null) {
            connectionAdapter = standalone;
            ownsConnectionAdapter = true;
        } else {
            connectionAdapter = null;
        }
    }

    private PostgresConnectionAdapter createStandalonePostgresAdapter() {
        try {
            File configFile = new File(plugin.getDataFolder(), "database-config.yml");
            if (!configFile.exists()) {
                plugin.saveResource("database-config.yml", false);
            }
            FileConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
            if (!yaml.contains("postgres")) {
                logger.warning("database-config.yml missing 'postgres' section.");
                return null;
            }

            String jdbcUrl = yaml.getString("postgres.jdbc-url", "jdbc:postgresql://localhost:5432/fulcrum");
            String database = yaml.getString("postgres.database", "fulcrum");
            String username = yaml.getString("postgres.username", "fulcrum_user");
            String password = yaml.getString("postgres.password", "");

            PostgresConnectionAdapter adapter = new PostgresConnectionAdapter(jdbcUrl, username, password, database);
            try (var connection = adapter.getConnection()) {
                if (!connection.isValid(5)) {
                    logger.severe("Standalone PostgreSQL connection reported invalid for database '" + database + "'.");
                    adapter.close();
                    return null;
                }
            }
            SchemaRegistry.ensureSchema(
                adapter,
                SchemaDefinition.fromResource(
                    "world-maps-001",
                    "Create world map storage table",
                    plugin.getClass().getClassLoader(),
                    "migrations/world_maps.sql"
                )
            );
            logger.info("Standalone PostgreSQL adapter initialized for world service (database: " + database + ").");
            return adapter;
        } catch (Exception ex) {
            logger.warning("Failed to create standalone PostgreSQL adapter: " + ex.getMessage());
            return null;
        }
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

    private void closeOwnedAdapter() {
        if (ownsConnectionAdapter && connectionAdapter != null) {
            try {
                connectionAdapter.close();
            } catch (Exception ex) {
                logger.warning("Failed to close world Postgres adapter: " + ex.getMessage());
            }
        }
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

















