package sh.harold.fulcrum.dialogue;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Conversation orchestration service.
 */
public interface DialogueService {

    CompletionStage<DialogueStartResult> startConversation(DialogueStartRequest request);

    Optional<DialogueSession> activeSession(UUID playerId);

    Optional<DialogueSession> advance(UUID playerId);

    Optional<DialogueSession> cancel(UUID playerId, DialogueCancelReason reason);
}
