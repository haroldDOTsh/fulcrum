package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeReceipt;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeWorker;

import java.util.Objects;
import java.util.Optional;

record AuthorityWorkerBinding(
        String authorityDomain,
        AuthorityWorkerPoller poller) {
    AuthorityWorkerBinding {
        authorityDomain = requireNonBlank(authorityDomain, "authorityDomain");
        poller = Objects.requireNonNull(poller, "poller");
    }

    Optional<AuthorityRuntimeReceipt> handleNext() {
        return poller.handleNext();
    }

    static <S, C extends CommandPayload, R> AuthorityWorkerBinding fromWorker(
            String authorityDomain,
            AuthorityRuntimeWorker<S, C, R> worker) {
        return new AuthorityWorkerBinding(authorityDomain, Objects.requireNonNull(worker, "worker")::handleNext);
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
