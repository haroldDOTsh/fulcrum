package sh.harold.fulcrum.sdk.authoring;

import java.util.Objects;

public record AuthorBundlePreflightRefusal(
        AuthorBundlePreflightRefusalCode code,
        String detail) {
    public AuthorBundlePreflightRefusal {
        code = Objects.requireNonNull(code, "code");
        detail = AuthoringNames.requireNonBlank(detail, "detail");
    }
}
