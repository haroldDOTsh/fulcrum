package sh.harold.fulcrum.fundamentals.network;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import sh.harold.fulcrum.api.messagebus.*;
import sh.harold.fulcrum.api.messagebus.messages.network.NetworkConfigUpdatedMessage;
import sh.harold.fulcrum.api.network.NetworkConfigService;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.runtime.redis.LettuceRedisOperations;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class NetworkConfigFeature implements PluginFeature {
    private JavaPlugin plugin;
    private Logger logger;
    private RedisNetworkConfigService service;
    private MessageBus messageBus;
    private MessageHandler updateHandler;
    private BukkitTask refreshTask;

    @Override
    public int getPriority() {
        // Load after message bus and rank features
        return 85;
    }

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();

        this.messageBus = container.getOptional(MessageBus.class).orElse(null);
        LettuceRedisOperations redisOperations = container.getOptional(LettuceRedisOperations.class).orElse(null);

        this.service = new RedisNetworkConfigService(plugin, messageBus, redisOperations, logger);
        container.register(NetworkConfigService.class, service);
        Optional.ofNullable(ServiceLocatorImpl.getInstance())
                .ifPresent(locator -> locator.registerService(NetworkConfigService.class, service));

        service.refreshActiveProfile();

        if (messageBus != null) {
            updateHandler = this::handleUpdate;
            messageBus.subscribe(ChannelConstants.REGISTRY_NETWORK_CONFIG_UPDATED, updateHandler);
        } else {
            logger.warning("MessageBus unavailable; network configuration updates will rely on periodic refresh only");
        }

        refreshTask = plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, service::refreshActiveProfile, 20L, 1200L);
    }

    @Override
    public void shutdown() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        if (messageBus != null && updateHandler != null) {
            messageBus.unsubscribe(ChannelConstants.REGISTRY_NETWORK_CONFIG_UPDATED, updateHandler);
        }
        Optional.ofNullable(ServiceLocatorImpl.getInstance())
                .ifPresent(locator -> locator.unregisterService(NetworkConfigService.class));
    }

    private void handleUpdate(MessageEnvelope envelope) {
        try {
            NetworkConfigUpdatedMessage message = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), NetworkConfigUpdatedMessage.class);
            if (message == null) {
                return;
            }
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, service::refreshActiveProfile);
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to process network configuration update event", ex);
        }
    }
}
