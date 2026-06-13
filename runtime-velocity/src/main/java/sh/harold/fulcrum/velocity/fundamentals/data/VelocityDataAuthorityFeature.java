package sh.harold.fulcrum.velocity.fundamentals.data;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.PostgresDataAuthority;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

import java.nio.file.Path;

public class VelocityDataAuthorityFeature implements VelocityFeature {
    private Logger logger;
    private VelocityConnectionAdapter connectionAdapter;
    private PostgresDataAuthority dataAuthority;
    private ServiceLocator serviceLocator;

    @Override
    public String getName() {
        return "DataAuthority";
    }

    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) throws Exception {
        this.logger = logger;
        this.serviceLocator = serviceLocator;

        serviceLocator.getRequiredService(ProxyServer.class);
        Path dataDirectory = serviceLocator.getRequiredService(Path.class);

        try {
            logger.info("Initializing Data Authority feature for Velocity...");

            connectionAdapter = new VelocityConnectionAdapter(dataDirectory, logger);
            PostgresConnectionAdapter postgresAdapter = connectionAdapter.createAdapter();
            dataAuthority = new PostgresDataAuthority(postgresAdapter);
            dataAuthority.validateSchema();

            serviceLocator.register(PostgresConnectionAdapter.class, postgresAdapter);
            serviceLocator.register(DataAuthority.CommandPort.class, dataAuthority);
            serviceLocator.register(DataAuthority.PlayerProfileReader.class, dataAuthority);
            serviceLocator.register(DataAuthority.PlayerRankReader.class, dataAuthority);

            logger.info("Data Authority initialized successfully for Velocity");
        } catch (Exception e) {
            logger.error("Failed to initialize Data Authority", e);
            throw new RuntimeException("Failed to initialize Data Authority", e);
        }
    }

    @Override
    public void shutdown() {
        logger.info("Shutting down Data Authority feature...");

        if (connectionAdapter != null) {
            connectionAdapter.shutdown();
        }

        if (serviceLocator != null) {
            serviceLocator.unregister(PostgresConnectionAdapter.class);
            serviceLocator.unregister(DataAuthority.CommandPort.class);
            serviceLocator.unregister(DataAuthority.PlayerProfileReader.class);
            serviceLocator.unregister(DataAuthority.PlayerRankReader.class);
        }

        dataAuthority = null;
        logger.info("Data Authority shut down successfully");
    }

    @Override
    public int getPriority() {
        return 20;
    }

    public PostgresDataAuthority getDataAuthority() {
        return dataAuthority;
    }
}
