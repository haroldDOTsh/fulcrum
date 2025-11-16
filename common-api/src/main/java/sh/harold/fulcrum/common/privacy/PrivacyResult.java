package sh.harold.fulcrum.common.privacy;

import java.util.Optional;

/**
 * Outcome returned by the privacy gate.
 *
 * @param allowed whether the action is permitted
 * @param reason  optional user-facing reason when denied
 */
public record PrivacyResult(boolean allowed, String reason) {

    public static PrivacyResult allow() {
        return new PrivacyResult(true, null);
    }

    public static PrivacyResult deny(String reason) {
        return new PrivacyResult(false, reason);
    }

    public Optional<String> denialReason() {
        return Optional.ofNullable(reason);
    }
}
