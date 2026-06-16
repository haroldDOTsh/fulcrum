package sh.harold.fulcrum.control.queue;

import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record QueueRosterControlCommand<T extends QueueRosterCommand>(
        CommandEnvelope<T> envelope,
        PrincipalId authenticatedPrincipal,
        long fencingEpoch,
        Optional<Revision> expectedRevision,
        String payloadFingerprint,
        Instant receivedAt) {
    public QueueRosterControlCommand {
        envelope = Objects.requireNonNull(envelope, "envelope");
        authenticatedPrincipal = Objects.requireNonNull(authenticatedPrincipal, "authenticatedPrincipal");
        if (fencingEpoch < 0) {
            throw new IllegalArgumentException("fencingEpoch must be non-negative");
        }
        expectedRevision = expectedRevision == null ? Optional.empty() : expectedRevision;
        payloadFingerprint = ControlQueueStrings.requireNonBlank(payloadFingerprint, "payloadFingerprint");
        receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
