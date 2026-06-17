package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeReceipt;

import java.util.Optional;

@FunctionalInterface
interface AuthorityWorkerPoller {
    Optional<AuthorityRuntimeReceipt> handleNext();
}
