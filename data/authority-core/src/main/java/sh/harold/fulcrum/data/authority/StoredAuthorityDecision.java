package sh.harold.fulcrum.data.authority;

import java.util.Objects;

public record StoredAuthorityDecision<S, R>(
        String payloadFingerprint,
        AuthorityDecision<S, R> decision) {
    public StoredAuthorityDecision {
        payloadFingerprint = requireNonBlank(payloadFingerprint, "payloadFingerprint");
        decision = Objects.requireNonNull(decision, "decision");
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
