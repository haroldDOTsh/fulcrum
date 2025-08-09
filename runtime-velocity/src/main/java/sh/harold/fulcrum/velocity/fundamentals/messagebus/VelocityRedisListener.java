package sh.harold.fulcrum.velocity.fundamentals.messagebus;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.pubsub.RedisPubSubListener;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;

import java.util.logging.Logger;

public class VelocityRedisListener implements RedisPubSubListener<String, String> {
    
    private static final Logger logger = Logger.getLogger(VelocityRedisListener.class.getName());
    private final VelocityRedisMessageBus messageBus;
    private final ObjectMapper objectMapper;
    
    public VelocityRedisListener(VelocityRedisMessageBus messageBus) {
        this.messageBus = messageBus;
        this.objectMapper = new ObjectMapper();
    }
    
    @Override
    public void message(String channel, String message) {
        try {
            MessageEnvelope envelope = objectMapper.readValue(message, MessageEnvelope.class);
            // The message bus will handle the message processing
            logger.fine("Received message on channel: " + channel);
        } catch (Exception e) {
            logger.severe("Failed to process Redis message: " + e.getMessage());
        }
    }
    
    @Override
    public void message(String pattern, String channel, String message) {
        message(channel, message);
    }
    
    @Override
    public void subscribed(String channel, long count) {
        logger.info("Subscribed to Redis channel: " + channel);
    }
    
    @Override
    public void psubscribed(String pattern, long count) {
        logger.info("Pattern subscribed to Redis: " + pattern);
    }
    
    @Override
    public void unsubscribed(String channel, long count) {
        logger.info("Unsubscribed from Redis channel: " + channel);
    }
    
    @Override
    public void punsubscribed(String pattern, long count) {
        logger.info("Pattern unsubscribed from Redis: " + pattern);
    }
}