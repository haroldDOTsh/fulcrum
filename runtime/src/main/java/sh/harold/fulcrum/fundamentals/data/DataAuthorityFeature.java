package sh.harold.fulcrum.fundamentals.data;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.PostgresDataAuthority;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.runtime.threading.PaperRuntime;

import java.util.logging.Logger;

public class DataAuthorityFeature implements PluginFeature {
    private Logger logger;
    private FulcrumConnectionAdapter connectionAdapter;
    private PostgresDataAuthority dataAuthority;
    private PaperRuntime runtime;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.logger = plugin.getLogger();

        try {
            logger.info("Initializing Data Authority feature...");

            runtime = container.get(PaperRuntime.class);
            connectionAdapter = new FulcrumConnectionAdapter(plugin);
            PostgresConnectionAdapter postgresAdapter = connectionAdapter.createAdapter();
            dataAuthority = new PostgresDataAuthority(postgresAdapter, runtime.asyncExecutor());
            dataAuthority.validateSchema();

            container.register(PostgresConnectionAdapter.class, postgresAdapter);
            container.register(DataAuthority.CommandPort.class, dataAuthority);
            container.register(DataAuthority.PlayerProfileReader.class, dataAuthority);
            container.register(DataAuthority.PlayerRankReader.class, dataAuthority);

            if (ServiceLocatorImpl.getInstance() != null) {
                ServiceLocatorImpl.getInstance().registerService(PostgresConnectionAdapter.class, postgresAdapter);
                ServiceLocatorImpl.getInstance().registerService(DataAuthority.CommandPort.class, dataAuthority);
                ServiceLocatorImpl.getInstance().registerService(DataAuthority.PlayerProfileReader.class, dataAuthority);
                ServiceLocatorImpl.getInstance().registerService(DataAuthority.PlayerRankReader.class, dataAuthority);
            }

            logger.info("Data Authority initialized successfully");
        } catch (Exception e) {
            logger.severe("Failed to initialize Data Authority: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize Data Authority", e);
        }
    }

    @Override
    public void shutdown() {
        logger.info("Shutting down Data Authority feature...");

        if (connectionAdapter != null) {
            connectionAdapter.shutdown();
        }

        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().unregisterService(PostgresConnectionAdapter.class);
            ServiceLocatorImpl.getInstance().unregisterService(DataAuthority.CommandPort.class);
            ServiceLocatorImpl.getInstance().unregisterService(DataAuthority.PlayerProfileReader.class);
            ServiceLocatorImpl.getInstance().unregisterService(DataAuthority.PlayerRankReader.class);
        }

        logger.info("Data Authority shut down successfully");
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public Class<?>[] getDependencies() {
        return new Class<?>[] { PaperRuntime.class };
    }

    public PostgresDataAuthority getDataAuthority() {
        return dataAuthority;
    }
}
