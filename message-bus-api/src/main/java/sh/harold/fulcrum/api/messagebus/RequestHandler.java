package sh.harold.fulcrum.api.messagebus;

import java.util.concurrent.CompletionStage;

/**
 * Handles a request message and returns the payload to send back to the requester.
 */
@FunctionalInterface
public interface RequestHandler {
    CompletionStage<Object> handle(MessageEnvelope envelope);
}
