package sh.harold.fulcrum.host.worker;

public record WorkerJobId(String value) {
    public WorkerJobId {
        value = WorkerNames.requireNonBlank(value, "workerJobId");
    }
}
