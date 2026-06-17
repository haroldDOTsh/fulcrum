package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.adapters.objectstorage.ObjectStorageAdapter;
import sh.harold.fulcrum.host.worker.WorkerAgentRuntime;
import sh.harold.fulcrum.host.worker.WorkerJobKind;
import sh.harold.fulcrum.host.worker.WorkerJobReceipt;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class WorkerJobCatalog {
    private final WorkerAgentRuntime runtime;
    private final LocalWorkerRuntimeBindings bindings;
    private final WorkerJobObjectHandler handler;
    private final Clock clock;

    WorkerJobCatalog(
            WorkerAgentRuntime runtime,
            LocalWorkerRuntimeBindings bindings,
            ObjectStorageAdapter objectStorage,
            Clock clock) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.handler = new WorkerJobObjectHandler(objectStorage);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    static List<String> workerDomains() {
        return Arrays.stream(WorkerJobKind.values())
                .map(WorkerJobObjectHandler::workerDomain)
                .toList();
    }

    List<WorkerJobBinding> workerBindings() {
        List<WorkerJobBinding> workers = new ArrayList<>();
        for (WorkerJobKind kind : WorkerJobKind.values()) {
            workers.add(new WorkerJobBinding(WorkerJobObjectHandler.workerDomain(kind), () -> handleNext(kind)));
        }
        return List.copyOf(workers);
    }

    private Optional<WorkerJobReceipt> handleNext(WorkerJobKind kind) {
        return bindings.poll(kind)
                .map(request -> {
                    WorkerJobReceipt receipt = runtime.handle(request, handler, clock.instant());
                    bindings.record(receipt);
                    return receipt;
                });
    }
}
