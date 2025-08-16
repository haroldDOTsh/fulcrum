package sh.harold.fulcrum.registry.messagebus;

import io.lettuce.core.pubsub.RedisPubSubListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis Pub/Sub listener for the Registry MessageBus
 */
public class RegistryRedisListener implements RedisPubSubListener<String, String> {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryRedisListener.class);
    private final RegistryMessageBus messageBus;
    
    public RegistryRedisListener(RegistryMessageBus messageBus) {
        this.messageBus = messageBus;
    }
    
    @Override
    public void message(String channel, String message) {
        LOGGER.debug("Received message on channel {}", channel);
        messageBus.handleMessage(channel, message);
    }
    
    @Override
    public void message(String pattern, String channel, String message) {
        LOGGER.debug("Received message on pattern {}, channel {}", pattern, channel);
        messageBus.handleMessage(channel, message);
    }
    
    @Override
    public void subscribed(String channel, long count) {
        LOGGER.debug("Subscribed to channel: {} (total: {})", channel, count);
    }
    
    @Override
    public void psubscribed(String pattern, long count) {
        LOGGER.debug("Subscribed to pattern: {} (total: {})", pattern, count);
    }
    
    @Override
    public void unsubscribed(String channel, long count) {
        LOGGER.debug("Unsubscribed from channel: {} (total: {})", channel, count);
    }
    
    @Override
    public void punsubscribed(String pattern, long count) {
        LOGGER.debug("Unsubscribed from pattern: {} (total: {})", pattern, count);
    }
}