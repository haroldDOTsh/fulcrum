package sh.harold.fulcrum.sdk.authority;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeReceipt;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeWorker;

import java.util.Objects;
import java.util.Optional;

public final class GuardedAuthorityRuntimeWorker<S, C extends CommandPayload, R> {
    private final AuthorityBackendRegistrationReceipt registrationReceipt;
    private final AuthorityRuntimeWorker<S, C, R> worker;

    GuardedAuthorityRuntimeWorker(
            AuthorityBackendRegistrationReceipt registrationReceipt,
            AuthorityRuntimeWorker<S, C, R> worker) {
        this.registrationReceipt = Objects.requireNonNull(registrationReceipt, "registrationReceipt");
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    public AuthorityBackendRegistrationReceipt registrationReceipt() {
        return registrationReceipt;
    }

    public Optional<AuthorityRuntimeReceipt> handleNext() {
        return worker.handleNext();
    }
}
