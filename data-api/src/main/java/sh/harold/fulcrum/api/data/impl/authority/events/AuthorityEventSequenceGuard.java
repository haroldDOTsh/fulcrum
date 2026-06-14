package sh.harold.fulcrum.api.data.impl.authority.events;

import java.util.Objects;

/**
 * Verifies that projection consumers only advance through aggregate revisions in order.
 */
final class AuthorityEventSequenceGuard {
    private AuthorityEventSequenceGuard() {
    }

    static SequenceVerdict verify(
        String projectionName,
        AuthorityEventEnvelope event,
        Long previousRevision
    ) {
        Objects.requireNonNull(event, "event");
        String projection = projectionName == null || projectionName.isBlank()
            ? "unknown"
            : projectionName.trim();
        if (event.revision() <= 0L) {
            return SequenceVerdict.quarantine(
                "Projection " + projection + " cannot dispatch non-positive revision "
                    + event.revision() + " for " + event.aggregateScope()
            );
        }
        if (previousRevision == null) {
            if (event.revision() == 1L) {
                return SequenceVerdict.accept();
            }
            return SequenceVerdict.quarantine(
                "Projection " + projection + " has no checkpoint for " + event.aggregateScope()
                    + " but next event revision is " + event.revision()
            );
        }
        long expectedRevision = previousRevision + 1L;
        if (event.revision() == expectedRevision) {
            return SequenceVerdict.accept();
        }
        if (event.revision() <= previousRevision) {
            return SequenceVerdict.quarantine(
                "Projection " + projection + " already checkpointed " + event.aggregateScope()
                    + " at revision " + previousRevision + " but saw revision " + event.revision()
            );
        }
        return SequenceVerdict.quarantine(
            "Projection " + projection + " expected " + event.aggregateScope()
                + " revision " + expectedRevision + " but saw revision " + event.revision()
        );
    }

    record SequenceVerdict(boolean accepted, String message) {
        static SequenceVerdict accept() {
            return new SequenceVerdict(true, "");
        }

        static SequenceVerdict quarantine(String message) {
            return new SequenceVerdict(false, message);
        }
    }
}
