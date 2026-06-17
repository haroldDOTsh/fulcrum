package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.host.worker.WorkerJobReceipt;

import java.util.Optional;

@FunctionalInterface
interface WorkerJobPoller {
    Optional<WorkerJobReceipt> handleNext();
}
