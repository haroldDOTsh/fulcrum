package sh.harold.fulcrum.control.fault;

import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record FaultControlCommand<T extends FaultCommand>(
        CommandEnvelope<T> envelope,
        PrincipalId authenticatedPrincipal,
        long fencingEpoch,
        Optional<Revision> expectedRevision,
        String payloadFingerprint,
        Instant receivedAt) {
    public FaultControlCommand {
        envelope = Objects.requireNonNull(envelope, "envelope");
        authenticatedPrincipal = Objects.requireNonNull(authenticatedPrincipal, "authenticatedPrincipal");
        if (fencingEpoch < 0) {
            throw new IllegalArgumentException("fencingEpoch must be non-negative");
        }
        expectedRevision = expectedRevision == null ? Optional.empty() : expectedRevision;
        payloadFingerprint = ControlFaultStrings.requireNonBlank(payloadFingerprint, "payloadFingerprint");
        receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
