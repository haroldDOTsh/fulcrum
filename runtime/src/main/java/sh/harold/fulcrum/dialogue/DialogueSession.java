package sh.harold.fulcrum.dialogue;

import java.util.UUID;

/**
 * Active dialogue session handle.
 */
public interface DialogueSession {

    UUID sessionId();

    UUID playerId();

    Dialogue dialogue();

    DialogueContext context();

    int nextStepIndex();

    int totalSteps();

    boolean isComplete();

    /**
     * Sends the next line if available.
     *
     * @return true if another line was sent, false if already completed
     */
    boolean advance();

    void cancel(DialogueCancelReason reason);
}
