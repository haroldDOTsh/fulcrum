package sh.harold.fulcrum.sdk.authority;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeWorker;

import java.util.Objects;

public final class AuthorityBackendRuntimeGuard {
    private AuthorityBackendRuntimeGuard() {
    }

    public static AuthorityBackendRegistrationReceipt requireAdmitted(AuthorityBackendRegistrationReceipt receipt) {
        AuthorityBackendRegistrationReceipt checked = Objects.requireNonNull(receipt, "receipt");
        if (!checked.admitted()) {
            throw new IllegalStateException("authority backend registration denied: "
                    + checked.rejectionReason().map(Enum::name).orElse("unknown"));
        }
        return checked;
    }

    public static <S, C extends CommandPayload, R> GuardedAuthorityRuntimeWorker<S, C, R> guard(
            AuthorityBackendRegistrationReceipt receipt,
            AuthorityRuntimeWorker<S, C, R> worker) {
        return new GuardedAuthorityRuntimeWorker<>(requireAdmitted(receipt), worker);
    }
}
