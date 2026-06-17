package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.host.worker.WorkerJobKind;
import sh.harold.fulcrum.host.worker.WorkerJobReceipt;
import sh.harold.fulcrum.host.worker.WorkerJobRequest;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

final class LocalWorkerRuntimeBindings {
    private final Map<WorkerJobKind, Queue<WorkerJobRequest>> jobQueues = new ConcurrentHashMap<>();
    private final Map<WorkerJobKind, Queue<WorkerJobReceipt>> receipts = new ConcurrentHashMap<>();

    void enqueue(WorkerJobRequest request) {
        jobQueues.computeIfAbsent(
                        Objects.requireNonNull(request, "request").jobKind(),
                        ignored -> new ConcurrentLinkedQueue<>())
                .add(request);
    }

    Optional<WorkerJobRequest> poll(WorkerJobKind jobKind) {
        Queue<WorkerJobRequest> queue = jobQueues.get(Objects.requireNonNull(jobKind, "jobKind"));
        return queue == null ? Optional.empty() : Optional.ofNullable(queue.poll());
    }

    void record(WorkerJobReceipt receipt) {
        receipts.computeIfAbsent(
                        Objects.requireNonNull(receipt, "receipt").jobKind(),
                        ignored -> new ConcurrentLinkedQueue<>())
                .add(receipt);
    }

    List<WorkerJobReceipt> receipts(WorkerJobKind jobKind) {
        Queue<WorkerJobReceipt> queue = receipts.get(Objects.requireNonNull(jobKind, "jobKind"));
        return queue == null ? List.of() : List.copyOf(queue);
    }
}
