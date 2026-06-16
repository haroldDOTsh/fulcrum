package sh.harold.fulcrum.data.authority;

import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record AuthorityCommand<T extends CommandPayload>(
        CommandEnvelope<T> envelope,
        PrincipalId authenticatedPrincipal,
        long fencingEpoch,
        Optional<Revision> expectedRevision,
        String payloadFingerprint,
        Instant receivedAt) {
    public AuthorityCommand {
        envelope = Objects.requireNonNull(envelope, "envelope");
        authenticatedPrincipal = Objects.requireNonNull(authenticatedPrincipal, "authenticatedPrincipal");
        if (fencingEpoch < 0) {
            throw new IllegalArgumentException("fencingEpoch must be non-negative");
        }
        expectedRevision = expectedRevision == null ? Optional.empty() : expectedRevision;
        payloadFingerprint = requireNonBlank(payloadFingerprint, "payloadFingerprint");
        receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
