package sh.harold.fulcrum.host.worker;

@FunctionalInterface
public interface WorkerJobHandler {
    WorkerJobResult handle(WorkerJobRequest request);
}
