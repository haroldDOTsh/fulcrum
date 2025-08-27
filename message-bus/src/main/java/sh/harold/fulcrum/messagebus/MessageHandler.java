package sh.harold.fulcrum.messagebus;

import java.util.concurrent.CompletableFuture;

/**
 * Handler interface for processing incoming request messages.
 * Implementations should return a response to the request.
 */
@FunctionalInterface
public interface MessageHandler {
    
    /**
     * Handle an incoming request message.
     * 
     * @param envelope The message envelope containing the request
     * @return CompletableFuture containing the response object
     */
    CompletableFuture<Object> handle(MessageEnvelope envelope);
}