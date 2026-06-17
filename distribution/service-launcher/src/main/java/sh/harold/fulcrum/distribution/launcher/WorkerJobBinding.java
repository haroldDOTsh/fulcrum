package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.host.worker.WorkerAgentRuntime;
import sh.harold.fulcrum.host.worker.WorkerJobHandler;
import sh.harold.fulcrum.host.worker.WorkerJobReceipt;
import sh.harold.fulcrum.host.worker.WorkerJobRequest;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

record WorkerJobBinding(
        String workerDomain,
        WorkerJobPoller poller) {
    WorkerJobBinding {
        workerDomain = requireNonBlank(workerDomain, "workerDomain");
        poller = Objects.requireNonNull(poller, "poller");
    }

    Optional<WorkerJobReceipt> handleNext() {
        return poller.handleNext();
    }

    static WorkerJobBinding fromRuntime(
            String workerDomain,
            WorkerAgentRuntime runtime,
            Supplier<Optional<WorkerJobRequest>> source,
            WorkerJobHandler handler,
            Clock clock) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(clock, "clock");
        return new WorkerJobBinding(workerDomain, () -> source.get()
                .map(request -> runtime.handle(request, handler, clock.instant())));
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
