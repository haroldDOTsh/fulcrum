package sh.harold.fulcrum.sdk.authority;

import sh.harold.fulcrum.api.contract.PrincipalId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record AuthorityBackendDeregistrationRequest(
        String receiptId,
        Optional<PrincipalId> principalId,
        String reason,
        Instant requestedAt) {
    public AuthorityBackendDeregistrationRequest {
        receiptId = AuthoritySdkNames.requireNonBlank(receiptId, "receiptId");
        principalId = principalId == null ? Optional.empty() : principalId;
        reason = AuthoritySdkNames.requireNonBlank(reason, "reason");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
