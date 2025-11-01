package sh.harold.fulcrum.velocity.fundamentals.environment;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.environment.directory.EnvironmentDirectoryService;
import sh.harold.fulcrum.api.messagebus.*;
import sh.harold.fulcrum.api.messagebus.messages.environment.EnvironmentDirectoryUpdatedMessage;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.config.ConfigLoader;
import sh.harold.fulcrum.velocity.config.RedisConfig;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;
import sh.harold.fulcrum.velocity.session.LettuceSessionRedisClient;

import java.time.Duration;
import java.util.Optional;

public final class VelocityEnvironmentDirectoryFeature implements VelocityFeature {
    private FulcrumVelocityPlugin plugin;
    private ProxyServer proxyServer;
    private Logger logger;
    private MessageBus messageBus;

    private LettuceSessionRedisClient redisClient;
    private VelocityEnvironmentDirectoryService service;
    private MessageHandler updateHandler;
    private ScheduledTask refreshTask;
    private ServiceLocator serviceLocatorRef;

    @Override
    public String getName() {
        return "EnvironmentDirectory";
    }

    @Override
    public int getPriority() {
        // After message bus but before network configuration.
        return 17;
    }

    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) {
        this.logger = logger;
        this.plugin = serviceLocator.getRequiredService(FulcrumVelocityPlugin.class);
        this.proxyServer = serviceLocator.getRequiredService(ProxyServer.class);
        this.serviceLocatorRef = serviceLocator;
        ConfigLoader configLoader = serviceLocator.getRequiredService(ConfigLoader.class);
        this.messageBus = serviceLocator.getService(MessageBus.class).orElse(null);

        RedisConfig redisConfig = configLoader.getConfig(RedisConfig.class);
        if (redisConfig == null) {
            throw new IllegalStateException("Redis configuration missing; cannot initialise environment directory feature");
        }

        this.redisClient = new LettuceSessionRedisClient(redisConfig, logger);
        this.service = new VelocityEnvironmentDirectoryService(plugin, logger, messageBus, redisClient);

        serviceLocator.register(EnvironmentDirectoryService.class, service);
        service.refresh();

        if (messageBus != null) {
            updateHandler = this::handleUpdate;
            messageBus.subscribe(ChannelConstants.REGISTRY_ENVIRONMENT_DIRECTORY_UPDATED, updateHandler);
        } else {
            logger.warn("MessageBus unavailable; environment directory updates will rely on scheduled refresh");
        }

        refreshTask = proxyServer.getScheduler()
                .buildTask(plugin, service::refresh)
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
            messageBus.unsubscribe(ChannelConstants.REGISTRY_ENVIRONMENT_DIRECTORY_UPDATED, updateHandler);
        }
        Optional.ofNullable(redisClient).ifPresent(client -> {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        });
        if (serviceLocatorRef != null) {
            serviceLocatorRef.unregister(EnvironmentDirectoryService.class);
            serviceLocatorRef = null;
        }
    }

    private void handleUpdate(MessageEnvelope envelope) {
        try {
            EnvironmentDirectoryUpdatedMessage message = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), EnvironmentDirectoryUpdatedMessage.class);
            if (message == null) {
                return;
            }
            proxyServer.getScheduler()
                    .buildTask(plugin, service::refresh)
                    .schedule();
        } catch (Exception ex) {
            logger.warn("Failed to process environment directory update event", ex);
        }
    }
}
