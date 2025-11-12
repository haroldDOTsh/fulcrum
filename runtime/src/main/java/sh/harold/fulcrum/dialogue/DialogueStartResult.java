package sh.harold.fulcrum.dialogue;

import java.time.Duration;

/**
 * Result of attempting to start a dialogue.
 */
public sealed interface DialogueStartResult permits DialogueStartResult.Started, DialogueStartResult.CooldownRejected {

    record Started(DialogueSession session) implements DialogueStartResult {
    }

    record CooldownRejected(Duration remaining) implements DialogueStartResult {
    }
}
