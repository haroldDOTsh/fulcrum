package sh.harold.fulcrum.fundamentals.props;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.data.schema.SchemaDefinition;
import sh.harold.fulcrum.api.data.schema.SchemaRegistry;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Feature responsible for loading prop schematics from storage and exposing the prop manager/service.
 */
public class PropFeature implements PluginFeature {

    private JavaPlugin plugin;
    private Logger logger;
    private PropService propService;
    private PropManager propManager;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();

        PostgresConnectionAdapter adapter = container.getOptional(PostgresConnectionAdapter.class)
                .orElseGet(() -> ServiceLocatorImpl.getInstance() != null
                        ? ServiceLocatorImpl.getInstance().findService(PostgresConnectionAdapter.class).orElse(null)
                        : null);
        if (adapter == null) {
            logger.severe("PropFeature could not obtain a PostgreSQL connection adapter.");
            return;
        }

        if (!ensurePropSchema(adapter)) {
            logger.severe("Prop schema unavailable; prop services disabled.");
            return;
        }

        this.propService = new PropService(plugin, adapter);
        try {
            propService.initialize().join();
        } catch (Exception exception) {
            logger.log(Level.SEVERE, "Failed to initialize PropService", exception);
            return;
        }

        this.propManager = new PropManager(plugin, propService);

        container.register(PropService.class, propService);
        container.register(PropManager.class, propManager);
        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().registerService(PropService.class, propService);
            ServiceLocatorImpl.getInstance().registerService(PropManager.class, propManager);
        }

        logger.info("PropFeature initialized with " + propService.getCachedPropCount() + " cached props.");
    }

    @Override
    public void shutdown() {
        if (propService != null) {
            propService.shutdown();
        }
        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().unregisterService(PropService.class);
            ServiceLocatorImpl.getInstance().unregisterService(PropManager.class);
        }
    }

    private boolean ensurePropSchema(PostgresConnectionAdapter adapter) {
        try {
            SchemaRegistry.ensureSchema(
                    adapter,
                    SchemaDefinition.fromResource(
                            "world-props-001",
                            "Create world props storage table",
                            plugin.getClass().getClassLoader(),
                            "migrations/world_props.sql"
                    )
            );
            return true;
        } catch (Exception exception) {
            logger.log(Level.SEVERE, "Failed to ensure prop schema", exception);
            return false;
        }
    }

    @Override
    public int getPriority() {
        return 205;
    }
}
