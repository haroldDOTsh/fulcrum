package sh.harold.fulcrum.sdk.authority;

import sh.harold.fulcrum.api.contract.PrincipalId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record AuthorityBackendDeregistrationReceipt(
        AuthorityBackendDeregistrationStatus status,
        String registrationReceiptId,
        Optional<PrincipalId> principalId,
        String reason,
        Instant issuedAt,
        String receiptId,
        String signature) {
    public AuthorityBackendDeregistrationReceipt {
        status = Objects.requireNonNull(status, "status");
        registrationReceiptId = AuthoritySdkNames.requireNonBlank(registrationReceiptId, "registrationReceiptId");
        principalId = principalId == null ? Optional.empty() : principalId;
        reason = AuthoritySdkNames.requireNonBlank(reason, "reason");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        receiptId = AuthoritySdkNames.requireNonBlank(receiptId, "receiptId");
        signature = AuthoritySdkNames.requireNonBlank(signature, "signature");
    }

    public static AuthorityBackendDeregistrationReceipt unsupported(AuthorityBackendDeregistrationRequest request) {
        AuthorityBackendDeregistrationRequest checked = Objects.requireNonNull(request, "request");
        String receiptId = "backend-deregistration-"
                + AuthorityBackendDescriptorDigests.sha256Hex(
                        checked.receiptId() + "|unsupported|" + checked.requestedAt())
                .substring(0, 16);
        String signature = AuthorityBackendDescriptorDigests.sha256Hex(
                "status=" + AuthorityBackendDeregistrationStatus.UNSUPPORTED
                        + "|registrationReceiptId=" + checked.receiptId()
                        + "|principalId=" + checked.principalId().map(PrincipalId::value).orElse("none")
                        + "|reason=registration-deregistration-unsupported"
                        + "|issuedAt=" + checked.requestedAt()
                        + "|receiptId=" + receiptId);
        return new AuthorityBackendDeregistrationReceipt(
                AuthorityBackendDeregistrationStatus.UNSUPPORTED,
                checked.receiptId(),
                checked.principalId(),
                "registration-deregistration-unsupported",
                checked.requestedAt(),
                receiptId,
                signature);
    }

    public boolean tombstoned() {
        return status == AuthorityBackendDeregistrationStatus.TOMBSTONED;
    }
}
