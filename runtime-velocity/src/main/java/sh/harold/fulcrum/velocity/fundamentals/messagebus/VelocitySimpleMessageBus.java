package sh.harold.fulcrum.velocity.fundamentals.messagebus;

import com.velocitypowered.api.proxy.ProxyServer;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageHandler;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Simple in-memory message bus for Velocity proxy.
 * Used when Redis is not configured.
 */
public class VelocitySimpleMessageBus implements MessageBus {
    private static final Logger logger = Logger.getLogger(VelocitySimpleMessageBus.class.getName());
    
    private final String serverId;
    private final ProxyServer proxy;
    private final Map<String, List<MessageHandler>> handlers = new ConcurrentHashMap<>();
    
    public VelocitySimpleMessageBus(String serverId, ProxyServer proxy) {
        this.serverId = serverId;
        this.proxy = proxy;
    }
    
    @Override
    public void broadcast(String type, Object payload) {
        logger.info("Broadcasting message type: " + type + " (local only)");
    }
    
    @Override
    public void send(String targetServerId, String type, Object payload) {
        logger.info("Sending message type: " + type + " to server: " + targetServerId + " (local only)");
    }
    
    @Override
    public CompletableFuture<Object> request(String targetServerId, String type, Object payload, Duration timeout) {
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public void subscribe(String type, MessageHandler handler) {
        handlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(handler);
    }
    
    @Override
    public void unsubscribe(String type, MessageHandler handler) {
        List<MessageHandler> typeHandlers = handlers.get(type);
        if (typeHandlers != null) {
            typeHandlers.remove(handler);
        }
    }
    
    public void shutdown() {
        handlers.clear();
        logger.info("Simple message bus shutdown");
    }
}