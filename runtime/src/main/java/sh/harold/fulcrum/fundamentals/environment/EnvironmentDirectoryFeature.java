package sh.harold.fulcrum.fundamentals.environment;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import sh.harold.fulcrum.api.environment.directory.EnvironmentDirectoryService;
import sh.harold.fulcrum.api.messagebus.*;
import sh.harold.fulcrum.api.messagebus.messages.environment.EnvironmentDirectoryUpdatedMessage;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.runtime.redis.LettuceRedisOperations;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class EnvironmentDirectoryFeature implements PluginFeature {
    private JavaPlugin plugin;
    private Logger logger;
    private RedisEnvironmentDirectoryService service;
    private MessageBus messageBus;
    private MessageHandler updateHandler;
    private BukkitTask refreshTask;
    private DependencyContainer containerRef;

    @Override
    public int getPriority() {
        // After message bus but before network config to ensure module data is ready.
        return 40;
    }

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.containerRef = container;

        this.messageBus = container.getOptional(MessageBus.class).orElse(null);
        LettuceRedisOperations redisOperations = container.getOptional(LettuceRedisOperations.class).orElse(null);

        this.service = new RedisEnvironmentDirectoryService(plugin, messageBus, redisOperations, logger);
        container.register(EnvironmentDirectoryService.class, service);
        Optional.ofNullable(ServiceLocatorImpl.getInstance())
                .ifPresent(locator -> locator.registerService(EnvironmentDirectoryService.class, service));

        service.refresh();

        if (messageBus != null) {
            updateHandler = this::handleUpdate;
            messageBus.subscribe(ChannelConstants.REGISTRY_ENVIRONMENT_DIRECTORY_UPDATED, updateHandler);
        } else {
            logger.warning("MessageBus unavailable; environment directory updates will rely on scheduled refresh");
        }

        refreshTask = plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, service::refresh, 20L, 1200L);
    }

    @Override
    public void shutdown() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        if (messageBus != null && updateHandler != null) {
            messageBus.unsubscribe(ChannelConstants.REGISTRY_ENVIRONMENT_DIRECTORY_UPDATED, updateHandler);
        }
        Optional.ofNullable(ServiceLocatorImpl.getInstance())
                .ifPresent(locator -> locator.unregisterService(EnvironmentDirectoryService.class));
        if (containerRef != null) {
            containerRef.unregister(EnvironmentDirectoryService.class);
            containerRef = null;
        }
        service = null;
    }

    private void handleUpdate(MessageEnvelope envelope) {
        try {
            EnvironmentDirectoryUpdatedMessage message = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), EnvironmentDirectoryUpdatedMessage.class);
            if (message == null) {
                return;
            }
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, service::refresh);
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to process environment directory update event", ex);
        }
    }
}
