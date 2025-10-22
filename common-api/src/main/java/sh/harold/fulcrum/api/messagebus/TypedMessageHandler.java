package sh.harold.fulcrum.api.messagebus;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Type-safe message handler that automatically deserializes messages to the correct type.
 *
 * @param <T> the message type this handler processes
 */
@FunctionalInterface
public interface TypedMessageHandler<T extends BaseMessage> {

    /**
     * Create a TypedMessageHandler from a Consumer that only needs the message.
     *
     * @param consumer the consumer function
     * @return a new TypedMessageHandler
     */
    static <T extends BaseMessage> TypedMessageHandler<T> of(Consumer<T> consumer) {
        return (message, envelope) -> consumer.accept(message);
    }

    /**
     * Create a TypedMessageHandler from a BiConsumer.
     *
     * @param consumer the bi-consumer function
     * @return a new TypedMessageHandler
     */
    static <T extends BaseMessage> TypedMessageHandler<T> of(BiConsumer<T, MessageEnvelope> consumer) {
        return consumer::accept;
    }

    /**
     * Handle a typed message.
     *
     * @param message  the deserialized message
     * @param envelope the message envelope with metadata
     */
    void handle(T message, MessageEnvelope envelope);
}