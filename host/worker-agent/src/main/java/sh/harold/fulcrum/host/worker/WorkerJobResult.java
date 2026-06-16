package sh.harold.fulcrum.host.worker;

public record WorkerJobResult(
        String resultCode,
        String outputRef) {
    public WorkerJobResult {
        resultCode = WorkerNames.requireNonBlank(resultCode, "resultCode");
        outputRef = WorkerNames.requireNonBlank(outputRef, "outputRef");
    }
}
