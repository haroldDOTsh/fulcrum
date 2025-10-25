package sh.harold.fulcrum.velocity.fundamentals.network;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.messagebus.*;
import sh.harold.fulcrum.api.messagebus.messages.network.NetworkConfigUpdatedMessage;
import sh.harold.fulcrum.api.network.NetworkConfigService;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.config.ConfigLoader;
import sh.harold.fulcrum.velocity.config.RedisConfig;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;
import sh.harold.fulcrum.velocity.session.LettuceSessionRedisClient;

import java.time.Duration;
import java.util.Optional;

public final class VelocityNetworkConfigFeature implements VelocityFeature {
    private FulcrumVelocityPlugin plugin;
    private ProxyServer proxyServer;
    private Logger logger;
    private MessageBus messageBus;

    private LettuceSessionRedisClient redisClient;
    private VelocityNetworkConfigService service;
    private MessageHandler updateHandler;
    private ScheduledTask refreshTask;

    @Override
    public String getName() {
        return "NetworkConfig";
    }

    @Override
    public int getPriority() {
        // After message bus but before routing features
        return 18;
    }

    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) {
        this.logger = logger;
        this.plugin = serviceLocator.getRequiredService(FulcrumVelocityPlugin.class);
        this.proxyServer = serviceLocator.getRequiredService(ProxyServer.class);
        ConfigLoader configLoader = serviceLocator.getRequiredService(ConfigLoader.class);
        this.messageBus = serviceLocator.getService(MessageBus.class).orElse(null);

        RedisConfig redisConfig = configLoader.getConfig(RedisConfig.class);
        if (redisConfig == null) {
            throw new IllegalStateException("Redis configuration missing; cannot initialise network configuration feature");
        }

        this.redisClient = new LettuceSessionRedisClient(redisConfig, logger);
        this.service = new VelocityNetworkConfigService(plugin, logger, messageBus, redisClient);

        serviceLocator.register(NetworkConfigService.class, service);
        service.refreshActiveProfile();

        if (messageBus != null) {
            updateHandler = this::handleUpdate;
            messageBus.subscribe(ChannelConstants.REGISTRY_NETWORK_CONFIG_UPDATED, updateHandler);
        } else {
            logger.warn("MessageBus unavailable; network configuration updates will rely on scheduled refresh");
        }

        refreshTask = proxyServer.getScheduler()
                .buildTask(plugin, service::refreshActiveProfile)
                .delay(Duration.ofSeconds(5))
                .repeat(Duration.ofSeconds(60))
                .schedule();
    }

    @Override
    public void shutdown() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        if (messageBus != null && updateHandler != null) {
            messageBus.unsubscribe(ChannelConstants.REGISTRY_NETWORK_CONFIG_UPDATED, updateHandler);
        }
        Optional.ofNullable(redisClient).ifPresent(client -> {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        });
    }

    private void handleUpdate(MessageEnvelope envelope) {
        try {
            NetworkConfigUpdatedMessage message = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), NetworkConfigUpdatedMessage.class);
            if (message == null) {
                return;
            }
            proxyServer.getScheduler()
                    .buildTask(plugin, service::refreshActiveProfile)
                    .schedule();
        } catch (Exception ex) {
            logger.warn("Failed to process network configuration update event", ex);
        }
    }
}
