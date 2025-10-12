package sh.harold.fulcrum.fundamentals.messagebus;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusConnectionConfig;
import sh.harold.fulcrum.api.messagebus.impl.MessageBusFactory;
import sh.harold.fulcrum.fundamentals.messagebus.commands.MessageDebugCommand;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;

import java.io.File;
import java.util.logging.Logger;

/**
 * Message Bus Feature providing inter-server communication capabilities.
 * Uses clean adapter pattern with consolidated message-bus-api.
 */
public class MessageBusFeature implements PluginFeature {
    private static final Logger LOGGER = Logger.getLogger(MessageBusFeature.class.getName());

    private MessageBus messageBus;
    private PaperMessageBusAdapter adapter;

    @Override
    public int getPriority() {
        // Infrastructure layer - loads before services
        return 10;
    }

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        LOGGER.info("Initializing Message Bus Feature");

        // Load configuration
        MessageBusConnectionConfig config = loadConfiguration(plugin);

        // Create Paper-specific adapter
        adapter = new PaperMessageBusAdapter(plugin, config);

        // Create message bus using factory
        try {
            messageBus = MessageBusFactory.create(adapter);
            LOGGER.info("MessageBus initialized with type: " + config.getType());

        } catch (Exception e) {
            LOGGER.severe("Failed to create message bus: " + e.getMessage());
            throw new RuntimeException("Failed to initialize message bus", e);
        }

        // Register services
        container.register(MessageBus.class, messageBus);

        // Register debug command
        MessageDebugCommand debugCommand = new MessageDebugCommand(container);
        CommandRegistrar.register(debugCommand.build());
        LOGGER.info("Message debug command registered (/fulcrum messagedebug)");

        LOGGER.info("Message Bus Feature initialized successfully");
        LOGGER.info("Type: " + config.getType());
        if (config.getType() == MessageBusConnectionConfig.MessageBusType.REDIS) {
            LOGGER.info("Redis: " + config.getHost() + ":" + config.getPort());
        }
    }

    @Override
    public void shutdown() {
        LOGGER.info("Shutting down Message Bus Feature");

        if (adapter != null) {
            adapter.shutdown();
        }

        if (messageBus != null) {
            try {
                // Message bus handles its own shutdown
                // The adapter will be notified via onMessageBusShutdown callback
            } catch (Exception e) {
                LOGGER.warning("Error during message bus shutdown: " + e.getMessage());
            }
        }

        messageBus = null;
        adapter = null;

        LOGGER.info("Message Bus Feature shutdown complete");
    }

    private MessageBusConnectionConfig loadConfiguration(JavaPlugin plugin) {
        // Check if development mode is enabled
        boolean devMode = plugin.getConfig().getBoolean("development-mode", false);

        if (devMode) {
            LOGGER.warning("=== DEVELOPMENT MODE ENABLED ===");
            LOGGER.warning("Using in-memory message bus (no Redis required)");
            LOGGER.warning("Perfect for single-server testing");
            return MessageBusConnectionConfig.inMemory();
        }

        // Production mode - load Redis configuration
        File configFile = new File(plugin.getDataFolder(), "database-config.yml");

        if (!configFile.exists()) {
            // Save default config if it doesn't exist
            plugin.saveResource("database-config.yml", false);
            configFile = new File(plugin.getDataFolder(), "database-config.yml");
        }

        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
            ConfigurationSection redisSection = yaml.getConfigurationSection("redis");

            if (redisSection == null) {
                LOGGER.warning("No Redis configuration found in database-config.yml");
                LOGGER.warning("Using in-memory message bus as fallback");
                return MessageBusConnectionConfig.inMemory();
            }

            // Check if Redis is enabled
            if (!redisSection.getBoolean("enabled", true)) {
                LOGGER.info("Redis is disabled in configuration");
                LOGGER.info("Using in-memory message bus");
                return MessageBusConnectionConfig.inMemory();
            }

            // Build Redis configuration
            MessageBusConnectionConfig.Builder builder = MessageBusConnectionConfig.builder()
                    .type(MessageBusConnectionConfig.MessageBusType.REDIS)
                    .host(redisSection.getString("host", "localhost"))
                    .port(redisSection.getInt("port", 6379))
                    .database(redisSection.getInt("database", 0));

            // Set password if provided
            String password = redisSection.getString("password", "");
            if (!password.isEmpty()) {
                builder.password(password);
            }

            // Load connection pool settings if present
            ConfigurationSection poolSection = redisSection.getConfigurationSection("connection-pool");
            if (poolSection != null) {
                // Note: The MessageBusConnectionConfig doesn't have pool settings,
                // but we can log them for informational purposes
                int minIdle = poolSection.getInt("min-idle", 1);
                int maxIdle = poolSection.getInt("max-idle", 8);
                int maxTotal = poolSection.getInt("max-total", 8);
                LOGGER.fine("Connection pool settings - min-idle: " + minIdle +
                        ", max-idle: " + maxIdle + ", max-total: " + maxTotal);
            }

            MessageBusConnectionConfig config = builder.build();
            LOGGER.info("Redis configuration loaded successfully");
            return config;

        } catch (Exception e) {
            LOGGER.severe("Failed to load configuration: " + e.getMessage());
            LOGGER.warning("Falling back to in-memory message bus");
            return MessageBusConnectionConfig.inMemory();
        }
    }
}