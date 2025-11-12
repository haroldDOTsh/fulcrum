package sh.harold.fulcrum.dialogue;

import java.util.Objects;

/**
 * View into the current dialogue progress (step/total).
 */
public record DialogueProgress(DialogueContext context, int stepIndex, int totalSteps) {

    public DialogueProgress {
        Objects.requireNonNull(context, "context");
        if (totalSteps < 0) {
            throw new IllegalArgumentException("totalSteps must be >= 0");
        }
        if (stepIndex < 0) {
            throw new IllegalArgumentException("stepIndex must be >= 0");
        }
        if (stepIndex > totalSteps) {
            throw new IllegalArgumentException("stepIndex cannot exceed totalSteps");
        }
    }

    public int stepNumber() {
        return stepIndex + 1;
    }
}
